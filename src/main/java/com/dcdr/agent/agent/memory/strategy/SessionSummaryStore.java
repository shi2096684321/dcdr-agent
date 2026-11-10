package com.dcdr.agent.agent.memory.strategy;

import com.dcdr.agent.dto.SessionSummaryView;
import com.dcdr.agent.repository.MessageRepository;
import com.dcdr.agent.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话摘要的读写入口(V3.4) —— 本类<b>独占</b> {@code sessions.context_summary} 的存取语义。
 *
 * <p><b>为什么单独一个类</b>: 摘要在语义上是"可丢弃的缓存", 不是业务事实 ——
 * 唯一正确的用法是"读到就用, 读不到就从零开始压"。把这个判断(以及边界校验)
 * 收在一处, 比让每个调用点各自判空更不容易出错。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionSummaryStore {

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    /**
     * 压缩事件记录器。作废/清除/改写这三件事都发生在摘要正文之外,
     * 不落事件的话界面上只剩"摘要没了", 用户分不清是自己清的还是边界失效。
     */
    private final ContextCompactionRecorder recorder;

    /**
     * 已存摘要。
     *
     * @param text 摘要正文
     * @param seq  摘要覆盖到的最后一条消息 seq(边界证明: 只丢弃 seq &lt;= 它的消息)
     */
    public record Snapshot(String text, int seq) {}

    /** @return 无摘要/会话不存在/字段残缺时返回 {@code null} */
    public Snapshot load(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        return sessionRepository.findById(sessionId)
                .map(s -> {
                    String text = s.getContextSummary();
                    Integer seq = s.getContextSummarySeq();
                    // 两列必须同时存在: 只有正文没有边界 → 不知道该丢哪些消息, 等同于没有
                    if (text == null || text.isBlank() || seq == null) return null;
                    return new Snapshot(text, seq);
                })
                .orElse(null);
    }

    /**
     * 覆盖保存摘要。
     * <p>每次压缩都是"整体替换"而不是追加: 旧摘要已被并进新摘要, 留着只会重复占上下文。
     *
     * @param seq 本次压缩覆盖到的最后一条消息 seq
     */
    @Transactional
    public void save(String sessionId, String text, int seq) {
        if (text == null || text.isBlank()) return;
        sessionRepository.findById(sessionId).ifPresent(s -> {
            s.setContextSummary(text);
            s.setContextSummarySeq(seq);
            sessionRepository.save(s);
        });
    }

    /**
     * 摘要自愈: 它覆盖到的边界消息已不在历史里(用户撤回/删除), 整份作废。
     * <p>刻意不抛异常: 摘要本来就是缓存, 清不掉(会话已删)没有后果。
     */
    @Transactional
    public void invalidate(String sessionId, String reason) {
        clearInternal(sessionId, CompactionEvent.INVALIDATED, reason);
    }

    /** 用户在界面上主动清除摘要 —— 与自愈分开记: 一个是用户意图, 一个是系统判断 */
    @Transactional
    public void clearByUser(String sessionId) {
        clearInternal(sessionId, CompactionEvent.CLEARED, null);
    }

    private void clearInternal(String sessionId, CompactionEvent event, String reason) {
        sessionRepository.findById(sessionId).ifPresent(s -> {
            if (s.getContextSummary() == null && s.getContextSummarySeq() == null) return;
            int chars = s.getContextSummary() == null ? 0 : s.getContextSummary().length();
            s.setContextSummary(null);
            s.setContextSummarySeq(null);
            sessionRepository.save(s);
            log.info("会话 {} 摘要已作废({})", sessionId, event.label());
            if (event == CompactionEvent.CLEARED) {
                recorder.cleared(sessionId, s.getUserId(), chars);
            } else {
                recorder.invalidated(sessionId, s.getUserId(), reason);
            }
        });
    }

    // ==================== 面向界面的读写 ====================

    /** 摘要字数上限: 用户手改也不该把摘要填成另一份长文, 那等于没压缩 */
    public static final int MAX_EDITABLE_CHARS = 20_000;

    /**
     * 供界面展示的摘要视图。
     * <p>未压缩过时返回 {@code available=false} 的空结构, 而不是 null —— 前端少一个判空分支,
     * 也不会把"没有摘要"和"接口出错"混为一谈。
     */
    public SessionSummaryView view(String sessionId) {
        SessionSummaryView view = new SessionSummaryView();
        Snapshot snapshot = load(sessionId);
        if (snapshot == null) {
            view.setAvailable(false);
            return view;
        }
        view.setAvailable(true);
        view.setText(snapshot.text());
        view.setUptoSeq(snapshot.seq());
        view.setChars(snapshot.text().length());
        view.setCoveredMessages(messageRepository.countBySessionIdAndSeqLessThanEqual(sessionId, snapshot.seq()));
        return view;
    }

    /**
     * 手工改写摘要正文(边界不变)。
     * <p>只改正文、不动 {@code summarySeq}: 边界表达的是"这段历史已经被压缩过了",
     * 与正文内容无关; 若跟着改动, 会让"哪些消息还要发"随之变化 —— 那是压缩策略该决定的事。
     *
     * @return {@code false} 表示该会话从未压缩过(没有可改的摘要)
     */
    @Transactional
    public boolean updateText(String sessionId, String text) {
        if (text == null || text.isBlank()) return false;
        String trimmed = text.strip();
        if (trimmed.length() > MAX_EDITABLE_CHARS) {
            throw new IllegalArgumentException("摘要过长(上限 " + MAX_EDITABLE_CHARS + " 字), 当前 " + trimmed.length());
        }
        return sessionRepository.findById(sessionId)
                .filter(s -> s.getContextSummary() != null && s.getContextSummarySeq() != null)
                .map(s -> {
                    int beforeChars = s.getContextSummary().length();
                    s.setContextSummary(trimmed);
                    sessionRepository.save(s);
                    log.info("会话 {} 摘要被手工改写: {} 字", sessionId, trimmed.length());
                    recorder.edited(sessionId, s.getUserId(), beforeChars, trimmed.length());
                    return true;
                })
                .orElse(false);
    }
}
