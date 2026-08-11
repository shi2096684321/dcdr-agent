package com.dcdr.agent.rag.reader;

import com.dcdr.agent.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文档解析器注册中心 (V2.1 RAG)。
 * <p>
 * 按 文件扩展名 路由到对应 {@link DocumentReader}。
 * 启动时由 {@code RagAutoConfiguration} 把所有 Reader 实例注册进来。
 */
@Slf4j
@Component
public class DocumentReaderRegistry {

    private final Map<String, DocumentReader> readers = new HashMap<>();

    public void register(DocumentReader reader) {
        for (String ext : reader.supportedExtensions()) {
            String key = ext.toLowerCase(Locale.ROOT);
            readers.put(key, reader);
        }
        log.info("注册文档解析器: {} -> 扩展名 {}", reader.getClass().getSimpleName(), reader.supportedExtensions());
    }

    /** 按扩展名获取 reader, 不存在抛业务异常 */
    public DocumentReader get(String extension) {
        if (extension == null) {
            throw new BusinessException(400, "文件扩展名为空", "UNSUPPORTED_FORMAT");
        }
        String ext = extension.toLowerCase(Locale.ROOT);
        // 兼容带点传入
        if (ext.startsWith(".")) ext = ext.substring(1);
        DocumentReader r = readers.get(ext);
        if (r == null) {
            throw new BusinessException(400, "不支持的文件格式: " + extension
                    + " (支持: " + supportedExtensions() + ")", "UNSUPPORTED_FORMAT");
        }
        return r;
    }

    /** 所有已注册 reader 支持的扩展名集合 */
    public Set<String> supportedExtensions() {
        return readers.keySet();
    }
}
