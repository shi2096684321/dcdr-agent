package com.dcdr.agent.rag.splitter;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归字符分块器 (V2.1 起为默认策略, 现为四种可选策略之一)。
 * <p>
 * 策略: 依次按优先级分隔符尝试切分, 直到块大小 ≤ chunkSize。
 * <ol>
 *   <li>优先按段落分隔符 {@code \n\n} 切分</li>
 *   <li>块仍过大则按单换行 {@code \n} 切分</li>
 *   <li>仍过大则按句号/问号/感叹号 {@code 。.!?} 切分</li>
 *   <li>最后按字符硬切</li>
 * </ol>
 * 切完后按顺序合并相邻小块使每块尽量接近 chunkSize, 并在块间保留 overlap 字符避免语义断裂。
 * <p>
 * 与 LangChain RecursiveCharacterTextSplitter 思路一致, 不引第三方依赖。
 */
@Slf4j
public class RecursiveSplitter implements TextSplitter {

    /** 分隔符优先级 (从高到低) */
    private static final String[] DEFAULT_SEPARATORS = {"\n\n", "\n", "。", ". ", "! ", "? ", " ", ""};

    private final int chunkSize;
    private final int chunkOverlap;
    /** 子类可替换分隔符优先级(如句子优先策略), 默认与历史实现一致 */
    private final String[] separators;

    public RecursiveSplitter(int chunkSize, int chunkOverlap) {
        this(chunkSize, chunkOverlap, DEFAULT_SEPARATORS);
    }

    protected RecursiveSplitter(int chunkSize, int chunkOverlap, String[] separators) {
        SplitterSupport.validate(chunkSize, chunkOverlap);
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.separators = separators;
    }

    @Override
    public List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        // 1. 递归切到不超过 chunkSize 的原子块
        List<String> atoms = new ArrayList<>();
        recursiveSplit(text, 0, atoms);

        // 2. 合并相邻原子块, 每块尽量接近 chunkSize; 块间保留 overlap 字符
        List<String> result = SplitterSupport.mergeWithOverlap(atoms, chunkSize, chunkOverlap);
        log.debug("递归分块完成: 原文 {} 字符 -> {} 块 (chunkSize={}, overlap={})",
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

    /**
     * 递归切分: 用第 {@code sepIdx} 个分隔符把 text 切成段, 对仍超长的段用下一个分隔符继续切;
     * 已 ≤ chunkSize 的段落直接加入 atoms。最后一个分隔符为空串(按字符硬切)。
     */
    private void recursiveSplit(String text, int sepIdx, List<String> atoms) {
        if (text.isEmpty()) return;
        if (text.length() <= chunkSize || sepIdx >= separators.length) {
            // 已达最小粒度, 直接硬切到 chunkSize
            if (text.length() <= chunkSize) {
                atoms.add(text);
                return;
            }
            for (int i = 0; i < text.length(); i += chunkSize) {
                atoms.add(text.substring(i, Math.min(i + chunkSize, text.length())));
            }
            return;
        }
        String sep = separators[sepIdx];
        String[] parts;
        if (sep.isEmpty()) {
            // 兜底: 按字符硬切
            for (int i = 0; i < text.length(); i += chunkSize) {
                atoms.add(text.substring(i, Math.min(i + chunkSize, text.length())));
            }
            return;
        }
        parts = text.split(java.util.regex.Pattern.quote(sep), -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            // 保留分隔符(粘到本段末尾), 避免 "标题\n正文" 被切成无结构碎片
            if (i < parts.length - 1) {
                part = part + sep;
            }
            if (part.length() <= chunkSize) {
                if (!part.isBlank()) atoms.add(part);
            } else {
                // 仍过长, 用下一级分隔符继续切
                recursiveSplit(part, sepIdx + 1, atoms);
            }
        }
    }
}
