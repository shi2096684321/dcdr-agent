package com.dcdr.agent.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 已入库分块的分页查询结果。
 * <p>
 * 用于"这个文档到底被切成了什么样"的排障/核对场景: 返回的是<b>真实入库内容</b>
 * (含 {@code [文件: …]} 元信息前缀, 且受 overlap 影响), 与切分预览的纯文本结果不同。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChunkPageResult {

    /** 该文档的分块总数 */
    private long total;

    /** 当前页(0-based) */
    private int page;

    /** 每页条数 */
    private int size;

    /** 当前页分块 */
    private List<ChunkItem> chunks;

    /** 单块明细: 不带向量, 只给界面需要的字段 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkItem {

        /** 块序号(0-based) */
        private int chunkIndex;

        /** 内容字符数 */
        private int length;

        /** 估算 token 数(入库时算好落库) */
        private Integer tokenCount;

        /** 真实入库内容(含元信息前缀) */
        private String content;
    }
}
