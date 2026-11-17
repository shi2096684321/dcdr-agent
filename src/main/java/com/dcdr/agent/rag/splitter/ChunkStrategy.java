package com.dcdr.agent.rag.splitter;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 文档切分策略。
 * <p>
 * 每个知识库可独立选择一种策略, 由 {@link SplitterFactory} 按 code 构造对应的 {@link TextSplitter}。
 * code 落库为 {@code knowledge_bases.chunk_strategy}, 与前端选项一一对应。
 * <p>
 * 选型的取舍(为什么是这四种, 没有上"语义切分"):
 * <ul>
 *   <li>{@link #FIXED}: 最快、块长最均匀; 适合本身无结构的文本, 但会在句中截断。</li>
 *   <li>{@link #RECURSIVE}: 默认, 段落 → 换行 → 句子 → 空格 → 字符逐级降级, 通用性最好。</li>
 *   <li>{@link #SENTENCE}: 句子优先, 尽量不切断句子; 适合 FAQ / 问答 / 散文等以句为语义单位的语料。</li>
 *   <li>{@link #MARKDOWN}: 标题结构优先, 每块携带所属标题路径; 适合带清晰标题的技术文档、README、Wiki。</li>
 * </ul>
 * 语义切分(逐句 embedding → 按相似度聚合)虽也是主流方案, 但会让"分块"阶段产生额外向量调用与成本,
 * 且需要把 EmbeddingProvider 引入 TextSplitter 抽象 —— 本轮不引入, 保持分块为纯文本、零网络操作。
 */
public enum ChunkStrategy {

    FIXED("fixed", "固定长度", "按字符数等长切分，速度最快；可能在句子中间截断"),
    RECURSIVE("recursive", "递归字符", "段落→换行→句子→空格逐级降级，通用默认策略"),
    SENTENCE("sentence", "句子切分", "优先按句号/问号/叹号切分，尽量不切断完整句子"),
    MARKDOWN("markdown", "Markdown 结构", "按标题层级切分，每块带上所属标题，适合技术文档");

    private final String code;
    private final String label;
    private final String description;

    ChunkStrategy(String code, String label, String description) {
        this.code = code;
        this.label = label;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    /** 供前端下拉框/接口文档使用的稳定有序列表 */
    public static List<ChunkStrategy> all() {
        return Arrays.asList(values());
    }

    /**
     * 按 code 解析(大小写与首尾空白不敏感)。
     * 兼容存量知识库该字段为空的情况: 返回空, 由调用方回退到全局默认。
     */
    public static java.util.Optional<ChunkStrategy> fromCode(String code) {
        if (code == null || code.isBlank()) return java.util.Optional.empty();
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(s -> s.code.equals(normalized))
                .findFirst();
    }
}
