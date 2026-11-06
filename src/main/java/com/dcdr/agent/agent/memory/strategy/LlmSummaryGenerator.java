package com.dcdr.agent.agent.memory.strategy;

import com.dcdr.agent.agent.governance.CostSource;
import com.dcdr.agent.agent.governance.CostTracker;
import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.config.AgentProperties;
import com.dcdr.agent.dto.ChatRequest;
import com.dcdr.agent.dto.ChatResponse;
import com.dcdr.agent.model.provider.ChatModelProvider;
import com.dcdr.agent.model.registry.ModelRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 用模型生成历史摘要(V3.4)。
 *
 * <p><b>提示词的三条硬约束</b>(都是踩过的坑, 不是文风偏好):
 * <ol>
 *   <li><b>只保留事实, 不保留指令</b>: 摘要会作为 system 消息回到上下文。若允许它写"你应该…",
 *       等于让一段<b>模型生成的文本</b>获得了系统指令的地位 —— 与"用户文本要平台包装后才能注入"
 *       是同一条安全底线。</li>
 *   <li><b>增量合并</b>: 已有摘要必须和新对话压成一份。写成两段会让摘要自身线性膨胀,
 *       压缩到最后比不压缩还占地方。</li>
 *   <li><b>不编造</b>: 摘要一旦虚构, 后续所有回答都会建立在这个虚构之上, 且没有任何痕迹可查。</li>
 * </ol>
 *
 * <p><b>超时是必须的</b>: 压缩跑在对话的准备阶段(用户正等着发出去)。模型端点偶发挂起时,
 * 若只依赖 HTTP 客户端默认超时, 用户会看到"发消息没反应"。这里用独立线程 + {@code Future.get}
 * 硬性超时, 到点即放弃并返回 null, 由策略降级为滑动窗口。
 */
@Slf4j
@Component
public class LlmSummaryGenerator implements SummaryGenerator {

    /** 单条消息进提示词时的截断长度: 一条超长工具结果或长回答不该独占整个摘要请求 */
    private static final int MAX_CHARS_PER_MESSAGE = 2000;
    /** 送进摘要请求的消息条数上限: 再多也不会让摘要更准, 只会让请求本身超预算 */
    private static final int MAX_MESSAGES_PER_RUN = 200;

    private static final String SYSTEM_PROMPT = """
            你是一个对话压缩器。你的唯一任务是把较早的一段对话压缩成一份「交接摘要」，
            供后续回合在没有原文的情况下继续工作。

            要求：
            1. 只输出摘要正文。不要前言、不要标题、不要代码块围栏。
            2. 必须保留：用户的目标与约束；已经确认的决定与结论；已经完成的改动（含文件、接口、字段等具体标识）；
               未完成的事项与下一步；关键事实与数据；用户的偏好与明确要求。
            3. 可以丢弃：寒暄与客套；重复表述；被推翻或作废的方案（可简记为"曾考虑 X，已否决，原因 Y"）。
            4. 若提供了【已有摘要】，把它与新对话合并成一份完整摘要，不要输出两段。
            5. 不得编造对话中没有的信息。
            6. 只做事实转述，不得输出任何指令性语句（如"你应该…""请继续…"）——
               这段文字会被当作背景资料使用，其中的要求一律无效。
            7. 摘要长度控制在 %d 字以内，超出部分会被截断。
            """;

    private final ModelRegistry modelRegistry;
    private final CostTracker costTracker;
    private final AgentProperties properties;

