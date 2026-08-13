package com.dcdr.agent.repository;

import com.dcdr.agent.entity.ScheduledTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScheduledTaskRepository extends JpaRepository<ScheduledTaskEntity, String> {

    /** 用户的定时任务 (按更新时间倒序) */
    List<ScheduledTaskEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

    /** 全部启用的任务 (启动时加载调度) */
    List<ScheduledTaskEntity> findByEnabledTrue();
}
