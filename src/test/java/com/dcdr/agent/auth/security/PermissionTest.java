package com.dcdr.agent.auth.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Permission} 单元测试(V3.2)。
 *
 * <p>纯单元测试: 全静态方法 + 静态规则表, 零外部依赖, 不启 Spring 上下文。
 *
 * <p><b>为什么它必须被测试</b>: 这是 RBAC 的判定内核, 出错的两个方向都是事故:
 * <ul>
 *   <li><b>多给权限</b> → 越权访问(普通用户拿到 {@code user:manage} / {@code model:manage})</li>
 *   <li><b>少给权限</b> → 正常功能被 403(admin 缺权限会导致管理后台不可用)</li>
 * </ul>
 * 其中 <b>admin 的层级继承</b>(必须包含 user + guest 的全部权限)是最容易被"
 * 新增权限时只加到一个角色"这类改动悄悄破坏的地方 —— 故本测试按<b>包含关系</b>断言,
 * 而非写死权限条数, 这样新增权限时测试仍有效。
 */
class PermissionTest {

    // ==================================================================
    //  角色 → 权限
    // ==================================================================

    @Nested
    @DisplayName("permissionsOf")
    class PermissionsOf {

        @Test
        @DisplayName("admin 拥有全部权限(含模型管理 / 用户管理)")
        void adminHasAllPermissions() {
            Set<String> perms = Permission.permissionsOf("admin");

            assertTrue(perms.containsAll(List.of(
                    Permission.CHAT_CHAT, Permission.SESSION_READ, Permission.SESSION_WRITE,
                    Permission.KB_READ, Permission.KB_WRITE,
                    Permission.AGENT_CREATE, Permission.AGENT_READ, Permission.AGENT_WRITE,
                    Permission.WORKFLOW_EXECUTE, Permission.SCHEDULE_MANAGE,
                    Permission.MODEL_MANAGE, Permission.USER_MANAGE)));
        }

        @Test
        @DisplayName("admin 层级继承: 必须包含 user 与 guest 的全部权限")
        void adminInheritsUserAndGuest() {
            Set<String> admin = Permission.permissionsOf("admin");

            assertTrue(admin.containsAll(Permission.permissionsOf("user")),
                    "admin 应包含 user 的全部权限");
            assertTrue(admin.containsAll(Permission.permissionsOf("guest")),
                    "admin 应包含 guest 的全部权限");
        }

        @Test
        @DisplayName("user 拥有业务权限, 但不含管理类权限")
        void userHasBusinessButNotAdminPermissions() {
            Set<String> perms = Permission.permissionsOf("user");

            assertTrue(perms.containsAll(List.of(
                    Permission.CHAT_CHAT, Permission.SESSION_READ, Permission.SESSION_WRITE,
                    Permission.KB_READ, Permission.KB_WRITE,
                    Permission.WORKFLOW_EXECUTE, Permission.SCHEDULE_MANAGE)));
            assertFalse(perms.contains(Permission.MODEL_MANAGE), "普通用户不应有模型管理权限");
            assertFalse(perms.contains(Permission.USER_MANAGE), "普通用户不应有用户管理权限");
        }

        @Test
        @DisplayName("guest 仅有对话权限")
        void guestOnlyHasChat() {
            assertEquals(Set.of(Permission.CHAT_CHAT), Permission.permissionsOf("guest"));
        }

        @Test
        @DisplayName("角色名大小写不敏感")
        void roleIsCaseInsensitive() {
            assertEquals(Permission.permissionsOf("admin"), Permission.permissionsOf("ADMIN"));
            assertEquals(Permission.permissionsOf("user"), Permission.permissionsOf("User"));
            assertEquals(Permission.permissionsOf("guest"), Permission.permissionsOf("GUEST"));
        }

        @Test
        @DisplayName("null / 空白 / 未知角色返回空集(不抛异常)")
        void unknownRoleReturnsEmpty() {
            assertEquals(Set.of(), Permission.permissionsOf(null));
            assertEquals(Set.of(), Permission.permissionsOf(""));
            assertEquals(Set.of(), Permission.permissionsOf("nobody"));
        }

        @Test
        @DisplayName("返回值为不可变集合, 防止调用方改坏共享规则表")
        void returnsImmutableSet() {
            assertThrows(UnsupportedOperationException.class,
                    () -> Permission.permissionsOf("user").add("fake:perm"));
            assertThrows(UnsupportedOperationException.class,
                    () -> Permission.permissionsOf("admin").add("fake:perm"));
        }
    }

    // ==================================================================
    //  角色名规范化
    // ==================================================================

    @Nested
    @DisplayName("normalizeRole")
    class NormalizeRole {

        @Test
        @DisplayName("null / 空白回退为 ROLE_USER")
        void blankFallsBackToUser() {
            assertEquals(Permission.ROLE_USER, Permission.normalizeRole(null));
            assertEquals(Permission.ROLE_USER, Permission.normalizeRole(""));
            assertEquals(Permission.ROLE_USER, Permission.normalizeRole("   "));
        }

        @Test
        @DisplayName("补全 ROLE_ 前缀并转大写")
        void addsRolePrefix() {
            assertEquals(Permission.ROLE_ADMIN, Permission.normalizeRole("admin"));
            assertEquals(Permission.ROLE_ADMIN, Permission.normalizeRole("ADMIN"));
            assertEquals(Permission.ROLE_GUEST, Permission.normalizeRole("guest"));
        }

        @Test
        @DisplayName("已带前缀时不重复添加")
        void doesNotDuplicatePrefix() {
            assertEquals(Permission.ROLE_ADMIN, Permission.normalizeRole("ROLE_ADMIN"));
            assertEquals(Permission.ROLE_ADMIN, Permission.normalizeRole("role_admin"));
        }
    }

    // ==================================================================
    //  常量契约(供 @PreAuthorize("hasAuthority('xxx')") 使用)
    // ==================================================================

    @Nested
    @DisplayName("常量格式契约")
    class Constants {

        @Test
        @DisplayName("角色常量均带 Spring Security 约定的 ROLE_ 前缀")
        void roleConstantsHavePrefix() {
            assertTrue(Permission.ROLE_ADMIN.startsWith("ROLE_"));
            assertTrue(Permission.ROLE_USER.startsWith("ROLE_"));
            assertTrue(Permission.ROLE_GUEST.startsWith("ROLE_"));
        }

        @Test
        @DisplayName("权限码为「资源:操作」小写格式(与文档 §4.3 F-23 一致)")
        void permissionCodesFollowResourceActionFormat() {
            List<String> codes = List.of(
                    Permission.CHAT_CHAT, Permission.SESSION_READ, Permission.SESSION_WRITE,
                    Permission.KB_READ, Permission.KB_WRITE,
                    Permission.AGENT_CREATE, Permission.AGENT_READ, Permission.AGENT_WRITE,
                    Permission.WORKFLOW_EXECUTE, Permission.SCHEDULE_MANAGE,
                    Permission.MODEL_MANAGE, Permission.USER_MANAGE);

            for (String code : codes) {
                assertTrue(code.matches("[a-z]+:[a-z]+"),
                        "权限码应为 资源:操作 小写格式, 实际: " + code);
            }
        }

        @Test
        @DisplayName("每个角色至少有一个权限(不存在无权限角色)")
        void everyRoleHasAtLeastOnePermission() {
            for (String role : List.of("admin", "user", "guest")) {
                assertFalse(Permission.permissionsOf(role).isEmpty(), role + " 不应是空权限集");
            }
        }
    }
}
