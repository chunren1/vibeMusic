package com.vibemusic.common.exception;

import com.vibemusic.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.slf4j.MDC;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 *
 * 统一拦截异常，返回标准 JSON 格式
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ==================== 参数校验异常 ====================

    /**
     * @Valid 校验失败
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidationException(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return Result.error(400, errors);
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBindException(BindException ex) {
        String errors = ex.getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return Result.error(400, errors);
    }

    // ==================== 鉴权异常 ====================

    @ExceptionHandler(BadCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleBadCredentials(BadCredentialsException ex) {
        return Result.error(401, "用户名或密码错误");
    }

    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleAuthenticationException(AuthenticationException ex) {
        return Result.error(401, ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleAccessDeniedException(AccessDeniedException ex) {
        return Result.error(403, "权限不足");
    }

    /**
     * 缺少必填请求参数
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException ex) {
        return Result.error(400, "缺少必填参数: " + ex.getParameterName());
    }

    /**
     * 请求体 JSON 格式错误 / 反序列化失败
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        return Result.error(400, "请求体格式错误，请检查 JSON 格式");
    }

    /**
     * HTTP 方法不支持
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return Result.error(405, "不支持的请求方法: " + ex.getMethod());
    }

    /**
     * 方法参数校验失败（@Validated on class level）
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleConstraintViolation(ConstraintViolationException ex) {
        String msg = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .reduce((a, b) -> a + ", " + b)
                .orElse("参数校验失败");
        return Result.error(400, msg);
    }

    // ==================== 业务异常 ====================

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException ex, HttpServletResponse response) {
        response.setStatus(ex.getCode());
        return Result.error(ex.getCode(), ex.getMessage());
    }

    // ==================== 基础设施异常 ====================

    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result<Void> handleDataAccessException(DataAccessException ex) {
        String traceId = MDC.get("traceId");
        log.error("[traceId={}] 数据库异常", traceId, ex);
        return Result.error(503, "数据存储暂时不可用 (traceId=" + traceId + ")");
    }

    @ExceptionHandler(RedisConnectionFailureException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result<Void> handleRedisConnectionFailure(RedisConnectionFailureException ex) {
        log.warn("Redis 连接失败: {}", ex.getMessage());
        return Result.error(503, "缓存服务暂时不可用，请稍后重试");
    }

    // ==================== 代码逻辑异常 ====================

    @ExceptionHandler(NullPointerException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleNullPointer(NullPointerException ex) {
        String traceId = MDC.get("traceId");
        log.error("[traceId={}] 空指针异常", traceId, ex);
        return Result.error(500, "服务器内部错误 (traceId=" + traceId + ")");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleIllegalArgument(IllegalArgumentException ex) {
        return Result.error(400, "请求参数错误: " + ex.getMessage());
    }

    // ==================== 兜底异常 ====================

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception ex) {
        String traceId = MDC.get("traceId");
        log.error("[traceId={}] 未知异常", traceId, ex);
        return Result.error(500, "服务器内部错误 (traceId=" + traceId + ")");
    }
}
