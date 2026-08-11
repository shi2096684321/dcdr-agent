package com.dcdr.agent.repository;

import com.dcdr.agent.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SessionRepository extends JpaRepository<SessionEntity, String> {

    List<SessionEntity> findAllByOrderByUpdatedAtDesc();

    List<SessionEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

    Optional<SessionEntity> findByIdAndUserId(String id, String userId);
}
