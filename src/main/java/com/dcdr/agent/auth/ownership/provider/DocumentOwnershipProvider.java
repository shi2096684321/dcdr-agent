package com.dcdr.agent.auth.ownership.provider;

import com.dcdr.agent.auth.ownership.Ownership;
import com.dcdr.agent.auth.ownership.ResourceOwnershipProvider;
import com.dcdr.agent.auth.ownership.ResourceType;
import com.dcdr.agent.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 知识库文档归属 Provider (V3.1 F-41)。
 * <p>
 * 文档<b>不按自身 userId 判定</b>, 而是沿 {@code doc.kbId} 借用所属知识库的归属口径 ——
 * 这与迁移前 {@code DocumentService} 的行为一致(避免"文档归属者"与"知识库归属者"
 * 不一致时出现权限缝隙, 例如 admin 往用户库里上传文档)。
 */
@Component
@RequiredArgsConstructor
public class DocumentOwnershipProvider implements ResourceOwnershipProvider {

    private final DocumentRepository documentRepository;
    private final KnowledgeBaseOwnershipProvider knowledgeBaseOwnershipProvider;

    @Override
    public ResourceType type() {
        return ResourceType.DOCUMENT;
    }

    @Override
    public Ownership load(String id) {
        return documentRepository.findById(id)
                .map(doc -> knowledgeBaseOwnershipProvider.load(doc.getKbId()))
                .orElse(null);
    }

    @Override
    public String notFoundMessage(String id) {
        return "文档不存在";
    }
}
