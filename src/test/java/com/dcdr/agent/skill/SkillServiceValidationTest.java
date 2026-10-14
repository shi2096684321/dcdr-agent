package com.dcdr.agent.skill;

import com.dcdr.agent.auth.ownership.OwnershipGuard;
import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.dto.SkillDtos;
import com.dcdr.agent.entity.SkillDefinitionEntity;
import com.dcdr.agent.repository.SkillRepository;
import com.dcdr.agent.tool.Tool;
import com.dcdr.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 技能服务校验规则单元测试 (V3.1 专家体系)。
 *
 * <p>为什么把这些校验当成"必须有测试"的部分: 技能包是<b>外部来的内容</b>
 * (市场下载 / 他人传阅 / 模型生成), 它会被注入模型上下文。这里的上限与白名单
 * 是唯一一道闸门 —— 而它们全都是"正常使用时永远不会触发"的分支,
 * 手工回归时不可能覆盖, 只能靠测试。
 *
 * <p>用 Mockito 伪 Repository/Registry: 校验逻辑本身不依赖数据库,
 * 为它起 Spring 上下文会让这层测试从"秒级"退化到"十几秒", 得不偿失。
 */
class SkillServiceValidationTest {

    private final SkillRepository repository = mock(SkillRepository.class);
    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
    private final SkillService service = new SkillService(
            repository, toolRegistry, mock(OwnershipGuard.class));

    // ===== 名称 =====

    @Test
    @DisplayName("名称必须 kebab-case, 且会归一化为小写")
    void nameMustBeKebabCase() {
        SkillDtos.SaveSkillRequest req = valid("My-Skill");
        assertEquals("my-skill", service.create(req, "u1").getName(),
                "大写应被归一化, 而不是报错 —— 标识是给模型用的, 大小写敏感只会制造歧义");
    }

    @Test
    @DisplayName("非法名称直接 400(含中文/下划线/空格)")
    void invalidNameRejected() {
        for (String bad : List.of("中文技能", "my_skill", "my skill", "a", "-lead")) {
            SkillDtos.SaveSkillRequest req = valid(bad);
            assertThrows(BusinessException.class, () -> service.create(req, "u1"),
                    "应拒绝非法名称: " + bad);
        }
    }

    // ===== 指令与资源 =====

    @Test
    @DisplayName("指令正文不能为空 —— 技能的核心就是这段指令")
    void blankInstructionsRejected() {
        SkillDtos.SaveSkillRequest req = valid("ok-skill");
        req.setInstructions("   ");
        assertThrows(BusinessException.class, () -> service.create(req, "u1"));
    }

    @Test
    @DisplayName("指令超长拒绝(应拆成技能 + 参考资源)")
    void oversizedInstructionsRejected() {
        SkillDtos.SaveSkillRequest req = valid("ok-skill");
        req.setInstructions("x".repeat(SkillService.MAX_INSTRUCTIONS_CHARS + 1));
        assertThrows(BusinessException.class, () -> service.create(req, "u1"));
    }

    @Test
    @DisplayName("资源路径拒绝: 目录回溯 / 绝对路径 / 盘符")
    void resourcePathTraversalRejected() {
        for (String bad : List.of("../secret.md", "../../etc/passwd.md", "/etc/hosts.md", "C:/x.md")) {
            SkillDtos.SaveSkillRequest req = valid("ok-skill");
            req.setResources(new LinkedHashMap<>(Map.of(bad, "内容")));
            assertThrows(BusinessException.class, () -> service.create(req, "u1"),
                    "应拒绝路径: " + bad);
        }
    }

    @Test
    @DisplayName("资源只接受文本后缀 —— 技能不携带可执行内容")
    void executableResourceRejected() {
        for (String bad : List.of("scripts/run.py", "bin/tool.sh", "a.exe", "lib.dll", "noext")) {
            SkillDtos.SaveSkillRequest req = valid("ok-skill");
            req.setResources(new LinkedHashMap<>(Map.of(bad, "x")));
            assertThrows(BusinessException.class, () -> service.create(req, "u1"),
                    "应拒绝非文本资源: " + bad);
        }
    }

