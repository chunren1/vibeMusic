package com.vibemusic.service;

import com.vibemusic.entity.Song;
import com.vibemusic.mapper.SongMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 版权预检 fast-fail 接线测试（audio-source batch c）。
 *
 * <p>网易云 standard 硬拦截时跳过 QQ 降级直落 DB 兜底；need-login/试听/
 * 可播路径行为与此前一致（降级链不变）。
 */
@DisplayName("播放版权预检 fast-fail 测试")
class SongPlayCopyrightPrecheckTest {

    private SongMapper songMapper;
    private NeteaseApiService neteaseApiService;
    private StorageService storageService;
    private StringRedisTemplate stringRedisTemplate;
    private ThreadPoolTaskExecutor getUrlExecutor;
    private SongPlayService songPlayService;

    @BeforeAll
    static void initMyBatisPlusLambdaCache() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                Song.class);
    }

    @BeforeEach
    void setUp() {
        songMapper = mock(SongMapper.class);
        neteaseApiService = mock(NeteaseApiService.class);
        storageService = mock(StorageService.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        when(stringRedisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));
        getUrlExecutor = new ThreadPoolTaskExecutor();
        getUrlExecutor.setCorePoolSize(2);
        getUrlExecutor.setMaxPoolSize(2);
        getUrlExecutor.setQueueCapacity(10);
        getUrlExecutor.initialize();
        songPlayService = new SongPlayService(songMapper, neteaseApiService,
                storageService, stringRedisTemplate, getUrlExecutor);
        when(songMapper.selectOne(any())).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        if (getUrlExecutor != null) getUrlExecutor.shutdown();
    }

    private void stubQqFallback(String qqUrl) {
        when(neteaseApiService.searchQQ(eq("青花瓷 周杰伦"), eq(5)))
                .thenReturn(Map.of("data", List.of(
                        Map.of("id", "qq123", "name", "青花瓷", "artists", "周杰伦", "duration", 240000))));
        when(neteaseApiService.getQQSongUrl("qq123"))
                .thenReturn(Map.of("data", List.of(Map.of("url", qqUrl))));
    }

    @Test
    @DisplayName("standard 硬拦截时跳过 QQ 降级、返回 null")
    void blockedSkipsQqFallback() {
        Map<String, Object> blocked = Map.of("code", 200, "data", List.of(Map.of("id", "12345")));
        when(neteaseApiService.getSongUrl(eq("12345"), anyString())).thenReturn(blocked);

        String url = songPlayService.getPlayUrl("12345", "青花瓷", "周杰伦");

        assertNull(url);
        verify(neteaseApiService, never()).searchQQ(anyString(), anyInt());
        verify(neteaseApiService, never()).getQQSongUrl(anyString());
    }

    @Test
    @DisplayName("need-login 时仍走完整降级链（预检不拦截可恢复信号）")
    void needLoginStillDegrades() {
        when(neteaseApiService.getSongUrl(eq("12345"), anyString()))
                .thenReturn(Map.of("code", 301));
        when(neteaseApiService.rotateSharedCookie()).thenReturn(false);
        stubQqFallback("https://qq-cdn.example/x.mp3");

        String url = songPlayService.getPlayUrl("12345", "青花瓷", "周杰伦");

        assertEquals("https://qq-cdn.example/x.mp3", url);
        verify(neteaseApiService, atLeastOnce()).searchQQ(anyString(), anyInt());
    }

    @Test
    @DisplayName("全试听时仍走 QQ 降级（预检不拦截试听版）")
    void trialStillDegrades() {
        Map<String, Object> item = new HashMap<>();
        item.put("url", "https://ne-cdn.example/trial.mp3");
        item.put("freeTrialInfo", Map.of("start", 0, "end", 30));
        item.put("time", 15000);
        when(neteaseApiService.getSongUrl(eq("12345"), anyString()))
                .thenReturn(Map.of("data", List.of(item)));
        stubQqFallback("https://qq-cdn.example/q.mp3");

        String url = songPlayService.getPlayUrl("12345", "青花瓷", "周杰伦");

        assertEquals("https://qq-cdn.example/q.mp3", url);
    }

    @Test
    @DisplayName("可播时直接返回网易云链接（健康路径不变）")
    void playableReturnsDirectly() {
        when(neteaseApiService.getSongUrl(eq("12345"), anyString()))
                .thenReturn(Map.of("data", List.of(
                        Map.of("url", "https://ne-cdn.example/full.mp3", "time", 240000))));

        String url = songPlayService.getPlayUrl("12345", "青花瓷", "周杰伦");

        assertEquals("https://ne-cdn.example/full.mp3", url);
        verify(neteaseApiService, never()).searchQQ(anyString(), anyInt());
    }
}
