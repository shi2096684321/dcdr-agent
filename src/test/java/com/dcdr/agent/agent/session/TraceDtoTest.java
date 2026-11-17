package com.dcdr.agent.agent.session;

import com.dcdr.agent.dto.MessageDTO;
import com.dcdr.agent.entity.MessageEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 时间线落库 → DTO 的往返测试。
 *
 * <p>{@code TraceTimelineTest} 只锁了记录端的纯函数，这里补上读取端：
 * 前端拿到的 {@code DTO.trace} 来自 {@code MessageEntity.traceJson} 的解析，
 * 切片长度或工具 id 在解析里丢了，历史消息就会退回"整段思考 + 工具堆末尾"的老排法。
 */
class TraceDtoTest {

    @Test
    @DisplayName("traceJson 经 toMessageDTO 原样还原: 切片长度与工具 id 不丢")
    void restoresTraceToDto() {
        MessageEntity entity = new MessageEntity();
        entity.setTraceJson("[{\"t\":\"reason\",\"n\":120},{\"t\":\"tool\",\"id\":\"call_a\"},"
                + "{\"t\":\"reason\",\"n\":80}]");

        MessageDTO dto = SessionManager.toMessageDTO(entity);

        assertNotNull(dto.getTrace());
        assertEquals(3, dto.getTrace().size());
        assertEquals("reason", dto.getTrace().get(0).get("t"));
        assertEquals(120, ((Number) dto.getTrace().get(0).get("n")).intValue());
        assertEquals("call_a", dto.getTrace().get(1).get("id"));
        assertEquals(80, ((Number) dto.getTrace().get(2).get("n")).intValue());
    }

    @Test
    @DisplayName("老消息 traceJson 为空: DTO.trace 为 null, 前端按旧排法兜底")
    void legacyMessageHasNoTrace() {
        assertNull(SessionManager.toMessageDTO(new MessageEntity()).getTrace());
    }
}
