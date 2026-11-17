package com.dcdr.agent.rag.splitter;

import java.util.ArrayList;
import java.util.List;

/**
 * 分块器公共逻辑。
 * <p>
 * 参数校验、硬切、<b>合并 + overlap 回溯</b>是所有策略共用的最后一道工序。
 * 抽出来是为了让四种策略的"块长语义"完全一致 —— 尤其保留既有约定:
 * overlap 是额外语义缓冲, 因此输出块<b>可能超过 chunkSize</b>。
 * <p>
 * 包内可见: 这是实现细节, 不进 public API。
 */
final class SplitterSupport {

    private SplitterSupport() {
    }

    /** 统一的构造参数校验, 失败信息沿用 RecursiveSplitter 原有措辞 */
    static void validate(int chunkSize, int chunkOverlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须大于 0, 实际: " + chunkSize);
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("chunkOverlap 必须在 [0, chunkSize) 范围, 实际: " + chunkOverlap);
        }
    }

    /** 按字符硬切为不超过 chunkSize 的等长块 */
    static List<String> hardSplit(String text, int chunkSize) {
        List<String> atoms = new ArrayList<>();
        for (int i = 0; i < text.length(); i += chunkSize) {
            atoms.add(text.substring(i, Math.min(i + chunkSize, text.length())));
        }
        return atoms;
    }

    /**
     * 合并相邻原子块到接近 chunkSize; 每落一块, 新块以旧块末尾 chunkOverlap 字符开头。
     * <p>
     * 与 V3.2 之前 RecursiveSplitter 的内联实现逐字等价 —— 既有单测固定了这些行为:
     * 分隔符粘在段末、相邻小块合并、overlap 回溯、块可超过 chunkSize。
     */
    static List<String> mergeWithOverlap(List<String> atoms, int chunkSize, int chunkOverlap) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder(chunkSize);
        for (String atom : atoms) {
            if (current.length() + atom.length() > chunkSize && current.length() > 0) {
                result.add(current.toString());
                if (chunkOverlap > 0 && current.length() > chunkOverlap) {
                    String tail = current.substring(current.length() - chunkOverlap);
                    current.setLength(0);
                    current.append(tail);
                } else {
                    current.setLength(0);
                }
            }
            current.append(atom);
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }
}
