package com.dcdr.agent.agent.memory.longterm;

import com.dcdr.agent.agent.governance.CostTracker;
import com.dcdr.agent.config.AgentProperties;
import com.dcdr.agent.dto.ChatResponse;
import com.dcdr.agent.dto.MemoryDtos.MemoryView;
import com.dcdr.agent.dto.MemoryDtos.SaveRequest;
import com.dcdr.agent.entity.MessageEntity;
import com.dcdr.agent.model.provider.ChatModelProvider;
import com.dcdr.agent.model.registry.ModelRegistry;
import com.dcdr.agent.repository.MessageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * LLM 自动抽取记忆的测试 (V3.1 P1)。
 *
 * <p>这个功能的失败方式都是<b>静默</b>的, 所以每一条都要钉住:
 * 开关写了不生效 → 白白产生模型费用; 去重失效 → 记忆列表成倍膨胀;
 * DELETE 写成硬删 → 模型判断错一次就永久丢数据; 异常没吞掉 → 直接打断用户的对话。
 */
class MemoryAutoExtractorTest {

    private final LongTermMemoryService memoryService = mock(LongTermMemoryService.class);
    private final MemoryRelevanceScorer scorer = new MemoryRelevanceScorer();
    private final MessageRepository messageRepository = mock(MessageRepository.class);
    private final ModelRegistry modelRegistry = mock(ModelRegistry.class);
    private final CostTracker costTracker = mock(CostTracker.class);
    private final ChatModelProvider provider = mock(ChatModelProvider.class);
    private final AgentProperties properties = new AgentProperties();
    /** 开关语义单独测(见 AutoExtractPreferenceTest), 这里只关心"关着就不抽" */
    private final AutoExtractPreference preference = mock(AutoExtractPreference.class);

    private MemoryAutoExtractor extractor() {
        when(modelRegistry.getDefaultChatProvider()).thenReturn(provider);
        when(provider.getName()).thenReturn("test-model");
        return new MemoryAutoExtractor(properties, preference, memoryService, scorer, messageRepository,
                modelRegistry, costTracker);
    }

    private void enable(boolean enabled, int everyNTurns, int maxPerRun) {
        AgentProperties.AutoExtractConfig cfg = properties.getMemory().getAutoExtract();
        cfg.setEveryNTurns(everyNTurns);
        cfg.setMaxPerRun(maxPerRun);
        when(preference.active("u1")).thenReturn(enabled);
    }

    private void stubConversation(String sessionId, int turns) {
        when(messageRepository.countBySessionIdAndRole(sessionId, "user")).thenReturn((long) turns);
        MessageEntity u = msg(1, "user", "帮我看看这个报错");
        MessageEntity a = msg(2, "assistant", "这是空指针导致的…");
        when(messageRepository.findBySessionIdOrderBySeqDesc(anyString(), any()))
                .thenReturn(List.of(a, u));   // 仓储返回倒序
    }

    private void stubModelReply(String json) {
        ChatResponse resp = new ChatResponse();
        resp.setContent(json);
        when(provider.invoke(any(), any())).thenReturn(resp);
    }

