package com.dcdr.agent.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能字段 JSON 编解码单元测试 (V3.1 专家体系)。
 *
 * <p>这个类的价值全在"<b>容忍度</b>"上: 技能包的字段是人手工写的(市场下载 / 同事传阅 /
 * 模型生成), 出现 {@code "calculator, time"} 这种逗号分隔写法是必然的。
 * 如果解析器只认严格 JSON, 表现就是"导入报错但看不出哪里错" —— 这类问题的排查成本
 * 远高于这里多做一层兼容。
 */
class SkillJsonTest {

    @Test
    @DisplayName("标准 JSON 数组双向一致")
    void jsonArrayRoundTrip() {
        List<String> src = List.of("calculator", "web_search");
        String stored = SkillJson.writeList(src);
        assertEquals(List.of("calculator", "web_search"), SkillJson.readList(stored));
    }

    @Test
    @DisplayName("空列表统一存 null, 不制造 [] 噪声")
    void emptyListStoredAsNull() {
        assertNull(SkillJson.writeList(List.of()));
        assertNull(SkillJson.writeList(null));
        assertEquals(List.of(), SkillJson.readList(null));
        assertEquals(List.of(), SkillJson.readList("   "));
    }

    @Test
    @DisplayName("兼容手写形态: 逗号分隔 / 带方括号 / 带引号 / 多余空白")
    void toleratesHandWrittenForms() {
        assertEquals(List.of("a", "b", "c"), SkillJson.readList("a, b , c"));
        assertEquals(List.of("a", "b"), SkillJson.readList("[a, b]"));
        assertEquals(List.of("a", "b"), SkillJson.readList("[\"a\",\"b\"]"));
        assertEquals(List.of("solo"), SkillJson.readList("solo"));
    }

    @Test
    @DisplayName("列表解析剔除空项(避免空串进入工具校验)")
    void dropsBlankItems() {
        assertEquals(List.of("a", "b"), SkillJson.readList("a,,b,  "));
    }

    @Test
    @DisplayName("资源 Map 双向一致, 保持插入顺序")
    void mapRoundTripKeepsOrder() {
        Map<String, String> src = new LinkedHashMap<>();
        src.put("references/a.md", "甲");
        src.put("references/b.md", "乙");
        Map<String, String> back = SkillJson.readMap(SkillJson.writeMap(src));
        assertEquals(List.of("references/a.md", "references/b.md"), List.copyOf(back.keySet()));
        assertEquals("甲", back.get("references/a.md"));
    }

    @Test
    @DisplayName("资源解析失败不抛异常(宁可少一份资源, 也不让整个技能不可用)")
    void brokenMapJsonDegradesToEmpty() {
        assertTrue(SkillJson.readMap("{ 这不是 JSON").isEmpty());
        assertTrue(SkillJson.readMap(null).isEmpty());
        assertTrue(SkillJson.writeMap(Map.of()) == null);
    }
}
