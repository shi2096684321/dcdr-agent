package com.dcdr.agent.common.log;

import com.dcdr.agent.rag.RagSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单轮对话的 LLM 调用链追踪。
 * <p>
 * 一次用户请求(流式/同步)触发的全部模型调用(智能路由 / agentic 每轮生成 / chatSync)与
 * 工具执行步骤, 以统一格式输出为连续、可 grep 的 INFO 日志行, 请求结束时输出汇总链路。
 * 解决"一轮对话内 LLM 调用次数多、日志散落各处难以拼出完整链路"的问题。
 * <p>
 * 使用方式: 由 Controller 在请求入口 {@link #begin()} 创建, 显式传递给
 * IntentRouter / AgentOrchestrator(响应式线程切换频繁, 不用 ThreadLocal);
 * 各记录点在调用边界打点, 结束由管线 doFinally 触发 {@link #summary}(幂等, 只输出一次)。
 * <p>
 * 日志形如:
 * <pre>
 * [llm-trace] turn=a3f2c1 #1 LLM routing model=qwen-turbo tokens=856/45 312ms
 * [llm-trace] turn=a3f2c1 TOOL image_generation 61.2s
 * [llm-trace] turn=a3f2c1 #2 LLM round-2 model=qwen-max tokens=3120/312 4.2s
 * [llm-trace] turn=a3f2c1 SUMMARY 结束=ON_COMPLETE LLM=2次 工具=1步 tokens(入/出)=3976/357 总耗时=71.3s 链路=routing → round-1 → tool[image_generation] → round-2
 * </pre>
 * grep 关键字 {@code llm-trace} 看全部链路, grep {@code turn=a3f2c1} 看单轮完整链路。
 */
public final class LlmCallTrace {

    private static final Logger log = LoggerFactory.getLogger(LlmCallTrace.class);

    /** 本轮短 ID: 6 位十六进制, 区分并发交织的多个会话日志 */
    private final String turnId;
    /** 请求入口时间(汇总总耗时用, 含路由与全部轮次) */
    private final long startNanos = System.nanoTime();
    /** 链路步骤名(汇总时还原完整顺序) */
    private final List<String> chain = new ArrayList<>();
    /** 完整步骤事件(供前端调用链面板随消息持久化与 SSE 实时下发) */
    private final List<java.util.Map<String, Object>> steps = new ArrayList<>();
    /** SSE 实时下发回调(注册时回放缓存事件 —— 路由步骤发生在编排 sink 建立之前, 靠回放补发) */
    private volatile java.util.function.Consumer<java.util.Map<String, Object>> emitter;
    /** LLM 调用序号 */
    private int llmCalls = 0;
    /** 工具步骤数 */
    private int toolSteps = 0;
    /** RAG 检索次数 */
    private int ragCount = 0;
    /** 长期记忆召回次数 */
    private int memoryCount = 0;
    /** 引用忠实度校验次数(每次回答收尾校验一次) */
    private int citationCount = 0;
    /** 本次请求所用 system prompt 的指纹(短哈希); null 表示尚未记录 */
    private String promptFingerprint;
    /** token 累计(入/出), 缺 usage 的调用不计 */
    private long totalIn;
    private long totalOut;
    /** 汇总只输出一次(流式管线 doFinally 与 Controller doFinally 可能都触发) */
    private final AtomicBoolean summaryLogged = new AtomicBoolean(false);
    /** 汇总完成回调(可选): 由 Spring 侧注册, 把调用链快照持久化到独立日志表供前端独立查看 */
    private volatile CallSummaryListener summaryListener;

    private LlmCallTrace() {
        this.turnId = String.format("%06x", ThreadLocalRandom.current().nextInt(0x1000000));
    }

    /** 汇总数据(供持久化到 llm_call_logs 独立日志表) */
    public record CallSummary(String turnId, long totalMs, int llmCalls, int toolSteps,
                              long totalIn, long totalOut, String endSignal, String chain,
                              List<java.util.Map<String, Object>> steps) {}

    /** 汇总完成回调 */
    @FunctionalInterface
    public interface CallSummaryListener {
        void onSummary(CallSummary summary);
    }

    /**
     * 注册汇总回调: 请求结束后(无论正常/取消/出错)回调一次, 供持久化。
     * 由持有 userId/sessionId/用户消息 的调用方(如 ChatController)注册, 以便组装完整日志记录。
     */
    public void onSummary(CallSummaryListener listener) {
        this.summaryListener = listener;
    }

    /** 请求入口创建追踪器 */
    public static LlmCallTrace begin() {
        return new LlmCallTrace();
    }

    /** 步骤字段: 本轮模型输出预览(调用链详情页展示"这一轮答了什么") */
    public static final String PARAM_OUTPUT_PREVIEW = "outputPreview";
    /** 步骤字段: 本轮模型输出原始字符数(预览被截断后仍可判断完整长度) */
    public static final String PARAM_OUTPUT_CHARS = "outputChars";
    /** 输出预览截断长度(与工具参数/结果摘要的 500 保持一致) */
    public static final int OUTPUT_PREVIEW_LIMIT = 500;

    /**
     * 把本轮模型输出写入步骤参数(截断留预览 + 保留原始字符数)。
     * <p>
     * 由各调用点(流式各轮 / chatSync / 路由判断)在记录步骤前调用, 键值随
     * {@code recordLlm} 里的 {@code step.putAll(params)} 落进 {@code llm_call_logs.steps_json}。
     * 空输出不写字段(纯工具轮的正文为空), 避免详情页出现空块。
     * <p>
     * 落在 params 而非新增表列的原因: {@code steps_json} 是 TEXT 天然可扩展, 且列表接口
     * 已有"剔除重字段"机制, 无需 DDL 变更即可控制列表体积。
     *
     * @param params 步骤参数(为 null 时不写, 允许调用点无参数场景)
     * @param output 本轮已产出的正文；异常/中断时传入已产出的部分(可能为 null)
     */
    public static void putOutput(java.util.Map<String, Object> params, CharSequence output) {
        if (params == null || output == null) return;
        String text = output.toString();
        if (text.isEmpty()) return;
        params.put(PARAM_OUTPUT_CHARS, text.length());
        params.put(PARAM_OUTPUT_PREVIEW, text.length() > OUTPUT_PREVIEW_LIMIT
                ? text.substring(0, OUTPUT_PREVIEW_LIMIT) + "…(截断)" : text);
    }

    /** 记录一次 LLM 调用完成(purpose 如 routing / round-1 / round-2 / chatSync) */
    public synchronized void recordLlm(String purpose, String model, Integer inTokens, Integer outTokens,
                                        long costMs, boolean interrupted) {
        recordLlm(purpose, model, inTokens, outTokens, costMs, interrupted, null);
    }

    /**
     * 记录一次 LLM 调用完成(带请求参数明细, 供调用链详情页展示)。
     *
     * @param params 请求参数键值(如 temperature / messageCount / toolSpecCount / finishReason), 可为 null
     */
    public synchronized void recordLlm(String purpose, String model, Integer inTokens, Integer outTokens,
                                        long costMs, boolean interrupted, java.util.Map<String, Object> params) {
        llmCalls++;
        if (inTokens != null) totalIn += inTokens;
        if (outTokens != null) totalOut += outTokens;
        chain.add(purpose);
        java.util.Map<String, Object> step = new java.util.LinkedHashMap<>();
        step.put("kind", "llm");
        step.put("seq", llmCalls);
        step.put("purpose", purpose);
        step.put("model", model);
        step.put("inTokens", inTokens);
        step.put("outTokens", outTokens);
        step.put("costMs", costMs);
        step.put("interrupted", interrupted);
        if (params != null && !params.isEmpty()) {
            step.putAll(params);
        }
        steps.add(step);
        emit(step);
        log.info("[llm-trace] turn={} #{} LLM {} model={} tokens={}/{} {}{}",
                turnId, llmCalls, purpose, model,
                inTokens == null ? "-" : inTokens, outTokens == null ? "-" : outTokens,
                fmtMs(costMs), interrupted ? " [中断]" : "");
    }

    /** 记录一次工具执行步骤(非 LLM 调用, 但占据链路时延, 是理解多轮成因的关键) */
    public synchronized void recordTool(String tools, long costMs, boolean interrupted) {
        recordTool(tools, costMs, interrupted, null);
    }

    /**
     * 记录一次工具执行步骤(带调用明细, 供调用链详情页展示每个工具的参数与结果摘要)。
     *
     * @param calls 调用明细列表(元素: name / args / resultPreview), 可为 null
     */
    public synchronized void recordTool(String tools, long costMs, boolean interrupted,
                                         java.util.List<java.util.Map<String, Object>> calls) {
        toolSteps++;
        chain.add("tool[" + tools + "]");
        java.util.Map<String, Object> step = new java.util.LinkedHashMap<>();
        step.put("kind", "tool");
        step.put("seq", toolSteps);
        step.put("tools", tools);
        step.put("costMs", costMs);
        step.put("interrupted", interrupted);
        if (calls != null && !calls.isEmpty()) {
            step.put("calls", calls);
        }
        steps.add(step);
        emit(step);
        log.info("[llm-trace] turn={} TOOL {} {}{}", turnId, tools, fmtMs(costMs), interrupted ? " [中断]" : "");
    }

    /**
     * 记录一次 RAG 检索(含质量明细)。
     *
     * <p><b>为什么需要 topScore 与 docIds</b>: "线上文档持续增长时效果有没有漂移"这个问题,
     * 光靠"命中几条"回答不了 ——
     * <ul>
     *   <li>{@code topScore}(最高相似度) 整体下降 → 库里的语料与用户问题的匹配度在变差
     *       (文档变多、话题被稀释、新文档内容质量差);</li>
     *   <li>{@code docIds}(命中的文档 ID) 能反过来找出"入库很久却从未被召回过"的文档 ——
     *       切分太碎、内容重复、与库主题不符时, 这类"死文档"会出现, 而整体命中率看不出异常。</li>
     * </ul>
     * 两者都可在库侧直接用 SQL 聚合(见 {@code docs/sql/eval/retrieval-health.sql})。
     *
     * <p><b>为什么需要 source</b>: 检索有两条发起路径 —— 用户勾选知识库后的强制检索、
     * 模型自主调用 {@code knowledge_base_search} 工具。它们在调用链里长得完全一样(都是 kind=rag),
     * 于是"这一轮究竟是谁决定要查的"从数据上无从判断: 实际排查时就遇到过
     * "看到了 rag 步骤、可工具调用里根本没有 knowledge_base_search", 最后只能靠
     * "这条步骤在 LLM 轮之前还是之间"去反推。记下 source 后一眼可答,
     * 也才能按来源分别统计检索成本。
     *
     * @param source   检索来源 —— 取值为 {@link RagSource} 枚举(唯一取值定义处), 不可为空:
     *                 它是"这次检索是谁发起的"的唯一答案, 不存在"记不出来的来源"这种情形
     * @param topScore 本次检索的最高相似度; 未命中传 0
     * @param docIds   命中的文档 ID(按相似度降序, 已被 topK 截断); 未命中传空列表
     */
    public synchronized void recordRag(String query, int kbCount, int hits, long costMs,
                                       double topScore, java.util.List<String> docIds,
                                       RagSource source) {
        String tag = "rag[" + kbCount + "库/命中" + hits + "]";
        chain.add(tag);
        ragCount++;
        java.util.Map<String, Object> step = new java.util.LinkedHashMap<>();
        step.put("kind", "rag");
        step.put("seq", ragCount);
        step.put("query", query);
        step.put("kbCount", kbCount);
        step.put("hits", hits);
        step.put("costMs", costMs);
        // 质量明细: 供库侧聚合判断"匹配度趋势"与"死文档"
        step.put("topScore", topScore);
        if (docIds != null && !docIds.isEmpty()) {
            step.put("docIds", docIds);
        }
        // 取值 + 中文名一起下发: 面板不再自己维护一份"取值 → 人话"的映射表
        // (那种映射改一边漏一边不会报错, 只会在界面上留个没人认得的英文单词)
        step.put("ragSource", source.value());
        step.put("ragSourceLabel", source.label());
        steps.add(step);
        emit(step);
        log.info("[llm-trace] turn={} RAG {} query='{}' {} topScore={}{}", turnId, tag, query, fmtMs(costMs),
                topScore > 0 ? String.format("%.3f", topScore) : "-", hits == 0 ? " [未命中]" : "");
    }

    /**
     * 记录一次长期记忆召回。
     *
     * <p><b>为什么需要 {@code memoryIds}</b>: "记忆是不是越攒越多、越攒越没用"这个问题,
     * 光看"注入了几条"回答不了 —— 一条记忆可能每轮都在候选池里, 却从未真正进过上下文。
     * 记下当轮召回的 ID, 就能在库侧反查<b>从未命中过的记忆</b>
     * (见 {@code docs/eval/memory-health.sql}): 它们占着常驻额度、每轮都要过一遍召回与打分,
     * 却对回答毫无贡献 —— 是纯成本。
     *
     * <p>字段命名刻意与 RAG 步骤对齐({@code query} / {@code hits} / {@code costMs}):
     * 调用链面板与库侧聚合都能复用同一套解析, 不必为"记忆"再写一套。
     *
     * @param query     当轮用户提问(条件触发匹配的依据)
     * @param hits      召回的条数(常驻 + 条件触发, 已按上限截断)
     * @param chars     注入文本的字符数(受背景信息预算约束后的实际长度, 不是逐条内容之和)
     * @param costMs    召回耗时
     * @param memoryIds 召回的记忆 ID; 未召回传空列表
     */
    public synchronized void recordMemory(String query, int hits, int chars, long costMs,
                                          java.util.List<String> memoryIds) {
        memoryCount++;
        chain.add("mem[" + hits + "]");
        java.util.Map<String, Object> step = new java.util.LinkedHashMap<>();
        step.put("kind", "memory");
        step.put("seq", memoryCount);
        step.put("query", query);
        step.put("hits", hits);
        step.put("chars", chars);
        step.put("costMs", costMs);
        if (memoryIds != null && !memoryIds.isEmpty()) {
            step.put("memoryIds", memoryIds);
        }
        steps.add(step);
        emit(step);
        log.info("[llm-trace] turn={} MEM 召回 {} 条 / 注入 {} 字 {}", turnId, hits, chars, fmtMs(costMs));
    }

    /**
     * 记录本次请求所用 system prompt 的<b>指纹</b>(短哈希)。
     *
     * <p><b>解决什么问题</b>: prompt 分散在配置与 classpath 模板文件里(见 {@code SystemPromptResolver}),
     * 改动它们不会有任何代码 diff 之外的痕迹。于是当"某天回答质量变了"时, 无法回答
     * <b>"是不是 prompt 被改过"</b> —— 只能靠人工回忆。
     *
     * <p>记录了指纹之后: 同一会话不同日期的回答, 只要对比日志里的 8 位哈希, 就能确认
     * "回答变化时 prompt 是否也变了", 把猜测变成事实。
     *
     * <p><b>为什么记哈希而不是全文</b>: 全文可由 git 中受版本控制的配置/模板还原;
     * 每轮都往日志里塞一份完整 prompt 只会浪费存储与带宽, 且不利于比对。
     *
     * @param systemPrompt 合并后的 L1+L2 场景级 system prompt(可为 null, 表示本轮未注入)
     */
    public synchronized void recordPromptFingerprint(String systemPrompt) {
        if (promptFingerprint != null) {
            return;   // 一个 turn 只用一份 system prompt, 记一次即可
        }
        promptFingerprint = fingerprint(systemPrompt);
        java.util.Map<String, Object> step = new java.util.LinkedHashMap<>();
        step.put("kind", "prompt");
        step.put("seq", 1);
        step.put("systemPromptFingerprint", promptFingerprint);
        step.put("systemPromptChars", systemPrompt == null ? 0 : systemPrompt.length());
        steps.add(step);
        emit(step);
        log.info("[llm-trace] turn={} system prompt 指纹={} ({} 字)", turnId, promptFingerprint,
                systemPrompt == null ? 0 : systemPrompt.length());
    }

    /** 取文本 SHA-256 前 4 字节的十六进制(8 位), 够区分 prompt 版本且便于阅读比对 */
    private static String fingerprint(String text) {
        if (text == null || text.isBlank()) {
            return "-";
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "-";
        }
    }

    /**
     * 记录一次「引用忠实度」校验结果。
     *
     * <p><b>它是什么</b>: 回答里的 {@code [[n]]} 由模型自己标注, 后端此前不校验。本方法记录
     * 「回答标注了几处引用 / 本轮共有几条来源 / 其中哪些编号越界」, 把
     * <b>幻觉引用</b>(引用了不存在的来源编号) 从"看不见"变成"可统计"。
     *
     * <p><b>为什么只记录不阻断</b>: 流式回答边生成边推送, 收到完整文本时用户已经看到内容,
     * 事后撤回体验更差。故只留痕 —— 便于统计「幻觉引用率」并在超标时回头调 prompt。
     *
     * @param citedCount     回答中出现的不同引用编号个数
     * @param availableCount 本轮可用来源条数(召回片段 + 工具来源)
     * @param outOfRange     越界编号(小于 1 或大于 availableCount); 空列表表示引用全部合法
     */
    public synchronized void recordCitation(int citedCount, int availableCount, java.util.List<Integer> outOfRange) {
        citationCount++;
        java.util.Map<String, Object> step = new java.util.LinkedHashMap<>();
        step.put("kind", "citation");
        step.put("seq", citationCount);
        step.put("citedCount", citedCount);
        step.put("availableCount", availableCount);
        if (outOfRange != null && !outOfRange.isEmpty()) {
            step.put("outOfRange", outOfRange);
        }
        steps.add(step);
        emit(step);

        if (outOfRange == null || outOfRange.isEmpty()) {
            log.info("[llm-trace] turn={} 引用校验: 标注 {} 处, 来源 {} 条, 全部在范围内",
                    turnId, citedCount, availableCount);
        } else {
            // 越界引用 = 编造了不存在的来源编号, 属幻觉信号, 用 WARN 让它更容易被发现
            log.warn("[llm-trace] turn={} 引用越界(疑似幻觉引用): 标注 {} 处, 来源仅 {} 条, 越界编号 {}",
                    turnId, citedCount, availableCount, outOfRange);
        }
    }

    /** 注册 SSE 实时下发回调: 先回放已缓存事件(路由等前置步骤), 后续事件实时推送 */
    public synchronized void emitTo(java.util.function.Consumer<java.util.Map<String, Object>> consumer) {
        this.emitter = consumer;
        for (java.util.Map<String, Object> step : steps) {
            consumer.accept(step);
        }
    }

    /** 步骤快照(供 assistant 消息落库, 刷新后前端仍可渲染调用链面板) */
    public synchronized List<java.util.Map<String, Object>> snapshot() {
        return new ArrayList<>(steps);
    }

    private void emit(java.util.Map<String, Object> step) {
        java.util.function.Consumer<java.util.Map<String, Object>> e = emitter;
        if (e != null) {
            try {
                e.accept(step);
            } catch (Exception ignore) {
                // 下发失败不影响追踪本身
            }
        }
    }

    /** 输出汇总链路(幂等: 流式管线的多个终止路径只会触发一次) */
    public void summary(String signal) {
        if (!summaryLogged.compareAndSet(false, true)) return;
        synchronized (this) {
            long totalMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("[llm-trace] turn={} SUMMARY 结束={} LLM={}次 工具={}步 tokens(入/出)={}/{} 总耗时={} 链路={}",
                    turnId, signal, llmCalls, toolSteps, totalIn, totalOut,
                    fmtMs(totalMs), String.join(" → ", chain));
            // 回调持久化: 独立日志表存完整快照供前端查询
            CallSummaryListener listener = summaryListener;
            if (listener != null) {
                listener.onSummary(new CallSummary(turnId, totalMs, llmCalls, toolSteps,
                        totalIn, totalOut, signal, String.join(" → ", chain),
                        new ArrayList<>(steps)));
            }
        }
    }

    /** 毫秒格式化: &lt;10s 用 ms, 否则用 s(1 位小数) */
    private static String fmtMs(long ms) {
        return ms < 10_000 ? ms + "ms" : String.format("%.1fs", ms / 1000.0);
    }
}
