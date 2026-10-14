package com.dcdr.agent.auth.ownership;

/**
 * 受归属保护(ownership)的资源类型清单 (V3.1 F-41)。
 * <p>
 * 每个类型对应一个 {@link ResourceOwnershipProvider} 实现, 由
 * {@link OwnershipRegistry} 在启动时自动装配。新增受保护资源时:
 * <ol>
 *   <li>在此枚举添加一项;</li>
 *   <li>新增一个 {@code ResourceOwnershipProvider} Bean(只依赖 Repository, 避免与 Service 形成环)。</li>
 * </ol>
 * 之后在 Controller 方法上标注 {@link RequireOwnership} 即完成接入, 无需再写任何判断逻辑。
 */
public enum ResourceType {

    /** 会话 */
    SESSION("session", "会话"),
    /** 知识库 */
    KNOWLEDGE_BASE("kb", "知识库"),
    /** 知识库文档 */
    DOCUMENT("document", "文档"),
    /** 自定义 Agent 定义 */
    AGENT("agent", "Agent"),
    /** 工作流定义 */
    WORKFLOW("workflow", "工作流"),
    /** 工作流执行实例 */
    WORKFLOW_EXECUTION("workflow_execution", "工作流执行记录"),
    /** 定时任务 */
    SCHEDULED_TASK("scheduled_task", "定时任务"),
    /** Webhook 端点 */
    WEBHOOK("webhook", "Webhook"),
    /** 用户模型配置 */
    USER_MODEL("user_model", "模型配置"),
    /** 创作项目 */
    PROJECT("project", "创作项目"),
    /** 生成记录 */
    GENERATION("generation", "生成记录"),
    /** 模板市场分享记录(分享者归属, 用于"下架分享"校验) */
    TEMPLATE_SHARE("template_share", "模板分享"),
    /** 长期记忆条目 (V3.1) */
    MEMORY("memory", "记忆"),
    /** 技能 (V3.1 专家体系): 按需加载的领域知识包 */
    SKILL("skill", "技能");

    private final String code;
    private final String label;

    ResourceType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 审计日志与错误标识用的稳定编码 */
    public String code() {
        return code;
    }

    /** 面向用户的中文名称 */
    public String label() {
        return label;
    }
}
