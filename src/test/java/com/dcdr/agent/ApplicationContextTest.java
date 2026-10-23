package com.dcdr.agent;

import com.dcdr.agent.agent.AgentOrchestrator;
import com.dcdr.agent.config.ToolsProperties;
import com.dcdr.agent.model.registry.ModelRegistry;
import com.dcdr.agent.rag.embedding.EmbeddingRegistry;
import com.dcdr.agent.tool.Tool;
import com.dcdr.agent.tool.ToolRegistry;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.scoring.ScoringModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring 上下文装配冒烟测试(V3.2 测试体系基建)。
 *
 * <p>这是项目第一个 {@code @SpringBootTest}: 不验证业务逻辑, 只回答一个问题 ——
 * <b>"全部 Bean 依赖能否装配成功、启动期副作用能否正常完成"</b>。它能拦住一类最常见的低级错误:
 * <ul>
 *   <li>新增/删除构造依赖后没有同步更新调用方(如 {@link AgentOrchestrator} 有 20 个协作者)</li>
 *   <li>条件装配({@code @ConditionalOn*})互斥关系写错, 导致重复注册或全都不注册</li>
 *   <li>新增 {@code @Component} 与已有 Bean 名/类型冲突</li>
 *   <li>配置文件缺 key, 导致 {@code @Value} 占位符解析失败</li>
 *   <li>启动期注册逻辑静默失败(只记 ERROR 日志、不阻断启动), 如工具 schema 构建失败
 *       —— 见 {@link #allStreamingToolsExposedToLlm()}</li>
 * </ul>
 *
 * <p>这些错误在 {@code mvn compile} 阶段完全看不出来(编译期只知道类型, 不知道运行期能否注入/转换),
 * 过去只能靠"启动一次完整应用并人工翻日志"来发现; 有了本测试后, {@code mvn test} 即可拦截。
 *
 * <p><b>零外部依赖</b>: 通过 {@code @ActiveProfiles("test")} 激活
 * {@code src/test/resources/application-test.yml}, 数据源切为 H2 内存库,
 * 不连 PostgreSQL / SearXNG / 沙箱 / Zipkin, 故在任何机器上都能直接跑通。
 *
 * <p><b>启动期副作用会真实发生</b>: {@code @SpringBootTest} 经 {@code SpringBootContextLoader}
 * 走 {@code SpringApplication.run()}, 因此 {@code ApplicationRunner} / {@code CommandLineRunner}
 * <b>会被执行</b>(实测: ToolRegistry 已注册全部工具、ModelRegistry 已注册模型、
 * WorkflowTemplateInitializer 已插入预置模板)。这使本类还能覆盖"注册期转换"这类只在启动时
 * 发生、且失败后不影响启动的隐患。
 */
@SpringBootTest
@ActiveProfiles("test")
class ApplicationContextTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ToolRegistry toolRegistry;

    /** V3.2 部署期工具白名单: 用于区分"故意不暴露"与"schema 构建失败" */
    @Autowired
    private ToolsProperties toolsProperties;

    @Test
    @DisplayName("Spring 上下文可成功启动")
    void contextLoads() {
        assertNotNull(context, "Spring 上下文应成功启动");
        assertTrue(context.getBeanDefinitionCount() > 0, "上下文应加载到 Bean 定义");
    }

    @Test
    @DisplayName("核心编排 Bean 装配完整(任一依赖缺失都会导致上下文启动失败)")
    void coreBeansPresent() {
        // AgentOrchestrator 依赖 20 个协作者, 能取到即说明整条依赖链装配正确
        assertNotNull(context.getBean(AgentOrchestrator.class), "AgentOrchestrator 应装配成功");
        assertNotNull(context.getBean(ToolRegistry.class), "ToolRegistry 应装配成功");
        assertNotNull(context.getBean(ModelRegistry.class), "ModelRegistry 应装配成功");
        assertNotNull(context.getBean(EmbeddingRegistry.class), "EmbeddingRegistry 应装配成功");
    }

    @Test
    @DisplayName("联网工具以 web_search / web_extract 之名注册(编排层硬编码依赖这两个名字)")
    void webToolsPresent() {
        Map<String, Tool> toolBeans = context.getBeansOfType(Tool.class);
        Set<String> names = toolBeans.values().stream()
                .map(Tool::getName)
                .collect(Collectors.toSet());
        assertTrue(names.contains("web_search"),
                "缺少 web_search 工具, 编排层的联网链路会失效; 当前工具: " + names);
        assertTrue(names.contains("web_extract"),
                "缺少 web_extract 工具, 正文串联抓取会失效; 当前工具: " + names);
    }

    @Test
    @DisplayName("所有流式工具都能成功构建 schema 并对 LLM 可见")
    void allStreamingToolsExposedToLlm() {
        // 工具注册期会把参数 schema 转换为 ToolSpecification(对 LLM 可见的定义)。
        // 该转换失败时 ToolRegistry 只记 ERROR 日志、不阻断启动: 工具仍可按名执行,
        // 但"对 LLM 不可见" —— 模型永远不知道它存在, 功能等于废掉却毫无报错。
        // 这是最隐蔽的一类缺陷(编译期无感、启动成功、仅日志里一行 ERROR), 必须由测试兜住。
        Set<String> expected = context.getBeansOfType(Tool.class).values().stream()
                .filter(Tool::supportsStreaming)
                .map(Tool::getName)
                // V3.2: 部署白名单(agent-tools.enabled)是"故意不暴露", 不算 schema 构建失败。
                // 平台机制工具(skill_load / tool_result_read)不受白名单约束。
                .filter(name -> {
                    List<String> allowed = toolsProperties.getEnabled();
                    return (allowed == null || allowed.isEmpty() || allowed.contains(name))
                            || ToolRegistry.platformToolNames().contains(name);
                })
                .collect(Collectors.toSet());
        Set<String> exposed = toolRegistry.getStreamingToolSpecifications().stream()
                .map(ToolSpecification::name)
                .collect(Collectors.toSet());
        assertEquals(expected, exposed,
                "存在 schema 构建失败的工具(已注册执行但对 LLM 不可见); 请检查启动日志中的 "
                        + "'ToolSpecification 构建失败' ERROR");
    }

    @Test
    @DisplayName("重排默认关闭: 条件装配不应注册 ScoringModel Bean")
    void rerankDisabledByDefault() {
        // RagRetrievalConfig 下两个 ScoringModel Bean 均带 @ConditionalOnExpression,
        // 默认 enabled=false 时两者都不注册。若此处失败, 说明条件装配被改坏(可能重复注册)。
        Map<String, ScoringModel> beans = context.getBeansOfType(ScoringModel.class);
        assertTrue(beans.isEmpty(),
                "默认(agent.rag.retrieval.rerank.enabled=false)不应注册 ScoringModel, 实际: " + beans.keySet());
    }
}
