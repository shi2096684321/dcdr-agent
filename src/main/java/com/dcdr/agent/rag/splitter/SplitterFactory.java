package com.dcdr.agent.rag.splitter;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * 分块器工厂。
 * <p>
 * 取代原先"全局单例 TextSplitter"的装配方式: 每个知识库存自己的
 * {@code chunk_strategy / chunk_size / chunk_overlap}, 摄取时按库构造对应分块器。
 * 未传的参数回退到 {@code agent.rag.splitter.*} 全局默认, 因此旧知识库(策略字段为空)行为不变。
 */
@Component
@RequiredArgsConstructor
public class SplitterFactory {

    private final RagProperties properties;

    /** 解析并校验后的分块参数, 供 Service 落库与工厂构造共用, 避免两处各校验一遍 */
    public record SplitOptions(ChunkStrategy strategy, int chunkSize, int chunkOverlap) {
    }

    /**
     * 按 code 构造分块器。
     *
     * @param strategyCode 策略 code; 为 null/空时取全局默认(兼容存量知识库)
     * @param chunkSize    块大小; 为 null 时取全局默认
     * @param chunkOverlap 重叠字符数; 为 null 时取全局默认
     * @throws BusinessException 策略不支持或参数越界(400)
     */
    public TextSplitter create(String strategyCode, Integer chunkSize, Integer chunkOverlap) {
        return create(resolve(strategyCode, chunkSize, chunkOverlap));
    }

    /** 完全使用全局配置(供 Bean 装配 / 评测脚本使用) */
    public TextSplitter createDefault() {
        return create(resolve(null, null, null));
    }

    public SplitOptions resolve(String strategyCode, Integer chunkSize, Integer chunkOverlap) {
        RagProperties.SplitterConfig defaults = properties.getSplitter();

        ChunkStrategy strategy;
        if (strategyCode == null || strategyCode.isBlank()) {
            strategy = ChunkStrategy.fromCode(defaults.getStrategy())
                    .orElseThrow(() -> new BusinessException(400,
                            "全局分块策略配置无效: " + defaults.getStrategy()));
        } else {
            strategy = ChunkStrategy.fromCode(strategyCode)
                    .orElseThrow(() -> new BusinessException(400,
                            "不支持的分块策略: " + strategyCode + "，可选: " + supportedCodes()));
        }

        int size = chunkSize != null ? chunkSize : defaults.getChunkSize();
        int overlap = chunkOverlap != null ? chunkOverlap : defaults.getChunkOverlap();
        try {
            // 与所有具体策略共用同一套参数域校验(size > 0, 0 <= overlap < size)
            SplitterSupport.validate(size, overlap);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "分块参数无效: " + e.getMessage());
        }
        return new SplitOptions(strategy, size, overlap);
    }

    public TextSplitter create(SplitOptions options) {
        return switch (options.strategy()) {
            case FIXED -> new FixedSplitter(options.chunkSize(), options.chunkOverlap());
            case RECURSIVE -> new RecursiveSplitter(options.chunkSize(), options.chunkOverlap());
            case SENTENCE -> new SentenceSplitter(options.chunkSize(), options.chunkOverlap());
            case MARKDOWN -> new MarkdownSplitter(options.chunkSize(), options.chunkOverlap());
        };
    }

    /** 供接口错误提示 / 前端联调使用的稳定 code 列表 */
    public String supportedCodes() {
        return ChunkStrategy.all().stream().map(ChunkStrategy::getCode).collect(Collectors.joining(" | "));
    }
}
