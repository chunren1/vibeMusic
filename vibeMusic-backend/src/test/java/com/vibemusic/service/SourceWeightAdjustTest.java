package com.vibemusic.service;

import com.vibemusic.dto.SearchResult;
import com.vibemusic.mapper.SongMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
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
 * 动态源权重测试（audio-source batch b）。
 *
 * <p>纯 Mockito 单测：连续失败自动降权（1→1/2→1/4→1/8 封顶），成功即复权；
 * 健康时因子恒为 1，排序与旧静态权重逐分一致。
 */
@DisplayName("动态源权重降权/复权测试")
class SourceWeightAdjustTest {

    private SongMapper songMapper;
    private NeteaseApiService neteaseApiService;
    private SongCacheService cacheService;
    private StringRedisTemplate redisTemplate;
    private ThreadPoolTaskExecutor searchExec;
    private ThreadPoolTaskExecutor warmExec;
    private SimpleMeterRegistry meterRegistry;
    private SongSearchService service;

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
        service = new SongSearchService(songMapper, neteaseApiService,
                cacheService, meterRegistry, searchExec, warmExec, redisTemplate);
        service.initMetrics();
    }

    @AfterEach
    void tearDown() {
        if (searchExec != null) searchExec.shutdown();
        if (warmExec != null) warmExec.shutdown();
    }

    @Test
    @DisplayName("deweightFactor 档位：0→1，1→1/2，2→1/4，3+→1/8 封顶，负数按 0")
    void deweightSteps() {
        assertEquals(1.0, SongSearchService.deweightFactor(0));
        assertEquals(0.5, SongSearchService.deweightFactor(1));
        assertEquals(0.25, SongSearchService.deweightFactor(2));
        assertEquals(0.125, SongSearchService.deweightFactor(3));
        assertEquals(0.125, SongSearchService.deweightFactor(10));
        assertEquals(1.0, SongSearchService.deweightFactor(-1));
    }

    @Test
    @DisplayName("baseWeightFor 保持网易>咪咕>酷狗>B站>QQ 排序，未知平台中性 1.0")
    void baseWeightOrder() {
        assertTrue(SongSearchService.baseWeightFor("netease") > SongSearchService.baseWeightFor("migu"));
        assertTrue(SongSearchService.baseWeightFor("migu") > SongSearchService.baseWeightFor("kugou"));
        assertTrue(SongSearchService.baseWeightFor("kugou") > SongSearchService.baseWeightFor("bilibili"));
        assertTrue(SongSearchService.baseWeightFor("bilibili") > SongSearchService.baseWeightFor("qq"));
        assertEquals(1.0, SongSearchService.baseWeightFor("unknown"));
    }

    @Test
    @DisplayName("record 失败累加、成功清零、null 平台无操作")
    void recordAndReset() {
        assertDoesNotThrow(() -> service.recordSourceOutcome(null, false));
        service.recordSourceOutcome("migu", false);
        service.recordSourceOutcome("migu", false);
        assertEquals(2, service.getSourceFailures("migu"));
        assertEquals(0.35, service.effectiveWeight("migu", 1.4), 1e-9);
        service.recordSourceOutcome("migu", true);
        assertEquals(0, service.getSourceFailures("migu"));
        assertEquals(1.4, service.effectiveWeight("migu", 1.4), 1e-9);
    }

    @Test
    @DisplayName("合并搜索中网易云异常：仅网易云降权，其余源权重不变")
    void mergePathRecordsPerSourceOutcome() {
        when(cacheService.getSearchCache(anyString())).thenReturn(null);
        when(cacheService.tryLock(anyString())).thenReturn("v1");
        when(neteaseApiService.searchNetease(anyString(), anyInt()))
                .thenThrow(new RuntimeException("netease down"));
        Map<String, Object> payload = Map.of("data", List.of(
                Map.of("id", "q1", "name", "晴天", "artists", "周杰伦", "duration", 240000)));
        when(neteaseApiService.searchQQ(anyString(), anyInt())).thenReturn(payload);
        when(neteaseApiService.searchMigu(anyString(), anyInt())).thenReturn(payload);
        when(neteaseApiService.searchKugou(anyString(), anyInt())).thenReturn(payload);
        when(neteaseApiService.searchBili(anyString(), anyInt())).thenReturn(payload);

        SearchResult result = service.search("晴天", 1, 20);

        assertFalse(result.getList().isEmpty());
        assertEquals(1, service.getSourceFailures("netease"));
        assertEquals(0.75, service.effectiveWeight("netease", 1.5), 1e-9);
        assertEquals(0, service.getSourceFailures("migu"));
        assertEquals(1.4, service.effectiveWeight("migu", 1.4), 1e-9);
        double gauge = meterRegistry.get("search.source.weight").tag("platform", "netease").gauge().value();
        assertEquals(0.75, gauge, 1e-9);
    }

    @Test
    @DisplayName("全源健康时权重与旧静态值一致（健康路径排序不变）")
    void healthyWeightsUnchanged() {
        assertEquals(1.5, service.effectiveWeight("netease", 1.5), 1e-9);
        assertEquals(1.4, service.effectiveWeight("migu", 1.4), 1e-9);
        assertEquals(1.0, service.effectiveWeight("kugou", 1.0), 1e-9);
        assertEquals(0.7, service.effectiveWeight("bilibili", 0.7), 1e-9);
        assertEquals(0.6, service.effectiveWeight("qq", 0.6), 1e-9);
    }
}
