package com.dcdr.agent.auth.ownership.provider;

import com.dcdr.agent.auth.ownership.Ownership;
import com.dcdr.agent.auth.ownership.ResourceOwnershipProvider;
import com.dcdr.agent.auth.ownership.ResourceType;
import com.dcdr.agent.repository.MemoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 长期记忆归属 Provider (V3.1)。
 * <p>
 * 记忆属于个人隐私数据, 归属校验必须严格: 任何用户不得读取、修改、删除他人记忆。
 * 与其余资源一致, 「不存在」与「越权」统一返回 404, 防止用响应差异枚举他人记忆 ID。
 */
@Component
@RequiredArgsConstructor
public class MemoryOwnershipProvider implements ResourceOwnershipProvider {

    private final MemoryRepository memoryRepository;

    @Override
    public ResourceType type() {
        return ResourceType.MEMORY;
    }

    @Override
    public Ownership load(String id) {
        return memoryRepository.findById(id)
                .map(memory -> Ownership.of(memory.getUserId()))
                .orElse(null);
    }
}
