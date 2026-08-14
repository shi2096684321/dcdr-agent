package com.dcdr.agent.repository;

import com.dcdr.agent.entity.RoutingLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoutingLogRepository extends JpaRepository<RoutingLogEntity, String> {

    /** 分页查询路由记录 (按时间倒序) */
    Page<RoutingLogEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** 按用户分页查询 */
    Page<RoutingLogEntity> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
}
