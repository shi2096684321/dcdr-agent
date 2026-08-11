package com.dcdr.agent.repository;

import com.dcdr.agent.entity.AgentDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentDefinitionRepository extends JpaRepository<AgentDefinitionEntity, String> {

    List<AgentDefinitionEntity> findByUserIdOrIsPublicTrueOrderByUpdatedAtDesc(String userId);
}
