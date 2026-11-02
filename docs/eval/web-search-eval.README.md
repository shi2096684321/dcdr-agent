# 联网搜索排序评测集

## 它是什么，不是什么

**是**：一张固定试卷 + 三个数字。用来回答"换搜索源 / 改清洗规则 / 调阈值之后，排序质量是变好还是变差"。

**不是**：排行榜、学术基准。20 条用例，单条命中位次变化就能让 MRR 跳几个点 —— **差异小于 0.05 一律当噪声**。

线上"联网健康度"看板回答的是"有没有拿到东西"（空结果率、清洗损耗）；本评测回答的是"拿到的东西对不对、排得准不准"。两者互补，缺一不可：换了搜索源之后结果条数可能完全一样，只是相关性掉了，看板看不出来。

## 金标怎么定

用**域名 + 路径片段**，不用精确 URL：

```json
"expectedUrlContains": ["spring.io/blog", "spring.io/projects/spring-boot"]
```

命中任一片段即算找到。理由：精确 URL 会随站点改版失效，让评测在没人动代码时自己变红——那种评测两周后就不会有人看了。

**两条铁律**：

1. **金标按"什么算好答案"定，不能按"引擎返回了什么"反推。**
   如果发现某条没命中，就把它返回的 URL 加进金标，评测立刻变成"给现有结果打分"，
   从此只有 100% 命中率、什么也测不出来。
2. **问"官方怎么说"的用例，金标必须是官方域名**（`docs.oracle.com`、`postgresql.org/docs`）。
   这类用例的价值恰恰在于"引擎有没有把官方排在前面"，放宽就失去意义。
   而问"怎么排查"的排错类用例，StackOverflow 高票答案在实践上同样是正确答案，应当允许。

## 指标口径（与 RAG 评测的差异）

| 指标 | 含义 | 用途 |
|---|---|---|
| **HitRate@K** | 多少条 query 在前 K 条里命中了金标 | 整体可用性 |
| **MRR@K** | 首个命中位置的倒数均值 | **主指标**：第 1 位对上了和第 9 位才对上，对模型帮助天差地别 |
| ~~Recall~~ | 不用 | RAG 那边一条 query 有多个**独立**正例，Recall 才有意义；这里的金标是一组**等价前缀**，算 Recall 只会得到恒偏低的假指标 |

分类型统计比总分更有用：总分无法指导行动，"哪类查询差"才能。

## 当前基线（2026-11-02，20 条用例 / topK=10）

| 指标 | searxng | deepseek |
|---|---|---|
| HitRate@10 | 35.0% | **60.0%** |
| MRR@10 | 0.205 | **0.433** |
| 平均首命中位次 | 3.29 | **2.83** |

分类型 MRR：`official` 0.20 → 0.40，`fact` 0 → 1.00，`zh-longtail` 0.25 → 1.00；
`tech` 两边都极低（0.045 → 0.110）—— 技术排错类查询是共同短板，与搜索源无关。

## 怎么跑

### 首选：页面（运维台 → 联网评测）

用管理员账号登录 → 左侧「运维台」→ 页签「联网评测」。页面上能做三件事：

- 选来源（两个源对比 / 仅 SearXNG / 仅 DeepSeek），点「运行评测」
- 看进度（跑的时候显示已完成几次检索），跑完自动选中最新报告
- 左边是历史报告列表，右边是选中报告：对比表 + 未命中明细

后台接口：`/api/eval/web-search/{overview,reports,reports/{file},run}`，全部管理员专属
（跑一次会真实调用外部搜索，不该开放给普通用户）。

> 页面和命令行**走的是同一份实现**（`WebSearchEvalService`）。命令行只是无界面时的兜底，
> 不存在"两边算出来的数不一样"的问题。

### 兜底：命令行

在**项目根目录**执行（需要在有 `pom.xml` 的那一层，路径都是相对的）：

```bash
# 两个源对比(推荐)
WEB_EVAL=true DEEPSEEK_API_KEY=sk-xxx mvn -B test -Dtest=WebSearchEvalIT

# 只跑一个源(省时间/省费用)
WEB_EVAL=true WEB_EVAL_PROVIDER=searxng mvn -B test -Dtest=WebSearchEvalIT
WEB_EVAL=true WEB_EVAL_PROVIDER=deepseek DEEPSEEK_API_KEY=sk-xxx \
  mvn -B test -Dtest=WebSearchEvalIT
```

前置条件：

- `searxng` → 本地 SearXNG 容器在跑（`docker ps` 里应有 `dcdr-agent-searxng`，端口 8888）
- `deepseek` → `DEEPSEEK_API_KEY` 有值且账户有余额（20 条约 0.1 元）

> 为什么必须写 `-Dtest=`：类名是 `*IT`，surefire 不认，`mvn test` 不会跑它。
> `mvn verify` 会加载但跳过（`WEB_EVAL` 未设），所以它不会污染日常构建，也不会在 CI 里偷偷花钱。

## 产物在哪

报告是**一份 JSON**，写到 `docs/eval/reports/{时间戳}-{源}.json`（入库，跟着 commit 走）。

- 页面直接读它渲染，不需要额外的 markdown
- 命令行想看：`cat docs/eval/reports/xxx.json | jq '.providers'`
- 归档目录可配：`agent.eval.web-search.reports-dir`（测试会指到 `target/`，不往仓库里写）

内容含每个源的 `hitRate` / `mrr` / `avgRank` 与**逐条结果**（rank、命中 URL、首条 URL、异常），
所以"未命中时它到底返回了什么"也能回答。文件名带时间戳不覆盖，保留"同一天跑了多次"的中间结果。

## 怎么跟上次比

```bash
# 看历史上跑过几次
ls docs/eval/reports/

# 直接比数字(JSON 只存指标, 一眼看出涨没涨)
diff docs/eval/reports/2026-11-02-1713-searxng+deepseek.json \
     docs/eval/reports/2026-11-15-1420-searxng+deepseek.json

# 看哪几条从命中变成未命中 / 反过来
# json 里的 misses 数组就是答案
```

**对比时才要注意**：只有同 provider、同用例集、同 topK 的两份才有可比性。用例集改了（加/删 case）
就必须重新建立基线，不能用旧数字直接比。

## 怎么加用例

在 `web-search-eval.json` 的 `cases` 里追加：

```json
{
  "id": "唯一短标识, 前缀表示类型",
  "type": "fact | official | tech | news | zh-longtail | entity",
  "query": "用真实用户会怎么问, 不要写成关键词堆",
  "expectedUrlContains": ["至少一个能站得住比的域名/路径"],
  "note": "为什么这些算对、什么不算"
}
```

加完**一定要跑一次**确认它至少能被其中一个源命中——长期 100% miss 的用例等于给自己埋噪音。

## 已知局限

- 只有 20 条，且集中在中英双语技术/资讯类；电商、本地服务、多轮指代类查询没有覆盖
- 金标是二值的，算不了 NDCG（需要 0/1/2/3 分级标注，成本约 10 倍）
- 只评"排序"，不评"抓取正文质量"——后者看 `WebExtractFixtureTest` 与联网健康看板的 `contentChars` / `thinPages`
