package com.dcdr.agent.model.circuitbreaker;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.config.V3Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V3.0-rc F-40.4 LLM 熔断器: 按 provider(模型配置名) 统计失败率, 开断后快速失败。
 * <p>
 * 三个状态 (CLOSED → OPEN → HALF_OPEN → CLOSED):
 * <ul>
 *   <li>CLOSED: 正常放行, 滚动窗口内统计调用量与失败数</li>
 *   <li>OPEN: 失败率达标后开断, 在 waitOpenMs 内直接拒绝调用 (快速失败, 503)</li>
 *   <li>HALF_OPEN: 冷却期满后放行一次探测调用, 成功则闭合, 失败则继续开断</li>
 * </ul>
 * 统计口径: 窗口内调用数 >= minCalls 且失败率 >= failureRateThreshold 才开断 (避免小样本误判)。
 * <p>
 * 注意: 进程内内存实现, 多实例部署时各实例独立统计 (规划文档 §14.4 允许, 严格场景可下沉 Redis)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmCircuitBreaker {

    private final V3Properties v3Properties;

    private final Map<String, Stats> stats = new ConcurrentHashMap<>();

    /** 调用前检查: 开断状态下直接抛 503 (流式链路里会被包装为错误块) */
    public void check(String providerName) {
        if (!v3Properties.getCircuitBreaker().isEnabled()) return;
        Stats s = stats.computeIfAbsent(providerName, k -> new Stats());
        synchronized (s) {
            long now = System.currentTimeMillis();
            if (s.state == State.OPEN) {
                if (now < s.openUntil) {
                    throw new BusinessException(503,
                            "模型服务 [" + providerName + "] 熔断中(失败率过高), 请稍后重试或切换模型",
                            "CIRCUIT_OPEN");
                }
                // 冷却期满 → 半开, 放行一次探测
                s.state = State.HALF_OPEN;
                s.probing = true;
                log.info("熔断器 [{}] 进入半开状态, 放行探测调用", providerName);
            }
        }
    }

    /** 调用成功: 闭合熔断器并清空统计 */
    public void recordSuccess(String providerName) {
        if (!v3Properties.getCircuitBreaker().isEnabled()) return;
        Stats s = stats.get(providerName);
        if (s == null) return;
        synchronized (s) {
            if (s.state != State.CLOSED) {
                log.info("熔断器 [{}] 探测成功, 闭合恢复", providerName);
            }
            s.state = State.CLOSED;
            s.probing = false;
            s.reset();
        }
    }

    /** 调用失败: 半开状态失败 → 立即重新开断; 闭合状态 → 累计失败率 */
    public void recordFailure(String providerName) {
        if (!v3Properties.getCircuitBreaker().isEnabled()) return;
        V3Properties.CircuitBreaker cfg = v3Properties.getCircuitBreaker();
        Stats s = stats.computeIfAbsent(providerName, k -> new Stats());
        synchronized (s) {
            long now = System.currentTimeMillis();
            if (s.state == State.HALF_OPEN) {
                open(s, providerName, now, cfg);
                return;
            }
            if (s.state != State.CLOSED) return;
            if (now - s.windowStart > WINDOW_MS) s.reset();
            s.total++;
            s.failures++;
            if (s.total >= cfg.getMinCalls()
                    && (double) s.failures / s.total >= cfg.getFailureRateThreshold()) {
                open(s, providerName, now, cfg);
            }
        }
    }

    private void open(Stats s, String providerName, long now, V3Properties.CircuitBreaker cfg) {
        s.state = State.OPEN;
        s.probing = false;
        s.openUntil = now + cfg.getWaitOpenMs();
        log.warn("熔断器 [{}] 开断: 窗口内 {}/{} 次失败, {}ms 内快速失败",
                providerName, s.failures, s.total, cfg.getWaitOpenMs());
        s.reset();
    }

    /** 熔断状态快照 (监控/运维查看用) */
    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> list = new ArrayList<>();
        stats.forEach((name, s) -> {
            synchronized (s) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("provider", name);
                m.put("state", s.state.name());
                m.put("windowCalls", s.total);
                m.put("windowFailures", s.failures);
                m.put("openUntil", s.openUntil);
                list.add(m);
            }
        });
        return list;
    }

    /** 统计窗口长度 (固定滚动窗口) */
    private static final long WINDOW_MS = 60_000L;

    private enum State { CLOSED, OPEN, HALF_OPEN }

    private static class Stats {
        volatile State state = State.CLOSED;
        volatile boolean probing;
        volatile long openUntil;
        long windowStart = System.currentTimeMillis();
        int total;
        int failures;

        void reset() {
            windowStart = System.currentTimeMillis();
            total = 0;
            failures = 0;
        }
    }
}
