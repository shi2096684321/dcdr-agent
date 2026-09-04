package com.dcdr.agent.common.exception;

import com.dcdr.agent.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;

import java.io.IOException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 响应已提交(如文件/视频流式传输已发出 206 PARTIAL_CONTENT)时, 任何错误体都无法再写入:
     * Spring 会按已提交的 Content-Type(如 video/mp4)寻找 Result 的编码器,
     * 触发二次 HttpMessageNotWritableException("No Encoder for Result<Void>")刷 ERROR 日志。
     * 此时仅记录日志、不返回响应体, 交由框架关闭连接。
     */
    private boolean responseCommitted(ServerWebExchange exchange) {
        return exchange != null && exchange.getResponse().isCommitted();
    }

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBusinessException(BusinessException e, ServerWebExchange exchange) {
        if (responseCommitted(exchange)) {
            log.debug("响应已提交, 跳过业务异常响应体: {}", e.getMessage());
            return null;
        }
        log.warn("业务异常: {}", e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e, ServerWebExchange exchange) {
        if (responseCommitted(exchange)) {
            log.debug("响应已提交, 跳过参数异常响应体: {}", e.getMessage());
            return null;
        }
        log.warn("参数异常: {}", e.getMessage());
        return Result.error(400, e.getMessage());
    }

    /**
     * 客户端中断连接(视频/文件流式传输中被取消、刷新、划走)导致的 IO 异常。
     * 此时响应往往已提交(如 206 PARTIAL_CONTENT), 再写错误体会触发二次
     * HttpMessageNotWritableException。此类属正常现象, 降级为 debug 且不返回响应体。
     */
    @ExceptionHandler(IOException.class)
    public Result<Void> handleClientAbort(IOException e) {
        log.debug("客户端连接中断(可忽略, 通常为流式传输中客户端取消): {}", e.getMessage());
        return null;
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e, ServerWebExchange exchange) {
        // 连接重置/断开类异常(含 Netty reactor 包装)统一降级, 不刷 ERROR 日志
        if (isClientAbort(e)) {
            log.debug("客户端连接中断(可忽略): {}", e.getMessage());
            return null;
        }
        if (responseCommitted(exchange)) {
            // 兜底: 未识别为断连、但响应已提交(典型为流式传输中客户端取消引发的非常规异常包装),
            // 错误体写不进去, 只能记录后放弃 —— 否则二次 HttpMessageNotWritableException 会刷 ERROR
            log.warn("流式响应已提交后发生异常(客户端可能已断开): {}", e.getMessage());
            return null;
        }
        log.error("系统异常", e);
        return Result.error(500, "系统内部错误: " + e.getMessage(), "INTERNAL_ERROR");
    }

    /** 判断是否为客户端断开连接类异常(连接重置/断开/取消) */
    private boolean isClientAbort(Throwable e) {
        Throwable t = e;
        while (t != null) {
            String msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
            if (t instanceof IOException
                    || msg.contains("connection reset")
                    || msg.contains("broken pipe")
                    || msg.contains("aborted")
                    || msg.contains("cancel")
                    // 中文 Windows 断连消息: "远程主机强迫关闭了一个现有的连接"
                    || msg.contains("强迫关闭")
                    // 英文 Windows: "An existing connection was forcibly closed by the remote host"
                    || msg.contains("forcibly closed")
                    // reactor-netty: "Connection prematurely closed"
                    || msg.contains("prematurely closed")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
