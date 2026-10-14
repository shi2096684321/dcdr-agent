package com.dcdr.agent.skill;

import com.dcdr.agent.config.SystemPromptProperties.PromptFragment;
import com.dcdr.agent.entity.SkillDefinitionEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 技能目录注入单元测试 (V3.1 专家体系)。
 *
 * <p>锁住三条容易在后续改动中悄悄退化的性质:
 * <ol>
 *   <li><b>无技能时不注入</b> —— 否则每轮对话都会多出一条空的 system 消息, 白占上下文;</li>
 *   <li><b>只给目录、不给正文</b> —— 这是渐进式披露的全部意义。一旦有人"顺手"把
 *       instructions 也拼进来, 上下文预算就悄悄崩了, 而且功能表面看还是好的;</li>
 *   <li><b>顺序稳定</b> —— 目录按名称排序。若改成按更新时间, 用户改一个无关技能就会让
 *       整段 prompt 变化, 上游的 prompt 指纹/缓存比较随之失效。</li>
 * </ol>
 */
class SkillCatalogProviderTest {

    private final SkillCatalogProvider provider =
            new SkillCatalogProvider(mock(SkillService.class));

    @Test
    @DisplayName("无技能时不注入(null 而非空消息)")
    void noSkillsMeansNoPrompt() {
        assertNull(provider.catalog(null));
        assertNull(provider.catalog(List.of()));
    }

    @Test
    @DisplayName("只输出名称/展示名/简介与依赖工具, 绝不带技能正文")
    void catalogCarriesNoInstructions() {
        SkillDefinitionEntity s = skill("contract-review", "合同审查", "审查采购合同条款风险");
        s.setInstructions("SECRET-INSTRUCTION-BODY-不应出现在目录里");

        PromptFragment f = provider.catalog(List.of(s));
        assertNotNull(f);
        assertEquals(SkillCatalogProvider.PROMPT_NAME, f.getName());
        assertTrue(f.getText().contains("contract-review"));
        assertTrue(f.getText().contains("合同审查"));
        assertTrue(f.getText().contains("审查采购合同条款风险"));
        assertTrue(f.getText().contains("skill_load"), "必须告诉模型怎么加载正文");
        assertTrue(!f.getText().contains("SECRET-INSTRUCTION-BODY"),
                "技能正文不得进入目录 —— 否则渐进式披露失效, 上下文预算被常驻内容吃掉");
    }

    @Test
    @DisplayName("声明了依赖工具时在目录中标注, 便于模型判断能力是否可用")
    void catalogShowsRequiredTools() {
        SkillDefinitionEntity s = skill("excel-helper", "表格助手", "生成与处理 Excel");
        s.setRequiredTools(SkillJson.writeList(List.of("code_interpreter")));
        PromptFragment f = provider.catalog(List.of(s));
        assertNotNull(f);
        assertTrue(f.getText().contains("code_interpreter"));
    }

    @Test
    @DisplayName("顺序按名称升序(与更新时间无关, 保证 prompt 稳定)")
    void catalogIsStableSorted() {
        SkillDefinitionEntity a = skill("zeta", "Z", "z 技能");
        a.setUpdatedAt(java.time.LocalDateTime.now());
        SkillDefinitionEntity b = skill("alpha", "A", "a 技能");
        b.setUpdatedAt(java.time.LocalDateTime.now().minusDays(10));

        PromptFragment f = provider.catalog(List.of(a, b));
        assertNotNull(f);
        int alpha = f.getText().indexOf("alpha");
        int zeta = f.getText().indexOf("zeta");
        assertTrue(alpha >= 0 && zeta >= 0 && alpha < zeta,
                "应按名称排序; 若按更新时间排序, 改一个无关技能就会让整段 prompt 变化");
    }

    @Test
    @DisplayName("超出上限时截断并显式告知(不静默丢弃)")
    void catalogTruncationIsVisible() {
        List<SkillDefinitionEntity> many = new ArrayList<>();
        for (int i = 0; i < SkillCatalogProvider.MAX_CATALOG_SKILLS + 5; i++) {
            many.add(skill("skill-" + String.format("%03d", i), "技能" + i, "描述" + i));
        }
        PromptFragment f = provider.catalog(many);
        assertNotNull(f);
        assertTrue(f.getText().contains("共 " + many.size() + " 个技能"),
                "截断必须显式说明, 否则用户会以为'技能没生效'");
        // 最后一个技能不应出现(已被截断)
        assertTrue(!f.getText().contains("skill-0" + (SkillCatalogProvider.MAX_CATALOG_SKILLS + 4)));
    }

    @Test
    @DisplayName("简介过长时截断, 不挤占目录空间")
    void longDescriptionIsClipped() {
        SkillDefinitionEntity s = skill("big-desc", "长简介", "描".repeat(1000));
        PromptFragment f = provider.catalog(List.of(s));
        assertNotNull(f);
        // 目录总长度应远小于原始简介长度
        assertTrue(f.getText().length() < 900, "单条简介必须截断, 实际 " + f.getText().length());
    }

    // ===== 用户手动指定技能 =====

