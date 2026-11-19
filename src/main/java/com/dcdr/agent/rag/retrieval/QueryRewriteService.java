package com.dcdr.agent.rag.retrieval;

import com.dcdr.agent.agent.governance.CostSource;
import com.dcdr.agent.agent.governance.CostTracker;
import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.dto.ChatRequest;
import com.dcdr.agent.dto.ChatResponse;
import com.dcdr.agent.model.provider.ChatModelProvider;
import com.dcdr.agent.model.registry.ModelRegistry;
import com.dcdr.agent.rag.config.RagProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * 检索查询改写：把多轮对话里的「它 / 这个 / 那个」补全成可独立检索的一句话。
 *
 * <p><b>不改写会怎样</b>: 挂了知识库的会话每轮都检索，但检索用的一直是用户原话。
 * 第二轮问「它的价格呢」，向量检索拿到的查询里没有任何可指向的对象 —— 打偏是必然的，
 * 而且<b>静默</b>：没有片段、没有提示，模型直接凭记忆答，界面上看不出来。
 *
 * <p><b>触发条件刻意收紧</b>（每轮都改写等于给每次对话加一笔无谓开销）:
 * <ul>
 *   <li>会话挂了知识库 {@code forced}（由调用方保证）</li>
 *   <li>历史非空 —— 第一轮没有上文可补</li>
 *   <li>本句确实依赖上文：含指代/省略词，或短到不足以独立检索（≤ 8 字）</li>
 * </ul>
 *
 * <p><b>失败一律回退原句</b>: 超时、空返回、跑偏（输出长度超过原句 3 倍）都退回原话。
 * 改写是优化项，不能让它变成对话失败的一个新原因。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryRewriteService {

    /** 单条历史消息进改写请求时的截断长度: 一条长回答不该独占整个预算 */
    private static final int MAX_CHARS_PER_MESSAGE = 600;
    /** 改写结果的硬上限: 再长就不像"一句查询"了 */
    private static final int MAX_REWRITE_CHARS = 200;

    private static final String SYSTEM_PROMPT = """
            你是检索查询改写器。把用户当前这句话改写成一句「可独立检索」的查询。

            要求：
            1. 结合对话上文，把代词与省略补全成具体对象（如"它"→"订单 A123"、"这个"→"合同违约责任"）。
            2. 只输出改写后的那一句：不要解释、不要引号、不要"改写为："之类前缀。
            3. 不得添加上文没有的信息；指代实在判断不出来时，按原句输出。
            4. 保留原句里的关键实体、编号与专有名词。
            """;

    /** 依赖上文的信号：指代词 */
    private static final Pattern REFERENTIAL = Pattern.compile(
            "它|他|她|这个|那个|这些|那些|该|此|上面|上述|刚才|前面|前者|后者|这样|那样|其中|还有");
    /** 短于此长度的句子通常不足以独立检索（"多少钱""怎么弄"） */
    private static final int SHORT_MESSAGE_CHARS = 6;
    /** 以"呢"收尾的追问（"那第二季度呢"）同样依赖上文, 但长度可能超过上一条阈值 */
    private static final int ELLIPSIS_MESSAGE_CHARS = 10;

    private final RagProperties ragProperties;
    private final ModelRegistry modelRegistry;
    private final CostTracker costTracker;

    /**
     * 改写调用的执行线程：固定 2 个 + 守护线程。
     * 改写是"首字节之前"的阻塞步骤，必须有独立超时，不能借用主调用链的 120s。
     */
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "rag-query-rewrite");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdownExecutor() {
        executor.shutdownNow();
    }

    /**
     * 这句话是否需要改写（纯函数，供测试锁定契约）。
     * <p>含指代词，或短到无法独立成句 —— 两种情况下"直接用原话检索"注定打偏。
     */
    public static boolean needsRewrite(String message) {
        if (message == null || message.isBlank()) return false;
        String m = message.strip();
        // 去掉标点后再判长度与结尾, 否则"多少钱？"会比"多少钱"多算一个字符
        String bare = m.replaceAll("[\\p{P}\\p{S}\\s]", "");
        if (bare.isEmpty()) return false;
        if (bare.length() <= SHORT_MESSAGE_CHARS) return true;
        if (bare.length() <= ELLIPSIS_MESSAGE_CHARS && bare.endsWith("呢")) return true;
        return REFERENTIAL.matcher(bare).find();
    }

    /**
     * 改写本轮检索查询。
     *
     * @param history     发送给模型的历史（已按上下文策略裁剪）
     * @param userMessage 用户本轮原话
     * @return 可独立检索的查询；未触发/失败/超时一律返回 {@code userMessage} 原句
     */
    public String rewrite(List<Message> history, String userMessage, String userId, String sessionId) {
        String original = userMessage == null ? "" : userMessage.strip();
        RagProperties.QueryRewriteConfig cfg = ragProperties.getRetrieval().getRewrite();
        if (!cfg.isEnabled() || original.isEmpty() || history == null || history.isEmpty()) {
            return original;
        }
        if (!needsRewrite(original)) return original;

        ChatModelProvider provider;
        String prompt;
        try {
            provider = resolveProvider(cfg);
            prompt = buildPrompt(history, original, Math.max(2, cfg.getHistoryMessages()));
        } catch (Exception e) {
            log.warn("检索改写请求组装失败, 本轮用原句检索: {}", e.getMessage());
            return original;
        }

        Future<ChatResponse> future = executor.submit(() -> {
            ChatRequest req = new ChatRequest();
            req.setMessage(prompt);
            req.setStream(false);
            // 温度 0: 要的是确定性转写, 不是创作
            req.setTemperature(0d);
            return provider.invoke(req, List.of(Message.system(SYSTEM_PROMPT)));
        });

        int timeout = Math.max(3, cfg.getTimeoutSeconds());
        try {
            ChatResponse resp = future.get(timeout, TimeUnit.SECONDS);
            if (resp == null) return original;
            // 改写同样是真实开销, 记进成本报表(按来源单列, 才能评估这笔钱值不值)
            costTracker.record(userId, sessionId, null, CostSource.RAG_REWRITE,
                    provider.getName(), resp.getTokenUsage());
            String rewritten = firstLine(resp.getContent());
            if (rewritten.isEmpty() || rewritten.equals(original)) return original;
            // 跑偏兜底: 模型有时会把整段历史复述一遍, 超长的一律不用
            if (rewritten.length() > MAX_REWRITE_CHARS || rewritten.length() > Math.max(60, original.length() * 3)) {
                log.warn("检索改写结果异常长({} 字), 回退原句: {}", rewritten.length(), rewritten);
                return original;
            }
            log.info("检索改写: 「{}」→「{}」", original, rewritten);
            return rewritten;
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("检索改写超时({}s), 本轮用原句检索", timeout);
            return original;
        } catch (Exception e) {
            log.warn("检索改写失败, 本轮用原句检索: {}", e.getMessage());
            return original;
        }
    }

    /** 配置了专用模型就用它（建议配低成本模型），否则跟随系统默认对话模型 */
    private ChatModelProvider resolveProvider(RagProperties.QueryRewriteConfig cfg) {
        String model = cfg.getModel();
        if (model != null && !model.isBlank()) {
            return modelRegistry.getChatProvider(model);
        }
        return modelRegistry.getDefaultChatProvider();
    }

    private static String buildPrompt(List<Message> history, String userMessage, int limit) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("【对话上文】\n");
        int start = Math.max(0, history.size() - limit);
        for (int i = start; i < history.size(); i++) {
            Message m = history.get(i);
            String content = m.getContent() == null ? "" : m.getContent().strip();
            if (content.isEmpty()) continue;
            if (content.length() > MAX_CHARS_PER_MESSAGE) {
                content = content.substring(0, MAX_CHARS_PER_MESSAGE) + "…";
            }
            sb.append("user".equals(m.getRole()) ? "用户" : "助手").append(": ").append(content).append('\n');
        }
        sb.append("\n【当前问题】\n").append(userMessage);
        sb.append("\n\n只输出改写后的检索查询，一行，不加任何前缀。");
        return sb.toString();
    }

    /** 取第一行并剥掉模型偶尔自带的引号/前缀 */
    private static String firstLine(String text) {
        if (text == null) return "";
        String line = text.strip().split("\\R", 2)[0].strip();
        line = line.replaceAll("^(改写|检索查询|查询)[:：]\\s*", "");
        line = line.replaceAll("^[\"'“”「」']|[\"'“”「」']$", "").strip();
        return line.length() > MAX_REWRITE_CHARS ? line.substring(0, MAX_REWRITE_CHARS) : line;
    }
}
