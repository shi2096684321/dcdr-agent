package com.example.agent.controller;

import com.example.agent.common.Result;
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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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

    @Operation(summary = "上传文件", description = "上传文件（如图片），返回可访问的 URL 路径")
    @ApiResponse(responseCode = "200", description = "返回文件 url 与 filename",
            content = @Content(schema = @Schema(example = "{\"url\":\"/api/files/uploads/2026/07/01/xxx.png\",\"filename\":\"xxx.png\"}")))
    @PostMapping("/upload")
    public Result<Map<String, String>> upload(@Parameter(description = "上传的文件", required = true)
                                                   @RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) return Result.error(400, "文件为空");
        String dateDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        Path dir = Paths.get(uploadDir, dateDir);
        Files.createDirectories(dir);
        String ext = "";
        String original = file.getOriginalFilename();
        if (original != null && original.contains(".")) {
            ext = original.substring(original.lastIndexOf("."));
        }
        String filename = UUID.randomUUID() + ext;
        Path target = dir.resolve(filename);
        file.transferTo(target.toFile());
        String url = "/api/files/uploads/" + dateDir + "/" + filename;
        log.info("文件上传: {} -> {}", original, url);
        return Result.success(Map.of("url", url, "filename", filename));
    }

    @Operation(summary = "访问上传的文件", description = "根据日期路径与文件名访问已上传的文件")
    @GetMapping("/uploads/{date}/{file}")
    public ResponseEntity<Resource> download(@Parameter(description = "日期路径，如 2026/07/01") @PathVariable String date,
                                             @Parameter(description = "文件名") @PathVariable String file) {
        String fullPath = date.replace(",", "/");
        Path path = Paths.get(uploadDir, fullPath, file);
        File f = path.toFile();
        if (!f.exists()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE)
                .body(new FileSystemResource(f));
    }
}