    @Test
    @DisplayName("合法的文本资源可通过, 且路径被归一化(去 ./ 前缀、反斜杠转正斜杠)")
    void textResourceAcceptedAndNormalized() {
        SkillDtos.SaveSkillRequest req = valid("ok-skill");
        req.setResources(new LinkedHashMap<>(Map.of("./references/faq.md", "常见问题")));
        assertEquals(List.of("references/faq.md"), service.create(req, "u1").getResourcePaths());
    }

    @Test
    @DisplayName("资源单文件与总量超限均拒绝")
    void oversizedResourcesRejected() {
        SkillDtos.SaveSkillRequest one = valid("ok-skill");
        one.setResources(new LinkedHashMap<>(Map.of(
                "a.md", "x".repeat(SkillService.MAX_RESOURCE_CHARS + 1))));
        assertThrows(BusinessException.class, () -> service.create(one, "u1"));

        Map<String, String> many = new LinkedHashMap<>();
        for (int i = 0; i < SkillService.MAX_RESOURCE_COUNT + 1; i++) {
            many.put("r" + i + ".md", "x");
        }
        SkillDtos.SaveSkillRequest tooMany = valid("ok-skill");
        tooMany.setResources(new LinkedHashMap<>(many));
        assertThrows(BusinessException.class, () -> service.create(tooMany, "u1"));
    }

    // ===== 归属与配额 =====

    @Test
    @DisplayName("同名技能拒绝创建(否则 skill_load 按名字加载会产生歧义)")
    void duplicateNameRejected() {
        SkillDefinitionEntity existing = new SkillDefinitionEntity();
        existing.setId("skill_x");
        existing.setName("dup-skill");
        when(repository.findByUserIdAndName("u1", "dup-skill")).thenReturn(Optional.of(existing));

        SkillDtos.SaveSkillRequest req = valid("dup-skill");
        assertThrows(BusinessException.class, () -> service.create(req, "u1"));
    }

    @Test
    @DisplayName("技能数超配额拒绝创建")
    void quotaExceededRejected() {
        when(repository.countByUserId("u1")).thenReturn((long) SkillService.MAX_SKILLS_PER_USER);
        assertThrows(BusinessException.class, () -> service.create(valid("ok-skill"), "u1"));
    }

    @Test
    @DisplayName("内置技能不可删除(只能停用) —— 否则依赖它的对话会静默失去能力")
    void builtinSkillCannotBeDeleted() {
        SkillDefinitionEntity builtin = new SkillDefinitionEntity();
        builtin.setId("skill_builtin");
        builtin.setName("builtin-skill");
        builtin.setSource("builtin");
        when(repository.findById("skill_builtin")).thenReturn(Optional.of(builtin));

        assertThrows(BusinessException.class, () -> service.delete("skill_builtin", "u1", true));
    }

    // ===== 工具依赖(引用而非捆绑) =====

    @Test
    @DisplayName("依赖工具存在时不报缺失; 缺失只提示不阻断")
    void missingToolsAreReported() {
        Tool calc = mock(Tool.class);
        when(toolRegistry.getTool("calculator")).thenReturn(calc);

        SkillDtos.SaveSkillRequest req = valid("ok-skill");
        req.setRequiredTools(List.of("calculator", "not-registered"));
        SkillDtos.SkillView view = service.create(req, "u1");

        assertEquals(List.of("not-registered"), view.getMissingTools(),
                "只应报告真正缺失的工具");
        assertTrue(view.getRequiredTools().contains("calculator"));
        assertFalse(view.isHasExecutable());
    }

    // ===== 内置技能的可见性 =====

    @Test
    @DisplayName("内置技能对所有登录用户生效, 且不依赖 isPublic")
    void builtinSkillsAreVisibleWithoutBeingPublic() {
        SkillDefinitionEntity builtin = new SkillDefinitionEntity();
        builtin.setId("skill_builtin_weekly-report");
        builtin.setName("weekly-report");
        builtin.setEnabled(true);
        builtin.setPublic(false);   // 关键: 内置技能没有开"公开"这个用户开关
        when(repository.findByEnabledTrueAndUserIdIsNullOrderByCreatedAtAsc()).thenReturn(List.of(builtin));

        assertTrue(service.visibleEnabledSkills("u1").stream()
                        .anyMatch(s -> "weekly-report".equals(s.getName())),
                "内置技能 userId 为 null: 既不属于任何用户, 也不该靠 isPublic 才生效 —— 必须单独查出来");
    }

