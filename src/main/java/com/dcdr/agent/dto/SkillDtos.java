package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 技能 DTO (V3.1 专家体系)。
 */
public final class SkillDtos {

    private SkillDtos() {
    }

    @Schema(description = "创建/更新技能请求")
    @Data
    public static class SaveSkillRequest {
        @Schema(description = "技能标识 (kebab-case, 模型按此名加载)", requiredMode = Schema.RequiredMode.REQUIRED)
        private String name;
        @Schema(description = "展示名")
        private String displayName;
        @Schema(description = "图标 URL (仅地址, 不含图片字节; 留空时界面用首字母占位)")
        private String iconUrl;
        @Schema(description = "来源页 URL (正文待补全时用户去这里取正文; 可手工填写)")
        private String sourceUrl;
        @Schema(description = "简介 (唯一常驻上下文的内容, 需写清何时该用它)")
        private String description;
        @Schema(description = "版本号")
        private String version;
        @Schema(description = "作者")
        private String author;
        @Schema(description = "分类")
        private String categoryId;
        @Schema(description = "标签列表")
        private List<String> tags;
        @Schema(description = "技能指令正文 (SKILL.md)")
        private String instructions;
        @Schema(description = "参考资源 {\"路径\": \"内容\"}")
        private Map<String, String> resources;
        @Schema(description = "依赖的已注册工具名列表 (引用, 不捆绑)")
        private List<String> requiredTools;
        @Schema(description = "是否声明需要可执行能力 (仅提示, 技能包不含代码)")
        private Boolean hasExecutable;
        @Schema(description = "是否公开")
        private Boolean isPublic;
    }

    /**
     * 技能视图。
     * <p>
     * 列表接口不返回 {@code instructions} 与 {@code resources} 内容(可能数十 KB),
     * 只给 {@code resourcePaths} 便于界面展示"含哪些参考文档"; 详情接口返回全量。
     * 这与本项目 {@code llm_call_logs} 列表剔重字段的做法一致。
     */
    @Data
    public static class SkillView {
        private String id;
        private String userId;
        private String name;
        private String displayName;
        private String iconUrl;
        @Schema(description = "来源页 URL; 正文待补全时界面据此给出'去来源页'入口")
        private String sourceUrl;
        /**
         * 来源标识, 如 {@code skillhub:@clawhub_shawnpana/browser-use}; 为空表示本地自建。
         * <p>
         * 与 {@code source} 的分工: {@code source} 表达<b>权限归属</b>(builtin 不可删),
         * 这里表达<b>血缘</b>(从哪个外部技能来的)。两者正交 —— 市场引入的技能
         * {@code source} 仍是 local(它就是用户自己的一条技能), 但血缘指向上游。
         */
        @Schema(description = "来源标识; 为空表示本地自建")
        private String sourceRef;
        private String description;
        private String version;
        private String author;
        private String categoryId;
        private List<String> tags;
        private List<String> requiredTools;
        @Schema(description = "依赖的工具中当前未注册的部分 —— 界面据此提示'能力缺失'")
        private List<String> missingTools;
        private boolean hasExecutable;
        @Schema(description = "来源: local | builtin")
        private String source;
        @Schema(description = "是否公开")
        private boolean isPublic;
        private boolean enabled;
        /**
         * 正文是否仍是"待补全"的占位骨架。
         * <p>
         * <b>不是数据库字段, 由正文内容派生</b> —— 这样老技能无需数据迁移, 用户一旦把占位说明
         * 替换掉就自动不再是"待补全", 也不需要额外的保存动作。为 true 时界面应给出补全指引与来源页入口。
         */
        private boolean instructionsPending;
        /** 列表接口返回 null(体积控制); 详情接口返回全量 */
        private String instructions;
        /** 列表接口返回 null; 详情接口返回全量 */
        private Map<String, String> resources;
        @Schema(description = "参考资源的路径清单 (列表接口也返回)")
        private List<String> resourcePaths;
        private boolean editable;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    /**
     * 技能包(导入/导出的交换格式)。
     * <p>
     * <b>为什么用单个 JSON 而不是 zip</b>: 技能是纯文本能力包(指令 + 参考文本),
     * 没有二进制内容 —— 单文件 JSON 既可读、可 diff、可放进 git, 又省掉了 zip 的
     * 路径穿越/编码/大小校验等一堆边界问题。这是"技能不带可执行内容"这条边界的直接红利。
     */
    @Data
    public static class SkillPackage {
        @Schema(description = "包格式版本 (便于将来演进)")
        private String schema = "dcdr-skill/v1";
        private String name;
        private String displayName;
        @Schema(description = "图标 URL (仅地址, 不含图片字节)")
        private String iconUrl;
        @Schema(description = "来源页 URL (随包传给下一位使用者, 便于其补全正文)")
        private String sourceUrl;
        @Schema(description = "来源标识 (随包传递血缘, 便于下一位使用者回溯原始出处)")
        private String sourceRef;
        private String description;
        private String version;
        private String author;
        private String categoryId;
        private List<String> tags;
        @Schema(description = "技能指令正文 (SKILL.md)")
        private String instructions;
        @Schema(description = "参考资源 {\"相对路径\": \"文本内容\"}")
        private Map<String, String> resources;
        @Schema(description = "依赖的已注册工具名")
        private List<String> requiredTools;
        private Boolean hasExecutable;
    }

    @Schema(description = "导入技能包请求")
    @Data
    public static class ImportRequest {
        @Schema(description = "技能包 (JSON 对象; 也可直接在 body 里传包本体)")
        private SkillPackage skill;
        @Schema(description = "导入同名技能时是否覆盖 (默认 false: 同名直接拒绝, 避免静默改掉在用技能)")
        private Boolean overwrite;
    }

    @Schema(description = "导入结果")
    @Data
    public static class ImportResult {
        private String id;
        private String name;
        @Schema(description = "动作: created | overwritten")
        private String action;
        @Schema(description = "告警(如依赖工具缺失、资源被截断) —— 导入成功但需用户知晓")
        private List<String> warnings;
    }
}
