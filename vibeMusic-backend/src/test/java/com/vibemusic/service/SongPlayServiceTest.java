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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SongPlayService 咪咕播放分支测试
 * <p>
 * 纯 Mockito 单元测试：Mock musicapi 客户端与存储依赖，
 * 验证 platform=migu 时走咪咕取链分支、无链时干净降级。
 */
@DisplayName("SongPlayService 咪咕播放分支测试")
class SongPlayServiceTest {

    private SongMapper songMapper;
    private NeteaseApiService neteaseApiService;
    private StorageService storageService;
    private StringRedisTemplate stringRedisTemplate;
    private ThreadPoolTaskExecutor getUrlExecutor;
    private SongPlayService songPlayService;

    @BeforeAll
    static void initMyBatisPlusLambdaCache() {
        // DB 兜底分支的 LambdaQueryWrapper.select(...) 需要实体 TableInfo，
        // 纯 Mockito 无 Spring 上下文，手动初始化一次即可。
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
        // Redis 不可用 → isCachedInMinio 降级为 false（走 API 分支）
        when(stringRedisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));
        getUrlExecutor = new ThreadPoolTaskExecutor();
        getUrlExecutor.setCorePoolSize(2);
        getUrlExecutor.setMaxPoolSize(2);
        getUrlExecutor.setQueueCapacity(10);
        getUrlExecutor.initialize();
        songPlayService = new SongPlayService(songMapper, neteaseApiService,
                storageService, stringRedisTemplate, getUrlExecutor);
    }

    @AfterEach
    void tearDown() {
        if (getUrlExecutor != null) getUrlExecutor.shutdown();
    }

    @Test @DisplayName("platform=migu 经咪咕取链返回签名 URL，不碰 QQ/网易云分支")
    void miguBranchReturnsSignedUrl() {
        when(neteaseApiService.getMiguSongUrl("60054701986", null))
                .thenReturn(Map.of("data", List.of(
                        Map.of("url", "https://signed.migu.cn/x.mp3?k=1"))));

        String url = songPlayService.getPlayUrl("60054701986", "青花瓷", "周杰伦", "migu");

        assertEquals("https://signed.migu.cn/x.mp3?k=1", url);
        verify(neteaseApiService, times(1)).getMiguSongUrl("60054701986", null);
        verify(neteaseApiService, never()).getQQSongUrl(anyString());
        verify(neteaseApiService, never()).getSongUrl(anyString(), anyString());
    }

    @Test @DisplayName("咪咕无链（url=null）时降级到网易云同名歌曲")
    void miguNoUrlFallsBackToNetease() {
        when(neteaseApiService.getMiguSongUrl("60054701986", null))
                .thenReturn(Map.of("data", List.of(Map.of())));
        when(neteaseApiService.searchNetease(eq("青花瓷 周杰伦"), anyInt()))
                .thenReturn(Map.of("data", List.of(
                        Map.of("id", "123", "name", "青花瓷", "duration", 240000))));
        when(neteaseApiService.getSongUrl(eq("123"), anyString()))
                .thenReturn(Map.of("data", List.of(
                        Map.of("url", "https://ne-cdn.example/x.mp3"))));

        String url = songPlayService.getPlayUrl("60054701986", "青花瓷", "周杰伦", "migu");

        assertEquals("https://ne-cdn.example/x.mp3", url);
        verify(neteaseApiService, times(1)).getMiguSongUrl("60054701986", null);
    }

    @Test @DisplayName("咪咕取链异常不抛错，降级链继续")
    void miguExceptionDegradesWithoutThrowing() {
        when(neteaseApiService.getMiguSongUrl(anyString(), any()))
                .thenThrow(new RuntimeException("musicapi down"));
        when(neteaseApiService.searchNetease(anyString(), anyInt()))
                .thenReturn(Map.of("data", List.of(
                        Map.of("id", "123", "name", "青花瓷", "duration", 240000))));
        when(neteaseApiService.getSongUrl(eq("123"), anyString()))
                .thenReturn(Map.of("data", List.of(
                        Map.of("url", "https://ne-cdn.example/x.mp3"))));

        assertDoesNotThrow(() ->
                assertEquals("https://ne-cdn.example/x.mp3",
                        songPlayService.getPlayUrl("60054701986", "青花瓷", "周杰伦", "migu")));
    }

    @Test @DisplayName("platform=kugou 经酷狗取链返回 URL，不碰 QQ/网易云分支")
    void kugouBranchReturnsUrl() {
        when(neteaseApiService.getKugouSongUrl("45f763d7beb1fd000af890eb6c70b9a2", null, "standard"))
                .thenReturn(Map.of("data", List.of(
                        Map.of("url", "https://kg-cdn.example/320.mp3"))));

        String url = songPlayService.getPlayUrl("45f763d7beb1fd000af890eb6c70b9a2", "安静", "周杰伦", "kugou");

        assertEquals("https://kg-cdn.example/320.mp3", url);
        verify(neteaseApiService, times(1))
                .getKugouSongUrl("45f763d7beb1fd000af890eb6c70b9a2", null, "standard");
        verify(neteaseApiService, never()).getQQSongUrl(anyString());
        verify(neteaseApiService, never()).getSongUrl(anyString(), anyString());
    }

    @Test @DisplayName("无显式平台时酷狗 hash 走酷狗分支而非 QQ 分支")
    void kugouHashWithoutPlatformRoutesToKugou() {
        when(neteaseApiService.getKugouSongUrl("45f763d7beb1fd000af890eb6c70b9a2", null, "standard"))
                .thenReturn(Map.of("data", List.of(
                        Map.of("url", "https://kg-cdn.example/x.mp3"))));

        String url = songPlayService.getPlayUrl("45f763d7beb1fd000af890eb6c70b9a2", "安静", "周杰伦");

        assertEquals("https://kg-cdn.example/x.mp3", url);
        verify(neteaseApiService, never()).getQQSongUrl(anyString());
    }

    @Test @DisplayName("酷狗 standard 无链时降级试 low 档")
    void kugouStandardFallsBackToLow() {
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(neteaseApiService);
        when(neteaseApiService.getKugouSongUrl("45f763d7beb1fd000af890eb6c70b9a2", null, "standard"))
                .thenReturn(Map.of("data", List.of(Map.of())));
        when(neteaseApiService.getKugouSongUrl("45f763d7beb1fd000af890eb6c70b9a2", null, "low"))
                .thenReturn(Map.of("data", List.of(
                        Map.of("url", "https://kg-cdn.example/128.mp3"))));

        String url = songPlayService.getPlayUrl("45f763d7beb1fd000af890eb6c70b9a2", "安静", "周杰伦", "kugou");

        assertEquals("https://kg-cdn.example/128.mp3", url);
        inOrder.verify(neteaseApiService)
                .getKugouSongUrl("45f763d7beb1fd000af890eb6c70b9a2", null, "standard");
        inOrder.verify(neteaseApiService)
                .getKugouSongUrl("45f763d7beb1fd000af890eb6c70b9a2", null, "low");
    }

    @Test @DisplayName("酷狗无链时降级到网易云同名歌曲")
    void kugouNoUrlFallsBackToNetease() {
        when(neteaseApiService.getKugouSongUrl(eq("45f763d7beb1fd000af890eb6c70b9a2"), any(), anyString()))
                .thenReturn(Map.of("data", List.of(Map.of())));
        when(neteaseApiService.searchNetease(eq("安静 周杰伦"), anyInt()))
                .thenReturn(Map.of("data", List.of(
                        Map.of("id", "123", "name", "安静", "duration", 240000))));
        when(neteaseApiService.getSongUrl(eq("123"), anyString()))
                .thenReturn(Map.of("data", List.of(
                        Map.of("url", "https://ne-cdn.example/x.mp3"))));

        String url = songPlayService.getPlayUrl("45f763d7beb1fd000af890eb6c70b9a2", "安静", "周杰伦", "kugou");

        assertEquals("https://ne-cdn.example/x.mp3", url);
    }

    @Test @DisplayName("全数字 32 位 ID 按酷狗 hash 优先于网易云分支（路由顺序）")
    void kugouNumeric32CharPreferredOverNetease() {
        String numericHash = "12345678901234567890123456789012";
        when(neteaseApiService.getKugouSongUrl(numericHash, null, "standard"))
                .thenReturn(Map.of("data", List.of(
                        Map.of("url", "https://kg-cdn.example/x.mp3"))));

        String url = songPlayService.getPlayUrl(numericHash, "安静", "周杰伦");

        assertEquals("https://kg-cdn.example/x.mp3", url);
        verify(neteaseApiService, never()).getSongUrl(anyString(), anyString());
    }

    @Test @DisplayName("platform=bilibili 经B站取链返回 URL，不碰 QQ/网易云分支")
    void biliBranchReturnsUrl() {
        when(neteaseApiService.getBiliSongUrl("BV1De411p77r"))
                .thenReturn(Map.of("data", List.of(
                        Map.of("url", "https://xy.bilivideo.com/x.m4s"))));

        String url = songPlayService.getPlayUrl("BV1De411p77r", "少年", "梦然", "bilibili");

        assertEquals("https://xy.bilivideo.com/x.m4s", url);
        verify(neteaseApiService, times(1)).getBiliSongUrl("BV1De411p77r");
        verify(neteaseApiService, never()).getQQSongUrl(anyString());
        verify(neteaseApiService, never()).getSongUrl(anyString(), anyString());
    }

    @Test @DisplayName("无显式平台时 BV 走B站分支而非 QQ 分支")
    void biliIdWithoutPlatformRoutesToBili() {
        when(neteaseApiService.getBiliSongUrl("BV1De411p77r"))
                .thenReturn(Map.of("data", List.of(
                        Map.of("url", "https://xy.bilivideo.com/x.m4s"))));

        String url = songPlayService.getPlayUrl("BV1De411p77r", "少年", "梦然");

        assertEquals("https://xy.bilivideo.com/x.m4s", url);
        verify(neteaseApiService, never()).getQQSongUrl(anyString());
    }

    @Test @DisplayName("复合 BV|cid 原样透传网关")
    void biliCompositeIdPassesThrough() {
        when(neteaseApiService.getBiliSongUrl("BV1De411p77r|171776208"))
                .thenReturn(Map.of("data", List.of(
                        Map.of("url", "https://xy.bilivideo.com/x.m4s"))));

        String url = songPlayService.getPlayUrl("BV1De411p77r|171776208", "少年", "梦然", "bilibili");

        assertEquals("https://xy.bilivideo.com/x.m4s", url);
        verify(neteaseApiService, times(1)).getBiliSongUrl("BV1De411p77r|171776208");
    }

    @Test @DisplayName("B站无链时降级到网易云同名歌曲")
    void biliNoUrlFallsBackToNetease() {
        when(neteaseApiService.getBiliSongUrl("BV1De411p77r"))
                .thenReturn(Map.of("data", List.of(Map.of())));
        when(neteaseApiService.searchNetease(eq("少年 梦然"), anyInt()))
                .thenReturn(Map.of("data", List.of(
                        Map.of("id", "123", "name", "少年", "duration", 240000))));
        when(neteaseApiService.getSongUrl(eq("123"), anyString()))
                .thenReturn(Map.of("data", List.of(
                        Map.of("url", "https://ne-cdn.example/x.mp3"))));

        String url = songPlayService.getPlayUrl("BV1De411p77r", "少年", "梦然", "bilibili");

        assertEquals("https://ne-cdn.example/x.mp3", url);
    }

    @Test @DisplayName("B站取链异常不抛错，降级链继续")
    void biliExceptionDegradesWithoutThrowing() {
        when(neteaseApiService.getBiliSongUrl(anyString()))
                .thenThrow(new RuntimeException("musicapi down"));
        when(songMapper.selectOne(any())).thenReturn(null);

        assertDoesNotThrow(() ->
                assertNull(songPlayService.getPlayUrl("BV1De411p77r", "少年", "梦然", "bilibili")));
    }

    @Test @DisplayName("getPlayUrl 高优命中后排队的低优任务被取消，不再消耗线程与上游配额")
    void getPlayUrlCancelsLosersOnHit() throws Exception {
        ThreadPoolTaskExecutor single = new ThreadPoolTaskExecutor();
        single.setCorePoolSize(1);
        single.setMaxPoolSize(1);
        single.setQueueCapacity(10);
        single.initialize();
        CountDownLatch gate = new CountDownLatch(1);
        AtomicBoolean standardRan = new AtomicBoolean(false);
        try {
            SongPlayService svc = new SongPlayService(songMapper, neteaseApiService,
                    storageService, stringRedisTemplate, single);
            when(neteaseApiService.getSongUrl("67890", "exhigh"))
                    .thenReturn(Map.of("data", List.of(
                            Map.of("url", "https://cdn.example/exhigh.mp3", "time", 240000))));
            when(neteaseApiService.getSongUrl("67890", "higher")).thenAnswer(inv -> {
                gate.await(30, TimeUnit.SECONDS);
                return null;
            });
            when(neteaseApiService.getSongUrl("67890", "standard")).thenAnswer(inv -> {
                standardRan.set(true);
                return null;
            });

            String url = svc.getPlayUrl("67890", "青花瓷", "周杰伦");

            assertEquals("https://cdn.example/exhigh.mp3", url);
            gate.countDown();
            Thread.sleep(500);
            assertFalse(standardRan.get(), "排队中的 standard 任务命中后应被取消不再执行");
            verify(neteaseApiService, never()).getSongUrl("67890", "standard");
        } finally {
            gate.countDown();
            single.shutdown();
        }
    }

    @Test @DisplayName("预算耗尽时跳过 QQ 降级兜底，不多等 45s")
    void budgetExhaustedSkipsStandardFallback() {
        when(neteaseApiService.getSongUrl(eq("99999"), anyString())).thenAnswer(inv -> {
            Thread.sleep(8500);
            return null;
        });

        long start = System.currentTimeMillis();
        String url = songPlayService.getPlayUrl("99999");
        long elapsed = System.currentTimeMillis() - start;

        assertNull(url);
        assertTrue(elapsed < 20000, "预算耗尽后兜底应跳过，总耗时应远小于 8s+45s，实际=" + elapsed + "ms");
        verify(neteaseApiService, never()).searchQQ(anyString(), anyInt());
    }

    @SuppressWarnings("unchecked")
    private org.springframework.data.redis.core.ValueOperations<String, String> stubMinioFlag(String sourceId, String flag) {
        org.springframework.data.redis.core.ValueOperations<String, String> vo =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        doReturn(vo).when(stringRedisTemplate).opsForValue();
        lenient().when(vo.get("minio:exists:v1:" + sourceId)).thenReturn(flag);
        return vo;
    }

    @Test @DisplayName("getPlayUrl 全试听后 QQ 降级命中同名歌曲")
    void getPlayUrlAllTrialFallsBackToQq() {
        Map<String, Object> trial = Map.of("data", List.of(
                Map.of("url", "https://cdn.example/trial.mp3", "time", 15000)));
        when(neteaseApiService.getSongUrl(eq("77777"), anyString())).thenReturn(trial);
        when(neteaseApiService.searchQQ(eq("青花瓷 周杰伦"), anyInt())).thenReturn(Map.of("data", List.of(
                Map.of("id", "qq777", "name", "青花瓷", "artists", "周杰伦", "duration", 240000))));
        when(neteaseApiService.getQQSongUrl("qq777")).thenReturn(Map.of("data", List.of(
                Map.of("url", "https://qq-cdn.example/qq777.mp3"))));

        String url = songPlayService.getPlayUrl("77777", "青花瓷", "周杰伦");

        assertEquals("https://qq-cdn.example/qq777.mp3", url);
    }

    @Test @DisplayName("getPlayUrl 一参/三参重载透传到四参，不抛错")
    void getPlayUrlOverloadsDelegate() {
        when(songMapper.selectOne(any())).thenReturn(null);

        assertDoesNotThrow(() -> songPlayService.getPlayUrl("12345"));
        assertDoesNotThrow(() -> songPlayService.getPlayUrl("12345", "晴天", "周杰伦"));
    }

    @Test @DisplayName("getPlayUrl 命中 MinIO 缓存直接返回且不碰 API/不计数降级")
    void getPlayUrlHitsMinioCache() {
        stubMinioFlag("12345", "1");
        when(storageService.getDirectUrl("songs/12345.mp3")).thenReturn("https://minio/x.mp3");

        assertEquals("https://minio/x.mp3", songPlayService.getPlayUrl("12345"));
        verify(neteaseApiService, never()).getQQSongUrl(anyString());
        verify(neteaseApiService, never()).getSongUrl(anyString(), anyString());
        assertEquals(0, songPlayService.getDegradationCount());
    }

    @Test @DisplayName("getPlayUrl QQ 分支有链直接返回")
    void getPlayUrlReturnsQqUrl() {
        Map<String, Object> qq = Map.of("data", List.of(Map.of("url", "https://qq-cdn.example/q.mp3")));
        when(neteaseApiService.getQQSongUrl("qqabc")).thenReturn(qq);

        assertEquals("https://qq-cdn.example/q.mp3",
                songPlayService.getPlayUrl("qqabc", "晴天", "周杰伦", "qq"));
    }

    @Test @DisplayName("getPlayUrl QQ 异常且网易云降级无结果时走 DB 兜底返回 null")
    void getPlayUrlQqFailureFallsToDb() {
        when(neteaseApiService.getQQSongUrl("qqbad")).thenThrow(new RuntimeException("api down"));
        when(songMapper.selectOne(any())).thenReturn(null);

        assertNull(songPlayService.getPlayUrl("qqbad", "晴天", "周杰伦", "qq"));
    }

    @Test @DisplayName("getPlayUrl 网易云全空时走 DB 兜底返回历史 URL")
    void getPlayUrlNeteaseEmptyFallsToDb() {
        when(neteaseApiService.getSongUrl(eq("55555"), anyString())).thenReturn(null);
        when(songMapper.selectOne(any()))
                .thenReturn(Song.builder().sourceId("55555").url("https://db/ne.mp3").build());

        assertEquals("https://db/ne.mp3", songPlayService.getPlayUrl("55555", "晴天", "周杰伦"));
    }
}
