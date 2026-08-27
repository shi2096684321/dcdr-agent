package com.dcdr.agent.agent.governance;

import com.dcdr.agent.dto.MonitorDtos;
import com.dcdr.agent.entity.WorkflowDefinitionEntity;
import com.dcdr.agent.entity.WorkflowExecutionEntity;
import com.dcdr.agent.repository.WorkflowDefinitionRepository;
import com.dcdr.agent.repository.WorkflowExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * V3.0-beta 工作流执行监控统计 (F-34 治理与可观测性, 文档 §8.2)。
 * <p>
 * 从 {@code workflow_executions} 聚合时间窗口内的执行指标:
 * 总次数/成功/失败/暂停/运行中/成功率/平均耗时, 以及按工作流维度的聚合。
 * 供监控仪表盘 (GET /api/monitor/workflows) 使用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowMonitorService {

    private final WorkflowExecutionRepository executionRepository;
    private final WorkflowDefinitionRepository workflowRepository;

    /**
     * 执行监控总览。
     *
     * @param userId 用户 ID; null = 全局 (管理员视角)
     * @param days   统计时间窗口 (天)
     */
    public MonitorDtos.WorkflowMonitorOverview overview(String userId, int days) {
        LocalDateTime since = LocalDate.now().minusDays(Math.max(days, 1)).atTime(LocalTime.MIN);
        List<WorkflowExecutionEntity> execs = executionRepository.findRecentForMonitor(userId, since);

        MonitorDtos.WorkflowMonitorOverview o = new MonitorDtos.WorkflowMonitorOverview();
        o.setTotalExecutions(execs.size());
        long success = 0, failed = 0, paused = 0, running = 0;
        long totalDurationMs = 0;
        long durationCount = 0;
        long totalTokens = 0;
        // 按工作流聚合
        Map<String, MonitorDtos.WorkflowStat> byWorkflow = new HashMap<>();
        Map<String, LocalDateTime> lastExecuted = new HashMap<>();
        for (WorkflowExecutionEntity e : execs) {
            switch (e.getStatus()) {
                case "success" -> success++;
                case "failed" -> failed++;
                case "paused" -> paused++;
                case "running" -> running++;
                default -> { }
            }
            if (e.getStartedAt() != null && e.getCompletedAt() != null) {
                totalDurationMs += Duration.between(e.getStartedAt(), e.getCompletedAt()).toMillis();
                durationCount++;
            }
            if (e.getCostTokens() != null) totalTokens += e.getCostTokens();

            MonitorDtos.WorkflowStat stat = byWorkflow.computeIfAbsent(e.getWorkflowId(), k -> {
                MonitorDtos.WorkflowStat s = new MonitorDtos.WorkflowStat();
                s.setWorkflowId(k);
                s.setExecutions(0);
                s.setSuccessCount(0);
                s.setFailedCount(0);
                s.setAvgDurationMs(0);
                s.setTotalTokens(0);
                return s;
            });
            stat.setExecutions(stat.getExecutions() + 1);
            if ("success".equals(e.getStatus())) stat.setSuccessCount(stat.getSuccessCount() + 1);
            if ("failed".equals(e.getStatus())) stat.setFailedCount(stat.getFailedCount() + 1);
            if (e.getCostTokens() != null) stat.setTotalTokens(stat.getTotalTokens() + e.getCostTokens());
            if (e.getStartedAt() != null && e.getCompletedAt() != null) {
                stat.setAvgDurationMs(stat.getAvgDurationMs()
                        + Duration.between(e.getStartedAt(), e.getCompletedAt()).toMillis());
            }
            if (e.getStartedAt() != null
                    && !lastExecuted.containsKey(e.getWorkflowId())) {
                lastExecuted.put(e.getWorkflowId(), e.getStartedAt());
            }
        }
        o.setSuccessCount(success);
        o.setFailedCount(failed);
        o.setPausedCount(paused);
        o.setRunningCount(running);
        o.setSuccessRate(execs.isEmpty() ? null : (double) success / execs.size());
        o.setAvgDurationMs(durationCount == 0 ? 0 : totalDurationMs / durationCount);
        o.setTotalTokens(totalTokens);

        // 工作流名称回填 + 平均耗时/成功率先算好
        List<MonitorDtos.WorkflowStat> stats = new ArrayList<>(byWorkflow.values());
        for (MonitorDtos.WorkflowStat s : stats) {
            workflowRepository.findById(s.getWorkflowId())
                    .map(WorkflowDefinitionEntity::getName)
                    .ifPresent(s::setWorkflowName);
            s.setSuccessRate(s.getExecutions() == 0 ? null
                    : (double) s.getSuccessCount() / s.getExecutions());
            s.setAvgDurationMs(s.getExecutions() == 0 ? 0 : s.getAvgDurationMs() / s.getExecutions());
            s.setLastExecutedAt(lastExecuted.get(s.getWorkflowId()));
        }
        stats.sort((a, b) -> Long.compare(b.getExecutions(), a.getExecutions()));
        o.setByWorkflow(stats);
        return o;
    }
}
