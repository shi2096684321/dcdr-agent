package com.dcdr.agent.repository;

import com.dcdr.agent.entity.WebhookEndpointEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpointEntity, String> {

    /** 按 token 查询端点 (Webhook 触发入口) */
    Optional<WebhookEndpointEntity> findByToken(String token);

    /** 列出用户自己的 Webhook */
    List<WebhookEndpointEntity> findByUserIdOrderByCreatedAtDesc(String userId);
}
