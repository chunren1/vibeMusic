package com.vibemusic.service;

import com.vibemusic.common.Result;
import com.vibemusic.dto.SearchResult;
import com.vibemusic.dto.SongDTO;
import com.vibemusic.mapper.SongMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 搜索链路韧性测试：
 * <ol>
 *   <li>单飞锁三态 —— Redis 不可用时不再白等 2.25s 退避；</li>
 *   <li>网关整体熔断 —— 连续传输失败开路后跳过 5 平台远程检索，仍返回 200 空结果结构。</li>
 * </ol>
 */
@DisplayName("搜索韧性：Redis 三态与网关整体熔断")
class SearchResilienceTest {

    private SongMapper songMapper;
    private NeteaseApiService neteaseApiService;
    private SongCacheService cacheService;
    private SongSearchService service;
    private ThreadPoolTaskExecutor searchExec;
    private ThreadPoolTaskExecutor warmExec;

    @BeforeEach
    void setUp() {
        songMapper = mock(SongMapper.class);
        neteaseApiService = mock(NeteaseApiService.class);
        cacheService = mock(SongCacheService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
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
        service = new SongSearchService(songMapper, neteaseApiService, cacheService,
                meters, searchExec, warmExec, redisTemplate);
        service.initMetrics();
        lenient().when(cacheService.getSearchCache(anyString())).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        searchExec.shutdown();
        warmExec.shutdown();
    }

    private static SongDTO song(String id, String name, String artist) {
        SongDTO s = new SongDTO();
        s.setSourceId(id);
        s.setName(name);
        s.setArtist(artist);
        s.setDuration(240);
        return s;
    }

    private void stubAllPlatformsEmpty() {
        lenient().when(neteaseApiService.searchNetease(anyString(), anyInt())).thenReturn(Map.of("data", List.of()));
        lenient().when(neteaseApiService.searchQQ(anyString(), anyInt())).thenReturn(Map.of("data", List.of()));
        lenient().when(neteaseApiService.searchMigu(anyString(), anyInt())).thenReturn(Map.of("data", List.of()));
        lenient().when(neteaseApiService.searchKugou(anyString(), anyInt())).thenReturn(Map.of("data", List.of()));
        lenient().when(neteaseApiService.searchBili(anyString(), anyInt())).thenReturn(Map.of("data", List.of()));
    }

    @Nested
    @DisplayName("单飞锁三态")
    class LockThreeState {

        @Test
        @DisplayName("退避数组总时长 = 2.25s（Redis 不可用时可整段省掉）")
        void backoffTotalMatchesDocumentedBudget() {
            assertEquals(2_250L, SongSearchService.LOCK_BACKOFF_TOTAL_MS);
        }

        @Test
        @DisplayName("UNAVAILABLE：不进入退避轮询，直接兜底直查")
        void unavailableSkipsBackoffAndSearchesDirectly() {
            stubAllPlatformsEmpty();
            when(cacheService.tryLockResult(anyString())).thenReturn(SongCacheService.LockResult.unavailable());
            when(neteaseApiService.searchNetease("晴天", 40)).thenReturn(Map.of("data", List.of(
                    Map.of("id", "n1", "name", "晴天", "artists", "周杰伦", "duration", 240000))));

            long start = System.currentTimeMillis();
            SearchResult result = service.search("晴天", 1, 20);
            long elapsed = System.currentTimeMillis() - start;

            assertEquals("api", result.getSource());
            assertEquals(1, result.getList().size());
            // 只读一次缓存（初始未命中）；退避轮询会每轮再读一次，这里必须没有
            verify(cacheService, times(1)).getSearchCache(anyString());
            // 没拿到锁 → 不释放
            verify(cacheService, never()).releaseLock(anyString(), anyString());
            assertTrue(elapsed < 1_000,
                    "Redis 不可用时应直接直查，不应白等 2.25s 退避，实际耗时=" + elapsed + "ms");
        }

        @Test
        @DisplayName("BUSY：维持原有退避重读，命中持锁者回写结果即返回 redis")
        void busyKeepsBackoffAndReadsHoldersResult() {
            when(cacheService.tryLockResult(anyString())).thenReturn(SongCacheService.LockResult.busy());
            AtomicInteger reads = new AtomicInteger();
            // 第一次读未命中；退避一轮后读到持锁者回写的结果
            when(cacheService.getSearchCache(anyString())).thenAnswer(inv ->
                    reads.getAndIncrement() == 0 ? null : List.of(song("holder-1", "晴天", "周杰伦")));

            SearchResult result = service.search("晴天", 1, 20);

            assertEquals("redis", result.getSource());
            assertEquals("holder-1", result.getList().get(0).getSourceId());
            assertTrue(reads.get() >= 2, "BUSY 必须保留退避重读行为");
            verify(neteaseApiService, never()).searchNetease(anyString(), anyInt());
        }

        @Test
        @DisplayName("ACQUIRED：拿锁搜索并释放锁")
        void acquiredSearchesAndReleases() {
            stubAllPlatformsEmpty();
            when(cacheService.tryLockResult(anyString())).thenReturn(SongCacheService.LockResult.acquired("lock-r"));
            when(neteaseApiService.searchNetease("晴天", 40)).thenReturn(Map.of("data", List.of(
                    Map.of("id", "n1", "name", "晴天", "artists", "周杰伦", "duration", 240000))));

            SearchResult result = service.search("晴天", 1, 20);

            assertEquals(1, result.getList().size());
            verify(cacheService).releaseLock("晴天:all", "lock-r");
        }
    }

    @Nested
    @DisplayName("网关整体熔断")
    class UpstreamCircuit {

        @Test
        @DisplayName("两轮全平台连接拒绝累计 10 次即开路，第三轮 0 次远程调用")
        void repeatedTransportFailuresOpenCircuitAndSkipRemoteBranches() {
            stubAllPlatformsEmpty();
            when(cacheService.tryLockResult(anyString())).thenReturn(SongCacheService.LockResult.acquired("l"));
            RuntimeException refused = new ResourceAccessException("I/O error",
                    new ConnectException("Connection refused"));
            when(neteaseApiService.searchNetease(anyString(), anyInt())).thenThrow(refused);
            when(neteaseApiService.searchQQ(anyString(), anyInt())).thenThrow(refused);
            when(neteaseApiService.searchMigu(anyString(), anyInt())).thenThrow(refused);
            when(neteaseApiService.searchKugou(anyString(), anyInt())).thenThrow(refused);
            when(neteaseApiService.searchBili(anyString(), anyInt())).thenThrow(refused);

            service.search("第一次", 1, 20);
            assertEquals(5, service.upstreamBreaker().consecutiveFailures(), "一次搜索 5 平台各计 1 次");
            assertFalse(service.upstreamBreaker().isOpen());

            service.search("第二次", 1, 20);
            assertEquals(10, service.upstreamBreaker().consecutiveFailures());
            assertTrue(service.upstreamBreaker().isOpen(), "连续 10 次传输失败应开路");

            reset(neteaseApiService);
            SearchResult result = service.search("第三次", 1, 20);

            verifyNoInteractions(neteaseApiService);
            assertNotNull(result);
            assertTrue(result.getList().isEmpty());
            assertEquals(0, result.getTotal());
            assertEquals("api", result.getSource());
            assertNull(result.getSuggestedKeyword(), "全上游不可用时不给改写建议，只提示重试");
            // 控制器统一信封仍为 200
            assertEquals(200, Result.ok(result).getCode());
            // 开路期间不写负缓存（避免恢复后 10s 内继续返回空）
            verify(cacheService, never()).setSearchCache(anyString(), anyList(), anyBoolean(), anyBoolean());
        }

        @Test
        @DisplayName("开路时单平台搜索同样跳过远程分支，返回 200 空结果")
        void openCircuitSkipsSinglePlatformSearch() {
            for (int i = 0; i < 10; i++) service.upstreamBreaker().recordTransportFailure();
            when(cacheService.tryLockResult(anyString())).thenReturn(SongCacheService.LockResult.acquired("l"));

            SearchResult result = service.search("晴天", 1, 20, "netease");

            verifyNoInteractions(neteaseApiService);
            assertEquals("api", result.getSource());
            assertTrue(result.getList().isEmpty());
            assertNull(result.getSuggestedKeyword());
        }

        @Test
        @DisplayName("200 空结果/4xx 不计失败：网关活着时不会误开路")
        void healthyEmptyAndBusinessErrorsDoNotOpenCircuit() {
            stubAllPlatformsEmpty();
            when(cacheService.tryLockResult(anyString())).thenReturn(SongCacheService.LockResult.acquired("l"));

            for (int i = 0; i < 5; i++) service.search("空结果" + i, 1, 20);

            assertEquals(0, service.upstreamBreaker().consecutiveFailures(), "200 空结果是健康应答");
            assertFalse(service.upstreamBreaker().isOpen());
        }
    }
}
