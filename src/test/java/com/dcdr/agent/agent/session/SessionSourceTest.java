package com.dcdr.agent.agent.session;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.dto.SessionDTO;
import com.dcdr.agent.repository.SessionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V3.3 会话来源分类: 定时任务 / 工作流自动执行产生的会话不能混进对话列表。
 *
 * <p>这组断言守的是一条产品约定, 而不是某个内部实现:
 * <ul>
 *   <li>对话侧边栏(DTO 列表)默认只有用户自己开的会话;</li>
 *   <li>「自动化 → 任务对话」用 {@code automation} 能同时拿到定时任务与工作流两种;</li>
 *   <li>自动会话按 (来源, 来源聚合 ID) 收敛保留数量 —— 分钟级 cron 不会把表撑爆。</li>
 * </ul>
 *
 * <p>用 H2 内存库跑(profile: test), 零外部依赖; 每个用例在事务里回滚, 不会互相污染。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SessionSourceTest {

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private SessionRepository sessionRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /** 每个用例用独立 userId 隔离数据, 避免与同上下文里其他测试留下的会话相互干扰 */
    private String newUser() {
        return "u-src-" + System.nanoTime();
    }

    @Test
    @DisplayName("对话列表默认只返回用户自己开的会话")
    void defaultFilterReturnsUserSessionsOnly() {
        String uid = newUser();
        sessionManager.createSession("我的对话", null, null, uid);
        sessionManager.createSession("定时任务 · 早报", null, null, uid,
                SessionSource.SCHEDULE.code(), "sched_a");
        sessionManager.createSession("工作流 · 抓取", null, null, uid,
                SessionSource.WORKFLOW.code(), "wf_a");

        List<SessionDTO> list = sessionManager.listSessions(uid, false, SessionSource.parseFilter(null));

        assertEquals(1, list.size(), "对话列表里应只剩用户自己开的会话, 实际: "
                + list.stream().map(SessionDTO::getTitle).toList());
        assertEquals(SessionSource.USER.code(), list.get(0).getSource());
    }

    @Test
    @DisplayName("automation 同时覆盖定时任务与工作流两种来源")
    void automationFilterCoversBothAutoSources() {
        String uid = newUser();
        sessionManager.createSession("我的对话", null, null, uid);
        sessionManager.createSession("定时任务 · 早报", null, null, uid,
                SessionSource.SCHEDULE.code(), "sched_b");
        sessionManager.createSession("工作流 · 抓取", null, null, uid,
                SessionSource.WORKFLOW.code(), "wf_b");

        List<SessionDTO> automation = sessionManager.listSessions(uid, false,
                SessionSource.parseFilter("automation"));

        assertEquals(2, automation.size(), "任务对话应包含定时任务 + 工作流各一条");
        Set<String> sources = automation.stream().map(SessionDTO::getSource).collect(Collectors.toSet());
        assertTrue(sources.contains(SessionSource.SCHEDULE.code()), "缺少定时任务来源");
        assertTrue(sources.contains(SessionSource.WORKFLOW.code()), "缺少工作流来源");
        // sourceRef 要透传到前端: 「任务对话」靠它显示"来自任务 xxx", 否则只能看到一串 UUID
        assertTrue(automation.stream().anyMatch(s -> "sched_b".equals(s.getSourceRef())),
                "自动化会话应带出来源聚合 ID");
    }

    @Test
    @DisplayName("自动会话按 (来源, 来源聚合 ID) 收敛到保留上限")
    void trimAutoSessionsKeepsRecentOnly() {
        String uid = newUser();
        for (int i = 0; i < 5; i++) {
            sessionManager.createSession("定时任务 · 第 " + i + " 次", null, null, uid,
                    SessionSource.SCHEDULE.code(), "sched_keep");
        }
        // 另一个任务的数据不能被连带清掉
        sessionManager.createSession("定时任务 · 别的任务", null, null, uid,
                SessionSource.SCHEDULE.code(), "sched_other");

        sessionManager.trimAutoSessions(SessionSource.SCHEDULE.code(), "sched_keep", 2);

        List<SessionDTO> kept = sessionManager.listSessions(uid, false,
                SessionSource.parseFilter("schedule"));
        assertEquals(3, kept.size(), "该任务应只保留 2 条, 加上另一个任务那 1 条共 3 条");
        assertEquals(1, kept.stream().filter(s -> "sched_other".equals(s.getSourceRef())).count(),
                "收敛必须按 sourceRef 分组, 不能把别的任务的会话删掉");
        // 留的是最近的: 第 3、4 次
        Set<String> titles = kept.stream().map(SessionDTO::getTitle).collect(Collectors.toSet());
        assertTrue(titles.contains("定时任务 · 第 4 次"), "最新的执行应该保留, 实际: " + titles);
        assertTrue(!titles.contains("定时任务 · 第 0 次"), "最早的执行应被清理, 实际: " + titles);
    }

    @Test
    @DisplayName("无法识别的来源入参按 400 拒绝, 而不是静默返回空列表")
    void unknownSourceRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> SessionSource.parseFilter("boss"));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("存量回填: 带旧前缀的自动会话被改归来源, 用户自己的会话不受影响")
    void legacyTitlesAreReclassified() {
        String uid = newUser();
        // 旧版二进制写出来的数据: 标题带前缀, 但没有 source(加列后回填为 user)
        sessionManager.createSession("schedule: 每日早报", null, null, uid);
        sessionManager.createSession("workflow: 抓取资讯", null, null, uid);
        sessionManager.createSession("帮我写个周报", null, null, uid);

        assertEquals(1, sessionRepository.reclassifyLegacyAutoSessions(
                SessionSource.SCHEDULE.code(), "schedule:%"));
        assertEquals(1, sessionRepository.reclassifyLegacyAutoSessions(
                SessionSource.WORKFLOW.code(), "workflow:%"));
        // 一级缓存里的实体还是旧值, 清掉再查, 否则断言读到的是内存态而不是库里的结果
        entityManager.flush();
        entityManager.clear();

        List<SessionDTO> userList = sessionManager.listSessions(uid, false, SessionSource.parseFilter(null));
        assertEquals(1, userList.size(), "回填后对话列表应只剩用户自己的那条");
        assertEquals("帮我写个周报", userList.get(0).getTitle());

        List<SessionDTO> autoList = sessionManager.listSessions(uid, false,
                SessionSource.parseFilter("automation"));
        assertEquals(2, autoList.size(), "两条旧前缀会话应进入任务对话");
        // 再跑一次是幂等的: 已经归好类的行不会被重复处理
        assertEquals(0, sessionRepository.reclassifyLegacyAutoSessions(
                SessionSource.SCHEDULE.code(), "schedule:%"));
    }
}
