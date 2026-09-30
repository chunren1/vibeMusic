package com.vibemusic.service;

import com.vibemusic.dto.SearchResult;
import com.vibemusic.dto.SongDTO;
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
 * 空结果回退建议布线测试：拼音别名/纠错建议随 SearchResult 下发，
 * 有结果与全上游失败时不带建议；热词表对外可用。
 */
@DisplayName("搜索空结果回退建议布线测试")
class SongSearchFallbackTest {

    private NeteaseApiService neteaseApiService;
    private SongCacheService cacheService;
    private SongSearchService service;
    private SimpleMeterRegistry meters;
    private ThreadPoolTaskExecutor searchExec;
    private ThreadPoolTaskExecutor warmExec;

    @BeforeEach
    void setUp() {
        SongMapper songMapper = mock(SongMapper.class);
        neteaseApiService = mock(NeteaseApiService.class);
        cacheService = mock(SongCacheService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        meters = new SimpleMeterRegistry();
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
                cacheService, meters, searchExec, warmExec, redisTemplate);
        service.initMetrics();
        when(cacheService.getSearchCache(anyString())).thenReturn(null);
        when(cacheService.tryLock(anyString())).thenReturn("lock-fb");
    }

    @AfterEach
    void tearDown() {
        searchExec.shutdown();
        warmExec.shutdown();
    }

    @Test @DisplayName("拼音首字母空结果带标准词建议")
    void aliasEmptySuggestsCanonical() {
        SearchResult r = service.search("zjl", 1, 20);

        assertEquals("api", r.getSource());
        assertTrue(r.getList().isEmpty());
        assertEquals("周杰伦", r.getSuggestedKeyword());
    }

    @Test @DisplayName("错字空结果带纠错建议")
    void typoEmptySuggestsCorrection() {
        SearchResult r = service.search("晴添", 1, 20);

        assertTrue(r.getList().isEmpty());
        assertEquals("晴天", r.getSuggestedKeyword());
    }

    @Test @DisplayName("命中结果不带建议")
    void hitCarriesNoSuggestion() {
        when(neteaseApiService.searchNetease("晴天", 40)).thenReturn(Map.of("data", List.of(
                Map.of("id", "1", "name", "晴天", "artists", "周杰伦", "duration", 240000))));

        SearchResult r = service.search("晴天", 1, 20);

        assertEquals(1, r.getList().size());
        assertNull(r.getSuggestedKeyword());
    }

    @Test @DisplayName("五平台全失败不带改写建议并计数")
    void allFailedCarriesNoSuggestion() {
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

        SearchResult r = service.search("晴添", 1, 20);

        assertTrue(r.getList().isEmpty());
        assertNull(r.getSuggestedKeyword());
        assertEquals(1.0, meters.get("search.upstream.all_failed").counter().count());
    }

    @Test @DisplayName("命中空哨兵同样带建议且不再穿透")
    void sentinelHitSuggestsWithoutPenetration() {
        when(cacheService.getSearchCache(eq("qt:all"))).thenReturn(List.of());

        SearchResult r = service.search("qt", 1, 20);

        assertEquals("redis", r.getSource());
        assertTrue(r.getList().isEmpty());
        assertEquals("晴天", r.getSuggestedKeyword());
        verify(neteaseApiService, never()).searchNetease(anyString(), anyInt());
    }

    @Test @DisplayName("热词表对外非空且含核心词")
    void hotwordsExposed() {
        List<String> hots = SongSearchService.hotKeywords();
        assertFalse(hots.isEmpty());
        assertTrue(hots.contains("周杰伦"));
        assertTrue(hots.contains("晴天"));
        assertEquals(8, hots.size());
    }

    @Test @DisplayName("SongDTO 列表缓存形态不受新字段影响")
    void suggestionDefaultsNull() {
        SongDTO s = new SongDTO();
        SearchResult r = SearchResult.of(List.of(s), 1, 1, 20, "api");
        assertNull(r.getSuggestedKeyword());
    }
}
