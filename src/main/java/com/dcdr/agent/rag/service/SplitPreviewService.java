package com.dcdr.agent.rag.service;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.rag.dto.SplitPreviewResult;
import com.dcdr.agent.rag.splitter.SplitterFactory;
import com.dcdr.agent.rag.splitter.TextSplitter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 切分预览 Service。
 * <p>
 * 纯计算, 不碰数据库/文件/向量: 调分块参数时先在界面上看效果, 比"上传一个文档等入库完再看"
 * 快得多, 也不会因为反复试参数而产生 embedding 费用或脏数据。
 * <p>
 * 与真实入库的差异(界面需提示用户): 入库时会额外拼接 {@code [文件: …]} 元信息前缀,
 * 且开启重叠后块长会略超过 chunkSize —— 预览只反映纯文本切分结果。
 */
@Service
@RequiredArgsConstructor
public class SplitPreviewService {

    /** 预览文本上限: 再长就不是"试切一段", 而是拿整本书压测接口了 */
    private static final int MAX_TEXT_CHARS = 200_000;
    /** 默认返回片段条数 */
    private static final int DEFAULT_MAX_CHUNKS = 20;
    /** 返回片段条数硬上限 */
    private static final int HARD_MAX_CHUNKS = 50;
    /** 单块预览字符上限: 块本身可能 8000 字, 50 块全量返回会让响应过大 */
    private static final int MAX_PREVIEW_CHARS = 1200;

    private final SplitterFactory splitterFactory;

    /**
     * 按指定策略与参数试切分。
     *
     * @param text         样本文本, 必填
     * @param strategy     策略 code; 为空取全局默认
     * @param chunkSize    块大小; 为空取全局默认
     * @param chunkOverlap 重叠字符数; 为空取全局默认
     * @param maxChunks    返回片段条数; 为空默认 20, 最多 50
     */
    public SplitPreviewResult preview(String text, String strategy, Integer chunkSize,
                                      Integer chunkOverlap, Integer maxChunks) {
        if (text == null || text.isBlank()) {
            throw new BusinessException(400, "请提供要预览的文本");
        }
        if (text.length() > MAX_TEXT_CHARS) {
            throw new BusinessException(400, "预览文本过长(最多 " + MAX_TEXT_CHARS + " 字符)");
        }

        // 复用工厂校验: 未知策略/非法参数同样返回 400, 与建库时口径一致
        TextSplitter splitter = splitterFactory.create(strategy, chunkSize, chunkOverlap);
        List<String> all = splitter.split(text);

        int limit = maxChunks == null ? DEFAULT_MAX_CHUNKS : Math.min(Math.max(maxChunks, 1), HARD_MAX_CHUNKS);
        List<SplitPreviewResult.ChunkPreview> previews = new ArrayList<>(Math.min(all.size(), limit));
        int total = 0;
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (String chunk : all) {
            int len = chunk.length();
            total += len;
            min = Math.min(min, len);
            max = Math.max(max, len);
        }
        for (int i = 0; i < Math.min(all.size(), limit); i++) {
            String chunk = all.get(i);
            boolean cut = chunk.length() > MAX_PREVIEW_CHARS;
            String content = cut ? chunk.substring(0, MAX_PREVIEW_CHARS) : chunk;
            previews.add(new SplitPreviewResult.ChunkPreview(i, chunk.length(), content, cut));
        }

        return new SplitPreviewResult(
                all.size(),
                text.length(),
                all.isEmpty() ? 0 : (int) Math.round((double) total / all.size()),
                all.isEmpty() ? 0 : min,
                max,
                all.size() > previews.size(),
                previews);
    }
}
