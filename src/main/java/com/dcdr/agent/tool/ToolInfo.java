package com.dcdr.agent.tool;

import java.util.List;
import java.util.Map;

/**
 * 工具执行的结构化结果。
 * <p>相比纯文本, 可携带扩展信息(如联网搜索返回的引用来源 sources), 供编排层向下游与前端透传。</p>
 */
public class ToolInfo {

    /** 返回给模型的纯文本结果 */
    private final String content;

    /** 扩展信息: 引用来源(如联网搜索的 title/url/content) */
    private final List<Map<String, Object>> sources;

    public ToolInfo(String content) {
        this(content, null);
    }

    public ToolInfo(String content, List<Map<String, Object>> sources) {
        this.content = content;
        this.sources = sources;
    }

    /** 便捷构造纯文本结果 */
    public static ToolInfo text(String content) {
        return new ToolInfo(content, null);
    }

    public String getContent() {
        return content;
    }

    public List<Map<String, Object>> getSources() {
        return sources;
    }
}
