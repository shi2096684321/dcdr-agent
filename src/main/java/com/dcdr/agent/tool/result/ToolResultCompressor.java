package com.dcdr.agent.tool.result;

import com.dcdr.agent.agent.governance.CostTracker;
import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.agent.memory.TokenEstimator;
import com.dcdr.agent.config.AgentProperties;
import com.dcdr.agent.dto.ChatRequest;
import com.dcdr.agent.dto.ChatResponse;
import com.dcdr.agent.model.provider.ChatModelProvider;
import com.dcdr.agent.model.registry.ModelRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * V3.1 F-47 工具结果体积治理 —— <b>唯一收口点</b>。
 * <p>
 * 设计取舍: 不在"回灌模型 / 前端下发 / 落库"三处各截一次, 而在<b>工具结果产出处</b>压缩一次,
 * 三处共用同一份结果。理由: 三处各截必然漂移(V3.0 的现状就是只有 2 处截断 —— 前端下发与
 * assistant 正文拼接都漏了), 而单点收口让"口径一致"成为结构性保证而非纪律要求。
 * <p>
 * 压缩优先级(从上到下逐级降级, 每级都只在必要时触发):
 * <ol>
 *   <li><b>落盘</b>({@link ToolResultSpillStore}): 超过 {@code spill.threshold} 时全文落盘,
 *       只回灌"路径 + 规模 + 前 N 行", 模型可用 {@code tool_result_read} 分页取全文。
 *       放在摘要之前 —— 落盘后回灌体积已很小, 再做一次模型调用纯属浪费</li>
 *   <li><b>LLM 摘要</b>: 未落盘且超过 {@code summary.threshold} 时做一次摘要;
 *       失败/异常一律回退硬截断(压缩失败绝不能影响主链路)</li>
 *   <li><b>头尾保留硬截断</b>: 字符上限({@code max-length}) + token 预算({@code max-token-budget})
 *       双约束。保留头部 <b>与尾部</b> —— 只保头部会丢掉结论、总计行与表格末尾(V3.0 的固有缺陷)</li>
 * </ol>
 * 默认值与 V3.0 现值一致({@code max-length=6000}), 升级后"上限"行为不变。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolResultCompressor {

    /** 摘要调用计入用量的来源标记(进 llm_usage_logs.source) */
    private static final String SUMMARY_SOURCE = "tool_summary";

    /** 尾部占比上限: 尾部保留过多会让头部信息(通常更关键)被挤掉 */
    private static final double MAX_TAIL_RATIO = 0.5;

    /** 收缩重试时的下限, 避免退化成空串 */
    private static final int MIN_ALLOWANCE = 200;

    /** token 预算比字符上限更紧时的收缩次数上限(按比例收缩 + 复验, 不做逐字符二分) */
    private static final int SHRINK_RETRY = 4;

    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是工具结果压缩器。把下面的工具返回内容压缩成结构化摘要, 要求:
            1. 保留全部事实性信息: 数字、日期、名称、URL、代码标识符、结论与判断
            2. 保留列表/表格的结构要点; 丢弃广告、导航、重复内容与寒暄
            3. 不做推理, 不补充原文没有的信息, 不使用"可能/大概"等模糊表述
            4. 直接输出摘要正文, 不要前言、不要结尾说明
            5. 使用与原文相同的语言输出""";

    private final AgentProperties properties;
    private final TokenEstimator tokenEstimator;
    private final ToolResultSpillStore spillStore;
    private final ModelRegistry modelRegistry;
    private final CostTracker costTracker;

    /**
     * 压缩结果。
     *
     * @param text          压缩后的文本(三处消费点共用这一份)
     * @param originalChars 原始字符数
     * @param keptChars     压缩后字符数
     * @param originalTokens 原始 token 估算
     * @param keptTokens    压缩后 token 估算
     * @param truncated     是否发生硬截断
     * @param summarized    是否发生 LLM 摘要
     * @param spilled       是否落盘
     */
    public record Compressed(String text, int originalChars, int keptChars,
                             int originalTokens, int keptTokens,
                             boolean truncated, boolean summarized, boolean spilled) {

        /** 内容是否发生过任何形式的压缩(截断/摘要/落盘), 供统计与日志使用 */
        public boolean changed() {
            return truncated || summarized || spilled;
        }
    }

    public Compressed compress(String toolName, String raw) {
        return compress(toolName, raw, 0);
    }

    /**
     * 压缩单条工具结果。
     *
     * @param maxCharsOverride 本轮单条字符上限(&gt;0 时生效, 取与配置值的较小者),
     *                         由 {@link #allowanceForRound} 依据窗口预算给出; 0 表示用配置默认
     */
    public Compressed compress(String toolName, String raw, int maxCharsOverride) {
        if (raw == null || raw.isEmpty()) {
            return new Compressed("", 0, 0, 0, 0, false, false, false);
        }
        AgentProperties.ResultConfig cfg = cfg();
        int originalChars = raw.length();
        int originalTokens = tokenEstimator.estimate(raw);
        int maxChars = maxCharsOverride > 0
                ? Math.min(maxCharsOverride, cfg.getMaxLength()) : cfg.getMaxLength();
        int tokenBudget = cfg.getMaxTokenBudget();
        if (originalChars <= maxChars && (tokenBudget <= 0 || originalTokens <= tokenBudget)) {
            return new Compressed(raw, originalChars, originalChars, originalTokens, originalTokens,
                    false, false, false);
        }

        String text = raw;
        boolean spilled = false;
        boolean summarized = false;

        // ① 落盘(极端超长)
        if (spillStore.enabled() && originalChars > cfg.getSpill().getThreshold()) {
            String path = spillStore.spill(toolName, raw);
            if (path != null) {
                text = spillHint(path, raw, cfg.getSpill().getHeadLines());
                spilled = true;
            }
        }

        // ② LLM 摘要(中间带: 未到落盘阈值但已远超上下文可承受的体积)
        if (!spilled && cfg.getSummary().isEnabled() && text.length() > cfg.getSummary().getThreshold()) {
            String summary = summarize(toolName, text);
            if (summary != null && !summary.isBlank()) {
                text = summary;
                summarized = true;
            }
        }

        // ③ 硬截断(头尾保留 + token 预算)
        String beforeClip = text;
        text = clipWithinBudget(text, maxChars, tokenBudget, cfg.getKeepTailRatio());
        boolean truncated = !text.equals(beforeClip);

        Compressed result = new Compressed(text, originalChars, text.length(), originalTokens,
                tokenEstimator.estimate(text), truncated, summarized, spilled);
        if (result.changed()) {
            // F-47.8 截断统计: 线上排查的可见入口(具体数值也会以"已截断"标记留在回灌文本里, 随调用链快照归档)
            log.warn("工具结果已压缩: tool={} {}字符/{}token -> {}字符/{}token (截断={}, 摘要={}, 落盘={})",
                    toolName, originalChars, originalTokens, result.keptChars(), result.keptTokens(),
                    truncated, summarized, spilled);
        }
        return result;
    }

    /**
     * 依据「回灌窗口内工具结果的 token 总量」给出本轮单条结果的字符上限。
     * <p>
     * 为什么需要: 单条已限 6000 字符, 但工具循环最多 10 轮、窗口保留若干条消息,
     * 若不做总量约束, 多轮工具结果的合计仍可能远超模型窗口(中文场景 1 字符 ≈ 1 token)。
     * 策略取"两级降级"而不是按剩余预算比例分配 —— 后者要在 token 与字符两种量纲间换算,
     * 近似会引入难解释的抖动; 触顶后统一收紧到 {@code window-floor-length} 更可预测。
     *
     * @return 0 表示不额外收紧(用配置的 max-length)
     */
    public int allowanceForRound(List<Message> history) {
        AgentProperties.ResultConfig cfg = cfg();
        int budget = cfg.getWindowTokenBudget();
        if (budget <= 0 || history == null || history.isEmpty()) return 0;
        int used = 0;
        for (Message m : history) {
            if ("tool".equals(m.getRole())) used += tokenEstimator.estimate(m.getContent());
        }
        if (used < budget) return 0;
        log.warn("工具结果窗口预算已用尽({}/{} token), 本轮压缩上限收紧到 {} 字符",
                used, budget, cfg.getWindowFloorLength());
        return cfg.getWindowFloorLength();
    }

    // ===== 内部 =====

    private AgentProperties.ResultConfig cfg() {
        return properties.getTools().getResult();
    }

    /** 落盘提示: 路径 + 规模 + 前 N 行预览(预览过长时由后续硬截断兜住) */
    private static String spillHint(String path, String raw, int headLines) {
        String[] lines = raw.split("\n", -1);
        StringBuilder sb = new StringBuilder(512);
        sb.append("[工具结果已落盘, 未全文回灌] 原始 ").append(raw.length()).append(" 字符 / ")
          .append(lines.length).append(" 行; 路径: ").append(path).append('\n');
        sb.append("需要完整内容时调用 tool_result_read 工具分页读取 (参数 path=\"")
          .append(path).append("\", 可选 offset/limit)。\n");
        sb.append("--- 前 ").append(Math.min(headLines, lines.length)).append(" 行预览 ---\n");
        for (int i = 0; i < Math.min(headLines, lines.length); i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    /** 字符上限 + token 预算双约束下的截断(头尾保留) */
    private String clipWithinBudget(String text, int maxChars, int tokenBudget, double tailRatio) {
        boolean overChars = text.length() > maxChars;
        boolean overTokens = tokenBudget > 0 && tokenEstimator.estimate(text) > tokenBudget;
        if (!overChars && !overTokens) return text;

        int allowance = maxChars;
        String candidate = clipHeadTail(text, allowance, tailRatio);
        // token 预算比字符上限更紧: 按「预算 / 实际估算」的比例收缩并复验。
        // 用比例而非固定系数是关键 —— 固定系数(如 0.6)在预算远小于上限时迭代 4 轮仍收敛不到,
        // 会静默返回一个超预算的结果, 等于预算失效。
        for (int i = 0; i < SHRINK_RETRY && tokenBudget > 0; i++) {
            int estimated = tokenEstimator.estimate(candidate);
            if (estimated <= tokenBudget) break;
            double factor = Math.min(0.9, (double) tokenBudget / estimated);
            allowance = Math.max(MIN_ALLOWANCE, (int) (allowance * factor));
            candidate = clipHeadTail(text, allowance, tailRatio);
            if (allowance <= MIN_ALLOWANCE) break;
        }
        return candidate;
    }

    /** 保留头部 + 标记 + 尾部(标记不计入 limit, 与 V3.0 "上限 + 标记" 的语义一致) */
    private static String clipHeadTail(String text, int limit, double tailRatio) {
        int total = text.length();
        if (total <= limit) return text;
        double ratio = Math.max(0d, Math.min(tailRatio, MAX_TAIL_RATIO));
        int tail = ratio <= 0 ? 0 : Math.max(1, (int) (limit * ratio));
        int head = Math.max(1, limit - tail);
        if (head + tail >= total) return text;
        String marker = "\n…(工具结果过长已截断: 原始 " + total + " 字符, 保留头 " + head
                + " + 尾 " + tail + " 字符)…\n";
        return text.substring(0, head) + marker + (tail > 0 ? text.substring(total - tail) : "");
    }

    /**
     * LLM 摘要降级。任何异常都返回 {@code null}, 由调用方回退硬截断 ——
     * 压缩是"尽力而为"的优化, 绝不能因为模型不可用而让工具调用失败。
     */
    private String summarize(String toolName, String text) {
        try {
            ChatModelProvider provider = resolveSummaryProvider();
            ChatRequest request = new ChatRequest();
            request.setSessionId(ToolResultContextHolder.sessionId());
            request.setMessage(text);
            request.setStream(false);
            ChatResponse response = provider.invoke(request, List.of(Message.system(SUMMARY_SYSTEM_PROMPT)));
            if (response == null || response.getContent() == null || response.getContent().isBlank()) {
                return null;
            }
            // 摘要调用同样是真实 LLM 开销, 计入用量 —— 否则 F-39/F-44 的成本报表会出现"看不见的费用"
            if (response.getTokenUsage() != null) {
                costTracker.record(ToolResultContextHolder.userId(), ToolResultContextHolder.sessionId(),
                        null, SUMMARY_SOURCE, provider.getName(), response.getTokenUsage());
            }
            return "[工具结果摘要(原文 " + text.length() + " 字符)]\n" + response.getContent().trim();
        } catch (Exception e) {
            log.warn("工具结果摘要失败, 回退硬截断: tool={}, {}", toolName, e.getMessage());
            return null;
        }
    }

    private ChatModelProvider resolveSummaryProvider() {
        String model = cfg().getSummary().getModel();
        return (model == null || model.isBlank())
                ? modelRegistry.getDefaultChatProvider()
                : modelRegistry.getChatProvider(model);
    }
}
