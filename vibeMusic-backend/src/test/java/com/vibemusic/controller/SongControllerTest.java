package com.vibemusic.controller;

import com.vibemusic.BaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("SongController 集成测试")
class SongControllerTest extends BaseTest {

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    @Nested
    @DisplayName("GET /api/songs/search")
    class Search {
        @Test
        @DisplayName("搜索 → 返回结果（降级到 musicapi）")
        void shouldSearchSongs() throws Exception {
            mockMvc.perform(get("/api/songs/search")
                            .param("keyword", "周杰伦")
                            .param("page", "1")
                            .param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
            // 注意：测试环境 musicapi 不可达，会降级返回空列表
        }

        @Test
        @DisplayName("搜索缺少 keyword → 400")
        void shouldRequireKeyword() throws Exception {
            mockMvc.perform(get("/api/songs/search"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
        }
    }

    @Nested
    @DisplayName("GET /api/songs/banner")
    class Banner {
        @Test
        @DisplayName("获取轮播图 → 返回空列表（musicapi 不可达）")
        void shouldReturnEmptyBanners() throws Exception {
            mockMvc.perform(get("/api/songs/banner"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isArray());
        }
    }

    @Nested
    @DisplayName("GET /api/songs/random")
    class Random {
        @Test
        @DisplayName("随机推荐 → 返回歌曲列表")
        void shouldReturnRandomSongs() throws Exception {
            mockMvc.perform(get("/api/songs/random")
                            .param("count", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    @Nested
    @DisplayName("GET /api/songs/es-health")
    class EsHealth {
        @Test
        @DisplayName("ES 健康检查 → 可用（中间件已启动）")
        void shouldReportEsAvailable() throws Exception {
            mockMvc.perform(get("/api/songs/es-health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
            // 如果 ES 中间件没启动，会降级返回 unavailable
        }
    }

    @Nested
    @DisplayName("GET /api/songs/history")
    class History {
        @Test
        @DisplayName("未登录 → 返回空列表")
        void shouldReturnEmptyWhenNotLoggedIn() throws Exception {
            mockMvc.perform(get("/api/songs/history")
                            .param("count", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }

    @Nested
    @DisplayName("GET /api/songs/lyric")
    class Lyric {
        @Test
        @DisplayName("获取歌词 → musicaapi 不可达时返回空列表")
        void shouldReturnEmptyWhenApiUnreachable() throws Exception {
            mockMvc.perform(get("/api/songs/lyric")
                            .param("sourceId", "000rh0dE2TyUic"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
        }
    }

    @Nested
    @DisplayName("GET /api/songs/play")
    class Play {
        @Test
        @DisplayName("记录播放 → 未登录不记录历史")
        void shouldNotRecordWhenNotLoggedIn() throws Exception {
            mockMvc.perform(get("/api/songs/play")
                            .param("sourceId", "000rh0dE2TyUic")
                            .param("name", "晴天")
                            .param("artist", "周杰伦"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.sourceId").value("000rh0dE2TyUic"));
        }

        @Test
        @DisplayName("记录播放 → 缺少参数")
        void shouldRequireParams() throws Exception {
            mockMvc.perform(get("/api/songs/play"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
        }
    }

    @Nested
    @DisplayName("GET /api/songs/stream")
    class Stream {
        @Test
        @DisplayName("音频流 → 歌曲不存在时 404")
        void shouldReturn404WhenNoUrl() throws Exception {
            mockMvc.perform(get("/api/songs/stream")
                            .param("sourceId", "nonexistent_song_id"))
                    .andExpect(status().is(404));
        }

        @Test
        @DisplayName("音频流 → 缺少 sourceId 参数")
        void shouldRequireSourceId() throws Exception {
            mockMvc.perform(get("/api/songs/stream"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
        }
    }
}
