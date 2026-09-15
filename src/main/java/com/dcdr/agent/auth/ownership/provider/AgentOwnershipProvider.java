package com.dcdr.agent.auth.ownership.provider;

import com.dcdr.agent.auth.ownership.Ownership;
import com.dcdr.agent.auth.ownership.ResourceOwnershipProvider;
import com.dcdr.agent.auth.ownership.ResourceType;
import com.dcdr.agent.repository.AgentDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 自定义 Agent 归属 Provider (V3.1 F-41)。
 * <p>
 * 可见性口径(迁移前散落在 {@code AgentDefinitionService.isVisible}):
 * {@code isTemplate || isPublic || userId 相同} 即可见。
 * 写操作仅限创建者本人或 admin(模板亦不例外), 由 {@code publicWrite=false} 表达。
 * <p>
 * 迁移后返回码统一为 404(原先读/写越权分别返回 403), 遵循「越权即不存在」原则。
 */
@Component
@RequiredArgsConstructor
public class AgentOwnershipProvider implements ResourceOwnershipProvider {

    private final AgentDefinitionRepository agentDefinitionRepository;

    @Override
    public ResourceType type() {
        return ResourceType.AGENT;
    }

    @Override
    public Ownership load(String id) {
        return agentDefinitionRepository.findById(id)
                .map(agent -> (agent.isPublic() || agent.isTemplate())
                        ? Ownership.publicRead(agent.getUserId())
                        : Ownership.of(agent.getUserId()))
                .orElse(null);
    }

    @Override
    public String notFoundMessage(String id) {
        return "Agent 不存在";
    }
}
