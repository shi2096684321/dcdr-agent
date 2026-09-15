package com.dcdr.agent.auth.ownership.provider;

import com.dcdr.agent.auth.ownership.Ownership;
import com.dcdr.agent.auth.ownership.ResourceOwnershipProvider;
import com.dcdr.agent.auth.ownership.ResourceType;
import com.dcdr.agent.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 会话归属 Provider (V3.1 F-41)。
 * <p>
 * 修复 V3.0 遗留缺口: {@code DELETE /api/sessions/{id}}、{@code PUT /{id}/title} 等接口
 * 原先仅校验存在性, 任何登录用户可篡改/删除他人会话。
 */
@Component
@RequiredArgsConstructor
public class SessionOwnershipProvider implements ResourceOwnershipProvider {

    private final SessionRepository sessionRepository;

    @Override
    public ResourceType type() {
        return ResourceType.SESSION;
    }

    @Override
    public Ownership load(String id) {
        return sessionRepository.findById(id)
                .map(session -> Ownership.of(session.getUserId()))
                .orElse(null);
    }
}
