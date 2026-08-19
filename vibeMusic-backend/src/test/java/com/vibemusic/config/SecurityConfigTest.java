package com.vibemusic.config;

import com.vibemusic.BaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("SecurityConfig 端点白名单测试")
class SecurityConfigTest extends BaseTest {

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("未登录访问推荐歌单 /api/playlists/recommend → 200（首页需展示推荐歌单封面）")
    void shouldAllowAnonymousPlaylistRecommend() throws Exception {
        mockMvc.perform(get("/api/playlists/recommend"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("未登录访问歌单详情 /api/playlists/detail → 不被 Security 拦截（缺参 400 属 controller 层）")
    void shouldAllowAnonymousPlaylistDetail() throws Exception {
        mockMvc.perform(get("/api/playlists/detail"))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertNotEquals(401, result.getResponse().getStatus()));
    }

    @Test
    @DisplayName("未登录访问歌单列表 /api/playlists/list → 401（个人歌单需认证）")
    void shouldRejectAnonymousPlaylistList() throws Exception {
        mockMvc.perform(get("/api/playlists/list"))
                .andExpect(status().isUnauthorized());
    }
}
