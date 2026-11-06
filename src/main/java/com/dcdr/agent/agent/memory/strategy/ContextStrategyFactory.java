package com.dcdr.agent.agent.memory.strategy;

import com.dcdr.agent.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按配置选择上下文策略(V3.4)。
 *
 * <p><b>为什么用工厂而不是在每个调用点 if/else</b>: 策略是 Spring Bean, 新增一个实现只要打上
 * {@code @Component} 并声明名字就自动可用 —— 调用方(编排器)不需要知道存在哪些策略。
 * 若在编排器里按字符串 if/else, "多了一种策略"就会变成一次对主链路的改动。
 *
 * <p><b>配置写错时的行为</b>: 退到默认策略(摘要压缩)并打 warn, 而不是抛异常 ——
 * 策略名拼错不该让服务起不来, 但也不能静默用错策略, 一条 warn 足以让人在日志里发现。
 */
@Slf4j
@Component
public class ContextStrategyFactory {

    private final Map<String, ContextStrategy> strategies = new HashMap<>();
    private final ContextStrategy defaultStrategy;
    /** 持有配置对象而不是它的当前值: 名字在启动后才可能被刷新(配置中心/测试), 读时取值 */
    private final AgentProperties properties;

    public ContextStrategyFactory(List<ContextStrategy> beans, AgentProperties properties) {
        this.properties = properties;
        for (ContextStrategy s : beans) {
            strategies.put(s.name(), s);
        }
        this.defaultStrategy = strategies.get(SummaryCompactionStrategy.NAME);

        // 启动期就把配置校验掉: 等到第一轮对话才发现策略名写错, 代价太大
        String configured = properties.getMemory().getStrategy();
        if (!strategies.containsKey(String.valueOf(configured))) {
            log.warn("上下文策略配置无效: agent.memory.strategy={}, 可选 {}; 已回退为默认策略 {}",
                    configured, strategies.keySet(), SummaryCompactionStrategy.NAME);
        } else {
            log.info("上下文策略: {} (可选 {})", configured, strategies.keySet());
        }
    }

    /** 当前生效的策略 */
    public ContextStrategy current() {
        return resolve(properties.getMemory().getStrategy());
    }

    /** 按名字取策略; 名字无效时回退默认 */
    public ContextStrategy resolve(String name) {
        ContextStrategy s = name == null ? null : strategies.get(name);
        return s != null ? s : defaultStrategy;
    }
}
