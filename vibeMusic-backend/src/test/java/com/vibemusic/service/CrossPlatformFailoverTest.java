package com.vibemusic.service;

import com.vibemusic.mapper.SongMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 跨平台换源测试（纯 Mockito，无 Spring 上下文）。
 *
 * <p>锁定点歌 404 兜底的核心契约：
 * <ul>
 *   <li>命中即止：第一个平台命中后不再搜后面的平台（省上游配额与延迟）</li>
 *   <li>前两个失败、第三个命中 → 返回第三个平台的链接</li>
 *   <li>全失败 → null（失败语义不变，仍由 controller 404）</li>
 *   <li>歌名为空 → 直接 null，且一次 search 都不发</li>
 *   <li>预算耗尽 → 立刻放弃，用固定/可推进时钟断言，不 sleep</li>
 *   <li>不递归：本类不持有 SongPlayService 引用，单平台取链次数上界 = 1</li>
 * </ul>
 */
@DisplayName("跨平台换源（点歌 404 兜底）测试")
class CrossPlatformFailoverTest {

    private static final String NAME = "青花瓷";
    private static final String ARTIST = "周杰伦";
    private static final String KEYWORD = "青花瓷 周杰伦";
    private static final Set<String> TRIED_QQ_AND_NETEASE = Set.of("qq", "netease");

    private NeteaseApiService neteaseApiService;
    private PlatformUrlFetcher platformUrlFetcher;
    private ThreadPoolTaskExecutor getUrlExecutor;
    private AtomicInteger hitCount;
    private CrossPlatformFailover failover;

    @BeforeEach
    void setUp() {
        neteaseApiService = mock(NeteaseApiService.class);
        platformUrlFetcher = mock(PlatformUrlFetcher.class);
        getUrlExecutor = new ThreadPoolTaskExecutor();
        getUrlExecutor.setCorePoolSize(2);
        getUrlExecutor.setMaxPoolSize(2);
        getUrlExecutor.setQueueCapacity(10);
        getUrlExecutor.initialize();
        hitCount = new AtomicInteger(0);
        failover = new CrossPlatformFailover(
                neteaseApiService, platformUrlFetcher, getUrlExecutor, hitCount);
    }

    @AfterEach
    void tearDown() {
        if (getUrlExecutor != null) getUrlExecutor.shutdown();
    }

    /** 外层 8s 预算下的 deadline（正常路径用真实时钟即可）。 */
    private long deadline() {
        return System.currentTimeMillis() + SongPlayService.PLAY_BUDGET_MS;
    }

    private Map<String, Object> searchHit(String id, String name, String artists, int durationMs) {
        return Map.of("data", List.of(Map.of(
                "id", id, "name", name, "artists", artists, "duration", durationMs)));
    }

    private Map<String, Object> emptyResult() {
        return Map.of("data", List.of());
    }

    @Test
    @DisplayName("第一个平台（咪咕）命中即返回，不再搜酷狗/B站/QQ")
    void firstPlatformHitStopsChain() {
        when(neteaseApiService.searchMigu(eq(KEYWORD), eq(5)))
                .thenReturn(searchHit("60054701986", NAME, ARTIST, 240000));
        when(platformUrlFetcher.fetchMiguUrl("60054701986"))
                .thenReturn("https://migu-cdn.example/x.mp3");

        CrossPlatformFailover.Result result =
                failover.tryFailover(NAME, ARTIST, TRIED_QQ_AND_NETEASE, deadline());

        assertNotNull(result);
        assertEquals("https://migu-cdn.example/x.mp3", result.url());
        assertEquals("migu", result.platform());
        assertEquals(1, hitCount.get(), "命中计数应恰好 +1");
        verify(neteaseApiService, never()).searchKugou(anyString(), anyInt());
        verify(neteaseApiService, never()).searchBili(anyString(), anyInt());
        verify(neteaseApiService, never()).searchQQ(anyString(), anyInt());
        verify(platformUrlFetcher, times(1)).fetchMiguUrl(anyString());
    }

    @Test
    @DisplayName("咪咕/酷狗无结果、B站命中 → 返回B站链接")
    void thirdPlatformHitAfterTwoMisses() {
        when(neteaseApiService.searchMigu(eq(KEYWORD), eq(5))).thenReturn(emptyResult());
        when(neteaseApiService.searchKugou(eq(KEYWORD), eq(5))).thenReturn(emptyResult());
        when(neteaseApiService.searchBili(eq(KEYWORD), eq(5)))
                .thenReturn(searchHit("BV1De411p77r", NAME, ARTIST, 240000));
        when(platformUrlFetcher.tryBiliUrl("BV1De411p77r"))
                .thenReturn("https://xy.bilivideo.com/x.m4s");

        CrossPlatformFailover.Result result =
                failover.tryFailover(NAME, ARTIST, TRIED_QQ_AND_NETEASE, deadline());

        assertNotNull(result);
        assertEquals("https://xy.bilivideo.com/x.m4s", result.url());
        assertEquals("bilibili", result.platform());
        verify(platformUrlFetcher, never()).fetchMiguUrl(anyString());
        verify(platformUrlFetcher, never()).tryKugouUrl(anyString());
        verify(platformUrlFetcher, times(1)).tryBiliUrl("BV1De411p77r");
    }

