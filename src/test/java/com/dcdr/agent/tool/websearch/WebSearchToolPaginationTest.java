package com.dcdr.agent.tool.websearch;

import com.dcdr.agent.config.ToolsProperties;
import com.dcdr.agent.tool.ToolInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSearchToolPaginationTest {

    @Test
    @DisplayName("只有支持分页的搜索源才向模型暴露 page")
    void pageSchemaFollowsProviderCapability() {
        WebSearchProvider paged = provider(true);
        WebSearchTool pagedTool = tool(paged);
        assertTrue(pagedTool.getParameters().properties().containsKey("page"));

        WebSearchProvider unpaged = provider(false);
        WebSearchTool unpagedTool = tool(unpaged);
        assertFalse(unpagedTool.getParameters().properties().containsKey("page"),
                "DeepSeek 没有分页参数, 暴露 page 只会诱导模型发无效请求");
    }

    @Test
    @DisplayName("第二页原样传给 provider, 第一页保持默认")
    void forwardsSupportedPage() throws Exception {
        WebSearchProvider provider = provider(true);
        when(provider.search(anyString(), isNull(), anyInt(), anyInt())).thenReturn(outcome());
        WebSearchTool tool = tool(provider);

        ToolInfo second = tool.executeWithInfo(Map.of("query", "Java 招聘", "page", 2));
        ToolInfo first = tool.executeWithInfo(Map.of("query", "Java 招聘"));

        assertEquals(1, second.getSources().size());
        assertEquals(1, first.getSources().size());
        verify(provider).search("Java 招聘", null, 8, 2);
        verify(provider).search("Java 招聘", null, 8, 1);
    }

    @Test
    @DisplayName("第三页直接拒绝, 不能静默改成第二页或发网络请求")
    void rejectsThirdPageWithoutCallingProvider() throws Exception {
        WebSearchProvider provider = provider(true);
        WebSearchTool tool = tool(provider);

        ToolInfo info = tool.executeWithInfo(Map.of("query", "Java 招聘", "page", 3));

        assertTrue(info.getContent().contains("页码仅支持 1~2"), info.getContent());
        verify(provider, never()).search(anyString(), isNull(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("不支持分页的源收到 page=2 时明确拒绝, 不重复返回第一页")
    void rejectsPageForUnsupportedProvider() throws Exception {
        WebSearchProvider provider = provider(false);
        WebSearchTool tool = tool(provider);

        ToolInfo info = tool.executeWithInfo(Map.of("query", "Java 招聘", "page", 2));

        assertTrue(info.getContent().contains("当前搜索源不支持分页"), info.getContent());
        verify(provider, never()).search(anyString(), isNull(), anyInt(), anyInt());
    }

    private static WebSearchTool tool(WebSearchProvider provider) {
        ToolsProperties properties = new ToolsProperties();
        WebProviderFactory factory = mock(WebProviderFactory.class);
        when(factory.searchProvider()).thenReturn(provider);
        return new WebSearchTool(properties, factory);
    }

    private static WebSearchProvider provider(boolean paged) {
        WebSearchProvider provider = mock(WebSearchProvider.class);
        when(provider.id()).thenReturn(paged ? "searxng" : "deepseek");
        when(provider.supportsPagination()).thenReturn(paged);
        return provider;
    }

    private static SearchOutcome outcome() {
        Map<String, Object> source = Map.of(
                "index", 1,
                "title", "岗位详情",
                "url", "https://example.com/job/1",
                "snippet", "岗位要求与薪资");
        return new SearchOutcome(1, 0, 0, List.of(source));
    }
}
