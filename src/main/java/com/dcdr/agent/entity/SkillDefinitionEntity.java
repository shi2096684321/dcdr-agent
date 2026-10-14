package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 技能定义实体 (V3.1 专家体系)。
 * <p>
 * <b>技能是什么</b>: 一段<b>按需加载</b>的领域知识包 —— 指令(最核心) + 参考资源 +
 * 所依赖的已注册工具清单。它解决的是"领域知识常驻 system prompt 会撑爆上下文"的问题:
 * 平时只把技能的 name/displayName/description 作为"技能目录"注入, 模型判断需要时
 * 才调用 {@code skill_load} 取回全文。
 * <p>
 * <b>技能 ≠ 插件(边界不可混)</b>:
 * <ul>
 *   <li>插件(F-37 {@code PluginLoader})是<b>可执行代码</b>(JAR), 装载前做危险 API 字节码审计,
 *       装载后注册成 {@code Tool}。它带来的是"新能力"。</li>
 *   <li>技能是<b>纯文本</b>, 不具备任何执行能力。需要跑代码时只能通过
 *       {@link #requiredTools} <b>引用</b>已注册的工具(例如沙箱工具), 而不是自带脚本。</li>
 * </ul>
 * 这条边界的意义: 技能包可以自由分享/在线分发(纯数据, 最坏是提示注入);
 * 一旦允许技能自带可执行内容, 分发就等于"远程投递任意代码", 必须额外一套审核与沙箱,
 * 本项目没有审核环节, 故从设计上就不支持。
 */
@Data
@Entity
@EntityListeners(OwnershipFillListener.class)
@Schema(description = "技能定义 (V3.1): 按需加载的领域知识包")
@Table(name = "skill_definitions",
        indexes = {
                @Index(name = "idx_skill_user", columnList = "userId"),
                @Index(name = "idx_skill_enabled", columnList = "enabled"),
                @Index(name = "idx_skill_category", columnList = "categoryId")
        },
        uniqueConstraints = @UniqueConstraint(name = "uk_skill_user_name", columnNames = {"userId", "name"}))
public class SkillDefinitionEntity implements OwnedEntity {

    @Schema(description = "主键 ID")
    @Id
    @Column(length = 64)
    private String id;

    @Schema(description = "归属用户 ID; 内置技能为 null")
    @Column(length = 64)
    private String userId;

    /**
     * 技能标识(kebab-case), 模型调用 {@code skill_load(name=...)} 时使用。
     * <p>
     * 同一用户下必须唯一 —— 否则"按名字加载"会产生歧义。
     */
    @Schema(description = "技能标识 (kebab-case, 模型按此名加载)")
    @Column(nullable = false, length = 128)
    private String name;

    @Schema(description = "展示名 (卡片标题)")
    @Column(length = 255)
    private String displayName;

    /**
     * 图标地址。
     * <p>
     * <b>只存 URL, 不存图片字节</b> —— 这与"技能是纯文本能力包"的边界一致:
     * 一旦技能表开始出现二进制(哪怕是内联 base64 的 png), 这条边界就破了,
     * 而它是"技能包可以自由分享"的前提。
     * <p>
     * 代价是<b>外链依赖</b>: 市场图标的 URL 指向上游 CDN, 上游轮换或防盗链会让图标失效。
     * 因此界面必须容忍加载失败(回落到首字母占位), 而不是留一个破图。
     * 本项目的先例同此取舍: {@code ReferImageStorage} 对远程 URL 也是原样返回、不下载。
     */
    @Schema(description = "图标 URL (仅地址, 不存图片字节; 允许为空)")
    @Column(length = 512)
    private String iconUrl;

    /**
     * 来源页地址(市场引入时来自上游)。
     * <p>
     * 存在的意义只有一条: <b>正文待补全时, 用户得知道去哪拿正文</b>。
     * 之前这个地址只写在占位正文里, 用户必须先打开技能读到那行才发现 —— 于是有了"列表上没有补全入口"的问题。
     * <p>
     * 注意"是否待补全"<b>不是</b>一个字段: 它由正文内容派生(见 {@code SkillService}),
     * 这样老技能无需迁移、用户替换正文后自动失效。
     */
    @Schema(description = "来源页 URL (正文待补全时用户去这里取正文; 允许为空)")
    @Column(length = 512)
    private String sourceUrl;

    /**
     * 来源标识 —— "这个技能是从哪来的", 如 {@code skillhub:@clawhub_shawnpana/browser-use}。
     * <p>
     * <b>为什么必须独立成字段, 而不是从正文里读</b>: 市场引入时占位正文里确实写过来源标识,
     * 但那行会随"用户补全正文"被整段替换掉 —— 来源信息就此<b>永久丢失</b>。
     * 而恰恰是这些技能最需要回溯来源: 正文是人工搬过来的, 出问题要拿原文核对。
     * <p>
     * 命名空间前缀({@code skillhub:})是为了将来能容纳别的来源而不混淆。
     * <p>
     * <b>只能由导入链路写</b>(见 {@code SkillService#markOrigin}): 它是系统记录的事实,
     * 不是用户内容, 所以刻意不放进用户可编辑的表单 DTO —— 否则一次普通编辑就会把它清成 null。
     */
    @Schema(description = "来源标识(如 skillhub:@ns/slug); 仅由导入/市场引入写入, 允许为空")
    @Column(length = 255)
    private String sourceRef;

    /**
     * 技能目录里唯一被常驻上下文的内容。
     * <p>
     * 因此它必须写得"能被模型判断是否需要", 而不是泛泛而谈 —— 它是唯一的触发信号。
     */
    @Schema(description = "简介 (常驻上下文, 模型据此判断是否需要加载)")
    @Column(columnDefinition = "TEXT")
    private String description;

    @Schema(description = "版本号")
    @Column(length = 32)
    private String version;

    @Schema(description = "作者")
    @Column(length = 128)
    private String author;

    @Schema(description = "分类 (技能市场筛选)")
    @Column(length = 64)
    private String categoryId;

    @Schema(description = "标签, JSON 数组字符串")
    @Column(columnDefinition = "TEXT")
    private String tags;

    /** 技能正文(SKILL.md)。仅在模型主动加载时才注入上下文, 不占常驻预算。 */
    @Schema(description = "技能指令正文 (SKILL.md; 按需加载, 不常驻上下文)")
    @Column(columnDefinition = "TEXT")
    private String instructions;

    /**
     * 参考资源: JSON 对象 {@code {"相对路径": "文本内容"}}。
     * <p>
     * 与指令分开存放的原因: 参考文档可能很大(动辄数十 KB), 只应在模型明确要看某一条时
     * 按 path 取回({@code skill_load(name, path)}), 而不是跟着指令一起灌进去。
     */
    @Schema(description = "参考资源 {\"路径\": \"内容\"} JSON, 按需加载")
    @Column(columnDefinition = "TEXT")
    private String resourcesJson;

    /**
     * 依赖的已注册工具名, JSON 数组字符串。
     * <p>
     * 这是<b>引用而非捆绑</b>: 技能声明"我需要 excel 能力", 实际能力由工具(内置/插件/沙箱)提供。
     * 导入时校验存在性 —— 缺失<b>只告警不阻断</b>, 因为技能的知识部分仍然可用。
     */
    @Schema(description = "依赖的已注册工具名, JSON 数组字符串")
    @Column(columnDefinition = "TEXT")
    private String requiredTools;

    /**
     * 是否声明需要可执行能力。
     * <p>
     * 注意: 本项目<b>不接受技能包自带脚本</b>, 该标记只用于界面提示与审计留痕,
     * 表示"这个技能的工作流里需要执行代码, 请确保沙箱/相关工具已启用"。
     */
    @Schema(description = "是否声明需要可执行能力 (不含代码, 仅提示走沙箱)", defaultValue = "false")
    @Column(nullable = false)
    private boolean hasExecutable = false;

    @Schema(description = "来源: local(自建/导入) | builtin(内置)", defaultValue = "local")
    @Column(nullable = false, length = 32)
    private String source = "local";

    @Schema(description = "是否公开 (公开后其他用户也能加载)", defaultValue = "false")
    @Column(nullable = false)
    private boolean isPublic = false;

    /** 停用的技能不进入"技能目录", 但仍保留定义(便于排查与重新启用)。 */
    @Schema(description = "是否启用; 停用后不进入技能目录", defaultValue = "true")
    @Column(nullable = false)
    private boolean enabled = true;

    @Schema(description = "创建时间")
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Schema(description = "最后更新时间")
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
