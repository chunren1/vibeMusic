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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BYOC Task 7：失效后 {@code GET /api/cookies/status} 出现 {@code needsRebind}。
 *
 * <p>密钥复用 {@code application-test.yml} 的 {@code COOKIE_ENC_KEY}，无新增 key plumbing。
 * 状态契约：{@code data.netease = {has, valid, updatedAt, needsRebind}}，
 * {@code needsRebind = has && valid == false}；任何响应不得含 Cookie 子串。
 */
@DisplayName("CookieController 重绑状态测试")
class CookieControllerRebindTest extends BaseTest {

    private static final String MARKER = "rebindMarker4d8e1b";
    private static final String COOKIE = "MUSIC_U=" + MARKER + "; NMTID=xyz";

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
        String name = "rebind_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return userService.register(name, "pass1234", null);
    }

    private void loginAs(User user) {
        CustomUserDetails details = new CustomUserDetails(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    @Test
    @DisplayName("刚绑定：needsRebind=false、valid=null")
    void shouldReportNoRebindWhenFreshlyBound() throws Exception {
        loginAs(newUser());
        mockMvc.perform(put("/api/cookies/netease")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("cookie", COOKIE))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/cookies/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.netease.has").value(true))
                .andExpect(jsonPath("$.data.netease.valid").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.netease.needsRebind").value(false));
    }

    @Test
    @DisplayName("失效标记后：needsRebind=true、valid=false，且响应不含 Cookie 子串")
    void shouldReportNeedsRebindAfterInvalidation() throws Exception {
        User user = newUser();
        loginAs(user);
        mockMvc.perform(put("/api/cookies/netease")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("cookie", COOKIE))))
                .andExpect(status().isOk());

        // 模拟 per-user 取链命中 need-login（code 301）后置 0
        userService.markNeteaseCookieInvalid(user.getId(), 301);

        MvcResult result = mockMvc.perform(get("/api/cookies/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.netease.has").value(true))
                .andExpect(jsonPath("$.data.netease.valid").value(false))
                .andExpect(jsonPath("$.data.netease.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.netease.needsRebind").value(true))
                .andReturn();
        assertFalse(result.getResponse().getContentAsString().contains(MARKER), "状态响应不得回显 Cookie");
    }

    @Test
    @DisplayName("未绑定用户：needsRebind=false")
    void shouldReportNoRebindWhenUnbound() throws Exception {
        loginAs(newUser());
        mockMvc.perform(get("/api/cookies/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.netease.has").value(false))
                .andExpect(jsonPath("$.data.netease.needsRebind").value(false));
    }

    @Test
    @DisplayName("重绑闭环：失效→重新绑定→needsRebind=false")
    void shouldClearNeedsRebindOnRebind() throws Exception {
        User user = newUser();
        loginAs(user);
        mockMvc.perform(put("/api/cookies/netease")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("cookie", COOKIE))))
                .andExpect(status().isOk());
        userService.markNeteaseCookieInvalid(user.getId(), 301);
        mockMvc.perform(get("/api/cookies/status"))
                .andExpect(jsonPath("$.data.netease.needsRebind").value(true));

        mockMvc.perform(put("/api/cookies/netease")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("cookie", "MUSIC_U=freshbound999; NMTID=xyz"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/cookies/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.netease.has").value(true))
                .andExpect(jsonPath("$.data.netease.needsRebind").value(false));
    }
}
