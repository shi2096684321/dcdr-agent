package com.dcdr.agent.skill.market;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.dto.SkillDtos;
import com.dcdr.agent.dto.SkillMarketDtos;
import com.dcdr.agent.repository.SkillRepository;
import com.dcdr.agent.skill.SkillPackageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 技能市场服务单元测试 (V3.1)。
 *
 * <p>为什么这三件事必须有测试 —— 它们都是"平时看不出来、出问题时已经在线上"的分支:
 * <ol>
 *   <li><b>标识归一化</b>: 上游 slug 千奇百怪(下划线/大写/中文/超长/过短), 归一化后的结果
 *       必须满足本项目 {@code SkillService.NAME_PATTERN}; 一旦不满足, 用户看到的是
 *       "这个技能装不上", 而不是一个可诊断的错误。</li>
 *   <li><b>字段容错映射</b>: 上游同一个含义在不同接口里字段名会变
 *       (列表 {@code iconUrl} / 搜索 {@code icon_url}), 且是<b>无契约的私有接口</b>,
 *       映射层是唯一防线。</li>
 *   <li><b>安全结论取值</b>: 多平台结论必须取"最需要提醒"的那个, 否则 benign 会盖掉 queued,
 *       把一个"还没扫完"的技能显示成"安全"。</li>
 * </ol>
 *
 * <p>用 Mockito 伪上游客户端与仓库: 这三条逻辑都不依赖网络与数据库, 为它起 Spring 上下文
 * 会让测试从秒级退化到十几秒(与 {@code SkillServiceValidationTest} 同一取舍)。
 */
class SkillMarketServiceTest {

    /** 与 {@code SkillService.NAME_PATTERN} 完全一致 —— 归一化的唯一验收标准 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static SkillHubProperties enabledProps() {
        SkillHubProperties p = new SkillHubProperties();
        p.setEnabled(true);
        p.setCacheTtlSeconds(0);
        return p;
    }

    /** 构造一个"上游可用、本地无同名技能"的服务实例 */
    private static SkillMarketService service(SkillHubClient client) {
        SkillRepository repo = mock(SkillRepository.class);
        when(repo.findByUserIdAndName(any(), any())).thenReturn(Optional.empty());
        return new SkillMarketService(enabledProps(), client, repo, null);
    }

    /** 上游返回空列表的桩(用于只关心"请求参数是什么"的用例) */
    private static SkillHubClient emptyListStub() {
        SkillHubClient client = mock(SkillHubClient.class);
        when(client.list(anyInt(), anyInt(), any(), any(), any(), any()))
                .thenReturn(json("{\"data\":{\"total\":0,\"skills\":[]}}"));
        return client;
    }

    // ===== 1. 标识归一化 =====

    @Test
    @DisplayName("标识归一化: 任意上游标识都能产出合法标识")
    void sanitizeNameAlwaysProducesValidName() {
        List<String> raw = List.of(
                "browser-use",      // 已合法, 应原样保留
                "A.Stock_Market",   // 大写 + 点 + 下划线
                "browser_use",
                "ab",               // 过短
                "a",
                "",                 // 空
                "---",              // 全是分隔符
                "Excel",
                "技能",              // 纯中文 → 无可用字符
                "-foo-",            // 首尾分隔符
                "café",             // 非 ASCII
                "a--b",             // 连续分隔符
                "  Trim Me  ",
                "9-lives",
                "x".repeat(80)      // 过长
        );
        for (String r : raw) {
            String name = SkillMarketService.sanitizeName(r);
            assertTrue(NAME_PATTERN.matcher(name).matches(),
                    "sanitizeName(\"" + r + "\") = \"" + name + "\" 不满足技能标识规则");
        }
    }

