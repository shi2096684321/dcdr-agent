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
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link JsonSchemaConverter} 单元测试(V3.2)。
 *
 * <p>纯单元测试: 全静态方法、无状态、零外部依赖, 不启 Spring 上下文。
 *
 * <p><b>为什么这个类必须重点测</b>: 它位于"工具定义 → 暴露给 LLM"的核心链路上
 * (ToolRegistry 注册期 / IntentRouter 路由 / 各 Provider 结构化输出都经过它), 且
 * <b>已经因它出过一次线上级缺陷</b> —— {@code fromSchemaElement} 的数组分支
 * 需要往 {@code schemaMap(...)} 返回的 Map 里继续追加 {@code items}, 而该 Map 曾是不可变的
 * {@code Map.of(...)}, 导致 {@code UnsupportedOperationException} 被 ToolRegistry 的 catch 吞掉,
 * 使得含数组参数的工具({@code web_extract})注册成功却<b>对 LLM 不可见</b>。
 *
 * <p>故本测试重点锁住两件事: (1) 数组 → Map 的反向转换<b>必须成功且带上 items</b>,
 * 尤其在<b>没有 description</b> 时(旧实现必炸的那条路径); (2) 正反向往返不丢信息。
 */
class JsonSchemaConverterTest {

    // ==================================================================
    //  正向: OpenAI 风格 Map → 框架类型化元素
    // ==================================================================

    @Nested
    @DisplayName("toSchemaElement 正向转换")
    class ToSchemaElement {

        @Test
        @DisplayName("非 Map 输入返回 null(调用方据此跳过该属性, 不阻断其他属性)")
        void nonMapReturnsNull() {
            assertNull(JsonSchemaConverter.toSchemaElement(null));
            assertNull(JsonSchemaConverter.toSchemaElement("string"));
            assertNull(JsonSchemaConverter.toSchemaElement(List.of("a")));
            assertNull(JsonSchemaConverter.toSchemaElement(42));
        }

        @Test
        @DisplayName("基础类型各自映射到对应元素")
        void basicTypes() {
            assertInstanceOf(JsonStringSchema.class,
                    JsonSchemaConverter.toSchemaElement(Map.of("type", "string")));
            assertInstanceOf(JsonIntegerSchema.class,
                    JsonSchemaConverter.toSchemaElement(Map.of("type", "integer")));
            assertInstanceOf(JsonNumberSchema.class,
                    JsonSchemaConverter.toSchemaElement(Map.of("type", "number")));
            assertInstanceOf(JsonBooleanSchema.class,
                    JsonSchemaConverter.toSchemaElement(Map.of("type", "boolean")));
        }

        @Test
        @DisplayName("description 被保留")
        void keepsDescription() {
            JsonStringSchema s = (JsonStringSchema) JsonSchemaConverter.toSchemaElement(
                    Map.of("type", "string", "description", "用户名"));
            assertEquals("用户名", s.description());
        }

        @Test
        @DisplayName("enum 优先于 type(字符串枚举)")
        void enumTakesPrecedence() {
            JsonEnumSchema e = (JsonEnumSchema) JsonSchemaConverter.toSchemaElement(
                    Map.of("type", "string", "enum", List.of("a", "b")));
            assertEquals(List.of("a", "b"), e.enumValues());
        }

        @Test
        @DisplayName("$ref 映射为引用元素")
        void refMapsToReference() {
            JsonReferenceSchema r = (JsonReferenceSchema) JsonSchemaConverter.toSchemaElement(
                    Map.of("$ref", "#/definitions/Foo"));
            assertEquals("#/definitions/Foo", r.reference());
        }

        @Test
        @DisplayName("可空联合类型 [string,null] 映射为 anyOf")
        void nullableUnionBecomesAnyOf() {
            JsonAnyOfSchema anyOf = (JsonAnyOfSchema) JsonSchemaConverter.toSchemaElement(
                    Map.of("type", List.of("string", "null")));
            assertEquals(2, anyOf.anyOf().size());
            assertInstanceOf(JsonStringSchema.class, anyOf.anyOf().get(0));
            assertInstanceOf(JsonNullSchema.class, anyOf.anyOf().get(1));
        }

