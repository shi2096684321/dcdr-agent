package com.dcdr.agent.repository;

import com.dcdr.agent.entity.TemplateShareEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TemplateShareRepository extends JpaRepository<TemplateShareEntity, String> {

    Optional<TemplateShareEntity> findByTypeAndTargetId(String type, String targetId);

    /**
     * 模板市场分页搜索: 类型 / 分类 / 关键词 (标题+描述+标签 模糊)。
     * CONCAT 内参数显式 CAST 为 string, 避免 PG 推断 bytea (项目已知问题)。
     */
    @Query("SELECT t FROM TemplateShareEntity t WHERE t.enabled = true AND " +
            "(:type IS NULL OR t.type = :type) AND " +
            "(:category IS NULL OR t.category = :category) AND " +
            "(:keyword IS NULL OR " +
            " LOWER(t.title) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%')) OR " +
            " LOWER(t.description) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%')) OR " +
            " LOWER(t.tags) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%')))")
    Page<TemplateShareEntity> searchMarket(@Param("type") String type, @Param("category") String category,
                                           @Param("keyword") String keyword, Pageable pageable);

    /** 用户自己分享的记录 (我的分享管理) */
    Page<TemplateShareEntity> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
}
