package com.dcdr.agent.auth.ownership;

/**
 * 一个资源的归属快照 (V3.1 F-41 统一归属框架)。
 * <p>
 * 由 {@link ResourceOwnershipProvider#load} 从数据库读取, 只携带判定所需的最小信息,
 * 不暴露完整实体 —— 这样 Guard 与被保护的业务逻辑零耦合。
 *
 * @param ownerId     归属用户 ID; {@code null} 表示无归属(存量数据/平台级)
 * @param publicRead  所有已登录用户是否可读(如 PLATFORM 知识库、公开/模板 Agent)
 * @param publicWrite 所有已登录用户是否可写(通常为 false; 模板类资源仅 admin 可写)
 */
public record Ownership(String ownerId, boolean publicRead, boolean publicWrite) {

    /** 私有资源: 仅归属者可读写 */
    public static Ownership of(String ownerId) {
        return new Ownership(ownerId, false, false);
    }

    /** 公开可读、仅归属者可写(如公开 Agent / 平台知识库) */
    public static Ownership publicRead(String ownerId) {
        return new Ownership(ownerId, true, false);
    }

    /** 公开可读写(谨慎使用) */
    public static Ownership publicReadWrite(String ownerId) {
        return new Ownership(ownerId, true, true);
    }
}
