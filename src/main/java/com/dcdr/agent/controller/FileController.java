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

    @Value("${agent.storage.video-dir:./data/videos}")
    private String videoDir;

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
    public Mono<ResponseEntity<Resource>> download(@Parameter(hidden = true) @PathVariable String filepath) {
        // {*filepath} 捕获的是含前导斜杠的整段路径, 先去掉再按「目录 + 多段」解析,
        // 不能被它看起来像绝对路径这一点骗过去(那是 URL 的写法, 不是文件系统的)
        String rel = filepath != null && filepath.startsWith("/") ? filepath.substring(1) : filepath;
        return serveWithin(uploadDir, resolveContentType(rel), rel);
    }

    @Operation(summary = "访问生成的图片", description = "访问文生图落盘后的本地图片(长期有效)")
    @GetMapping("/generated/{year}/{month}/{day}/{file}")
    public Mono<ResponseEntity<Resource>> serveGenerated(@Parameter(description = "年, 如 2026") @PathVariable String year,
                                                         @Parameter(description = "月, 如 07") @PathVariable String month,
                                                         @Parameter(description = "日, 如 23") @PathVariable String day,
                                                         @Parameter(description = "文件名") @PathVariable String file) {
        return serveWithin(generatedDir, resolveContentType(file), year, month, day, file);
    }

    @Operation(summary = "访问生成的视频", description = "访问文生视频落盘后的本地视频(长期有效)")
    @GetMapping("/videos/{year}/{month}/{day}/{file}")
    public Mono<ResponseEntity<Resource>> serveVideo(@Parameter(description = "年, 如 2026") @PathVariable String year,
                                                     @Parameter(description = "月, 如 07") @PathVariable String month,
                                                     @Parameter(description = "日, 如 23") @PathVariable String day,
                                                     @Parameter(description = "文件名") @PathVariable String file) {
        return serveWithin(videoDir, resolveVideoContentType(file), year, month, day, file);
    }

    /**
     * SVG 响应的 CSP: 禁一切外部资源与脚本, 并沙箱化。
     * <p>
     * <b>为什么只给 SVG 加</b>: 上传接口不限制类型, 而 SVG 是唯一一个"图片外壳、内部能执行脚本"的格式 ——
     * 匿名上传一个带 {@code <script>} 的 .svg, 再把这个端点 URL 发给别人, 脚本就在本站源下执行了
     * (存储型 XSS)。{@code nosniff} 拦不住它, 因为 {@code image/svg+xml} 本来就是它该有的类型。
     * <p>
     * <b>为什么不改成 attachment</b>: 那样 {@code <img src="...">} 也渲染不出来, 会直接弄坏现有的
     * SVG 展示。加 CSP 既保住图片展示(在 img 上下文里脚本本来就不会跑), 又堵掉直接访问执行脚本这条路。
     * {@code style-src 'unsafe-inline'} 是留给 SVG 内联样式的, 否则大量正常图标会显示成一片黑。
     */
    private static final String SVG_CSP = "default-src 'none'; style-src 'unsafe-inline'; sandbox";

    /**
     * 把「根目录 + 若干路径段」解析成一个可返回的资源, 越界与不存在一律当作 404。
     * <p>
     * <b>为什么必须集中到这一个方法</b>: 这三个端点都在 {@code agent.auth.permit-paths} 的匿名白名单里,
     * 路径段完全由请求方控制。此前直接 {@code Paths.get(root, 用户输入)} 拼接有两个漏洞 ——
     * 一是 {@code ../../} 能把 {@code ./data/uploads} 之外的文件读出来(例如配置里写死的密钥);
     * 二是没有归一化就无法判断"解析后的目标是否仍在根目录下", 而这正是路径越界的唯一可靠判据。
     * <p>
     * 三道判断的顺序不能调: 先归一化比前缀(拦 {@code ../}), 再校验真实路径
     * (拦符号链接/目录联接指向外部, {@code normalize()} 是纯字符串运算, 解析不了链接),
     * 最后才判文件类型。
     *
     * @param contentType 响应头里的类型(由调用方按文件名算好)
     * @param segments    从根目录往下的各段; 首段若带前导斜杠会被去掉
     * @return 命中时返回 200 响应; 越界/不存在/非普通文件返回 404
     */
    private Mono<ResponseEntity<Resource>> serveWithin(String rootDir, String contentType, String... segments) {
        Path file = resolveWithin(rootDir, segments);
        if (file == null) return Mono.just(ResponseEntity.notFound().build());
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                // 上传内容是用户可控字节: 禁止浏览器按嗅探结果改变解释方式
                .header("X-Content-Type-Options", "nosniff");
        if (contentType != null && contentType.startsWith("image/svg")) {
            builder.header("Content-Security-Policy", SVG_CSP);
        }
        return Mono.just(builder.body(new FileSystemResource(file)));
    }

    /**
     * 解析并校验目标文件, 越界返回 {@code null}。
     * <p>
     * 包内可见(package-private)而非 private: 越界判定是这个 Controller 里唯一需要单测的逻辑,
     * 起一个完整的 WebTestClient 去验它太贵, 而错判的后果是任意文件读取。
     */
    static Path resolveWithin(String rootDir, String... segments) {
        Path root = Paths.get(rootDir).toAbsolutePath().normalize();
        Path target = root;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment == null) return null;
            // 只允许首段带一个前导斜杠: 那是 URL 的写法(/{*filepath} 捕获出来长这样), 不是文件系统的
            String s = i == 0 && segment.startsWith("/") ? segment.substring(1) : segment;
            // NUL 截断: 某些文件系统 API 会把它后面的内容当成另一件事
            if (s.isEmpty() || s.indexOf('\0') >= 0) return null;
            // 盘符 / UNC / 仍以分隔符开头: URL 的路径段不可能是这些。
            // 不拦的话, Windows 上 resolve("C:\\x") 会直接替换掉整条路径 —— 虽然随后的
            // startsWith(root) 仍能拦住指向根目录之外的目标, 但"绝对路径被当相对路径用"
            // 本身就不该发生, 这类语义缺口是后续改动的隐患。
            if (s.indexOf(':') >= 0 || s.startsWith("/") || s.startsWith("\\")) return null;
            target = target.resolve(s);
        }
        target = target.normalize();
        if (!target.startsWith(root)) {
            log.warn("拒绝越界的文件访问: root={}, 请求路径={}", root, String.join("/", segments));
            return null;
        }
        if (!Files.isRegularFile(target)) return null;
        try {
            // 两侧都取真实路径再比: 链接目标、以及 Windows 下大小写归一都靠这一步,
            // 而且 toRealPath 的结果大小写形式一致, 不会出现"同一目录因写法不同被判越界"
            if (!target.toRealPath().startsWith(root.toRealPath())) {
                log.warn("拒绝经符号链接逃逸的文件访问: root={}, 请求路径={}", root, String.join("/", segments));
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        return target;
    }

    private String resolveVideoContentType(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mov")) return "video/quicktime";
        return "video/mp4";
    }

    private String resolveContentType(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG_VALUE;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF_VALUE;
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        return MediaType.IMAGE_JPEG_VALUE;
    }
}
