package com.dcdr.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 「背景信息」预算属性 —— 长期记忆与 RAG 检索片段共用一套账 (V3.1)。
 * <p>
 * 对应 application.yml 中 {@code agent.context.budget.*}。
 * <p>
 * <b>为什么需要它</b>: 这两类内容都注入 system 上下文, 此前却各有限制、互不感知 ——
 * 记忆只有条数上限(常驻 20 + 触发 10 条), 单条可到 1000 字符, 最坏约 3 万字符;
 * 检索片段有 6000 字符预算。两者叠加时"先砍谁"没有任何规则, 全靠各自的默认值恰好不冲突。
 * <p>
 * <b>优先级: 记忆优先, 检索片段让位</b>。理由是两类内容的失效方式不同 ——
 * 记忆是"关于用户/这个 Agent 的稳定结论", 砍掉会让同一个问题在不同轮次得到不一致的
 * 个性化回答, 代价隐蔽; 而检索片段本就按"与当前提问的相关度"降序, 砍的是尾部最不相关的部分,
 * 损失最小。若某场景更需要保住资料, 把 {@code memory-max-chars} 调小即可 ——
 * 它同时会让出更多额度给检索片段。
 */
@Data
@ConfigurationProperties(prefix = "agent.context.budget")
public class ContextBudgetProperties {

    /**
     * 背景信息(长期记忆 + 检索片段)合计字符上限; &lt;= 0 表示不限制。
     * <p>
     * 默认 9000: 两类内容的常态占用约 1~2k + 5k, 留有余量 ——
     * 也就是<b>默认配置下这条上限不会触发</b>, 只在"记忆存了很多条"时才真正收紧。
     * 这样设计是为了不改变既有行为: 新的预算只处理以前没人处理的叠加情况。
     */
    private int backgroundMaxTotalChars = 9000;

    /**
     * 长期记忆单独上限; &lt;= 0 表示不限制。
     * <p>
     * 默认 6000 与 {@code agent.rag.inject.max-total-chars} 相同 —— 先保证任何一方都吃不到
     * 全局预算, 再由合计上限处理"两个都不超、加起来超"的那一段。
     */
    private int memoryMaxChars = 6000;
}
