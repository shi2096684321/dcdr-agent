package com.dcdr.agent.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能字段的 JSON 编解码 (V3.1 专家体系)。
 * <p>
 * 与 {@code AgentDefinitionService} 的 {@code serializeList/parseStrList} 保持同一约定:
 * <b>存储用 JSON 数组字符串, 读取时容忍历史/手写形态</b> —— 技能包由人手工编写与传阅,
 * 出现 {@code "a, b, c"} 这种写法是必然的, 直接抛异常会让导入体验极差。
 * <p>
 * 包级私有: 这些形态转换只在 skill 包内需要, 不对外暴露(避免成为又一个"通用 JSON 工具")。
 */
final class SkillJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SkillJson() {
    }

    /** List → JSON 数组字符串; 空列表统一存 null, 减少存储噪声 */
    static String writeList(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(list);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 列表字符串 → List&lt;String&gt;, 容忍三种形态:
     * <ol>
     *   <li>JSON 数组 {@code ["a","b"]} —— 本项目的标准写法;</li>
     *   <li>逗号分隔 {@code "a, b"} —— 手写技能包的常见写法;</li>
     *   <li>单值 {@code "a"}。</li>
     * </ol>
     */
    static List<String> readList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String s = raw.trim();
        if (s.startsWith("[")) {
            try {
                List<String> parsed = MAPPER.readValue(s, new TypeReference<List<String>>() { });
                return parsed == null ? List.of() : parsed.stream().filter(v -> v != null && !v.isBlank()).toList();
            } catch (Exception e) {
                // 落回下面的分隔符解析
            }
        }
        List<String> out = new ArrayList<>();
        for (String part : s.replace("[", "").replace("]", "").replace("\"", "").split(",")) {
            String v = part.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    /** Map → JSON 对象字符串; 空 Map 存 null */
    static String writeMap(Map<String, String> map) {
        if (map == null || map.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            return null;
        }
    }

    /** JSON 对象字符串 → Map; 解析失败返回空 Map(不抛异常 —— 资源解析失败不该让整个技能不可用) */
    static Map<String, String> readMap(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            Map<String, String> parsed = MAPPER.readValue(raw, new TypeReference<LinkedHashMap<String, String>>() { });
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
