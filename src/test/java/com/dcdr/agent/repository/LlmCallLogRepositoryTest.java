package com.dcdr.agent.repository;

import com.dcdr.agent.entity.LlmCallLogEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link LlmCallLogRepository#findForList} 的筛选语义测试。
 *
 * <p><b>为什么要专门测"来源=user 时包含 null"</b>: V3.3 之前 {@code llm_call_logs} 没有
 * {@code source} 列, 存量行全是 null, 而那时只有用户对话会写日志。若按
 * {@code source = 'user'} 精确匹配, <b>历史记录会从"用户对话"筛选结果里整批消失</b> ——
 * 不报错、不告警, 用户只会觉得"以前的日志被删了"。这条口径必须钉住。
 *
 * <p>测试方式与 {@code SessionSourceTest} 一致: 真实 JPA + 内存库, 不 mock ——
 * 要验证的正是 JPQL 在数据库里的实际行为, mock 掉就等于什么都没测。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LlmCallLogRepositoryTest {

    @Autowired
    private LlmCallLogRepository repository;

    @Test
    @DisplayName("按 user 筛选: 必须把历史 null 行也算进来")
    void userFilterIncludesLegacyNullRows() {
        save("u-1", null);          // V3.3 之前的存量行
        save("u-1", "user");
        save("u-1", "schedule");

        List<LlmCallLogEntity> rows = pageOf("u-1", "user");
        assertEquals(2, rows.size(), "存量 null 行必须算作用户对话, 否则历史日志会凭空消失");
    }

    @Test
    @DisplayName("按 schedule 筛选: 只返回定时任务的记录")
    void scheduleFilterExcludesOthers() {
        save("u-1", null);
        save("u-1", "user");
        save("u-1", "schedule");

        List<LlmCallLogEntity> rows = pageOf("u-1", "schedule");
        assertEquals(1, rows.size());
        assertEquals("schedule", rows.get(0).getSource());
    }

    @Test
    @DisplayName("不传来源: 三类全部返回")
    void noSourceFilterReturnsAll() {
        save("u-1", null);
        save("u-1", "user");
        save("u-1", "schedule");

        assertEquals(3, pageOf("u-1", null).size());
    }

    @Test
    @DisplayName("userId 为 null(管理员全局视图): 跨用户返回, 但仍受来源约束")
    void adminViewCrossesUsers() {
        save("u-1", "schedule");
        save("u-2", "schedule");
        save("u-2", "user");

        assertEquals(2, pageOf(null, "schedule").size());
        assertEquals(3, pageOf(null, null).size());
    }

    private List<LlmCallLogEntity> pageOf(String userId, String source) {
        return repository.findForList(userId, source, PageRequest.of(0, 50)).getContent();
    }

    private void save(String userId, String source) {
        LlmCallLogEntity e = new LlmCallLogEntity();
        e.setId(java.util.UUID.randomUUID().toString());
        e.setTurnId("t1");
        e.setUserId(userId);
        e.setSessionId("s1");
        e.setSource(source);
        e.setUserMessage("hi");
        e.setEndSignal("ON_COMPLETE");
        e.setCreatedAt(LocalDateTime.now());
        repository.save(e);
    }
}
