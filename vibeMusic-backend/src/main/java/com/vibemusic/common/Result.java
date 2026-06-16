package com.vibemusic.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一 API 响应封装
 *
 * 所有 Controller 返回此格式，前端解析统一
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    private int code;
    private String message;
    private T data;

    // ---------- 成功 ----------

    public static <T> Result<T> ok() {
        return new Result<>(200, "success", null);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(200, "success", data);
    }

    public static <T> Result<T> ok(String message, T data) {
        return new Result<>(200, message, data);
    }

    // ---------- 失败 ----------

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }

    public static <T> Result<T> error(String message) {
        return new Result<>(400, message, null);
    }

    // ---------- 常用状态码 ----------

    public static <T> Result<T> badRequest(String message) {
        return new Result<>(400, message, null);
    }

    public static <T> Result<T> unauthorized() {
        return new Result<>(401, "未登录或 Token 已过期", null);
    }

    public static <T> Result<T> forbidden() {
        return new Result<>(403, "权限不足", null);
    }

    public static <T> Result<T> notFound() {
        return new Result<>(404, "资源不存在", null);
    }
}
