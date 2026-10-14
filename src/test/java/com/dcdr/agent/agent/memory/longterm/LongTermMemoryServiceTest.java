package com.dcdr.agent.agent.memory.longterm;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.dto.MemoryDtos.MemoryView;
import com.dcdr.agent.dto.MemoryDtos.SaveRequest;
import com.dcdr.agent.entity.MemoryEntity;
import com.dcdr.agent.repository.AgentDefinitionRepository;
import com.dcdr.agent.repository.MemoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆生命周期(归档 / 恢复 / 过期归档 / 专家删除连带)的测试。
 *
 * <p>这些分支此前<b>完全没有覆盖</b> —— 而它们正是"记忆只增不减"的收口处。
 * 状态流转写错的表现是"记忆凭空消失"或"删了还在被注入", 两者都不会报错,
 * 也不会让任何既有测试变红, 只能靠这里钉住。
 */
class LongTermMemoryServiceTest {

    private final MemoryRepository repository = mock(MemoryRepository.class);
    private final AgentDefinitionRepository agentRepository = mock(AgentDefinitionRepository.class);
    private final AutoExtractPreference preference = stubPreference();
    private final LongTermMemoryService service = new LongTermMemoryService(
            repository, agentRepository, mock(MemoryRelevanceScorer.class), preference);

    /**
     * 概览会读"用户三态"来下发开关状态, 而 {@code choice()} 的契约是<b>非空</b>
     * (未表态时返回 {@link AutoExtractChoice#DEFAULT}) —— mock 的默认返回值(null)会直接
     * 把 {@code summary()} 打成 NPE。故在构造 service 之前就把桩打上, 免得每个碰概览的用例各踩一次。
     */
    private static AutoExtractPreference stubPreference() {
        AutoExtractPreference p = mock(AutoExtractPreference.class);
        when(p.choice("u1")).thenReturn(AutoExtractChoice.DEFAULT);
        return p;
    }

    @Test
    @DisplayName("召回策略说明由实际参数生成: 条数上限/重要度阈值/常驻类型改了, 它不会停在旧文案上")
    void retrievalDescriptionFollowsActualParams() {
        String desc = service.summary("u1").getRetrieval();

        // 这几条数字/名称都来自代码里的常量与枚举, 而不是另写一份字面量。
        // 之所以钉住: 这段说明原本就是手写的, 行为改了几轮它却一直没变, 用户看到的解释
        // 与实际行为对不上。断言"包含当前参数值"而不是比对整句 —— 改措辞不该让测试变红,
        // 但把它改回手写固定文案就会。
        assertTrue(desc.contains("20"), "常驻条数上限应来自 RESIDENT_LIMIT: " + desc);
        assertTrue(desc.contains("10"), "条件触发条数上限应来自 TRIGGERED_LIMIT: " + desc);
        assertTrue(desc.contains("0.90"), "重要度阈值应来自 RESIDENT_IMPORTANCE: " + desc);
        assertTrue(desc.contains("画像"), "常驻类型应从 MemoryType.resident() 生成: " + desc);
        assertTrue(desc.contains("向量"), "必须说明当前未启用向量检索, 否则用户会以为语义匹配已生效: " + desc);
    }

    @Test
    @DisplayName("写入来源: 落进实体字段并映射成视图; 存量行按来源会话兜底归因")
    void originIsPersistedAndMapped() {
        ArgumentCaptor<MemoryEntity> saved = ArgumentCaptor.forClass(MemoryEntity.class);
        when(repository.save(saved.capture())).thenAnswer(inv -> inv.getArgument(0));
        SaveRequest req = new SaveRequest();
        req.setContent("用户偏好中文回答");

        // 对话中用户要求记的
        MemoryView requested = service.create("u1", req, MemoryOrigin.REQUESTED);
        assertEquals("REQUESTED", saved.getValue().getSourceKind(),
                "来源必须落库: 空了会被兜底归因成 AUTO, 那对'你要求记的'是错的 —— 用户会以为那句话没生效");
        assertEquals(MemoryOrigin.REQUESTED.label(), requested.getSourceKindLabel());

        // 默认重载(记忆页新增)落到 MANUAL
        assertEquals("MANUAL", service.create("u1", req).getSourceKind());

        // 存量行: sourceKind 为空, 但带来源会话 —— 该列上线前只有自动抽取会在对话里写记忆
        MemoryEntity legacy = memory("m_old", "u1", "ACTIVE");
        legacy.setSourceSessionId("s9");
        when(repository.findByUserIdAndStatusOrderByPinnedDescImportanceDescCreatedAtDesc("u1", "ACTIVE"))
                .thenReturn(List.of(legacy));
        MemoryView old = service.list("u1", false).get(0);
        assertEquals("AUTO", old.getSourceKind());
        assertEquals(MemoryOrigin.AUTO.label(), old.getSourceKindLabel());
    }

