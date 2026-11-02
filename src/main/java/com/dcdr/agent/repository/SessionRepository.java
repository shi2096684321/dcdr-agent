package com.dcdr.agent.repository;

import com.dcdr.agent.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface SessionRepository extends JpaRepository<SessionEntity, String> {

    List<SessionEntity> findAllByOrderByUpdatedAtDesc();

    List<SessionEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

    Optional<SessionEntity> findByIdAndUserId(String id, String userId);

    // ===== V3.3 按来源筛选 (对话列表只看 user, 任务对话看 schedule/workflow) =====

    List<SessionEntity> findBySourceInOrderByUpdatedAtDesc(Collection<String> sources);

    List<SessionEntity> findByUserIdAndSourceInOrderByUpdatedAtDesc(String userId, Collection<String> sources);

    /**
     * 某一来源聚合下的全部自动会话 (新→旧)。
     * <p>按 {@code createdAt} 而非 {@code updatedAt} 排序: 自动会话执行完不会被用户再触碰,
     * 但清理时要按"第几次执行"来收敛, createdAt 才是稳定的先后顺序。
     */
    List<SessionEntity> findBySourceAndSourceRefOrderByCreatedAtDesc(String source, String sourceRef);

    /**
     * 把"旧版靠标题前缀标记的自动会话"改归真实来源 (V3.3 一次性回填)。
     * <p>
     * 落 source 字段之前, 定时任务 / 工作流的执行会话只能靠标题 {@code schedule:xxx} /
     * {@code workflow:xxx} 辨认, 而它们混在对话列表里。这里按前缀把它们认回来。
     *
     * @return 受影响行数(0 表示没有需要回填的存量, 正常启动就是这样)
     */
    @Modifying
    @Transactional
    @Query("update SessionEntity s set s.source = :source "
            + "where s.source = 'user' and s.title like :prefix")
    int reclassifyLegacyAutoSessions(@Param("source") String source, @Param("prefix") String prefix);
}
