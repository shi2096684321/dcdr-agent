package com.dcdr.agent.auth;

/**
 * 当前登录用户的 <b>线程局部</b> 持有器 (V3.1 F-41 自动化增强)。
 * <p>
 * <b>为什么可以用 ThreadLocal, 而不用 SecurityContextHolder?</b>
 * <p>
 * 这两者不是一回事, 容易混淆 —— 之前实测失败的是后者:
 * <ul>
 *   <li>{@code SecurityContextHolder} 在 WebFlux 下<b>根本不会被写入</b>
 *       (Spring Security 只把 SecurityContext 放进 Reactor Context), 所以任何地方读都是 null;</li>
 *   <li>而 {@code ThreadLocal} 是<b>我们自己写入</b>的。关键在于写入点与读取点是否同线程 ——
 *       对于本项目的同步链路(WebFilter → DispatcherHandler → 同步 Controller → Service → JPA),
 *       全程在同一个 nio 线程上同步嵌套执行, <b>不存在线程切换</b>,
 *       因此 ThreadLocal 可靠。这也与 JPA 自身的事务模型一致(JPA 的事务状态同样绑在 ThreadLocal 上)。</li>
 * </ul>
 * <p>
 * <b>失效场景(已知边界)</b>: 一旦链路跨了异步边界(如切到 {@code boundedElastic} 的对话准备阶段、
 * 定时任务线程、工作流内部执行), ThreadLocal 就是空的。此时<b>必须回退到显式传递 userId</b>
 * (现有 Service 方法签名都保留了 userId 参数, 正是为此)。
 * <p>
 * <b>防残留</b>: 写入方({@code SecurityContextWebFilter})采用「每个请求进来先清空、认证后再写入、
 * 链尾再清空」三段式。即使链尾清理因跨线程未命中, 下一个请求进入时也会先清掉残留,
 * 从而避免 nio 线程复用导致的身份串号(越权)。
 *
 * @see com.dcdr.agent.entity.OwnershipFillListener 用它做写入时的归属自动填充
 */
public final class CurrentUserHolder {

    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();

    private CurrentUserHolder() {
    }

    /** 写入当前用户 ID; 传 null 等同于清理 */
    public static void set(String userId) {
        if (userId == null) {
            USER_ID.remove();
        } else {
            USER_ID.set(userId);
        }
    }

    /** 读取当前用户 ID; 不在同步链路上时为 null */
    public static String get() {
        return USER_ID.get();
    }

    /** 清理(必须在设置处以 try/finally 或链尾回调中调用, 防止线程复用串号) */
    public static void clear() {
        USER_ID.remove();
    }
}