    @Test
    @DisplayName("四个平台全失败 → 返回 null（失败语义不变，仍由 controller 404）")
    void allPlatformsFailReturnsNull() {
        when(neteaseApiService.searchMigu(anyString(), anyInt())).thenReturn(emptyResult());
        when(neteaseApiService.searchKugou(anyString(), anyInt())).thenReturn(emptyResult());
        when(neteaseApiService.searchBili(anyString(), anyInt())).thenReturn(emptyResult());
        when(neteaseApiService.searchQQ(anyString(), anyInt())).thenReturn(emptyResult());

        CrossPlatformFailover.Result result =
                failover.tryFailover(NAME, ARTIST, Set.of("netease"), deadline());

        assertNull(result);
        assertEquals(0, hitCount.get());
        verify(neteaseApiService, times(1)).searchMigu(anyString(), anyInt());
        verify(neteaseApiService, times(1)).searchKugou(anyString(), anyInt());
        verify(neteaseApiService, times(1)).searchBili(anyString(), anyInt());
        verify(neteaseApiService, times(1)).searchQQ(anyString(), anyInt());
        verifyNoInteractions(platformUrlFetcher);
    }

    @Test
    @DisplayName("歌名空白 → 直接 null，且不发起任何搜索/取链")
    void blankSongNameSkipsEverything() {
        assertNull(failover.tryFailover("   ", ARTIST, Set.of(), deadline()));
        assertNull(failover.tryFailover(null, ARTIST, Set.of(), deadline()));
        verifyNoInteractions(neteaseApiService, platformUrlFetcher);
        assertEquals(0, hitCount.get());
    }

    @Test
    @DisplayName("外层预算已耗尽 → 立刻放弃，不发起任何搜索")
    void exhaustedOuterBudgetAbortsImmediately() {
        AtomicLong now = new AtomicLong(1_000L);
        CrossPlatformFailover fixedClock = new CrossPlatformFailover(
                neteaseApiService, platformUrlFetcher, getUrlExecutor, hitCount, now::get);

        // 外层 deadline 已到（now 与 deadline 相等）
        CrossPlatformFailover.Result result =
                fixedClock.tryFailover(NAME, ARTIST, Set.of("qq"), 1_000L);

        assertNull(result);
        verifyNoInteractions(neteaseApiService, platformUrlFetcher);
    }

    @Test
    @DisplayName("首个平台吃穿 3s 换源预算后，后续平台不再尝试（逐次检查剩余预算）")
    void stopsWhenBudgetRunsOutMidChain() {
        AtomicLong now = new AtomicLong(0L);
        CrossPlatformFailover fixedClock = new CrossPlatformFailover(
                neteaseApiService, platformUrlFetcher, getUrlExecutor, hitCount, now::get);
        when(neteaseApiService.searchMigu(anyString(), anyInt())).thenAnswer(inv -> {
            now.set(10_000L); // 模拟首个平台耗时吃穿 3s 预算（不 sleep）
            return emptyResult();
        });

        CrossPlatformFailover.Result result =
                fixedClock.tryFailover(NAME, ARTIST, Set.of("qq", "netease"), 60_000L);

        assertNull(result);
        verify(neteaseApiService, never()).searchKugou(anyString(), anyInt());
        verify(neteaseApiService, never()).searchBili(anyString(), anyInt());
        verify(neteaseApiService, never()).searchQQ(anyString(), anyInt());
    }

    @Test
    @DisplayName("已试过的平台不重试（bili 别名归一为 bilibili）")
    void triedPlatformsAreNotRetried() {
        when(neteaseApiService.searchKugou(eq(KEYWORD), eq(5)))
                .thenReturn(searchHit("45f763d7beb1fd000af890eb6c70b9a2", NAME, ARTIST, 240000));
        when(platformUrlFetcher.tryKugouUrl("45f763d7beb1fd000af890eb6c70b9a2"))
                .thenReturn("https://kg-cdn.example/x.mp3");

        CrossPlatformFailover.Result result = failover.tryFailover(
                NAME, ARTIST, Set.of("migu", "bili", "qq", "netease"), deadline());

        assertNotNull(result);
        assertEquals("kugou", result.platform());
        verify(neteaseApiService, never()).searchMigu(anyString(), anyInt());
        verify(neteaseApiService, never()).searchBili(anyString(), anyInt());
        verify(neteaseApiService, never()).searchQQ(anyString(), anyInt());
    }

