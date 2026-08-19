package com.dcdr.agent.repository;

import com.dcdr.agent.entity.WorkflowExecutionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
}
