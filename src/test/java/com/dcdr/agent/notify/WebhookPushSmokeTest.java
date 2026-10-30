package com.dcdr.agent.notify;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Webhook 推送冒烟测试。
 * <p>
 * <b>只给一个 webhook 地址就能跑</b>：不启动 Spring 上下文、不连数据库、不需要模型 Key，
 * 直接走真实渠道实现（含加签、截断、errcode 解析），用于排查「配了但收不到消息」。
 * <p>
 * 覆盖的失败面：出口网络不通（公司内网封 oapi/qyapi）、webhook 拼错或机器人被删、
 * 加签 secret 不对、安全设置是关键词模式但正文没带关键词、被频率限制。
 * <p>
 * <b>注意：配了地址就会真的往群里发一条消息</b>，别在共享群上反复跑。
 * <p>
 * 运行方式（命令行）：
 * <pre>
 *   export NOTIFY_SMOKE_TEST=true                    # 必需：显式开启，否则整类跳过
 *   export AGENT_NOTIFY_DINGTALK_WEBHOOK="https://oapi.dingtalk.com/robot/send?access_token=xxx"
 *   export AGENT_NOTIFY_DINGTALK_SECRET="SECxxx"     # 「加签」模式填这个
 *   export AGENT_NOTIFY_DINGTALK_KEYWORD="定时任务"   # 「自定义关键词」模式填这个
 *   mvn test -Dtest=WebhookPushSmokeTest
 *
 *   # 企业微信同理：
 *   export AGENT_NOTIFY_WECOM_WEBHOOK="https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx"
 * </pre>
 * 这些也可以只写进项目根目录的 {@code .env}（本类会自动读取），但 {@code NOTIFY_SMOKE_TEST}
 * 建议只在需要验证时临时 export —— 写进 {@code .env} 会让每次全量回归都往群里发消息。
 * <p>
 * 运行方式二（IDEA 里直接点运行）：VM options 加
 * {@code -Dnotify.smoke=true -Ddingtalk.webhook=... -Ddingtalk.secret=...}。
 * <p>
 * 未提供地址时整类跳过（{@link Assumptions}），不会让 CI 变红。所以 CI 上如果配置了同名
 * 环境变量，要留意它会真的发消息 —— 想彻底静默就别在 CI 环境里注入这两个变量。
 * <p>
 * <b>参数从哪来</b>（按优先级，取到就停）：
 * <ol>
 *   <li>{@code -D} 系统属性，如 {@code -Ddingtalk.webhook=...}（IDEA 里临时指定最方便）；</li>
 *   <li>当前进程的环境变量；</li>
 *   <li>项目根目录的 {@code .env} —— <b>本类自己解析</b>。</li>
 * </ol>
 * 第 3 条是刻意补的兜底：{@code docker compose} 会解析 {@code .env}，但 {@code mvn} 不会，
 * 于是"在 .env 里配好了、跑测试却提示未配置"是个必然会踩到的坑。
 * 补上之后同一份 {@code .env} 对容器和本地测试同时生效；该文件已在 {@code .gitignore} 中。
 * <p>
 * 注意 {@code src/test/resources/application-test.yml} <b>不生效</b>：本类刻意不起 Spring
 * （要能在没有数据库、没有模型 Key 的机器上跑），那份配置只有 Spring 上下文才会读。
 */
class WebhookPushSmokeTest {

    /**
     * 总闸门：默认<b>不执行</b>。
     * <p>
     * 没有它的话，一旦机器上配了 webhook，`mvn test` 全量回归就会真的往群里发消息、
     * 还会因为外部服务波动把构建搞红 —— 单元测试不该依赖网络。
     * 所以必须显式打开：{@code NOTIFY_SMOKE_TEST=true}（或 {@code -Dnotify.smoke=true}）。
     */
    @BeforeAll
    static void requireExplicitSwitch() {
        // 诊断信息全部放在闸门之前：即使最终跳过，也能据此确认"配置是否被正确读入"，
        // 不必先开开关发一条消息再来查。
        System.out.println("[notify-smoke] 工作目录   = " + System.getProperty("user.dir"));
        System.out.println("[notify-smoke] 定位到的 .env = " + locateDotEnv());
        System.out.println("[notify-smoke] 开关 NOTIFY_SMOKE_TEST = "
                + orEmpty(config("notify.smoke", "NOTIFY_SMOKE_TEST")));
        dumpConfig("dingtalk.webhook", config("dingtalk.webhook", "AGENT_NOTIFY_DINGTALK_WEBHOOK"));
        dumpConfig("dingtalk.secret", config("dingtalk.secret", "AGENT_NOTIFY_DINGTALK_SECRET"));
        dumpConfig("dingtalk.keyword", config("dingtalk.keyword", "AGENT_NOTIFY_DINGTALK_KEYWORD"));
        dumpConfig("wecom.webhook", config("wecom.webhook", "AGENT_NOTIFY_WECOM_WEBHOOK"));

        Assumptions.assumeTrue("true".equalsIgnoreCase(config("notify.smoke", "NOTIFY_SMOKE_TEST")),
                "冒烟测试默认不执行（会真的向群里发消息）。"
                        + "开启方式: 环境变量 NOTIFY_SMOKE_TEST=true，或 VM options 加 -Dnotify.smoke=true");
    }

