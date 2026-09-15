package com.dcdr.agent.auth.ownership.provider;

import com.dcdr.agent.auth.ownership.Ownership;
import com.dcdr.agent.auth.ownership.ResourceOwnershipProvider;
import com.dcdr.agent.auth.ownership.ResourceType;
import com.dcdr.agent.repository.WorkflowDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 工作流定义归属 Provider (V3.1 F-41)。
 * <p>
 * 与 Agent 同构: 模板对所有用户可见且仅可克隆, 写操作仅 admin;
 * 非模板工作流仅创建者可读写。
 */
@Component
@RequiredArgsConstructor
public class WorkflowOwnershipProvider implements ResourceOwnershipProvider {

    private final WorkflowDefinitionRepository workflowDefinitionRepository;

    @Override
    public ResourceType type() {
        return ResourceType.WORKFLOW;
    }

    @Override
    public Ownership load(String id) {
        return workflowDefinitionRepository.findById(id)
                .map(workflow -> workflow.isTemplate()
                        ? Ownership.publicRead(workflow.getUserId())
                        : Ownership.of(workflow.getUserId()))
                .orElse(null);
    }

    @Override
    public String notFoundMessage(String id) {
        return "工作流不存在";
    }
}
