package com.dcdr.agent.repository;

import com.dcdr.agent.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SessionRepository extends JpaRepository<SessionEntity, String> {

    List<SessionEntity> findAllByOrderByUpdatedAtDesc();
}