    @Test
    @DisplayName("手动指定: 直接给正文, 且不再出现在目录里(避免同一份正文注入两遍)")
    void manualSelectionInjectsBodyAndExcludesFromCatalog() {
        SkillDefinitionEntity picked = skill("weekly-report", "周报撰写", "写周报时使用");
        picked.setInstructions("BODY-OF-WEEKLY-REPORT");
        SkillDefinitionEntity other = skill("contract-review", "合同审查", "审查合同");

        SkillCatalogProvider p = providerWith(List.of(picked, other));
        List<PromptFragment> fs = p.skillPrompts("u1", List.of("weekly-report"));

        assertEquals(2, fs.size(), "应产出 [手动正文, 其余目录] 两个片段");
        assertEquals(SkillCatalogProvider.MANUAL_PROMPT_NAME, fs.get(0).getName());
        assertTrue(fs.get(0).getText().contains("BODY-OF-WEEKLY-REPORT"),
                "用户显式指定的技能必须直接给正文 —— 否则还要赌模型会不会调 skill_load");
        assertTrue(!fs.get(1).getText().contains("weekly-report"),
                "已手动注入的技能必须从目录剔除, 否则模型会再 skill_load 一次, 正文进上下文两遍");
        assertTrue(fs.get(1).getText().contains("contract-review"), "其余技能照旧走目录");
    }

    @Test
    @DisplayName("手动指定: 只认当前用户可见且已启用的技能")
    void manualSelectionRejectsInvisibleSkills() {
        SkillDefinitionEntity mine = skill("mine", "我的", "可见且已启用");
        mine.setInstructions("MY-BODY");

        // visibleEnabledSkills 是"可见 + 已启用"的唯一准绳: 他人的私有技能、已停用的技能都不在其中。
        // 这里刻意只返回 mine, 然后请求一个"他人私有"的名字 —— 它必须不被当成技能注入
        SkillCatalogProvider p = providerWith(List.of(mine));
        String manual = p.skillPrompts("u1", List.of("someone-elses-private", "mine")).get(0).getText();

        assertTrue(manual.contains("### 技能: mine"), "可见技能应作为技能正文注入");
        assertTrue(manual.contains("MY-BODY"));
        assertTrue(!manual.contains("### 技能: someone-elses-private"),
                "越权技能名绝不能被当成技能注入正文 —— 否则构造一个名字就能读到他人的私有技能");
        assertTrue(manual.contains("someone-elses-private"),
                "但要在'不可用'里如实列出, 否则用户会以为自己选中的技能生效了");
    }

    @Test
    @DisplayName("手动指定: 全部不可用时只给提示, 目录照常注入")
    void manualSelectionAllUnavailableStillWarns() {
        SkillDefinitionEntity other = skill("contract-review", "合同审查", "审查合同");
        SkillCatalogProvider p = providerWith(List.of(other));

        List<PromptFragment> fs = p.skillPrompts("u1", List.of("ghost-skill"));

        assertEquals(2, fs.size());
        String manual = fs.get(0).getText();
        assertTrue(manual.contains("不可用"));
        assertTrue(manual.contains("ghost-skill"));
        assertTrue(!manual.contains("### 技能:"), "没有任何技能命中时不该有技能正文段");
        assertTrue(fs.get(1).getText().contains("contract-review"), "目录不受影响");
    }

    @Test
    @DisplayName("未指定技能时行为不变: 只有目录, 没有手动片段")
    void noManualSelectionKeepsCatalogOnly() {
        SkillDefinitionEntity s = skill("alpha", "A", "a 技能");
        SkillCatalogProvider p = providerWith(List.of(s));

        List<PromptFragment> none = p.skillPrompts("u1", null);
        assertEquals(1, none.size());
        assertEquals(SkillCatalogProvider.PROMPT_NAME, none.get(0).getName());

        // 空列表与全是空白名字等价于"没指定", 不应产生一条空的手动消息
        assertEquals(1, p.skillPrompts("u1", List.of()).size());
        assertEquals(1, p.skillPrompts("u1", List.of("   ")).size());
    }

    @Test
    @DisplayName("手动指定: 重复名字去重, 正文只注入一遍")
    void manualSelectionDeduplicatesNames() {
        SkillDefinitionEntity s = skill("mine", "我的", "可见");
        s.setInstructions("ONLY-ONCE");
        SkillCatalogProvider p = providerWith(List.of(s));

        String manual = p.skillPrompts("u1", List.of("mine", "mine", " mine ")).get(0).getText();
        int first = manual.indexOf("ONLY-ONCE");
        assertEquals(first, manual.lastIndexOf("ONLY-ONCE"), "同一技能重复指定不该注入多份正文");
    }

    /** 构造一个"某用户可见一组技能"的 Provider */
    private static SkillCatalogProvider providerWith(List<SkillDefinitionEntity> visible) {
        SkillService svc = mock(SkillService.class);
        when(svc.visibleEnabledSkills("u1")).thenReturn(visible);
        when(svc.instructionsOf(any())).thenAnswer(i -> {
            SkillDefinitionEntity e = i.getArgument(0);
            return e == null ? "" : e.getInstructions();
        });
        return new SkillCatalogProvider(svc);
    }

    private static SkillDefinitionEntity skill(String name, String displayName, String description) {
        SkillDefinitionEntity s = new SkillDefinitionEntity();
        s.setName(name);
        s.setDisplayName(displayName);
        s.setDescription(description);
        s.setEnabled(true);
        return s;
    }
}
