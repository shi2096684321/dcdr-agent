package com.dcdr.agent.agent.version;

import com.dcdr.agent.dto.VersionDiffDtos;
import com.dcdr.agent.entity.WorkflowVersionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * V3.1 F-44.1 工作流版本对比 (标量字段 + 编排结构)。
 * <p>
 * 编排结构统一成"节点/边"两个维度后对比, 两种存储格式都能覆盖:
 * <ul>
 *   <li>{@code dag_json} (F-32 可视化编排): 节点按 {@code id} 对齐, 边按 {@code source->target(label)} 对齐</li>
 *   <li>{@code steps_json} (V2.3 表单式): 节点 = 步骤, 按 {@code name} 对齐; 步骤间无显式连线, 故边为空</li>
 * </ul>
 * 输出用 {@link TreeMap} / {@link TreeSet} 保证顺序稳定 —— 否则 Map 的迭代顺序会让
 * 同一对版本的 diff 结果每次不同, 前端对比视图会闪烁。
 */
public final class WorkflowVersionDiffer {

    private static final String K_LABEL = "_label";
    private static final String K_TYPE = "_type";
    private static final String K_AGENT = "_agentId";
    private static final String K_INPUT = "_input";
    private static final String K_CONDITION = "_condition";

    private WorkflowVersionDiffer() {}

    /** 标量字段差异 */
    public static List<VersionDiffDtos.Item> diffFields(WorkflowVersionEntity left, WorkflowVersionEntity right) {
        List<VersionDiffDtos.Item> items = new ArrayList<>(4);
        items.add(DiffSupport.item("name", "名称", left.getName(), right.getName()));
        items.add(DiffSupport.item("description", "描述", left.getDescription(), right.getDescription()));
        items.add(DiffSupport.item("mode", "编排模式", left.getMode(), right.getMode()));
        items.add(DiffSupport.item("format", "编排格式", formatOf(left), formatOf(right)));
        return items;
    }

    /** 编排结构差异 */
    public static VersionDiffDtos.Graph diffGraph(ObjectMapper mapper,
                                                  WorkflowVersionEntity left,
                                                  WorkflowVersionEntity right) {
        Map<String, Map<String, Object>> leftNodes = nodesOf(mapper, left);
        Map<String, Map<String, Object>> rightNodes = nodesOf(mapper, right);
        VersionDiffDtos.Graph graph = new VersionDiffDtos.Graph();

        leftNodes.forEach((id, attrs) -> {
            if (!rightNodes.containsKey(id)) graph.getRemovedNodes().add(label(attrs, id));
        });
        rightNodes.forEach((id, attrs) -> {
            if (!leftNodes.containsKey(id)) graph.getAddedNodes().add(label(attrs, id));
        });
        leftNodes.forEach((id, attrs) -> {
            Map<String, Object> other = rightNodes.get(id);
            if (other == null) return;
            List<VersionDiffDtos.Item> changes = nodeChanges(attrs, other);
            if (changes.isEmpty()) return;
            VersionDiffDtos.NodeChange change = new VersionDiffDtos.NodeChange();
            change.setNodeId(id);
            change.setLabel(label(other, id));
            change.setChanges(changes);
            graph.getModifiedNodes().add(change);
        });

        Set<String> leftEdges = edgesOf(mapper, left);
        Set<String> rightEdges = edgesOf(mapper, right);
        leftEdges.forEach(e -> {
            if (!rightEdges.contains(e)) graph.getRemovedEdges().add(e);
        });
        rightEdges.forEach(e -> {
            if (!leftEdges.contains(e)) graph.getAddedEdges().add(e);
        });

        graph.setStructureChanged(!graph.getAddedNodes().isEmpty() || !graph.getRemovedNodes().isEmpty()
                || !graph.getModifiedNodes().isEmpty()
                || !graph.getAddedEdges().isEmpty() || !graph.getRemovedEdges().isEmpty());
        return graph;
    }

    // ===== 内部: 结构提取 =====

