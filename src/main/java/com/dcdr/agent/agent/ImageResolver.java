package com.dcdr.agent.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 图片解析器 - 将发送给视觉模型的图片 URL 统一解析为模型可访问的形式。
 * <p>
 * 上传接口返回的是本地相对路径(如 /api/files/uploads/...), 外部视觉模型(通义/OpenAI 等)
 * 无法直接访问。此处把这类本地路径读取为字节并内嵌为 base64 data URL, 确保模型能读到图片,
 * 且不依赖部署环境是否对外暴露文件服务。
 */
@Slf4j
@Component
public class ImageResolver {

    @Value("${agent.storage.upload-dir:./data/uploads}")
    private String uploadDir;

    /** 上传文件对外访问的 URL 前缀(对应 FileController 的 /api/files/uploads) */
    @Value("${agent.storage.upload-url-prefix:/api/files/uploads}")
    private String uploadUrlPrefix;

    /**
     * 将图片 URL 列表解析为模型可访问形式:
     * - http(s):// 或 data: 开头 → 原样返回
     * - 本地相对路径 → 去掉 URL 前缀后读取文件, 内嵌为 base64 data URL
     */
    public List<String> resolveForModel(List<String> urls) {
        if (urls == null || urls.isEmpty()) return urls;
        List<String> out = new ArrayList<>(urls.size());
        for (String u : urls) {
            out.add(resolveOne(u));
        }
        return out;
    }

    private String resolveOne(String url) {
        if (url == null) return null;
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:")) {
            return url;
        }
        try {
            // 去掉 URL 前缀(如 /api/files/uploads/), 仅保留磁盘相对路径(如 2026/07/24/xxx.png)
            String rel = url.startsWith("/") ? url.substring(1) : url;
            String prefix = uploadUrlPrefix.startsWith("/") ? uploadUrlPrefix.substring(1) : uploadUrlPrefix;
            if (!prefix.isEmpty() && rel.startsWith(prefix)) {
                rel = rel.substring(prefix.length());
                if (rel.startsWith("/")) rel = rel.substring(1);
            }
            Path p = Paths.get(uploadDir, rel);
            byte[] bytes = Files.readAllBytes(p);
            String mime = resolveMime(p.getFileName().toString());
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            log.warn("图片解析为 base64 失败, 退回原 url: {}", url, e);
            return url;
        }
    }

    private String resolveMime(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return "image/jpeg";
    }
}