    @Test
    @DisplayName("同名时优先级: 自己的 > 公开的 > 内置的")
    void ownSkillOverridesBuiltinWithSameName() {
        SkillDefinitionEntity builtin = new SkillDefinitionEntity();
        builtin.setId("skill_builtin_shared");
        builtin.setName("shared");
        builtin.setDisplayName("内置版");
        builtin.setEnabled(true);

        SkillDefinitionEntity mine = new SkillDefinitionEntity();
        mine.setId("skill_mine");
        mine.setName("shared");
        mine.setDisplayName("我的版本");
        mine.setEnabled(true);
        mine.setUserId("u1");

        when(repository.findByEnabledTrueAndUserIdIsNullOrderByCreatedAtAsc()).thenReturn(List.of(builtin));
        when(repository.findByEnabledTrueAndUserIdOrderByUpdatedAtDesc("u1")).thenReturn(List.of(mine));

        List<SkillDefinitionEntity> visible = service.visibleEnabledSkills("u1");
        assertEquals(1, visible.size(), "同名应去重, 否则 skill_load(name) 会有歧义");
        assertEquals("我的版本", visible.get(0).getDisplayName(),
                "自建同名技能应覆盖内置的 —— 反过来会让用户永远无法修正平台的行为");
    }

    // ===== 运行时加载 =====

    @Test
    @DisplayName("按名加载: 返回指令正文; 指定 path 返回对应资源; 名字不存在给出可读提示")
    void loadContentBehaves() {
        SkillDefinitionEntity s = new SkillDefinitionEntity();
        s.setId("skill_1");
        s.setName("alpha");
        s.setEnabled(true);
        s.setPublic(true);
        s.setInstructions("这是 alpha 的指令正文");
        s.setResourcesJson(SkillJson.writeMap(Map.of("references/a.md", "资源A")));
        when(repository.findByEnabledTrueAndIsPublicTrueOrderByUpdatedAtDesc()).thenReturn(List.of(s));

        String userId = "u1";
        assertTrue(service.loadContent(userId, "alpha", null).contains("alpha 的指令正文"));
        assertEquals("资源A", service.loadContent(userId, "alpha", "references/a.md"));
        // 资源不存在时要把"有哪些资源"告诉模型, 否则它只会反复猜路径
        String miss = service.loadContent(userId, "alpha", "references/nope.md");
        assertTrue(miss.contains("references/a.md"));
        assertTrue(service.loadContent(userId, "ghost", null).contains("未找到可用技能"));
    }

    @Test
    @DisplayName("停用的技能不出现在目录里, 也无法被加载")
    void disabledSkillIsInvisible() {
        // 两个仓库方法都返回空: 表示该用户当前没有任何生效技能
        SkillService svc = new SkillService(repository, toolRegistry, mock(OwnershipGuard.class));
        assertTrue(svc.visibleEnabledSkills("u1").isEmpty());
        assertTrue(svc.loadContent("u1", "anything", null).contains("未找到可用技能"));
    }

    // ===== 图标 =====

    @Test
    @DisplayName("图标: 只接受 http(s) 或站内相对路径, 其余静默丢弃")
    void iconUrlAcceptsOnlyHttpAndSiteRelative() {
        for (String ok : List.of("https://cdn.example.com/a.png", "http://x/b.png", "/api/files/icons/a.png")) {
            assertEquals(ok, service.create(valid("icon-ok", ok), "u1").getIconUrl());
        }
        // data: 是"往文本字段塞二进制"的开始 —— 必须拒绝。这条限制是"技能表保持纯文本"的关口,
        // 而那条前提正是技能包可以自由分享/传阅的基础
        for (String bad : List.of(
                "data:image/png;base64,AAAA",      // 内联二进制
                "javascript:alert(1)",             // 非法协议
                "ftp://x/a.png",                   // 非 http(s)
                "a.png",                           // 相对路径但不是站内绝对路径
                "https://x/a.png\nSet-Cookie: y",  // 控制字符
                "   ")) {
            assertNull(service.create(valid("icon-bad", bad), "u1").getIconUrl(),
                    "应丢弃非法图标: " + bad);
        }
        // 超过实体列宽也丢 —— 否则会在入库那一刻抛异常, 把一个装饰字段变成保存失败
        assertNull(service.create(valid("icon-long", "https://x/" + "a".repeat(600)), "u1").getIconUrl());
    }