    @Test
    @DisplayName("标识归一化: 合法标识保持原样, 非法标识按预期改写")
    void sanitizeNameMapsKnownCases() {
        assertEquals("browser-use", SkillMarketService.sanitizeName("browser-use"));
        assertEquals("a-stock-market", SkillMarketService.sanitizeName("A.Stock_Market"));
        assertEquals("browser-use", SkillMarketService.sanitizeName("browser_use"));
        assertEquals("excel", SkillMarketService.sanitizeName("Excel"));
        // 过短补后缀而不是截断, 保留原意
        assertEquals("ab-skill", SkillMarketService.sanitizeName("ab"));
        // 无可用字符时给一个确定性的兜底名, 而不是空串
        assertEquals("mkt-skill", SkillMarketService.sanitizeName("技能"));
        assertEquals("mkt-skill", SkillMarketService.sanitizeName("---"));
        assertEquals("a-b", SkillMarketService.sanitizeName("a--b"));
        assertEquals(64, SkillMarketService.sanitizeName("x".repeat(80)).length());
    }

    // ===== 2. 未启用时必须拒绝且不触碰上游 =====

    @Test
    @DisplayName("未启用: 发现层接口直接拒绝(503), 不发起任何外部请求")
    void disabledMarketRejectsWithoutTouchingUpstream() {
        SkillHubClient client = mock(SkillHubClient.class);
        SkillHubProperties props = new SkillHubProperties();
        props.setEnabled(false);
        SkillMarketService svc = new SkillMarketService(props, client, mock(SkillRepository.class), null);

        BusinessException e = assertThrows(BusinessException.class, svc::categories);
        assertEquals(503, e.getCode());
        // 默认关闭是"零外部依赖"的承诺: 连客户端都不该被调用
        verifyNoInteractions(client);
    }

    // ===== 3. 字段容错映射 =====

    @Test
    @DisplayName("列表映射: 吸收上游字段名差异并保留未知字段为空")
    void listMappingToleratesUpstreamFieldVariance() {
        SkillHubClient client = mock(SkillHubClient.class);
        when(client.list(anyInt(), anyInt(), any(), any(), any(), any())).thenReturn(json("""
                {"code":0,"message":"success","data":{"total":193600,"skills":[
                  {"slug":"browser-use","name":"Browser Use","description_zh":"浏览器自动化",
                   "downloads":124895,"stars":165,"version":"2.0.1","category":"ai-agent",
                   "namespace":{"canonicalName":"@ns/browser-use","handle":"ns"},
                   "subCategories":[{"key":"agent-task-automation","name":"任务自动化"}],
                   "labels":{"requires_api_key":"false"},
                   "upstream_url":"https://clawhub.ai/x/browser-use"}
                ]}}
                """));

        // 必须显式给一个"上游排序"才会走普通列表分支 —— sort 为空/未知现在是「推荐」(候选池路径)
        SkillMarketDtos.MarketPage p = service(client).list(0, 12, null, null, "downloads", "u1");

        assertEquals(193600L, p.getTotal());
        assertEquals(1, p.getItems().size());
        SkillMarketDtos.MarketSkillView v = p.getItems().get(0);
        assertEquals("browser-use", v.getSlug());
        // description_zh 优先于英文 description
        assertEquals("浏览器自动化", v.getSummary());
        assertEquals("ns", v.getNamespace());
        assertEquals("@ns/browser-use", v.getCanonicalName());
        assertEquals(List.of("任务自动化"), v.getSubCategories());
        assertEquals("https://clawhub.ai/x/browser-use", v.getSourceUrl());
        assertEquals(124895L, v.getDownloads());
        // 上游用字符串 "false" 表达布尔, 不能因为"有值"就判真
        assertFalse(v.isRequiresApiKey());
        assertFalse(v.isInstalled());
        // 列表接口不返回安全结论: 应为空而不是报错
        assertNull(v.getSecurityStatus());
    }

    @Test
    @DisplayName("排序: 已知值映射为上游参数(对外 key 与上游字段名是两套命名)")
    void knownSortMapsToUpstreamParams() {
        SkillHubClient client = emptyListStub();
        SkillMarketService svc = service(client);

        assertEquals("downloads", svc.list(0, 12, null, null, "downloads", "u1").getSort());
        verify(client).list(anyInt(), anyInt(), any(), any(), eq("downloads"), eq("desc"));

        assertEquals("recent", svc.list(0, 12, null, null, "recent", "u1").getSort());
        verify(client).list(anyInt(), anyInt(), any(), any(), eq("updated_at"), eq("desc"));
    }

