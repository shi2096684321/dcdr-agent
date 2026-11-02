package com.dcdr.agent.repository;

import com.dcdr.agent.entity.UserNotifyConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 用户级推送配置持久层 (V3.3)。
 */
@Repository
public interface UserNotifyConfigRepository extends JpaRepository<UserNotifyConfigEntity, String> {

    List<UserNotifyConfigEntity> findByUserId(String userId);

    Optional<UserNotifyConfigEntity> findByUserIdAndChannel(String userId, String channel);
}
