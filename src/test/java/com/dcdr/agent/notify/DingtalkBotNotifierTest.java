package com.dcdr.agent.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 钉钉渠道测试。
 * <p>
 * 加签是这里最容易写错的地方: 被签的字符串是 {@code timestamp + "\n" + secret},
 * 而 secret 同时是 HMAC 的密钥。少一个换行符、密钥用错, 都只会得到一句
 * {@code sign not match}, 看不出具体哪里错。
 */
class DingtalkBotNotifierTest {

    private static final String BASE_URL = "https://oapi.dingtalk.com/robot/send?access_token=abc123";

    @Test
    @DisplayName("加签结果与官方公式一致 (含换行符, secret 作 HMAC 密钥)")
    void signMatchesOfficialFormula() throws Exception {
        String secret = "SEC-test-secret";
        long timestamp = 1_700_000_000_000L;

        String sign = DingtalkBotNotifier.sign(secret, timestamp);

        // 签名是 Base64 后再 URLEncode, 解码回来应是 32 字节 (SHA-256 输出)
        byte[] decoded = Base64.getDecoder()
                .decode(URLDecoder.decode(sign, StandardCharsets.UTF_8));
        assertEquals(32, decoded.length);

        // 独立按官方公式重算一遍做对照, 防止实现里漏掉 "\n" 或把密钥写成 stringToSign
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal(
                (timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(expected, decoded);
    }

    @Test
    @DisplayName("timestamp 不同则签名不同 (不是静态串)")
    void signVariesWithTimestamp() {
        String a = DingtalkBotNotifier.sign("SEC-x", 1_700_000_000_000L);
        String b = DingtalkBotNotifier.sign("SEC-x", 1_700_000_000_001L);
        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("配了 secret 时把 timestamp 与 sign 追加到已有 query 之后")
    void signedUrlAppendsToExistingQuery() {
        String url = DingtalkBotNotifier.signedUrl(BASE_URL, "SEC-x", 1_700_000_000_000L);
        assertTrue(url.startsWith(BASE_URL), "原有 access_token 必须保留");
        assertTrue(url.contains("&timestamp=1700000000000"));
        assertTrue(url.contains("&sign="));
    }

    @Test
    @DisplayName("未配 secret 时原样返回 (关键词/IP 白名单模式无需加签)")
    void signedUrlWithoutSecretIsUnchanged() {
        assertEquals(BASE_URL, DingtalkBotNotifier.signedUrl(BASE_URL, "", 1L));
        assertEquals(BASE_URL, DingtalkBotNotifier.signedUrl(BASE_URL, null, 1L));
        assertEquals(BASE_URL, DingtalkBotNotifier.signedUrl(BASE_URL, "   ", 1L));
    }

    @Test
    @DisplayName("不加签的地址也能正确拼参数 (没有 query 时用 ?)")
    void signedUrlHandlesUrlWithoutQuery() {
        String url = DingtalkBotNotifier.signedUrl("https://oapi.dingtalk.com/robot/send",
                "SEC-x", 1_700_000_000_000L);
        assertTrue(url.contains("?timestamp="));
    }

    @Test
    @DisplayName("按字符截断时不留下孤立的高位代理 (emoji 被切成一半)")
    void truncateCharsKeepsSurrogatePairsIntact() {
        String emoji = "😀".repeat(10); // 每个 emoji 占 2 个 char
        String out = NotifyRender.truncateChars(emoji, 5, "");
        assertEquals(4, out.length());
        assertFalse(Character.isHighSurrogate(out.charAt(out.length() - 1)),
                "末尾出现了孤立高位代理, 渲染时会变成乱码");
    }

    @Test
    @DisplayName("配置关键词后追加到正文末尾")
    void keywordIsAppended() {
        assertEquals("正文\n\n定时任务", DingtalkBotNotifier.appendKeyword("正文", "定时任务"));
    }

    @Test
    @DisplayName("未配关键词时不追加任何内容")
    void blankKeywordIsIgnored() {
        assertEquals("正文", DingtalkBotNotifier.appendKeyword("正文", ""));
        assertEquals("正文", DingtalkBotNotifier.appendKeyword("正文", null));
        assertEquals("正文", DingtalkBotNotifier.appendKeyword("正文", "   "));
    }

    @Test
    @DisplayName("关键词长度计入预算，渲染前就扣掉，不会被截断吃掉")
    void keywordCostIsReserved() {
        assertEquals(0, DingtalkBotNotifier.keywordCost(null));
        assertEquals(0, DingtalkBotNotifier.keywordCost("   "));
        // 关键词本身 + 与正文之间的两个换行
        assertEquals("定时任务".length() + 2, DingtalkBotNotifier.keywordCost("定时任务"));

        int budget = 200 - DingtalkBotNotifier.keywordCost("定时任务");
        String body = NotifyRender.truncateChars("a".repeat(5000), budget, "");
        String out = DingtalkBotNotifier.appendKeyword(body, "定时任务");
        assertTrue(out.endsWith("定时任务"), "关键词被截断吃掉了, 钉钉会回 310000");
        assertEquals(200, out.length());
    }

    @Test
    @DisplayName("常见错误码翻译成可操作的中文, 原始信息保留在后")
    void errorCodesAreTranslated() {
        assertTrue(DingtalkBotNotifier.describe(130101, "").contains("限流 10 分钟"));
        assertTrue(DingtalkBotNotifier.describe(310000, "sign not match").contains("加签"));
        assertTrue(DingtalkBotNotifier.describe(310000, "sign not match").contains("sign not match"));
        assertTrue(DingtalkBotNotifier.describe(300005, "").contains("机器人已被移除"));
        assertTrue(DingtalkBotNotifier.describe(999, "unknown").contains("999"));
    }
}