    @Test
    @DisplayName("排序: 未知值回落推荐, 上游会 400 的字段名绝不透传")
    void unknownSortNeverReachesUpstream() {
        SkillHubClient client = emptyListStub();
        SkillMarketDtos.MarketPage p = service(client).list(0, 12, null, null, "created_at", "u1");

        assertEquals("recommended", p.getSort());
        assertTrue(p.isRecommended());
        // 推荐的候选池固定走 downloads + updated_at 两路
        verify(client).list(anyInt(), anyInt(), any(), any(), eq("downloads"), eq("desc"));
        verify(client).list(anyInt(), anyInt(), any(), any(), eq("updated_at"), eq("desc"));
        // 关键断言: 用户传的那个上游不认的字段, 一次都没出现在任何请求里
        verify(client, never()).list(anyInt(), anyInt(), any(), any(), eq("created_at"), any());
    }

    @Test
    @DisplayName("推荐: 复合评分不只看下载量; total 是候选池大小(池内分页)")
    void recommendedRanksByCompositeScoreWithinPool() {
        long now = System.currentTimeMillis();
        long fiveYearsAgo = now - 5L * 365 * 24 * 3600 * 1000;
        long yesterday = now - 24L * 3600 * 1000;

        SkillHubClient client = mock(SkillHubClient.class);
        // 候选池第一路(按下载量): 老而量大的 old-huge 排在第一
        when(client.list(anyInt(), anyInt(), any(), any(), eq("downloads"), any())).thenReturn(json("""
                {"data":{"total":2,"skills":[
                  {"slug":"old-huge","downloads":1000000,"stars":0,"installs":0,"updated_at":%d,
                   "namespace":{"canonicalName":"@a/old-huge"}},
                  {"slug":"new-hot","downloads":1,"stars":10000,"installs":10000,"updated_at":%d,
                   "namespace":{"canonicalName":"@b/new-hot"}}
                ]}}
                """.formatted(fiveYearsAgo, yesterday)));
        // 第二路(按最近更新)给空: 让池子就是上面两条, 断言才确定
        when(client.list(anyInt(), anyInt(), any(), any(), eq("updated_at"), any()))
                .thenReturn(json("{\"data\":{\"total\":0,\"skills\":[]}}"));
        SkillMarketService svc = service(client);

        SkillMarketDtos.MarketPage p = svc.list(0, 12, null, null, "recommended", "u1");

        assertEquals("recommended", p.getSort());
        assertTrue(p.isRecommended());
        assertEquals(2, p.getTotal(), "推荐模式下 total 应是候选池大小, 而不是全库总数");
        // 老技能下载量高 100 万倍, 但零收藏零安装且 5 年没更新 → 应排在活跃的新技能之后
        assertEquals("new-hot", p.getItems().get(0).getSlug());
        assertEquals("old-huge", p.getItems().get(1).getSlug());

        // 池内分页: 第 2 页(每页 1 条)拿到的应是池内第 2 名, total 仍是池子大小
        SkillMarketDtos.MarketPage p2 = svc.list(1, 1, null, null, "recommended", "u1");
        assertEquals(1, p2.getItems().size());
        assertEquals("old-huge", p2.getItems().get(0).getSlug());
        assertEquals(2, p2.getTotal());
    }

