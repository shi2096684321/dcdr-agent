package com.dcdr.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具清单自检 (V3.2)。
 *
 * <p>这层的价值全在"配置漂移能不能被发现"上, 所以断言的是三类校对结果, 而不是日志文案:
 * 白名单里写了却没注册(拼错/被关掉)、注册了却被白名单排除、注册了但 schema 坏了(模型看不见)。
 * 三者都是<b>静默</b>发生的 —— 没有任何一条会报错, 只是能力凭空少了一块。
 */
class ToolInventoryTest {

    @Test
    @DisplayName("未配置白名单: 不排除任何工具, 也不报缺失")
    void noWhitelistMeansEverythingExposed() {
        ToolInventory.Audit audit = ToolInventory.audit(
                List.of("web_search", "calculator"), List.of("web_search", "calculator"),
                List.of("web_search", "calculator"), null);

        assertTrue(audit.excluded().isEmpty());
        assertTrue(audit.missing().isEmpty());
        assertTrue(audit.withoutSpec().isEmpty());
        assertEquals(2, audit.exposed().size());
    }

    @Test
    @DisplayName("白名单生效: 未列出的工具被排除, 但仍算已注册(可被 workflow 调用)")
    void whitelistExcludesUnlistedTools() {
        ToolInventory.Audit audit = ToolInventory.audit(
                List.of("web_search", "calculator", "weather"),
                List.of("web_search", "calculator"),
                List.of("web_search", "calculator", "weather"),
                List.of("web_search", "calculator"));

        assertEquals(List.of("weather"), audit.excluded());
        assertTrue(audit.missing().isEmpty());
    }

    @Test
    @DisplayName("白名单写了不存在的工具: 报缺失(拼错或对应 enabled 已关)")
    void whitelistEntryThatIsNotRegisteredIsReported() {
        ToolInventory.Audit audit = ToolInventory.audit(
                List.of("web_search"), List.of("web_search"), List.of("web_search"),
                List.of("web_search", "web_serach"));

        assertEquals(List.of("web_serach"), audit.missing());
    }

    @Test
    @DisplayName("schema 构建失败的工具: 单独归类, 不算「被配置排除」(两者原因不同, 处理动作也不同)")
    void registeredWithoutSpecificationIsReportedSeparately() {
        ToolInventory.Audit audit = ToolInventory.audit(
                List.of("web_search", "broken_tool"), List.of("web_search"),
                List.of("web_search"), null);

        assertEquals(List.of("broken_tool"), audit.withoutSpec());
        assertTrue(audit.excluded().isEmpty(), "没配白名单时不该把它算成被排除");
    }

    @Test
    @DisplayName("被部署配置排除的工具, 不得同时被归类为「schema 构建失败」")
    void excludedToolIsNotAlsoReportedAsBroken() {
        // 调用方即使误传了「已按白名单过滤」的 specNames(现实里很容易写错),
        // 也不能把"故意不暴露"报成"坏了" —— 两类原因的处理动作完全不同
        ToolInventory.Audit audit = ToolInventory.audit(
                List.of("web_search", "weather"), List.of("web_search"),
                List.of("web_search"), List.of("web_search"));

        assertEquals(List.of("weather"), audit.excluded());
        assertTrue(audit.withoutSpec().isEmpty(), "被排除的工具不该再进 schema 失败清单");
    }

    @Test
    @DisplayName("平台机制工具不受白名单约束: 否则会出现「看得见技能清单却无法读取」")
    void platformToolsAreNeverExcluded() {
        String skillLoad = com.dcdr.agent.tool.skill.SkillLoadTool.NAME;
        assertTrue(ToolRegistry.platformToolNames().contains(skillLoad));

        ToolInventory.Audit audit = ToolInventory.audit(
                List.of("web_search", skillLoad), List.of("web_search", skillLoad),
                List.of("web_search", skillLoad), List.of("web_search"));

        assertTrue(audit.excluded().isEmpty());
    }
}
