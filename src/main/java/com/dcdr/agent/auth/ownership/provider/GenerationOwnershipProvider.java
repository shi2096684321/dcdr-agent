package com.dcdr.agent.auth.ownership.provider;

import com.dcdr.agent.auth.ownership.Ownership;
import com.dcdr.agent.auth.ownership.ResourceOwnershipProvider;
import com.dcdr.agent.auth.ownership.ResourceType;
import com.dcdr.agent.repository.GenerationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 生成记录归属 Provider (V3.1 F-41)。
 * <p>
 * 生成记录无自身归属字段, 沿 {@code generation.projectId} 借用所属项目的归属,
 * 与 {@link DocumentOwnershipProvider} 借用知识库归属的做法一致。
 */
@Component
@RequiredArgsConstructor
public class GenerationOwnershipProvider implements ResourceOwnershipProvider {

    private final GenerationRepository generationRepository;
    private final ProjectOwnershipProvider projectOwnershipProvider;

    @Override
    public ResourceType type() {
        return ResourceType.GENERATION;
    }

    @Override
    public Ownership load(String id) {
        return generationRepository.findById(id)
                .map(generation -> projectOwnershipProvider.load(generation.getProjectId()))
                .orElse(null);
    }

    @Override
    public String notFoundMessage(String id) {
        return "生成记录不存在";
    }
}