    @Test
    @DisplayName("候选仅试听版（<=30s）时跳过，改试下一个平台")
    void trialOnlyCandidateIsSkipped() {
        when(neteaseApiService.searchMigu(eq(KEYWORD), eq(5)))
                .thenReturn(searchHit("migu-trial", NAME, ARTIST, 15_000));
        when(neteaseApiService.searchKugou(eq(KEYWORD), eq(5)))
                .thenReturn(searchHit("45f763d7beb1fd000af890eb6c70b9a2", NAME, ARTIST, 240000));
        when(platformUrlFetcher.tryKugouUrl("45f763d7beb1fd000af890eb6c70b9a2"))
                .thenReturn("https://kg-cdn.example/x.mp3");

        CrossPlatformFailover.Result result =
                failover.tryFailover(NAME, ARTIST, TRIED_QQ_AND_NETEASE, deadline());

        assertNotNull(result);
        assertEquals("kugou", result.platform());
        verify(platformUrlFetcher, never()).fetchMiguUrl(anyString());
    }

    @Test
    @DisplayName("不递归：本类不持有 SongPlayService 引用，单平台取链次数恰好 1")
    void neverRecursesIntoGetPlayUrl() {
        boolean holdsFacade = Arrays.stream(CrossPlatformFailover.class.getDeclaredFields())
                .anyMatch(f -> f.getType().equals(SongPlayService.class));
        assertFalse(holdsFacade,
                "换源类不得持有 SongPlayService 引用，否则可能回头调 getPlayUrl 形成递归");

        when(neteaseApiService.searchMigu(anyString(), anyInt()))
                .thenReturn(searchHit("m1", NAME, ARTIST, 240000));
        when(neteaseApiService.searchKugou(anyString(), anyInt()))
                .thenReturn(searchHit("45f763d7beb1fd000af890eb6c70b9a2", NAME, ARTIST, 240000));
        when(neteaseApiService.searchBili(anyString(), anyInt()))
                .thenReturn(searchHit("BV1De411p77r", NAME, ARTIST, 240000));
        when(neteaseApiService.searchQQ(anyString(), anyInt()))
                .thenReturn(searchHit("qq123", NAME, ARTIST, 240000));
        when(platformUrlFetcher.fetchMiguUrl(anyString())).thenReturn(null);
        when(platformUrlFetcher.tryKugouUrl(anyString())).thenReturn(null);
        when(platformUrlFetcher.tryBiliUrl(anyString())).thenReturn(null);
        when(platformUrlFetcher.fetchQqUrl(anyString())).thenReturn(null);

        assertNull(failover.tryFailover(NAME, ARTIST, Set.of("netease"), deadline()));

        // 取链调用次数上界 = 平台数（4）：递归会让次数成倍膨胀
        verify(platformUrlFetcher, times(1)).fetchMiguUrl(anyString());
        verify(platformUrlFetcher, times(1)).tryKugouUrl(anyString());
        verify(platformUrlFetcher, times(1)).tryBiliUrl(anyString());
        verify(platformUrlFetcher, times(1)).fetchQqUrl(anyString());
        // 换源只调 PlatformUrlFetcher 的取链方法，不碰网易云取链（递归入口）
        verify(neteaseApiService, never()).getSongUrl(anyString(), anyString());
        verify(neteaseApiService, never()).getSongUrl(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("接线：platform=qq 无链且网易降级无果时，换源从咪咕返回可播链接并标记来源")
    void wiredIntoGetPlayUrlAfterQqAndNeteaseFail() {
        SongMapper songMapper = mock(SongMapper.class);
        StorageService storageService = mock(StorageService.class);
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        when(stringRedisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));
        SongPlayService songPlayService = new SongPlayService(
                songMapper, neteaseApiService, storageService, stringRedisTemplate, getUrlExecutor);

        when(neteaseApiService.getQQSongUrl("qqvip")).thenReturn(Map.of("data", List.of(Map.of())));
        when(neteaseApiService.searchNetease(anyString(), anyInt())).thenReturn(emptyResult());
        when(neteaseApiService.searchMigu(eq(KEYWORD), eq(5)))
                .thenReturn(searchHit("60054701986", NAME, ARTIST, 240000));
        when(neteaseApiService.getMiguSongUrl("60054701986", null))
                .thenReturn(Map.of("data", List.of(Map.of("url", "https://migu-cdn.example/x.mp3"))));

        String url = songPlayService.getPlayUrl("qqvip", NAME, ARTIST, "qq");

        assertEquals("https://migu-cdn.example/x.mp3", url);
        assertEquals("migu", songPlayService.consumeFailoverPlaySource(), "应标记换源平台供 X-Play-Source");
        assertNull(songPlayService.consumeFailoverPlaySource(), "换源标记必须一次性消费");
        assertEquals(1, songPlayService.getFailoverCount());
        assertEquals(0, songPlayService.getDegradationCount(), "换源不计入原有降级计数");
    }
}
