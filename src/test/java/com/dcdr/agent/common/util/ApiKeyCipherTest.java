package com.dcdr.agent.common.util;

import com.dcdr.agent.config.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ApiKeyCipher} 单元测试(V3.2)。
 *
 * <p>纯单元测试: 只需一个 {@link AgentProperties} 桩提供主密钥, 无 IO、无 Spring 上下文。
 * {@code init()} 为包级可见(原 {@code @PostConstruct}), 故测试类置于同一包内直接调用。
 *
 * <p><b>为什么值得测</b>: 它保护的是"用户自定义模型的 API Key 落库"这条链路,
 * 失效的两种后果都很严重 ——
 * <ul>
 *   <li>能加不能解 → 用户保存的模型全部不可用(且报错信息指向"主密钥可能已变更", 容易误判);</li>
 *   <li>解密不校验完整性 → 密文被篡改也照样"解出"垃圾数据, 静默损坏。</li>
 * </ul>
 * AES-GCM 是<b>认证加密</b>, 后一种情况本应被 tag 校验拦住 —— 本测试正是要确认这一点
 * 真的生效(而不是"用了 GCM 就等于安全"这种想当然)。
 */
class ApiKeyCipherTest {

    private static final String PLAINTEXT = "sk-abcdef0123456789";

    /** 构造一个已完成初始化的加密器(用 getter 拿默认嵌套配置对象, 避免依赖外层类的 setter) */
    private static ApiKeyCipher cipherWithSecret(String secret) {
        AgentProperties properties = new AgentProperties();
        properties.getModels().setApiKeySecret(secret);
        ApiKeyCipher cipher = new ApiKeyCipher(properties);
        cipher.init();
        return cipher;
    }

    // ==================================================================
    //  加解密往返
    // ==================================================================

    @Nested
    @DisplayName("加解密往返")
    class RoundTrip {

        @Test
        @DisplayName("encrypt → decrypt 还原原文")
        void roundTripRestoresPlaintext() {
            ApiKeyCipher cipher = cipherWithSecret("test-master-secret");

            assertEquals(PLAINTEXT, cipher.decrypt(cipher.encrypt(PLAINTEXT)));
        }

        @Test
        @DisplayName("空字符串可往返(边界不抛异常)")
        void roundTripEmptyString() {
            ApiKeyCipher cipher = cipherWithSecret("test-master-secret");

            assertEquals("", cipher.decrypt(cipher.encrypt("")));
        }

        @Test
        @DisplayName("含中文与特殊字符的明文可往返")
        void roundTripNonAscii() {
            ApiKeyCipher cipher = cipherWithSecret("test-master-secret");
            String text = "密钥-中文-!@#$%^&*()_+";

            assertEquals(text, cipher.decrypt(cipher.encrypt(text)));
        }

        @Test
        @DisplayName("主密钥未配置时走兜底密钥, 仍可用(但会告警)")
        void worksWithFallbackSecret() {
            ApiKeyCipher cipher = cipherWithSecret(null);

            assertEquals(PLAINTEXT, cipher.decrypt(cipher.encrypt(PLAINTEXT)));
        }
    }

    // ==================================================================
    //  IV 随机性
    // ==================================================================

    @Nested
    @DisplayName("IV 随机性")
    class IvRandomness {

        @Test
        @DisplayName("同一明文两次加密结果不同(每次使用随机 IV, 避免密文可被比对)")
        void sameInputProducesDifferentCiphertext() {
            ApiKeyCipher cipher = cipherWithSecret("test-master-secret");

            String first = cipher.encrypt(PLAINTEXT);
            String second = cipher.encrypt(PLAINTEXT);

            assertNotEquals(first, second, "若两次密文相同, 说明 IV 固定 —— 用户密钥可被直接比对");
            assertEquals(PLAINTEXT, cipher.decrypt(first));
            assertEquals(PLAINTEXT, cipher.decrypt(second));
        }
    }

    // ==================================================================
    //  完整性: GCM 认证失败必须抛异常, 而不是返回垃圾
    // ==================================================================

    @Nested
    @DisplayName("完整性校验(GCM 认证标签)")
    class Integrity {

        @Test
        @DisplayName("换用不同主密钥解密时失败(而非解出乱码)")
        void failsWithDifferentMasterSecret() {
            ApiKeyCipher encoder = cipherWithSecret("secret-A");
            ApiKeyCipher decoder = cipherWithSecret("secret-B");
            String encrypted = encoder.encrypt(PLAINTEXT);

            assertThrows(IllegalStateException.class, () -> decoder.decrypt(encrypted));
        }

        @Test
        @DisplayName("密文被篡改一位即被检测出(认证加密的核心价值)")
        void detectsTamperedCiphertext() {
            ApiKeyCipher cipher = cipherWithSecret("test-master-secret");
            String encrypted = cipher.encrypt(PLAINTEXT);

            // 翻转最后 1 bit —— 落在 GCM 的认证标签上
            byte[] raw = Base64.getDecoder().decode(encrypted);
            raw[raw.length - 1] ^= 0x01;
            String tampered = Base64.getEncoder().encodeToString(raw);

            assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered),
                    "GCM 的 tag 校验必须拦住篡改; 若此处放行, 说明完整性保护未生效");
        }

        @Test
        @DisplayName("非法密文与过短负载均抛异常, 且异常语义统一")
        void rejectsInvalidInput() {
            ApiKeyCipher cipher = cipherWithSecret("test-master-secret");

            assertThrows(IllegalStateException.class, () -> cipher.decrypt("这不是-base64!!"));
            // 仅 5 字节, 不足以容纳 12 字节 IV
            assertThrows(IllegalStateException.class,
                    () -> cipher.decrypt(Base64.getEncoder().encodeToString(new byte[5])));
        }
    }
}
