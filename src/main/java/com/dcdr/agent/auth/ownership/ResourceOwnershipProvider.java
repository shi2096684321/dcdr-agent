package com.dcdr.agent.auth.ownership;

import java.util.Objects;

/**
 * 某类资源「归属信息加载 + 访问策略」的提供者 (V3.1 F-41 统一归属框架)。
 * <p>
 * <b>实现约束</b>: 实现类只能依赖 Repository(以及其它无状态工具), <b>不得依赖业务 Service</b> ——
 * Service 会调用 {@link OwnershipGuard}, 若 Provider 反向依赖 Service 将形成循环依赖。
 * <p>
 * 每个 {@link ResourceType} 必须有且仅有一个实现, 由 Spring 自动收集进 {@link OwnershipRegistry};
 * 缺失或重复会在启动期直接失败(fail-fast), 避免「新资源忘了接归属校验」被静默放过。
 */
public interface ResourceOwnershipProvider {

    /** 本 Provider 负责的资源类型 */
    ResourceType type();

    /**
     * 按主键加载资源归属快照。
     *
     * @param id 资源主键
     * @return 归属快照; 资源不存在时返回 {@code null}
     */
    Ownership load(String id);

    /**
     * 归属判定策略。默认口径(全项目统一):
     * <ol>
     *   <li>admin → 放行一切;</li>
     *   <li>匿名(userId == null) → 仅在认证关闭(V2.1 兼容模式)时放行, 否则拒绝;</li>
     *   <li>公开可读/可写位命中 → 放行;</li>
     *   <li>归属者本人 → 放行。</li>
     * </ol>
     * 特例资源(如知识库 PLATFORM 级只读、Agent 模板仅 admin 可改)通过
     * {@link Ownership} 的 publicRead/publicWrite 表达即可, 通常无需覆写本方法。
     *
     * @param ownership 资源归属快照
     * @param ctx       当前请求鉴权上下文
     * @param write     true 表示写操作(创建/更新/删除), false 表示读操作
     */
    default boolean canAccess(Ownership ownership, AuthContext ctx, boolean write) {
        if (ctx.admin()) return true;
        if (ctx.userId() == null) return ctx.anonymousAllowed();
        if (write ? ownership.publicWrite() : ownership.publicRead()) return true;
        return Objects.equals(ownership.ownerId(), ctx.userId());
    }

    /**
     * 资源不存在时的提示语。
     * <p>
     * 越权与不存在使用<b>同一提示</b>(「越权即不存在」原则), 防止攻击者通过
     * 403/404 差异枚举他人资源 ID。
     */
    default String notFoundMessage(String id) {
        return type().label() + "不存在";
    }
}
