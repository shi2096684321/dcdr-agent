package com.dcdr.agent.repository;

import com.dcdr.agent.entity.GenerationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GenerationRepository extends JpaRepository<GenerationEntity, String> {

    List<GenerationEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);

    void deleteByProjectId(String projectId);

    long countByProjectId(String projectId);
}
