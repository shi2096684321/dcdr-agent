package com.dcdr.agent.tool;

import com.dcdr.agent.model.langchain4j.JsonSchemaConverter;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ToolRegistry 注册期转换测试 (V3.0 P2 工具定义框架化)。
 * <p>
 * 验证三件事:
 * <ol>
 *   <li>静态工具(流式 builder schema)注册后同时产出框架规范格式与 OpenAI 兼容 Map</li>
 *   <li>动态工具(JSON schema 字符串, 如 MCP)注册后保留原始 JSON 于 legacy 定义</li>
 *   <li>白名单过滤对两个出口一致生效</li>
 * </ol>
 */
class ToolRegistryTest {

    /** 静态 schema 工具(框架 builder 声明) */
    static class StaticSchemaTool extends AbstractTool {
        @Override
        public String getName() {
            return "static_tool";
        }

        @Override
        public String getDescription() {
            return "静态 schema 测试工具";
        }

        @Override
        public JsonObjectSchema getParameters() {
            return JsonObjectSchema.builder()
                    .addProperty("city", JsonStringSchema.builder().description("城市名").build())
                    .required(List.of("city"))
                    .build();
        }

        @Override
        public String execute(Map<String, Object> params) {
            return "ok";
        }

        @Override
        public boolean supportsStreaming() {
            return true;
        }
    }

    /** 动态 schema 工具(JSON 字符串, 模拟 MCP inputSchema) */
    static class DynamicSchemaTool extends AbstractTool {
        @Override
        public String getName() {
            return "dynamic_tool";
        }

        @Override
        public String getDescription() {
            return "动态 schema 测试工具";
        }

        @Override
        public String getParameterSchema() {
            return """
                    {"type":"object","properties":{"q":{"type":"string"}},"required":["q"]}""";
        }

        @Override
        public String execute(Map<String, Object> params) {
            return "ok";
        }
    }

    @Test
    void staticToolProducesBothFormats() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new StaticSchemaTool());

        // 框架规范格式
        List<ToolSpecification> specs = registry.getStreamingToolSpecifications();
        assertEquals(1, specs.size());
        ToolSpecification spec = specs.get(0);
        assertEquals("static_tool", spec.name());
        assertNotNull(spec.parameters());
        assertTrue(spec.parameters().required().contains("city"));
        assertEquals(1, spec.parameters().properties().size());

        // OpenAI 兼容 Map(legacy Provider 回退路径)
        List<Map<String, Object>> defs = registry.getStreamingToolDefinitions();
        assertEquals(1, defs.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) defs.get(0).get("function");
        assertEquals("static_tool", function.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");
        assertEquals("object", parameters.get("type"));
        assertTrue(((List<?>) parameters.get("required")).contains("city"));
        @SuppressWarnings("unchecked")
        Map<String, Object> city = (Map<String, Object>) ((Map<String, Object>) parameters.get("properties")).get("city");
        assertEquals("string", city.get("type"));
        assertEquals("城市名", city.get("description"));
    }

    @Test
    void dynamicToolKeepsOriginalJsonInLegacyDef() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new DynamicSchemaTool());

        // 规范格式: JSON 解析转换
        List<ToolSpecification> specs = registry.getStreamingToolSpecificationsFiltered(null);
        // DynamicSchemaTool 未标记 supportsStreaming(默认 false), 不出现在流式出口
        assertTrue(specs.isEmpty());

        // 非流式出口(getToolSpecifications)应有
        List<ToolSpecification> all = registry.getToolSpecifications();
        assertEquals(1, all.size());
        assertEquals("dynamic_tool", all.get(0).name());
        assertTrue(all.get(0).parameters().required().contains("q"));

        // legacy 定义保留原始 JSON(q 属性无 description, 反向派生会丢失/不同)
        List<Map<String, Object>> defs = registry.getToolDefinitions();
        assertEquals(1, defs.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) defs.get(0).get("function");
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");
        assertTrue(((List<?>) parameters.get("required")).contains("q"));
        assertNull(registry.getTool("dynamic_tool").getParameters());
    }

    @Test
    void whitelistFilterAppliesToBothOutputs() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new StaticSchemaTool());
        registry.register(new DynamicSchemaTool());

        List<ToolSpecification> specs = registry.getStreamingToolSpecificationsFiltered(List.of("nonexistent"));
        assertTrue(specs.isEmpty());
        List<Map<String, Object>> defs = registry.getStreamingToolDefinitionsFiltered(List.of("nonexistent"));
        assertTrue(defs.isEmpty());

        specs = registry.getStreamingToolSpecificationsFiltered(List.of("static_tool"));
        assertEquals(1, specs.size());
    }

    @Test
    void brokenSchemaToolStillExecutable() {
        ToolRegistry registry = new ToolRegistry();
        Tool broken = new AbstractTool() {
            @Override
            public String getName() {
                return "broken_tool";
            }

            @Override
            public String getDescription() {
                return "坏 schema 工具";
            }

            @Override
            public String getParameterSchema() {
                return "not a valid json";
            }

            @Override
            public String execute(Map<String, Object> params) {
                return "still works";
            }
        };
        registry.register(broken);

        // schema 构建失败: 不暴露给 LLM, 但可按名执行
        assertTrue(registry.getToolSpecifications().isEmpty());
        assertEquals("still works", registry.execute("broken_tool", Map.of()));
    }

    @Test
    void schemaConverterRoundTrip() {
        // 正向: OpenAI Map → 框架类型; 反向: 框架类型 → OpenAI Map; 结构应等价
        Map<String, Object> original = new LinkedHashMap<>();
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> city = new LinkedHashMap<>();
        city.put("type", "string");
        city.put("description", "城市名");
        props.put("city", city);
        original.put("type", "object");
        original.put("properties", props);
        original.put("required", List.of("city"));

        JsonObjectSchema schema = JsonSchemaConverter.toObjectSchema(original);
        Map<String, Object> roundTrip = JsonSchemaConverter.fromObjectSchema(schema);

        assertEquals("object", roundTrip.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> rtProps = (Map<String, Object>) roundTrip.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> rtCity = (Map<String, Object>) rtProps.get("city");
        assertEquals("string", rtCity.get("type"));
        assertEquals("城市名", rtCity.get("description"));
        assertTrue(((List<?>) roundTrip.get("required")).contains("city"));
    }

    @Test
    void noSchemaToolIsParameterless() {
        ToolRegistry registry = new ToolRegistry();
        Tool bare = new AbstractTool() {
            @Override
            public String getName() {
                return "bare_tool";
            }

            @Override
            public String getDescription() {
                return "无参数工具";
            }

            @Override
            public String execute(Map<String, Object> params) {
                return "ok";
            }
        };
        registry.register(bare);

        List<ToolSpecification> specs = registry.getToolSpecifications();
        assertEquals(1, specs.size());
        // 无 schema → 规范格式 parameters 为空(无参数工具), legacy 定义不含 parameters 键
        assertTrue(specs.get(0).parameters() == null || specs.get(0).parameters().properties().isEmpty());
        List<Map<String, Object>> defs = registry.getToolDefinitions();
        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) defs.get(0).get("function");
        assertFalse(function.containsKey("parameters"));
    }
}
