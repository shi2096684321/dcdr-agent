package com.dcdr.agent.agent.memory.longterm;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 记忆的写入动作 —— 由 {@code memory} 工具(模型在对话中主动调用)与自动抽取器<b>共用</b>。
 *
 * <p><b>为什么收敛到枚举</b>: 同一个业务概念此前有<b>两份字面量</b> —— 工具侧 {@code switch} 认
 * {@code "add"/"update"/"delete"}, 自动抽取侧 {@code switch} 认 {@code "ADD"/"UPDATE"/"DELETE"}。
 * 两边都在解析"不可信的模型输出", 却各自维护词表: 新增一个动作时改漏一边, 表现为
 * "工具能改、自动抽取当没看见"(或反之), 且没有任何编译错误。
 *
 * <p><b>大小写不敏感是有意的</b>: 工具 schema 与抽取提示词是两条独立的提示词, 历史上分别用了
 * 小写与大写。契约定在枚举里、解析做大小写归一, 就不必为了统一大小写去改提示词
 * —— 改提示词要重新观察模型输出, 而它的收益仅仅是"看起来整齐"。
 *
 * <p><b>DELETE 的界面措辞是"归档"</b>: 两条链路都实现为归档而非硬删(判错时可在记忆页一键恢复),
 * 故 {@link #label()} 用"归档"而不是"删除" —— 提示词里若写"删除", 模型与用户都会以为数据没了。
 */
public enum MemoryAction {

    /** 新增一条 —— 内容与现有记忆重复时会被查重挡下 */
    ADD("add", "新增"),

    /** 修改一条 —— 由内容相似度定位到既有记忆, 而不是暴露内部 id 给模型 */
    UPDATE("update", "修改"),

    /** 归档一条(可恢复) —— 不是硬删 */
    DELETE("delete", "归档");

    /** 工具参数 / 抽取输出里的取值(小写)。改名即改契约, 故写死而非依赖 {@code name()} */
    private final String value;
    /** 展示用短名 */
    private final String label;

    MemoryAction(String value, String label) {
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
     * 供工具 schema 描述使用, 形如 {@code add(新增) / update(修改) / delete(归档)}。
     * <p>由枚举拼装而不是手写: 提示词里那句"操作类型: ..."原本是手抄的, 加了动作却忘了改它,
     * 模型就永远不知道有这个动作可用 —— 而这类不一致不会报任何错。
     */
    public static String describeAll() {
        return Arrays.stream(values())
                .map(a -> a.value + "(" + a.label + ")")
                .collect(Collectors.joining(" / "));
    }

    /**
     * 解析模型给出的动作: 大小写不敏感, 未知/空返回 {@code null}(调用方按"忽略该动作"处理)。
     * <p>刻意不抛异常: 输入是不可信的模型输出, 认不出来属正常情况 —— 抛异常会让"模型多说了一个
     * 字段"升级成整次抽取失败。
     */
    public static MemoryAction from(String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        for (MemoryAction a : values()) {
            if (a.value.equalsIgnoreCase(v)) return a;
        }
        // NOOP 是提示词约定的"无需动作", 不是非法输入, 但也确实没有对应动作 → 返回 null
        return null;
    }
}
