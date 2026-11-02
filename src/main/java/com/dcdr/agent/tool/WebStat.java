package com.dcdr.agent.tool;

/**
 * 一次联网搜索 / 网页抓取的<b>执行质量统计</b>(V3.3)。
 *
 * <p><b>为什么需要它</b>: 联网工具此前只留下 {@code recordTool} 那一条通用记录(工具名 + 耗时 +
 * 结果前 500 字), 于是"最近效果是不是变差了"这类问题<b>一个数字都答不出来</b> ——
 * 只能靠翻日志里零散的 {@code log.warn}。而 RAG 侧早有 {@code recordRag} + 检索健康度 SQL
 * 的成熟做法, 联网侧一直缺对应的那一半。
 *
 * <p><b>为什么 {@code rawCount} 与 {@code results} 必须分开记</b>:
 * "返回 0 条"有两种截然不同的成因, 混在一起就永远查不出是谁的问题 ——
 * <ul>
 *   <li>{@code rawCount=0} —— 上游搜索引擎没返回任何结果(引擎被限流、查询词过窄、
 *       服务不可用)。这是<b>外部依赖</b>故障;</li>
 *   <li>{@code rawCount=20, results=0} —— 上游明明返回了 20 条, 全被
 *       {@link com.dcdr.agent.tool.websearch.SearchResultCleaner} 过滤掉了。
 *       这是<b>我们自己的清洗规则</b>过严 —— 加一条黑名单就可能把好结果全干掉,
 *       而调用方的表现完全一样("联网搜索未返回可用结果"), 只看结果条数无法区分。</li>
 * </ul>
 *
 * <p><b>为什么需要 {@code thinPages}</b>: 抓到 200 字以内的正文工具仍判为成功(不计入失败),
 * 但它通常是"请先登录""访问过于频繁""页面不存在"这类拦截页。若不单独计数,
 * 抓取成功率会虚高, 而模型拿到的其实是一句废话。
 *
 * <p><b>为什么 {@code rawCount} 允许 -1</b>: {@code web_extract} 是直接抓 URL, 不存在
 * "上游返回几条"的概念。用 -1 而非 0 表示"不适用", 避免库侧把它统计成"一次零结果"。
 *
 * @param tool        工具名({@code web_search} / {@code web_extract})
 * @param provider    实际执行该次调用的源 id(成本核算按源定价, 见字段注释)
 * @param query       搜索词; 抓取时为请求的 URL(截断到 200 字, 只为排查时认得出是哪一次)
 * @param rawCount    上游原始返回条数; {@code -1} 表示该工具不适用
 * @param results     最终进入 sources 的条数(清洗 / 抓取之后)
 * @param extractOk   串联抓取成功篇数; 未触发抓取为 0
 * @param extractFailed 串联抓取失败篇数; 未触发抓取为 0
 * @param contentChars 成功抓到的正文总字符数(未抓取为 0)
 * @param thinPages   正文过短(疑似登录页 / 拦截页)的篇数
 * @param emptyReason 本次"没拿到东西"的成因; 正常拿到结果时为 {@code null}
 */
