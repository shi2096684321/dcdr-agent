package com.dcdr.agent.auth.security;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * V2.2 RBAC 权限模型 (F-23)。
 * <p>
 * 三层模型: User → Role → Permission。权限粒度到资源:操作级别,
 * 由 Spring Security 的 {@code GrantedAuthority} 承载, 配合 {@code @PreAuthorize("hasAuthority('xxx')")} 使用。
 * <p>
 * 权限编码规则: {@code 资源:操作}, 如 {@code kb:write} 表示知识库写入权限。
 * 角色用 {@code ROLE_} 前缀(Spring Security 约定), 如 {@code ROLE_ADMIN}。
 *
 * <pre>
 * 角色权限映射 (文档 §4.3 F-23):
 *   admin  → 全部权限 + ROLE_ADMIN (含知识库管理)
 *   user   → chat:chat, session:read, session:write, kb:read, kb:write, agent:create,
 *             agent:read, agent:write, workflow:execute, schedule:manage + ROLE_USER
 *   guest  → chat:chat + ROLE_GUEST
 * </pre>
 * <p>
 * 知识库管理(kb:read/kb:write): user 与 admin 均拥有; 普通用户可创建/管理自己的
 * USER 级知识库, PLATFORM 级知识库仅 admin 可改、普通用户只读(仅限已登录用户, guest 无此权限)。
 * <p>
 * V2.3 新增权限: agent:read / agent:write (自定义 Agent CRUD),
 * workflow:execute (工作流编排), schedule:manage (定时任务管理); user 与 admin 均可使用。
 */
public final class Permission {

    private Permission() {}

    // ===== 权限常量 (资源:操作) =====
    /** 对话 */
    public static final String CHAT_CHAT = "chat:chat";
    /** 会话查询 (list/get/messages) */
    public static final String SESSION_READ = "session:read";
    /** 会话管理 (创建/删除/重命名) */
    public static final String SESSION_WRITE = "session:write";
    /** 知识库读取 */
    public static final String KB_READ = "kb:read";
    /** 知识库写入 (创建/更新/删除/上传文档) */
    public static final String KB_WRITE = "kb:write";
    /** 创建自定义 Agent (V2.2 保留兼容) */
    public static final String AGENT_CREATE = "agent:create";
    /** 读取自定义 Agent 列表/详情/模板 (V2.3) */
    public static final String AGENT_READ = "agent:read";
    /** 创建/更新/删除/克隆自定义 Agent (V2.3) */
    public static final String AGENT_WRITE = "agent:write";
    /** 工作流编排: 定义/执行/管理 (V2.3, F-25) */
    public static final String WORKFLOW_EXECUTE = "workflow:execute";
    /** 定时任务管理: CRUD/触发 (V2.3, F-26) */
    public static final String SCHEDULE_MANAGE = "schedule:manage";
    /** 模型管理 */
    public static final String MODEL_MANAGE = "model:manage";
    /** 用户管理 (管理后台) */
    public static final String USER_MANAGE = "user:manage";

    // ===== 角色常量 (Spring Security ROLE_ 前缀) =====
    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    public static final String ROLE_USER = "ROLE_USER";
    public static final String ROLE_GUEST = "ROLE_GUEST";

    /** 全部权限列表 (admin 拥有) */
    private static final List<String> ALL_PERMISSIONS = List.of(
            CHAT_CHAT, SESSION_READ, SESSION_WRITE,
            KB_READ, KB_WRITE, AGENT_CREATE, AGENT_READ, AGENT_WRITE,
            WORKFLOW_EXECUTE, SCHEDULE_MANAGE, MODEL_MANAGE, USER_MANAGE
    );

    /** 角色 → 权限集合映射 */
    private static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.of(
            ROLE_ADMIN, Set.copyOf(ALL_PERMISSIONS),
            ROLE_USER, Set.of(CHAT_CHAT, SESSION_READ, SESSION_WRITE,
                    KB_READ, KB_WRITE,   // V3.0: 知识库开放给普通用户(可创建/管理自己的 USER 级; PLATFORM 级只读)
                    AGENT_CREATE, AGENT_READ, AGENT_WRITE,
                    WORKFLOW_EXECUTE, SCHEDULE_MANAGE),
            ROLE_GUEST, Set.of(CHAT_CHAT)
    );

    /**
     * 根据角色名获取其全部权限 (含角色自身的 ROLE_ 权限)。
     * <p>
     * admin 自动继承 user 和 guest 的权限 (层级包含关系)。
     *
     * @param role 角色名 (admin/user/guest, 大小写不敏感)
     * @return 权限集合, 未知角色返回空集
     */
    public static Set<String> permissionsOf(String role) {
        if (role == null) return Set.of();
        String roleKey = "ROLE_" + role.toUpperCase();
        Set<String> perms = ROLE_PERMISSIONS.getOrDefault(roleKey, Set.of());
        // admin 层级包含: 合并 user + guest 权限
        if (ROLE_ADMIN.equals(roleKey)) {
            var merged = new java.util.HashSet<>(perms);
            merged.addAll(ROLE_PERMISSIONS.get(ROLE_USER));
            merged.addAll(ROLE_PERMISSIONS.get(ROLE_GUEST));
            return Set.copyOf(merged);
        }
        return perms;
    }

    /** 将角色名规范化为 Spring Security 的 ROLE_ 前缀格式 */
    public static String normalizeRole(String role) {
        if (role == null || role.isBlank()) return ROLE_USER;
        String upper = role.toUpperCase();
        return upper.startsWith("ROLE_") ? upper : "ROLE_" + upper;
    }
}
