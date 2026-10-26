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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 播放链接短缓存与取链链路的集成行为（纯 Mockito，无 Spring 上下文）。
 *
 * <p>守的是 2026-10-26 实测问题：生产无 MinIO → 每次 {@code /stream}（含每个 Range 分片）
 * 都重跑整条降级链，无版权曲实测 8.8s、每拖一次进度再等一次。缓存命中后必须**不再碰上游**。
 */
class SongPlayUrlCacheTest {

    private static final String SRC = "60054701986";
    private static final String URL = "https://signed.migu.cn/x.mp3?k=1";

    private SongMapper songMapper;
    private NeteaseApiService neteaseApiService;
    private SongPlayService songPlayService;
    private ThreadPoolTaskExecutor getUrlExecutor;
    /** 模拟 Redis：键 → JSON 载荷。 */
    private Map<String, Map<String, Object>> store;

    @BeforeAll
    static void initMyBatisPlusLambdaCache() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                Song.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        songMapper = mock(SongMapper.class);
        neteaseApiService = mock(NeteaseApiService.class);
        StorageService storageService = mock(StorageService.class);
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        // Redis 不可用 → isCachedInMinio 降级为 false（与生产无 MinIO 同形）
        when(stringRedisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        getUrlExecutor = new ThreadPoolTaskExecutor();
        getUrlExecutor.setCorePoolSize(2);
        getUrlExecutor.setMaxPoolSize(2);
        getUrlExecutor.setQueueCapacity(10);
        getUrlExecutor.initialize();

        songPlayService = new SongPlayService(songMapper, neteaseApiService,
                storageService, stringRedisTemplate, getUrlExecutor);

        store = new HashMap<>();
        JsonCacheService jsonCache = mock(JsonCacheService.class);
        when(jsonCache.getAsMap(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0, String.class)));
        doAnswer(inv -> {
            store.put(inv.getArgument(0, String.class), inv.getArgument(1, Map.class));
            return null;
        }).when(jsonCache).set(anyString(), any(), any());
        doAnswer(inv -> {
            store.remove(inv.getArgument(0, String.class));
            return null;
        }).when(jsonCache).delete(anyString());
        songPlayService.setPlayUrlCache(new PlayUrlCache(jsonCache));
    }

    @AfterEach
    void tearDown() {
        if (getUrlExecutor != null) getUrlExecutor.shutdown();
    }

    @Test
    @DisplayName("同一首歌连续取链只打一次上游（模拟拖动进度产生的连续 Range 请求）")
    void secondCallHitsCacheWithoutUpstream() {
        when(neteaseApiService.getMiguSongUrl(SRC, null))
                .thenReturn(Map.of("data", List.of(Map.of("url", URL))));

        assertEquals(URL, songPlayService.getPlayUrl(SRC, "青花瓷", "周杰伦", "migu"));
        assertEquals(URL, songPlayService.getPlayUrl(SRC, "青花瓷", "周杰伦", "migu"));
        assertEquals(URL, songPlayService.getPlayUrl(SRC, "青花瓷", "周杰伦", "migu"));

        verify(neteaseApiService, times(1)).getMiguSongUrl(SRC, null);
    }

    @Test
    @DisplayName("上游 403 作废缓存后，下一次取链必须真正重新请求上游")
    void invalidateForcesFreshLookup() {
        when(neteaseApiService.getMiguSongUrl(SRC, null))
                .thenReturn(Map.of("data", List.of(Map.of("url", URL))));

        songPlayService.getPlayUrl(SRC, "青花瓷", "周杰伦", "migu");
        verify(neteaseApiService, times(1)).getMiguSongUrl(SRC, null);

        songPlayService.invalidatePlayUrl(SRC, "migu");
        songPlayService.getPlayUrl(SRC, "青花瓷", "周杰伦", "migu");

        verify(neteaseApiService, times(2)).getMiguSongUrl(SRC, null);
    }

    @Test
    @DisplayName("缓存命中时仍补 X-Play-Source：换源平台随条目一起缓存，不因命中而丢头")
    void cacheHitRestoresFailoverSourceHeader() {
        store.put(PlayUrlCache.key(null, "migu", SRC), Map.of("url", URL, "src", "kugou"));

        String url = songPlayService.getPlayUrl(SRC, "青花瓷", "周杰伦", "migu");

        assertEquals(URL, url);
        assertEquals("kugou", songPlayService.consumeFailoverPlaySource());
        verify(neteaseApiService, never()).getMiguSongUrl(anyString(), any());
    }

    @Test
    @DisplayName("取链失败（null）不写缓存，避免把 404 固化 90 秒")
    void nullUrlIsNotCached() {
        when(neteaseApiService.getMiguSongUrl(SRC, null))
                .thenReturn(Map.of("data", List.of(Map.of())));
        when(neteaseApiService.searchNetease(any(), anyInt()))
                .thenReturn(Map.of("data", List.of()));
        when(songMapper.selectOne(any())).thenReturn(null);

        // songName=null → 跳过跨平台换源分支（该分支依赖歌名搜索），只验证「失败不缓存」
        songPlayService.getPlayUrl(SRC, null, null, "migu");
        songPlayService.getPlayUrl(SRC, null, null, "migu");

        verify(neteaseApiService, times(2)).getMiguSongUrl(SRC, null);
    }
}