public record WebStat(
        String tool,
        /**
         * 实际执行这次调用的源 id: {@code deepseek} / {@code searxng} / {@code local} …
         * <p><b>为什么必须单独记</b>: 成本核算按"调用次数 × 该源单价"算, 而 deepseek 按次计费、
         * 自建 searxng 零单价。只记"调了 1000 次 web_search"无法回答"这个月花了多少钱" ——
         * 从配置反推也不行: 用户中途换了源, 历史数据就全错了。
         */
        String provider,
        String query,
        int rawCount,
        int results,
        int extractOk,
        int extractFailed,
        int contentChars,
        int thinPages,
        Empty emptyReason) {

    /** 正文短于该长度即视为"疑似拦截页 / 空壳页" —— 正常文章正文远不止 200 字 */
    public static final int THIN_CONTENT_CHARS = 200;

    /** 单次统计里 query 的最大长度: 抓取时传的是 URL, 不截断会让 steps_json 明显膨胀 */
    private static final int QUERY_MAX = 200;

    /**
     * "这次没拿到东西"的成因。
     * <p>取值与中文名都定义在这里, 前端与库侧 SQL 都消费同一份 —— 避免各自维护映射表,
     * 改一边漏一边不会有任何报错, 只会在界面上留下一个没人认得的英文单词。
     */
    public enum Empty {

        /** 上游搜索引擎返回 0 条(限流 / 查询词过窄 / 服务异常) —— 外部依赖问题 */
        NO_RESULTS("no_results", "搜索引擎无返回"),

        /** 上游有返回, 但全部被清洗规则过滤掉 —— 我们自己的规则过严 */
        ALL_FILTERED("all_filtered", "结果全被过滤"),

        /** 联网搜索未启用(配置开关关闭) */
        DISABLED("disabled", "联网未启用"),

        /** 请求异常(超时 / 连不上 / 响应格式错误) */
        ERROR("error", "请求失败");

        private final String value;
        private final String label;

        Empty(String value, String label) {
            this.value = value;
            this.label = label;
        }

        public String value() {
            return value;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 搜索阶段的统计(抓取数据尚未产生)。
     *
     * @param rawCount 上游原始返回条数(清洗前)
     * @param results  清洗后保留条数
     */
    public static WebStat search(String provider, String query, int rawCount, int results) {
        return new WebStat("web_search", provider, clipQuery(query), rawCount, results,
                0, 0, 0, 0, results == 0 ? emptyOf(rawCount) : null);
    }

    /** 搜索阶段就失败(未启用 / 请求异常)时的统计 */
    public static WebStat searchFailed(String provider, String query, Empty reason) {
        return unavailable("web_search", provider, query, reason);
    }

    /**
     * 工具<b>没有真正执行</b>就结束时的统计(未启用 / 请求异常)。
     * <p>这类情况返回的是一句提示文本, 既无 sources 也无损耗数据 —— 若不单独上报,
     * 库侧只能看到"这条调用没有 web 步骤", 与"根本没调用工具"无法区分。
     */
    public static WebStat unavailable(String tool, String provider, String query, Empty reason) {
        return new WebStat(tool, provider, clipQuery(query), -1, 0, 0, 0, 0, 0, reason);
    }

    /** 抓取阶段的统计(模型直接调用 {@code web_extract} 时使用) */
    public static WebStat extract(String provider, String url, int ok, int failed,
                                  int contentChars, int thinPages) {
        return new WebStat("web_extract", provider, clipQuery(url), -1, ok + failed,
                ok, failed, contentChars, thinPages, ok == 0 ? Empty.NO_RESULTS : null);
    }

    /**
     * 补上串联抓取的数据(搜索成功后由编排层回填)。
     * <p>搜索工具本身不发起抓取 —— 抓取发生在 {@code AgentOrchestrator#enrichWithExtract},
     * 所以这部分统计只能事后合并, 不能由工具自己填。
     *
     * @param results      补全正文后进入 sources 的条数(与搜索阶段一致, 保持调用方传值)
     * @param contentChars 成功抓取的正文总字符数
     */
    public WebStat withExtract(int results, int ok, int failed, int contentChars, int thinPages) {
        return new WebStat(tool, provider, query, rawCount, results, ok, failed, contentChars, thinPages, emptyReason);
    }

    /** 由原始条数推断空结果成因: 上游一条都没给 vs 全被清洗掉 */
    private static Empty emptyOf(int rawCount) {
        return rawCount <= 0 ? Empty.NO_RESULTS : Empty.ALL_FILTERED;
    }

    private static String clipQuery(String query) {
        if (query == null) {
            return "";
        }
        String q = query.trim();
        return q.length() <= QUERY_MAX ? q : q.substring(0, QUERY_MAX) + "…";
    }
}
