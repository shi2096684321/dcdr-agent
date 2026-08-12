package com.dcdr.agent.repository;

import com.dcdr.agent.entity.AuditLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, String> {

    /**
     * 多条件分页查询(管理后台用)。
     * <p>
     * 时间参数用 COALESCE 而非 IS NULL: PostgreSQL 无法从 {@code (? IS NULL)} 推断 null 参数的类型,
     * 改用 {@code COALESCE(:from, a.createdAt)} 让 PG 从列类型推断参数类型, 避免
     * "could not determine data type of parameter" 错误。
     * 当 :from/:to 为 null 时, COALESCE 返回列自身值, 条件恒真, 等价于不筛选。
     */
    @Query("SELECT a FROM AuditLogEntity a WHERE " +
            "(:userId IS NULL OR a.userId = :userId) AND " +
            "(:action IS NULL OR a.action = :action) AND " +
            "(a.createdAt >= COALESCE(:from, a.createdAt)) AND " +
            "(a.createdAt <= COALESCE(:to, a.createdAt)) " +
            "ORDER BY a.createdAt DESC")
    Page<AuditLogEntity> queryByConditions(@Param("userId") String userId,
                                           @Param("action") String action,
                                           @Param("from") LocalDateTime from,
                                           @Param("to") LocalDateTime to,
                                           Pageable pageable);

    /** 统计指定动作在时间范围内的次数 */
    long countByActionAndCreatedAtBetween(String action, LocalDateTime from, LocalDateTime to);

    /** 统计时间范围内的全部日志数 */
    long countByCreatedAtBetween(LocalDateTime from, LocalDateTime to);
}
