package com.dcdr.agent.skill.market;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SkillHub 技能市场配置 (V3.1)。
 * <p>
 * <b>SkillHub 是什么</b>: WorkBuddy 技能市场背后的服务, 提供分类/列表/搜索/详情/版本
 * 等<b>公开只读</b>接口 ({@code https://lightmake.site})。本项目把它当作一个
 * <b>可选的技能来源</b> —— 用于发现技能、借用它的分类体系与第三方安全扫描结论。
 * <p>
 * <b>默认关闭</b>: 与 {@code agent.v3.plugin}、{@code agent-tools.sandbox} 等同口径 ——
 * 这是一个<b>指向外部服务</b>的能力, 是否允许出网应由部署方显式决定, 而不是默认就往外连。
 * 关闭时所有市场接口直接返回明确提示, 不发起任何网络请求。
 * <p>
 * <b>导入的技能没有正文</b>: SkillHub 的公开接口只发放"发现层"(元数据),
 * 技能包正文走客户端私有通道({@code backend:skillhub-download-url} / {@code backend:skillhub-install}),
 * 实测 {@code /api/v1/skills/{slug}/download} 对 GET/POST 均返回 405, 详情与版本响应里
 * 也不含任何包地址。所以这里刻意<b>不假装能一键装好</b>: 导入产出的是"元数据骨架 +
 * 待补全占位正文", 并自动停用(详见 {@link SkillMarketService#install})。
 */
@Data
@ConfigurationProperties(prefix = "agent.skill.hub")
public class SkillHubProperties {

    /** 是否启用技能市场(默认关闭; 关闭时不发起任何外部请求) */
    private boolean enabled = false;

    /** SkillHub 服务地址 */
    private String baseUrl = "https://lightmake.site";

    /**
     * 出站响应超时(秒)。
     * <p>
     * 与 WorkBuddy 客户端保持一致(它自己是 10s) —— 市场是"锦上添花"的能力,
     * 不该让用户为了浏览市场而等一个慢请求。
     */
    private int timeoutSeconds = 10;

    /**
     * 分类/详情的内存缓存时长(秒)。
     * <p>
     * 分类几乎不变、详情变化很慢, 而这两者是最频繁被点开的; 缓存能把外部依赖
     * 从"每次点击"降为"每 TTL 一次", 也让上游抖动时页面仍可用。
     */
    private int cacheTtlSeconds = 300;

    /** 单次请求最多返回多少条(兜底上限, 防止前端传入超大 pageSize 打爆上游) */
    private int maxPageSize = 50;

    /** 出站 User-Agent(便于上游识别流量来源) */
    private String userAgent = "dcdr-agent/3.1 (skill-market)";
}
