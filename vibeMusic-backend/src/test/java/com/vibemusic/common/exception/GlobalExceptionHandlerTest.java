package com.vibemusic.common.exception;

import com.vibemusic.common.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GlobalExceptionHandler 单元测试")
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    @DisplayName("MethodArgumentNotValidException 应返回 400")
    void handleValidationException() {
        var binding = new BeanPropertyBindingResult(new Object(), "dto");
        binding.addError(new FieldError("dto", "username", "用户名不能为空"));
        var ex = new MethodArgumentNotValidException(null, binding);
        Result<Void> result = handler.handleValidationException(ex);
        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).contains("用户名不能为空");
    }

    @Test
    @DisplayName("MissingServletRequestParameterException 应返回 400")
    void handleMissingParam() {
        var ex = new MissingServletRequestParameterException("keyword", "String");
        Result<Void> result = handler.handleMissingParam(ex);
        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).contains("keyword");
    }

    @Test
    @DisplayName("HttpMessageNotReadableException 应返回 400")
    void handleHttpMessageNotReadable() {
        var ex = new HttpMessageNotReadableException("JSON parse error", null);
        Result<Void> result = handler.handleHttpMessageNotReadable(ex);
        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).contains("请求体格式错误");
    }

    @Test
    @DisplayName("IllegalArgumentException 应返回 400")
    void handleIllegalArgument() {
        var ex = new IllegalArgumentException("page must be positive");
        Result<Void> result = handler.handleIllegalArgument(ex);
        assertThat(result.getCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("BadCredentialsException 应返回 401")
    void handleBadCredentials() {
        var ex = new BadCredentialsException("wrong");
        Result<Void> result = handler.handleBadCredentials(ex);
        assertThat(result.getCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("AccessDeniedException 应返回 403")
    void handleAccessDenied() {
        var ex = new AccessDeniedException("denied");
        Result<Void> result = handler.handleAccessDeniedException(ex);
        assertThat(result.getCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("HttpRequestMethodNotSupportedException 应返回 405")
    void handleMethodNotSupported() {
        var ex = new HttpRequestMethodNotSupportedException("DELETE");
        Result<Void> result = handler.handleMethodNotSupported(ex);
        assertThat(result.getCode()).isEqualTo(405);
    }

    @Test
    @DisplayName("DataAccessException 应返回 503")
    void handleDataAccessException() {
        var ex = new DataAccessException("db down") {};
        Result<Void> result = handler.handleDataAccessException(ex);
        assertThat(result.getCode()).isEqualTo(503);
    }

    @Test
    @DisplayName("RedisConnectionFailureException 应返回 503")
    void handleRedisFailure() {
        var ex = new RedisConnectionFailureException("Redis down");
        Result<Void> result = handler.handleRedisConnectionFailure(ex);
        assertThat(result.getCode()).isEqualTo(503);
    }

    @Test
    @DisplayName("NullPointerException 应返回 500")
    void handleNullPointer() {
        var ex = new NullPointerException("oops");
        Result<Void> result = handler.handleNullPointer(ex);
        assertThat(result.getCode()).isEqualTo(500);
    }

    @Test
    @DisplayName("通用 Exception 应返回 500")
    void handleGenericException() {
        var ex = new RuntimeException("unknown");
        Result<Void> result = handler.handleException(ex);
        assertThat(result.getCode()).isEqualTo(500);
    }

    @Test
    @DisplayName("BusinessException 应使用业务状态码")
    void handleBusinessException() {
        var ex = new BusinessException(404, "歌曲不存在");
        var response = new MockHttpServletResponse();
        Result<Void> result = handler.handleBusinessException(ex, response);
        assertThat(result.getCode()).isEqualTo(404);
        assertThat(result.getMessage()).isEqualTo("歌曲不存在");
    }
}
