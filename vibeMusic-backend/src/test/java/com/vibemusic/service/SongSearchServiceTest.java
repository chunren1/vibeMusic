package com.vibemusic.service;

import com.vibemusic.common.exception.BusinessException;
import com.vibemusic.dto.SearchResult;
import com.vibemusic.dto.SongDTO;
import com.vibemusic.mapper.SongMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SongSearchService 搜索服务测试
 * <p>
 * 纯 Mockito 单元测试，Mock Redis/musicapi 依赖，
 * 验证降级链：Redis → API → 空结果兜底。
 */
@DisplayName("SongSearchService 搜索服务测试")
class SongSearchServiceTest {

    private SongMapper songMapper;
    private NeteaseApiService neteaseApiService;
    private SongCacheService cacheService;
    private SongSearchService songSearchService;
    private StringRedisTemplate redisTemplate;
    private ThreadPoolTaskExecutor searchExec;
    private ThreadPoolTaskExecutor warmExec;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        songMapper = mock(SongMapper.class);
        neteaseApiService = mock(NeteaseApiService.class);
        cacheService = mock(SongCacheService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        meterRegistry = new SimpleMeterRegistry();
        searchExec = new ThreadPoolTaskExecutor();
        searchExec.setCorePoolSize(2);
        searchExec.setMaxPoolSize(2);
        searchExec.setQueueCapacity(10);
        searchExec.initialize();
        warmExec = new ThreadPoolTaskExecutor();
        warmExec.setCorePoolSize(1);
        warmExec.setMaxPoolSize(1);
        warmExec.setQueueCapacity(5);
        warmExec.initialize();
        songSearchService = new SongSearchService(songMapper, neteaseApiService,
                cacheService, meterRegistry, searchExec, warmExec,
                redisTemplate);
        songSearchService.initMetrics();
    }

    @AfterEach
    void tearDown() {
        if (searchExec != null) searchExec.shutdown();
        if (warmExec != null) warmExec.shutdown();
    }

    private SongDTO createSong(String sourceId, String name, String artist) {
        SongDTO s = new SongDTO();
        s.setSourceId(sourceId);
        s.setName(name);
        s.setArtist(artist);
        s.setDuration(240);
        return s;
    }

    @Nested @DisplayName("L1: Redis 缓存命中")
    class RedisHitTest {

        @Test @DisplayName("Redis 命中应直接返回缓存结果，不查 API")
        void shouldReturnRedisCache() {
            List<SongDTO> cached = List.of(createSong("1", "晴天", "周杰伦"));
            when(cacheService.getSearchCache(eq("晴天:all"))).thenReturn(cached);

            SearchResult result = songSearchService.search("晴天", 1, 20);

            assertEquals("redis", result.getSource());
            assertEquals(1, result.getList().size());
            verify(cacheService, never()).setSearchCache(anyString(), anyList(), anyBoolean());
            verify(neteaseApiService, never()).searchNetease(anyString(), anyInt());
        }
    }

    @Nested @DisplayName("L3: API 实时搜索")
    class ApiHitTest {

        @Test @DisplayName("Redis 未命中 + API 返回结果应聚合去重")
        void shouldSearchFromApiAndMerge() {
            when(cacheService.getSearchCache(anyString())).thenReturn(null);

            var neSong = Map.of("id", "3", "name", "夜曲", "artists", "周杰伦",
                    "album", "十一月的肖邦", "cover", "", "duration", 300000);
            when(neteaseApiService.searchNetease("夜曲", 40))
                    .thenReturn(Map.of("data", List.of(neSong)));
            when(neteaseApiService.searchQQ("夜曲", 40))
                    .thenReturn(Map.of("data", List.of()));

            SearchResult result = songSearchService.search("夜曲", 1, 20);

            assertEquals("api", result.getSource());
            assertFalse(result.getList().isEmpty());
            assertEquals("netease", result.getList().get(0).getPlatform());
            verify(cacheService).setSearchCache(anyString(), anyList(), eq(true), anyBoolean());
        }

        @Test @DisplayName("API 超时应降级返回空列表")
        void shouldReturnEmptyOnApiTimeout() {
            when(cacheService.getSearchCache(anyString())).thenReturn(null);
            when(neteaseApiService.searchNetease(anyString(), anyInt())).thenThrow(new RuntimeException("timeout"));
            when(neteaseApiService.searchQQ(anyString(), anyInt())).thenThrow(new RuntimeException("timeout"));

            SearchResult result = songSearchService.search("超时测试", 1, 20);

            assertEquals("api", result.getSource());
            assertTrue(result.getList().isEmpty());
        }
    }

    @Nested @DisplayName("L4: 空结果兜底")
    class EmptyFallbackTest {

        @Test @DisplayName("空关键词应返回 none 源")
        void shouldReturnNoneForBlankKeyword() {
            SearchResult result = songSearchService.search("   ", 1, 20);
            assertEquals("none", result.getSource());
            assertTrue(result.getList().isEmpty());
        }

        @Test @DisplayName("null 关键词应返回 none 源")
        void shouldReturnNoneForNullKeyword() {
            SearchResult result = songSearchService.search((String) null, 1, 20);
            assertEquals("none", result.getSource());
            assertTrue(result.getList().isEmpty());
        }
    }

    @Nested @DisplayName("分平台搜索")
    class PlatformSearchTest {

        @Test @DisplayName("platform=netease 只搜网易云")
        void shouldSearchNeteaseOnly() {
            when(cacheService.getSearchCache(eq("网易云歌:netease"))).thenReturn(null);
            when(neteaseApiService.searchNetease("网易云歌", 40))
                    .thenReturn(Map.of("data", List.of(
                            Map.of("id", "4", "name", "网易云歌曲", "artists", "歌手", "duration", 200000))));

            SearchResult result = songSearchService.search("网易云歌", 1, 20, "netease");

            assertEquals("api", result.getSource());
            verify(neteaseApiService, times(1)).searchNetease(anyString(), anyInt());
            verify(neteaseApiService, never()).searchQQ(anyString(), anyInt());
        }

        @Test @DisplayName("platform=qq 只搜 QQ")
        void shouldSearchQQOnly() {
            when(cacheService.getSearchCache(eq("QQ歌:qq"))).thenReturn(null);
            when(neteaseApiService.searchQQ("QQ歌", 40))
                    .thenReturn(Map.of("data", List.of(
                            Map.of("id", "5", "name", "QQ歌曲", "artists", "歌手", "duration", 200000))));

            SearchResult result = songSearchService.search("QQ歌", 1, 20, "qq");

            assertEquals("api", result.getSource());
            verify(neteaseApiService, never()).searchNetease(anyString(), anyInt());
            verify(neteaseApiService, times(1)).searchQQ(anyString(), anyInt());
        }
    }

    @Nested @DisplayName("查询相关性加分")
    class RelevanceBonusTest {

        private void mockCacheMiss() {
            when(cacheService.getSearchCache(anyString())).thenReturn(null);
        }

        private Map<String, Object> apiSong(String id, String name, String artist, int durationMs) {
            return apiSong(id, name, artist, durationMs, null);
        }

        /** vip 传 null 表示上游未知（不带 vip 键），与明确 false / true 区分。 */
        private Map<String, Object> apiSong(String id, String name, String artist, int durationMs, Boolean vip) {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("artists", artist);
            m.put("album", "专辑");
            m.put("cover", "");
            m.put("duration", durationMs);
            if (vip != null) m.put("vip", vip);
            return m;
        }

