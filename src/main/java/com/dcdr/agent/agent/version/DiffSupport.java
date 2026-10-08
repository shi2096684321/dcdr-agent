package com.dcdr.agent.agent.version;

import com.dcdr.agent.dto.VersionDiffDtos;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * V3.1 F-44.1 版本 diff 公共支撑方法 (Agent / 工作流两个 differ 共用)。
 * <p>
 * 只做三件事: 构造差异项、列表值归一化、JSON 宽松解析。
 * 独立成类是为了让两侧 differ 只保留"比什么"这一件事, 方法都不长。
 */
public final class DiffSupport {

    private DiffSupport() {}

    /** 单个字段的展示截断上限 (仅影响 diff 展示, 不影响存储) */
    private static final int MAX_VALUE_LEN = 4000;

    /** 构造字段级差异项 (自动判定 changed; null 与空串视为相同) */
    public static VersionDiffDtos.Item item(String field, String label, String left, String right) {
        VersionDiffDtos.Item it = new VersionDiffDtos.Item();
        it.setField(field);
        it.setLabel(label);
        it.setLeft(clip(left));
        it.setRight(clip(right));
        it.setChanged(!Objects.equals(nullToEmpty(left), nullToEmpty(right)));
        return it;
    }

    public static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * 归一化"字符串列表"取值, 让 diff 忽略书写格式与顺序差异。
     * <p>
     * 与 {@code AgentDefinitionService.parseStrList} 的兼容口径一致: 兼容
     * JSON 数组 {@code ["a","b"]}、裸字符串 {@code a}、逗号分隔 {@code a, b} 三种历史形态;
     * 差异仅在于本方法会<b>排序</b> —— 工具顺序变化不应算作"配置变更"。
     */
    public static String normalizeList(String raw) {
        if (raw == null || raw.isBlank()) return "";
        List<String> items = new ArrayList<>();
        for (String part : raw.replaceAll("[\\[\\]\"]", "").split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) items.add(t);
        }
        Collections.sort(items);
        return String.join(", ", items);
    }

    /** 宽松解析 JSON 对象, 失败返回 null (版本对比不应因脏数据抛错) */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readMap(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 宽松解析 JSON 数组, 失败返回空列表 */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> readList(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            List<Map<String, Object>> list = mapper.readValue(json, List.class);
            return list == null ? Collections.emptyList() : list;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /** 取 Map 中的字符串值 (缺 key 或 null 返回空串) */
    public static String getStr(Map<String, Object> map, String key) {
        if (map == null) return "";
        Object v = map.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    /** 超长展示值截断 (避免 diff 响应被超长 prompt/步骤输入撑爆) */
    public static String clip(String s) {
        if (s == null) return "";
        if (s.length() <= MAX_VALUE_LEN) return s;
        return s.substring(0, MAX_VALUE_LEN) + " …(已截断, 共 " + s.length() + " 字符)";
    }
}
