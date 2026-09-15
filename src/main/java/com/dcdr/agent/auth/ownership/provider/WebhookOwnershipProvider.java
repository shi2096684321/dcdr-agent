package com.dcdr.agent.auth.ownership.provider;

import com.dcdr.agent.auth.ownership.Ownership;
import com.dcdr.agent.auth.ownership.ResourceOwnershipProvider;
import com.dcdr.agent.auth.ownership.ResourceType;
import com.dcdr.agent.repository.WebhookEndpointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Webhook 端点归属 Provider (V3.1 F-41)。
 * <p>
 * 承接迁移前 {@code WebhookController.loadOwned} 的口径(管理端点 GET/PUT/DELETE),
 * 越权返回码由 403 统一为 404。
 * <p>
 * 注意: 触发端点 {@code POST /api/webhooks/trigger/**} 靠 token+secret 鉴权,
 * <b>不走本 Provider</b>(SecretConfig 中已 permitAll)。
 */
@Component
@RequiredArgsConstructor
public class WebhookOwnershipProvider implements ResourceOwnershipProvider {

    private final WebhookEndpointRepository webhookEndpointRepository;

    @Override
    public ResourceType type() {
        return ResourceType.WEBHOOK;
    }

    @Override
    public Ownership load(String id) {
        return webhookEndpointRepository.findById(id)
                .map(endpoint -> Ownership.of(endpoint.getUserId()))
                .orElse(null);
    }

    @Override
    public String notFoundMessage(String id) {
        return "Webhook 不存在";
    }
}