    private static MessageEntity msg(int seq, String role, String content) {
        MessageEntity m = new MessageEntity();
        m.setSeq(seq);
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    private static MemoryView memory(String id, String content) {
        MemoryView v = new MemoryView();
        v.setId(id);
        v.setType("PREFERENCE");
        v.setContent(content);
        return v;
    }

    // ===== 开关与门控 =====

    @Test
    @DisplayName("开关关闭(部署未开 或 用户自己关了): 连计数都不查 —— 不留任何后台查询")
    void disabledNeverTouchesRepository() {
        enable(false, 4, 2);
        assertFalse(extractor().shouldExtract("u1", "s1"));
        verifyNoInteractions(messageRepository);
    }

    @Test
    @DisplayName("每 N 轮门控: 只在第 N 的整数轮触发")
    void gateFiresOnEveryNthTurn() {
        enable(true, 4, 2);
        MemoryAutoExtractor ex = extractor();

        when(messageRepository.countBySessionIdAndRole("s1", "user")).thenReturn(3L);
        assertFalse(ex.shouldExtract("u1", "s1"), "第 3 轮不该抽");

        when(messageRepository.countBySessionIdAndRole("s1", "user")).thenReturn(4L);
        assertTrue(ex.shouldExtract("u1", "s1"), "第 4 轮该抽");

        when(messageRepository.countBySessionIdAndRole("s1", "user")).thenReturn(8L);
        assertTrue(ex.shouldExtract("u1", "s1"));
    }

    @Test
    @DisplayName("未登录/无会话时门控直接为假")
    void gateRequiresUserAndSession() {
        enable(true, 4, 2);
        MemoryAutoExtractor ex = extractor();
        assertFalse(ex.shouldExtract(null, "s1"));
        assertFalse(ex.shouldExtract("u1", null));
    }

    // ===== 动作执行 =====

    @Test
    @DisplayName("ADD: 写入并带上来源会话与消息序号, 且只写用户级")
    void addWritesWithSource() {
        enable(true, 4, 2);
        stubConversation("s1", 4);
        when(memoryService.list("u1", false)).thenReturn(List.of());
        stubModelReply("""
                {"actions":[{"op":"ADD","type":"PREFERENCE","content":"用户偏好中文回答","topic":"语言","importance":0.9}]}
                """);

        extractor().extract("u1", "s1");

        ArgumentCaptor<SaveRequest> cap = ArgumentCaptor.forClass(SaveRequest.class);
        verify(memoryService).create(org.mockito.ArgumentMatchers.eq("u1"), cap.capture(),
                org.mockito.ArgumentMatchers.eq(MemoryOrigin.AUTO));
        assertEquals("用户偏好中文回答", cap.getValue().getContent());
        assertEquals("s1", cap.getValue().getSourceSessionId(), "溯源: 用户要能看出这条来自哪次对话");
        assertEquals(2, cap.getValue().getSourceMsgSeq(), "取本轮最后一条消息的序号");
        assertNull(cap.getValue().getScope(), "自动抽取只写用户级(默认值), 不归属到某个专家");
    }

    @Test
    @DisplayName("ADD 去重: 与既有记忆相似度过高时跳过 —— 重复条目会双份占用常驻注入额度")
    void addSkipsNearDuplicate() {
        enable(true, 4, 2);
        stubConversation("s1", 4);
        when(memoryService.list("u1", false))
                .thenReturn(List.of(memory("m1", "用户偏好用中文回答")));
        stubModelReply("""
                {"actions":[{"op":"ADD","type":"PREFERENCE","content":"用户偏好中文回答"}]}
                """);

        extractor().extract("u1", "s1");

        verify(memoryService, never()).create(anyString(), any(), any());
    }

    @Test
    @DisplayName("单次上限: 超出的动作被丢弃 —— 一次灌太多会把背景预算吃光")
    void respectsMaxPerRun() {
        enable(true, 4, 1);
        stubConversation("s1", 4);
        when(memoryService.list("u1", false)).thenReturn(List.of());
        stubModelReply("""
                {"actions":[
                  {"op":"ADD","type":"PREFERENCE","content":"偏好 A"},
                  {"op":"ADD","type":"PREFERENCE","content":"偏好 B"},
                  {"op":"ADD","type":"PREFERENCE","content":"偏好 C"}]}
                """);

        extractor().extract("u1", "s1");

        verify(memoryService, times(1)).create(anyString(), any(), any());
    }

    @Test
    @DisplayName("DELETE 走归档而不是硬删: 模型判断错一次不该永久丢数据")
    void deleteArchivesInsteadOfRemoving() {
        enable(true, 4, 2);
        stubConversation("s1", 4);
        when(memoryService.list("u1", false))
                .thenReturn(List.of(memory("m1", "用户偏好英文回答")));
        stubModelReply("""
                {"actions":[{"op":"DELETE","id":"m1","reason":"用户已改用中文"}]}
                """);

        extractor().extract("u1", "s1");

        verify(memoryService).archive("u1", "m1");
        verify(memoryService, never()).delete(anyString(), anyString());
    }

    @Test
    @DisplayName("NOOP 与未知 op: 什么都不做")
    void ignoresNoopAndUnknownOps() {
        enable(true, 4, 2);
        stubConversation("s1", 4);
        when(memoryService.list("u1", false)).thenReturn(List.of());
        stubModelReply("""
                {"actions":[{"op":"NOOP"},{"op":"REMEMBER_EVERYTHING","content":"x"}]}
                """);

        extractor().extract("u1", "s1");

        // 不产生任何写入。注意不能断言 verifyNoInteractions: list 是"读现有记忆"
        // (拼接提示词 + 本地查重都要用), 它必然会被调用 —— 断言要精确到写动作本身
        verify(memoryService, never()).create(anyString(), any(), any());
        verify(memoryService, never()).update(anyString(), anyString(), any());
        verify(memoryService, never()).archive(anyString(), anyString());
    }

    // ===== 容错 =====

    @Test
    @DisplayName("模型返回垃圾/调用异常: 一律静默, 绝不冒泡到对话链路")
    void neverThrowsOnGarbageOrFailure() {
        enable(true, 4, 2);
        MemoryAutoExtractor ex = extractor();

        stubConversation("s1", 4);
        when(memoryService.list("u1", false)).thenReturn(List.of());
        stubModelReply("模型今天不想输出 JSON");
        assertDoesNotThrow(() -> ex.extract("u1", "s1"), "解析失败等价于本轮没抽出东西");

        when(provider.invoke(any(), any())).thenThrow(new RuntimeException("模型服务 500"));
        assertDoesNotThrow(() -> ex.extract("u1", "s1"), "抽取是增强项, 失败不能影响对话");

        when(messageRepository.findBySessionIdOrderBySeqDesc(anyString(), any()))
                .thenThrow(new RuntimeException("DB 挂了"));
        assertDoesNotThrow(() -> ex.extract("u1", "s1"));
    }
}
