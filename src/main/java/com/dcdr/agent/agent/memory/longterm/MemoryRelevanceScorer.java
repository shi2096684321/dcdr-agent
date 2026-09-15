package com.dcdr.agent.agent.memory.longterm;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 长期记忆相关性评分器 (V3.1, 无向量版本)。
 * <p>
 * 承担「条件触发」召回的打分: 判断一条记忆的 {@code topic} / {@code tags} 是否与当轮提问相关。
 * <p>
 * <b>为什么不用向量</b>: 本阶段单用户记忆条目在几十条量级, 全量常驻 + 关键词触发的确定性
 * 完全够用, 且结果可解释、可复现、零额外基础设施 (与 CodeBuddy 的记忆机制同路线)。
 * 待条目规模突破阈值后再引入向量召回 (P2), 届时本类作为"精确匹配通道"与向量通道并行融合
 * —— 精确匹配对标识符类查询 (包名、错误码、文件名) 始终优于语义匹配, 不会被替换掉。
 * <p>
 * <b>算法</b>: 候选词若被提问直接包含则满分; 否则退化为字符 2-gram 覆盖率, 以适配中文
 * (中文无空格分词, "部署环境" 与 "部署到测试环境" 靠分词无法直接命中)。
 */
@Component
public class MemoryRelevanceScorer {

    /** 命中阈值: 2-gram 覆盖率低于此值视为不相关 */
    public static final double HIT_THRESHOLD = 0.5;

    /** n-gram 长度: 2 元足够兼顾中文短语与英文单词 */
    private static final int NGRAM = 2;

    /**
     * 计算记忆与提问的相关性。
     *
     * @param query 当轮用户提问
     * @param topic 记忆的条件触发键 (可为空)
     * @param tags  记忆标签 (可为空)
     * @return 0.0~1.0 的相关性; 两者均为空时返回 0 (不参与条件触发)
     */
    public double score(String query, String topic, Collection<String> tags) {
        if (query == null || query.isBlank()) return 0.0;
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isEmpty()) return 0.0;

        double best = matchOne(normalizedQuery, topic);
        if (tags != null) {
            for (String tag : tags) {
                best = Math.max(best, matchOne(normalizedQuery, tag));
            }
        }
        return best;
    }

    /** 是否命中: 相关性达到阈值 */
    public boolean hit(String query, String topic, Collection<String> tags) {
        return score(query, topic, tags) >= HIT_THRESHOLD;
    }

    /** 解析逗号分隔的标签串 (与 MemoryEntity.tags 的存储格式对应) */
    public static List<String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 序列化标签列表为存储格式 */
    public static String joinTags(Collection<String> tags) {
        if (tags == null || tags.isEmpty()) return null;
        String joined = tags.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(java.util.stream.Collectors.joining(","));
        return joined.isEmpty() ? null : joined;
    }

    /** 单个候选词的匹配度 */
    private double matchOne(String normalizedQuery, String candidate) {
        if (candidate == null || candidate.isBlank()) return 0.0;
        String normalizedCandidate = normalize(candidate);
        if (normalizedCandidate.isEmpty()) return 0.0;

        // 直接包含: 精确命中 (标识符/短语类触发键走这条)
        if (normalizedQuery.contains(normalizedCandidate)) return 1.0;

        Set<String> candidateGrams = ngrams(normalizedCandidate);
        if (candidateGrams.isEmpty()) return 0.0;
        Set<String> queryGrams = ngrams(normalizedQuery);
        long overlap = candidateGrams.stream().filter(queryGrams::contains).count();
        return (double) overlap / candidateGrams.size();
    }

    /** 提取字符 n-gram 集合 (去重保序) */
    private static Set<String> ngrams(String text) {
        Set<String> grams = new LinkedHashSet<>();
        for (int i = 0; i + NGRAM <= text.length(); i++) {
            grams.add(text.substring(i, i + NGRAM));
        }
        return grams;
    }

    /** 归一化: 转小写并仅保留字母与数字, 消除空白与标点带来的差异 */
    private static String normalize(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        return sb.toString();
    }
}
