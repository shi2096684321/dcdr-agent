package com.dcdr.agent.repository;

import com.dcdr.agent.entity.ContextCompactionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ContextCompactionRepository extends JpaRepository<ContextCompactionEntity, String> {

    /**
     * 分页查询压缩事件(按时间倒序)。三个条件都是"传 null 表示不限"。
     *
     * <p><b>为什么把 userId 也放进查询而不是只靠调用方筛</b>: 普通用户只能看自己的记录,
     * 这条约束必须在 SQL 里生效 —— 只在 Controller 里判一下"是不是本人",
     * 后续新增入口时就容易漏掉一次。管理员全局视图传 userId=null。
     */
    @Query("SELECT e FROM ContextCompactionEntity e "
            + "WHERE (:userId IS NULL OR e.userId = :userId) "
            + "AND (:sessionId IS NULL OR e.sessionId = :sessionId) "
            + "AND (:eventType IS NULL OR e.eventType = :eventType) "
            + "ORDER BY e.createdAt DESC")
    Page<ContextCompactionEntity> findForList(@Param("userId") String userId,
                                              @Param("sessionId") String sessionId,
                                              @Param("eventType") String eventType,
                                              Pageable pageable);

    /**
     * 会话被删除时解除引用, 不删行。
     *
     * <p>理由同 {@link LlmCallLogRepository#detachSession(String)}: 压缩事件的价值是
     * "这个部署多久压一次、降级率多高"这类统计, 会话只是排查时的一条线索;
     * 跟着会话一起删, 等于让存储清理策略决定还能不能复盘历史问题。
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE ContextCompactionEntity e SET e.sessionId = NULL WHERE e.sessionId = :sessionId")
    int detachSession(@Param("sessionId") String sessionId);
}
