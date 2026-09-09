package com.dcdr.agent.repository;

import com.dcdr.agent.entity.UserModelConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 用户自定义模型配置持久层 (V3.1 新增)。
 */
@Repository
public interface UserModelConfigRepository extends JpaRepository<UserModelConfigEntity, String> {

    List<UserModelConfigEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<UserModelConfigEntity> findByIdAndUserId(String id, String userId);

    void deleteByIdAndUserId(String id, String userId);
}