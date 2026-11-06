package com.dcdr.agent.agent.memory.strategy;

import com.dcdr.agent.dto.SessionSummaryView;
import com.dcdr.agent.entity.SessionEntity;
import com.dcdr.agent.repository.MessageRepository;
import com.dcdr.agent.repository.SessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 摘要读写(界面可见/可改/可删)的规则测试(V3.4)。
 *
 * <p>最要紧的一条是<b>手改摘要不能动边界</b>: 边界 {@code summarySeq} 表达的是"这段历史已经压过了",
 * 若跟着正文一起变, "哪些消息还要发"就会随用户编辑而漂移 —— 用户改一句话, 结果对话少了十轮。
 */
class SessionSummaryStoreTest {

    private static final String SESSION = "s1";

    private static SessionEntity session(String summary, Integer seq) {
        SessionEntity e = new SessionEntity();
        e.setId(SESSION);
        e.setContextSummary(summary);
        e.setContextSummarySeq(seq);
        return e;
    }

    @Test
    @DisplayName("从未压缩过: available=false, 不查消息条数")
    void viewReportsUnavailableWhenNeverCompacted() {
        SessionRepository repo = mock(SessionRepository.class);
        MessageRepository messages = mock(MessageRepository.class);
        when(repo.findById(SESSION)).thenReturn(Optional.of(session(null, null)));

        SessionSummaryView view = new SessionSummaryStore(repo, messages).view(SESSION);

        assertFalse(view.isAvailable(), "没压过就不该显示摘要卡片");
        verify(messages, never()).countBySessionIdAndSeqLessThanEqual(anyString(), anyInt());
    }

    @Test
    @DisplayName("已压缩: 回传正文/字数/边界/被压缩条数")
    void viewReportsSnapshot() {
        SessionRepository repo = mock(SessionRepository.class);
        MessageRepository messages = mock(MessageRepository.class);
        when(repo.findById(SESSION)).thenReturn(Optional.of(session("旧摘要正文", 7)));
        when(messages.countBySessionIdAndSeqLessThanEqual(SESSION, 7)).thenReturn(8L);

        SessionSummaryView view = new SessionSummaryStore(repo, messages).view(SESSION);

        assertTrue(view.isAvailable());
        assertEquals("旧摘要正文", view.getText());
        assertEquals(7, view.getUptoSeq());
        assertEquals(5, view.getChars());
        assertEquals(8L, view.getCoveredMessages(), "条数应按实际消息数统计, 不是 seq/2 估算");
    }

    @Test
    @DisplayName("手改摘要: 只换正文, 边界保持不变")
    void updateTextKeepsBoundary() {
        SessionRepository repo = mock(SessionRepository.class);
        SessionEntity entity = session("旧摘要", 7);
        when(repo.findById(SESSION)).thenReturn(Optional.of(entity));

        boolean ok = new SessionSummaryStore(repo, mock(MessageRepository.class))
                .updateText(SESSION, "  用户要求：不要动生产库  ");

        assertTrue(ok);
        assertEquals("用户要求：不要动生产库", entity.getContextSummary(), "首尾空白应被去掉");
        assertEquals(7, entity.getContextSummarySeq(), "边界不能被用户编辑改动");
        verify(repo).save(entity);
    }

    @Test
    @DisplayName("会话还没压缩过: 手改返回 false, 不写库")
    void updateTextRejectedWhenNeverCompacted() {
        SessionRepository repo = mock(SessionRepository.class);
        when(repo.findById(SESSION)).thenReturn(Optional.of(session(null, null)));

        boolean ok = new SessionSummaryStore(repo, mock(MessageRepository.class))
                .updateText(SESSION, "想直接写一段");

        assertFalse(ok, "没有边界就没有摘要 —— 直接写一段会让'哪些消息已被压过'无从判断");
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("摘要过长: 抛参数异常, 由 Controller 转 400")
    void updateTextRejectsTooLong() {
        SessionRepository repo = mock(SessionRepository.class);
        when(repo.findById(SESSION)).thenReturn(Optional.of(session("旧摘要", 7)));
        String tooLong = "长".repeat(SessionSummaryStore.MAX_EDITABLE_CHARS + 1);

        assertThrows(IllegalArgumentException.class,
                () -> new SessionSummaryStore(repo, mock(MessageRepository.class)).updateText(SESSION, tooLong));
    }
}
