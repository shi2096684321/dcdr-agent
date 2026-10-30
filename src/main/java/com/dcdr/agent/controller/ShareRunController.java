package com.dcdr.agent.controller;

import com.dcdr.agent.entity.ScheduledTaskRunEntity;
import com.dcdr.agent.repository.ScheduledTaskRepository;
import com.dcdr.agent.repository.ScheduledTaskRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 定时任务执行详情页 (V3.3 F-50)。
 * <p>
 * 推送消息里的"查看完整结果"指向这里：IM 里只放 300 字概览，全文在这个页面上看。
 * <p>
 * <b>为什么是免登录的裸页面</b>：
 * <ul>
 *   <li>手机上点开 IM 里的链接时通常没有 JWT（浏览器里没有登录态），
 *       如果要求登录，用户点开只会看到登录页，等于链接没用；</li>
 *   <li>路径刻意放在 {@code /share/**} 而不是 {@code /api/**} —— 后者在 SecurityConfig 里
 *       被 {@code .pathMatchers("/api/**").authenticated()} 拦下，要额外开口子；</li>
 *   <li>访问控制靠 32 位随机 token（UUID 去横线），配合可配置的过期时间。
 *       这是"分享链接"模型：拿到链接就能看，因此<b>输出内容敏感的部署应把 share-base-url 留空</b>，
 *       只推概览。</li>
 * </ul>
 * <p>
 * 正文用 {@code <pre>} 原样展示而不是渲染 markdown：渲染需要引入 markdown 库，
 * 而 Agent 输出里可能包含任意 HTML，交给前端渲染就得处理 XSS；纯文本 + 转义最稳。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ShareRunController {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ScheduledTaskRunRepository runRepository;
    private final ScheduledTaskRepository taskRepository;

    @GetMapping("/share/runs/{token}")
    public Mono<ResponseEntity<String>> page(@PathVariable String token) {
        // 查库是阻塞调用，必须离开 reactor-http-nio
        return Mono.fromCallable(() -> render(token))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ResponseEntity<String> render(String token) {
        ScheduledTaskRunEntity run = runRepository.findByShareToken(token).orElse(null);
        if (run == null) {
            return html(404, notFoundPage("链接无效或已被清理"));
        }
        if (run.getShareExpiresAt() != null && run.getShareExpiresAt().isBefore(LocalDateTime.now())) {
            return html(404, notFoundPage("链接已过期"));
        }
        String taskName = taskRepository.findById(run.getTaskId())
                .map(t -> t.getName())
                .orElse("定时任务");
        return html(200, detailPage(taskName, run));
    }

    private String detailPage(String taskName, ScheduledTaskRunEntity run) {
        boolean ok = !"failed".equalsIgnoreCase(run.getStatus());
        String statusText = ok ? "执行成功" : "执行失败";
        String statusClass = ok ? "ok" : "bad";

        StringBuilder meta = new StringBuilder();
        meta.append("<span>").append(esc(formatTime(run.getFinishedAt()))).append("</span>");
        meta.append("<span class=\"").append(statusClass).append("\">").append(statusText).append("</span>");
        meta.append("<span>耗时 ").append(esc(formatDuration(run.getDurationMs()))).append("</span>");

        String body = ok ? run.getOutput() : run.getError();
        if (body == null || body.isBlank()) body = ok ? "(本次执行没有输出)" : "(没有错误详情)";

        return DETAIL_PAGE.formatted(
                esc(taskName),
                STYLE,
                esc(taskName),
                meta,
                esc(body));
    }

    private static String notFoundPage(String reason) {
        return NOT_FOUND_PAGE.formatted(STYLE, esc(reason));
    }

    private static ResponseEntity<String> html(int status, String body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .body(body);
    }

    // ===== 文本处理 =====

    /** HTML 转义：正文来自 Agent 输出，可能含任意标签，必须转义后再放进 pre（包可见，便于单测） */
    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String formatTime(LocalDateTime t) {
        return (t == null ? LocalDateTime.now() : t).format(TIME_FMT);
    }

    private static String formatDuration(long ms) {
        if (ms < 0) return "-";
        if (ms < 1000) return ms + " ms";
        if (ms < 60_000) return String.format(Locale.ROOT, "%.1f s", ms / 1000.0);
        return String.format(Locale.ROOT, "%d 分 %d 秒", ms / 60_000, (ms % 60_000) / 1000);
    }

    // ===== 页面 =====

    private static final String STYLE = """
            *{box-sizing:border-box}
            body{margin:0;padding:24px 16px 64px;background:#f5f5f4;color:#1f1f1f;
              font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;
              -webkit-text-size-adjust:100%}
            .wrap{max-width:720px;margin:0 auto;background:#fff;border:1px solid #e5e5e0;border-radius:12px;padding:24px}
            h1{font-size:18px;font-weight:600;margin:0 0 8px}
            .meta{font-size:13px;color:#6b6b66;margin-bottom:20px}
            .meta span+span::before{content:" · ";color:#b4b2a9}
            .ok{color:#0f6e56}
            .bad{color:#a32d2d}
            pre{white-space:pre-wrap;word-break:break-word;margin:0;font-size:13px;line-height:1.75;
              font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
            .hint{margin-top:16px;padding-top:12px;border-top:1px solid #f0efe9;font-size:12px;color:#8b8b85}
            .empty{font-size:14px;color:#6b6b66;line-height:1.8}
            """;

    private static final String DETAIL_PAGE = """
            <!doctype html>
            <html lang="zh-CN"><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>%s</title>
            <style>%s</style>
            </head><body><div class="wrap">
            <h1>%s</h1>
            <div class="meta">%s</div>
            <pre>%s</pre>
            </div></body></html>
            """;

    private static final String NOT_FOUND_PAGE = """
            <!doctype html>
            <html lang="zh-CN"><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>链接不可用</title>
            <style>%s</style>
            </head><body><div class="wrap">
            <h1>链接不可用</h1>
            <div class="empty">%s</div>
            </div></body></html>
            """;
}
