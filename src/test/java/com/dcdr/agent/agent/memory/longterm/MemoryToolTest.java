package com.dcdr.agent.agent.memory.longterm;

import com.dcdr.agent.dto.MemoryDtos.MemoryView;
import com.dcdr.agent.dto.MemoryDtos.SaveRequest;
import com.dcdr.agent.tool.ToolInfo;
import com.dcdr.agent.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 长期记忆工具测试 (V3.1)。
 *
 * <p>这个工具要解决的是一个"用户以为发生了、其实什么都没发生"的问题, 所以每条断言都围绕
 * 「到底有没有真的动记忆」: 用户说记住就要写进去; 定位不到就一条都不许改;
 * 删除只能归档不能硬删(判错要能一键撤回)。
 */
class MemoryToolTest {

    private final LongTermMemoryService memoryService = mock(LongTermMemoryService.class);
    private final MemoryRelevanceScorer scorer = new MemoryRelevanceScorer();
    private final MemoryTool tool = new MemoryTool(memoryService, scorer);

    @BeforeEach
    void setUp() {
        MemoryContextHolder.set("u1", "s1");
    }

    @AfterEach
    void tearDown() {
        // ThreadLocal 不清会串到别的测试, 也会串到真实的线程池线程
        MemoryContextHolder.clear();
    }

    private static MemoryView memory(String id, String content) {
        MemoryView v = new MemoryView();
        v.setId(id);
        v.setType("PREFERENCE");
        v.setContent(content);
        return v;
    }

    // ===== add: 用户说"记住", 就必须真的写 =====

    @Test
    @DisplayName("add: 真的写入长期记忆, 并带上来源会话(记忆页据此标出'自动记录')")
    void addWritesWithSession() {
        when(memoryService.list("u1", false)).thenReturn(List.of());
        when(memoryService.create(anyString(), any(), any())).thenReturn(memory("m_new", "用户偏好中文回答"));

        ToolInfo info = tool.executeWithInfo(Map.of(
                "action", "add", "content", "用户偏好中文回答", "type", "PREFERENCE"));

        ArgumentCaptor<SaveRequest> cap = ArgumentCaptor.forClass(SaveRequest.class);
        verify(memoryService).create(org.mockito.ArgumentMatchers.eq("u1"), cap.capture(),
                org.mockito.ArgumentMatchers.eq(MemoryOrigin.REQUESTED));
        assertEquals("用户偏好中文回答", cap.getValue().getContent());
        assertEquals("s1", cap.getValue().getSourceSessionId());
        assertEquals("PREFERENCE", cap.getValue().getType());
        assertTrue(info.getContent().contains("已写入长期记忆"), "回显要能让模型如实转达: " + info.getContent());
    }

    @Test
    @DisplayName("add: 类型非法或缺省时落到 PREFERENCE, 不因为类型没给全就整条失败")
    void addFallsBackToPreferenceType() {
        when(memoryService.list("u1", false)).thenReturn(List.of());
        when(memoryService.create(anyString(), any(), any())).thenReturn(memory("m_new", "x"));

        tool.execute(Map.of("action", "add", "content", "随口的偏好", "type", "NOT_A_TYPE"));

        ArgumentCaptor<SaveRequest> cap = ArgumentCaptor.forClass(SaveRequest.class);
        verify(memoryService).create(anyString(), cap.capture(),
                org.mockito.ArgumentMatchers.eq(MemoryOrigin.REQUESTED));
        assertEquals("PREFERENCE", cap.getValue().getType());
    }

    @Test
    @DisplayName("add: 与既有记忆几乎相同时不重复写, 而是把既有内容回显出来让模型照实说")
    void addSkipsNearDuplicate() {
        when(memoryService.list("u1", false))
                .thenReturn(List.of(memory("m1", "用户偏好中文回答")));

        ToolInfo info = tool.executeWithInfo(Map.of(
                "action", "add", "content", "用户偏好中文回答"));

        verify(memoryService, never()).create(anyString(), any());
        assertTrue(info.getContent().contains("已存在几乎相同的长期记忆"),
                "否则又会退化成'说了记住却没反应': " + info.getContent());
        assertTrue(info.getContent().contains("用户偏好中文回答"), "要回显既有内容, 模型才知道是什么已存在");
    }

    @Test
    @DisplayName("add: 内容超长时把服务端的校验原因转达给模型, 而不是静默失败")
    void addSurfacesValidationError() {
        when(memoryService.list("u1", false)).thenReturn(List.of());
        when(memoryService.create(anyString(), any(), any()))
                .thenThrow(new IllegalArgumentException("记忆内容长度不能超过 1000 字符"));

        ToolInfo info = tool.executeWithInfo(Map.of("action", "add", "content", "很长很长的内容"));

        assertTrue(info.getContent().contains("写入失败"), info.getContent());
        assertTrue(info.getContent().contains("1000"), "原因要转达到, 模型才能请用户改短些");
    }

    // ===== update / delete: 定位不到就一条也不许动 =====

    @Test
    @DisplayName("update: 按内容匹配到既有记忆后修改")
    void updateMatchesByContent() {
        when(memoryService.list("u1", false))
                .thenReturn(List.of(memory("m1", "用户偏好中文回答")));

        ToolInfo info = tool.executeWithInfo(Map.of(
                "action", "update", "existing", "用户偏好中文回答", "content", "用户现在偏好英文回答"));

        ArgumentCaptor<SaveRequest> cap = ArgumentCaptor.forClass(SaveRequest.class);
        verify(memoryService).update(org.mockito.ArgumentMatchers.eq("u1"),
                org.mockito.ArgumentMatchers.eq("m1"), cap.capture());
        assertEquals("用户现在偏好英文回答", cap.getValue().getContent());
        assertTrue(info.getContent().contains("已修改长期记忆"));
    }

