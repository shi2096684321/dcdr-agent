package com.dcdr.agent.common.util;

import lombok.extern.slf4j.Slf4j;

/**
 * 启动期「默认密钥」拦截 —— 拒绝带着随仓公开的密钥把服务拉起来。
 *
 * <p><b>为什么不是"打个 WARN 就算了"</b>: 这类默认值的问题在于它<b>不会自己暴露</b> ——
 * 服务照常启动、功能照常可用，只有"密钥其实是公开的"这一条被静默吞掉，而启动日志里的 WARN
 * 恰好是最容易被淹没的一行。代价却完全不对称:
 * <ul>
 *   <li>JWT 签名密钥公开 → 任何人都能<b>伪造任意用户(含 admin)的 Token</b>, 不需要知道任何密码;</li>
 *   <li>用户模型主密钥公开 → <b>拿到数据库里的密文就能解出用户真实的模型 API Key</b>。</li>
 * </ul>
 * 所以默认姿态是<b>拒绝启动</b>，并在报错里给出可直接复制的生成命令。
 *
 * <p><b>为什么用 opt-out 而不是判断 profile</b>: "是否生产环境"无法从代码可靠推断
 * (profile 命名因人而异，本地开发也常常连真实数据库)。这里把判断权交给部署方，
 * 且必须是<b>显式写一行配置</b>才放行 —— 默认安全，想放宽得动手。
 */
@Slf4j
public final class SecretGuard {

    private SecretGuard() {
    }

    /** 一条密钥的"身份": 显示名、注入用的环境变量、放行它的配置项、以及一旦被利用的后果 */
    public record Spec(String displayName, String envVar, String optOutProperty, String risk) {
    }

    /** JWT 签名密钥: 泄露后果是"可伪造任意身份的 Token" */
    public static final Spec JWT_SECRET = new Spec(
            "agent.auth.jwt.secret (JWT 签名密钥)", "JWT_SECRET",
            "agent.auth.jwt.allow-default-secret",
            "签名密钥公开可知 => 任何人都能伪造任意用户(含 admin)的 Token, 无需知道任何密码");

    /** 用户自定义模型 apiKey 的加密主密钥: 泄露后果是"密文可被解出" */
    public static final Spec MODEL_KEY_SECRET = new Spec(
            "agent.models.api-key-secret (用户模型 Key 的加密主密钥)", "MODEL_API_KEY_SECRET",
            "agent.models.allow-default-key-secret",
            "主密钥公开可知 => 拿到数据库里的密文即可解出用户真实的模型 API Key");

    /**
     * 校验: 为空、或等于随仓发布的默认值时拒绝启动(除非已显式放行)。
     *
     * <p>刻意把"等于默认值"也当成未配置 —— 有人把旧的默认值复制回配置文件时同样要拦下,
     * 只判空会漏掉这种情况。
     *
     * @param value          当前生效的密钥值
     * @param shippedDefault 随仓发布的默认值
     * @param allowed        是否已通过 opt-out 显式放行
     * @throws IllegalStateException 未配置且未放行时抛出(由 Spring 启动期向上冒泡, 上下文不启动)
     */
    public static void rejectPublicDefault(Spec spec, String value, String shippedDefault, boolean allowed) {
        boolean insecure = value == null || value.isBlank() || value.equals(shippedDefault);
        if (!insecure) return;

        if (allowed) {
            log.warn("{} 未配置真实密钥, 已由 {} 显式放行 —— {}", spec.displayName(),
                    spec.optOutProperty(), spec.risk());
            return;
        }

        throw new IllegalStateException("""
                %s 未配置(或仍是随仓内置的默认值)。
                风险: %s
                修复: 写入项目根目录的 .env —— 本地运行(mvn spring-boot:run / IDE)与 docker compose
                      都会读它(见 application.yml 的 spring.config.import); 也可直接注入环境变量 %s。
                      生成一个随机值:
                  openssl rand -hex 32                                                     # Linux / macOS
                  [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')        # PowerShell
                若确认这是仅本机、不对外暴露的环境, 可在配置中显式设置 %s=true 跳过本检查。
                """.formatted(spec.displayName(), spec.risk(), spec.envVar(), spec.optOutProperty()));
    }
}
