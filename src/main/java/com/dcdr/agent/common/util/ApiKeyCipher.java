package com.dcdr.agent.common.util;

import com.dcdr.agent.config.AgentProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * 用户自定义模型 apiKey 的落库加密器。
 * <p>
 * AES-128-GCM 实现 (认证加密, 密文自带完整性校验)。主密钥取自
 * {@code agent.models.api-key-secret} (环境变量 {@code MODEL_API_KEY_SECRET}),
 * SHA-256 摘要后取前 16 字节作为 AES 密钥, 与主密钥长度无关。
 * <p>
 * 安全契约:
 * <ul>
 *   <li>用户 apiKey 只在写入数据库前加密、读取后解密, 不落日志;</li>
 *   <li>对前端始终不回显明文 (见 UserModelDTO.list 掩码逻辑)。</li>
 * </ul>
 */
@Slf4j
@Component
public class ApiKeyCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final AgentProperties properties;
    private volatile SecretKeySpec key;

    public ApiKeyCipher(AgentProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        String secret = null;
        if (properties.getModels() != null) {
            secret = properties.getModels().getApiKeySecret();
        }
        if (secret == null || secret.isBlank()) {
            // 兜底: 极低概率走到这里(未配置), 用固定派生的密钥, 并告警提示生产务必注入
            secret = "dcdr-agent-model-api-key-secret-default";
            log.warn("agent.models.api-key-secret 未配置, 使用内置兜底密钥(生产务必通过 MODEL_API_KEY_SECRET 注入)");
        }
        try {
            byte[] digest = sha256(secret);
            this.key = new SecretKeySpec(Arrays.copyOf(digest, 16), "AES");
        } catch (Exception e) {
            throw new IllegalStateException("初始化 apiKey 加密器失败", e);
        }
    }

    public String encrypt(String plaintext) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            byte[] iv = new byte[IV_LENGTH];
            new java.security.SecureRandom().nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            // 格式: base64(iv + ciphertext), iv 前置便于解密
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("apiKey 加密失败", e);
        }
    }

    public String decrypt(String ciphertext) {
        try {
            byte[] payload = Base64.getDecoder().decode(ciphertext);
            if (payload.length <= IV_LENGTH) {
                throw new IllegalArgumentException("非法的密文长度");
            }
            byte[] iv = Arrays.copyOfRange(payload, 0, IV_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(payload, IV_LENGTH, payload.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("apiKey 解密失败(主密钥可能已变更, 请重新保存该模型)", e);
        }
    }

    private static byte[] sha256(String input) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
    }
}