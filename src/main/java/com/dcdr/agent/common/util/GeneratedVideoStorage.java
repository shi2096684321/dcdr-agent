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
 * 生成视频存储: 将远程视频 URL 下载落盘到本地 video 目录,
 * 返回长期可访问的内网 URL, 避免厂商临时链接过期。
 * <p>
 * 与 {@link GeneratedImageStorage} 对称, 专用于视频文件。
 */
@Component
public class GeneratedVideoStorage {

    private static final Logger log = LoggerFactory.getLogger(GeneratedVideoStorage.class);
    private final Path dir;
    private final HttpClient httpClient;

    public GeneratedVideoStorage(@Value("${agent.storage.video-dir:./data/videos}") String videoDir) {
        this.dir = Paths.get(videoDir);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 将远程视频 URL 下载到本地, 并返回可访问的内网 URL。
     * 非 http(s) URL 或下载异常时原样返回。
     */
    public String store(String remoteUrl) {
        if (remoteUrl == null || (!remoteUrl.startsWith("http://") && !remoteUrl.startsWith("https://"))) {
            return remoteUrl;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(remoteUrl))
                    .timeout(Duration.ofMinutes(3))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET().build();
            HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200 || resp.body() == null) {
                log.warn("下载生成视频失败, 状态码={}, url={}", resp.statusCode(), remoteUrl);
                return remoteUrl;
            }
            byte[] data = resp.body().readAllBytes();
            String ext = resolveExt(remoteUrl, resp.headers().firstValue("Content-Type").orElse(""));
            String dateDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            Path targetDir = dir.resolve(dateDir);
            Files.createDirectories(targetDir);
            String filename = UUID.randomUUID() + "." + ext;
            Path target = targetDir.resolve(filename);
            Files.write(target, data);
            String localUrl = "/api/files/videos/" + dateDir + "/" + filename;
            log.info("生成视频已落盘: {} -> {}", remoteUrl, localUrl);
            return localUrl;
        } catch (IOException e) {
            log.warn("下载生成视频 IO 异常, 回退为原始 URL: {}", remoteUrl, e);
            return remoteUrl;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("下载生成视频被中断, 回退为原始 URL: {}", remoteUrl, e);
            return remoteUrl;
        } catch (Exception e) {
            log.warn("下载生成视频异常, 回退为原始 URL: {}", remoteUrl, e);
            return remoteUrl;
        }
    }

    private String resolveExt(String url, String contentType) {
        String u = url.split("\\?")[0].toLowerCase();
        if (u.endsWith(".mp4")) return "mp4";
        if (u.endsWith(".webm")) return "webm";
        if (u.endsWith(".mov")) return "mov";
        if (contentType.contains("mp4")) return "mp4";
        if (contentType.contains("webm")) return "webm";
        if (contentType.contains("quicktime")) return "mov";
        return "mp4";
    }
}
