package com.dcdr.agent.repository;

import com.dcdr.agent.entity.LlmCallLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LlmCallLogRepository extends JpaRepository<LlmCallLogEntity, String> {

    /** 分页查询调用链日志(按时间倒序) */
    Page<LlmCallLogEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** 按用户分页查询 */
    Page<LlmCallLogEntity> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
}