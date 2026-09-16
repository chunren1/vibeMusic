package com.vibemusic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.BaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("AuthController 集成测试")
class AuthControllerTest extends BaseTest {

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    @Nested
    @DisplayName("POST /api/auth/register")
    class Register {
        @Test
        @DisplayName("正常注册 → 201")
        void shouldRegister() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "username", "mvcuser", "password", "pass1234", "nickname", "MVC用户"));
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk()) // Result.ok 返回 200
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("密码少于 8 位 → Bean Validation 400（handleValidationException 分支）")
        void shouldRejectShortPassword() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "username", "short", "password", "12"));
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("password: 密码至少8位"));
        }

        @Test
        @DisplayName("用户名为空 → Bean Validation 400（handleValidationException 分支）")
        void shouldRejectEmptyUsername() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "username", "", "password", "12345678"));
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("username: 用户名不能为空"));
        }

        @Test
        @DisplayName("用户名超 30 字符 → Bean Validation 400（手工校验未覆盖的新防线）")
        void shouldRejectLongUsername() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "username", "u".repeat(31), "password", "pass1234"));
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("username: 用户名格式不正确"));
        }
    }

    @Nested
    @DisplayName("POST /api/auth/login 入参校验")
    class LoginValidation {
        @Test
        @DisplayName("缺少 password → Bean Validation 400（handleValidationException 分支）")
        void shouldRejectMissingPassword() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("username", "testuser"));
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("password: 用户名和密码不能为空"));
        }
    }

    @Test
    @DisplayName("GET /api/auth/me → 未登录返回错误")
    void shouldReturnErrorWhenNotLoggedIn() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }
}
