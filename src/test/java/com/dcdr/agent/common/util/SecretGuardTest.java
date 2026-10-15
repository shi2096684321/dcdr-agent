package com.dcdr.agent.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动期默认密钥拦截的契约测试。
 *
 * <p>为什么值得单独测: 这段逻辑的价值全在"<b>拒绝</b>"上 —— 它一旦失效(判空写错、默认值常量
 * 被改得对不上、放行开关默认值反了), 表现是服务照常启动、功能照常可用, 没有任何症状。
 * 而它守的是"任何人都能伪造 admin Token"这类后果。
 *
 * <p>同时钉住<b>报错内容</b>: 这条消息是部署方唯一的线索 —— 必须出现环境变量名与放行配置项名,
 * 否则用户只知道"起不来", 不知道要配什么。
 */
class SecretGuardTest {

    private static final String SHIPPED_DEFAULT = "shipped-default-secret";

    @Test
    @DisplayName("未配置(空)时拒绝启动")
    void rejectsBlank() {
        assertThrows(IllegalStateException.class,
                () -> SecretGuard.rejectPublicDefault(SecretGuard.JWT_SECRET, null, SHIPPED_DEFAULT, false));
        assertThrows(IllegalStateException.class,
                () -> SecretGuard.rejectPublicDefault(SecretGuard.JWT_SECRET, "   ", SHIPPED_DEFAULT, false));
    }

    @Test
    @DisplayName("仍是随仓默认值时拒绝启动 —— 有人把它复制回配置也要拦下, 只判空会漏")
    void rejectsShippedDefault() {
        assertThrows(IllegalStateException.class,
                () -> SecretGuard.rejectPublicDefault(SecretGuard.JWT_SECRET,
                        SHIPPED_DEFAULT, SHIPPED_DEFAULT, false));
    }

    @Test
    @DisplayName("注入真实密钥后放行")
    void acceptsRealSecret() {
        assertDoesNotThrow(() -> SecretGuard.rejectPublicDefault(SecretGuard.JWT_SECRET,
                "a-real-random-secret-0123456789abcdef", SHIPPED_DEFAULT, false));
    }

    @Test
    @DisplayName("显式放行时不拦(opt-out)")
    void optOutAllowsAnyValue() {
        assertDoesNotThrow(() -> SecretGuard.rejectPublicDefault(SecretGuard.JWT_SECRET,
                null, SHIPPED_DEFAULT, true));
    }

    @Test
    @DisplayName("报错必须给出「配哪个环境变量」与「怎么生成」, 否则用户只知道起不来")
    void messageIsActionable() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SecretGuard.rejectPublicDefault(SecretGuard.MODEL_KEY_SECRET,
                        null, SHIPPED_DEFAULT, false));

        String msg = e.getMessage();
        assertTrue(msg.contains("MODEL_API_KEY_SECRET"), "必须点明环境变量名: " + msg);
        assertTrue(msg.contains(SecretGuard.MODEL_KEY_SECRET.optOutProperty()),
                "必须点明放行开关的配置项名: " + msg);
        assertTrue(msg.contains("openssl rand"), "必须给出可直接复制的生成命令: " + msg);
        assertTrue(msg.contains(".env"),
                "必须点出 .env 这条路径 —— 实际最容易踩的就是「写进 .env 却报未配置」"
                        + "(docker compose 读 .env, 而本地运行只认环境变量): " + msg);
        assertTrue(msg.contains(SecretGuard.MODEL_KEY_SECRET.risk()),
                "必须写清后果, 否则容易被当成「顺手改一下的配置」就绕过去了: " + msg);
    }
}
