package com.dcdr.agent.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 切分预览结果。
 * <p>
 * 供"调整分块参数时先看看效果"使用: 只做文本切分, <b>不入库、不向量化、不写磁盘</b>,
 * 因此可以随意试参数而不会产生任何副作用或 embedding 成本。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SplitPreviewResult {

    /** 实际切出的总块数(不受返回条数上限影响, 用于展示真实规模) */
    private int chunkCount;

    /** 原文总字符数 */
    private int totalChars;

    /** 平均每块字符数(四舍五入) */
    private int avgChars;

    /** 最短块字符数(无块时为 0) */
    private int minChars;

    /** 最长块字符数(无块时为 0) */
    private int maxChars;

    /** 返回的片段列表是否被条数上限截断(为 true 时 chunkCount > chunks.size()) */
    private boolean truncated;

    /** 片段明细(最多返回调用方指定的条数) */
    private List<ChunkPreview> chunks;

    /** 单块预览: 只带前若干字符, 避免大块把响应撑爆 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkPreview {

        /** 块序号(0-based) */
        private int index;

        /** 该块完整长度(字符数) */
        private int length;

        /** 预览内容(可能被截断) */
        private String content;

        /** content 是否因过长被截断 */
        private boolean contentTruncated;
    }
}
