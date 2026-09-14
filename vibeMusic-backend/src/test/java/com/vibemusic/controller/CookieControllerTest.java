package com.vibemusic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.BaseTest;
import com.vibemusic.entity.User;
import com.vibemusic.security.CustomUserDetails;
import com.vibemusic.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * CookieController 集成测试（AuthControllerTest + SecurityConfigTest 风格）。
 *
 * <p>CookieCryptoService 构造时 fail-fast，此处经 @TestPropertySource 注入仅测试有效的
 * 密钥，主代码不加任何默认值。断言红线：任何响应不得含 Cookie 子串。
 */
@TestPropertySource(properties = "COOKIE_ENC_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@DisplayName("CookieController 集成测试")
class CookieControllerTest extends BaseTest {

    private static final String MARKER = "cookieMarker7f3a9c";
    private static final String COOKIE = "MUSIC_U=" + MARKER + "; __remember_me=true; NMTID=xyz";

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserService userService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        SecurityContextHolder.clearContext();
    }

    private User newUser() {
        String name = "cookie_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return userService.register(name, "pass1234", null);
    }

    private void loginAs(User user) {
        CustomUserDetails details = new CustomUserDetails(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    private String cookieBody(String cookie) throws Exception {
        return objectMapper.writeValueAsString(Map.of("cookie", cookie));
    }

    @Test
    @DisplayName("未登录经安全链访问 /api/cookies/status → 401（默认 authenticated 覆盖，无需白名单）")
    void shouldRejectAnonymousThroughSecurityChain() throws Exception {
        MockMvc secured = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        secured.perform(get("/api/cookies/status")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("未登录访问 → 401（controller 层兜底）")
    void shouldReturn401WhenNotLoggedIn() throws Exception {
        mockMvc.perform(get("/api/cookies/status"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(put("/api/cookies/netease")
                        .contentType(MediaType.APPLICATION_JSON).content(cookieBody(COOKIE)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("粘贴绑定 → 200 {bound:true}，status.has=true 且响应不含 Cookie 子串")
    void shouldBindAndReportStatusWithoutEcho() throws Exception {
        loginAs(newUser());
        mockMvc.perform(put("/api/cookies/netease")
                        .contentType(MediaType.APPLICATION_JSON).content(cookieBody(COOKIE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.bound").value(true));

        MvcResult result = mockMvc.perform(get("/api/cookies/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.netease.has").value(true))
                .andExpect(jsonPath("$.data.bili.reserved").value(true))
                .andReturn();
        assertFalse(result.getResponse().getContentAsString().contains(MARKER), "状态响应不得回显 Cookie");
    }

    @Test
    @DisplayName("解绑 → 200，status.has=false")
    void shouldUnbindAndClearStatus() throws Exception {
        loginAs(newUser());
        mockMvc.perform(put("/api/cookies/netease")
                        .contentType(MediaType.APPLICATION_JSON).content(cookieBody(COOKIE)))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/cookies/netease"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/api/cookies/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.netease.has").value(false));
    }

    @Test
    @DisplayName("超长 Cookie（>8KB）→ 400")
    void shouldRejectOversized() throws Exception {
        loginAs(newUser());
        mockMvc.perform(put("/api/cookies/netease")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cookieBody("MUSIC_U=" + "x".repeat(9000))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("空白/缺 MUSIC_U → 400（缺 MUSIC_U 带重绑指引）")
    void shouldRejectBlankAndMissingMusicU() throws Exception {
        loginAs(newUser());
        mockMvc.perform(put("/api/cookies/netease")
                        .contentType(MediaType.APPLICATION_JSON).content(cookieBody("   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(put("/api/cookies/netease")
                        .contentType(MediaType.APPLICATION_JSON).content(cookieBody("FOO=bar; BAZ=qux")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(containsString("重新")));
    }
}
