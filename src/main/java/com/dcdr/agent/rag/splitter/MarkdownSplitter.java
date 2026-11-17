package com.dcdr.agent.rag.splitter;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 结构分块器。
 * <p>
 * 按 ATX 标题({@code # ~ ######})切分文档, 每个块都带上其所属标题路径 ——
 * 这样单独检索某一小节时, 片段里仍保留"这段属于哪个章节"的信息, 显著提升技术文档/Wiki 的召回质量。
 * <ul>
 *   <li>标题行在代码块({@code ```} / {@code ~~~})内时不识别, 避免把示例代码当章节。</li>
 *   <li>小节本身超过 chunkSize 时, 去掉标题前缀后交给 {@link RecursiveSplitter} 细分, 再把前缀贴回每块。</li>
 *   <li>全文没有任何标题时, 退化为默认递归分块(不因选错策略而丢失内容)。</li>
 * </ul>
 * 合并 + overlap 逻辑与其余策略共用 {@link SplitterSupport}, 保持块长语义一致。
 */
@Slf4j
public class MarkdownSplitter implements TextSplitter {

    /** ATX 标题: 行首最多 3 空格 + 1~6 个 # + 空白 + 标题内容(允许结尾的 #) */
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(.+?)[ \\t]*#*[ \\t]*$");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(```+|~~~+)");

    private final int chunkSize;
    private final int chunkOverlap;

    public MarkdownSplitter(int chunkSize, int chunkOverlap) {
        SplitterSupport.validate(chunkSize, chunkOverlap);
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    @Override
    public List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<Section> sections = parseSections(text);
        if (sections.isEmpty()) {
            // 没有标题结构: 退化为默认策略, 不产出只有标题的怪异块
            return new RecursiveSplitter(chunkSize, chunkOverlap).split(text);
        }

        List<String> atoms = new ArrayList<>();
        for (Section section : sections) {
            String prefix = buildPrefix(section.headings);
            String body = section.body.toString();
            String full = prefix + body;
            if (full.length() <= chunkSize) {
                if (!full.isBlank()) atoms.add(full);
                continue;
            }
            // 仅当前缀本身就放不进一块时, 才放弃"每块贴前缀"的约定, 退化为普通递归切整段
            if (prefix.length() >= chunkSize) {
                atoms.addAll(new RecursiveSplitter(chunkSize, 0).split(full));
                continue;
            }
            RecursiveSplitter bodySplitter = new RecursiveSplitter(chunkSize - prefix.length(), 0);
            for (String piece : bodySplitter.split(body)) {
                atoms.add(prefix + piece);
            }
        }

        List<String> result = SplitterSupport.mergeWithOverlap(atoms, chunkSize, chunkOverlap);
        log.debug("Markdown 分块完成: 原文 {} 字符 -> {} 节 -> {} 块 (chunkSize={}, overlap={})",
                text.length(), sections.size(), result.size(), chunkSize, chunkOverlap);
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

    /** 逐行扫描, 把全文切成"标题路径 + 正文"的连续小节(无标题的前言也算一节) */
    private static List<Section> parseSections(String text) {
        String[] lines = text.split("\\R", -1);
        List<Section> sections = new ArrayList<>();
        Section current = new Section();
        boolean inFence = false;
        boolean hasHeading = false;

        for (String line : lines) {
            Matcher fence = FENCE.matcher(line);
            boolean fenceLine = fence.find();
            Matcher heading = inFence ? null : HEADING.matcher(line);
            if (!fenceLine && heading != null && heading.matches()) {
                int level = heading.group(1).length();
                // 先在当前标题路径上算出新路径(弹出同级/更深标题), 再开新节 ——
                // 顺序反了会把父标题丢掉, 子节片段就失去"它属于哪一章"的信息
                List<Heading> path = new ArrayList<>(current.headings);
                while (!path.isEmpty() && path.get(path.size() - 1).level >= level) {
                    path.remove(path.size() - 1);
                }
                path.add(new Heading(level, line));

                if (!current.isEmpty()) sections.add(current);
                hasHeading = true;
                current = new Section();
                current.headings.addAll(path);
            } else {
                current.body.append(line).append('\n');
            }
            if (fenceLine) inFence = !inFence;
        }
        if (!current.isEmpty()) sections.add(current);
        return hasHeading ? sections : List.of();
    }

    /** 标题路径按层级逐行拼接, 作为每块的前缀(保留原文标题, 不重写) */
    private static String buildPrefix(List<Heading> headings) {
        StringBuilder sb = new StringBuilder();
        for (Heading h : headings) {
            sb.append(h.line).append('\n');
        }
        return sb.toString();
    }

    private static final class Heading {
        final int level;
        final String line;

        Heading(int level, String line) {
            this.level = level;
            this.line = line;
        }
    }

    private static final class Section {
        final List<Heading> headings = new ArrayList<>();
        final StringBuilder body = new StringBuilder();

        boolean isEmpty() {
            return headings.isEmpty() && body.length() == 0;
        }
    }
}
