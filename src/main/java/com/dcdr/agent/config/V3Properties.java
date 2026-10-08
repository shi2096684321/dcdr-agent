package com.dcdr.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V3.0 配置属性 (对应 V3.0 规划文档 §1.3 功能开关 / §4.4 / §7.2 / §13.3)。
 * <p>
 * 绑定 {@code agent.v3.*}, 默认全部关闭以兼容 V2.3 行为; 按需在 application.yml 中开启。
 * <ul>
 *   <li>{@code smart-routing}: F-30 智能路由层开关</li>
 *   <li>{@code execution}: F-33 执行容错(失败重试默认策略)</li>
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "agent.v3")
public class V3Properties {

    /** F-30 智能路由层配置 */
    private SmartRouting smartRouting = new SmartRouting();

    /** F-33 执行容错配置 */
    private Execution execution = new Execution();

    /** F-31 Webhook 触发层配置 */
    private Webhook webhook = new Webhook();

    /** F-39 成本与配额管理 (V3.0-beta) */
    private Quota quota = new Quota();

    /** F-40.3 API 限流 (V3.0-rc 生产化加固) */
    private RateLimit rateLimit = new RateLimit();

    /** F-40.4 LLM 熔断与降级 (V3.0-rc 生产化加固) */
    private CircuitBreaker circuitBreaker = new CircuitBreaker();

    /** F-37 插件系统 (V3.0-rc) */
    private Plugin plugin = new Plugin();

    /** F-36 多租户 SaaS 化 (V3.0-rc) */
    private Tenant tenant = new Tenant();

    /** V3.1 F-44.2 成本计价 (报表金额来源) */
    private Cost cost = new Cost();

    /**
     * V3.1 F-44.2 模型计价表。
     * <p>
     * 为什么需要它: {@code llm_usage_logs.cost_amount} 自 V3.0 起就存在但从未赋值 ——
     * 没有单价就无法把 token 折算成金额, 而"模型成本占比"在混合模型下与 token 占比并不等价
     * (贵模型 1 token ≠ 便宜模型 1 token)。
     * <p>
     * 未配置单价的模型: 落库金额为 null, 报表以 token 口径为准、金额列降级显示, 不臆造 0 元。
     */
    @Data
    public static class Cost {
        /** 模型计价表: key = 模型名, value = 单价(元 / 千 token) */
        private Map<String, ModelPrice> pricing = new LinkedHashMap<>();

        /**
         * 取模型单价: 精确匹配优先, 否则按<b>前缀</b>匹配
         * (配置 {@code qwen} 可命中 {@code qwen-max} / {@code qwen-plus} 等变体);
         * 未配置返回 {@code null}。
         */
        public ModelPrice priceOf(String model) {
            if (model == null || pricing == null || pricing.isEmpty()) return null;
            ModelPrice exact = pricing.get(model);
            if (exact != null) return exact;
            for (Map.Entry<String, ModelPrice> entry : pricing.entrySet()) {
                if (!model.startsWith(entry.getKey())) continue;
                return entry.getValue();
            }
            return null;
        }
    }

    /** 单个模型的单价 (元 / 千 token) */
    @Data
    public static class ModelPrice {
        /** 输入单价 (元 / 千 token) */
        private BigDecimal input = BigDecimal.ZERO;
        /** 输出单价 (元 / 千 token) */
        private BigDecimal output = BigDecimal.ZERO;
    }

    @Data
    public static class Quota {
        /** 是否启用配额拦截; 关闭时不做任何限额检查 */
        private boolean enabled = false;
        /** 用户级: 单日 token 上限 (0 = 不限) */
        private long dailyTokens = 100_000;
        /** 用户级: 单月 token 上限 (0 = 不限) */
        private long monthlyTokens = 2_000_000;
        /** 工作流级: 单次执行 token 上限 (0 = 不限); 超出时该执行标记 failed */
        private long workflowMaxTokensPerExecution = 50_000;
        /**
         * 对话/Agent 级: 单次任务 token 上限 (0 = 不限)。
         * <p>一次对话请求全生命周期(含多轮工具循环)的累计 token 达到该值后,
         * 在「轮次边界」停止派生下一轮并收尾, 已生成内容正常下发/落库。
         */
        private long taskMaxTokens = 50_000;
    }