    // ===== 来源页 / 待补全 =====

    @Test
    @DisplayName("来源页: 与图标同一套归一化规则(只接受 http(s) 或站内路径)")
    void sourceUrlIsNormalized() {
        assertEquals("https://clawhub.ai/a/b",
                service.create(validWithSource("src-ok", "https://clawhub.ai/a/b"), "u1").getSourceUrl());
        for (String bad : List.of("javascript:alert(1)", "data:text/html,x", "clawhub.ai/a/b", "  ")) {
            assertNull(service.create(validWithSource("src-bad", bad), "u1").getSourceUrl(),
                    "应丢弃非法来源页: " + bad);
        }
    }

    @Test
    @DisplayName("待补全: 由正文内容派生; 且老技能能从正文回读到来源地址")
    void pendingAndSourceAreDerivedFromBody() {
        // 这是市场引入时生成的占位正文(老技能没有 source_url 列, 地址只写在正文里)
        SkillDefinitionEntity legacy = skillEntity("""
                # Browser Use

                > ⚠️ **正文待补全**: 本技能由技能市场引入。
                > 来源: https://clawhub.ai/shawnpana/browser-use

                ## 何时使用
                浏览器自动化
                """);
        SkillDtos.SkillView v = service.toView(legacy, "u1", false, false);
        assertTrue(v.isInstructionsPending(), "正文里还留着占位说明 → 应判定为待补全");
        assertEquals("https://clawhub.ai/shawnpana/browser-use", v.getSourceUrl(),
                "老技能没有 source_url 列, 必须能从正文回读到来源地址, 否则列表上就没有补全入口");
    }

    @Test
    @DisplayName("待补全: 正文被替换后自动失效, 不需要额外的\"标记已补全\"动作")
    void pendingClearsOnceBodyReplaced() {
        SkillDefinitionEntity filled = skillEntity("# 合同审查\n\n## 步骤\n1. 逐条比对条款\n2. 标注风险等级");
        SkillDtos.SkillView v = service.toView(filled, "u1", false, false);
        assertFalse(v.isInstructionsPending(), "换成真正文后应自动不再是待补全");
        assertNull(v.getSourceUrl(), "没有来源地址时为空, 界面据此不显示来源链接");
    }

    // ===== 来源标识(血缘) =====

    @Test
    @DisplayName("来源标识: 手工编辑不会把它清掉 —— 会去编辑的正是补全正文的那个人")
    void editingDoesNotWipeOriginRef() {
        SkillDefinitionEntity existing = skillEntity("待补全的骨架正文…");
        existing.setUserId("u1");
        existing.setSourceRef("skillhub:@clawhub_shawnpana/browser-use");
        when(repository.findById("skill_1")).thenReturn(Optional.of(existing));

        // 编辑表单里没有"来源标识"这个输入项, 所以保存请求里也不会带上它
        SkillDtos.SaveSkillRequest req = valid("sample-skill");
        req.setInstructions("补全后的正文");
        service.update("skill_1", req, "u1", false);

        assertEquals("skillhub:@clawhub_shawnpana/browser-use", existing.getSourceRef(),
                "来源是系统记录的事实、不是用户内容: 不能因为一次普通编辑就丢掉血缘, "
                        + "否则用户补全正文之后, 这个技能就再也说不清是从哪来的了");
    }

    @Test
    @DisplayName("来源标识: 传空表示不改(而不是清空), 传新值才覆盖")
    void markOriginOnlyOverwritesWithRealValue() {
        SkillDefinitionEntity e = skillEntity("正文");
        e.setSourceRef("skillhub:@old/browser-use");
        when(repository.findById("skill_1")).thenReturn(Optional.of(e));

        service.markOrigin("skill_1", "   ");
        assertEquals("skillhub:@old/browser-use", e.getSourceRef(), "清空血缘没有任何合理场景");

        service.markOrigin("skill_1", "skillhub:@new/browser-use");
        assertEquals("skillhub:@new/browser-use", e.getSourceRef(),
                "重复引入可能换了命名空间, 以最后一次为准");
    }