    @Test
    @DisplayName("搜索映射: 兼容 icon_url / publisher 等与列表不同的字段名")
    void searchMappingToleratesAlternateFieldNames() {
        SkillHubClient client = mock(SkillHubClient.class);
        when(client.search(any(), anyInt())).thenReturn(json("""
                {"results":[
                  {"slug":"excel","displayName":"Excel","summary":"表格自动化","category":"office-efficiency",
                   "icon_url":"https://cdn/x.png","source":"enterprise",
                   "publisher":{"name":"昆仑增长","verified":true},
                   "tags":["excel","办公提效"]}
                ]}
                """));

        List<SkillMarketDtos.MarketSkillView> list = service(client).search("excel", 20, "u1");

        assertEquals(1, list.size());
        SkillMarketDtos.MarketSkillView v = list.get(0);
        assertEquals("Excel", v.getName());
        assertEquals("https://cdn/x.png", v.getIconUrl());
        assertEquals("昆仑增长", v.getPublisher());
        assertTrue(v.isPublisherVerified());
        assertEquals(List.of("excel", "办公提效"), v.getTags());
    }

    @Test
    @DisplayName("来源页: 优先上游 GitHub 页, 否则拼可打开的 SkillHub 展示页")
    void sourceUrlPrefersUpstreamThenOpenableSkillhubPage() {
        SkillHubClient client = mock(SkillHubClient.class);
        when(client.list(anyInt(), anyInt(), any(), any(), any(), any())).thenReturn(json("""
                {"data":{"total":2,"skills":[
                  {"slug":"browser-use","name":"Browser Use",
                   "upstream_url":"https://clawhub.ai/ns/browser-use",
                   "homepage":"https://api.skillhub.cn/ns/browser-use",
                   "namespace":{"handle":"ns"}},
                  {"slug":"dev-expert","name":"Dev Expert",
                   "homepage":"https://api.skillhub.cn/indiv-ebandao/dev-expert",
                   "namespace":{"handle":"indiv-ebandao"}}
                ]}}
                """));

        List<SkillMarketDtos.MarketSkillView> items =
                service(client).list(0, 12, null, null, "downloads", "u1").getItems();

        assertEquals("https://clawhub.ai/ns/browser-use", items.get(0).getSourceUrl(),
                "有上游页时优先给上游页 —— 它最接近技能的原始出处");
        assertEquals("https://skillhub.cn/skills/indiv-ebandao/dev-expert", items.get(1).getSourceUrl(),
                "没有上游页时用 handle/slug 拼技能页; 三个候选 URL 里只有这个 /skills/ 形式可用"
                        + "(api. 子域 405, 缺 /skills/ 段是 SPA 的 page not found)");
    }

    @Test
    @DisplayName("来源页: 详情的 namespace 只挂在 root 上, 也必须能拿到(社区/企业源就靠它)")
    void detailResolvesSourceUrlWhenNamespaceIsOnlyOnRoot() {
        SkillHubClient client = mock(SkillHubClient.class);
        // 忠实还原实测结构: namespace 在 root 上, skill 节点里没有; 且社区源没有 upstream_url。
        // 这条之所以要单独钉住: 详情路径是 toView(skillNode, root, userId), 若只从当前节点找
        // namespace, 那么"没有 upstream_url 的七成技能(社区/企业源)"在详情页就拿不到来源页,
        // 而有 upstream_url 的三成正常 —— 表现为"有的有、有的没有", 最难被发现的一类分支。
        when(client.detail(any(), any())).thenReturn(json("""
                {"slug":"dev-expert",
                 "namespace":{"canonicalName":"@indiv-ebandao/dev-expert","handle":"indiv-ebandao"},
                 "skill":{"slug":"dev-expert","displayName":"Dev Expert","source":"community"}}
                """));
        when(client.versions(any(), any())).thenReturn(json("{\"versions\":[]}"));

        SkillMarketDtos.MarketSkillDetail d = service(client).detail("dev-expert", "indiv-ebandao", "u1");

        assertEquals("indiv-ebandao", d.getNamespace(), "handle 必须能从 root 的 namespace 取到");
        assertEquals("https://skillhub.cn/skills/indiv-ebandao/dev-expert", d.getSourceUrl(),
                "没有 upstream_url 的社区源技能也要能拼出来源页 —— 它们占市场的七成");
    }

    // ===== 4. 安全结论 =====

