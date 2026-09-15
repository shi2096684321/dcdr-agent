package com.dcdr.agent.entity;

/**
 * 归属实体契约 (V3.1 F-41 统一归属框架)。
 * <p>
 * 任何「属于某个用户」的实体实现本接口即可被 {@link OwnershipFillListener} 与
 * {@code OwnershipGuard} 统一识别。各实体的归属字段已统一命名为 {@code userId}
 * (Lombok {@code @Data} 会生成 {@code getUserId()}/{@code setUserId()}),
 * 因此实现类只需 {@code implements OwnedEntity}, <b>无需新增字段</b>。
 * <p>
 * 放在 {@code entity} 包而非 {@code auth.ownership} 包, 是为了让实体侧(监听器)不需要反向依赖
 * 认证包, 避免包级循环依赖。
 * <p>
 * <b>不实现本接口的例外</b>:
 * <ul>
 *   <li>归属字段命名不同的实体(如 {@code WorkflowVersionEntity.createdBy});</li>
 *   <li>无直接归属、需沿关联链推导的实体(如 {@code MessageEntity} 经 {@code sessionId} 推所属会话);</li>
 *   <li>可见性由多个布尔位共同决定的实体(如 {@code KnowledgeBaseEntity} 的 PLATFORM 级、
 *       {@code AgentDefinitionEntity} 的 isPublic/isTemplate)。</li>
 * </ul>
 * 以上情况由对应的 {@code ResourceOwnershipProvider} 自行解析, 不强制实现本接口。
 */
public interface OwnedEntity {

    /**
     * 归属用户 ID。
     *
     * @return 归属用户 ID; {@code null} 表示无归属(存量数据或平台级资源)
     */
    String getUserId();

    /**
     * 设置归属用户 ID。
     * <p>
     * 由 {@link OwnershipFillListener} 在 insert 前调用, 用于「调用方忘记显式设置归属」时兜底。
     * 各实体由 Lombok 自动实现, 无需手写。
     */
    void setUserId(String userId);
}
