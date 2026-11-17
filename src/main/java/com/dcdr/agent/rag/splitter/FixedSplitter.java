package com.dcdr.agent.rag.splitter;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 固定长度分块器。
 * <p>
 * 纯粹按字符数硬切, 不看段落/句子边界 —— 这是它存在的意义: 块长最均匀、切分最快、行为最可预测。
 * 适合本身就是连续文本(日志、转录稿、无结构语料)或下游更看重"每块 token 数稳定"的场景。
 * 代价是会在句中截断, 语义完整性不如其它策略。
 * <p>
 * 与 {@link RecursiveSplitter} 共用 {@link SplitterSupport} 的合并 + overlap 逻辑:
 * 首块恰好 chunkSize, 后续块为 chunkSize + overlap。
 */
@Slf4j
public class FixedSplitter implements TextSplitter {

    private final int chunkSize;
    private final int chunkOverlap;

    public FixedSplitter(int chunkSize, int chunkOverlap) {
        SplitterSupport.validate(chunkSize, chunkOverlap);
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    @Override
    public List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> result = SplitterSupport.mergeWithOverlap(
                SplitterSupport.hardSplit(text, chunkSize), chunkSize, chunkOverlap);
        log.debug("固定长度分块完成: 原文 {} 字符 -> {} 块 (chunkSize={}, overlap={})",
                text.length(), result.size(), chunkSize, chunkOverlap);
        return result;
    }

    @Override
    public int getChunkSize() {
        return chunkSize;
    }

    @Override
    public int getChunkOverlap() {
        return chunkOverlap;
    }
}