    @Test
    @DisplayName("安全结论: 取最需要提醒的那个结论, benign 不能盖掉 queued")
    void detailPicksMostNoteworthySecurityStatus() {
        SkillHubClient client = mock(SkillHubClient.class);
        when(client.detail(any(), any())).thenReturn(json("""
                {"slug":"browser-use",
                 "latestVersion":{"version":"2.0.1","changelog":"同步自上游"},
                 "namespace":{"canonicalName":"@ns/browser-use","handle":"ns"},
                 "securityReports":{
                   "keen":{"status":"benign","statusText":"安全，无风险","reportUrl":"https://a"},
                   "sanbu":{"status":"queued","statusText":"排队中","reportUrl":""}},
                 "skill":{"slug":"browser-use","displayName":"Browser Use","category":"ai-agent"}}
                """));
        when(client.versions(any(), any())).thenReturn(json("""
                {"slug":"browser-use","versions":[
                  {"version":"2.0.1","versionId":43146,"createdAt":1777498869307,"changelog":"c",
                   "securityReports":{"keen":{"status":"benign","statusText":"安全，无风险"}}}]}
                """));

        SkillMarketDtos.MarketSkillDetail d = service(client).detail("browser-use", "ns", "u1");

        // queued(severity 2) 必须压过 benign(severity 1)
        assertEquals("queued", d.getSecurityStatus());
        assertEquals("排队中", d.getSecurityStatusText());
        assertEquals(2, d.getSecurityReports().size());
        assertEquals("同步自上游", d.getChangelog());
        assertEquals(1, d.getVersions().size());
        assertEquals("2.0.1", d.getVersions().get(0).getVersion());
    }

    @Test
    @DisplayName("安全结论: 未知结论按最需提醒处理, 不被静默忽略")
    void detailTreatsUnknownSecurityStatusAsNoteworthy() {
        SkillHubClient client = mock(SkillHubClient.class);
        when(client.detail(any(), any())).thenReturn(json("""
                {"slug":"x",
                 "securityReports":{"keen":{"status":"benign"},"new":{"status":"risk","statusText":"存在风险"}},
                 "skill":{"slug":"x","displayName":"X"}}
                """));
        when(client.versions(any(), any())).thenReturn(json("{\"versions\":[]}"));

        SkillMarketDtos.MarketSkillDetail d = service(client).detail("x", null, "u1");

        assertEquals("risk", d.getSecurityStatus());
        assertEquals("存在风险", d.getSecurityStatusText());
    }

    // ===== 5. 引入 =====

