package com.dcdr.agent.agent.feedback;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.dto.FeedbackDtos;
import com.dcdr.agent.entity.MessageEntity;
import com.dcdr.agent.entity.MessageFeedbackEntity;
import com.dcdr.agent.repository.MessageFeedbackRepository;
import com.dcdr.agent.repository.MessageRepository;
import com.dcdr.agent.repository.SessionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * V3.1 回答反馈服务 (F-48 点赞/点踩收集)。
 * <p>
 * <b>本版只负责"收集"</b> —— 把用户对单条回答的评价连同回答侧上下文稳定落库,
 * 并提供最小可用的归因视图 (好评率趋势 / 原因分布 / 按模型聚合 / 差评明细),
 * 使数据收上来之后可以被看懂, 而不是躺在表里。
 * <p>
 * 数据的消费 (差评 → 回归用例、改提示词、调路由权重、沉淀示例库) 不在本版范围。
 * <p>
 * <b>为什么需要上下文快照</b>: 一条"👎"本身不含任何可行动信息。只有把它与
 * 模型、是否联网、是否走 RAG、回答长度绑定, 才能回答"是哪个模型/哪条链路在变差"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackService {

    /** 允许的原因标签 (code → 展示名), 顺序即前端展示顺序 */
    private static final Map<String, String> REASON_TAGS = new LinkedHashMap<>();

    static {
        REASON_TAGS.put("INACCURATE", "内容不准确");
        REASON_TAGS.put("IRRELEVANT", "答非所问");
        REASON_TAGS.put("INCOMPLETE", "回答不完整");
        REASON_TAGS.put("OUTDATED", "信息过时");
        REASON_TAGS.put("FORMAT", "排版/格式差");
        REASON_TAGS.put("VERBOSE", "太啰嗦");
        REASON_TAGS.put("UNSAFE", "内容不安全");
        REASON_TAGS.put("OTHER", "其他");
    }

    /** 补充说明长度上限 */
    private static final int MAX_COMMENT_LEN = 1000;
    /** 单次最多选几个原因标签 */
    private static final int MAX_REASON_TAGS = 5;
    /** 明细分页单页上限 */
    private static final int MAX_PAGE_SIZE = 100;
    /** 统计窗口上限 (天) */
    private static final int MAX_STATS_DAYS = 365;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final MessageFeedbackRepository feedbackRepository;
    private final MessageRepository messageRepository;
    private final SessionRepository sessionRepository;

    // ==================== 收集 ====================

    /**
     * 提交或更新反馈 (同一用户对同一条回答重复提交即覆盖, 便于改主意)。
     *
     * @param req    反馈内容
     * @param userId 反馈人; null 时落 {@link MessageFeedbackEntity#ANONYMOUS_USER} 哨兵值
     * @return 落库后的反馈明细
     */
    @Transactional
    public FeedbackDtos.FeedbackView submit(FeedbackDtos.FeedbackRequest req, String userId) {
        if (req == null || req.getSessionId() == null || req.getSessionId().isBlank()) {
            throw new BusinessException(400, "会话 ID 不能为空");
        }
        if (req.getMessageSeq() == null) {
            throw new BusinessException(400, "缺少被评价回答的序号 (messageSeq)");
        }
        String rating = req.getRating();
        if (!MessageFeedbackEntity.RATING_UP.equals(rating) && !MessageFeedbackEntity.RATING_DOWN.equals(rating)) {
            throw new BusinessException(400, "评价类型必须为 up 或 down");
        }
        String sessionId = req.getSessionId();
        int seq = req.getMessageSeq();
        String fbUser = normalizeUserId(userId);

        assertSessionAccessible(sessionId, userId);

        MessageEntity message = messageRepository.findBySessionIdAndSeq(sessionId, seq)
                .orElseThrow(() -> new BusinessException(404, "被评价的消息不存在"));
        if (!"assistant".equals(message.getRole())) {
            throw new BusinessException(400, "只能对助手回答进行评价");
        }

        // 点踩才带原因标签; 点赞时忽略原因(点赞的价值在于给出正样本, 不需要归因)
        Set<String> tags = MessageFeedbackEntity.RATING_DOWN.equals(rating)
                ? normalizeTags(req.getReasonTags())
                : Set.of();
        String comment = trimToNull(req.getComment());
        if (comment != null && comment.length() > MAX_COMMENT_LEN) {
            throw new BusinessException(400, "补充说明过长(上限 " + MAX_COMMENT_LEN + " 字符)");
        }

        LocalDateTime now = LocalDateTime.now();
        MessageFeedbackEntity entity = feedbackRepository
                .findBySessionIdAndMessageSeqAndUserId(sessionId, seq, fbUser)
                .orElseGet(MessageFeedbackEntity::new);

        boolean isNew = entity.getId() == null;
        entity.setSessionId(sessionId);
        entity.setMessageSeq(seq);
        entity.setUserId(fbUser);
        entity.setRating(rating);
        entity.setReasonTags(tags.isEmpty() ? null : String.join(",", tags));
        entity.setComment(comment);
        // 上下文快照: 每次提交都按当前消息刷新(回答不会变, 但历史数据可能在早期版本缺快照)
        entity.setModel(message.getModel());
        entity.setToolsUsed(message.getToolsUsed());
        entity.setSourceCount(countSources(message.getSources()));
        entity.setWebSearch(detectWebSearch(message));
        entity.setMessageChars(message.getContent() == null ? 0 : message.getContent().length());
        entity.setCreatedAt(isNew ? now : entity.getCreatedAt());
        entity.setUpdatedAt(now);
        feedbackRepository.save(entity);

        log.info("回答反馈: session={} seq={} rating={} tags={} user={} (new={})",
                sessionId, seq, rating, entity.getReasonTags(), fbUser, isNew);
        return toView(entity);
    }

    /** 撤销反馈 (幂等)。 */
    @Transactional
    public void withdraw(String sessionId, Integer messageSeq, String userId) {
        if (sessionId == null || sessionId.isBlank() || messageSeq == null) {
            throw new BusinessException(400, "会话 ID 与 messageSeq 不能为空");
        }
        feedbackRepository.findBySessionIdAndMessageSeqAndUserId(sessionId, messageSeq, normalizeUserId(userId))
                .ifPresent(feedbackRepository::delete);
    }

    /** 某会话内当前用户的反馈: seq → rating, 用于回填各条回答的反馈状态。 */
    public Map<Integer, String> ratingsOfSession(String sessionId, String userId) {
        if (sessionId == null || sessionId.isBlank()) return Map.of();
        List<MessageFeedbackEntity> list =
                feedbackRepository.findBySessionIdAndUserId(sessionId, normalizeUserId(userId));
        Map<Integer, String> map = new LinkedHashMap<>(list.size());
        for (MessageFeedbackEntity f : list) {
            map.put(f.getMessageSeq(), f.getRating());
        }
        return map;
    }

    // ==================== 级联清理 ====================

    /** 会话被删除时清理其反馈 (会话内消息已被一并删除, 反馈不应残留)。 */
    @Transactional
    public void deleteBySession(String sessionId) {
        feedbackRepository.deleteBySessionId(sessionId);
    }

    /**
     * 会话历史被截断时清理受影响消息上的反馈。
     * <p>
     * 编辑历史消息 / 重新生成会删除 seq >= fromSeq 的消息并重新生成新 seq,
     * 旧 seq 上的反馈若不清理会孤悬, 并在新消息复用同一 seq 时错误地"继承"旧评价。
     */
    @Transactional
    public void deleteFromSeq(String sessionId, int fromSeq) {
        feedbackRepository.deleteBySessionIdAndMessageSeqGreaterThanEqual(sessionId, fromSeq);
    }

    // ==================== 归因视图 ====================

    /** 原因标签清单 (含展示名), 由后端下发保证前后端口径一致。 */
    public List<FeedbackDtos.ReasonTag> reasonTags() {
        List<FeedbackDtos.ReasonTag> list = new ArrayList<>(REASON_TAGS.size());
        REASON_TAGS.forEach((code, label) -> list.add(new FeedbackDtos.ReasonTag(code, label)));
        return list;
    }

    /**
     * 反馈概览。
     *
     * @param userId 为 null 表示全局 (仅管理员可请求, 判断在 Controller)
     * @param days   统计窗口天数 (1~365)
     */
    public FeedbackDtos.StatsView stats(String userId, int days) {
        int window = Math.max(1, Math.min(days, MAX_STATS_DAYS));
        LocalDateTime since = LocalDateTime.now().minusDays(window);
        List<MessageFeedbackEntity> rows = (userId == null)
                ? feedbackRepository.findByCreatedAtAfter(since)
                : feedbackRepository.findByUserIdAndCreatedAtAfter(userId, since);

        FeedbackDtos.StatsView view = new FeedbackDtos.StatsView();
        view.setDays(window);
        view.setGlobal(userId == null);
        view.setTotal(rows.size());

        long up = rows.stream().filter(r -> MessageFeedbackEntity.RATING_UP.equals(r.getRating())).count();
        view.setUp(up);
        view.setDown(rows.size() - up);
        view.setSatisfaction(rate(up, rows.size()));

        view.setReasonCounts(reasonCounts(rows));
        view.setModelStats(modelStats(rows));
        view.setDayStats(dayStats(rows, window));
        return view;
    }

    /**
     * 反馈明细分页。
     *
     * @param userId 为 null 表示全局 (仅管理员可请求)
     * @param rating 可选过滤: up / down / 空=全部
     */
    public FeedbackDtos.PageView list(String userId, String rating, int page, int size) {
        int p = Math.max(0, page);
        int s = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        Pageable pageable = PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "createdAt"));
        String r = (rating == null || rating.isBlank()) ? null : rating.trim().toLowerCase();
        if (r != null && !MessageFeedbackEntity.RATING_UP.equals(r) && !MessageFeedbackEntity.RATING_DOWN.equals(r)) {
            throw new BusinessException(400, "rating 只能为 up 或 down");
        }

        Page<MessageFeedbackEntity> result;
        if (userId == null) {
            result = (r == null) ? feedbackRepository.findAll(pageable)
                    : feedbackRepository.findByRating(r, pageable);
        } else {
            result = (r == null) ? feedbackRepository.findByUserId(userId, pageable)
                    : feedbackRepository.findByUserIdAndRating(userId, r, pageable);
        }

        FeedbackDtos.PageView view = new FeedbackDtos.PageView();
        view.setContent(result.getContent().stream().map(this::toView).toList());
        view.setPage(p);
        view.setSize(s);
        view.setTotalElements(result.getTotalElements());
        view.setTotalPages(result.getTotalPages());
        return view;
    }

    // ==================== 内部工具 ====================

    /** 归属校验: 只能对自己可访问的会话提交反馈 (越权即不存在, 避免资源枚举) */
    private void assertSessionAccessible(String sessionId, String userId) {
        if (userId == null) {
            // 认证关闭(单用户模式): 退化为主键存在性校验
            if (!sessionRepository.existsById(sessionId)) {
                throw new BusinessException(404, "会话不存在");
            }
            return;
        }
        if (sessionRepository.findByIdAndUserId(sessionId, userId).isEmpty()) {
            throw new BusinessException(404, "会话不存在");
        }
    }

    /** 无用户体系时用哨兵值占位, 使唯一约束 (session_id, message_seq, user_id) 依然生效 */
    static String normalizeUserId(String userId) {
        return (userId == null || userId.isBlank()) ? MessageFeedbackEntity.ANONYMOUS_USER : userId;
    }

    /** 校验并规范化原因标签: 丢弃未知 code; 顺序按 {@link #REASON_TAGS} 声明顺序, 保证聚合一致 */
    private Set<String> normalizeTags(List<String> raw) {
        if (raw == null || raw.isEmpty()) return Set.of();
        Set<String> picked = new LinkedHashSet<>();
        for (String t : raw) {
            if (t == null) continue;
            String code = t.trim().toUpperCase();
            if (!code.isEmpty() && REASON_TAGS.containsKey(code)) picked.add(code);
            if (picked.size() >= MAX_REASON_TAGS) break;
        }
        return picked;
    }

    /** 引用来源条数 (sources 为 JSON 数组字符串) */
    private int countSources(String sourcesJson) {
        if (sourcesJson == null || sourcesJson.isBlank()) return 0;
        try {
            List<Map<String, Object>> list = MAPPER.readValue(sourcesJson, new TypeReference<>() {});
            return list == null ? 0 : list.size();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 是否走了联网检索 (tools_used 中带 web_search) */
    private boolean detectWebSearch(MessageEntity message) {
        String tools = message.getToolsUsed();
        return tools != null && tools.contains("web_search");
    }

    private List<FeedbackDtos.ReasonCount> reasonCounts(List<MessageFeedbackEntity> rows) {
        Map<String, Long> counter = new LinkedHashMap<>();
        REASON_TAGS.keySet().forEach(code -> counter.put(code, 0L));
        for (MessageFeedbackEntity r : rows) {
            if (!MessageFeedbackEntity.RATING_DOWN.equals(r.getRating()) || r.getReasonTags() == null) continue;
            for (String code : r.getReasonTags().split(",")) {
                String c = code.trim();
                if (!c.isEmpty()) counter.merge(c, 1L, Long::sum);
            }
        }
        List<FeedbackDtos.ReasonCount> list = new ArrayList<>();
        counter.forEach((code, count) -> {
            if (count == 0) return;
            FeedbackDtos.ReasonCount rc = new FeedbackDtos.ReasonCount();
            rc.setCode(code);
            rc.setLabel(REASON_TAGS.getOrDefault(code, code));
            rc.setCount(count);
            list.add(rc);
        });
        list.sort(Comparator.comparingLong(FeedbackDtos.ReasonCount::getCount).reversed());
        return list;
    }

    private List<FeedbackDtos.ModelStat> modelStats(List<MessageFeedbackEntity> rows) {
        Map<String, long[]> agg = new LinkedHashMap<>();
        for (MessageFeedbackEntity r : rows) {
            String model = (r.getModel() == null || r.getModel().isBlank()) ? "(未知)" : r.getModel();
            long[] c = agg.computeIfAbsent(model, k -> new long[2]);
            if (MessageFeedbackEntity.RATING_UP.equals(r.getRating())) c[0]++;
            else c[1]++;
        }
        List<FeedbackDtos.ModelStat> list = new ArrayList<>();
        agg.forEach((model, c) -> {
            long total = c[0] + c[1];
            FeedbackDtos.ModelStat ms = new FeedbackDtos.ModelStat();
            ms.setModel(model);
            ms.setUp(c[0]);
            ms.setDown(c[1]);
            ms.setTotal(total);
            ms.setSatisfaction(rate(c[0], total));
            list.add(ms);
        });
        list.sort(Comparator.comparingLong(FeedbackDtos.ModelStat::getTotal).reversed());
        return list;
    }

    /** 按天补齐无数据的日期, 便于前端直接画趋势 */
    private List<FeedbackDtos.DayStat> dayStats(List<MessageFeedbackEntity> rows, int window) {
        Map<String, long[]> agg = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        for (int i = window - 1; i >= 0; i--) {
            agg.put(today.minusDays(i).format(DAY_FMT), new long[2]);
        }
        for (MessageFeedbackEntity r : rows) {
            if (r.getCreatedAt() == null) continue;
            long[] c = agg.get(r.getCreatedAt().toLocalDate().format(DAY_FMT));
            if (c == null) continue; // 窗口外的历史数据 (时区边界), 忽略
            if (MessageFeedbackEntity.RATING_UP.equals(r.getRating())) c[0]++;
            else c[1]++;
        }
        List<FeedbackDtos.DayStat> list = new ArrayList<>(agg.size());
        agg.forEach((date, c) -> {
            FeedbackDtos.DayStat ds = new FeedbackDtos.DayStat();
            ds.setDate(date);
            ds.setUp(c[0]);
            ds.setDown(c[1]);
            ds.setTotal(c[0] + c[1]);
            list.add(ds);
        });
        return list;
    }

    private static double rate(long part, long total) {
        return total == 0 ? 0d : Math.round(part * 1000d / total) / 1000d;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private FeedbackDtos.FeedbackView toView(MessageFeedbackEntity e) {
        FeedbackDtos.FeedbackView v = new FeedbackDtos.FeedbackView();
        v.setId(e.getId());
        v.setSessionId(e.getSessionId());
        v.setMessageSeq(e.getMessageSeq());
        v.setUserId(e.getUserId());
        v.setRating(e.getRating());
        v.setReasonTags(Optional.ofNullable(e.getReasonTags())
                .filter(s -> !s.isBlank())
                .map(s -> List.of(s.split(",")))
                .orElse(List.of()));
        v.setComment(e.getComment());
        v.setModel(e.getModel());
        v.setToolsUsed(e.getToolsUsed());
        v.setSourceCount(e.getSourceCount());
        v.setWebSearch(e.isWebSearch());
        v.setMessageChars(e.getMessageChars());
        v.setCreatedAt(e.getCreatedAt());
        v.setUpdatedAt(e.getUpdatedAt());
        return v;
    }
}
