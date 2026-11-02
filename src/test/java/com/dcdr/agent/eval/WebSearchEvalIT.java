package com.dcdr.agent.eval;

import com.dcdr.agent.config.ToolsProperties;
import com.dcdr.agent.tool.websearch.DeepseekApiClient;
import com.dcdr.agent.tool.websearch.DeepseekSearchProvider;
import com.dcdr.agent.tool.websearch.SearxngSearchProvider;
import com.dcdr.agent.tool.websearch.WebSearchProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link WebSearchEvalService} 的<b>冒烟测试</b>(默认不执行)。
 *
 * <h2>它测什么、不测什么</h2>
 * 只测"这条链路还能不能跑通": 提交 → 执行 → 归档 → 读回 → 出现在列表里。
 * <b>不</b>测分数好坏 —— 排序质量没有绝对阈值, 硬编码一个"MRR 必须 ≥ 0.4"只会天天误报。
 *
 * <h2>为什么不在这里重复实现评分</h2>
 * 评测逻辑已从测试类搬进 {@code WebSearchEvalService}(main), 因为产品需要一个页面入口。
 * 这个类因此退化成薄壳: 只负责"用临时目录跑一次、断言产物合法"。若在这里再写一份评分,
 * 两份实现必然漂移 —— 页面显示的数和命令行跑出来的数会不一致, 而没人知道该信哪个。
 *
 * <h2>怎么跑</h2>
 * <pre>
 *   WEB_EVAL=true mvn -B test -Dtest=WebSearchEvalIT
 *   # 需要 deepseek 时额外给 DEEPSEEK_API_KEY, 并把 WEB_EVAL_PROVIDER 设为 deepseek / both
 * </pre>
 * 默认只跑 searxng(免费、无需 Key), 对无外网余额的环境也友好。
 * 日常用「运维台 → 联网评测」页面触发即可, 这里主要给无界面场景兜底。
 */
class WebSearchEvalIT {

    @Test
    @DisplayName("服务能跑通一轮评测并把报告归档(冒烟, 不断言分数高低)")
    void runsAndArchives() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("WEB_EVAL")),
                "未开启评测(设置 WEB_EVAL=true 后执行)");

        ToolsProperties properties = new ToolsProperties();
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            properties.getWeb().getDeepseek().setApiKey(apiKey);
        }
        List<WebSearchProvider> providers = new ArrayList<>();
        providers.add(new SearxngSearchProvider(properties));
        providers.add(new DeepseekSearchProvider(properties, new DeepseekApiClient(properties)));

        // 归档默认写 target/(测试不该往仓库的 docs/eval/reports 里塞文件);
        // 需要生成正式基线时用 WEB_EVAL_ARCHIVE_DIR 指到 docs/eval/reports
        String archiveDir = System.getenv().getOrDefault("WEB_EVAL_ARCHIVE_DIR",
                Path.of("target", "eval-reports").toString());
        WebSearchEvalService service = new WebSearchEvalService(providers, new ObjectMapper(), archiveDir);

        String provider = System.getenv().getOrDefault("WEB_EVAL_PROVIDER", "searxng").toLowerCase();
        WebSearchEvalService.RunState submitted = service.submit(provider);
        assertTrue(submitted.running() || "FAILED".equals(submitted.status()),
                "提交后应处于运行中或明确失败, 实际: " + submitted.status());

        long deadline = System.currentTimeMillis() + 5 * 60 * 1000;
        while (service.currentState().running() && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
        }
        WebSearchEvalService.RunState state = service.currentState();

        assertEquals("DONE", state.status(), "评测未正常结束: " + state.error());
        assertNotNull(state.reportFile(), "完成后必须产出报告文件");

        WebSearchEvalService.EvalReport report = service.readReport(state.reportFile());
        assertTrue(report.getCases() > 0, "报告应记录用例数");
        assertTrue(!report.getProviders().isEmpty(), "报告应至少含一个源的指标");
        report.getProviders().forEach((id, m) -> {
            assertTrue(m.getMrr() >= 0 && m.getMrr() <= 1, id + " 的 MRR 越界: " + m.getMrr());
            assertTrue(m.getHitRate() >= 0 && m.getHitRate() <= 1, id + " 的 HitRate 越界");
            assertEquals(report.getCases(), m.getCases().size(), id + " 的逐条结果数应与用例数一致");
        });

        assertTrue(service.listReports().stream().anyMatch(r -> r.file().equals(state.reportFile())),
                "刚归档的报告应出现在列表里");
    }
}
