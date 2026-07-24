package com.dcdr.agent.controller;

import com.dcdr.agent.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@Tag(name = "文件", description = "文件上传与访问接口")
public class FileController {

    @Value("${agent.storage.upload-dir:./data/uploads}")
    private String uploadDir;

    @Value("${agent.storage.generated-dir:./data/generated}")
    private String generatedDir;

    @Operation(summary = "上传文件", description = "上传文件（如图片），返回可访问的 URL 路径")
    @ApiResponse(responseCode = "200", description = "返回文件 url 与 filename",
            content = @Content(schema = @Schema(example = "{\"url\":\"/api/files/uploads/2026/07/01/xxx.png\",\"filename\":\"xxx.png\"}")))
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Result<Map<String, String>>> upload(
            @Parameter(description = "上传的文件", required = true,
                    schema = @Schema(type = "string", format = "binary"))
            @RequestPart("file") FilePart file) {
        if (file.filename().isEmpty()) return Mono.just(Result.error(400, "文件为空"));
        String dateDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        Path dir = Paths.get(uploadDir, dateDir);
        String original = file.filename();
        String ext = "";
        if (original.contains(".")) {
            ext = original.substring(original.lastIndexOf("."));
        }
        String filename = UUID.randomUUID() + ext;
        Path target = dir.resolve(filename);
        return Mono.fromCallable(() -> Files.createDirectories(dir))
                .then(file.transferTo(target))
                .then(Mono.fromCallable(() -> {
                    String url = "/api/files/uploads/" + dateDir + "/" + filename;
                    log.info("文件上传: {} -> {}", original, url);
                    return Result.success(Map.of("url", url, "filename", filename));
                }));
    }

    @Operation(summary = "访问上传的文件", description = "根据日期路径与文件名访问已上传的文件")
    @GetMapping("/uploads/{*filepath}")
    public ResponseEntity<Resource> download(@Parameter(hidden = true) @PathVariable String filepath) {
        String normalized = filepath.startsWith("/") ? filepath.substring(1) : filepath;
        Path path = Paths.get(uploadDir, normalized);
        File f = path.toFile();
        if (!f.exists()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, resolveContentType(f.getName()))
                .body(new FileSystemResource(f));
    }

    @Operation(summary = "访问生成的图片", description = "访问文生图落盘后的本地图片(长期有效)")
    @GetMapping("/generated/{year}/{month}/{day}/{file}")
    public ResponseEntity<Resource> serveGenerated(@Parameter(description = "年, 如 2026") @PathVariable String year,
                                                   @Parameter(description = "月, 如 07") @PathVariable String month,
                                                   @Parameter(description = "日, 如 23") @PathVariable String day,
                                                   @Parameter(description = "文件名") @PathVariable String file) {
        Path path = Paths.get(generatedDir, year, month, day, file);
        File f = path.toFile();
        if (!f.exists()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, resolveContentType(file))
                .body(new FileSystemResource(f));
    }

    private String resolveContentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG_VALUE;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF_VALUE;
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        return MediaType.IMAGE_JPEG_VALUE;
    }
}
