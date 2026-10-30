package com.dcdr.agent.repository;

import com.dcdr.agent.entity.ScheduledTaskRunEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 定时任务执行记录仓库 (V3.3 F-50)。
 */
@Repository
public interface ScheduledTaskRunRepository extends JpaRepository<ScheduledTaskRunEntity, String> {

    /** 详情页按 token 查（免登录） */
    Optional<ScheduledTaskRunEntity> findByShareToken(String token);

    /** 任务的执行历史，按时间倒序；条数由 Pageable 控制 */
    List<ScheduledTaskRunEntity> findByTaskIdOrderByCreatedAtDesc(String taskId, Pageable pageable);

    /** 清理过期记录（预留：当前由保留策略决定是否调用） */
    long deleteByCreatedAtBefore(LocalDateTime cutoff);

    /** 某任务的记录总数（分页展示用） */
    long countByTaskId(String taskId);
}
