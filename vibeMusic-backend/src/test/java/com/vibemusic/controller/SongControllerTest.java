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

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
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

        @Test
        @DisplayName("随机推荐 count=10000 → 钳制到 50 以内，仍 HTTP 200 信封不变")
        void shouldClampHugeCount() throws Exception {
            mockMvc.perform(get("/api/songs/random")
                            .param("count", "10000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data", hasSize(lessThanOrEqualTo(50))));
        }

        @Test
        @DisplayName("随机推荐 count=0/负数 → 钳制到至少 1，仍 HTTP 200")
        void shouldClampNonPositiveCount() throws Exception {
            mockMvc.perform(get("/api/songs/random")
                            .param("count", "0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
            mockMvc.perform(get("/api/songs/random")
                            .param("count", "-5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
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
    @DisplayName("歌词时间解析 parseLrc")
    class LyricParser {
        private double timeOf(String lrcLine) {
            List<Map<String, Object>> lines = SongController.parseLrc(lrcLine);
            assertEquals(1, lines.size());
            return ((Number) lines.get(0).get("time")).doubleValue();
        }

        @Test
        @DisplayName("一位小数按十分秒换算：[00:01.5] = 1.5s")
        void shouldParseTenthsAsDeciseconds() {
            assertEquals(1.5, timeOf("[00:01.5]歌词"), 1e-9);
        }

        @Test
        @DisplayName("两位小数按百分秒换算（回归旧偏斜）：[00:01.50] = 1.5s 而非 1.05s")
        void shouldParseHundredthsAsCentiseconds() {
            assertEquals(1.5, timeOf("[00:01.50]歌词"), 1e-9);
            assertEquals(65.25, timeOf("[01:05.25]歌词"), 1e-9);
        }

        @Test
        @DisplayName("三位小数按毫秒原值：[00:01.500] = 1.5s")
        void shouldParseMillisAsIs() {
            assertEquals(1.5, timeOf("[00:01.500]歌词"), 1e-9);
        }

        @Test
        @DisplayName("超过三位截断到毫秒：[00:01.5009] = 1.5s")
        void shouldTruncateBeyondMillis() {
            assertEquals(1.5, timeOf("[00:01.5009]歌词"), 1e-9);
        }

        @Test
        @DisplayName("无小数部分为整秒：[01:05] = 65s")
        void shouldParseWholeSeconds() {
            assertEquals(65.0, timeOf("[01:05]歌词"), 1e-9);
        }

        @Test
        @DisplayName("多行混合精度各自归一")
        void shouldNormalizeMixedPrecisionLines() {
            List<Map<String, Object>> lines = SongController.parseLrc(
                    "[00:01.5]a\n[00:02.25]b\n[00:03.125]c\n");
            assertEquals(3, lines.size());
            assertEquals(1.5, ((Number) lines.get(0).get("time")).doubleValue(), 1e-9);
            assertEquals(2.25, ((Number) lines.get(1).get("time")).doubleValue(), 1e-9);
            assertEquals(3.125, ((Number) lines.get(2).get("time")).doubleValue(), 1e-9);
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