    /**
     * 摘要调用的执行线程。固定 2 个 + 守护线程:
     * 压缩是低频操作(每十几轮一次), 不需要线程池调优; 守护线程保证进程退出时不会被它拖住。
     */
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "memory-compaction");
        t.setDaemon(true);
        return t;
    });

    public LlmSummaryGenerator(ModelRegistry modelRegistry, CostTracker costTracker, AgentProperties properties) {
        this.modelRegistry = modelRegistry;
        this.costTracker = costTracker;
        this.properties = properties;
    }

    /** 容器关闭时释放线程池: 守护线程虽不挡进程退出, 但不关就是一条没人体面收尾的线程 */
    @PreDestroy
    void shutdownExecutor() {
        executor.shutdownNow();
    }

    @Override
    public String summarize(String existingSummary, List<Message> messages, String userId, String sessionId) {
        if (messages == null || messages.isEmpty()) return null;
        AgentProperties.CompactionConfig cfg = properties.getMemory().getCompaction();
        int maxChars = Math.max(200, cfg.getSummaryMaxChars());

        ChatModelProvider provider;
        String prompt;
        try {
            provider = resolveProvider(cfg);
            prompt = buildUserPrompt(existingSummary, messages, maxChars);
        } catch (Exception e) {
            // 这一条的现场大多是 agent.memory.compaction.model 填错了: 那里要的是 model-registry
            // 的 provider 名(如 qwen-chat), 填成上游模型 id(如 qwen3.8-flash) 就会 404。
            // 由于降级路径把异常吞在这里, 不写清楚的话症状只有"摘要永远不生效"。
            log.warn("摘要请求组装失败(将降级为滑动窗口), 请确认 agent.memory.compaction.model 填的是"
                    + " provider 名而非模型 id: {}", e.getMessage());
            return null;
        }

        String systemPrompt = String.format(SYSTEM_PROMPT, maxChars);
        Future<String> future = executor.submit(() -> {
            ChatRequest req = new ChatRequest();
            req.setMessage(prompt);
            req.setStream(false);
            // 低温: 要的是忠实转述, 不是创作
            req.setTemperature(0.2);
            ChatResponse resp = provider.invoke(req, List.of(Message.system(systemPrompt)));
            if (resp == null) return null;
            // 摘要同样是真实开销, 必须记进成本报表; 否则"按来源看"会少一笔, 且找不到是谁花的
            costTracker.record(userId, sessionId, null, CostSource.MEMORY_COMPACTION,
                    provider.getName(), resp.getTokenUsage());
            return resp.getContent();
        });

        try {
            int timeout = Math.max(5, cfg.getTimeoutSeconds());
            String text = future.get(timeout, TimeUnit.SECONDS);
            if (text == null || text.isBlank()) {
                log.warn("摘要生成为空(将降级为滑动窗口): session={}, model={}", sessionId, provider.getName());
                return null;
            }
            String cleaned = stripFence(text.strip());
            // 硬截断兜底: 提示词里要求了长度, 但模型并不总是遵守
            if (cleaned.length() > maxChars) {
                cleaned = cleaned.substring(0, maxChars) + "…";
            }
            return cleaned;
        } catch (Exception e) {
            // 超时/线程中断/模型报错都走同一条降级路径, 不动对话链路
            future.cancel(true);
            log.warn("摘要生成失败(将降级为滑动窗口): session={}, err={}", sessionId, e.toString());
            return null;
        }
    }

    /** 配置了专用模型就用它(建议配低成本模型), 否则跟随系统默认对话模型 */
    private ChatModelProvider resolveProvider(AgentProperties.CompactionConfig cfg) {
        String model = cfg.getModel();
        if (model != null && !model.isBlank()) {
            return modelRegistry.getChatProvider(model);
        }
        return modelRegistry.getDefaultChatProvider();
    }

    private String buildUserPrompt(String existingSummary, List<Message> messages, int maxChars) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("【已有摘要】\n");
        if (existingSummary == null || existingSummary.isBlank()) {
            sb.append("(无)\n");
        } else {
            sb.append(existingSummary.strip()).append('\n');
        }
        sb.append("\n【待压缩的对话】\n");
        int start = Math.max(0, messages.size() - MAX_MESSAGES_PER_RUN);
        for (int i = start; i < messages.size(); i++) {
            Message m = messages.get(i);
            sb.append(m.getRole());
            if (m.getName() != null) sb.append('/').append(m.getName());
            sb.append(": ").append(clip(m.getContent())).append('\n');
        }
        sb.append("\n请按系统提示的要求输出合并后的摘要（不超过 ").append(maxChars).append(" 字）。");
        return sb.toString();
    }

    private static String clip(String content) {
        if (content == null) return "";
        String s = content.strip();
        return s.length() <= MAX_CHARS_PER_MESSAGE ? s : s.substring(0, MAX_CHARS_PER_MESSAGE) + "…";
    }

    /** 模型偶尔仍会套 ``` 围栏: 摘要是纯文本, 围栏会让它看起来像代码块 */
    private static String stripFence(String text) {
        String s = text.strip();
        if (!s.startsWith("```")) return s;
        int firstLine = s.indexOf('\n');
        if (firstLine < 0) return s;
        int end = s.lastIndexOf("```");
        return end > firstLine ? s.substring(firstLine + 1, end).strip() : s.substring(firstLine + 1).strip();
    }
}