        @Test @DisplayName("精确歌名在上游第20名也应排到首位")
        void exactNameAtRank20OutranksRank1Mismatch() {
            mockCacheMiss();
            List<Map<String, Object>> raws = new ArrayList<>();
            for (int i = 0; i < 19; i++) {
                raws.add(apiSong("ne" + i, "无关歌" + i, "路人歌手" + i, 200000));
            }
            raws.add(apiSong("ne19", "来不及爱你", "筷子兄弟", 240000));
            when(neteaseApiService.searchNetease("来不及爱你", 40))
                    .thenReturn(Map.of("data", raws));
            when(neteaseApiService.searchQQ("来不及爱你", 40))
                    .thenReturn(Map.of("data", List.of()));

            SearchResult result = songSearchService.search("来不及爱你", 1, 20);

            assertEquals("api", result.getSource());
            assertEquals(20, result.getList().size());
            assertEquals("来不及爱你", result.getList().get(0).getName());
            assertEquals("筷子兄弟", result.getList().get(0).getArtist());
        }

        @Test @DisplayName("多token查询中歌手token应提升正确版本")
        void artistTokenPromotesCorrectVersion() {
            mockCacheMiss();
            when(neteaseApiService.searchNetease("来不及爱你 汪苏泷", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("ne1", "来不及爱你", "筷子兄弟", 240000),
                    apiSong("ne2", "来不及爱你", "汪苏泷", 240000))));
            when(neteaseApiService.searchQQ("来不及爱你 汪苏泷", 40))
                    .thenReturn(Map.of("data", List.of()));

            SearchResult result = songSearchService.search("来不及爱你 汪苏泷", 1, 20);

            assertEquals(2, result.getList().size());
            assertEquals("汪苏泷", result.getList().get(0).getArtist());
            assertEquals("筷子兄弟", result.getList().get(1).getArtist());
        }

        @Test @DisplayName("无相关性命中时单token查询保持上游顺序")
        void singleTokenWithoutMatchKeepsUpstreamOrder() {
            mockCacheMiss();
            when(neteaseApiService.searchNetease("zzzqqq不存在", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("ne1", "晴天", "周杰伦", 240000),
                    apiSong("ne2", "夜曲", "周杰伦", 240000),
                    apiSong("ne3", "七里香", "周杰伦", 240000))));
            when(neteaseApiService.searchQQ("zzzqqq不存在", 40))
                    .thenReturn(Map.of("data", List.of()));

            SearchResult result = songSearchService.search("zzzqqq不存在", 1, 20);

