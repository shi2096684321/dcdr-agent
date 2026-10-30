package com.dcdr.agent.workspace;

import com.dcdr.agent.tool.workspace.WorkspaceDeleteTool;
import com.dcdr.agent.tool.workspace.WorkspaceEditTool;
import com.dcdr.agent.tool.workspace.WorkspaceListTool;
import com.dcdr.agent.tool.workspace.WorkspaceReadTool;
import com.dcdr.agent.tool.workspace.WorkspaceSearchTool;
import com.dcdr.agent.tool.workspace.WorkspaceWriteTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 授权档位的工具清单测试。
 * <p>
 * 这份清单是"注入给模型哪些工作区工具"与"提示词点名哪些工具"的<b>唯一来源</b>,
 * 但它写的是字符串(不能 import 工具类, 否则包级循环依赖), 所以最容易出的错是
 * "改了工具的 NAME 却忘了改这里"。下面第一个测试就是为了在编译期之外再兜一层: 漏改就红。
 */
class WorkspaceAccessTest {

    @Test
    @DisplayName("清单与实际工具类的 NAME 常量一致(防单一真值源漂移)")
    void toolNamesMatchRegisteredTools() {
        Set<String> declared = Set.of(
                WorkspaceListTool.NAME, WorkspaceReadTool.NAME, WorkspaceSearchTool.NAME,
                WorkspaceWriteTool.NAME, WorkspaceEditTool.NAME, WorkspaceDeleteTool.NAME);

        assertEquals(declared, Set.copyOf(WorkspaceAccess.FULL.toolNames()),
                "FULL 档必须是全部 6 个工作区工具");
        assertEquals(6, WorkspaceAccess.FULL.toolNames().size());
    }

    @Test
    @DisplayName("三档按包含关系递增: READ ⊂ WRITE ⊂ FULL")
    void levelsAreNested() {
        List<String> read = WorkspaceAccess.READ.toolNames();
        List<String> write = WorkspaceAccess.WRITE.toolNames();
        List<String> full = WorkspaceAccess.FULL.toolNames();

        assertTrue(write.containsAll(read));
        assertTrue(full.containsAll(write));
        assertFalse(read.contains(WorkspaceWriteTool.NAME));
        assertFalse(write.contains(WorkspaceDeleteTool.NAME));
    }

    @Test
    @DisplayName("allowsTool: 读授权不等于写授权, 更不等于删授权")
    void allowsToolFollowsLevel() {
        assertTrue(WorkspaceAccess.READ.allowsTool(WorkspaceReadTool.NAME));
        assertFalse(WorkspaceAccess.READ.allowsTool(WorkspaceWriteTool.NAME));
        assertFalse(WorkspaceAccess.READ.allowsTool(WorkspaceDeleteTool.NAME));

        assertTrue(WorkspaceAccess.WRITE.allowsTool(WorkspaceWriteTool.NAME));
        assertFalse(WorkspaceAccess.WRITE.allowsTool(WorkspaceDeleteTool.NAME));

        assertTrue(WorkspaceAccess.FULL.allowsTool(WorkspaceDeleteTool.NAME));
    }

    @Test
    @DisplayName("isWorkspaceTool 只认工作区工具族, 非工作区工具不受档位影响")
    void isWorkspaceToolScopedToFamily() {
        assertTrue(WorkspaceAccess.isWorkspaceTool(WorkspaceReadTool.NAME));
        assertFalse(WorkspaceAccess.isWorkspaceTool("web_search"));
        assertFalse(WorkspaceAccess.isWorkspaceTool("calculator"));
        assertFalse(WorkspaceAccess.isWorkspaceTool(null));
    }

    @Test
    @DisplayName("提示词点名的工具必须与清单同源(不能提示说能用、实际不注入)")
    void promptTextMatchesToolNames() {
        for (WorkspaceAccess access : WorkspaceAccess.values()) {
            String text = access.tools();
            for (String name : access.toolNames()) {
                assertTrue(text.contains("`" + name + "`"), access + " 的提示文本漏了 " + name);
            }
            // 反引号成对出现, 对数应等于清单条数 —— 多出来的就是"提示里提了但不给注入"
            long ticks = text.chars().filter(c -> c == '`').count();
            assertEquals(access.toolNames().size(), ticks / 2,
                    access + " 的提示文本含清单之外的工具名");
        }
    }

    @Test
    @DisplayName("allows(intent) 与工具清单表达同一件事")
    void intentAndToolsAgree() {
        assertTrue(WorkspaceAccess.READ.allows(WorkspaceIntent.READ));
        assertFalse(WorkspaceAccess.READ.allows(WorkspaceIntent.WRITE));
        assertFalse(WorkspaceAccess.READ.allows(WorkspaceIntent.DELETE));
        assertFalse(WorkspaceAccess.WRITE.allows(WorkspaceIntent.DELETE));
        assertTrue(WorkspaceAccess.FULL.allows(WorkspaceIntent.DELETE));
    }
}
