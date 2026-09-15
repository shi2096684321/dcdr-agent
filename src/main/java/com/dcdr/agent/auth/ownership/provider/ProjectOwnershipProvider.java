package com.dcdr.agent.auth.ownership.provider;

import com.dcdr.agent.auth.ownership.Ownership;
import com.dcdr.agent.auth.ownership.ResourceOwnershipProvider;
import com.dcdr.agent.auth.ownership.ResourceType;
import com.dcdr.agent.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 创作项目归属 Provider (V3.1 F-41)。
 * <p>
 * {@code ProjectEntity} 原先<b>完全没有归属字段</b>, 导致 {@code ProjectController}
 * 的 GET/DELETE/PUT 可操作任意用户项目。V3.1 为实体补上 {@code userId} 字段,
 * 由本 Provider 承接校验; 存量项目 userId 为空, 迁移后仅 admin 或匿名兼容模式可访问。
 */
@Component
@RequiredArgsConstructor
public class ProjectOwnershipProvider implements ResourceOwnershipProvider {

    private final ProjectRepository projectRepository;

    @Override
    public ResourceType type() {
        return ResourceType.PROJECT;
    }

    @Override
    public Ownership load(String id) {
        return projectRepository.findById(id)
                .map(project -> Ownership.of(project.getUserId()))
                .orElse(null);
    }

    @Override
    public String notFoundMessage(String id) {
        return "项目不存在";
    }
}
