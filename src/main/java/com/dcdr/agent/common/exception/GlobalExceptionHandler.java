package com.dcdr.agent.common.exception;

import com.dcdr.agent.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

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

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
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
    public Result<Void> handleException(Exception e) {
        // 连接重置/断开类异常(含 Netty reactor 包装)统一降级, 不刷 ERROR 日志
        if (isClientAbort(e)) {
            log.debug("客户端连接中断(可忽略): {}", e.getMessage());
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
                    || msg.contains("cancel")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
