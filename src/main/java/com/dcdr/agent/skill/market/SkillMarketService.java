package com.dcdr.agent.skill.market;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.dto.SkillDtos;
import com.dcdr.agent.dto.SkillMarketDtos;
import com.dcdr.agent.entity.SkillDefinitionEntity;
import com.dcdr.agent.repository.SkillRepository;
import com.dcdr.agent.skill.SkillPackageService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能市场服务 (V3.1) —— 把 SkillHub 的发现层接进来。
 * <p>
 * <h3>接入边界(重要, 决定了本类的所有取舍)</h3>
 * SkillHub 的公开 HTTP 接口只提供<b>发现层</b>: 分类 / 列表 / 搜索 / 详情 / 版本 + 第三方安全扫描结论。
 * 技能包<b>正文</b>不在这条链路上 —— 客户端下载走的是私有通道
 * ({@code backend:skillhub-download-url} / {@code backend:skillhub-install}),
 * 实测 {@code /api/v1/skills/{slug}/download} 对 GET/POST 都返回 405,
 * 详情与版本响应里也不含任何包地址。
 * <p>
 * 所以本类的"引入"<b>刻意不是"一键装好"</b>, 而是:
 * <ol>
 *   <li>把上游元数据(名称/简介/版本/作者/分类/标签)完整带过来, 省掉手工抄写;</li>
 *   <li>把第三方安全扫描结论作为<b>告警</b>呈现 —— 这是本项目自己造不出来的信号;</li>
 *   <li>正文写入<b>明确标注"待补全"的骨架</b>, 并<b>自动停用</b>。</li>
 * </ol>
 * 自动停用是关键: 没有正文的技能等于没有能力, 若启用进入技能目录, 只会白占模型的判断预算。
 * 用户补完正文、手工启用后它才真正生效 —— 这条链路上没有任何一步是"假装完成了"。
 * <p>
 * <h3>缓存</h3>
 * 只缓存上游的原始 {@link JsonNode}(分类/详情/版本), <b>不缓存映射后的视图</b> ——
 * 视图带 {@code installed} 这类"因人而异"的字段, 缓存它会把 A 用户的已装状态泄露给 B。
 * 手写 TTL map 而非引入 Caffeine: 与项目现有做法一致(见 {@code ToolRegistry} /
 * {@code AccessTokenBlacklist})。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillMarketService {

    /** 分类 key 的长度上限(防上游塞超长值进本地 categoryId 列) */
    private static final int MAX_CATEGORY_LEN = 64;
    /** 缓存条目上限: 超出直接清空。技能市场是低频功能, 用一个粗暴但确定的上界即可 */
    private static final int MAX_CACHE_ENTRIES = 500;

    /**
     * 「推荐」候选池每一路的条数。
     * <p>
     * 实测上游 pageSize=100 可用、200 直接 400, 故取 50 留安全余量 ——
     * 两路合计最多 100 条候选, 足够撑 8 页(每页 12)的推荐浏览。
     */
    private static final int RECOMMEND_POOL_PER_SOURCE = 50;

    /** 复合评分权重(见 rankRecommended 的公式说明) */
    private static final double W_HOT = 0.45;
    private static final double W_STARS = 0.25;
    private static final double W_INSTALLS = 0.15;
    private static final double W_FRESH = 0.15;
    /** 新鲜度半衰期(天): 距今 90 天得 0.5 分 */
    private static final double FRESH_HALF_LIFE_DAYS = 90.0;
    /**
     * 缺更新时间时的中性新鲜度分。
     * <p>
     * 不给 0 的原因: "上游没给这个字段"不等于"这个技能很旧" —— 用 0 会把字段缺失的技能
     * 无理由地压到后面, 而那可能只是映射问题。
     */
    private static final double FRESH_UNKNOWN = 0.5;

    private final SkillHubProperties props;
    private final SkillHubClient client;
    private final SkillRepository skillRepository;
    private final SkillPackageService packageService;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(JsonNode value, long expiresAt) {
    }

    public boolean isEnabled() {
        return props.isEnabled();
    }

    // ===== 发现层 =====

    /** 一级分类(几乎不变, 缓存收益最高) */
    public List<SkillMarketDtos.MarketCategory> categories() {
        requireEnabled();
        JsonNode root = cached("categories", client::categories);
        JsonNode items = root.path("items");
        List<SkillMarketDtos.MarketCategory> out = new ArrayList<>();
        if (!items.isArray()) return out;
        for (JsonNode it : items) {
            if (!bool(it, "active", "enabled")) continue;
            SkillMarketDtos.MarketCategory c = new SkillMarketDtos.MarketCategory();
            c.setKey(text(it, "key"));
            c.setName(text(it, "name"));
            c.setNameEn(text(it, "nameEn"));
            c.setSortOrder((int) num(it, "sortOrder"));
            if (c.getKey() != null) out.add(c);
        }
        out.sort((a, b) -> Integer.compare(a.getSortOrder(), b.getSortOrder()));
        return out;
    }

    /** 列表(带 total, 支持关键词/分类筛选与排序) */
    public SkillMarketDtos.MarketPage list(int page, int pageSize, String keyword, String category,
                                           String sort, String userId) {
        requireEnabled();
        int p = Math.max(0, page);
        int size = clampPageSize(pageSize);
        // 排序必须经枚举映射: 上游 sortBy 是严格白名单, 未知值直接 400(见 SkillMarketSort)
        SkillMarketSort resolved = SkillMarketSort.fromKey(sort);
        String kw = blankToNull(keyword);
        String cat = blankToNull(category);

        if (resolved.isLocallyRanked()) {
            return recommendedPage(p, size, kw, cat, userId);
        }

        JsonNode root = client.list(p, size, kw, cat, resolved.getUpstreamField(), resolved.getOrder());
        JsonNode data = root.path("data");

        SkillMarketDtos.MarketPage result = new SkillMarketDtos.MarketPage();
        result.setPage(p);
        result.setPageSize(size);
        result.setTotal(data.path("total").asLong(0));
        // 回显实际生效的排序: 未知值会静默回落推荐, 不回显的话"我传的参数没生效"就查不出来
        result.setSort(resolved.getKey());
        result.setItems(parseItems(data.path("skills"), userId));
        return result;
    }

    /**
     * 「推荐」分页: 取候选池 → 本地评分重排 → 在池内分页。
     * <p>
     * <b>候选池为什么是两路合成的</b>: 只按下载量取池, "推荐"就退化成"热门榜换个顺序" ——
     * 长尾技能与刚发布但上升期的技能永远进不来。所以池子 = <b>下载量前 N</b> ∪ <b>最近更新前 N</b>,
     * 合并去重。两路的上游参数都取自枚举, 不引入字面量。
     * <p>
     * <b>为什么 total 是池子大小而不是全库总数</b>: 评分只在池内进行, 所以"第 3 页"只可能表示
     * "池内第 25~36 名"。若把 total 报成全库那 19 万+, 用户会以为能翻到第 1 万页 —— 那是假的。
     * 返回体用 {@code recommended=true} 明确区分这个语义, 前端据此换文案。
     */
    private SkillMarketDtos.MarketPage recommendedPage(int p, int size, String keyword,
                                                       String category, String userId) {
        Map<String, SkillMarketDtos.MarketSkillView> pool = new LinkedHashMap<>();
        collectInto(pool, client.list(0, RECOMMEND_POOL_PER_SOURCE, keyword, category,
                SkillMarketSort.RECOMMENDED.getUpstreamField(), SkillMarketSort.RECOMMENDED.getOrder()), userId);
        collectInto(pool, client.list(0, RECOMMEND_POOL_PER_SOURCE, keyword, category,
                SkillMarketSort.RECENT.getUpstreamField(), SkillMarketSort.RECENT.getOrder()), userId);

        List<SkillMarketDtos.MarketSkillView> ranked = rankRecommended(new ArrayList<>(pool.values()));
        int from = Math.min(p * size, ranked.size());
        int to = Math.min(from + size, ranked.size());

        SkillMarketDtos.MarketPage result = new SkillMarketDtos.MarketPage();
        result.setPage(p);
        result.setPageSize(size);
        result.setSort(SkillMarketSort.RECOMMENDED.getKey());
        result.setRecommended(true);
        result.setItems(new ArrayList<>(ranked.subList(from, to)));
        result.setTotal(ranked.size());
        return result;
    }

    /** 把一路候选池并入总池; 两路会有交集, 按规范化名去重 */
    private void collectInto(Map<String, SkillMarketDtos.MarketSkillView> pool, JsonNode root, String userId) {
        for (SkillMarketDtos.MarketSkillView v : parseItems(root.path("data").path("skills"), userId)) {
            pool.putIfAbsent(nz(v.getCanonicalName(), v.getSlug()), v);
        }
    }

    /** 上游 {@code {data:{skills:[...]}}} → 视图列表 */
    private List<SkillMarketDtos.MarketSkillView> parseItems(JsonNode array, String userId) {
        List<SkillMarketDtos.MarketSkillView> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode s : array) out.add(toView(s, null, userId));
        }
        return out;
    }

    /**
     * 「推荐」排序: 本地复合评分。
     * <p>
     * 公式(各项先做 min-max 归一, 再加权求和):
     * <pre>
     *   score = 0.45 · norm(ln(1 + downloads))
     *         + 0.25 · norm(ln(1 + stars))
     *         + 0.15 · norm(ln(1 + installs))
     *         + 0.15 · freshness            // 1 / (1 + 距今天数 / 90)
     * </pre>
     * 三个刻意的选择:
     * <ul>
     *   <li><b>先取对数再归一</b>: 不取对数的话, 一个 294 万下载的头部技能会把其余全部压到 0,
     *       推荐就只剩"下载榜前几名"。</li>
     *   <li><b>保留新鲜度权重</b>: 纯热度排序会让几年前的老技能一直占着前排;
     *       90 天半衰给"还在维护"的技能合理提升, 这是"推荐"区别于"最热"的关键。</li>
     *   <li><b>稳定排序</b>: 分数相同时保持池内原顺序(下载量降序), 不会每次刷新都跳来跳去。</li>
     * </ul>
     */
    private List<SkillMarketDtos.MarketSkillView> rankRecommended(List<SkillMarketDtos.MarketSkillView> pool) {
        if (pool.size() < 2) return pool;

        double maxHot = 0, maxStars = 0, maxInstalls = 0;
        for (SkillMarketDtos.MarketSkillView v : pool) {
            maxHot = Math.max(maxHot, log1p(v.getDownloads()));
            maxStars = Math.max(maxStars, log1p(v.getStars()));
            maxInstalls = Math.max(maxInstalls, log1p(v.getInstalls()));
        }
        long now = System.currentTimeMillis();

        record Scored(SkillMarketDtos.MarketSkillView view, double score) { }
        List<Scored> scored = new ArrayList<>(pool.size());
        for (SkillMarketDtos.MarketSkillView v : pool) {
            double s = W_HOT * ratio(log1p(v.getDownloads()), maxHot)
                    + W_STARS * ratio(log1p(v.getStars()), maxStars)
                    + W_INSTALLS * ratio(log1p(v.getInstalls()), maxInstalls)
                    + W_FRESH * freshness(v.getUpdatedAt(), now);
            scored.add(new Scored(v, s));
        }
        // reversed() 用 lambda 而非方法引用: 局部 record 的访问器在这种泛型推断下更容易出歧义
        scored.sort(Comparator.comparingDouble((Scored s) -> s.score()).reversed());

        List<SkillMarketDtos.MarketSkillView> out = new ArrayList<>(scored.size());
        for (Scored s : scored) out.add(s.view());
        return out;
    }

    private static double log1p(long v) {
        return Math.log1p(Math.max(0L, v));
    }

    /** min-max 归一; 全池该项都为 0 时返回 0(避免除零), 即"这项大家都没有, 不加分也不减分" */
    private static double ratio(double value, double max) {
        return max <= 0 ? 0 : value / max;
    }

    private static double freshness(LocalDateTime updatedAt, long nowMillis) {
        if (updatedAt == null) return FRESH_UNKNOWN;
        long updatedMillis = updatedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        double days = Math.max(0, (nowMillis - updatedMillis) / 86_400_000.0);
        return 1.0 / (1.0 + days / FRESH_HALF_LIFE_DAYS);
    }

    /**
     * 可选排序方式 —— 经接口下发给前端, 而不是前端另写一份。
     * <p>
     * 不查上游、不受 enabled 开关限制(纯静态枚举): 前端要在页面挂载时就能渲染筛选器,
     * 不该为了 5 个固定选项等一次外部请求。
     */
    public List<SkillMarketDtos.SortOption> sorts() {
        List<SkillMarketDtos.SortOption> out = new ArrayList<>();
        for (SkillMarketSort s : SkillMarketSort.values()) {
            SkillMarketDtos.SortOption o = new SkillMarketDtos.SortOption();
            o.setKey(s.getKey());
            o.setLabel(s.getLabel());
            out.add(o);
        }
        return out;
    }

    /** 关键词搜索(上游不返回总数; 结果字段与列表略有差异, 由容错映射吸收) */
    public List<SkillMarketDtos.MarketSkillView> search(String query, int limit, String userId) {
        requireEnabled();
        if (query == null || query.isBlank()) {
            throw new BusinessException(400, "搜索关键词不能为空", "SKILL_HUB_BAD_REQUEST");
        }
        JsonNode root = client.search(query.trim(), clampPageSize(limit));
        JsonNode results = root.path("results");
        List<SkillMarketDtos.MarketSkillView> out = new ArrayList<>();
        if (results.isArray()) {
            for (JsonNode s : results) out.add(toView(s, null, userId));
        }
        return out;
    }

    /** 详情(含版本历史与安全扫描结论) */
    public SkillMarketDtos.MarketSkillDetail detail(String slug, String namespace, String userId) {
        requireEnabled();
        String s = requireSlug(slug);
        String ns = blankToNull(namespace);

        JsonNode root = cached("detail:" + nz(ns) + ":" + s, () -> client.detail(s, ns));
        // 详情把技能字段包在 skill 下, 同时根上还有 slug/securityReports/latestVersion
        JsonNode skillNode = root.path("skill").isObject() ? root.path("skill") : root;
        SkillMarketDtos.MarketSkillView base = toView(skillNode, root, userId);

        SkillMarketDtos.MarketSkillDetail d = new SkillMarketDtos.MarketSkillDetail();
        copyInto(base, d);
        d.setChangelog(text(root.path("latestVersion"), "changelog"));

        JsonNode versionsNode = cached("versions:" + nz(ns) + ":" + s, () -> client.versions(s, ns));
        JsonNode arr = versionsNode.path("versions");
        List<SkillMarketDtos.VersionView> versions = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode v : arr) {
                SkillMarketDtos.VersionView vv = new SkillMarketDtos.VersionView();
                vv.setVersion(text(v, "version"));
                vv.setCreatedAt(epochToDateTime(num(v, "createdAt")));
                vv.setChangelog(text(v, "changelog"));
                List<SkillMarketDtos.SecurityReport> reports = securityReports(v);
                vv.setSecurityStatus(pickStatus(reports));
                vv.setSecurityStatusText(pickStatusText(reports));
                versions.add(vv);
            }
        }
        d.setVersions(versions);
        d.setVersionCount(versions.size());
        return d;
    }

    // ===== 引入 =====

    /**
     * 把市场技能引入为本地技能 —— 产出"元数据骨架 + 待补全正文", 自动停用。
     * <p>
     * 复用 {@link SkillPackageService#importPackage}: 不另开一条入库路径, 否则
     * "手工创建"与"市场引入"的校验规则必然漂移(名称格式/大小上限/资源白名单/配额)。
     *
     * @param overwrite 覆盖同名技能。注意: 覆盖会用骨架正文<b>覆盖掉你已补全的正文</b>,
     *                  故前端必须二次确认 —— 这是这个接口最容易造成不可逆损失的地方。
     */
    // 刻意不加 @Transactional: 方法前半段要发 2 次上游 HTTP(详情 + 版本), 把网络 IO 包进写事务
    // 会长时间占住数据库连接。真正需要原子的入库部分在 SkillPackageService 里自成事务。
    public SkillMarketDtos.InstallResult install(SkillMarketDtos.InstallRequest req, String userId) {
        requireEnabled();
        if (req == null) throw new BusinessException(400, "缺少请求参数", "SKILL_HUB_BAD_REQUEST");
        String slug = requireSlug(req.getSlug());

        List<String> warnings = new ArrayList<>();
        SkillMarketDtos.MarketSkillDetail d = detail(slug, req.getNamespace(), userId);

        // 1) 标识归一化: 上游 slug 不保证满足本项目的 kebab-case 规则(3~64 位, 仅小写/数字/连字符)
        String rawName = blankToNull(req.getName()) != null ? req.getName().trim() : slug;
        String name = sanitizeName(rawName);
        if (!name.equalsIgnoreCase(rawName)) {
            warnings.add("技能标识已归一化为 " + name + " (来源标识 \"" + rawName
                    + "\" 不符合本项目的标识规则: 小写字母/数字/连字符, 3~64 位)");
        }

        // 2) 安全结论: 非 benign 一律告警。这是上游的第三方扫描结果, 只作参考
        if (d.getSecurityStatus() != null && !"benign".equalsIgnoreCase(d.getSecurityStatus())) {
            warnings.add("来源站安全扫描结论为「" + nz(d.getSecurityStatusText(), d.getSecurityStatus())
                    + "」, 建议确认后再补全并启用");
        } else if (d.getSecurityStatus() == null) {
            warnings.add("来源站暂无安全扫描结论(可能仍在排队), 请自行确认技能内容");
        }
        if (d.isRequiresApiKey()) {
            warnings.add("该技能声明需要 API Key: 启用前请确认对应凭据已配置");
        }

        // 3) 构造技能包(正文为骨架)
        SkillDtos.SkillPackage pkg = new SkillDtos.SkillPackage();
        pkg.setName(name);
        pkg.setDisplayName(nz(d.getName(), name));
        // 图标: 只带 URL, 不下载图片字节(与"技能表是纯文本"的边界一致)。
        // 上游部分技能没有图标, 此时为空 —— 界面用首字母占位, 不产生告警
        pkg.setIconUrl(d.getIconUrl());
        // 来源页: "正文待补全"时用户要去这里取正文, 所以必须单独存下来 ——
        // 只有写在占位正文里的话, 用户得先打开技能读到那行才发现有这个地址
        pkg.setSourceUrl(d.getSourceUrl());
        // 来源标识: 同来源页一起单独存成字段。占位正文里虽然也写了,
        // 但用户补全正文时会整段替换掉 —— 血缘必须独立于正文才能留下来
        pkg.setSourceRef(buildSourceRef(d));
        pkg.setDescription(nz(d.getSummary(), "来自技能市场: " + name));
        pkg.setVersion(d.getVersion());
        pkg.setAuthor(buildAuthor(d));
        pkg.setCategoryId(truncate(d.getCategory(), MAX_CATEGORY_LEN));
        pkg.setTags(mergeTags(d));
        pkg.setInstructions(buildPlaceholder(d, name));
        pkg.setResources(Map.of());
        pkg.setRequiredTools(List.of());
        // 刻意不设 hasExecutable: 上游没有这个字段, 我们**无从判断**某个技能是否真的需要执行能力。
        // 早先这里是"一律置 true"(想提醒能力可能不完整), 但那让标记退化成"来自市场"的同义词 ——
        // 满屏都有的标签等于没有标签, 反而削弱了它真正该起作用时的提示价值。
        // "能力可能不完整"这件事, 已由告警表达得更准: 正文标为待补全 + 未导入任何可执行内容。

        SkillDtos.ImportRequest importReq = new SkillDtos.ImportRequest();
        importReq.setSkill(pkg);
        importReq.setOverwrite(req.getOverwrite());
        // 4) enabled=false: 骨架正文不可用, 以停用状态入库(与导入同一事务, 见 SkillPackageService)
        SkillDtos.ImportResult imported = packageService.importPackage(importReq, userId, false);
        if (imported.getWarnings() != null) warnings.addAll(imported.getWarnings());

        SkillMarketDtos.InstallResult result = new SkillMarketDtos.InstallResult();
        result.setId(imported.getId());
        result.setName(imported.getName());
        result.setAction(imported.getAction());
        result.setEnabled(false);
        result.setInstructionsPending(true);
        result.setSourceUrl(d.getSourceUrl());
        result.setWarnings(warnings);
        log.info("技能市场引入: {} ({}) action={}, user={}, 告警 {} 条",
                name, d.getCanonicalName(), imported.getAction(), userId, warnings.size());
        return result;
    }

    /**
     * 来源标识: {@code skillhub:<canonicalName>}, 如 {@code skillhub:@clawhub_shawnpana/browser-use}。
     * <p>
     * 优先用上游的 {@code canonicalName}: 它带了命名空间, 能区分"同名技能的不同发布者"
     * (实测市场里 {@code dev-expert} 就有 {@code @indiv-ebandao} 与 {@code @user_814dbe54} 两版);
     * 缺失时退回 {@code @handle/slug} 自行拼装; 两者都没有时返回 null ——
     * 宁可没有血缘, 也不写一个编出来的标识。
     */
    private static String buildSourceRef(SkillMarketDtos.MarketSkillDetail d) {
        String canonical = blankToNull(d.getCanonicalName());
        if (canonical != null) return "skillhub:" + canonical;
        String handle = blankToNull(d.getNamespace());
        String slug = blankToNull(d.getSlug());
        return (handle != null && slug != null) ? "skillhub:@" + handle + "/" + slug : null;
    }

    // ===== 映射(容错) =====

    /**
     * 上游技能对象 → 视图。
     * <p>
     * 上游同一含义字段名不统一, 这里全部走别名容错:
     * 图标 {@code iconUrl}/{@code icon_url}; 简介 {@code description_zh}/{@code description}/{@code summary};
     * 发布者 {@code publisher.name}/{@code ownerName}/{@code owner.displayName}。
     * 缺字段一律降级为空 —— 上游改字段名只会让某个展示位空掉, 而不是让整个页面 500。
     */
    private SkillMarketDtos.MarketSkillView toView(JsonNode n, JsonNode securityContext, String userId) {
        SkillMarketDtos.MarketSkillView v = new SkillMarketDtos.MarketSkillView();
        v.setSlug(text(n, "slug"));
        JsonNode nsNode = namespaceNode(n, securityContext);
        String handle = text(nsNode, "handle", "displayName");
        v.setNamespace(handle);
        v.setCanonicalName(text(nsNode, "canonicalName"));
        v.setName(nz(text(n, "displayName", "name"), v.getSlug()));
        v.setSummary(text(n, "description_zh", "summary_zh", "summary", "description"));
        v.setCategory(text(n, "category"));
        v.setSubCategories(subCategoryNames(n.path("subCategories")));
        v.setTags(strList(n.path("tags")));
        v.setVersion(text(n, "version"));
        v.setIconUrl(text(n, "iconUrl", "icon_url"));
        v.setSource(text(n, "source"));
        v.setSourceUrl(resolveSourceUrl(n, nsNode));
        v.setPublisher(publisher(n));
        v.setPublisherVerified(bool(n.path("publisher"), "verified"));
        v.setDownloads(num(n, "downloads"));
        v.setInstalls(num(n, "installs"));
        v.setStars(num(n, "stars"));
        // 上游列表用 updatedAt、搜索用 updated_at, 两个别名都要认(推荐排序的新鲜度依赖它)
        v.setUpdatedAt(epochToDateTime(num(n, "updatedAt", "updated_at")));
        v.setRequiresApiKey("true".equalsIgnoreCase(text(n.path("labels"), "requires_api_key")));

        if (securityContext != null) {
            List<SkillMarketDtos.SecurityReport> reports = securityReports(securityContext);
            v.setSecurityReports(reports);
            v.setSecurityStatus(pickStatus(reports));
            v.setSecurityStatusText(pickStatusText(reports));
        }
        markInstalled(v, userId);
        return v;
    }

    /**
     * 标记"本地已存在"。
     * <p>
     * 用<b>归一化后的名字</b>匹配而不是原始 slug —— 因为 {@link #install} 落库时用的就是
     * 归一化结果, 用 slug 匹配会出现"明明装过却显示未安装"的错觉。
     */
    private void markInstalled(SkillMarketDtos.MarketSkillView v, String userId) {
        if (userId == null || v.getSlug() == null) return;
        String localName = sanitizeName(v.getSlug());
        SkillDefinitionEntity e = skillRepository.findByUserIdAndName(userId, localName).orElse(null);
        if (e != null) {
            v.setInstalled(true);
            v.setInstalledSkillId(e.getId());
        }
    }

    private static List<String> subCategoryNames(JsonNode arr) {
        if (!arr.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode s : arr) {
            String name = text(s, "name", "key");
            if (name != null) out.add(name);
        }
        return out;
    }

    private static List<String> strList(JsonNode arr) {
        // 上游 tags 三种形态都出现过: 数组(搜索)、null(列表)、对象(详情的 {latest:"2.0.1"})
        // 只认数组形态; 其它一律当作"无标签", 避免把版本号误当成标签展示
        if (!arr.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode t : arr) {
            if (t.isTextual() && !t.asText().isBlank()) out.add(t.asText());
        }
        return out;
    }

    /**
     * 解析"来源页"地址 —— 用户要能<b>点开它</b>找到技能正文, 所以这里必须给可访问的地址。
     * <p>
     * 三级取值, 顺序不能换:
     * <ol>
     *   <li><b>上游 GitHub 页</b>({@code upstream_url}, 只有 clawhub 源有): 最接近原始出处, 实测 200;</li>
     *   <li><b>SkillHub 技能页</b>: 用 {@code handle + slug} 自己拼
     *       {@code https://skillhub.cn/skills/{handle}/{slug}} —— 这是<b>覆盖面最大的那一级</b>;</li>
     *   <li>都没有时留空 —— 界面据此不显示链接, 而不是给一个打不开的地址。</li>
     * </ol>
     * <h4>URL 形式是实测出来的, 三个候选里只有第三个可用</h4>
     * <pre>
     *   api.skillhub.cn/{handle}/{slug}         → 405(上游 homepage 给的就是这个, 不是可打开的页面)
     *   skillhub.cn/{handle}/{slug}             → 200 但页面是 SPA 的 "page not found"(少了 /skills/ 段)
     *   skillhub.cn/skills/{handle}/{slug}      → 200 且完整渲染技能正文  ✅
     * </pre>
     * 最后一条是用真实浏览器验证的: 页面会完整渲染 H1 与全部章节(实测见 "Step 0…Step 6" 全文),
     * 也就意味着<b>每个技能都有一份可打开、可复制的正文</b> —— 不需要靠猜、也不需要自己重写。
     * <p>
     * <b>为什么不用上游给的 {@code homepage} 做字符串替换</b>: 它的值就是上面第一个 405 地址,
     * 替换前缀属于"猜另一个域名", 上游换域名时会静默失效; 自己用 {@code handle + slug} 拼更稳,
     * 而且这两个字段我们本来就在用。
     */
    /**
     * 取 namespace 节点 —— <b>不能只看当前节点</b>。
     * <p>
     * 实测: 详情响应把技能字段包在 {@code skill} 下, 但 {@code namespace} <b>只挂在 root 上</b>
     * ({@code skill.namespace} 不存在)。而详情路径是 {@code toView(skillNode, root, userId)},
     * 于是在 skill 节点里找 handle 永远找不到 —— 后果是
     * <b>没有 upstream_url 的那七成技能(社区源/企业源)在详情页拿不到来源页</b>,
     * 而有 upstream_url 的三成(clawhub 源)正常。表现就是"有的有、有的没有",
     * 这类只有一部分数据走到的分支最难发现 —— 列表接口恰好没这个问题(列表项自带 namespace),
     * 所以只在列表上验证会得出"全都正常"的错误结论。
     * <p>
     * 故按 当前节点 → 外层上下文 依次查找; 都拿不到返回 null,
     * 由 {@code text()} 统一走"缺字段降级为空"的老规矩。
     */
    private static JsonNode namespaceNode(JsonNode n, JsonNode fallback) {
        for (JsonNode candidate : new JsonNode[]{n, fallback}) {
            if (candidate == null) continue;
            JsonNode ns = candidate.path("namespace");
            if (ns.isObject()) return ns;
        }
        return null;
    }

    private static String resolveSourceUrl(JsonNode n, JsonNode nsNode) {
        String upstream = text(n, "upstream_url", "upstreamUrl", "sourceUrl");
        if (upstream != null) return upstream;
        String handle = text(nsNode, "handle", "displayName");
        String slug = text(n, "slug");
        if (handle != null && slug != null) {
            return "https://skillhub.cn/skills/" + handle + "/" + slug;
        }
        return null;
    }

    private static String publisher(JsonNode n) {
        String p = text(n.path("publisher"), "name");
        if (p != null) return p;
        p = text(n, "ownerName", "owner_name");
        if (p != null) return p;
        p = text(n.path("owner"), "displayName");
        if (p != null) return p;
        return text(n.path("namespace"), "displayName");
    }

    /** 上游 securityReports 是按平台名索引的对象: {@code {keen:{...}, sanbu:{...}}} */
    private static List<SkillMarketDtos.SecurityReport> securityReports(JsonNode node) {
        JsonNode sr = node.path("securityReports");
        if (!sr.isObject()) return List.of();
        List<SkillMarketDtos.SecurityReport> out = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = sr.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            SkillMarketDtos.SecurityReport r = new SkillMarketDtos.SecurityReport();
            r.setPlatform(e.getKey());
            r.setStatus(text(e.getValue(), "status"));
            r.setStatusText(text(e.getValue(), "statusText"));
            r.setReportUrl(text(e.getValue(), "reportUrl"));
            out.add(r);
        }
        return out;
    }

    /** 取最"值得注意"的那个结论(而不是第一个), 否则 benign 可能盖掉 queued */
    private static String pickStatus(List<SkillMarketDtos.SecurityReport> reports) {
        SkillMarketDtos.SecurityReport worst = reports.stream()
                .max((a, b) -> Integer.compare(severity(a.getStatus()), severity(b.getStatus())))
                .orElse(null);
        return worst == null ? null : worst.getStatus();
    }

    private static String pickStatusText(List<SkillMarketDtos.SecurityReport> reports) {
        SkillMarketDtos.SecurityReport worst = reports.stream()
                .max((a, b) -> Integer.compare(severity(a.getStatus()), severity(b.getStatus())))
                .orElse(null);
        return worst == null ? null : worst.getStatusText();
    }

    /**
     * 结论严重度排序。
     * <p>
     * 注意 {@code default -> 3}: 上游将来若新增"风险/恶意"之类结论, 会被当作<b>最需要提醒</b>的
     * 情况展示出来, 而不是因为不认识就被静默忽略 —— 未知结论默认按"要谨慎"处理。
     */
    private static int severity(String status) {
        if (status == null || status.isBlank()) return 0;
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "benign", "safe", "pass" -> 1;
            case "queued", "pending", "scanning", "processing" -> 2;
            default -> 3;
        };
    }

    // ===== 骨架正文 =====

    /**
     * 生成"待补全"占位正文。
     * <p>
     * 刻意写得醒目的原因: 技能正文会进入模型上下文, 一个看不出是占位的正文
     * (比如只留空字符串或"TODO")会让用户以为装好了。这里至少要让人一眼看出
     * "要做什么、去哪拿正文", 并且把来源信息固化下来 —— 补全时不用再去市场里找。
     */
    private static String buildPlaceholder(SkillMarketDtos.MarketSkillDetail d, String name) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("# ").append(nz(d.getName(), name)).append("\n\n");
        sb.append("> ⚠️ **正文待补全**: 本技能由技能市场引入。市场公开接口只提供元数据, ")
                .append("**不发放技能包正文**, 因此下面的内容是待替换的骨架。\n");
        sb.append("> 请到来源页复制技能正文替换本段说明, 然后在「技能管理」中启用本技能")
                .append("(在此之前它处于停用状态, 不会生效)。\n\n");
        if (d.getSourceUrl() != null) sb.append("> 来源: ").append(d.getSourceUrl()).append("\n\n");

        sb.append("## 何时使用\n\n");
        sb.append(nz(d.getSummary(), "(来源站未提供简介)")).append("\n\n");

        sb.append("## 来源信息\n\n");
        sb.append("- 标识: ").append(nz(d.getCanonicalName(), d.getSlug())).append("\n");
        if (d.getVersion() != null) sb.append("- 版本: ").append(d.getVersion()).append("\n");
        if (d.getCategory() != null) sb.append("- 分类: ").append(d.getCategory()).append("\n");
        if (d.getPublisher() != null) {
            sb.append("- 发布者: ").append(d.getPublisher())
                    .append(d.isPublisherVerified() ? " (已认证)" : "").append("\n");
        }
        if (d.getSecurityStatus() != null) {
            sb.append("- 安全扫描: ").append(nz(d.getSecurityStatusText(), d.getSecurityStatus()))
                    .append(" (第三方平台结论, 仅供参考)\n");
        }
        sb.append("\n## 依赖与边界\n\n");
        sb.append("- 本项目**未从该技能导入任何可执行内容** —— 来源站的脚本一律没有入库。\n");
        sb.append("- 若该技能的工作流需要执行代码, 请通过引用沙箱等已注册工具来提供能力, ")
                .append("而不是把脚本写进这里(技能是纯文本能力包)。\n");
        return sb.toString();
    }

    // ===== 工具方法 =====

    private void requireEnabled() {
        if (!props.isEnabled()) {
            throw new BusinessException(503,
                    "技能市场未启用: 需在配置中开启 agent.skill.hub.enabled", "SKILL_HUB_DISABLED");
        }
    }

    private String requireSlug(String slug) {
        String s = blankToNull(slug);
        if (s == null) throw new BusinessException(400, "缺少技能标识(slug)", "SKILL_HUB_BAD_REQUEST");
        // slug 会拼进 URL path, 只允许安全字符(防路径穿越/查询串注入)
        if (!s.matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")) {
            throw new BusinessException(400, "非法的技能标识: " + slug, "SKILL_HUB_BAD_REQUEST");
        }
        return s;
    }

    private int clampPageSize(int size) {
        if (size <= 0) return 12;
        return Math.min(size, Math.max(1, props.getMaxPageSize()));
    }

    private JsonNode cached(String key, java.util.function.Supplier<JsonNode> loader) {
        long now = System.currentTimeMillis();
        Cached c = cache.get(key);
        if (c != null && c.expiresAt() > now) return c.value();

        JsonNode fresh = loader.get();
        if (cache.size() > MAX_CACHE_ENTRIES) cache.clear();
        cache.put(key, new Cached(fresh, now + props.getCacheTtlSeconds() * 1000L));
        return fresh;
    }

    /**
     * 上游标识 → 本地合法标识。
     * <p>
     * 规则与 {@link SkillService} 的 {@code NAME_PATTERN} 对齐(小写字母/数字/连字符, 3~64 位)。
     * 上游 slug 五花八门(可能含下划线/点/大写/中文/超长/过短), 直接拿来用会在入库那一刻
     * 才报"标识非法", 用户看到的是"这个技能装不了" —— 归一化把它变成"装得上, 但名字变了",
     * 后者显然更有用。
     */
    static String sanitizeName(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        s = s.replaceAll("[^a-z0-9]+", "-");
        s = s.replaceAll("-{2,}", "-").replaceAll("^-+|-+$", "");
        if (s.length() > 64) s = s.substring(0, 64).replaceAll("-+$", "");
        if (s.length() < 3) s = s.isEmpty() ? "mkt-skill" : s + "-skill";
        return s;
    }

    private static List<String> mergeTags(SkillMarketDtos.MarketSkillDetail d) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (d.getTags() != null) set.addAll(d.getTags());
        if (d.getSubCategories() != null) set.addAll(d.getSubCategories());
        // 标签上限: 上游标签质量参差, 收敛到 20 条, 避免把标签墙灌进本地字段
        List<String> all = new ArrayList<>(set);
        return all.subList(0, Math.min(all.size(), 20));
    }

    private static String buildAuthor(SkillMarketDtos.MarketSkillDetail d) {
        String author = d.getPublisher();
        if (author == null) return d.getNamespace();
        if (d.isPublisherVerified()) return author + " (认证)";
        return author;
    }

    private static void copyInto(SkillMarketDtos.MarketSkillView from, SkillMarketDtos.MarketSkillDetail to) {
        to.setSlug(from.getSlug());
        to.setNamespace(from.getNamespace());
        to.setCanonicalName(from.getCanonicalName());
        to.setName(from.getName());
        to.setSummary(from.getSummary());
        to.setCategory(from.getCategory());
        to.setSubCategories(from.getSubCategories());
        to.setTags(from.getTags());
        to.setVersion(from.getVersion());
        to.setIconUrl(from.getIconUrl());
        to.setSource(from.getSource());
        to.setSourceUrl(from.getSourceUrl());
        to.setPublisher(from.getPublisher());
        to.setPublisherVerified(from.isPublisherVerified());
        to.setDownloads(from.getDownloads());
        to.setInstalls(from.getInstalls());
        to.setStars(from.getStars());
        to.setRequiresApiKey(from.isRequiresApiKey());
        to.setSecurityReports(from.getSecurityReports());
        to.setSecurityStatus(from.getSecurityStatus());
        to.setSecurityStatusText(from.getSecurityStatusText());
        to.setInstalled(from.isInstalled());
        to.setInstalledSkillId(from.getInstalledSkillId());
    }

    private static String text(JsonNode n, String... fields) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        for (String f : fields) {
            JsonNode v = n.path(f);
            if (v.isTextual()) {
                String s = v.asText();
                if (!s.isBlank()) return s.trim();
            } else if (v.isNumber()) {
                return v.asText();
            }
        }
        return null;
    }

    private static long num(JsonNode n, String... fields) {
        if (n == null) return 0L;
        for (String f : fields) {
            JsonNode v = n.path(f);
            if (v.isNumber()) return v.asLong();
            if (v.isTextual()) {
                try {
                    return Long.parseLong(v.asText().trim());
                } catch (NumberFormatException ignored) {
                    // 上游偶尔把数字写成字符串, 解析不了就当 0
                }
            }
        }
        return 0L;
    }

    private static boolean bool(JsonNode n, String... fields) {
        if (n == null) return false;
        for (String f : fields) {
            JsonNode v = n.path(f);
            if (v.isBoolean()) return v.asBoolean();
        }
        // 上游 labels.requires_api_key 是字符串 "false" 而不是布尔, 这里统一按"存在且非 false"判真
        return false;
    }

    /** 上游毫秒时间戳 → LocalDateTime; 缺失或非正数返回 null */
    private static LocalDateTime epochToDateTime(long millis) {
        return millis > 0
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
                : null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String nz(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private static String nz(String a) {
        return a == null ? "" : a;
    }
}
