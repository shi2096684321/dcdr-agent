package com.dcdr.agent.agent.version;

import com.dcdr.agent.dto.VersionDiffDtos;
import com.dcdr.agent.entity.AgentDefinitionVersionEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * V3.1 F-44.1 Agent 版本对比 (字段级)。
 * <p>
 * 逐个对比定义列。两处刻意的口径处理:
 * <ul>
 *   <li>工具 / 知识库列表先归一化再比 —— 顺序变化不应算作"配置变更"
 *       ({@link DiffSupport#normalizeList})</li>
 *   <li>null 与空串视为相同, 避免"清空 vs 未设置"产生噪声差异</li>
 * </ul>
 * 不做文本行 diff: 行 diff 无法回答"哪个字段改了", 对 prompt 尤其没有可读性。
 */
public final class AgentVersionDiffer {

    /** 定义列 → 展示名 (顺序即前端展示顺序) */
    private static final String[][] FIELDS = {
            {"name", "名称"},
            {"description", "描述"},
            {"avatar", "头像"},
            {"persona", "角色设定"},
            {"model", "模型"},
            {"tools", "工具"},
            {"kbIds", "知识库"},
            {"temperature", "温度"},
            {"isPublic", "公开"},
            {"isTemplate", "预设模板"}
    };

    private AgentVersionDiffer() {}

    /** 对比两个 Agent 版本快照, 返回全部字段的差异项(含未变化的, 由 changed 标记区分) */
    public static List<VersionDiffDtos.Item> diff(AgentDefinitionVersionEntity left,
                                                  AgentDefinitionVersionEntity right) {
        List<VersionDiffDtos.Item> items = new ArrayList<>(FIELDS.length);
        for (String[] f : FIELDS) {
            items.add(DiffSupport.item(f[0], f[1], value(left, f[0]), value(right, f[0])));
        }
        return items;
    }

    /** 按字段名取快照值 (列表字段做归一化) */
    private static String value(AgentDefinitionVersionEntity v, String field) {
        if (v == null) return "";
        return switch (field) {
            case "name" -> DiffSupport.str(v.getName());
            case "description" -> DiffSupport.str(v.getDescription());
            case "avatar" -> DiffSupport.str(v.getAvatar());
            case "persona" -> DiffSupport.str(v.getSystemPrompt());
            case "model" -> DiffSupport.str(v.getModel());
            case "tools" -> DiffSupport.normalizeList(v.getTools());
            case "kbIds" -> DiffSupport.normalizeList(v.getKbIds());
            case "temperature" -> DiffSupport.str(v.getTemperature());
            case "isPublic" -> DiffSupport.str(v.isPublic());
            case "isTemplate" -> DiffSupport.str(v.isTemplate());
            default -> "";
        };
    }
}
