package com.dcdr.agent.auth.ownership.provider;

import com.dcdr.agent.auth.ownership.Ownership;
import com.dcdr.agent.auth.ownership.ResourceOwnershipProvider;
import com.dcdr.agent.auth.ownership.ResourceType;
import com.dcdr.agent.repository.UserModelConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 用户自定义模型配置归属 Provider (V3.1 F-41)。
 * <p>
 * 该资源含用户自己的 apiKey 密文, 归属隔离要求最严: 不做 admin 放行以外的任何公开,
 * 越权返回码保持迁移前的 404。
 */
@Component
@RequiredArgsConstructor
public class UserModelOwnershipProvider implements ResourceOwnershipProvider {

    private final UserModelConfigRepository userModelConfigRepository;

    @Override
    public ResourceType type() {
        return ResourceType.USER_MODEL;
    }

    @Override
    public Ownership load(String id) {
        return userModelConfigRepository.findById(id)
                .map(config -> Ownership.of(config.getUserId()))
                .orElse(null);
    }

    @Override
    public String notFoundMessage(String id) {
        return "模型配置不存在";
    }
}