    private static String orEmpty(String s) {
        return s == null || s.isBlank() ? "<未设置>" : s;
    }

    @Test
    @DisplayName("钉钉：给一个 webhook 地址即可验证推送链路")
    void pushToDingtalk() {
        String webhook = config("dingtalk.webhook", "AGENT_NOTIFY_DINGTALK_WEBHOOK");
        Assumptions.assumeTrue(!webhook.isBlank(),
                "未提供钉钉 webhook，跳过。设置 AGENT_NOTIFY_DINGTALK_WEBHOOK 或 -Ddingtalk.webhook=");
        String secret = config("dingtalk.secret", "AGENT_NOTIFY_DINGTALK_SECRET");
        // 机器人若用「自定义关键词」模式, 正文必须含该词, 否则钉钉返回 310000
        String keyword = config("dingtalk.keyword", "AGENT_NOTIFY_DINGTALK_KEYWORD");
        Assumptions.assumeTrue(webhook.startsWith("https://oapi.dingtalk.com/robot/send"),
                "地址看起来不是钉钉机器人 webhook（应以 https://oapi.dingtalk.com/robot/send 开头）");

        NotifyProperties props = new NotifyProperties();
        props.setEnabled(true);
        props.getDingtalk().setWebhookUrl(webhook);
        props.getDingtalk().setSecret(secret);
        props.getDingtalk().setKeyword(keyword);

        NotifyResult result = new DingtalkBotNotifier(props, new NotifyTemplate(props))
                .send(sample("钉钉冒烟测试"));
        assertTrue(result.isSuccess(), () -> "推送失败: " + result.getError());
    }

    @Test
    @DisplayName("企业微信：给一个 webhook 地址即可验证推送链路")
    void pushToWecom() {
        String webhook = config("wecom.webhook", "AGENT_NOTIFY_WECOM_WEBHOOK");
        Assumptions.assumeTrue(!webhook.isBlank(),
                "未提供企业微信 webhook，跳过。设置 AGENT_NOTIFY_WECOM_WEBHOOK 或 -Dwecom.webhook=");
        Assumptions.assumeTrue(webhook.startsWith("https://qyapi.weixin.qq.com/cgi-bin/webhook/send"),
                "地址看起来不是企业微信群机器人 webhook（应以 https://qyapi.weixin.qq.com/cgi-bin/webhook/send 开头）");

        NotifyProperties props = new NotifyProperties();
        props.setEnabled(true);
        props.getWecom().setWebhookUrl(webhook);

        NotifyResult result = new WecomBotNotifier(props, new NotifyTemplate(props))
                .send(sample("企微冒烟测试"));
        assertTrue(result.isSuccess(), () -> "推送失败: " + result.getError());
    }

    /**
     * 用一份真实感数据推一次，用来确认"实际观感"（排版、列表、加粗在钉钉里的渲染）。
     * <p>与冒烟测试分开：冒烟那条刻意写得像测试，这条写得像真实产出，别混。
     * <p>单独跑: {@code NOTIFY_SMOKE_TEST=true mvn test -Dtest="WebhookPushSmokeTest#pushRealisticSample"}
     */
    @Test
    @DisplayName("演示：用一份真实感数据推送一次")
    void pushRealisticSample() {
        String webhook = config("dingtalk.webhook", "AGENT_NOTIFY_DINGTALK_WEBHOOK");
        Assumptions.assumeTrue(!webhook.isBlank(), "未提供钉钉 webhook，跳过");

        NotifyProperties props = new NotifyProperties();
        props.setEnabled(true);
        props.getDingtalk().setWebhookUrl(webhook);
        props.getDingtalk().setSecret(config("dingtalk.secret", "AGENT_NOTIFY_DINGTALK_SECRET"));
        props.getDingtalk().setKeyword(config("dingtalk.keyword", "AGENT_NOTIFY_DINGTALK_KEYWORD"));

        NotifyResult result = new DingtalkBotNotifier(props, new NotifyTemplate(props))
                .send(realisticSample());
        assertTrue(result.isSuccess(), () -> "推送失败: " + result.getError());
    }

