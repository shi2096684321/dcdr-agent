package com.dcdr.agent.repository;

import com.dcdr.agent.entity.WorkflowExecutionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecutionEntity, String> {

    /** 按用户列出执行记录 (最近优先) */
    List<WorkflowExecutionEntity> findByUserIdOrderByStartedAtDesc(String userId);

    /** 按工作流列出执行记录 */
    List<WorkflowExecutionEntity> findByWorkflowIdOrderByStartedAtDesc(String workflowId);

    /**
     * V3.0 多条件分页查询 (执行历史搜索/筛选/分页)。
     * <p>
     * 可选条件: 状态 / 工作流 / 触发方式 / 关键词 (模糊匹配输入内容/错误信息/工作流ID)。
     * <p>
     * 类型推断说明 (项目经验):
     * <ul>
     *   <li>等值条件用 {@code :param IS NULL OR col = :param}; PG 从列推断参数类型, 无歧义。</li>
     *   <li>关键词模糊匹配参与 {@code CONCAT('%', :keyword, '%')} 时, 该参数 {@code IS NULL} 判断
     *       会让 PG 把后续 CONCAT 里的同名参数推断成 {@code bytea} (因 CONCAT 有 bytea 重载),
     *       导致 {@code lower(bytea)} 不存在报错。故对 CONCAT 内的 :keyword 显式
     *       {@code CAST(:keyword AS string)} 锁定为 text 类型。</li>
     * </ul>
     */
    @Query("SELECT e FROM WorkflowExecutionEntity e WHERE " +
            "(:userId IS NULL OR e.userId = :userId) AND " +
            "(:status IS NULL OR e.status = :status) AND " +
            "(:workflowId IS NULL OR e.workflowId = :workflowId) AND " +
            "(:triggerType IS NULL OR e.triggerType = :triggerType) AND " +
            "(:keyword IS NULL OR " +
            " LOWER(e.input) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%')) OR " +
            " LOWER(e.error) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%')) OR " +
            " LOWER(e.workflowId) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%'))) " +
            "ORDER BY e.startedAt DESC")
    Page<WorkflowExecutionEntity> queryByConditions(@Param("userId") String userId,
                                                    @Param("status") String status,
                                                    @Param("workflowId") String workflowId,
                                                    @Param("triggerType") String triggerType,
                                                    @Param("keyword") String keyword,
                                                    Pageable pageable);

    /**
     * V3.0-beta F-34: 时间窗口内的执行记录 (监控统计用)。
     * userId 为 null 时统计全局 (仅管理员监控接口会传 null)。
     */
    @Query("SELECT e FROM WorkflowExecutionEntity e WHERE " +
            "(:userId IS NULL OR e.userId = :userId) AND e.startedAt >= :since " +
            "ORDER BY e.startedAt DESC")
    List<WorkflowExecutionEntity> findRecentForMonitor(@Param("userId") String userId,
                                                       @Param("since") LocalDateTime since);

    /**
     * F-40.8 兜底: 查询超过阈值仍未结束的指定状态执行记录(用于把卡死的运行标记为 dead_letter)。
     * statuses 例如 ["running"]; before = 应完成时限。
     */
    @Query("SELECT e FROM WorkflowExecutionEntity e WHERE e.status IN :statuses AND e.startedAt < :before")
    List<WorkflowExecutionEntity> findStuckByStatus(@Param("statuses") List<String> statuses,
                                                    @Param("before") LocalDateTime before);
}
