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

    /**
     * 扩展信息: 执行质量统计。
     * <p>目前只有联网工具填充 —— 这类工具的效果<b>无法从返回文本里读出来</b>
     * ("搜索到 0 条"和"搜索到 8 条但全是摘要"在模型眼里一样可用), 必须由工具自己上报,
     * 编排层才能写进调用链供库侧聚合。其他工具保持 {@code null}。
     */
    private final WebStat webStat;

    public ToolInfo(String content) {
        this(content, null, null);
    }

    public ToolInfo(String content, List<Map<String, Object>> sources) {
        this(content, sources, null);
    }

    public ToolInfo(String content, List<Map<String, Object>> sources, WebStat webStat) {
        this.content = content;
        this.sources = sources;
        this.webStat = webStat;
    }

    /** 便捷构造纯文本结果 */
    public static ToolInfo text(String content) {
        return new ToolInfo(content, null, null);
    }

    /**
     * 附加执行统计(不可变: 返回新实例)。
     * <p>供工具在失败分支上使用 —— 那些分支只返回一句提示文本, 没有 sources,
     * 但"为什么失败"恰恰是最该被统计的部分。
     */
    public ToolInfo withStat(WebStat stat) {
        return new ToolInfo(content, sources, stat);
    }

    public String getContent() {
        return content;
    }

    public List<Map<String, Object>> getSources() {
        return sources;
    }

    /** 执行质量统计; 非联网工具为 {@code null} */
    public WebStat getWebStat() {
        return webStat;
    }
}