    @Data
    public static class SmartRouting {
        /** 是否启用智能路由; 关闭时退化为 V2.3 行为(普通对话) */
        private boolean enabled = true;
        /** 置信度阈值, 低于此值回退到普通对话 (0~1) */
        private double confidenceThreshold = 0.6;
        /** 路由用的模型名称(可选, 留空则用系统默认对话模型, 建议用低成本模型) */
        private String model;
        /** 路由过程是否对用户可见(下发"已自动选择 XX"提示) */
        private boolean visibleHint = true;
        /**
         * LLM 结构化输出约束。
         * <ul>
         *   <li>{@code json_object} (默认): 强制模型输出合法 JSON, 兼容性最好 (Qwen/DeepSeek/GLM/OpenAI);
         *       仅保证语法合法, 不保证字段名/结构, 靠 prompt + 解析校验兜底</li>
         *   <li>{@code json_schema}: 按 JSON Schema strict 强约束字段 (需模型支持 strict 模式, 如 gpt-4o)</li>
         *   <li>{@code tool_call}: 把期望输出包装为工具并强制调用(tool_choice 指定函数), 从 arguments
         *       取符合 schema 的 JSON。schema 级保证且国产模型 function calling 普遍支持,
         *       国产路由模型 (Qwen/DeepSeek/GLM) 推荐此模式</li>
         *   <li>留空: 不约束, 仅靠 prompt</li>
         * </ul>
         * 切换到不支持的 response_format 可能 400; tool_call 需模型支持 function calling。
         */
        private String responseFormat = "json_object";
    }

    @Data
    public static class Execution {
        /** 步骤未显式配置重试时的默认重试策略 */
        private Retry retry = new Retry();

        @Data
        public static class Retry {
            /** 默认最大尝试次数(含首次, 1 表示不重试) */
            private int defaultMaxAttempts = 3;
            /** 退避策略: fixed / linear / exponential */
            private String backoff = "exponential";
            /** 初始退避延迟 ms */
            private long initialDelayMs = 1000;
            /** 退避乘数(exponential/linear 用) */
            private double multiplier = 2.0;
        }
    }

    @Data
    public static class Webhook {
        /**
         * Webhook 触发 URL 的外部基址 (回显给用户用)。
         * 留空时回显相对路径 {@code /api/webhooks/trigger}。
         * 生产环境应填完整外网地址, 如: {@code https://api.example.com/api/webhooks/trigger}。
         */
        private String baseUrl = "";
    }

    /** F-40.3 API 限流: 滑动窗口计数, 用户+IP 双维度 */
    @Data
    public static class RateLimit {
        /** 是否启用限流; 关闭时不拦截 */
        private boolean enabled = false;
        /** 登录用户请求上限 (次/分钟) */
        private int userRequestsPerMinute = 60;
        /** 匿名/按 IP 请求上限 (次/分钟), 主要保护未认证接口 */
        private int ipRequestsPerMinute = 120;
    }

    /** F-40.4 熔断: 按 LLM provider 名统计失败率, 开断后快速失败 */
    @Data
    public static class CircuitBreaker {
        /** 是否启用熔断; 关闭时全部直通 */
        private boolean enabled = false;
        /** 触发开断的失败率阈值 (0~1, 窗口内最小调用数需达到 minCalls) */
        private double failureRateThreshold = 0.5;
        /** 统计窗口内最小调用数 (不足时不判定开断, 避免小样本误判) */
        private int minCalls = 10;
        /** 开断后等待时间 (ms), 之后进入半开放行一次探测 */
        private long waitOpenMs = 30_000;
    }

    /** F-37 插件系统: 外部 JAR 加载 */
    @Data
    public static class Plugin {
        /** 是否启用插件加载; 关闭时忽略 plugins 目录 */
        private boolean enabled = false;
        /** 插件目录 (相对工作目录) */
        private String dir = "./plugins";
        /**
         * 危险 API 审计拦截: 扫描插件字节码中的 Runtime/ProcessBuilder/反射特征,
         * 发现即拒绝加载。关闭后仅 WARN 不拦截 (不推荐)。
         */
        private boolean blockDangerous = true;
    }

    /** F-36 多租户: 租户级资源配额 */
    @Data
    public static class Tenant {
        /** 是否启用多租户; 关闭时所有配额检查跳过 (用户 tenantId 仍可存储) */
        private boolean enabled = false;
    }
}