    private static MemoryEntity memory(String id, String userId, String status) {
        MemoryEntity e = new MemoryEntity();
        e.setId(id);
        e.setUserId(userId);
        e.setScope(MemoryScope.USER.name());
        e.setType(MemoryType.PREFERENCE.name());
        e.setContent("偏好中文回答");
        e.setStatus(status);
        return e;
    }

    // ===== 归档 / 恢复 =====

    @Test
    @DisplayName("归档: 置为 ARCHIVED 并落库; 重复归档不重复写库")
    void archiveMovesToArchived() {
        MemoryEntity e = memory("m1", "u1", MemoryStatus.ACTIVE.name());
        when(repository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(e));

        assertEquals("ARCHIVED", service.archive("u1", "m1").getStatus());
        assertNotNull(e.getUpdatedAt());
        verify(repository).save(e);

        // 幂等: 已是目标状态就不再写 —— 否则每次点击都会让 updatedAt 无谓抖动
        service.archive("u1", "m1");
        verify(repository, times(1)).save(any());
    }

    @Test
    @DisplayName("恢复: 已删除与已归档都能回到活跃 —— 这是软删除的撤销入口")
    void restoreFromDeletedAndArchived() {
        MemoryEntity deleted = memory("m1", "u1", MemoryStatus.DELETED.name());
        when(repository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(deleted));
        assertEquals("ACTIVE", service.restore("u1", "m1").getStatus());

        MemoryEntity archived = memory("m2", "u1", MemoryStatus.ARCHIVED.name());
        when(repository.findByIdAndUserId("m2", "u1")).thenReturn(Optional.of(archived));
        assertEquals("ACTIVE", service.restore("u1", "m2").getStatus());
    }

    @Test
    @DisplayName("恢复: 未登录 403、越权或不存在 404 —— 都不静默成功")
    void restoreRejectsMissingUserAndMemory() {
        assertThrows(BusinessException.class, () -> service.restore(null, "m1"),
                "未登录时不能操作记忆");

        when(repository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.empty());
        assertThrows(BusinessException.class, () -> service.restore("u1", "m1"),
                "越权与不存在应统一 404, 不能返回成功");
    }

    @Test
    @DisplayName("删除: 软删置为 DELETED, 重复删除不重复写库")
    void deleteIsSoftAndIdempotent() {
        MemoryEntity e = memory("m1", "u1", MemoryStatus.ACTIVE.name());
        when(repository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(e));

        service.delete("u1", "m1");
        assertEquals("DELETED", e.getStatus());
        service.delete("u1", "m1");
        verify(repository, times(1)).save(any());
    }

    // ===== 专家删除连带 =====

    @Test
    @DisplayName("删除专家: 归档它名下的活跃记忆, 不留悬空 agentId")
    void archiveByAgentArchivesOwnedMemories() {
        MemoryEntity m1 = memory("m1", "u1", MemoryStatus.ACTIVE.name());
        MemoryEntity m2 = memory("m2", "u1", MemoryStatus.ACTIVE.name());
        when(repository.findByAgentIdAndStatus("agent-1", MemoryStatus.ACTIVE.name()))
                .thenReturn(List.of(m1, m2));

        assertEquals(2, service.archiveByAgent("agent-1"));
        assertEquals("ARCHIVED", m1.getStatus());
        assertEquals("ARCHIVED", m2.getStatus());
        verify(repository).saveAll(List.of(m1, m2));
    }

    @Test
    @DisplayName("删除专家: agentId 为空时不查库(否则会变成一次全表扫描)")
    void archiveByAgentSkipsBlankAgentId() {
        assertEquals(0, service.archiveByAgent(null));
        assertEquals(0, service.archiveByAgent("   "));
        verify(repository, never()).findByAgentIdAndStatus(isNull(), any());
    }

    // ===== 过期归档 =====

    @Test
    @DisplayName("过期归档: 只扫活跃条目, 且没有过期项时不触发空批量写")
    void archiveExpiredOnlyTouchesActiveExpired() {
        MemoryEntity expired = memory("m1", "u1", MemoryStatus.ACTIVE.name());
        when(repository.findByStatusAndExpiresAtBefore(eq(MemoryStatus.ACTIVE.name()), any(LocalDateTime.class)))
                .thenReturn(List.of(expired));

        assertEquals(1, service.archiveExpired());
        assertEquals("ARCHIVED", expired.getStatus());
        // 查询必须限定 ACTIVE: 若把已归档/已删除的也算进来, 归档状态会被反复重写
        verify(repository).findByStatusAndExpiresAtBefore(eq("ACTIVE"), any(LocalDateTime.class));
        verify(repository, times(1)).saveAll(any());

        when(repository.findByStatusAndExpiresAtBefore(any(), any())).thenReturn(List.of());
        assertEquals(0, service.archiveExpired());
        verify(repository, times(1)).saveAll(any());
    }
}
