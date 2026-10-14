package com.dcdr.agent.rag;

/**
 * 一次知识库检索的<b>发起方</b> (V3.1)。
 *
 * <p><b>为什么需要它</b>: 检索有两条发起路径 —— 用户勾选知识库后的强制检索、模型自主调用
 * {@code knowledge_base_search} 工具。它们在调用链里长得完全一样(都是 {@code kind=rag}),
 * 于是"这一轮究竟是谁决定要查的"从数据上无从判断。记下来源后一眼可答, 也才能按来源分别统计检索成本。
 *
 * <p><b>为什么必须是枚举而不是散落的字符串</b>: 这个取值是<b>三段代码共同的契约</b> ——
 * 后端两个调用点、调用链步骤字段、前端面板的中文显示, 外加库侧聚合
 * (见 {@code docs/sql/eval/retrieval-health.sql})。原先三处各写一份字面量, 改一边漏一边
 * 不会有任何编译或运行错误, 只会在面板上留下一个没人认得的英文单词。
 * 收敛到枚举后, 取值与中文名只有这一处定义, 前端改为直接消费后端下发的 {@link #label()}。
 */
public enum RagSource {

    /** 用户显式勾选了知识库(强意图) —— 未命中也要明确告知"未在所选知识库中找到" */
    FORCED("forced", "强制检索（你选了库）"),

    /**
     * 平台预检索(未显式选择时, 平台先拿原话代查一遍)。
     * <p>该路径<b>已移除</b> —— 它"每轮必查", 与"模型自主调用"在调用链里还难以区分。
     * 取值刻意保留: 存量调用链数据里就是它, 删掉会让历史记录显示不出中文。
     */
    AUTO("auto", "平台预检索"),

    /** 模型自主调用 {@code knowledge_base_search} —— 省 token, 闲聊不查库 */
    TOOL("tool", "模型自主调用");

    /** 下发 / 落库的取值(小写)。它与 enum 名分开写死: 改名即等于改契约, 不能被"重构"顺手带跑 */
    private final String value;
    /** 界面展示用短名 —— 由后端下发, 前端不再自己维护一份映射表 */
    private final String label;

    RagSource(String value, String label) {
        this.value = value;
        this.label = label;
    }

    public String value() {
        return value;
    }

    public String label() {
        return label;
    }

    /**
     * 解析: 未知/空一律返回 {@code null} 而不抛异常。
     * <p>取值来自历史调用链数据与上游, 出现不认识的字符串是正常情况, 不该让调用链记录因此失败。
     */
    public static RagSource from(String value) {
        if (value == null || value.isBlank()) return null;
        for (RagSource s : values()) {
            if (s.value.equalsIgnoreCase(value.trim())) return s;
        }
        return null;
    }
}
