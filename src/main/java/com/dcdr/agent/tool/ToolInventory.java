package com.dcdr.agent.tool;

import com.dcdr.agent.config.ToolsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工具清单与启动期自检 (V3.2)。
 *
 * <p><b>为什么需要它</b>: 工具一旦从产品界面撤下(不再允许用户勾选), "当前到底有哪些工具被
 * 暴露给模型"就没有任何可视化入口了。工具膨胀恰恰是<b>静默发生</b>的 —— 每加一个工具,
 * 上下文成本与选错概率都上升一点, 但没有任何报错。本类把这件事挪到两个可观测的位置:
 * <ul>
 *   <li>启动期: 打印实际暴露清单, 并校对"部署配置里写了但没注册"(拼错 / 被自身 enabled 关掉)
 *       与"注册了但 schema 坏了"(对模型不可见)两类配置漂移;</li>
 *   <li>运行期: 单轮注入条数超阈值时告警(节流), 这是"该合并/裁剪工具了"的唯一早期信号。</li>
 * </ul>
 *
 * <p>它<b>不是</b>产品目录: 不再提供中文名/适用场景文案 —— 那些文案的消费者(配置页)已经移除,
 * 留着只会变成第二份需要人工维护、且必然漂移的真值源。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolInventory {

    /** 运行期告警节流: 每轮对话都会走到这里, 不节流会把日志刷成噪声 */
    private static final long WARN_INTERVAL_MS = 60_000L;

    private final ToolRegistry toolRegistry;
    private final ToolsProperties toolsProperties;

    private final AtomicLong lastWarnAt = new AtomicLong(0L);

    /**
     * 用 {@link ApplicationReadyEvent} 而不是 {@code CommandLineRunner}: 后者与
     * {@code ToolAutoConfiguration#toolRegistrar} 的执行顺序无法稳定保证
     * (CommandLineRunner 的排序看 Bean 实例, {@code @Bean} 方法上的 {@code @Order} 不参与),
     * 一旦先跑, 清单就是空的。ApplicationReadyEvent 保证在全部 runner 之后。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        Audit audit = audit(
                names(toolRegistry.getAllTools().stream().map(Tool::getName).toList()),
                names(toolRegistry.getStreamingToolSpecifications().stream()
                        .map(dev.langchain4j.agent.tool.ToolSpecification::name).toList()),
                // 必须用"不受部署白名单影响"的名单: 用 getToolSpecifications() 会把
                // "故意不暴露"误判成"schema 构建失败"
                names(toolRegistry.getSpecifiedToolNames()),
                toolsProperties.getEnabled());

        log.info("暴露给模型的工具 {} 个: {}", audit.exposed().size(), audit.exposed());
        if (!audit.excluded().isEmpty()) {
            log.info("被 agent-tools.enabled 排除的工具 {} 个(仍可被 workflow 显式调用): {}",
                    audit.excluded().size(), audit.excluded());
        }
        if (!audit.missing().isEmpty()) {
            // 配置漂移: 写了名字却没注册 —— 拼错、或该工具自身的 enabled 关着。
            // 不报错(部署方可能只是提前占位), 但必须可见, 否则会以为"已经开了"。
            log.warn("agent-tools.enabled 中以下工具未注册(拼写错误? 或对应 enabled 已关闭?): {}",
                    audit.missing());
        }
        if (!audit.withoutSpec().isEmpty()) {
            log.warn("以下工具已注册但 ToolSpecification 构建失败, 模型看不到它们: {}", audit.withoutSpec());
        }
        log.info("工具清单自检完成: 已注册 {} 个, 暴露 {} 个, 上下文告警阈值 {} 个",
                toolRegistry.getAllTools().size(), audit.exposed().size(),
                toolsProperties.getContextWarnCount());
    }

    /**
     * 单轮注入条数超阈值时告警(60 秒内最多一次)。
     *
     * <p>为什么告警而不是拒绝: 超出阈值本身不是故障, 只是"上下文成本与选错概率开始不划算"的
     * 信号; 直接拒绝会把一次正常对话变成失败, 代价远大于多花的那点 token。
     */
    public void warnIfContextTooLarge(int toolCount) {
        int limit = toolsProperties.getContextWarnCount();
        if (limit <= 0 || toolCount <= limit) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = lastWarnAt.get();
        if (now - last < WARN_INTERVAL_MS || !lastWarnAt.compareAndSet(last, now)) {
            return;
        }
        log.warn("单轮注入了 {} 个工具定义, 已超过阈值 {}: 工具定义是每轮重发的上下文成本,"
                        + " 且工具越多模型越容易选错。该考虑合并同类工具或按场景裁剪了"
                        + "(agent-tools.enabled / 技能依赖)。",
                toolCount, limit);
    }

    /**
     * 配置与实际注册情况的静态比对(纯函数, 便于单测)。
     *
     * @param registered 已注册的工具名(全部, 含 schema 坏的)
     * @param exposed    实际注入给模型的工具名(已按部署开关 + supportsStreaming 过滤)
     * @param specNames  ToolSpecification 构建成功的工具名
     * @param allowed    部署白名单; null/空 = 不裁剪
     */
    static Audit audit(List<String> registered, List<String> exposed,
                       List<String> specNames, List<String> allowed) {
        List<String> excluded = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> withoutSpec = new ArrayList<>();

        boolean filtering = allowed != null && !allowed.isEmpty();
        for (String name : registered) {
            // 两者互斥: 一个工具要么是"被部署配置主动排除", 要么是"schema 坏了",
            // 同时归入两类会让日志自相矛盾(既说没暴露, 又说坏了), 也让排查方向发散
            if (filtering && !allowed.contains(name) && !ToolRegistry.platformToolNames().contains(name)) {
                excluded.add(name);
            } else if (!specNames.contains(name)) {
                withoutSpec.add(name);
            }
        }
        if (filtering) {
            for (String name : allowed) {
                if (!registered.contains(name)) {
                    missing.add(name);
                }
            }
        }
        return new Audit(List.copyOf(exposed), List.copyOf(excluded),
                List.copyOf(missing), List.copyOf(withoutSpec));
    }

    /** 去重并排序: 注册表底层是 ConcurrentHashMap, 遍历顺序不稳定, 日志要能跨次对比 */
    private static List<String> names(Collection<String> src) {
        return src.stream().filter(java.util.Objects::nonNull).distinct().sorted().toList();
    }

    /**
     * 自检结果。
     *
     * @param exposed     实际暴露给模型的工具
     * @param excluded    被部署配置排除的工具
     * @param missing     部署配置里列了但未注册的工具
     * @param withoutSpec 已注册但 schema 构建失败(对模型不可见)的工具
     */
    public record Audit(List<String> exposed, List<String> excluded,
                        List<String> missing, List<String> withoutSpec) {
    }
}