    @Test
    @DisplayName("来源标识: 超长整条丢弃, 不截断 —— 截断出来的标识指向一个不存在的技能")
    void markOriginDropsOverlongRef() {
        SkillDefinitionEntity e = skillEntity("正文");
        when(repository.findById("skill_1")).thenReturn(Optional.of(e));

        service.markOrigin("skill_1", "skillhub:@" + "x".repeat(300) + "/s");

        assertNull(e.getSourceRef(),
                "与其留下一个看起来像标识、实际对不上的字符串, 不如留空");
    }

    @Test
    @DisplayName("来源标识: 存量技能从占位正文回读, 并在补全正文时落库")
    void derivesOriginFromPendingBodyAndPersistsIt() {
        SkillDefinitionEntity existing = skillEntity("""
                # Browser Use

                > ⚠️ **正文待补全**: 骨架内容

                ## 来源信息

                - 标识: @clawhub_shawnpana/browser-use
                - 版本: 2.0.1
                """);
        existing.setUserId("u1");
        when(repository.findById("skill_1")).thenReturn(Optional.of(existing));

        // 1) 还没补全时列表就能看到来源 —— source_ref 是后加的列, 存量技能只有正文里那行可读
        assertEquals("skillhub:@clawhub_shawnpana/browser-use",
                service.toView(existing, "u1", false, false).getSourceRef(),
                "存量引入的技能必须也能显示出源, 否则这个技能列表对它们等于没改");

        // 2) 补全正文时落库。不落的话血缘会随这次编辑一起消失 —— 而那正是用户最需要它的时刻
        SkillDtos.SaveSkillRequest req = valid("sample-skill");
        req.setInstructions("补全后的正文");
        service.update("skill_1", req, "u1", false);

        assertEquals("skillhub:@clawhub_shawnpana/browser-use", existing.getSourceRef(),
                "覆盖正文之前必须先回读落库, 否则占位里那行标识就再也找不回来了");
    }

    @Test
    @DisplayName("来源标识: 读不出标识就不编造, 不给普通技能安上血缘")
    void derivationYieldsNothingWithoutMarker() {
        // 自建技能: 正文既不是占位骨架, 也没有标识行
        SkillDefinitionEntity own = skillEntity("手写的正文, 完全没有标识信息");
        assertNull(service.toView(own, "u1", false, false).getSourceRef());

        // 是占位骨架、但没有"标识:"行 → 同样留空, 而不是拿技能名凑一个
        SkillDefinitionEntity pending = skillEntity("> ⚠️ **正文待补全**: 骨架\n\n## 来源信息\n\n- 版本: 1.0\n");
        assertNull(service.toView(pending, "u1", false, false).getSourceRef());
    }

    private static SkillDefinitionEntity skillEntity(String instructions) {
        SkillDefinitionEntity e = new SkillDefinitionEntity();
        e.setId("skill_1");
        e.setName("sample-skill");
        e.setEnabled(true);
        e.setInstructions(instructions);
        return e;
    }

    /** 构造一个除名称外全部合法的请求 */
    private static SkillDtos.SaveSkillRequest valid(String name) {
        SkillDtos.SaveSkillRequest req = new SkillDtos.SaveSkillRequest();
        req.setName(name);
        req.setDisplayName("测试技能");
        req.setDescription("用于测试的技能");
        req.setInstructions("按以下步骤执行: 1. ... 2. ...");
        return req;
    }

    /** 同上, 但带上图标地址 */
    private static SkillDtos.SaveSkillRequest valid(String name, String iconUrl) {
        SkillDtos.SaveSkillRequest req = valid(name);
        req.setIconUrl(iconUrl);
        return req;
    }

    /** 同上, 但带上来源页地址 */
    private static SkillDtos.SaveSkillRequest validWithSource(String name, String sourceUrl) {
        SkillDtos.SaveSkillRequest req = valid(name);
        req.setSourceUrl(sourceUrl);
        return req;
    }
}
