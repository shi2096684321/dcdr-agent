package com.dcdr.agent.repository;

import com.dcdr.agent.entity.GenerationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GenerationRepository extends JpaRepository<GenerationEntity, String> {

    List<GenerationEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);

    Page<GenerationEntity> findByProjectIdOrderByCreatedAtDesc(String projectId, Pageable pageable);

    void deleteByProjectId(String projectId);

    long countByProjectId(String projectId);

    long countByProjectIdAndType(String projectId, String type);

    @Query("SELECT g FROM GenerationEntity g WHERE g.projectId = :projectId AND g.type = :type ORDER BY g.createdAt DESC")
    Page<GenerationEntity> findByProjectIdAndType(@Param("projectId") String projectId,
                                                  @Param("type") String type,
                                                  Pageable pageable);

    /** 历史记录只返回终态(completed 成功 / failed 失败), 排除进行中的 pending/processing, 保证前端分页 total 与展示一致 */
    @Query("SELECT g FROM GenerationEntity g WHERE g.projectId = :projectId "
            + "AND (g.type = :type OR :type IS NULL) "
            + "AND g.status IN ('completed', 'failed') "
            + "ORDER BY g.createdAt DESC")
    Page<GenerationEntity> findTerminatedByProjectId(@Param("projectId") String projectId,
                                                     @Param("type") String type,
                                                     Pageable pageable);
}