    @Test
    @DisplayName("update: 匹配不到时一条都不改, 并让模型如实说'没找到'而不是反复重试")
    void updateDoesNothingWhenNoMatch() {
        when(memoryService.list("u1", false))
                .thenReturn(List.of(memory("m1", "用户偏好中文回答")));

        ToolInfo info = tool.executeWithInfo(Map.of(
                "action", "update", "existing", "用户喜欢打篮球", "content", "用户喜欢踢足球"));

        verify(memoryService, never()).update(anyString(), anyString(), any());
        assertTrue(info.getContent().contains("没有找到"), info.getContent());
        assertTrue(info.getContent().contains("不要反复重试"), "否则模型会拿同一个条件反复试");
    }

    @Test
    @DisplayName("delete: 走归档而不是硬删 —— 判错也要能一键撤回")
    void deleteArchivesInsteadOfRemoving() {
        when(memoryService.list("u1", false))
                .thenReturn(List.of(memory("m1", "用户偏好英文回答")));

        ToolInfo info = tool.executeWithInfo(Map.of(
                "action", "delete", "existing", "用户偏好英文回答"));

        verify(memoryService).archive("u1", "m1");
        verify(memoryService, never()).delete(anyString(), anyString());
        assertTrue(info.getContent().contains("可恢复"), "要让用户知道删错了还能撤: " + info.getContent());
    }

    @Test
    @DisplayName("delete: 匹配不到时一条都不删")
    void deleteDoesNothingWhenNoMatch() {
        when(memoryService.list("u1", false))
                .thenReturn(List.of(memory("m1", "用户偏好中文回答")));

        ToolInfo info = tool.executeWithInfo(Map.of("action", "delete", "existing", "用户喜欢打篮球"));

        verify(memoryService, never()).archive(anyString(), anyString());
        assertTrue(info.getContent().contains("没有找到"));
    }

    // ===== 边界 =====

    @Test
    @DisplayName("未登录(认证关闭模式)时明确拒绝, 不能假装写了")
    void rejectsWhenNoUser() {
        MemoryContextHolder.clear();

        ToolInfo info = tool.executeWithInfo(Map.of("action", "add", "content", "x"));

        verify(memoryService, never()).create(anyString(), any());
        assertTrue(info.getContent().contains("没有登录用户"), info.getContent());
    }

    @Test
    @DisplayName("参数缺失/action 非法: 给模型可读的提示, 而不是抛异常炸掉工具循环")
    void handlesBadParams() {
        when(memoryService.list("u1", false)).thenReturn(List.of());

        assertTrue(tool.executeWithInfo(Map.of("action", "add")).getContent().contains("缺少 content"));
        assertTrue(tool.executeWithInfo(Map.of("action", "update", "content", "x"))
                .getContent().contains("缺少 existing"));
        assertTrue(tool.executeWithInfo(Map.of("action", "delete")).getContent().contains("缺少 existing"));
        assertTrue(tool.executeWithInfo(Map.of("action", "explode")).getContent().contains("不支持的 action"));
        assertTrue(tool.executeWithInfo(Map.of()).getContent().contains("缺少 action"));
        // 参数里塞了字面量 "null" 时按没给处理(模型偶尔会这么输出)
        assertFalse(tool.executeWithInfo(Map.of("action", "add", "content", "null"))
                .getContent().contains("已写入"));
    }

    @Test
    @DisplayName("注册后确实暴露给模型, 且能按名字被调到 —— 这两条断一条, 工具就等于不存在")
    void registeredAndDispatchableByName() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);

        // ① 暴露: schema 构建失败时 ToolRegistry 会"注册执行但不暴露给 LLM"(见其 register 的 catch),
        //    模型就永远看不到这个工具 —— 表现是"说了记住毫无反应", 且没有任何报错
        assertTrue(registry.getStreamingToolSpecificationsFiltered(null).stream()
                        .anyMatch(s -> "memory".equals(s.name())),
                "工具没进流式工具清单: 先查 MemoryTool.getParameters() 的 schema 是否构建失败");

        // ② 按名分发: executeTools 里没有 memory 分支, 走的是通用 bridge
        //    → ToolRegistry.executeInfo(name, params) → tools.get(name).executeWithInfo(params)。
        //    纯按名字查表, 所以任何注册进来的 Tool Bean 都能被执行, 无需在编排层登记
        MemoryContextHolder.set("u1", "s1");
        when(memoryService.list("u1", false)).thenReturn(List.of());
        when(memoryService.create(anyString(), any(), any())).thenReturn(memory("m1", "用户偏好中文回答"));

        ToolInfo info = registry.executeInfo("memory",
                Map.of("action", "add", "content", "用户偏好中文回答"));

        assertTrue(info.getContent().contains("已写入长期记忆"), info.getContent());
    }

    @Test
    @DisplayName("暴露给流式对话: 记忆要在对话里就能记")
    void availableInStreamingChat() {
        assertTrue(tool.supportsStreaming());
        assertEquals("memory", tool.getName());
    }
}