            assertEquals(3, result.getList().size());
            assertEquals("晴天", result.getList().get(0).getName());
            assertEquals("夜曲", result.getList().get(1).getName());
            assertEquals("七里香", result.getList().get(2).getName());
        }

        @Test @DisplayName("试听版去重仍优先完整版且平台权重与跨平台加分不变")
        void pickBestStillPrefersFullVersion() {
            mockCacheMiss();
            when(neteaseApiService.searchNetease("晴天", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("ne1", "晴天", "周杰伦", 15000, false))));
            when(neteaseApiService.searchQQ("晴天", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("qq1", "晴天", "周杰伦", 240000, false))));

            SearchResult result = songSearchService.search("晴天", 1, 20);

            assertEquals(1, result.getList().size());
            assertEquals(240, result.getList().get(0).getDuration());
            assertTrue(result.getList().get(0).getAvailableSources().contains("netease"));
            assertTrue(result.getList().get(0).getAvailableSources().contains("qq"));
            // 去重胜者为 QQ 完整版（明确非 VIP）：平台分 1.5 + 跨平台 0.3 + 精确歌名 2.0 + 非 VIP 0.5
            assertEquals(1.5 + 0.3 + 2.0 + 0.5, result.getList().get(0).getFinalScore(), 1e-9);
        }
    }

    @Nested @DisplayName("非 VIP 加权")
    class NonVipBonusTest {

        private void mockCacheMiss() {
            when(cacheService.getSearchCache(anyString())).thenReturn(null);
        }

        private Map<String, Object> apiSong(String id, String name, String artist, int durationMs, Boolean vip) {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("artists", artist);
            m.put("album", "专辑");
            m.put("cover", "");
            m.put("duration", durationMs);
            if (vip != null) m.put("vip", vip);
            return m;
        }

        @Test @DisplayName("同等相关性下非 VIP 版本排到 VIP 版本之前")
        void nonVipOutranksIdenticalVip() {
            mockCacheMiss();
            // 网易云第 2 名 VIP：0.75 + 精确歌名 2.0 = 2.75（无加成）
            // QQ 第 1 名非 VIP：0.6 + 精确歌名 2.0 + 非 VIP 0.5 = 3.1
            when(neteaseApiService.searchNetease("来不及爱你", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("ne0", "无关歌", "路人", 200000, false),
                    apiSong("ne1", "来不及爱你", "歌手甲", 240000, true))));
            when(neteaseApiService.searchQQ("来不及爱你", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("qq1", "来不及爱你", "歌手乙", 240000, false))));

            SearchResult result = songSearchService.search("来不及爱你", 1, 20);

            assertEquals(3, result.getList().size());
            assertEquals("歌手乙", result.getList().get(0).getArtist());
            assertEquals(0.6 + 2.0 + 0.5, result.getList().get(0).getFinalScore(), 1e-9);
            assertEquals("歌手甲", result.getList().get(1).getArtist());
            assertEquals(0.75 + 2.0, result.getList().get(1).getFinalScore(), 1e-9);
        }

        @Test @DisplayName("vip 未知（null）不得分：不反超已知可用版本")
        void nullVipGetsNoBonus() {
            mockCacheMiss();
            // 网易云第 1 名未知 vip：1.5 + 精确歌名 2.0 + 0 = 3.5，仍凭上游排名第一
            // 网易云第 2 名明确非 VIP：0.75 + 精确歌名 2.0 + 0.5 = 3.25
            when(neteaseApiService.searchNetease("来不及爱你", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("ne1", "来不及爱你", "歌手甲", 240000, null),
                    apiSong("ne2", "来不及爱你", "歌手乙", 240000, false))));
            when(neteaseApiService.searchQQ("来不及爱你", 40))
                    .thenReturn(Map.of("data", List.of()));

            SearchResult result = songSearchService.search("来不及爱你", 1, 20);

            assertEquals(2, result.getList().size());
            assertEquals("歌手甲", result.getList().get(0).getArtist());
            assertEquals(1.5 + 2.0, result.getList().get(0).getFinalScore(), 1e-9);
            assertEquals("歌手乙", result.getList().get(1).getArtist());
            assertEquals(0.75 + 2.0 + 0.5, result.getList().get(1).getFinalScore(), 1e-9);
        }
    }

    @Nested @DisplayName("QQ 熔断器")
    class QqBreakerTest {

        private java.util.concurrent.atomic.AtomicLong fakeNow;

        @BeforeEach
        void useFakeClock() {
            fakeNow = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
            songSearchService.setQqClockForTest(fakeNow::get);
        }

        private void mockCacheMiss() {
            when(cacheService.getSearchCache(anyString())).thenReturn(null);
        }

        @Test @DisplayName("连续 3 次失败开路，此前保持闭路；成功清零")
        void closedToOpenAndSuccessResets() {
            assertFalse(songSearchService.shouldSkipQq());
            songSearchService.recordQqFailure();
            songSearchService.recordQqFailure();
            assertFalse(songSearchService.shouldSkipQq());
            songSearchService.recordQqSuccess();
            songSearchService.recordQqFailure();
            songSearchService.recordQqFailure();
            assertFalse(songSearchService.shouldSkipQq());
            songSearchService.recordQqFailure();
            assertTrue(songSearchService.shouldSkipQq());
        }

        @Test @DisplayName("开路 5 分钟内跳过，半开仅放行一个探针")
        void openHalfOpenSingleProbe() {
            songSearchService.recordQqFailure();
            songSearchService.recordQqFailure();
            songSearchService.recordQqFailure();
            assertTrue(songSearchService.shouldSkipQq());
            fakeNow.addAndGet(4 * 60 * 1000L);
            assertTrue(songSearchService.shouldSkipQq());
            fakeNow.addAndGet(60 * 1000L);
            assertFalse(songSearchService.shouldSkipQq());
            assertTrue(songSearchService.shouldSkipQq());
        }

        @Test @DisplayName("探针成功闭路，探针失败重开并重置计时")
        void probeSuccessClosesProbeFailureReopens() {
            songSearchService.recordQqFailure();
            songSearchService.recordQqFailure();
            songSearchService.recordQqFailure();
            fakeNow.addAndGet(5 * 60 * 1000L);
            assertFalse(songSearchService.shouldSkipQq());
            songSearchService.recordQqSuccess();
            assertFalse(songSearchService.shouldSkipQq());
            songSearchService.recordQqFailure();
            songSearchService.recordQqFailure();
            assertFalse(songSearchService.shouldSkipQq());

            songSearchService.recordQqFailure();
            assertTrue(songSearchService.shouldSkipQq());
            fakeNow.addAndGet(5 * 60 * 1000L);
            assertFalse(songSearchService.shouldSkipQq());
            songSearchService.recordQqFailure();
            assertTrue(songSearchService.shouldSkipQq());
            fakeNow.addAndGet(4 * 60 * 1000L + 59 * 1000L);
            assertTrue(songSearchService.shouldSkipQq());
            fakeNow.addAndGet(1000L);
            assertFalse(songSearchService.shouldSkipQq());
        }

        @Test @DisplayName("QQ 连挂 3 次后冷搜索跳过 QQ：不再调用 searchQQ")
        void wiringSkipsQqAfterConsecutiveFailures() {
            mockCacheMiss();
            when(neteaseApiService.searchNetease(anyString(), anyInt()))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchQQ(anyString(), anyInt()))
                    .thenThrow(new RuntimeException("QQ down"));

            songSearchService.search("熔断布线", 1, 20);
            songSearchService.search("熔断布线", 1, 20);
            songSearchService.search("熔断布线", 1, 20);
            songSearchService.search("熔断布线", 1, 20);

            verify(neteaseApiService, times(3)).searchQQ(anyString(), anyInt());
        }

        @Test @DisplayName("platform=qq 单搜同样受熔断跳过")
        void singleQqSearchRespectsBreaker() {
            when(cacheService.getSearchCache(anyString())).thenReturn(null);
            when(neteaseApiService.searchQQ(anyString(), anyInt()))
                    .thenThrow(new RuntimeException("QQ down"));

            songSearchService.search("单搜熔断", 1, 20, "qq");
            songSearchService.search("单搜熔断", 1, 20, "qq");
            songSearchService.search("单搜熔断", 1, 20, "qq");
            SearchResult skipped = songSearchService.search("单搜熔断", 1, 20, "qq");

            assertTrue(skipped.getList().isEmpty());
            verify(neteaseApiService, times(3)).searchQQ(anyString(), anyInt());
        }
    }

    @Nested @DisplayName("QQ 熔断器 Redis 持久化")
    class QqBreakerRedisTest {

        private final ConcurrentHashMap<String, String> fakeHash = new ConcurrentHashMap<>();
        private volatile boolean redisDown;

        @SuppressWarnings("unchecked")
        private StringRedisTemplate fakeBreakerRedis() {
            StringRedisTemplate tpl = mock(StringRedisTemplate.class);
            HashOperations<String, String, String> ops = mock(HashOperations.class);
            String key = SongSearchService.QQ_BREAKER_REDIS_KEY;
            when(tpl.opsForHash()).thenAnswer(inv -> {
                if (redisDown) throw new RedisConnectionFailureException("Redis down");
                return ops;
            });
            when(ops.get(eq(key), anyString())).thenAnswer(inv -> {
                if (redisDown) throw new RedisConnectionFailureException("Redis down");
                return fakeHash.get(inv.getArgument(1));
            });
            doAnswer(inv -> {
                if (redisDown) throw new RedisConnectionFailureException("Redis down");
                fakeHash.put(inv.getArgument(1), inv.getArgument(2));
                return null;
            }).when(ops).put(eq(key), anyString(), anyString());
            when(ops.increment(eq(key), anyString(), anyLong())).thenAnswer(inv -> {
                if (redisDown) throw new RedisConnectionFailureException("Redis down");
                String field = inv.getArgument(1);
                long delta = inv.getArgument(2);
                String next = fakeHash.merge(field, String.valueOf(delta),
                        (a, b) -> String.valueOf(Long.parseLong(a) + Long.parseLong(b)));
                return Long.parseLong(next);
            });
            when(tpl.expire(eq(key), anyLong(), any(TimeUnit.class))).thenAnswer(inv -> {
                if (redisDown) throw new RedisConnectionFailureException("Redis down");
                return true;
            });
            when(tpl.delete(eq(key))).thenAnswer(inv -> {
                if (redisDown) throw new RedisConnectionFailureException("Redis down");
                fakeHash.clear();
                return true;
            });
            return tpl;
        }

        private SongSearchService newBreakerService(StringRedisTemplate tpl, AtomicLong clock) {
            SongSearchService s = new SongSearchService(songMapper, neteaseApiService,
                    cacheService, new SimpleMeterRegistry(), searchExec, warmExec, tpl);
            s.initMetrics();
            s.setQqClockForTest(clock::get);
            return s;
        }

        @Test @DisplayName("连续 3 次失败开路，重启后新实例仍保持开路")
        void openSurvivesRestart() {
            StringRedisTemplate tpl = fakeBreakerRedis();
            AtomicLong clock = new AtomicLong(1_000_000L);
            SongSearchService a = newBreakerService(tpl, clock);
            assertFalse(a.shouldSkipQq());
            a.recordQqFailure();
            a.recordQqFailure();
            assertFalse(a.shouldSkipQq());
            a.recordQqFailure();
            assertTrue(a.shouldSkipQq());
            SongSearchService b = newBreakerService(tpl, clock);
            assertTrue(b.shouldSkipQq(), "重启后新实例应仍看到开路状态");
        }

        @Test @DisplayName("开路写入 TTL 等于开路窗口")
        void openStateTtlEqualsWindow() {
            StringRedisTemplate tpl = fakeBreakerRedis();
            AtomicLong clock = new AtomicLong(1_000_000L);
            SongSearchService a = newBreakerService(tpl, clock);
            a.recordQqFailure();
            a.recordQqFailure();
            a.recordQqFailure();
            assertTrue(a.shouldSkipQq());
            verify(tpl, atLeastOnce()).expire(eq(SongSearchService.QQ_BREAKER_REDIS_KEY),
                    eq(SongSearchService.QQ_BREAKER_OPEN_MS), eq(TimeUnit.MILLISECONDS));
        }

        @Test @DisplayName("半开探针成功后新老实例同样看到闭路")
        void probeSuccessClosesForAllInstances() {
            StringRedisTemplate tpl = fakeBreakerRedis();
            AtomicLong clock = new AtomicLong(1_000_000L);
            SongSearchService a = newBreakerService(tpl, clock);
            a.recordQqFailure();
            a.recordQqFailure();
            a.recordQqFailure();
            assertTrue(a.shouldSkipQq());
            clock.addAndGet(5 * 60 * 1000L);
            SongSearchService b = newBreakerService(tpl, clock);
            assertFalse(b.shouldSkipQq(), "窗口过期后新实例应放行探针");
            b.recordQqSuccess();
            SongSearchService c = newBreakerService(tpl, clock);
            assertFalse(c.shouldSkipQq());
            assertFalse(a.shouldSkipQq(), "老实例内存中的开路状态应被 Redis 闭路覆盖");
        }

        @Test @DisplayName("Redis 宕机时降级为内存行为：照常开路且搜索不抛异常")
        void redisDownDegradesToMemory() {
            StringRedisTemplate tpl = fakeBreakerRedis();
            redisDown = true;
            AtomicLong clock = new AtomicLong(1_000_000L);
            SongSearchService s = newBreakerService(tpl, clock);
            assertFalse(s.shouldSkipQq());
            s.recordQqFailure();
            s.recordQqFailure();
            assertFalse(s.shouldSkipQq());
            s.recordQqFailure();
            assertTrue(s.shouldSkipQq());
            when(cacheService.getSearchCache(anyString())).thenReturn(null);
            when(neteaseApiService.searchNetease(anyString(), anyInt()))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchQQ(anyString(), anyInt()))
                    .thenThrow(new RuntimeException("QQ down"));
            SearchResult r = s.search("降级搜索", 1, 20);
            assertNotNull(r);
            assertEquals("api", r.getSource());
            s.recordQqSuccess();
            assertFalse(s.shouldSkipQq());
        }
    }

    @Nested @DisplayName("getRandomSongs 随机推荐")
    class RandomSongsTest {

        @Test @DisplayName("API 歌曲足够时随机打乱返回")
        void shouldShuffleApiResults() {
            when(cacheService.getSearchCache(eq("热歌:all"))).thenReturn(null);

            var song = Map.of("id", "6", "name", "热歌", "artists", "歌手", "duration", 240000);
            when(neteaseApiService.searchNetease("热歌", 40)).thenReturn(Map.of("data", List.of(song)));
            when(neteaseApiService.searchQQ("热歌", 40)).thenReturn(Map.of("data", List.of()));

            List<SongDTO> result = songSearchService.getRandomSongs(1);

            assertFalse(result.isEmpty());
            assertEquals("热歌", result.get(0).getName());
        }
    }

    @Nested @DisplayName("咪咕三平台合并")
    class MiguMergeTest {

        private void mockCacheMiss() {
            when(cacheService.getSearchCache(anyString())).thenReturn(null);
        }

        private Map<String, Object> apiSong(String id, String name, String artist, int durationMs, Boolean vip) {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("artists", artist);
            m.put("album", "");
            m.put("cover", "");
            m.put("duration", durationMs);
            if (vip != null) m.put("vip", vip);
            return m;
        }

        @Test @DisplayName("platform=migu 只搜咪咕")
        void shouldSearchMiguOnly() {
            when(cacheService.getSearchCache(eq("咪咕歌:migu"))).thenReturn(null);
            when(neteaseApiService.searchMigu("咪咕歌", 40))
                    .thenReturn(Map.of("data", List.of(
                            Map.of("id", "60054701986", "name", "咪咕歌曲", "artists", "歌手", "duration", 0))));

            SearchResult result = songSearchService.search("咪咕歌", 1, 20, "migu");

            assertEquals("api", result.getSource());
            assertEquals("migu", result.getList().get(0).getPlatform());
            verify(neteaseApiService, times(1)).searchMigu(anyString(), anyInt());
            verify(neteaseApiService, never()).searchNetease(anyString(), anyInt());
            verify(neteaseApiService, never()).searchQQ(anyString(), anyInt());
        }

        @Test @DisplayName("咪咕第1名按权重公式排到网易云第2名之前")
        void miguRank1OutranksNeteaseRank2() {
            mockCacheMiss();
            when(neteaseApiService.searchNetease("青花瓷", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("ne0", "无关歌", "路人", 200000, null),
                    apiSong("ne1", "青花瓷", "周杰伦", 240000, null))));
            when(neteaseApiService.searchQQ("青花瓷", 40))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchMigu("青花瓷", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("mg1", "青花瓷Live版", "周杰伦", 0, false))));

            SearchResult result = songSearchService.search("青花瓷", 1, 20);

            assertEquals(3, result.getList().size());
            // 咪咕第1名：1.4 + 歌名包含 1.0 + 非 VIP 0.5 = 2.9
            assertEquals("migu", result.getList().get(0).getPlatform());
            assertEquals(1.4 + 1.0 + 0.5, result.getList().get(0).getFinalScore(), 1e-9);
            // 网易云第2名：0.75 + 精确歌名 2.0 = 2.75（vip 未知，中性）
            assertEquals("ne1", result.getList().get(1).getSourceId());
            assertEquals(0.75 + 2.0, result.getList().get(1).getFinalScore(), 1e-9);
        }

        @Test @DisplayName("咪咕 vip 未知（null）不得分：权重决定排在网易云之后")
        void miguNullVipGetsNoBonus() {
            mockCacheMiss();
            when(neteaseApiService.searchNetease("青花瓷", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("ne1", "青花瓷", "歌手甲", 240000, null))));
            when(neteaseApiService.searchQQ("青花瓷", 40))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchMigu("青花瓷", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("mg1", "青花瓷", "歌手乙", 240000, null))));

            SearchResult result = songSearchService.search("青花瓷", 1, 20);

            assertEquals(2, result.getList().size());
            // 网易云第1名：1.5 + 精确歌名 2.0 = 3.5；咪咕第1名：1.4 + 2.0 = 3.4
            assertEquals("netease", result.getList().get(0).getPlatform());
            assertEquals(1.5 + 2.0, result.getList().get(0).getFinalScore(), 1e-9);
            assertEquals("migu", result.getList().get(1).getPlatform());
            assertEquals(1.4 + 2.0, result.getList().get(1).getFinalScore(), 1e-9);
        }

        @Test @DisplayName("网易云与咪咕同曲合并：跨平台加分且来源齐全")
        void neteaseMiguSameSongMergesWithBonus() {
            mockCacheMiss();
            when(neteaseApiService.searchNetease("晴天", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("ne1", "晴天", "周杰伦", 240000, false))));
            when(neteaseApiService.searchQQ("晴天", 40))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchMigu("晴天", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("mg1", "晴天", "周杰伦", 0, false))));

            SearchResult result = songSearchService.search("晴天", 1, 20);

            assertEquals(1, result.getList().size());
            assertTrue(result.getList().get(0).getAvailableSources().contains("netease"));
            assertTrue(result.getList().get(0).getAvailableSources().contains("migu"));
            // 胜者为网易云完整版（咪咕 duration=0 视为试听片段）：1.5 + 跨平台 0.3 + 精确歌名 2.0 + 非 VIP 0.5
            assertEquals(1.5 + 0.3 + 2.0 + 0.5, result.getList().get(0).getFinalScore(), 1e-9);
        }

        @Test @DisplayName("咪咕异常不影响网易云/QQ结果")
        void miguFailureDegradesGracefully() {
            mockCacheMiss();
            when(neteaseApiService.searchNetease("晴天", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("ne1", "晴天", "周杰伦", 240000, null))));
            when(neteaseApiService.searchQQ("晴天", 40))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchMigu(anyString(), anyInt()))
                    .thenThrow(new RuntimeException("migu down"));

            SearchResult result = songSearchService.search("晴天", 1, 20);

            assertEquals("api", result.getSource());
            assertEquals(1, result.getList().size());
            assertEquals("netease", result.getList().get(0).getPlatform());
        }
    }

    @Nested @DisplayName("酷狗四平台合并")
    class KugouMergeTest {

        private void mockCacheMiss() {
            when(cacheService.getSearchCache(anyString())).thenReturn(null);
        }

        private Map<String, Object> apiSong(String id, String name, String artist, int durationMs, Boolean vip) {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("artists", artist);
            m.put("album", "");
            m.put("cover", "");
            m.put("duration", durationMs);
            if (vip != null) m.put("vip", vip);
            return m;
        }

        @Test @DisplayName("platform=kugou 只搜酷狗")
        void shouldSearchKugouOnly() {
            when(cacheService.getSearchCache(eq("酷狗歌:kugou"))).thenReturn(null);
            when(neteaseApiService.searchKugou("酷狗歌", 40))
                    .thenReturn(Map.of("data", List.of(
                            Map.of("id", "45f763d7beb1fd000af890eb6c70b9a2", "name", "酷狗歌曲",
                                    "artists", "歌手", "duration", 334000))));

            SearchResult result = songSearchService.search("酷狗歌", 1, 20, "kugou");

            assertEquals("api", result.getSource());
            assertEquals("kugou", result.getList().get(0).getPlatform());
            verify(neteaseApiService, times(1)).searchKugou(anyString(), anyInt());
            verify(neteaseApiService, never()).searchNetease(anyString(), anyInt());
            verify(neteaseApiService, never()).searchQQ(anyString(), anyInt());
            verify(neteaseApiService, never()).searchMigu(anyString(), anyInt());
        }

        @Test @DisplayName("酷狗结果进入 :all 合并且权重介于咪咕与 QQ 之间")
        void kugouPresentInAllWithMidWeight() {
            mockCacheMiss();
            when(neteaseApiService.searchNetease("安静", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("ne0", "无关歌", "路人", 200000, null),
                    apiSong("ne1", "安静", "周杰伦", 240000, null))));
            when(neteaseApiService.searchQQ("安静", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("qq1", "安静啦啦版", "周杰伦", 240000, false))));
            when(neteaseApiService.searchMigu("安静", 40))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchKugou("安静", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("45f763d7beb1fd000af890eb6c70b9a2", "安静", "歌手乙", 334000, false))));

            SearchResult result = songSearchService.search("安静", 1, 20);

            assertEquals("api", result.getSource());
            assertEquals(4, result.getList().size());
            // 酷狗第 1 名：1.0 + 精确歌名 2.0 + 非 VIP 0.5 = 3.5
            assertEquals("kugou", result.getList().get(0).getPlatform());
            assertEquals(1.0 + 2.0 + 0.5, result.getList().get(0).getFinalScore(), 1e-9);
            // 网易云第 2 名：0.75 + 精确歌名 2.0 = 2.75（vip 未知，中性）
            assertEquals("ne1", result.getList().get(1).getSourceId());
            assertEquals(0.75 + 2.0, result.getList().get(1).getFinalScore(), 1e-9);
        }

        @Test @DisplayName("网易云与酷狗同曲合并：跨平台加分且来源齐全")
        void neteaseKugouSameSongMergesWithBonus() {
            mockCacheMiss();
            when(neteaseApiService.searchNetease("晴天", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("ne1", "晴天", "周杰伦", 240000, false))));
            when(neteaseApiService.searchQQ("晴天", 40))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchMigu("晴天", 40))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchKugou("晴天", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("45f763d7beb1fd000af890eb6c70b9a2", "晴天", "周杰伦", 240000, false))));

            SearchResult result = songSearchService.search("晴天", 1, 20);

            assertEquals(1, result.getList().size());
            assertTrue(result.getList().get(0).getAvailableSources().contains("netease"));
            assertTrue(result.getList().get(0).getAvailableSources().contains("kugou"));
            // 胜者为网易云（先入）：1.5 + 跨平台 0.3 + 精确歌名 2.0 + 非 VIP 0.5
            assertEquals(1.5 + 0.3 + 2.0 + 0.5, result.getList().get(0).getFinalScore(), 1e-9);
        }

        @Test @DisplayName("仅酷狗失败不计全失败：有结果仍写缓存且不计数")
        void onlyKugouFailureIsNotAllFailed() {
            mockCacheMiss();
            when(cacheService.tryLock(anyString())).thenReturn("lock-kg");
            when(neteaseApiService.searchNetease(anyString(), anyInt())).thenReturn(Map.of("data", List.of(
                    apiSong("ne1", "晴天", "周杰伦", 240000, null))));
            when(neteaseApiService.searchQQ(anyString(), anyInt()))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchMigu(anyString(), anyInt()))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchKugou(anyString(), anyInt()))
                    .thenThrow(new RuntimeException("kugou down"));

            SearchResult result = songSearchService.search("晴天", 1, 20);

            assertEquals("api", result.getSource());
            assertEquals(1, result.getList().size());
            verify(cacheService).setSearchCache(anyString(), anyList(), eq(true), anyBoolean());
            assertEquals(0.0, meterRegistry.get("search.upstream.all_failed").counter().count());
        }

        @Test @DisplayName("五平台全失败不写空哨兵并计数")
        void allFiveUpstreamFailedCountsWithoutSentinel() {
            when(cacheService.getSearchCache(anyString())).thenReturn(null);
            when(cacheService.tryLock(anyString())).thenReturn("lock-kg-all");
            when(neteaseApiService.searchNetease(anyString(), anyInt()))
                    .thenThrow(new RuntimeException("netease down"));
            when(neteaseApiService.searchQQ(anyString(), anyInt()))
                    .thenThrow(new RuntimeException("qq down"));
            when(neteaseApiService.searchMigu(anyString(), anyInt()))
                    .thenThrow(new RuntimeException("migu down"));
            when(neteaseApiService.searchKugou(anyString(), anyInt()))
                    .thenThrow(new RuntimeException("kugou down"));
            when(neteaseApiService.searchBili(anyString(), anyInt()))
                    .thenThrow(new RuntimeException("bili down"));

            SearchResult result = songSearchService.search("四挂词", 1, 20);

            assertEquals("api", result.getSource());
            assertTrue(result.getList().isEmpty());
            verify(cacheService, never()).setSearchCache(anyString(), anyList(), anyBoolean(), anyBoolean());
            verify(cacheService, never()).setSearchCache(anyString(), anyList(), anyBoolean());
            assertEquals(1.0, meterRegistry.get("search.upstream.all_failed").counter().count());
        }

        @Test @DisplayName("酷狗异常不影响网易云结果")
        void kugouFailureDegradesGracefully() {
            mockCacheMiss();
            when(neteaseApiService.searchNetease("晴天", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("ne1", "晴天", "周杰伦", 240000, null))));
            when(neteaseApiService.searchQQ("晴天", 40))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchMigu("晴天", 40))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchKugou(anyString(), anyInt()))
                    .thenThrow(new RuntimeException("kugou down"));

            SearchResult result = songSearchService.search("晴天", 1, 20);

            assertEquals("api", result.getSource());
            assertEquals(1, result.getList().size());
            assertEquals("netease", result.getList().get(0).getPlatform());
        }
    }

    @Nested @DisplayName("B站五平台合并")
    class BiliMergeTest {

        private void mockCacheMiss() {
            when(cacheService.getSearchCache(anyString())).thenReturn(null);
        }

        private Map<String, Object> apiSong(String id, String name, String artist, int durationMs, Boolean vip) {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("artists", artist);
            m.put("album", "");
            m.put("cover", "");
            m.put("duration", durationMs);
            if (vip != null) m.put("vip", vip);
            return m;
        }

        /** 网关形态 B 站行：封面 + _raw 播放量/弹幕(门控与热度透传用)。 */
        private Map<String, Object> biliSong(String id, String name, String artist, int durationMs,
                                             String cover, long plays, long danmaku) {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("artists", artist);
            m.put("album", "");
            m.put("cover", cover);
            m.put("duration", durationMs);
            m.put("vip", false);
            Map<String, Object> raw = new java.util.HashMap<>();
            raw.put("play", plays);
            raw.put("danmaku", danmaku);
            m.put("_raw", raw);
            return m;
        }

        @Test @DisplayName("platform=bilibili 只搜B站")
        void shouldSearchBiliOnly() {
            when(cacheService.getSearchCache(eq("B站歌:bilibili"))).thenReturn(null);
            when(neteaseApiService.searchBili("B站歌", 40))
                    .thenReturn(Map.of("data", List.of(
                            Map.of("id", "BV1De411p77r", "name", "B站歌曲",
                                    "artists", "UP主", "duration", 258000))));

            SearchResult result = songSearchService.search("B站歌", 1, 20, "bilibili");

            assertEquals("api", result.getSource());
            assertEquals("bilibili", result.getList().get(0).getPlatform());
            verify(neteaseApiService, times(1)).searchBili(anyString(), anyInt());
            verify(neteaseApiService, never()).searchNetease(anyString(), anyInt());
            verify(neteaseApiService, never()).searchQQ(anyString(), anyInt());
            verify(neteaseApiService, never()).searchMigu(anyString(), anyInt());
            verify(neteaseApiService, never()).searchKugou(anyString(), anyInt());
        }

        @Test @DisplayName("B站结果进入 :all 合并且权重介于酷狗与 QQ 之间")
        void biliPresentInAllWithLowMidWeight() {
            mockCacheMiss();
            when(neteaseApiService.searchNetease("少年", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("ne0", "无关歌", "路人", 200000, null),
                    apiSong("ne1", "少年", "周杰伦", 240000, null))));
            when(neteaseApiService.searchQQ("少年", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("qq1", "少年啦啦版", "周杰伦", 240000, false))));
            when(neteaseApiService.searchMigu("少年", 40))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchKugou("少年", 40))
                    .thenReturn(Map.of("data", List.of()));
            // 强行：有封面 + 标题成形 + 播放量达标，正常参与排序
            when(neteaseApiService.searchBili("少年", 40)).thenReturn(Map.of("data", List.of(
                    biliSong("BV1De411p77r", "少年", "歌手乙", 258000,
                            "https://i0.hdslb.com/bfs/archive/x.jpg", 2_955_739L, 17_317L))));

            SearchResult result = songSearchService.search("少年", 1, 20);

            assertEquals("api", result.getSource());
            assertEquals(4, result.getList().size());
            // B站第 1 名：0.7 + 精确歌名 2.0 + 非 VIP 0.5 = 3.2
            assertEquals("bilibili", result.getList().get(0).getPlatform());
            assertEquals(0.7 + 2.0 + 0.5, result.getList().get(0).getFinalScore(), 1e-9);
            // 网易云第 2 名：0.75 + 精确歌名 2.0 = 2.75（vip 未知，中性）
            assertEquals("ne1", result.getList().get(1).getSourceId());
            assertEquals(0.75 + 2.0, result.getList().get(1).getFinalScore(), 1e-9);
        }

        @Test @DisplayName("弱B站行封顶沉底：无封面低播放即使歌名精确也不得越过0.5")
        void weakBiliRowCappedToBottom() {
            mockCacheMiss();
            when(neteaseApiService.searchNetease("少年", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("ne0", "无关歌", "路人", 200000, null),
                    apiSong("ne1", "少年", "周杰伦", 240000, null))));
            when(neteaseApiService.searchQQ("少年", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("qq1", "少年啦啦版", "周杰伦", 240000, false))));
            when(neteaseApiService.searchMigu("少年", 40))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchKugou("少年", 40))
                    .thenReturn(Map.of("data", List.of()));
            // 弱行：无封面 + 播放/弹幕双低 → 加成后照样封顶 0.5
            when(neteaseApiService.searchBili("少年", 40)).thenReturn(Map.of("data", List.of(
                    biliSong("BV1weak000001", "少年", "UP主", 258000, "", 25_548L, 36L))));

            SearchResult result = songSearchService.search("少年", 1, 20);

            assertEquals("api", result.getSource());
            assertEquals(4, result.getList().size());
            assertEquals("ne1", result.getList().get(0).getSourceId());
            assertEquals("qq1", result.getList().get(1).getSourceId());
            assertEquals("ne0", result.getList().get(2).getSourceId());
            assertEquals("bilibili", result.getList().get(3).getPlatform());
            assertEquals(0.5, result.getList().get(3).getFinalScore(), 1e-9);
        }

        @Test @DisplayName("弱B站行残留标题括号同样沉底：歌形成立但标题未解析")
        void weakBiliRowWithRawTitleCapped() {
            mockCacheMiss();
            when(neteaseApiService.searchNetease("晴天", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("ne1", "晴天", "周杰伦", 240000, null))));
            when(neteaseApiService.searchQQ("晴天", 40))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchMigu("晴天", 40))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchKugou("晴天", 40))
                    .thenReturn(Map.of("data", List.of()));
            // 有封面、有播放，但标题残留【】(网关歧义回退) → 仍判弱
            when(neteaseApiService.searchBili("晴天", 40)).thenReturn(Map.of("data", List.of(
                    biliSong("BV1raw000001", "【疑似《那天下雨了》MV】晴天", "UP主", 147000,
                            "https://i0.hdslb.com/bfs/archive/y.jpg", 900_000L, 5_000L))));

            SearchResult result = songSearchService.search("晴天", 1, 20);

            assertEquals(2, result.getList().size());
            assertEquals("netease", result.getList().get(0).getPlatform());
            assertEquals("bilibili", result.getList().get(1).getPlatform());
            assertEquals(0.5, result.getList().get(1).getFinalScore(), 1e-9);
        }

        @Test @DisplayName("跨平台合并行不受门控封顶：弱B站与网易云同曲时胜者保分")
        void mergedBiliRowExemptFromGate() {
            mockCacheMiss();
            when(neteaseApiService.searchNetease("晴天", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("ne1", "晴天", "周杰伦", 240000, false))));
            when(neteaseApiService.searchQQ("晴天", 40))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchMigu("晴天", 40))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchKugou("晴天", 40))
                    .thenReturn(Map.of("data", List.of()));
            // 同曲弱 B 站行：合并后有多源背书，不封顶
            when(neteaseApiService.searchBili("晴天", 40)).thenReturn(Map.of("data", List.of(
                    biliSong("BV1xx00000001", "晴天", "周杰伦", 240000, "", 10L, 0L))));

            SearchResult result = songSearchService.search("晴天", 1, 20);

            assertEquals(1, result.getList().size());
            assertTrue(result.getList().get(0).getAvailableSources().contains("bilibili"));
            assertEquals(1.5 + 0.3 + 2.0 + 0.5, result.getList().get(0).getFinalScore(), 1e-9);
        }

        @Test @DisplayName("isBiliStrong 边界：播放10万/弹幕1000恰好放行，差一即拦")
        void isBiliStrongBoundaries() {
            SongDTO base = SongDTO.builder().name("晴天").artist("周杰伦")
                    .coverUrl("https://i0.hdslb.com/x.jpg").build();
            SongDTO playsOk = SongDTO.builder().name("晴天").artist("周杰伦")
                    .coverUrl("https://i0.hdslb.com/x.jpg").playCount(100_000L).danmakuCount(0L).build();
            assertTrue(SongSearchService.isBiliStrong(playsOk));
            SongDTO playsShort = SongDTO.builder().name("晴天").artist("周杰伦")
                    .coverUrl("https://i0.hdslb.com/x.jpg").playCount(99_999L).danmakuCount(999L).build();
            assertFalse(SongSearchService.isBiliStrong(playsShort));
            SongDTO danmakuOk = SongDTO.builder().name("晴天").artist("周杰伦")
                    .coverUrl("https://i0.hdslb.com/x.jpg").playCount(50_000L).danmakuCount(1_000L).build();
            assertTrue(SongSearchService.isBiliStrong(danmakuOk));
            // 无封面：播放再高也弱
            SongDTO noCover = SongDTO.builder().name("晴天").artist("周杰伦")
                    .coverUrl("").playCount(9_000_000L).danmakuCount(80_000L).build();
            assertFalse(SongSearchService.isBiliStrong(noCover));
            // 热度未知(null，老缓存形态)：按 0 计→弱
            assertFalse(SongSearchService.isBiliStrong(base));
            // 超长标题/残留括号→弱
            SongDTO longName = SongDTO.builder().name("晴".repeat(61)).artist("周杰伦")
                    .coverUrl("https://i0.hdslb.com/x.jpg").playCount(9_000_000L).build();
            assertFalse(SongSearchService.isBiliStrong(longName));
            SongDTO rawTitle = SongDTO.builder().name("【4K】晴天").artist("周杰伦")
                    .coverUrl("https://i0.hdslb.com/x.jpg").playCount(9_000_000L).build();
            assertFalse(SongSearchService.isBiliStrong(rawTitle));
        }

        @Test @DisplayName("B站封面走 image-proxy 代取：hdslb 代理、他站 https 原样")
        void biliCoverProxiedForHotlink() {
            when(cacheService.getSearchCache(eq("晴天:bilibili"))).thenReturn(null);
            when(neteaseApiService.searchBili("晴天", 40)).thenReturn(Map.of("data", List.of(
                    biliSong("BV1De411p77r", "晴天", "周杰伦", 258000,
                            "https://i0.hdslb.com/bfs/archive/x.jpg", 2_955_739L, 17_317L),
                    biliSong("BV1other00001", "夜曲", "周杰伦", 240000,
                            "https://example.com/y.jpg", 500_000L, 2_000L))));

            SearchResult result = songSearchService.search("晴天", 1, 20, "bilibili");

            assertEquals("api", result.getSource());
            assertEquals(2, result.getList().size());
            assertTrue(result.getList().get(0).getCoverUrl().startsWith("/api/image-proxy?url="));
            assertTrue(result.getList().get(0).getCoverUrl().contains("i0.hdslb.com"));
            assertEquals("https://example.com/y.jpg", result.getList().get(1).getCoverUrl());
            // 热度透传上 DTO
            assertEquals(2_955_739L, result.getList().get(0).getPlayCount());
            assertEquals(17_317L, result.getList().get(0).getDanmakuCount());
        }

        @Test @DisplayName("网易云与B站同曲合并：跨平台加分且来源齐全")
        void neteaseBiliSameSongMergesWithBonus() {
            mockCacheMiss();
            when(neteaseApiService.searchNetease("晴天", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("ne1", "晴天", "周杰伦", 240000, false))));
            when(neteaseApiService.searchQQ("晴天", 40))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchMigu("晴天", 40))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchKugou("晴天", 40))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchBili("晴天", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("BV1xx00000001", "晴天", "周杰伦", 240000, false))));

            SearchResult result = songSearchService.search("晴天", 1, 20);

            assertEquals(1, result.getList().size());
            assertTrue(result.getList().get(0).getAvailableSources().contains("netease"));
            assertTrue(result.getList().get(0).getAvailableSources().contains("bilibili"));
            // 胜者为网易云（先入）：1.5 + 跨平台 0.3 + 精确歌名 2.0 + 非 VIP 0.5
            assertEquals(1.5 + 0.3 + 2.0 + 0.5, result.getList().get(0).getFinalScore(), 1e-9);
        }

        @Test @DisplayName("仅B站失败不计全失败：有结果仍写缓存且不计数")
        void onlyBiliFailureIsNotAllFailed() {
            mockCacheMiss();
            when(cacheService.tryLock(anyString())).thenReturn("lock-bi");
            when(neteaseApiService.searchNetease(anyString(), anyInt())).thenReturn(Map.of("data", List.of(
                    apiSong("ne1", "晴天", "周杰伦", 240000, null))));
            when(neteaseApiService.searchQQ(anyString(), anyInt()))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchMigu(anyString(), anyInt()))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchKugou(anyString(), anyInt()))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchBili(anyString(), anyInt()))
                    .thenThrow(new RuntimeException("bili down"));

            SearchResult result = songSearchService.search("晴天", 1, 20);

            assertEquals("api", result.getSource());
            assertEquals(1, result.getList().size());
            verify(cacheService).setSearchCache(anyString(), anyList(), eq(true), anyBoolean());
            assertEquals(0.0, meterRegistry.get("search.upstream.all_failed").counter().count());
        }

        @Test @DisplayName("B站异常不影响网易云结果")
        void biliFailureDegradesGracefully() {
            mockCacheMiss();
            when(neteaseApiService.searchNetease("晴天", 40)).thenReturn(Map.of("data", List.of(
                    apiSong("ne1", "晴天", "周杰伦", 240000, null))));
            when(neteaseApiService.searchQQ("晴天", 40))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchMigu("晴天", 40))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchKugou("晴天", 40))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchBili(anyString(), anyInt()))
                    .thenThrow(new RuntimeException("bili down"));

            SearchResult result = songSearchService.search("晴天", 1, 20);

            assertEquals("api", result.getSource());
            assertEquals(1, result.getList().size());
            assertEquals("netease", result.getList().get(0).getPlatform());
        }
    }

    @Nested @DisplayName("单飞锁防击穿")
    class SingleFlightTest {

        @Test @DisplayName("并发同关键词只触发一次上游搜索，其余等待共享持锁者结果")
        void concurrentSameKeywordTriggersSingleUpstreamSearch() throws Exception {
            String key = "击穿词:all";
            AtomicReference<List<SongDTO>> cacheBox = new AtomicReference<>(null);
            when(cacheService.getSearchCache(eq(key))).thenAnswer(inv -> cacheBox.get());
            doAnswer(inv -> {
                cacheBox.set(inv.getArgument(1));
                return null;
            }).when(cacheService).setSearchCache(anyString(), anyList(), anyBoolean(), anyBoolean());
            AtomicInteger lockCalls = new AtomicInteger(0);
            when(cacheService.tryLock(eq(key))).thenAnswer(inv ->
                    lockCalls.getAndIncrement() == 0 ? "holder-lock" : null);

            CountDownLatch holderEntered = new CountDownLatch(1);
            CountDownLatch releaseHolder = new CountDownLatch(1);
            when(neteaseApiService.searchNetease(eq("击穿词"), eq(40))).thenAnswer(inv -> {
                holderEntered.countDown();
                assertTrue(releaseHolder.await(30, TimeUnit.SECONDS), "持锁者应被主线程释放");
                return Map.of("data", List.of(
                        Map.of("id", "s1", "name", "击穿词", "artists", "歌手",
                                "album", "专辑", "cover", "", "duration", 240000)));
            });
            when(neteaseApiService.searchQQ(eq("击穿词"), eq(40)))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchMigu(eq("击穿词"), eq(40)))
                    .thenReturn(Map.of("data", List.of()));

            int threads = 4;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch startGate = new CountDownLatch(1);
            List<Future<SearchResult>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < threads; i++) {
                    futures.add(pool.submit(() -> {
                        assertTrue(startGate.await(30, TimeUnit.SECONDS));
                        return songSearchService.search("击穿词", 1, 20);
                    }));
                }
                startGate.countDown();
                assertTrue(holderEntered.await(30, TimeUnit.SECONDS), "持锁者应进入上游搜索");
                Thread.sleep(100);
                releaseHolder.countDown();

                List<SearchResult> results = new ArrayList<>();
                for (Future<SearchResult> f : futures) {
                    results.add(f.get(30, TimeUnit.SECONDS));
                }
                for (SearchResult r : results) {
                    assertNotNull(r);
                    assertFalse(r.getList().isEmpty(), "所有请求都应拿到持锁者的搜索结果");
                }
                verify(neteaseApiService, times(1)).searchNetease(eq("击穿词"), eq(40));
                verify(neteaseApiService, times(1)).searchQQ(eq("击穿词"), eq(40));
            } finally {
                releaseHolder.countDown();
                pool.shutdownNow();
            }
        }

        @Test @DisplayName("持锁者崩溃无回写时等待者兜底直查，不死锁")
        void holderCrashDegradesWithoutDeadlock() throws Exception {
            when(cacheService.getSearchCache(anyString())).thenReturn(null);
            when(cacheService.tryLock(anyString())).thenReturn(null);
            when(neteaseApiService.searchNetease(anyString(), anyInt()))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchQQ(anyString(), anyInt()))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchMigu(anyString(), anyInt()))
                    .thenReturn(Map.of("data", List.of()));
            // 2 线程 × 五平台 = 10 提交，恰好容于本测试池(2 线程 + 10 队列)；
            // 3 线程会触发过载快速失败 503(另有 PoolRejectionTest 专测该语义)。
            int threads = 2;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<SearchResult>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < threads; i++) {
                    futures.add(pool.submit(() -> songSearchService.search("崩溃词", 1, 20)));
                }
                for (Future<SearchResult> f : futures) {
                    SearchResult r = f.get(30, TimeUnit.SECONDS);
                    assertNotNull(r);
                    assertEquals("api", r.getSource());
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Nested @DisplayName("线程池过载快速失败")
    class PoolRejectionTest {

        @Test @DisplayName("线程池打满时搜索快速失败抛可重试错误，不等满超时")
        void shouldFailFastWhenPoolSaturated() {
            ThreadPoolTaskExecutor tiny = new ThreadPoolTaskExecutor();
            tiny.setCorePoolSize(1);
            tiny.setMaxPoolSize(1);
            tiny.setQueueCapacity(1);
            tiny.initialize();
            CountDownLatch release = new CountDownLatch(1);
            Runnable blocker = () -> {
                try {
                    assertTrue(release.await(30, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            SimpleMeterRegistry reg = new SimpleMeterRegistry();
            SongSearchService svc = new SongSearchService(songMapper, neteaseApiService,
                    cacheService, reg, tiny, warmExec, redisTemplate);
            svc.initMetrics();
            try {
                tiny.execute(blocker);
                tiny.execute(blocker);
                when(cacheService.getSearchCache(anyString())).thenReturn(null);
                when(cacheService.tryLock(anyString())).thenReturn("lock-tp");

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> svc.search("过载词", 1, 20));
                assertEquals(503, ex.getCode());
                assertTrue(ex.getMessage().contains("稍后重试"));
                assertEquals(1.0, reg.get("search.pool.rejected").counter().count());
            } finally {
                release.countDown();
                tiny.shutdown();
            }
        }
    }

    @Nested @DisplayName("负缓存哨兵与全失败语义")
    class NegativeCacheTest {

        private void mockUpstreamDown() {
            when(neteaseApiService.searchNetease(anyString(), anyInt()))
                    .thenThrow(new RuntimeException("netease down"));
            when(neteaseApiService.searchQQ(anyString(), anyInt()))
                    .thenThrow(new RuntimeException("qq down"));
            when(neteaseApiService.searchMigu(anyString(), anyInt()))
                    .thenThrow(new RuntimeException("migu down"));
            when(neteaseApiService.searchKugou(anyString(), anyInt()))
                    .thenThrow(new RuntimeException("kugou down"));
            when(neteaseApiService.searchBili(anyString(), anyInt()))
                    .thenThrow(new RuntimeException("bili down"));
        }

        @Test @DisplayName("命中空哨兵直接返回空，不再穿透上游")
        void shouldReturnEmptyWithoutPenetrationOnSentinel() {
            when(cacheService.getSearchCache(eq("哨兵词:all"))).thenReturn(List.of());

            SearchResult result = songSearchService.search("哨兵词", 1, 20);

            assertEquals("redis", result.getSource());
            assertTrue(result.getList().isEmpty());
            verify(neteaseApiService, never()).searchNetease(anyString(), anyInt());
            verify(neteaseApiService, never()).searchQQ(anyString(), anyInt());
        }

        @Test @DisplayName("三平台全失败不写空哨兵并计数，下次仍可穿透重试")
        void shouldNotWriteSentinelWhenAllUpstreamFailed() {
            when(cacheService.getSearchCache(anyString())).thenReturn(null);
            when(cacheService.tryLock(anyString())).thenReturn("lock-fail");
            mockUpstreamDown();

            SearchResult result = songSearchService.search("全挂词", 1, 20);

            assertEquals("api", result.getSource());
            assertTrue(result.getList().isEmpty());
            verify(cacheService, never()).setSearchCache(anyString(), anyList(), anyBoolean(), anyBoolean());
            verify(cacheService, never()).setSearchCache(anyString(), anyList(), anyBoolean());
            assertEquals(1.0, meterRegistry.get("search.upstream.all_failed").counter().count());
        }

        @Test @DisplayName("三平台返回空数据（非异常）仍写空哨兵防穿透")
        void shouldWriteSentinelWhenGenuinelyEmpty() {
            when(cacheService.getSearchCache(anyString())).thenReturn(null);
            when(cacheService.tryLock(anyString())).thenReturn("lock-empty");
            when(neteaseApiService.searchNetease(anyString(), anyInt()))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchQQ(anyString(), anyInt()))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchMigu(anyString(), anyInt()))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchKugou(anyString(), anyInt()))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchBili(anyString(), anyInt()))
                    .thenReturn(Map.of("data", List.of()));

            SearchResult result = songSearchService.search("真空词", 1, 20);

            assertEquals("api", result.getSource());
            assertTrue(result.getList().isEmpty());
            verify(cacheService).setSearchCache(eq("真空词:all"), argThat(list -> list.isEmpty()),
                    eq(false), anyBoolean());
            assertEquals(0.0, meterRegistry.get("search.upstream.all_failed").counter().count());
        }
    }

    @Nested @DisplayName("入参上限校验")
    class InputCapTest {

        private void mockEmptyUpstream() {
            when(cacheService.getSearchCache(anyString())).thenReturn(null);
            when(cacheService.tryLock(anyString())).thenReturn("lock-cap");
            when(neteaseApiService.searchNetease(anyString(), anyInt()))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchQQ(anyString(), anyInt()))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchMigu(anyString(), anyInt()))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchKugou(anyString(), anyInt()))
                    .thenReturn(Map.of("data", List.of()));
            when(neteaseApiService.searchBili(anyString(), anyInt()))
                    .thenReturn(Map.of("data", List.of()));
        }

        @Test @DisplayName("超长 keyword 截断到 200 字符再参与搜索与缓存")
        void shouldTruncateOverlongKeyword() {
            mockEmptyUpstream();
            String longKw = "歌".repeat(300);

            SearchResult result = songSearchService.search(longKw, 1, 20);

            assertEquals("api", result.getSource());
            verify(cacheService).getSearchCache(eq("歌".repeat(200) + ":all"));
            verify(neteaseApiService).searchNetease(
                    argThat(s -> s != null && s.length() == 200), eq(40));
        }

        @Test @DisplayName("200 字符 keyword 不截断，原样透传")
        void shouldNotTruncateAtBoundary() {
            mockEmptyUpstream();
            String kw200 = "a".repeat(200);

            songSearchService.search(kw200, 1, 20);

            verify(neteaseApiService).searchNetease(eq(kw200), eq(40));
        }

        @Test @DisplayName("getRandomSongs(10000) 钳制到 50：DB LIMIT 不超过 50")
        void shouldClampRandomCountUpper() {
            mockEmptyUpstream();

            List<SongDTO> result = songSearchService.getRandomSongs(10000);

            assertTrue(result.size() <= 50);
            verify(songMapper).findRandomSongs(50);
        }

        @Test @DisplayName("getRandomSongs(0/负数) 钳制到 1")
        void shouldClampRandomCountLower() {
            mockEmptyUpstream();

            songSearchService.getRandomSongs(0);
            verify(songMapper).findRandomSongs(1);
            songSearchService.getRandomSongs(-5);
            verify(songMapper, times(2)).findRandomSongs(1);
        }
    }
}
