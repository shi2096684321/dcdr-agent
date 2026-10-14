package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 技能市场 DTO (V3.1)。
 * <p>
 * <b>为什么单独一套 DTO 而不是复用 {@link SkillDtos}</b>: 这是<b>外部服务的视图</b>,
 * 不是本项目的数据模型 —— 它带 downloads/stars/第三方安全报告这类本地根本没有的概念,
 * 字段命名也随上游变化。把它们混进 {@code SkillDtos} 会让"我们的技能"与"别人的技能"
 * 的边界变模糊。两者之间唯一的桥是 {@link SkillDtos.SkillPackage}(导入时转换)。
 * <p>
 * 所有字段都是<b>尽力而为</b>的: 上游同一个含义在不同接口里字段名并不统一
 * (列表用 {@code iconUrl}、搜索用 {@code icon_url}; 列表有 {@code subCategories}、
 * 搜索没有), 故映射层做了别名容错, 缺字段一律降级为空/null 而不是报错。
 */
public final class SkillMarketDtos {

    private SkillMarketDtos() {
    }

    /** 市场分类(借用 SkillHub 的一级分类体系, 可直接用作本项目技能的 categoryId) */
    @Schema(description = "市场分类")
    @Data
    public static class MarketCategory {
        private String key;
        private String name;
        private String nameEn;
        private int sortOrder;
    }

    /**
     * 第三方安全扫描结论。
     * <p>
     * SkillHub 会给每个版本送两个平台扫描(keen / sanbu), 这是本项目自己造不出来的东西 ——
     * 项目没有审核环节, 这个结论只作为<b>导入前的参考信号</b>展示, 不替代本地判断。
     */
    @Schema(description = "第三方安全扫描结论")
    @Data
    public static class SecurityReport {
        /** 扫描平台标识(上游的字段名, 如 keen / sanbu) */
        private String platform;
        /** 结论: benign(安全) / queued(排队中) / 其它 */
        private String status;
        /** 结论文案(上游已本地化, 如 "安全，无风险") */
        private String statusText;
        /** 报告链接(可能为空或带时效签名) */
        private String reportUrl;
    }

    /** 市场技能列表项 */
    @Schema(description = "市场技能(列表项)")
    @Data
    public static class MarketSkillView {
        private String slug;
        @Schema(description = "命名空间 handle(可空); 与 slug 共同唯一确定一个技能")
        private String namespace;
        @Schema(description = "@namespace/slug")
        private String canonicalName;
        private String name;
        @Schema(description = "简介(中文优先, 上游 description_zh 缺失时回落到英文)")
        private String summary;
        private String category;
        private List<String> subCategories;
        private List<String> tags;
        private String version;
        private String iconUrl;
        /** 来源: community | clawhub | enterprise ... */
        private String source;
        @Schema(description = "上游页面地址(正文要人工去这里取)")
        private String sourceUrl;
        @Schema(description = "发布者/组织名(企业源才有), 用于展示与填入 author")
        private String publisher;
        private boolean publisherVerified;
        private long downloads;
        private long installs;
        private long stars;
        @Schema(description = "技能是否声明需要 API Key(来自上游 labels)")
        private boolean requiresApiKey;
        @Schema(description = "最近更新时间(「推荐」排序的新鲜度依据; 上游可能缺该字段)")
        private LocalDateTime updatedAt;

        /** 安全结论汇总(仅详情/版本接口返回, 列表为空) */
        private String securityStatus;
        private String securityStatusText;
        private List<SecurityReport> securityReports = List.of();

        @Schema(description = "本地是否已存在同名技能")
        private boolean installed;
        @Schema(description = "已安装时的本地技能 ID")
        private String installedSkillId;
    }

    /** 市场技能详情(列表项 + 版本历史) */
    @Schema(description = "市场技能详情")
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class MarketSkillDetail extends MarketSkillView {
        private String changelog;
        private long versionCount;
        private List<VersionView> versions = List.of();
    }

    /** 版本条目 */
    @Schema(description = "市场技能版本")
    @Data
    public static class VersionView {
        private String version;
        private LocalDateTime createdAt;
        private String changelog;
        private String securityStatus;
        private String securityStatusText;
    }

    /** 可选排序方式 */
    @Schema(description = "市场技能排序方式")
    @Data
    public static class SortOption {
        /** 对外 key(前端回传用) */
        private String key;
        private String label;
    }

    /** 分页结果(与 {@code TemplatePageView} 同口径) */
    @Schema(description = "市场技能分页结果")
    @Data
    public static class MarketPage {
        private List<MarketSkillView> items = List.of();
        private long total;
        /** 页码, 从 0 开始(与 /api/market/templates 一致) */
        private int page;
        private int pageSize;
        /**
         * 实际生效的排序 key。
         * <p>
         * 回显的意义: 非法/未知的排序值会被<b>静默回落</b>到推荐(见 {@code SkillMarketSort}),
         * 不回显的话前端无法区分"我传的排序生效了"和"被忽略了"。
         */
        private String sort;
        /**
         * 是否为「推荐」排序。
         * <p>
         * 为 true 时 {@link #total} 的语义与其它排序<b>不同</b>: 它是<b>候选池大小</b>
         * (本地评分只在池内进行), 而不是全库总数。前端据此改文案 ——
         * 否则用户会看到"共 100 个技能"而误以为整个市场只有 100 个。
         */
        private boolean recommended;
    }

    @Schema(description = "市场技能一键引入请求")
    @Data
    public static class InstallRequest {
        @Schema(description = "技能标识(slug)", requiredMode = Schema.RequiredMode.REQUIRED)
        private String slug;
        @Schema(description = "命名空间 handle(可选; 同名 slug 存在于多个命名空间时必须指定)")
        private String namespace;
        @Schema(description = "本地技能名覆盖(可选; 留空则用 slug 归一化后的结果)")
        private String name;
        @Schema(description = "同名技能是否覆盖(默认 false; 覆盖会丢弃你已补全的正文, 慎用)")
        private Boolean overwrite;
    }

    /**
     * 引入结果。
     * <p>
     * 注意 {@code enabled} 恒为 <b>false</b> —— 市场公开接口不发放技能正文, 故引入的是
     * "元数据骨架"。停用是刻意的: 没有正文的技能不该进入模型可见的技能目录。
     */
    @Schema(description = "市场技能引入结果")
    @Data
    public static class InstallResult {
        private String id;
        private String name;
        @Schema(description = "动作: created | overwritten")
        private String action;
        @Schema(description = "入库后是否启用; 市场引入恒为 false(正文待补全)")
        private boolean enabled;
        @Schema(description = "正文是否待补全")
        private boolean instructionsPending;
        @Schema(description = "上游来源页(去这里取正文)")
        private String sourceUrl;
        private List<String> warnings = List.of();
    }
}
