package com.dcdr.agent.auth.ownership.provider;

import com.dcdr.agent.auth.ownership.Ownership;
import com.dcdr.agent.auth.ownership.ResourceOwnershipProvider;
import com.dcdr.agent.auth.ownership.ResourceType;
import com.dcdr.agent.repository.WorkflowExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 工作流执行记录归属 Provider (V3.1 F-41)。
 * <p>
 * 承接迁移前 {@code WorkflowEngine.loadExecutionForUser} 的口径
 * (resume / approve / cancel / 查询执行详情), 返回码统一为 404。
 */
@Component
@RequiredArgsConstructor
public class WorkflowExecutionOwnershipProvider implements ResourceOwnershipProvider {

    private final WorkflowExecutionRepository workflowExecutionRepository;

    @Override
    public ResourceType type() {
        return ResourceType.WORKFLOW_EXECUTION;
    }

    @Override
    public Ownership load(String id) {
        return workflowExecutionRepository.findById(id)
                .map(execution -> Ownership.of(execution.getUserId()))
                .orElse(null);
    }

    @Override
    public String notFoundMessage(String id) {
        return "执行记录不存在";
    }
}
