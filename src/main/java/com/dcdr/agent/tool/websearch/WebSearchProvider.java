package com.dcdr.agent.tool.websearch;

/**
 * 联网搜索策略(Strategy)。
 *
 * <p><b>存在两个实现</b>: {@code searxng}(自托管元搜索, 免费但依赖上游引擎质量) 与
 * {@code deepseek}(自研索引, 按次计费但结果已为 LLM 清洗过)。切换点只有配置
 * {@code agent-tools.web.provider} 一处, 由 {@link WebProviderFactory} 在<b>启动时</b>解析。
 *
 * <p><b>实现约定</b>:
 * <ul>
 *   <li>返回的 {@code sources} 必须按顺序带 {@code index}(从 1 开始)、{@code title}、
 *       {@code url}、{@code snippet} —— 门面依赖它拼 {@code [[n]]} 引用标记; </li>
 *   <li>可选带 {@code summary}(更长的、给模型读的摘要)与 {@code content}(正文);
 *       门面在拼提示词时优先用 {@code summary}, 缺失才回落 {@code snippet}; </li>
 *   <li><b>不要吞掉网络异常</b>: 抛出来, 由门面统一转成 {@code WebStat.Empty.ERROR}。
 *       两个源都不该各自决定"失败时给模型看什么文案"。</li>
 * </ul>
 */
public interface WebSearchProvider {

    /** 策略标识, 与配置值一致(如 {@code searxng} / {@code deepseek}); 工厂用它做注册表 key */
    String id();

    /**
     * 本策略当前是否可用。
     * <p>默认可用; 需要外部凭据的实现(DeepSeek 需要 API Key)覆写它。
     * 工厂据此在<b>启动时</b>决定降级或报错, 而不是等到第一次搜索才发现 401。
     */
    default boolean available() {
        return true;
    }

    /**
     * 执行一次检索。
     *
     * @param query           检索词
     * @param originalQuestion 用户原始问题(供支持意图理解的源使用, 可为 null)
     * @param limit           期望条数(已由门面钳制过上限)
     * @return 统一结果; 上游返回空数组时返回空 {@code sources} 而非 null
     * @throws Exception 网络/协议错误, 由门面统一处理
     */
    SearchOutcome search(String query, String originalQuestion, int limit) throws Exception;

    /**
     * 是否支持用页码继续取同一 query 的后续结果。
     * <p>DeepSeek Search 没有 page/offset 参数，只能通过 count 调整条数；SearXNG 支持 pageno。
     * 门面据此决定是否向模型暴露 {@code page}，不能给不支持的源发一个会被静默忽略的参数。
     */
    default boolean supportsPagination() {
        return false;
    }

    /**
     * 分页检索入口。不支持分页的源只需实现三参数版本；页码合法性由门面统一校验。
     */
    default SearchOutcome search(String query, String originalQuestion, int limit, int page) throws Exception {
        if (page <= 1) {
            return search(query, originalQuestion, limit);
        }
        throw new UnsupportedOperationException(id() + " 不支持分页搜索");
    }
}
