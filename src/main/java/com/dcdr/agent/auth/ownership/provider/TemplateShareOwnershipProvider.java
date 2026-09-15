package com.dcdr.agent.auth.ownership.provider;

import com.dcdr.agent.auth.ownership.Ownership;
import com.dcdr.agent.auth.ownership.ResourceOwnershipProvider;
import com.dcdr.agent.auth.ownership.ResourceType;
import com.dcdr.agent.repository.TemplateShareRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 模板市场分享记录归属 Provider (V3.1 F-41)。
 * <p>
 * 归属者为「分享者」。用于 {@code DELETE /api/market/templates/{id}}(下架分享)的归属校验 ——
 * 迁移前由 {@code TemplateMarketService.unshare} 内联判断(越权 403), 现统一为 404。
 * <p>
 * 注意: 市场模板的「克隆」与「评分」是面向全体登录用户的公共操作, <b>不做</b>归属校验, 故不使用本 Provider。
 */
@Component
@RequiredArgsConstructor
public class TemplateShareOwnershipProvider implements ResourceOwnershipProvider {

    private final TemplateShareRepository templateShareRepository;

    @Override
    public ResourceType type() {
        return ResourceType.TEMPLATE_SHARE;
    }

    @Override
    public Ownership load(String id) {
        return templateShareRepository.findById(id)
                .map(share -> Ownership.of(share.getUserId()))
                .orElse(null);
    }

    @Override
    public String notFoundMessage(String id) {
        return "模板分享不存在";
    }
}
