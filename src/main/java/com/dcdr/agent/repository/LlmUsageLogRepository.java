package com.dcdr.agent.repository;

import com.dcdr.agent.entity.LlmUsageLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface LlmUsageLogRepository extends JpaRepository<LlmUsageLogEntity, String> {

    /** 用户在某时间点之后消耗的总 token (配额检查用) */
    @Query("SELECT COALESCE(SUM(e.totalTokens), 0) FROM LlmUsageLogEntity e " +
            "WHERE e.userId = :userId AND e.createdAt >= :since")
    long sumTokensByUserSince(@Param("userId") String userId, @Param("since") LocalDateTime since);

    /**
     * 按天+模型聚合 token 用量 (成本统计用)。
     * 按天分组用 JPQL 的 created_at 字符串截断 (PostgreSQL 无 date_trunc 的 JPQL 等价物,
     * CAST(:since AS string) 锁定参数类型避免 PG 推断成 bytea —— 项目已知问题)。
     */
    @Query("SELECT SUBSTRING(CAST(e.createdAt AS string), 1, 10), e.model, " +
            "COUNT(e), COALESCE(SUM(e.inputTokens), 0), COALESCE(SUM(e.outputTokens), 0), COALESCE(SUM(e.totalTokens), 0) " +
            "FROM LlmUsageLogEntity e " +
            "WHERE (:userId IS NULL OR e.userId = :userId) AND e.createdAt >= :since " +
            "GROUP BY SUBSTRING(CAST(e.createdAt AS string), 1, 10), e.model " +
            "ORDER BY SUBSTRING(CAST(e.createdAt AS string), 1, 10) DESC")
    List<Object[]> aggregateDailyUsage(@Param("userId") String userId, @Param("since") LocalDateTime since);

    /** 按模型聚合 token 用量 (成本统计用) */
    @Query("SELECT e.model, COUNT(e), COALESCE(SUM(e.inputTokens), 0), COALESCE(SUM(e.outputTokens), 0), COALESCE(SUM(e.totalTokens), 0) " +
            "FROM LlmUsageLogEntity e " +
            "WHERE (:userId IS NULL OR e.userId = :userId) AND e.createdAt >= :since " +
            "GROUP BY e.model ORDER BY COALESCE(SUM(e.totalTokens), 0) DESC")
    List<Object[]> aggregateUsageByModel(@Param("userId") String userId, @Param("since") LocalDateTime since);

    /** V3.0-rc F-36: 用户集合在某时间点之后消耗的总 token (租户月配额用) */
    @Query("SELECT COALESCE(SUM(e.totalTokens), 0) FROM LlmUsageLogEntity e " +
            "WHERE e.userId IN :userIds AND e.createdAt >= :since")
    long sumTokensByUserIdsSince(@Param("userIds") List<String> userIds, @Param("since") LocalDateTime since);
}
