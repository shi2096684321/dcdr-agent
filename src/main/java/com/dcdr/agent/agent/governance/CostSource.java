package com.dcdr.agent.agent.governance;

/**
 * 一次 LLM 调用的费用<b>出自哪条链路</b> —— 落 {@code llm_usage_logs.source}, 成本报表按它分组。
 *
 * <p><b>为什么收敛到枚举</b>: 这个取值此前是散落的字面量({@code "chat"} ×3、{@code "workflow"} ×3、
 * 一个私有常量、以及后加的 {@code "memory_extract"}), 却是<b>报表契约</b>的一部分 ——
 * 按来源聚合、CSV 导出、界面展示都读它。散着写的问题不是"难看", 而是新增一条链路时
 * (如记忆自动抽取、工具结果摘要)很容易漏记, 于是 F-39/F-44 的成本报表里就出现"看不见的费用":
 * 明明花了钱, 按来源看却对不上总账。
 *
 * <p><b>写入侧严格、读取侧宽容</b>: 参数类型是枚举, 编译器保证每个调用点都必须表态"这笔钱是谁花的";
 * 而解析历史数据时{@link #from} 遇到不认识的值返回 {@code null} —— 存量库里存在已弃用的来源,
 * 不能因此让报表失败。
 */
public enum CostSource {

    /** 对话主链路(流式 agentic 每轮生成 / 同步链路) */
    CHAT("chat", "对话"),

    /** 工作流节点执行 */
    WORKFLOW("workflow", "工作流"),

    /** 工具结果摘要(超长结果二次压缩) —— 同样是真实模型开销 */
    SUMMARY("tool_summary", "工具结果摘要"),

    /** 长期记忆自动抽取(每 N 轮一次) */
    MEMORY_EXTRACT("memory_extract", "记忆自动抽取");

    /** 落库取值; 与 enum 名分开写死: 改名即等于改报表口径与历史数据 */
    private final String value;
    /** 报表展示用短名 */
    private final String label;

    CostSource(String value, String label) {
        this.value = value;
        this.label = label;
    }

    public String value() {
        return value;
    }

    public String label() {
        return label;
    }

    /** 解析: 未知/空返回 {@code null}(读取侧宽容, 存量数据里可能有已弃用的来源) */
    public static CostSource from(String value) {
        if (value == null || value.isBlank()) return null;
        for (CostSource s : values()) {
            if (s.value.equalsIgnoreCase(value.trim())) return s;
        }
        return null;
    }

    /**
     * 报表展示: 认得出就用中文名, 认不出<b>原样返回</b>而不是统一显示"未知" ——
     * 历史数据里真存在已弃用的来源(如 {@code routing}), 把它们都吞成"未知"会让历史报表无法解释。
     */
    public static String labelOf(String value) {
        CostSource s = from(value);
        if (s != null) return s.label;
        return (value == null || value.isBlank()) ? "未知" : value;
    }
}
