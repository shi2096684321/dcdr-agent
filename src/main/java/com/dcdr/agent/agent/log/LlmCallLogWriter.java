package com.dcdr.agent.agent.log;

import com.dcdr.agent.agent.session.SessionSource;
import com.dcdr.agent.common.log.LlmCallTrace;
import com.dcdr.agent.entity.LlmCallLogEntity;
import com.dcdr.agent.repository.LlmCallLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 调用链日志落库器: 把 {@link LlmCallTrace} 的汇总快照写进 {@code llm_call_logs}。
 *
 * <p><b>为什么要有这个类, 而不是各调用方自己注册回调</b>: 这段逻辑原先写在
 * {@code ChatController#registerLogSink} 里, 于是<b>只有走 Controller 的对话才有链路日志</b> ——
 * 定时任务与工作流调用 {@code orchestrator.chatSync} 时 trace 为 null, 编排层会自建一个
 * 但<b>没人注册监听</b>, 快照只进了日志文件、从不落库。表现就是"调用链日志"页面里
 * 从来搜不到定时任务, 而失败排查恰恰最需要它("第 2 轮生成超时" vs "工具 web_search 挂了")。
 *
 * <p>抽成一个组件后, 三类调用方共用同一套字段映射, 不会再出现"某个入口少写一个字段"。
 *
 * <p><b>使用方式</b>(顺序不能反):
 * <pre>
 *   LlmCallTrace trace = LlmCallTrace.begin();
 *   writer.bind(trace, userId, sessionId, userMessage, SessionSource.SCHEDULE);
 *   ... orchestration.chat(request, userId, trace) ...
 *   trace.summary(signal);   // 由管线 doFinally 触发, 此时才真正写库
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmCallLogWriter {

    /** 用户消息入库时的截断长度(列表页只用来认"这是哪一次", 不需要全文) */
    private static final int USER_MESSAGE_MAX = 200;

    private final LlmCallLogRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * 注册落库回调。
     *
     * @param trace       追踪器; 为 null 时直接返回(调用方没建追踪, 本就没有可落的快照)
     * @param userId      归属用户
     * @param sessionId   会话 ID; 定时任务/工作流传自动会话的 ID, 便于从日志跳到那次对话
     * @param userMessage 触发本次调用的输入(用户问句 / 任务提示词 / 工作流步骤输入)
     * @param source      来源; 决定列表页按哪个页签展示
     */
    public void bind(LlmCallTrace trace, String userId, String sessionId,
                     String userMessage, SessionSource source) {
        if (trace == null) {
            return;
        }
        trace.onSummary(summary -> {
            try {
                LlmCallLogEntity entity = new LlmCallLogEntity();
                entity.setId(UUID.randomUUID().toString());
                entity.setTurnId(summary.turnId());
                entity.setUserId(userId);
                entity.setSessionId(sessionId);
                entity.setSource(source == null ? SessionSource.USER.code() : source.code());
                entity.setUserMessage(userMessage == null ? "" : clip(userMessage));
                entity.setEndSignal(summary.endSignal());
                entity.setLlmCalls(summary.llmCalls());
                entity.setToolSteps(summary.toolSteps());
                entity.setTotalInTokens(summary.totalIn());
                entity.setTotalOutTokens(summary.totalOut());
                entity.setTotalDurationMs(summary.totalMs());
                entity.setChain(summary.chain());
                entity.setStepsJson(objectMapper.writeValueAsString(summary.steps()));
                // 失败信息单独成列(而不是只躺在 steps_json 里): 列表页要按"是否失败"排序/筛选时不必解析 JSON
                entity.setFailedStep(summary.failedStep());
                entity.setErrorMessage(summary.errorMessage());
                entity.setCreatedAt(LocalDateTime.now());
                repository.save(entity);
            } catch (Exception e) {
                // 落库失败不能反过来影响业务: 一次对话已经成功生成, 不该因为写日志出错而报错
                log.error("保存调用链日志失败: turn={}, source={}", summary.turnId(), source, e);
            }
        });
    }

    private static String clip(String text) {
        String t = text.trim();
        return t.length() <= USER_MESSAGE_MAX ? t : t.substring(0, USER_MESSAGE_MAX);
    }
}
