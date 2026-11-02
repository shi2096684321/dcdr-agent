package com.dcdr.agent.agent.log;

import com.dcdr.agent.agent.session.SessionSource;
import com.dcdr.agent.common.log.LlmCallTrace;
import com.dcdr.agent.entity.LlmCallLogEntity;
import com.dcdr.agent.repository.LlmCallLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LlmCallLogWriter} 的落库口径测试。
 *
 * <p><b>为什么这个类必须有测试</b>: 它原先是一段写在 {@code ChatController} 里的私有回调,
 * 于是定时任务与工作流调用 {@code chatSync} 时 trace 为 null, 快照只进日志文件、从不落库 ——
 * 而这个问题<b>不会报任何错</b>, 表现只是"调用链日志页面里搜不到定时任务"。
 * 一旦有人在别处又把 trace 传成 null, 症状完全相同且极难发现, 所以这里把它钉死。
 */
class LlmCallLogWriterTest {

    private LlmCallLogRepository repository = mock(LlmCallLogRepository.class);
    private final LlmCallLogWriter writer = new LlmCallLogWriter(repository, new ObjectMapper());

    @Test
    @DisplayName("定时任务的调用链按 source=schedule 落库, 并带上自动会话 ID")
    void savesScheduleSource() {
        LlmCallTrace trace = LlmCallTrace.begin();
        writer.bind(trace, "u1", "sess-auto-1", "生成今日早报", SessionSource.SCHEDULE);
        trace.summary("ON_COMPLETE");

        ArgumentCaptor<LlmCallLogEntity> captor = ArgumentCaptor.forClass(LlmCallLogEntity.class);
        verify(repository).save(captor.capture());
        LlmCallLogEntity saved = captor.getValue();

        assertEquals("schedule", saved.getSource(), "来源必须落库, 否则列表页分不出定时任务");
        assertEquals("sess-auto-1", saved.getSessionId(), "会话 ID 是日志跳回「任务对话」的唯一线索");
        assertEquals("u1", saved.getUserId());
        assertEquals("生成今日早报", saved.getUserMessage());
        assertEquals("ON_COMPLETE", saved.getEndSignal());
        assertNotNull(saved.getTurnId());
        assertEquals("[]", saved.getStepsJson(), "无步骤时应是空数组而不是 null, 前端按数组遍历");
    }

    @Test
    @DisplayName("trace 为 null 时安全返回, 不注册任何回调也不写库")
    void ignoresNullTrace() {
        assertDoesNotThrow(() -> writer.bind(null, "u1", "s1", "hi", SessionSource.USER));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("用户消息截断到 200 字符: 列表只用来认「这是哪一次」")
    void clipsLongUserMessage() {
        String longInput = "问".repeat(500);
        LlmCallTrace trace = LlmCallTrace.begin();
        writer.bind(trace, "u1", "s1", longInput, SessionSource.USER);
        trace.summary("ON_COMPLETE");

        ArgumentCaptor<LlmCallLogEntity> captor = ArgumentCaptor.forClass(LlmCallLogEntity.class);
        verify(repository).save(captor.capture());
        assertEquals(200, captor.getValue().getUserMessage().length());
    }

    @Test
    @DisplayName("落库失败不得向上抛: 一次成功的对话不该因为写日志出错而变成失败")
    void swallowsPersistFailure() {
        when(repository.save(any())).thenThrow(new RuntimeException("db down"));
        LlmCallTrace trace = LlmCallTrace.begin();
        writer.bind(trace, "u1", "s1", "hi", SessionSource.WORKFLOW);

        assertDoesNotThrow(() -> trace.summary("ON_COMPLETE"));
    }

    @Test
    @DisplayName("summary 幂等: 多个终止路径只写一条, 不会把同一次调用记成两条")
    void writesOnlyOnce() {
        LlmCallTrace trace = LlmCallTrace.begin();
        writer.bind(trace, "u1", "s1", "hi", SessionSource.USER);
        // 模拟定时任务的 finally 与编排层 doFinally 都触发的情形
        trace.summary("ON_COMPLETE");
        trace.summary("ON_ERROR");

        verify(repository).save(any());
    }
}
