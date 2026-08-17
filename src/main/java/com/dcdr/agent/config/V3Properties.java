package com.dcdr.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
         * 生产环境应填完整外网地址, 如 {@code https://api.example.com/api/webhooks/trigger}。
         */
        private String baseUrl = "";
    }
}
