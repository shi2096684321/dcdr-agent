package com.example.agent.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 统一响应结果封装
 */
@Data
@Schema(description = "统一响应结果")
public class Result<T> {

    @Schema(description = "业务状态码，200 表示成功", example = "200")
    private int code;
    @Schema(description = "状态描述", example = "success")
    private String message;
    @Schema(description = "业务数据，失败时为 null")
    private T data;
    @Schema(description = "错误标识，系统异常时为 INTERNAL_ERROR")
    private String error;

    private Result(int code, String message, T data, String error) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.error = error;
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(200, "success", data, null);
    }

    public static <T> Result<T> success() {
        return new Result<>(200, "success", null, null);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null, null);
    }

    public static <T> Result<T> error(int code, String message, String error) {
        return new Result<>(code, message, null, error);
    }
}
