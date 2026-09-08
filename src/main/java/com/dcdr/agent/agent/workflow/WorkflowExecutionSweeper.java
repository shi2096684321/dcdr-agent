package com.dcdr.agent.agent.workflow;

import com.dcdr.agent.entity.WorkflowExecutionEntity;
import com.dcdr.agent.repository.WorkflowExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * V3.0 F-40.8 数据一致性兜底: 工作流执行超时/卡死的标记与释放。
 * <p>
 * 场景: 进程异常退出/上层大量等待导致某次执行停留在 {@code running} 态迟迟不结束。
 * 无状态服务重启后该记录无法被推进, 本兜底任务周期扫描:
 * <ul>
 *   <li>将启动超过 {@code agent.workflow.dead-letter-ms} 仍未结束的 running 执行
 *       标记为 <b>dead_letter</b>(附原因), 暴露到执行历史供排查/手动 resume 或重跑;</li>
 *   <li>只对 {code running} 生效(用户主动暂停的 paused 属合法状态, 不在兜底范围)。</li>
 * </ul>
 * 注: 进程内多实例可能触发多次扫描, 幂等(重复置为同一状态无副作用), 单实例即可。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowExecutionSweeper {

    private final WorkflowExecutionRepository executionRepository;

    /** 单次执行判定"卡死"的最长时长(毫秒), 默认 30 分钟 */
    @Value("${agent.workflow.dead-letter-ms:1800000}")
    private long deadLetterMs;

    /** 扫描间隔(毫秒), 默认 5 分钟 */
    @Value("${agent.workflow.sweep-interval-ms:300000}")
    private long sweepIntervalMs;

    @Scheduled(fixedDelayString = "${agent.workflow.sweep-interval-ms:300000}",
            initialDelayString = "60000")
    public void markStuckExecutionsDeadLetter() {
        LocalDateTime before = LocalDateTime.now().minus(deadLetterMs, ChronoUnit.MILLIS);
        try {
            List<WorkflowExecutionEntity> stuck = executionRepository.findStuckByStatus(List.of("running"), before);
            for (WorkflowExecutionEntity e : stuck) {
                e.setStatus("dead_letter");
                e.setError("执行超过 " + (deadLetterMs / 60000) + " 分钟仍未完成, 由兜底任务标记为 dead_letter"
                        + "(疑似卡死/进程中断), 可手动取消或重跑");
                executionRepository.save(e);
                log.warn("工作流执行兜底: exec={}, workflow={}, 标记为 dead_letter",
                        e.getId(), e.getWorkflowId());
            }
            if (!stuck.isEmpty()) {
                log.info("兜底扫描完成: {} 条执行被标记为 dead_letter", stuck.size());
            }
        } catch (Exception ex) {
            log.error("工作流执行兜底扫描失败", ex);
        }
    }
}