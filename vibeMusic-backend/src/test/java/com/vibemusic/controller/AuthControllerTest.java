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
        @DisplayName("密码少于 4 位 → 拒绝")
        void shouldRejectShortPassword() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "username", "short", "password", "12"));
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400));
        }

        @Test
        @DisplayName("用户名为空 → 拒绝")
        void shouldRejectEmptyUsername() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "username", "", "password", "123456"));
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400));
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
