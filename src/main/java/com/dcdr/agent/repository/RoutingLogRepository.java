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

    /**
     * 按路由类型分页查询 (全部用户)。
     * <p>
     * 类型过滤必须下推到数据库: 前端只在"当前页 20 条"里过滤的话, 下一页会把上一页过滤掉的行
     * 又补回来, 且 total 仍是全量 —— 分页与筛选互相打架, 用户看到的是"每页条数忽多忽少"。
     */
    Page<RoutingLogEntity> findByRouteTypeOrderByCreatedAtDesc(String routeType, Pageable pageable);

    /** 按用户 + 路由类型分页查询 */
    Page<RoutingLogEntity> findByUserIdAndRouteTypeOrderByCreatedAtDesc(
            String userId, String routeType, Pageable pageable);
}
