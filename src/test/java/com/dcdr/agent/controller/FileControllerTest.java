package com.dcdr.agent.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文件访问端点越界校验测试。
 * <p>
 * 这三个端点都在匿名白名单里, 路径段由请求方完全控制, 错判一次就是任意文件读取。
 * 所以这里测的不是"正常路径能取到"(那是最不容易错的部分), 而是各种"看起来像路径"的输入
 * 是否都被判成不存在。
 */
class FileControllerTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("正常相对路径: 命中根目录下的文件")
    void resolvesRegularFile() throws IOException {
        Path f = root.resolve("2026/10/29/a.png");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "x");

        Path got = FileController.resolveWithin(root.toString(), "2026/10/29/a.png");
        assertNotNull(got);
        assertEquals(f.toRealPath(), got.toRealPath());
    }

    @Test
    @DisplayName("每段允许带前导斜杠(URL 写法), 去掉后正常解析")
    void stripsLeadingSlash() throws IOException {
        Files.writeString(root.resolve("a.txt"), "x");
        assertNotNull(FileController.resolveWithin(root.toString(), "/a.txt"));
        assertNotNull(FileController.resolveWithin(root.toString(), "a.txt"));
    }

    @Test
    @DisplayName("拿不到的四种输入: 越界 / 绝对路径 / 空段 / NUL")
    void rejectsEscapes() throws IOException {
        Files.writeString(root.resolve("a.txt"), "x");

        assertNull(FileController.resolveWithin(root.toString(), "../a.txt"), "../ 必须拦住");
        assertNull(FileController.resolveWithin(root.toString(), "2026/../../a.txt"), "夹在中间的 ../ 也要拦住");
        assertNull(FileController.resolveWithin(root.toString(), root.resolve("a.txt").toString()),
                "绝对路径不应被当成相对路径解析");
        assertNull(FileController.resolveWithin(root.toString(), ""));
        assertNull(FileController.resolveWithin(root.toString(), "a\0b"));
    }

    @Test
    @DisplayName("多段形态: 任何一段越界都整体拒绝")
    void rejectsEscapeInAnySegment() throws IOException {
        Files.createDirectories(root.resolve("2026"));
        Files.writeString(root.resolve("2026/a.txt"), "x");

        assertNotNull(FileController.resolveWithin(root.toString(), "2026", "a.txt"));
        assertNull(FileController.resolveWithin(root.toString(), "..", "a.txt"));
        assertNull(FileController.resolveWithin(root.toString(), "2026", "..", "..", "a.txt"));
    }

    @Test
    @DisplayName("目录与不存在的路径都按不存在处理(不区分, 不泄露存在性)")
    void rejectsDirectoryAndMissing() throws IOException {
        Files.createDirectories(root.resolve("dir"));
        assertNull(FileController.resolveWithin(root.toString(), "dir"));
        assertNull(FileController.resolveWithin(root.toString(), "nope.png"));
    }

    @Test
    @DisplayName("符号链接指向根目录之外: 必须拒绝")
    void rejectsSymlinkEscape() throws IOException {
        Path outside = Files.createTempFile("outside", ".txt");
        try {
            Files.writeString(outside, "secret");
            Path link = root.resolve("link.txt");
            try {
                Files.createSymbolicLink(link, outside);
            } catch (IOException | UnsupportedOperationException e) {
                // Windows 未开开发者模式时建不了链接: 跳过, 不把环境限制算成失败
                return;
            }
            assertNull(FileController.resolveWithin(root.toString(), "link.txt"));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    @DisplayName("端点级: SVG 必须带 CSP 沙箱, 普通图片不带")
    void svgResponseIsSandboxed() throws IOException {
        Path day = root.resolve("generated/2026/10/29");
        Files.createDirectories(day);
        // 用户可控内容里能出现脚本的只有 SVG, 这里就用最直接的注入载荷
        Files.writeString(day.resolve("a.svg"),
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>");
        Files.writeString(day.resolve("b.png"), "png");
        FileController controller = controller(root);

        ResponseEntity<Resource> svg = controller.serveGenerated("2026", "10", "29", "a.svg").block();
        assertNotNull(svg);
        assertEquals(200, svg.getStatusCode().value());
        assertEquals("nosniff", svg.getHeaders().getFirst("X-Content-Type-Options"));
        String csp = svg.getHeaders().getFirst("Content-Security-Policy");
        assertNotNull(csp, "SVG 必须沙箱化, 否则匿名上传的一个 <script> 就在本站源下执行了");
        assertTrue(csp.contains("sandbox"));
        assertTrue(csp.contains("default-src 'none'"));

        // 位图没有脚本能力, 加 CSP 只会干扰正常展示
        ResponseEntity<Resource> png = controller.serveGenerated("2026", "10", "29", "b.png").block();
        assertNotNull(png);
        assertEquals(200, png.getStatusCode().value());
        assertEquals("nosniff", png.getHeaders().getFirst("X-Content-Type-Options"));
        assertNull(png.getHeaders().getFirst("Content-Security-Policy"));

        ResponseEntity<Resource> missing = controller.serveGenerated("2026", "10", "29", "nope.png").block();
        assertNotNull(missing);
        assertEquals(404, missing.getStatusCode().value());
    }

    @Test
    @DisplayName("端点级: 上传目录越界按不存在处理, 不泄露文件是否存在")
    void uploadTraversalReturnsNotFound() throws IOException {
        Path uploads = root.resolve("uploads/2026/10/29");
        Files.createDirectories(uploads);
        Files.writeString(uploads.resolve("ok.png"), "png");
        Files.writeString(root.resolve("secret.txt"), "top secret");
        FileController controller = controller(root);

        // {*filepath} 捕获出来的形态带前导斜杠, 正常请求必须能取到
        assertEquals(200, controller.download("/2026/10/29/ok.png").block().getStatusCode().value());
        // 越界与不存在给同一个 404: 用 403 等于告诉对方"文件在, 只是你不能读"
        assertEquals(404, controller.download("/../../secret.txt").block().getStatusCode().value());
        assertEquals(404, controller.download("/nope.png").block().getStatusCode().value());
    }

    /**
     * 直接 new 出来再注入 {@code @Value} 字段。
     * <p>
     * 用 {@code WebTestClient.bindToController} 起真实请求反而测不了: 它会对传入的实例再做一次
     * 依赖注入, 把这里设好的目录覆盖成 {@code @Value} 的默认值({@code ./data/generated}),
     * 于是"文件明明存在却 404"。路由匹配不是本测试的目标, 直接调方法即可。
     */
    private static FileController controller(Path root) {
        FileController controller = new FileController();
        ReflectionTestUtils.setField(controller, "uploadDir", root.resolve("uploads").toString());
        ReflectionTestUtils.setField(controller, "generatedDir", root.resolve("generated").toString());
        ReflectionTestUtils.setField(controller, "videoDir", root.resolve("videos").toString());
        return controller;
    }
}
