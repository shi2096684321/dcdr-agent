package com.dcdr.agent.tool.websearch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 抓取兜底链的取舍测试。
 *
 * <p><b>核心要锁住的是"什么时候<b>不</b>兜底"</b>: 两个抓取源的超时都是 15s,
 * 无脑兜底会让单篇最坏 30s; 配上单轮 6 篇的预算就是 180s, 用户看到的是卡死。
 * 而兜底真正救得回的, 是 403/404 这类**秒级快速失败**的页面 —— 主源已经耗满超时的场景,
 * 换源大概率再等 15s 还是超时。所以用"主源耗时预算"当闸门, 而不是看失败原因。
 */
class FallbackFetchProviderTest {

    @Test
    @DisplayName("主源成功: 直接返回, 不碰兜底")
    void primarySuccessSkipsFallback() {
        Fake primary = new Fake("deepseek", new FetchedPage("t", "正文"), 0);
        Fake fallback = new Fake("local", new FetchedPage("t2", "兜底正文"), 0);
        FallbackFetchProvider chain = new FallbackFetchProvider(primary, fallback, 5000);

        FetchedPage page = chain.fetch("https://example.com/a", null);

        assertNotNull(page);
        assertEquals("正文", page.content());
        assertEquals(0, fallback.calls, "主源成功时不该浪费一次兜底请求");
    }

    @Test
    @DisplayName("主源快速失败: 换源兜底(这才是兜底的价值所在)")
    void fastFailureTriggersFallback() {
        Fake primary = new Fake("deepseek", null, 0);
        Fake fallback = new Fake("local", new FetchedPage("t", "兜底正文"), 0);
        FallbackFetchProvider chain = new FallbackFetchProvider(primary, fallback, 5000);

        FetchedPage page = chain.fetch("https://example.com/a", null);

        assertNotNull(page);
        assertEquals("兜底正文", page.content());
        assertEquals(1, fallback.calls);
    }

    @Test
    @DisplayName("主源已耗时超过预算: 放弃兜底, 避免超时叠加")
    void slowFailureSkipsFallback() {
        Fake primary = new Fake("deepseek", null, 120);
        Fake fallback = new Fake("local", new FetchedPage("t", "兜底正文"), 0);
        FallbackFetchProvider chain = new FallbackFetchProvider(primary, fallback, 50);

        FetchedPage page = chain.fetch("https://example.com/a", null);

        assertNull(page, "主源已慢到超预算, 换源只会把'慢'变成'更慢'");
        assertEquals(0, fallback.calls);
    }

    @Test
    @DisplayName("兜底源也不可用时返回 null, 不抛异常")
    void unavailableFallbackReturnsNull() {
        Fake primary = new Fake("deepseek", null, 0);
        Fake fallback = new Fake("local", new FetchedPage("t", "x"), 0);
        fallback.available = false;
        FallbackFetchProvider chain = new FallbackFetchProvider(primary, fallback, 5000);

        assertNull(chain.fetch("https://example.com/a", null));
        assertEquals(0, fallback.calls);
    }

    /** 手写 fake: 只测链路取舍, 不需要 Mockito 也不需要真网络 */
    private static final class Fake implements WebFetchProvider {
        private final String id;
        private final FetchedPage result;
        private final long sleepMs;
        private boolean available = true;
        private int calls;

        Fake(String id, FetchedPage result, long sleepMs) {
            this.id = id;
            this.result = result;
            this.sleepMs = sleepMs;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public FetchedPage fetch(String url, String query) {
            calls++;
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return result;
        }
    }
}
