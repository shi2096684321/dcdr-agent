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

    // ===== V3.1 F-44.2 成本分析报表 =====
    // 两条约定:
    //  1) 一律「左闭右开」区间 [from, to) —— 便于按天/周/月切分且不重不漏;
    //  2) 只做「天粒度」聚合, 周/月的 rollup 在 Java 侧完成 —— JPQL 无 date_trunc,
    //     改用 nativeQuery 会绑死 PostgreSQL 并在 H2(开发/测试) 下直接报错(项目已有同类踩坑)。

    /** 天粒度区间聚合 (趋势图数据源; 列: 日期 / 调用数 / 输入 / 输出 / 合计 / 金额) */
    @Query("SELECT SUBSTRING(CAST(e.createdAt AS string), 1, 10), COUNT(e), " +
            "COALESCE(SUM(e.inputTokens), 0), COALESCE(SUM(e.outputTokens), 0), " +
            "COALESCE(SUM(e.totalTokens), 0), COALESCE(SUM(e.costAmount), 0) " +
            "FROM LlmUsageLogEntity e " +
            "WHERE (:userId IS NULL OR e.userId = :userId) AND e.createdAt >= :from AND e.createdAt < :to " +
            "GROUP BY SUBSTRING(CAST(e.createdAt AS string), 1, 10) " +
            "ORDER BY SUBSTRING(CAST(e.createdAt AS string), 1, 10)")
    List<Object[]> aggregateDailyBetween(@Param("userId") String userId,
                                         @Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to);

    /** 模型维度区间聚合 (列: 模型 / 调用数 / 输入 / 输出 / 合计 / 金额) */
    @Query("SELECT e.model, COUNT(e), COALESCE(SUM(e.inputTokens), 0), COALESCE(SUM(e.outputTokens), 0), " +
            "COALESCE(SUM(e.totalTokens), 0), COALESCE(SUM(e.costAmount), 0) " +
            "FROM LlmUsageLogEntity e " +
            "WHERE (:userId IS NULL OR e.userId = :userId) AND e.createdAt >= :from AND e.createdAt < :to " +
            "GROUP BY e.model ORDER BY COALESCE(SUM(e.totalTokens), 0) DESC")
    List<Object[]> aggregateModelsBetween(@Param("userId") String userId,
                                          @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);

    /** 来源维度区间聚合 (chat / workflow / routing; 列: 来源 / 调用数 / 合计 token / 金额) */
    @Query("SELECT e.source, COUNT(e), COALESCE(SUM(e.totalTokens), 0), COALESCE(SUM(e.costAmount), 0) " +
            "FROM LlmUsageLogEntity e " +
            "WHERE (:userId IS NULL OR e.userId = :userId) AND e.createdAt >= :from AND e.createdAt < :to " +
            "GROUP BY e.source ORDER BY COALESCE(SUM(e.totalTokens), 0) DESC")
    List<Object[]> aggregateSourcesBetween(@Param("userId") String userId,
                                           @Param("from") LocalDateTime from,
                                           @Param("to") LocalDateTime to);

    /** Top 用户聚合 (仅全局报表使用; 列: 用户 ID / 调用数 / 合计 token / 金额) */
    @Query("SELECT e.userId, COUNT(e), COALESCE(SUM(e.totalTokens), 0), COALESCE(SUM(e.costAmount), 0) " +
            "FROM LlmUsageLogEntity e " +
            "WHERE e.createdAt >= :from AND e.createdAt < :to " +
            "GROUP BY e.userId ORDER BY COALESCE(SUM(e.totalTokens), 0) DESC")
    List<Object[]> aggregateTopUsersBetween(@Param("from") LocalDateTime from,
                                            @Param("to") LocalDateTime to);

    /**
     * 区间内"有金额"的记录数。
     * 用于判断报表金额列是否可用 —— 未配置模型定价时 cost_amount 全为 null,
     * 此时聚合结果是 0, 直接展示会误导成"零成本", 故用本查询区分「无数据」与「真 0」。
     */
    @Query("SELECT COUNT(e) FROM LlmUsageLogEntity e " +
            "WHERE (:userId IS NULL OR e.userId = :userId) AND e.createdAt >= :from AND e.createdAt < :to " +
            "AND e.costAmount IS NOT NULL")
    long countWithCostBetween(@Param("userId") String userId,
                              @Param("from") LocalDateTime from,
                              @Param("to") LocalDateTime to);
}
