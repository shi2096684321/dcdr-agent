package com.dcdr.agent.repository;

import com.dcdr.agent.entity.WorkspaceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 工作区仓储(V3.3 F-49)。
 */
@Repository
public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, String> {

    List<WorkspaceEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

    List<WorkspaceEntity> findAllByOrderByUpdatedAtDesc();

    /** 同一用户重复添加同一目录时提示"已存在", 而不是静默建出两条 */
    Optional<WorkspaceEntity> findFirstByUserIdAndRootPath(String userId, String rootPath);

    List<WorkspaceEntity> findByUserId(String userId);
}
