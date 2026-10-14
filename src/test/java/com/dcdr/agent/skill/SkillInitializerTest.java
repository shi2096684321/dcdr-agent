package com.dcdr.agent.skill;

import com.dcdr.agent.entity.SkillDefinitionEntity;
import com.dcdr.agent.repository.SkillRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 内置技能初始化器单元测试 (V3.1 专家体系)。
 *
 * <p>这里锁的是一条<b>产品规则</b>而不是实现细节: 内置技能必须是<b>纯知识型</b> ——
 * 只有流程/判据/输出格式, 不依赖任何工具、不需要执行能力。
 * <p>为什么值得单独测: 这条规则是"开箱即用"的<b>唯一前提</b>。一旦有人往内置技能里加了
 * 依赖工具或声明执行能力, 它立刻退化成"用户还得先去启用某个工具才能用" —— 而这正是
 * 技能市场那条路走不通的原因。改坏它不会有任何编译错误, 只会在用户那里表现为"内置技能也用不了"。
 */
class SkillInitializerTest {

    private static final Set<String> EXPECTED = Set.of(
            "weekly-report", "meeting-notes", "code-review", "requirement-spec");

    @Test
    @DisplayName("首次启动: 插入全部内置技能")
    void insertsAllPresetsOnFirstRun() {
        SkillRepository repo = mock(SkillRepository.class);
        when(repo.findByUserIdIsNullOrderByCreatedAtAsc()).thenReturn(List.of());

        new SkillInitializer(repo).run(null);

        Set<String> names = captured(repo).stream()
                .map(SkillDefinitionEntity::getName)
                .collect(Collectors.toSet());
        assertEquals(EXPECTED, names);
    }

    @Test
    @DisplayName("内置技能必须是纯知识型: 不依赖工具、不需要执行能力")
    void presetsArePureKnowledge() {
        SkillRepository repo = mock(SkillRepository.class);
        when(repo.findByUserIdIsNullOrderByCreatedAtAsc()).thenReturn(List.of());
        new SkillInitializer(repo).run(null);

        for (SkillDefinitionEntity s : captured(repo)) {
            assertTrue(SkillJson.readList(s.getRequiredTools()).isEmpty(),
                    s.getName() + " 不应依赖任何工具 —— 否则用户会撞上'能力未启用', 内置技能就不再是开箱即用");
            assertFalse(s.isHasExecutable(), s.getName() + " 不应声明需要执行能力");
            assertNull(s.getUserId(), s.getName() + " 内置技能的 userId 必须为 null(不可删除保护的依据)");
            assertEquals("builtin", s.getSource());
            assertTrue(s.isEnabled(), s.getName() + " 内置技能应当默认启用");
            assertTrue(s.getDescription() != null && s.getDescription().length() > 15,
                    s.getName() + " 缺少简介 —— 它是模型判断'要不要用'的唯一信号, 写不清就等于技能不存在");
            assertTrue(s.getInstructions() != null && s.getInstructions().length() > 200,
                    s.getName() + " 正文过短, 不像一份可执行的流程/规范");
        }
    }

    @Test
    @DisplayName("已存在的不重复插入, 也不覆盖(管理员改过的或被停用的不该被冲掉)")
    void doesNotOverwriteExistingPresets() {
        SkillRepository repo = mock(SkillRepository.class);
        SkillDefinitionEntity existing = new SkillDefinitionEntity();
        existing.setName("weekly-report");
        when(repo.findByUserIdIsNullOrderByCreatedAtAsc()).thenReturn(List.of(existing));

        new SkillInitializer(repo).run(null);

        List<SkillDefinitionEntity> inserted = captured(repo);
        assertTrue(inserted.stream().noneMatch(s -> "weekly-report".equals(s.getName())),
                "已存在的内置技能不得再次写入 —— 覆盖会冲掉管理员的修改, 也会把用户主动停用的状态改回启用");
        assertEquals(EXPECTED.size() - 1, inserted.size(), "只应补插缺失的那些");
    }

    @Test
    @DisplayName("多实例同时首启: 撞唯一约束静默跳过, 不让启动失败")
    void concurrentStartupDoesNotFail() {
        SkillRepository repo = mock(SkillRepository.class);
        when(repo.findByUserIdIsNullOrderByCreatedAtAsc()).thenReturn(List.of());
        when(repo.saveAndFlush(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup"));

        // 另一个实例已抢先插入时, 本实例必须静默跳过 —— 从 ApplicationRunner 抛出异常会导致启动失败
        assertDoesNotThrow(() -> new SkillInitializer(repo).run(null));
    }

    private static List<SkillDefinitionEntity> captured(SkillRepository repo) {
        ArgumentCaptor<SkillDefinitionEntity> cap = ArgumentCaptor.forClass(SkillDefinitionEntity.class);
        verify(repo, atLeastOnce()).saveAndFlush(cap.capture());
        return cap.getAllValues();
    }
}
