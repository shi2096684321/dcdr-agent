package com.dcdr.agent.repository;

import com.dcdr.agent.entity.ProjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjectRepository extends JpaRepository<ProjectEntity, String> {

    List<ProjectEntity> findAllByOrderByUpdatedAtDesc();

    /** V3.1 F-41: 按归属用户查询(创作项目此前无归属字段, 任何登录用户可见全部) */
    List<ProjectEntity> findByUserIdOrderByUpdatedAtDesc(String userId);
}