    /** 模拟一份「抓取类任务」的真实产出：正文远超人能一眼看完的长度 */
    private static NotifyMessage realisticSample() {
        String content = """
                今日 AI 领域 5 条要闻：

                1. OpenAI 发布新一代推理模型，官方称在数学与代码基准上较上代提升约 30%。
                   值得注意的是定价未变，但上下文窗口扩大到 40 万 token。
                2. 某开源社区发布轻量级向量数据库，宣称在 1000 万条向量下召回率不低于商业方案，
                   单机内存占用约 8GB，支持增量写入。
                3. 国内某云厂商上线模型推理缓存服务，对重复 prompt 命中率高的场景可降低约 40% 成本，
                   目前仅支持对话类接口。
                4. 一份针对 200 家企业的调研显示，2026 年将 Agent 投入生产环境的比例从 12% 升至 34%，
                   但其中只有不到一半建立了人工兜底流程。
                5. 有团队开源了面向 Java 的 Agent 编排框架，主打响应式与工具调用链的可观测性，
                   已提供 Spring Boot Starter。

                **值得关注**：第 4 条里"人工兜底流程"缺失的问题，和最近在做的推送通知是同一类需求 ——
                让 Agent 的结果能被及时看到，而不是等出问题才回头翻日志。如果这条链路补齐，
                「跑完没人知道」的成本会明显下降。
                """;
        return NotifyMessage.builder()
                .taskId("demo:morning-report")
                .taskName("今日 AI 资讯汇总")
                .status("success")
                .content(content)
                .durationMs(12_600)
                .runAt(LocalDateTime.now())
                .build();
    }

    // ===== 辅助 =====

    /** 系统属性优先（便于 IDEA 里临时指定），其次环境变量，最后回落到项目根目录的 .env */
    private static String config(String sysProp, String envName) {
        String v = System.getProperty(sysProp);
        if (v == null || v.isBlank()) v = System.getenv(envName);
        if (v == null || v.isBlank()) v = dotEnv().get(envName);
        return v == null ? "" : v.trim();
    }

    private static volatile Map<String, String> dotEnvCache;

    /**
     * 打印脱敏后的配置值，用于回答"为什么读不到 / 为什么失败"。
     * <p>只输出来源与长度，绝不输出密钥、token 原文 —— 测试日志很容易被贴进聊天窗口。
     */
    private static void dumpConfig(String name, String value) {
        System.out.println("[notify-smoke] " + name + " = "
                + (value == null || value.isBlank() ? "<未配置>" : mask(value)));
    }

    private static String mask(String v) {
        for (String marker : new String[]{"access_token=", "key="}) {
            int at = v.indexOf(marker);
            if (at >= 0) {
                int end = at + marker.length();
                return v.substring(0, end) + "***(共 " + (v.length() - end) + " 字符)";
            }
        }
        return v.length() <= 8
                ? "***(共 " + v.length() + " 字符)"
                : v.substring(0, 4) + "***(共 " + v.length() + " 字符)";
    }

    private static Map<String, String> dotEnv() {
        Map<String, String> cache = dotEnvCache;
        if (cache != null) return cache;
        cache = parseDotEnv();
        dotEnvCache = cache;
        return cache;
    }

    /**
     * 解析项目根目录的 {@code .env}（若有）。
     * <p>
     * 只做够用为止的 dotenv 语法：跳过空行与 {@code #} 注释、兼容 {@code export} 前缀、
     * 去掉成对引号、剥离 UTF-8 BOM（Windows 记事本保存会带，不处理的话首个键名会变成
     * {@code \uFEFFAGENT_NOTIFY...} 从而永远匹配不上）。
     * <p>
     * 任何异常都当成"没有 .env"处理 —— 这是本地便利功能，不该让测试失败。
     */
    private static Map<String, String> parseDotEnv() {
        Path file = locateDotEnv();
        if (file == null) return Map.of();
        Map<String, String> map = new HashMap<>();
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw;
                if (!line.isEmpty() && line.charAt(0) == '\uFEFF') line = line.substring(1);
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("export ")) line = line.substring("export ".length()).trim();
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                map.put(line.substring(0, eq).trim(), unquote(line.substring(eq + 1).trim()));
            }
        } catch (Exception ignored) {
            return Map.of();
        }
        return map;
    }

    /** 从当前工作目录向上找 4 层，兼容 surefire 在子模块/子目录下 fork 的情况 */
    private static Path locateDotEnv() {
        Path dir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++) {
            Path candidate = dir.resolve(".env");
            if (Files.isRegularFile(candidate)) return candidate;
            dir = dir.getParent();
        }
        return null;
    }

    /** 去掉成对的双引号或单引号；URL 里带 {@code ?} {@code &} 时用户通常会加引号 */
    private static String unquote(String v) {
        if (v.length() >= 2) {
            char first = v.charAt(0);
            char last = v.charAt(v.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return v.substring(1, v.length() - 1);
            }
        }
        return v;
    }

    /**
     * 构造一条成功状态的消息。
     * <p>用 success 而不是 failed，是因为失败模板里有「错误信息」段落，容易让人误以为
     * 冒烟测试本身出错了。
     */
    private static NotifyMessage sample(String name) {
        LocalDateTime now = LocalDateTime.now();
        return NotifyMessage.builder()
                .taskId("smoke:" + System.currentTimeMillis())
                .taskName(name)
                .status("success")
                .content("这是一条冒烟测试消息。\n"
                        + "收到即说明 webhook 地址、出口网络与签名都正常。\n"
                        + "发送时间: " + now)
                .durationMs(0)
                .runAt(now)
                .build();
    }
}
