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

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    /** 元 / 千 token 换算基数 (与 {@code V3Properties.Cost.pricing} 的计价口径一致) */
    private static final BigDecimal PER_THOUSAND = new BigDecimal("1000");

    private final LlmUsageLogRepository usageRepository;
    private final V3Properties v3Properties;

    /**
     * 记录一次 LLM 调用 token 消耗。
     *
     * @param userId     用户 ID (可空)
     * @param sessionId  会话 ID (可空)
     * @param execId     工作流执行 ID (可空)
     * @param source     调用来源 —— 取值为 {@link CostSource}(唯一取值定义处), 不可为空:
     *                   费用报表按它分组, 记错了就是"看不见的费用"
     * @param model      模型名
     * @param usage      token 统计 (可空, 无统计时不落库)
     */
    public void record(String userId, String sessionId, String execId, CostSource source,
                       String model, ChatResponse.TokenUsage usage) {
        if (usage == null) return;
        if (source == null) {
            // 宁可少记一行, 也不默认记成"对话": 默认值是静默的错误归因,
            // 报表上看不出来, 只能靠"总数与分项对不上"这种更难查的迹象发现
            log.warn("用量记录缺少来源, 已跳过: model={}, user={}", model, userId);
            return;
        }
        try {
            LlmUsageLogEntity e = new LlmUsageLogEntity();
            e.setId("lu_" + UUID.randomUUID().toString().replace("-", ""));
            e.setUserId(userId);
            e.setSessionId(sessionId);
            e.setWorkflowExecutionId(execId);
            e.setSource(source.value());
            e.setModel(model);
            e.setInputTokens(usage.getPrompt());
            e.setOutputTokens(usage.getCompletion());
            e.setTotalTokens(usage.getTotal() > 0 ? usage.getTotal()
                    : usage.getPrompt() + usage.getCompletion());
            // V3.1 F-44.2: 按模型定价折算金额(未配置定价的模型留空, 报表金额列降级显示)
            e.setCostAmount(estimateCost(model, usage));
            e.setCreatedAt(LocalDateTime.now());
            usageRepository.save(e);
        } catch (Exception ex) {
            log.warn("LLM 用量记录失败(不影响主链路): {}", ex.getMessage());
        }
    }

    /**
     * V3.1 F-44.2: 按模型定价折算金额 (元)。
     * <p>未配置该模型单价时返回 {@code null} —— 宁可留空也不落 0,
     * 否则报表会把"没有定价"误报成"零成本"。
     */
    private BigDecimal estimateCost(String model, ChatResponse.TokenUsage usage) {
        V3Properties.ModelPrice price = v3Properties.getCost().priceOf(model);
        if (price == null || usage == null) return null;
        BigDecimal input = price.getInput() == null ? BigDecimal.ZERO : price.getInput();
        BigDecimal output = price.getOutput() == null ? BigDecimal.ZERO : price.getOutput();
        return input.multiply(BigDecimal.valueOf(usage.getPrompt()))
                .add(output.multiply(BigDecimal.valueOf(usage.getCompletion())))
                .divide(PER_THOUSAND, 6, RoundingMode.HALF_UP);
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
