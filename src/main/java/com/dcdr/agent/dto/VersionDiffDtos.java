package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * V3.1 F-44.1 版本对比 DTO (Agent 与工作流共用)。
 * <p>
 * 采用<b>结构化 diff</b> 而非文本行 diff: 行 diff 无法表达"哪个字段/哪个节点被改了",
 * 对 prompt 与 JSON 编排定义都没有可读性。此处输出:
 * <ul>
 *   <li>{@code fields}: 标量字段级差异 (名称/描述/角色设定/模型/工具/…)</li>
 *   <li>{@code graph}: 编排结构的节点/边级差异 (工作流专用, Agent 为 null)。
 *       统一承载 {@code dag_json}(节点+边) 与 {@code steps_json}(节点=步骤, 无边) 两种格式</li>
 * </ul>
 */
public class VersionDiffDtos {

    private VersionDiffDtos() {}

    /** 版本对比结果 */
    @Data
    @Schema(description = "版本对比结果 (F-44.1)")
    public static class View {
        @Schema(description = "资源类型: agent / workflow")
        private String resourceType;
        @Schema(description = "资源 ID")
        private String resourceId;
        @Schema(description = "左侧(较旧)版本号")
        private int leftVersion;
        @Schema(description = "右侧(较新)版本号")
        private int rightVersion;
        private LocalDateTime leftCreatedAt;
        private LocalDateTime rightCreatedAt;
        @Schema(description = "标量字段级差异")
        private List<Item> fields = new ArrayList<>();
        @Schema(description = "编排结构差异 (仅工作流)")
        private Graph graph;
    }

    /** 单个字段的差异项 */
    @Data
    @Schema(description = "字段级差异项")
    public static class Item {
        @Schema(description = "字段名")
        private String field;
        @Schema(description = "字段展示名")
        private String label;
        @Schema(description = "左侧取值")
        private String left;
        @Schema(description = "右侧取值")
        private String right;
        @Schema(description = "是否发生变化")
        private boolean changed;
    }

    /** 编排结构 (节点/边) 差异 */
    @Data
    @Schema(description = "编排结构差异")
    public static class Graph {
        @Schema(description = "新增节点 (节点 ID 或步骤名)")
        private List<String> addedNodes = new ArrayList<>();
        @Schema(description = "删除节点")
        private List<String> removedNodes = new ArrayList<>();
        @Schema(description = "修改节点")
        private List<NodeChange> modifiedNodes = new ArrayList<>();
        @Schema(description = "新增连线 (仅 DAG 格式; source->target)")
        private List<String> addedEdges = new ArrayList<>();
        @Schema(description = "删除连线 (仅 DAG 格式)")
        private List<String> removedEdges = new ArrayList<>();
        @Schema(description = "结构是否发生变化")
        private boolean structureChanged;
    }

    /** 节点内部字段的差异 */
    @Data
    @Schema(description = "节点差异")
    public static class NodeChange {
        @Schema(description = "节点 ID (DAG) 或步骤名 (steps)")
        private String nodeId;
        @Schema(description = "节点展示名")
        private String label;
        private List<Item> changes = new ArrayList<>();
    }
}