    @Test
    @DisplayName("引入: 图标地址随技能包一并带入(只带 URL, 不下载图片字节)")
    void installCarriesIconUrlIntoPackage() {
        SkillHubClient client = mock(SkillHubClient.class);
        when(client.detail(any(), any())).thenReturn(json("""
                {"slug":"browser-use",
                 "namespace":{"canonicalName":"@ns/browser-use","handle":"ns"},
                 "skill":{"slug":"browser-use","displayName":"Browser Use","category":"ai-agent",
                          "iconUrl":"https://cdn.example.com/icon.png",
                          "upstream_url":"https://clawhub.ai/x/browser-use"}}
                """));
        when(client.versions(any(), any())).thenReturn(json("{\"versions\":[]}"));

        SkillRepository repo = mock(SkillRepository.class);
        when(repo.findByUserIdAndName(any(), any())).thenReturn(Optional.empty());

        SkillDtos.ImportResult imported = new SkillDtos.ImportResult();
        imported.setId("skill_1");
        imported.setName("browser-use");
        imported.setAction("created");
        SkillPackageService pkgSvc = mock(SkillPackageService.class);
        when(pkgSvc.importPackage(any(), any(), anyBoolean())).thenReturn(imported);

        SkillMarketService svc = new SkillMarketService(enabledProps(), client, repo, pkgSvc);
        SkillMarketDtos.InstallRequest req = new SkillMarketDtos.InstallRequest();
        req.setSlug("browser-use");
        req.setNamespace("ns");

        SkillMarketDtos.InstallResult result = svc.install(req, "u1");

        ArgumentCaptor<SkillDtos.ImportRequest> cap = ArgumentCaptor.forClass(SkillDtos.ImportRequest.class);
        verify(pkgSvc).importPackage(cap.capture(), eq("u1"), eq(false));
        SkillDtos.SkillPackage pkg = cap.getValue().getSkill();
        assertEquals("https://cdn.example.com/icon.png", pkg.getIconUrl());
        // 来源页必须单独存下来: "正文待补全"时用户要去这里取正文, 不能只藏在正文里
        assertEquals("https://clawhub.ai/x/browser-use", pkg.getSourceUrl());
        // 血缘必须带上命名空间: 同名技能可以来自不同发布者(实测 dev-expert 就有两个命名空间),
        // 只记 slug 会分不清到底是从哪一个引入的
        assertEquals("skillhub:@ns/browser-use", pkg.getSourceRef());
        assertEquals("browser-use", pkg.getName());
        // 不声明"需要执行能力": 上游没有这个字段, 判断不了。
        // 若无条件置 true, 该标记会退化成"来自市场"的同义词, 真正需要它的技能就失去提示价值了
        assertFalse(Boolean.TRUE.equals(pkg.getHasExecutable()));
        // 骨架正文不可用 → 必须停用入库, 否则它会进入模型可见的技能目录
        assertFalse(result.isEnabled());
    }

    @Test
    @DisplayName("引入: 血缘优先用 canonicalName, 缺失时回退 @handle/slug, 都没有则不写")
    void sourceRefFallsBackToHandleAndSlug() {
        // 1) 有 canonicalName(自带命名空间) → 直接用, 这是主流情况
        assertEquals("skillhub:@ns/browser-use", installPackOf("""
                {"slug":"browser-use","canonicalName":"@ns/browser-use",
                 "namespace":{"handle":"ns"},
                 "skill":{"slug":"browser-use","displayName":"B","source":"community"}}
                """).getSourceRef());

        // 2) 上游没给 canonicalName → 用 handle + slug 自己拼, 仍然可回溯
        assertEquals("skillhub:@ns/browser-use", installPackOf("""
                {"slug":"browser-use","namespace":{"handle":"ns"},
                 "skill":{"slug":"browser-use","displayName":"B","source":"community"}}
                """).getSourceRef());

        // 3) 连标识都拼不出来 → 留空。宁可没有血缘, 也不写一个编出来的标识
        assertNull(installPackOf("""
                {"slug":"browser-use","skill":{"slug":"browser-use","displayName":"B"}}
                """).getSourceRef());
    }

    /** 跑一次引入, 返回实际交给技能包导入的那份包 */
    private static SkillDtos.SkillPackage installPackOf(String detailJson) {
        SkillHubClient client = mock(SkillHubClient.class);
        when(client.detail(any(), any())).thenReturn(json(detailJson));
        when(client.versions(any(), any())).thenReturn(json("{\"versions\":[]}"));

        SkillDtos.ImportResult imported = new SkillDtos.ImportResult();
        imported.setId("skill_1");
        imported.setName("browser-use");
        imported.setAction("created");
        SkillPackageService pkgSvc = mock(SkillPackageService.class);
        when(pkgSvc.importPackage(any(), any(), anyBoolean())).thenReturn(imported);

        SkillMarketDtos.InstallRequest req = new SkillMarketDtos.InstallRequest();
        req.setSlug("browser-use");
        req.setNamespace("ns");
        new SkillMarketService(enabledProps(), client, mock(SkillRepository.class), pkgSvc)
                .install(req, "u1");

        ArgumentCaptor<SkillDtos.ImportRequest> cap = ArgumentCaptor.forClass(SkillDtos.ImportRequest.class);
        verify(pkgSvc).importPackage(cap.capture(), eq("u1"), eq(false));
        return cap.getValue().getSkill();
    }
}
