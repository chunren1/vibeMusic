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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 网易→QQ 降级链一致性测试（Q2d 收敛）。
 *
 * <p>生产路径 {@code getPlayUrl} 与元信息路径 {@code getPlayInfo} 共用
 * {@code degradeNeteaseToQq} 唯一入口；本类锁定两者在网易云全挂时
 * 落到同一 QQ 降级链接、顺序一致。
 */
@DisplayName("网易→QQ 降级链一致性测试")
class SongPlayDegradeChainTest {

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

    private void stubNeteaseDownAndQqFallback() {
        when(neteaseApiService.getSongUrl(anyString(), anyString()))
                .thenThrow(new RuntimeException("netease down"));
        when(neteaseApiService.searchQQ(eq("青花瓷 周杰伦"), eq(5)))
                .thenReturn(Map.of("data", List.of(
                        Map.of("id", "qq123", "name", "青花瓷", "artists", "周杰伦", "duration", 240000))));
        when(neteaseApiService.getQQSongUrl("qq123"))
                .thenReturn(Map.of("data", List.of(
                        Map.of("url", "https://qq-cdn.example/x.mp3"))));
    }

    @Test
    @DisplayName("网易云全挂时两条链路一致落到同一 QQ 降级链接")
    void bothChainsFallToSameQqUrl() {
        stubNeteaseDownAndQqFallback();

        String url = songPlayService.getPlayUrl("12345", "青花瓷", "周杰伦");
        Map<String, Object> info = songPlayService.getPlayInfo("12345", "青花瓷", "周杰伦");

        assertEquals("https://qq-cdn.example/x.mp3", url);
        assertEquals("https://qq-cdn.example/x.mp3", info.get("url"));
        assertEquals(url, info.get("url"), "两条链路必须解析到同一降级链接");
        assertEquals("qq", info.get("platform"));
        assertEquals(true, info.get("degraded"));
    }

    @Test
    @DisplayName("单次 QQ 降级只计一次降级计数")
    void qqDegradeCountsOnce() {
        stubNeteaseDownAndQqFallback();

        songPlayService.getPlayUrl("12345", "青花瓷", "周杰伦");

        assertEquals(1, songPlayService.getDegradationCount());
    }
}
