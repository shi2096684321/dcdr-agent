package com.dcdr.agent.auth.ownership.provider;

import com.dcdr.agent.auth.ownership.Ownership;
import com.dcdr.agent.auth.ownership.ResourceOwnershipProvider;
import com.dcdr.agent.auth.ownership.ResourceType;
import com.dcdr.agent.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 技能归属 Provider (V3.1 专家体系)。
 * <p>
 * 归属模型与 Agent 一致: 自己创建的私有技能仅自己可读写; 公开技能他人可读、只有归属者可写。
 * "公开可读"由 {@link Ownership#publicRead} 表达, 无需覆写 {@code canAccess}。
 * <p>
 * 越权与不存在统一 404, 防止用响应差异枚举他人技能。
 */
@Component
@RequiredArgsConstructor
public class SkillOwnershipProvider implements ResourceOwnershipProvider {

    private final SkillRepository skillRepository;

    @Override
    public ResourceType type() {
        return ResourceType.SKILL;
    }

    @Override
    public Ownership load(String id) {
        return skillRepository.findById(id)
                .map(s -> s.isPublic() ? Ownership.publicRead(s.getUserId()) : Ownership.of(s.getUserId()))
                .orElse(null);
    }
}