        @Test
        @DisplayName("单元素 type 列表退化为该类型(不包 anyOf)")
        void singleElementTypeListDegrades() {
            assertInstanceOf(JsonStringSchema.class,
                    JsonSchemaConverter.toSchemaElement(Map.of("type", List.of("string"))));
        }

        @Test
        @DisplayName("无法识别的 type 列表返回 null")
        void unrecognizedTypeListReturnsNull() {
            assertNull(JsonSchemaConverter.toSchemaElement(Map.of("type", List.of("weird"))));
        }

        @Test
        @DisplayName("type 缺省时按结构推断: 有 properties 视为 object")
        void infersObjectFromProperties() {
            JsonObjectSchema o = (JsonObjectSchema) JsonSchemaConverter.toSchemaElement(
                    Map.of("properties", Map.of("a", Map.of("type", "string"))));
            assertNotNull(o.properties());
            assertEquals(1, o.properties().size());
        }

        @Test
        @DisplayName("type 缺省且无 properties 返回 null")
        void noTypeNoPropertiesReturnsNull() {
            assertNull(JsonSchemaConverter.toSchemaElement(Map.of("unknown", 1)));
        }

        @Test
        @DisplayName("未知 type 返回 null")
        void unknownTypeReturnsNull() {
            assertNull(JsonSchemaConverter.toSchemaElement(Map.of("type", "weird")));
        }

        @Test
        @DisplayName("object: properties / required / additionalProperties 全部保留")
        void objectKeepsAllFacets() {
            JsonObjectSchema o = (JsonObjectSchema) JsonSchemaConverter.toSchemaElement(Map.of(
                    "type", "object",
                    "properties", Map.of("city", Map.of("type", "string", "description", "城市")),
                    "required", List.of("city"),
                    "additionalProperties", false));

            assertEquals(1, o.properties().size());
            assertEquals(List.of("city"), o.required());
            assertEquals(Boolean.FALSE, o.additionalProperties());
        }

        @Test
        @DisplayName("object 内嵌 array 参数(工具注册最典型的形态)")
        void objectWithArrayProperty() {
            JsonObjectSchema o = (JsonObjectSchema) JsonSchemaConverter.toSchemaElement(Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "urls", Map.of("type", "array",
                                    "items", Map.of("type", "string"),
                                    "description", "URL 列表"))));