    /** 节点 ID → 归一化属性; DAG 用节点 id, steps 用步骤名 */
    private static Map<String, Map<String, Object>> nodesOf(ObjectMapper mapper, WorkflowVersionEntity v) {
        Map<String, Map<String, Object>> nodes = new TreeMap<>();
        if (hasDag(v)) {
            Map<String, Object> dag = DiffSupport.readMap(mapper, v.getDagJson());
            for (Map<String, Object> node : childList(dag, "nodes")) {
                String id = DiffSupport.getStr(node, "id");
                if (id.isEmpty()) continue;
                Map<String, Object> data = childMap(node, "data");
                Map<String, Object> attrs = new LinkedHashMap<>();
                attrs.put(K_LABEL, DiffSupport.getStr(data, "label"));
                attrs.put(K_TYPE, DiffSupport.getStr(node, "type"));
                attrs.put(K_AGENT, DiffSupport.getStr(data, "agentId"));
                attrs.put(K_INPUT, DiffSupport.getStr(data, "input"));
                attrs.put(K_CONDITION, DiffSupport.getStr(data, "condition"));
                nodes.put(id, attrs);
            }
            return nodes;
        }
        for (Map<String, Object> step : DiffSupport.readList(mapper, v.getStepsJson())) {
            String name = DiffSupport.getStr(step, "name");
            if (name.isEmpty()) continue;
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put(K_LABEL, name);
            attrs.put(K_AGENT, DiffSupport.getStr(step, "agentId"));
            attrs.put(K_INPUT, DiffSupport.getStr(step, "input"));
            attrs.put(K_CONDITION, DiffSupport.getStr(step, "condition"));
            nodes.put(name, attrs);
        }
        return nodes;
    }

    /** 边集合; steps 格式无显式连线, 返回空集 */
    private static Set<String> edgesOf(ObjectMapper mapper, WorkflowVersionEntity v) {
        Set<String> edges = new TreeSet<>();
        if (!hasDag(v)) return edges;
        Map<String, Object> dag = DiffSupport.readMap(mapper, v.getDagJson());
        for (Map<String, Object> edge : childList(dag, "edges")) {
            String source = DiffSupport.getStr(edge, "source");
            String target = DiffSupport.getStr(edge, "target");
            if (source.isEmpty() || target.isEmpty()) continue;
            String label = DiffSupport.getStr(edge, "label");
            edges.add(label.isEmpty() ? source + "->" + target : source + "->" + target + "(" + label + ")");
        }
        return edges;
    }

    /** 节点内部仅列出发生变化的字段 */
    private static List<VersionDiffDtos.Item> nodeChanges(Map<String, Object> left, Map<String, Object> right) {
        List<VersionDiffDtos.Item> items = new ArrayList<>(4);
        addIfChanged(items, "type", "节点类型", left, right, K_TYPE);
        addIfChanged(items, "agentId", "Agent", left, right, K_AGENT);
        addIfChanged(items, "input", "输入", left, right, K_INPUT);
        addIfChanged(items, "condition", "条件", left, right, K_CONDITION);
        return items;
    }

    private static void addIfChanged(List<VersionDiffDtos.Item> items, String field, String label,
                                     Map<String, Object> left, Map<String, Object> right, String key) {
        VersionDiffDtos.Item item = DiffSupport.item(field, label,
                get(left, key), get(right, key));
        if (item.isChanged()) items.add(item);
    }

    private static String get(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        return DiffSupport.str(v);
    }

    private static String label(Map<String, Object> attrs, String fallback) {
        String l = get(attrs, K_LABEL);
        return l.isEmpty() ? fallback : l;
    }

    private static boolean hasDag(WorkflowVersionEntity v) {
        return v != null && v.getDagJson() != null && !v.getDagJson().isBlank();
    }

    private static String formatOf(WorkflowVersionEntity v) {
        return hasDag(v) ? "DAG (dag_json)" : "步骤 (steps_json)";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> childList(Map<String, Object> parent, String key) {
        if (parent == null) return List.of();
        Object v = parent.get(key);
        if (!(v instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) result.add((Map<String, Object>) m);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> childMap(Map<String, Object> parent, String key) {
        if (parent == null) return Map.of();
        Object v = parent.get(key);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
