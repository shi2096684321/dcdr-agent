package com.dcdr.agent.auth.ownership.provider;

import com.dcdr.agent.auth.ownership.Ownership;
import com.dcdr.agent.auth.ownership.ResourceOwnershipProvider;
import com.dcdr.agent.auth.ownership.ResourceType;
import com.dcdr.agent.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 知识库归属 Provider (V3.1 F-41)。
 * <p>
 * 可见性口径(与 {@code KnowledgeBaseService.getAccessible} 完全一致, 迁移后由本类承接):
 * <ul>
 *   <li>{@code USER} 级: 仅创建者可读写;</li>
 *   <li>{@code PLATFORM} 级: 所有已登录用户<b>只读</b>, 写操作仅 admin
 *       (通过 {@code publicRead=true, publicWrite=false} 表达)。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class KnowledgeBaseOwnershipProvider implements ResourceOwnershipProvider {

    /** 平台级可见性标识 */
    public static final String VISIBILITY_PLATFORM = "PLATFORM";

    private final KnowledgeBaseRepository knowledgeBaseRepository;

    @Override
    public ResourceType type() {
        return ResourceType.KNOWLEDGE_BASE;
    }

    @Override
    public Ownership load(String id) {
        return knowledgeBaseRepository.findById(id)
                .map(kb -> VISIBILITY_PLATFORM.equals(kb.getVisibility())
                        ? Ownership.publicRead(kb.getUserId())
                        : Ownership.of(kb.getUserId()))
                .orElse(null);
    }
}