            JsonArraySchema urls = (JsonArraySchema) o.properties().get("urls");
            assertInstanceOf(JsonStringSchema.class, urls.items());
            assertEquals("URL 列表", urls.description());
        }

        @Test
        @DisplayName("array 无 items 时 items 为空(不抛异常)")
        void arrayWithoutItems() {
            JsonArraySchema a = (JsonArraySchema) JsonSchemaConverter.toSchemaElement(
                    Map.of("type", "array"));
            assertNull(a.items());
        }

        @Test
        @DisplayName("工具顶层非 object 时退化为空 object(等价无参数工具, 不抛异常)")
        void toObjectSchemaDegradesToEmpty() {
            // 注: LangChain4j 的 JsonObjectSchema.builder().build() 其 properties() 是"空 Map"而非 null
            JsonObjectSchema o = JsonSchemaConverter.toObjectSchema(Map.of("type", "string"));
            assertNotNull(o);
            assertEquals(0, o.properties().size(), "非 object 输入应退化为无属性的空 object");

            JsonObjectSchema empty = JsonSchemaConverter.toObjectSchema(Map.of("bogus", 1));
            assertNotNull(empty);
            assertEquals(0, empty.properties().size());
        }
    }

    // ==================================================================
    //  反向: 框架类型化元素 → OpenAI 风格 Map
    // ==================================================================

    @Nested
    @DisplayName("fromSchemaElement 反向转换")
    class FromSchemaElement {

        @Test
        @DisplayName("null 元素返回 null")
        void nullElementReturnsNull() {
            assertNull(JsonSchemaConverter.fromSchemaElement(null));
        }

        @Test
        @DisplayName("基础类型输出 {type} / {type, description}")
        void basicTypes() {
            assertEquals(Map.of("type", "string"),
                    JsonSchemaConverter.fromSchemaElement(JsonStringSchema.builder().build()));
            assertEquals(Map.of("type", "string", "description", "用户名"),
                    JsonSchemaConverter.fromSchemaElement(
                            JsonStringSchema.builder().description("用户名").build()));
            assertEquals(Map.of("type", "boolean"),
                    JsonSchemaConverter.fromSchemaElement(JsonBooleanSchema.builder().build()));
        }

        @Test
        @DisplayName("null 元素类型输出 {type: null}")
        void nullSchemaType() {
            assertEquals(Map.of("type", "null"),
                    JsonSchemaConverter.fromSchemaElement(new JsonNullSchema()));
        }

        @Test
        @DisplayName("enum 输出 {type: string, enum: [...]}")
        void enumSchema() {
            Map<String, Object> m = JsonSchemaConverter.fromSchemaElement(
                    JsonEnumSchema.builder().enumValues(List.of("a", "b")).build());
            assertEquals("string", m.get("type"));
            assertEquals(List.of("a", "b"), m.get("enum"));
        }

        @Test
        @DisplayName("$ref 输出 {$ref}")
        void referenceSchema() {
            assertEquals(Map.of("$ref", "#/definitions/Foo"),
                    JsonSchemaConverter.fromSchemaElement(
                            JsonReferenceSchema.builder().reference("#/definitions/Foo").build()));
        }

        @Test
        @DisplayName("只有一个分支的 anyOf 直接展开为该分支")
        void singleBranchAnyOfUnwraps() {
            Map<String, Object> m = JsonSchemaConverter.fromSchemaElement(
                    JsonAnyOfSchema.builder()
                            .anyOf(List.of(JsonStringSchema.builder().build()))
                            .build());
            assertEquals("string", m.get("type"));
        }

        @Test
        @DisplayName("多分支 anyOf 输出 {anyOf: [...]}")
        void multiBranchAnyOf() {
            Map<String, Object> m = JsonSchemaConverter.fromSchemaElement(
                    JsonAnyOfSchema.builder()
                            .anyOf(List.of(JsonStringSchema.builder().build(), new JsonNullSchema()))
                            .build());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> alternatives = (List<Map<String, Object>>) m.get("anyOf");
            assertEquals(2, alternatives.size());
            assertEquals("string", alternatives.get(0).get("type"));
            assertEquals("null", alternatives.get(1).get("type"));
        }

        @Test
        @DisplayName("回归: array 无 description 时仍能写入 items")
        void arrayWithoutDescriptionStillGetsItems() {
            // 旧实现中 schemaMap 返回不可变 Map.of(...), 此处会抛 UnsupportedOperationException,
            // 导致 ToolRegistry 注册 web_extract 失败、该工具对 LLM 不可见。
            // 本断言即是该缺陷的回归防线。
            JsonArraySchema array = JsonArraySchema.builder()
                    .items(JsonStringSchema.builder().build())
                    .build();

            Map<String, Object> m = JsonSchemaConverter.fromSchemaElement(array);

            assertEquals("array", m.get("type"));
            assertNotNull(m.get("items"), "items 必须写入(不可变 Map 会在此抛异常)");
            @SuppressWarnings("unchecked")
            Map<String, Object> items = (Map<String, Object>) m.get("items");
            assertEquals("string", items.get("type"));
        }

        @Test
        @DisplayName("array 有 description 时 description 与 items 并存")
        void arrayWithDescriptionKeepsBoth() {
            JsonArraySchema array = JsonArraySchema.builder()
                    .items(JsonStringSchema.builder().build())
                    .description("URL 列表")
                    .build();

            Map<String, Object> m = JsonSchemaConverter.fromSchemaElement(array);

            assertEquals("array", m.get("type"));
            assertEquals("URL 列表", m.get("description"));
            assertNotNull(m.get("items"));
        }

        @Test
        @DisplayName("array 无 items 时只输出 type(可带 description)")
        void arrayWithoutItems() {
            assertEquals(Map.of("type", "array"),
                    JsonSchemaConverter.fromSchemaElement(JsonArraySchema.builder().build()));
        }

        @Test
        @DisplayName("object 还原 properties / required / additionalProperties")
        void objectSchema() {
            JsonObjectSchema schema = JsonObjectSchema.builder()
                    .addProperty("city", JsonStringSchema.builder().description("城市").build())
                    .required(List.of("city"))
                    .additionalProperties(false)
                    .build();

            Map<String, Object> m = JsonSchemaConverter.fromObjectSchema(schema);

            assertEquals("object", m.get("type"));
            assertEquals(List.of("city"), m.get("required"));
            assertEquals(Boolean.FALSE, m.get("additionalProperties"));
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) m.get("properties");
            assertEquals("string", ((Map<?, ?>) props.get("city")).get("type"));
        }

        @Test
        @DisplayName("空 object 不输出 properties / required 键")
        void emptyObjectOmitsKeys() {
            Map<String, Object> m = JsonSchemaConverter.fromObjectSchema(
                    JsonObjectSchema.builder().build());

            assertEquals("object", m.get("type"));
            assertNull(m.get("properties"));
            assertNull(m.get("required"));
        }
    }

    // ==================================================================
    //  往返一致性 + 真实缺陷场景
    // ==================================================================

    @Nested
    @DisplayName("往返一致性")
    class RoundTrip {

        @Test
        @DisplayName("回归: web_extract 形态的 schema(含数组参数)可完整走通正向 + 反向")
        void webExtractShapeSurvivesBothDirections() {
            // 构造与 WebExtractTool.getParameters() 等价的 schema, 走完
            // 框架类型 → OpenAI Map 的反向转换 —— 这正是当初抛异常的路径。
            JsonObjectSchema schema = JsonObjectSchema.builder()
                    .addProperty("urls", JsonArraySchema.builder()
                            .items(JsonStringSchema.builder()
                                    .description("要抓取的完整 URL, 必须以 http:// 或 https:// 开头")
                                    .build())
                            .description("待抓取的 URL 列表")
                            .build())
                    .addProperty("query", JsonStringSchema.builder()
                            .description("可选: 用户原始问题")
                            .build())
                    .required(List.of("urls"))
                    .build();

            Map<String, Object> m = JsonSchemaConverter.fromObjectSchema(schema);

            assertEquals("object", m.get("type"));
            assertEquals(List.of("urls"), m.get("required"));

            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) m.get("properties");
            assertNotNull(props);
            @SuppressWarnings("unchecked")
            Map<String, Object> urls = (Map<String, Object>) props.get("urls");
            assertEquals("array", urls.get("type"));
            assertNotNull(urls.get("items"), "数组参数的 items 必须存在, 否则 LLM 无法正确构造入参");
            assertEquals("string", ((Map<?, ?>) urls.get("items")).get("type"));
        }

        @Test
        @DisplayName("OpenAI Map → 框架类型 → OpenAI Map 不丢关键信息")
        void mapToTypedAndBack() {
            Map<String, Object> original = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "query", Map.of("type", "string", "description", "检索词"),
                            "tags", Map.of("type", "array",
                                    "items", Map.of("type", "string"))),
                    "required", List.of("query"));

            JsonObjectSchema typed = JsonSchemaConverter.toObjectSchema(original);
            Map<String, Object> back = JsonSchemaConverter.fromObjectSchema(typed);

            assertEquals("object", back.get("type"));
            assertEquals(List.of("query"), back.get("required"));

            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) back.get("properties");
            assertEquals("string", ((Map<?, ?>) props.get("query")).get("type"));
            assertEquals("检索词", ((Map<?, ?>) props.get("query")).get("description"));
            @SuppressWarnings("unchecked")
            Map<String, Object> tags = (Map<String, Object>) props.get("tags");
            assertEquals("array", tags.get("type"));
            assertEquals("string", ((Map<?, ?>) tags.get("items")).get("type"));
        }
    }
}
