package com.dcdr.agent.agent.governance;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.config.V3Properties;
import com.dcdr.agent.dto.ChatResponse;
import com.dcdr.agent.dto.MonitorDtos;
import com.dcdr.agent.entity.LlmUsageLogEntity;
import com.dcdr.agent.repository.LlmUsageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * V3.0-beta 成本追踪 + 配额检查 (F-34 成本追踪 / F-39 成本与配额管理, 文档 §8.3 / §13)。
 * <p>
 * 职责:
 * <ul>
 *   <li><b>记录</b>: 每次 LLM 调用 (对话/工作流步骤/路由) 落库 {@code llm_usage_logs}</li>
 *   <li><b>配额</b>: 用户级日/月 token 限额检查, 超额抛 {@code 429} 业务异常拦截调用</li>
 *   <li><b>统计</b>: 按天/按模型聚合用量, 供监控仪表盘 (F-34) 使用</li>
 * </ul>
 * 记录失败不影响主链路 (仅 warn 日志)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostTracker {

    private final LlmUsageLogRepository usageRepository;
    private final V3Properties v3Properties;

    /**
     * 记录一次 LLM 调用 token 消耗。
     *
     * @param userId     用户 ID (可空)
     * @param sessionId  会话 ID (可空)
     * @param execId     工作流执行 ID (可空)
     * @param source     调用来源: chat / workflow / routing
     * @param model      模型名
     * @param usage      token 统计 (可空, 无统计时不落库)
     */
    public void record(String userId, String sessionId, String execId, String source,
                       String model, ChatResponse.TokenUsage usage) {
        if (usage == null) return;
        try {
            LlmUsageLogEntity e = new LlmUsageLogEntity();
            e.setId("lu_" + UUID.randomUUID().toString().replace("-", ""));
            e.setUserId(userId);
            e.setSessionId(sessionId);
            e.setWorkflowExecutionId(execId);
            e.setSource(source == null ? "chat" : source);
            e.setModel(model);
            e.setInputTokens(usage.getPrompt());
            e.setOutputTokens(usage.getCompletion());
            e.setTotalTokens(usage.getTotal() > 0 ? usage.getTotal()
                    : usage.getPrompt() + usage.getCompletion());
            e.setCreatedAt(LocalDateTime.now());
            usageRepository.save(e);
        } catch (Exception ex) {
            log.warn("LLM 用量记录失败(不影响主链路): {}", ex.getMessage());
        }
    }

    /**
     * F-39 用户级配额检查: 日/月 token 限额。
     * 超额抛 BusinessException(429), 由调用方拦截本次 LLM 调用。
     * 配额关闭 (agent.v3.quota.enabled=false) 或限额为 0 (不限) 时直接放行。
     */
    public void checkUserQuota(String userId) {
        if (userId == null) return;
        V3Properties.Quota quota = v3Properties.getQuota();
        if (!quota.isEnabled()) return;

        long dailyLimit = quota.getDailyTokens();
        if (dailyLimit > 0) {
            long used = usageRepository.sumTokensByUserSince(userId, LocalDate.now().atStartOfDay());
            if (used >= dailyLimit) {
                throw new BusinessException(429,
                        "已达到今日 token 配额 (" + used + "/" + dailyLimit + "), 请明日再试或联系管理员调整",
                        "QUOTA_EXCEEDED_DAILY");
            }
        }
        long monthlyLimit = quota.getMonthlyTokens();
        if (monthlyLimit > 0) {
            long used = usageRepository.sumTokensByUserSince(userId,
                    LocalDate.now().withDayOfMonth(1).atStartOfDay());
            if (used >= monthlyLimit) {
                throw new BusinessException(429,
                        "已达到本月 token 配额 (" + used + "/" + monthlyLimit + "), 请联系管理员调整",
                        "QUOTA_EXCEEDED_MONTHLY");
            }
        }
    }

    /** F-39 工作流单次执行 token 上限 (0 = 不限) */
    public long workflowMaxTokensPerExecution() {
        V3Properties.Quota quota = v3Properties.getQuota();
        return quota.isEnabled() ? quota.getWorkflowMaxTokensPerExecution() : 0;
    }

    /**
     * 对话/Agent 单次任务 token 上限 (0 = 不限)。
     * <p>一次对话请求(含多轮工具循环)的跨轮累计消耗达到该值后, AgentOrchestrator
     * 在轮次边界停止派生下一轮并正常收尾。配额关闭时为 0(不限制)。
     */
    public long taskMaxTokens() {
        V3Properties.Quota quota = v3Properties.getQuota();
        return quota.isEnabled() ? quota.getTaskMaxTokens() : 0;
    }

    /** 当前用户配额使用情况 (前端展示) */
    public MonitorDtos.QuotaUsage getQuotaUsage(String userId) {
        V3Properties.Quota quota = v3Properties.getQuota();
        MonitorDtos.QuotaUsage u = new MonitorDtos.QuotaUsage();
        u.setQuotaEnabled(quota.isEnabled());
        u.setDailyLimit(quota.getDailyTokens());
        u.setMonthlyLimit(quota.getMonthlyTokens());
        if (userId != null) {
            u.setDailyUsed(usageRepository.sumTokensByUserSince(userId, LocalDate.now().atStartOfDay()));
            u.setMonthlyUsed(usageRepository.sumTokensByUserSince(userId,
                    LocalDate.now().withDayOfMonth(1).atStartOfDay()));
        }
        return u;
    }

    /** F-34 成本统计: 按天 + 按模型聚合 (近 N 天) */
    public MonitorDtos.CostOverview costStats(String userId, int days) {
        LocalDateTime since = LocalDate.now().minusDays(Math.max(days, 1)).atTime(LocalTime.MIN);
        MonitorDtos.CostOverview o = new MonitorDtos.CostOverview();

        List<Object[]> dailyRows = usageRepository.aggregateDailyUsage(userId, since);
        List<MonitorDtos.DailyUsage> daily = new ArrayList<>(dailyRows.size());
        long total = 0, inTotal = 0, outTotal = 0, calls = 0;
        for (Object[] row : dailyRows) {
            MonitorDtos.DailyUsage d = new MonitorDtos.DailyUsage();
            d.setDate(String.valueOf(row[0]));
            d.setInputTokens(asLong(row[2]));
            d.setOutputTokens(asLong(row[3]));
            d.setTotalTokens(asLong(row[4]));
            daily.add(d);
            inTotal += d.getInputTokens();
            outTotal += d.getOutputTokens();
            total += d.getTotalTokens();
        }
        o.setDaily(daily);

        List<Object[]> modelRows = usageRepository.aggregateUsageByModel(userId, since);
        List<MonitorDtos.ModelUsage> byModel = new ArrayList<>(modelRows.size());
        for (Object[] row : modelRows) {
            MonitorDtos.ModelUsage m = new MonitorDtos.ModelUsage();
            m.setModel(row[0] == null ? "(unknown)" : String.valueOf(row[0]));
            m.setCalls(asLong(row[1]));
            m.setInputTokens(asLong(row[2]));
            m.setOutputTokens(asLong(row[3]));
            m.setTotalTokens(asLong(row[4]));
            calls += m.getCalls();
            byModel.add(m);
        }
        o.setByModel(byModel);

        o.setTotalTokens(total);
        o.setInputTokens(inTotal);
        o.setOutputTokens(outTotal);
        o.setCallCount(calls);
        return o;
    }

    private static long asLong(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }
}
