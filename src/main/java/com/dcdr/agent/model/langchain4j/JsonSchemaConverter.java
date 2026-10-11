package com.dcdr.agent.model.langchain4j;

import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonReferenceSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON Schema 转换器 (V3.0 框架化改造)。
 * <p>
 * 项目内部(ToolRegistry 工具定义 / IntentRouter 路由 schema / Agent 结构化输出)通行的都是
 * OpenAI 风格的 JSON Schema(裸 Map 结构), 而 LangChain4j 的
 * {@code ToolSpecification.parameters} / {@code JsonSchema.rootElement} 要求类型化的
 * {@link JsonSchemaElement} 树, 本类负责两者之间的双向转换:
 * <ul>
 *   <li>{@link #toSchemaElement}/{@link #toObjectSchema}: OpenAI Map → 框架类型(主路径)</li>
 *   <li>{@link #fromSchemaElement}/{@link #fromObjectSchema}: 框架类型 → OpenAI Map
 *       (反向, 供 legacy 手写 Provider 回退路径从缓存的 ToolSpecification 派生工具定义)</li>
 * </ul>
 * 支持的 schema 特性: object/array/string/integer/number/boolean/enum、
 * required、additionalProperties、items、description、$ref、
 * 可空联合类型(如 {@code ["string","null"]}); 不认识的节点返回 null,
 * 由调用方决定跳过该属性(不阻断其他工具/属性)。
 */
public final class JsonSchemaConverter {

    private JsonSchemaConverter() {
    }

    /**
     * 任意 schema 节点 → JsonSchemaElement。
     *
     * @param node OpenAI 风格 JSON Schema 节点(Map 结构)
     * @return 类型化 schema 元素; 无法识别时返回 null
     */
    public static JsonSchemaElement toSchemaElement(Object node) {
        if (!(node instanceof Map<?, ?> rawMap)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) rawMap;
        String description = m.get("description") instanceof String s ? s : null;

        // 枚举(字符串枚举)优先于 type 判断
        if (m.get("enum") instanceof List<?> enumValues && !enumValues.isEmpty()) {
            List<String> values = enumValues.stream().map(String::valueOf).toList();
            return JsonEnumSchema.builder().enumValues(values).description(description).build();
        }
        // $ref 引用(MCP 工具 inputSchema 可能出现)
        if (m.get("$ref") instanceof String ref) {
            return JsonReferenceSchema.builder().reference(ref).build();
        }
        Object type = m.get("type");
        // 可空联合类型 ["string","null"] → anyOf(string, null)
        if (type instanceof List<?> types) {
            List<JsonSchemaElement> alternatives = new ArrayList<>();
            for (Object t : types) {
                if ("null".equals(t)) {
                    alternatives.add(new JsonNullSchema());
                    continue;
                }
                JsonSchemaElement el = toSchemaElement(Map.of("type", t));
                if (el != null) {
                    alternatives.add(el);
                }
            }
            if (alternatives.size() == 1) {
                return alternatives.get(0);
            }
            if (!alternatives.isEmpty()) {
                return JsonAnyOfSchema.builder().anyOf(alternatives).build();
            }
            return null;
        }
        // type 缺省时按结构推断: 有 properties 视为 object, 否则无法识别
        String t = type == null ? (m.containsKey("properties") ? "object" : null) : String.valueOf(type);
        if (t == null) {
            return null;
        }
        return switch (t) {
            case "string" -> JsonStringSchema.builder().description(description).build();
            case "integer" -> JsonIntegerSchema.builder().description(description).build();
            case "number" -> JsonNumberSchema.builder().description(description).build();
            case "boolean" -> JsonBooleanSchema.builder().description(description).build();
            case "array" -> {
                JsonSchemaElement items = m.get("items") == null ? null : toSchemaElement(m.get("items"));
                yield JsonArraySchema.builder().items(items).description(description).build();
            }
            case "object" -> {
                JsonObjectSchema.Builder b = JsonObjectSchema.builder().description(description);
                if (m.get("properties") instanceof Map<?, ?> props) {
                    for (Map.Entry<?, ?> e : props.entrySet()) {
                        JsonSchemaElement el = toSchemaElement(e.getValue());
                        if (el != null) {
                            b.addProperty(String.valueOf(e.getKey()), el);
                        }
                    }
                }
                if (m.get("required") instanceof List<?> required) {
                    b.required(required.stream().map(String::valueOf).toList());
                }
                if (m.get("additionalProperties") instanceof Boolean ap) {
                    b.additionalProperties(ap);
                }
                yield b.build();
            }
            default -> null;
        };
    }

    /**
     * 工具参数 schema(顶层应为 object) → {@link JsonObjectSchema}。
     * 顶层不是合法 object 时退化为空 object(等价于无参数工具), 不抛异常。
     */
    public static JsonObjectSchema toObjectSchema(Map<String, Object> schema) {
        JsonSchemaElement el = toSchemaElement(schema);
        return el instanceof JsonObjectSchema o ? o : JsonObjectSchema.builder().build();
    }

    /**
     * 反向转换: 框架类型化 schema → OpenAI 风格 JSON Schema(Map 结构)。
     * <p>
     * 供 legacy 手写 Provider 路径使用: ToolRegistry 以 ToolSpecification 为规范格式缓存,
     * OpenAI 格式定义由缓存 spec 派生。与正向转换支持相同的特性集。
     *
     * @param element 类型化 schema 元素
     * @return OpenAI 风格 Map; 无法识别时返回 null
     */
    public static Map<String, Object> fromSchemaElement(JsonSchemaElement element) {
        if (element == null) {
            return null;
        }
        if (element instanceof JsonStringSchema) {
            return schemaMap("string", ((JsonStringSchema) element).description());
        }
        if (element instanceof JsonIntegerSchema) {
            return schemaMap("integer", ((JsonIntegerSchema) element).description());
        }
        if (element instanceof JsonNumberSchema) {
            return schemaMap("number", ((JsonNumberSchema) element).description());
        }
        if (element instanceof JsonBooleanSchema) {
            return schemaMap("boolean", ((JsonBooleanSchema) element).description());
        }
        if (element instanceof JsonNullSchema) {
            return schemaMap("null", null);
        }
        if (element instanceof JsonEnumSchema e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "string");
            m.put("enum", new ArrayList<>(e.enumValues()));
            if (e.description() != null) {
                m.put("description", e.description());
            }
            return m;
        }
        if (element instanceof JsonReferenceSchema r) {
            return Map.of("$ref", r.reference());
        }
        if (element instanceof JsonAnyOfSchema a) {
            List<Object> alternatives = new ArrayList<>();
            for (JsonSchemaElement alt : a.anyOf()) {
                Map<String, Object> m = fromSchemaElement(alt);
                if (m != null) {
                    alternatives.add(m);
                }
            }
            if (alternatives.size() == 1) {
                @SuppressWarnings("unchecked")
                Map<String, Object> single = (Map<String, Object>) alternatives.get(0);
                return single;
            }
            return Map.of("anyOf", alternatives);
        }
        if (element instanceof JsonArraySchema a) {
            Map<String, Object> m = schemaMap("array", a.description());
            if (a.items() != null) {
                m.put("items", fromSchemaElement(a.items()));
            }
            return m;
        }
        if (element instanceof JsonObjectSchema o) {
            return fromObjectSchema(o);
        }
        return null;
    }

    /**
     * 反向转换: 顶层 object schema → OpenAI 风格 Map。
     */
    public static Map<String, Object> fromObjectSchema(JsonObjectSchema schema) {
        if (schema == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        if (schema.description() != null) {
            m.put("description", schema.description());
        }
        if (schema.properties() != null && !schema.properties().isEmpty()) {
            Map<String, Object> props = new LinkedHashMap<>();
            for (Map.Entry<String, JsonSchemaElement> e : schema.properties().entrySet()) {
                Map<String, Object> prop = fromSchemaElement(e.getValue());
                if (prop != null) {
                    props.put(e.getKey(), prop);
                }
            }
            m.put("properties", props);
        }
        if (schema.required() != null && !schema.required().isEmpty()) {
            m.put("required", new ArrayList<>(schema.required()));
        }
        if (schema.additionalProperties() != null) {
            m.put("additionalProperties", schema.additionalProperties());
        }
        return m;
    }

    /**
     * 基础类型 schema Map: {type, description?}。
     * <p>
     * <b>必须返回可变 Map</b>: 调用方可能需要在结果上继续追加字段 ——
     * {@link JsonArraySchema} 分支就要补 {@code items}(见 {@link #fromSchemaElement})。
     * 早前此处返回 {@code Map.of(...)} 不可变实例, 使数组类型 schema 转换抛
     * {@code UnsupportedOperationException}, 进而导致含数组参数的工具(web_extract)
     * 在注册期构建 ToolSpecification 失败、无法暴露给 LLM(功能静默失效)。
     */
    private static Map<String, Object> schemaMap(String type, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        if (description != null) {
            m.put("description", description);
        }
        return m;
    }
}
