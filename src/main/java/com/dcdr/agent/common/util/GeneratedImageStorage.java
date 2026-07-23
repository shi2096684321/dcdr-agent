package com.dcdr.agent.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 生成图存储: 文生图模型返回的多为厂商临时 URL(约 1 小时失效),
 * 这里将其下载落盘到本地, 并把 resultUrl 替换为长期可访问的内网 URL,
 * 使创作历史中的图片不再因厂商链接过期而裂图。
 * 下载失败或 URL 非 http(s) 时原样返回, 不影响主流程。
 */
@Component
public class GeneratedImageStorage {

    private static final Logger log = LoggerFactory.getLogger(GeneratedImageStorage.class);
    private final Path dir;
    private final HttpClient httpClient;

    public GeneratedImageStorage(@Value("${agent.storage.generated-dir:./data/generated}") String generatedDir) {
        this.dir = Paths.get(generatedDir);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 将远程图片 URL 下载到本地, 并返回可访问的内网 URL。
     * 非 http(s) URL 或下载异常时原样返回。
     */
    public String store(String remoteUrl) {
        if (remoteUrl == null || (!remoteUrl.startsWith("http://") && !remoteUrl.startsWith("https://"))) {
            return remoteUrl;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(remoteUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET().build();
            HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200 || resp.body() == null) {
                log.warn("下载生成图失败, 状态码={}, url={}", resp.statusCode(), remoteUrl);
                return remoteUrl;
            }
            byte[] data = resp.body().readAllBytes();
            String contentType = resp.headers().firstValue("Content-Type").orElse("");
            String ext = resolveExt(remoteUrl, contentType);
            String dateDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            Path targetDir = dir.resolve(dateDir);
            Files.createDirectories(targetDir);
            String filename = UUID.randomUUID() + "." + ext;
            Path target = targetDir.resolve(filename);
            Files.write(target, data);
            String localUrl = "/api/files/generated/" + dateDir + "/" + filename;
            log.info("生成图已落盘: {} -> {}", remoteUrl, localUrl);
            return localUrl;
        } catch (IOException e) {
            log.warn("下载生成图 IO 异常, 回退为原始 URL: {}", remoteUrl, e);
            return remoteUrl;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("下载生成图被中断, 回退为原始 URL: {}", remoteUrl, e);
            return remoteUrl;
        } catch (Exception e) {
            log.warn("下载生成图异常, 回退为原始 URL: {}", remoteUrl, e);
            return remoteUrl;
        }
    }

    private String resolveExt(String url, String contentType) {
        String u = url.split("\\?")[0].toLowerCase();
        if (u.endsWith(".png")) return "png";
        if (u.endsWith(".jpg") || u.endsWith(".jpeg")) return "jpg";
        if (u.endsWith(".webp")) return "webp";
        if (u.endsWith(".gif")) return "gif";
        if (contentType.contains("png")) return "png";
        if (contentType.contains("webp")) return "webp";
        if (contentType.contains("gif")) return "gif";
        return "jpg";
    }
}
