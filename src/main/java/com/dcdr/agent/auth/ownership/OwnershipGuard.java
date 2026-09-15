package com.dcdr.agent.auth.ownership;

import com.dcdr.agent.audit.AuditService;
import com.dcdr.agent.auth.SecurityContext;
import com.dcdr.agent.auth.config.AuthProperties;
import com.dcdr.agent.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 统一归属校验闸门 (V3.1 F-41)。
 * <p>
 * <b>这是全项目唯一的资源归属判定出口</b>。「资源不存在」与「越权访问」统一返回 404
 * (越权即不存在, 防止攻击者用 403/404 差异枚举他人资源 ID), 同时把越权尝试写入审计。
 * <p>
 * <b>三种用法</b>:
 * <ol>
 *   <li><b>声明式(HTTP 入口首选)</b>: Controller 方法标注
 *       {@code @PreAuthorize("@ownershipGuard.requireRead(#id, 'SESSION', principal)")}。
 *       当前用户由 Spring Security 以 SpEL 变量 {@code principal} 注入, 因此方法<b>无需</b>声明
 *       {@code ServerWebExchange} 等任何上下文参数。前提: {@code SecurityConfig} 已开启
 *       {@code @EnableReactiveMethodSecurity}, 且被保护方法返回 {@code Mono}
 *       (响应式方法级安全不支持 {@code Flux}; 流式端点改用 {@link #currentContext()} 手工包一层);</li>
 *   <li><b>响应式取值</b>: {@link #currentContext()} 供需要 userId(按用户隔离 / 写入归属)的
 *       Controller 使用, 同样无需声明参数;</li>
 *   <li><b>显式调用</b>: Service 层与非 HTTP 链路用
 *       {@link #check(ResourceType, String, String, boolean, boolean)} 或 {@link #canAccess},
 *       适用于需要读取归属结果、批量过滤、或定时任务/工作流内部编排。</li>
 * </ol>
 * <p>
 * <b>匿名策略(V3.1 起)</b>: 认证开启却取不到 userId 一律拒绝; 仅当
 * {@code agent.auth.enabled=false}(V2.1 单用户兼容模式)时放行匿名请求。
 * <p>
 * <b>为什么不从 {@code SecurityContextHolder}(ThreadLocal)取?(实测结论, 勿重复踩坑)</b>
 * <p>
 * 曾尝试让同步 AOP 切面从 {@code SecurityContextHolder} 取当前用户(自备 {@code ThreadLocalAccessor}
 * 并在启动期调用 {@code Hooks.enableAutomaticContextPropagation()}), 以省去业务方法上的
 * {@code ServerWebExchange} 参数。实测在「同步返回 {@code Result<T>} 的 Controller + 同步切面」下
 * {@code SecurityContextHolder} 始终为<b>空</b>(表现为用户访问<b>自己</b>的资源也返回 404)。
 * 根因: WebFlux 下 Spring Security 只把 SecurityContext 放入 <b>Reactor Context</b>,
 * 而同步方法调用不在 Reactor 算子回调语义内, 自动上下文传播(在算子边界 restore/reset)覆盖不到该执行点;
 * 且 Spring Security 6.3.x 未内置 SecurityContext 的 {@code ThreadLocalAccessor}。
 * <p>
 * <b>正确解法(当前实现)</b>: 让被保护方法返回 {@code Mono} 并用 {@code @PreAuthorize} ——
 * 校验发生在响应式链路上, 上下文由框架从 Reactor Context 提供, 既不需要 ThreadLocal,
 * 也不需要任何方法参数。
 * <p>
 * <b>使用约束</b>:
 * <ul>
 *   <li>{@code @PreAuthorize} 只对返回 {@code Mono} 的方法生效; 返回 {@code Flux} 的流式端点
 *       需用 {@link #currentContext()} 手工包一层(见 {@code ImageController#streamProgress});</li>
 *   <li>SpEL 的第二个参数是 {@link ResourceType} 枚举名(如 {@code 'SESSION'}); 写错会在启动/调用期
 *       以 {@code IllegalStateException} 快速失败, 不会静默放行;</li>
 *   <li>跨线程的异步链路(如切到 boundedElastic 的对话准备阶段)请沿用既有的「显式传 userId」做法;</li>
 *   <li>非 HTTP 入口(定时任务、工作流内部编排)使用
 *       {@link #check(ResourceType, String, String, boolean, boolean)} 显式校验;</li>
 *   <li>若将来需要更细的权限模型(一个资源授权给多主体、权限位分化), 可把
 *       {@link ResourceOwnershipProvider} 实现为 Spring Security 的 {@code PermissionEvaluator},
 *       并改用 {@code hasPermission(...)} 表达式。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OwnershipGuard {

    private final OwnershipRegistry registry;
    private final AuthProperties authProperties;
    private final AuditService auditService;

    /** 从 HTTP 请求组装鉴权上下文 */
    public AuthContext context(ServerWebExchange exchange) {
        return new AuthContext(
                SecurityContext.getUserId(exchange),
                SecurityContext.isAdmin(exchange),
                authProperties.isEnabled());
    }

    /**
     * 系统内部上下文: 不受归属限制。
     * <p>
     * 仅供无 HTTP 请求的后台链路使用(定时任务触发、工作流内部编排、审计报表等) ——
     * 这些链路在调度器中已做过前置鉴权, 此处以 admin 身份放行。业务代码中<b>不得</b>用
     * 它来绕过用户请求的归属校验。
     */
    public AuthContext systemContext() {
        return new AuthContext(null, true, true);
    }

    /** 校验读权限(HTTP 入口) */
    public Ownership checkRead(ResourceType type, String id, ServerWebExchange exchange) {
        return check(type, id, context(exchange), false);
    }

    /** 校验写权限(HTTP 入口) */
    public Ownership checkWrite(ResourceType type, String id, ServerWebExchange exchange) {
        return check(type, id, context(exchange), true);
    }

    /**
     * 校验资源归属(Service 层显式调用入口)。
     * <p>
     * 供拿不到 {@code ServerWebExchange} 的 Service / 异步链路使用 —— 它们从请求同步段
     * 提前取出 {@code userId}/{@code isAdmin} 后传入。{@code authEnabled} 由注入的
     * {@link AuthProperties} 补齐, 保证匿名策略与 HTTP 入口完全一致。
     *
     * @param userId  归属用户 ID; null 表示匿名
     * @param isAdmin 是否管理员
     * @param write   true 表示写操作
     */
    public Ownership check(ResourceType type, String id, String userId, boolean isAdmin, boolean write) {
        return check(type, id, new AuthContext(userId, isAdmin, authProperties.isEnabled()), write);
    }

    /**
     * 校验资源归属, 不通过则抛出 404。
     *
     * @param type  资源类型
     * @param id    资源主键
     * @param ctx   鉴权上下文
     * @param write true 表示写操作
     * @return 资源归属快照(便于调用方复用, 例如取 ownerId 做后续过滤)
     * @throws BusinessException 资源不存在或越权时抛出(统一 404)
     */
    public Ownership check(ResourceType type, String id, AuthContext ctx, boolean write) {
        ResourceOwnershipProvider provider = registry.get(type);

        if (id == null || id.isBlank()) {
            throw notFound(provider, id);
        }

        Ownership ownership = provider.load(id);
        if (ownership == null) {
            throw notFound(provider, id);
        }

        if (!provider.canAccess(ownership, ctx, write)) {
            recordDenied(type, id, ownership, ctx, write);
            throw notFound(provider, id);
        }
        return ownership;
    }

    /**
     * 响应式读取当前鉴权上下文。
     * <p>
     * 供返回 {@code Mono} 的 Controller 使用 —— 当接口需要 userId(如列表按用户隔离、
     * 创建资源时写入归属)但<b>不想</b>声明 {@code ServerWebExchange} 参数时用它。
     * 上下文取自 <b>Reactor Context</b> 中的 Authentication(WebFlux 下 Spring Security 的标准存放位置),
     * 而非 ThreadLocal。
     * <p>
     * 匿名/认证关闭时返回<b>匿名上下文</b>(userId 为 null), 不会为空, 便于链式处理:
     * <pre>
     *   return ownershipGuard.currentContext()
     *           .map(ctx -&gt; Result.success(sessionManager.listSessions(ctx.userId())));
     * </pre>
     */
    public Mono<AuthContext> currentContext() {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(sc -> sc.getAuthentication())
                .mapNotNull(Authentication::getPrincipal)
                .ofType(SecurityContext.CurrentUser.class)
                .map(user -> new AuthContext(user.getUserId(), "admin".equals(user.getRole()),
                        authProperties.isEnabled()))
                .defaultIfEmpty(new AuthContext(null, false, authProperties.isEnabled()));
    }

    // ===== @PreAuthorize 入口 (响应式方法级安全, V3.1 F-41) =====

    /**
     * SpEL 入口(读): 供 {@code @PreAuthorize} 表达式调用。
     * <p>
     * 典型写法:
     * <pre>
     *   &#64;PreAuthorize("@ownershipGuard.requireRead(#id, 'SESSION', principal)")
     *   public Mono&lt;Result&lt;SessionDTO&gt;&gt; get(&#64;PathVariable String id) { ... }
     * </pre>
     * 表达式中的 {@code principal} 是 Spring Security 注入的<b>当前认证主体</b>
     * (由 {@code SecurityContextWebFilter} 构造, 携带 userId/role), 因此业务方法
     * <b>无需</b>声明 {@code ServerWebExchange} 等任何上下文参数。
     *
     * @param id           资源主键(表达式引用方法参数, 如 {@code #id})
     * @param resourceType {@link ResourceType} 枚举名(如 {@code 'SESSION'})
     * @param principal    当前认证主体({@link SecurityContext.CurrentUser}); 未认证时为 null
     * @return 恒为 {@code true}(校验失败抛异常, 不会返回 false)
     * @throws BusinessException 资源不存在或越权(统一 404)
     */
    public boolean requireRead(String id, String resourceType, Object principal) {
        return require(id, resourceType, principal, false);
    }

    /** SpEL 入口(写): 语义同 {@link #requireRead}, 但要求写权限 */
    public boolean requireWrite(String id, String resourceType, Object principal) {
        return require(id, resourceType, principal, true);
    }

    private boolean require(String id, String resourceType, Object principal, boolean write) {
        check(parseResourceType(resourceType), id, contextOf(principal), write);
        return true;
    }

    /** 资源类型名 → 枚举; 未知类型属配置错误(SpEL 写错), 快速失败 */
    private static ResourceType parseResourceType(String resourceType) {
        if (resourceType == null || resourceType.isBlank()) {
            throw new IllegalStateException("@PreAuthorize 未提供资源类型 (第二个参数应为 ResourceType 枚举名)");
        }
        try {
            return ResourceType.valueOf(resourceType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("@PreAuthorize 中的资源类型不存在: " + resourceType
                    + " (可选值见 ResourceType 枚举)");
        }
    }

    /** 由 SpEL 传入的 principal 组装鉴权上下文 */
    private AuthContext contextOf(Object principal) {
        if (principal instanceof SecurityContext.CurrentUser user) {
            return new AuthContext(user.getUserId(), "admin".equals(user.getRole()), authProperties.isEnabled());
        }
        // 未认证: userId 为 null, 是否放行交由匿名策略(authEnabled=false 时放行)
        return new AuthContext(null, false, authProperties.isEnabled());
    }

    /**
     * 不抛异常的权限探测, 用于列表过滤或分支判断。
     *
     * @return true 表示允许访问; 资源不存在同样返回 false(与 404 语义一致)
     */
    public boolean canAccess(ResourceType type, String id, AuthContext ctx, boolean write) {
        ResourceOwnershipProvider provider = registry.get(type);
        if (id == null || id.isBlank()) return false;
        Ownership ownership = provider.load(id);
        if (ownership == null) return false;
        return provider.canAccess(ownership, ctx, write);
    }

    /** 记录越权尝试(异步落库, 失败不影响主流程) */
    private void recordDenied(ResourceType type, String id, Ownership ownership, AuthContext ctx, boolean write) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("write", write);
        detail.put("resourceOwner", ownership.ownerId());
        detail.put("actor", ctx.userId());
        detail.put("admin", ctx.admin());
        auditService.record("access_denied", type.code(), id, detail, ctx.userId());
        log.warn("越权访问被拒绝: type={}, id={}, actor={}, write={}, owner={}",
                type.code(), id, ctx.userId(), write, ownership.ownerId());
    }

    private BusinessException notFound(ResourceOwnershipProvider provider, String id) {
        return new BusinessException(404, provider.notFoundMessage(id));
    }
}
