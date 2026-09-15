package com.dcdr.agent.repository;

import com.dcdr.agent.entity.MemoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 长期记忆仓储 (V3.1)。
 * <p>
 * 查询均为派生查询, 与项目既有 Repository 风格一致 (无 MyBatis, 无 XML)。
 * 排序统一用「常驻优先 → 重要度降序 → 时间倒序」, 让注入候选天然按价值排列。
 */
@Repository
public interface MemoryRepository extends JpaRepository<MemoryEntity, String> {

    /** 某用户全部活跃记忆 (召回候选池: 用户级 + 全部 Agent 级, 由 Service 按 Agent 分层过滤) */
    List<MemoryEntity> findByUserIdAndStatusOrderByPinnedDescImportanceDescCreatedAtDesc(String userId, String status);

    /** 归属校验: 按主键 + 归属用户查询, 避免越权读取他人记忆 */
    Optional<MemoryEntity> findByIdAndUserId(String id, String userId);

    /** 全部记忆 (含归档/删除), 供记忆管理页的"显示已删除/归档"视图展示 */
    List<MemoryEntity> findByUserIdOrderByPinnedDescImportanceDescCreatedAtDesc(String userId);

    /** 已启用记忆条数, 供后续规模阈值判断 (条目超过阈值时升级为向量召回, V3.1 P2) */
    long countByUserIdAndStatus(String userId, String status);
}
