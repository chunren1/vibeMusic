package com.vibemusic.service;

import com.vibemusic.dto.SearchResult;
import com.vibemusic.dto.SongDTO;
import com.vibemusic.mapper.SongMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SongSearchService 搜索服务测试
 * <p>
 * 纯 Mockito 单元测试，Mock Redis/ES/musicapi 依赖，
 * 验证四级降级链：Redis → ES → API → 空结果兜底。
 */
@DisplayName("SongSearchService 搜索服务测试")
class SongSearchServiceTest {

    private SongMapper songMapper;
    private NeteaseApiService neteaseApiService;
    private SongCacheService cacheService;
    private ESSearchService esSearchService;
    private SongSearchService songSearchService;

    @BeforeEach
    void setUp() {
        songMapper = mock(SongMapper.class);
        neteaseApiService = mock(NeteaseApiService.class);
        cacheService = mock(SongCacheService.class);
        esSearchService = mock(ESSearchService.class);
        // 手动构造，手动调用 @PostConstruct
        songSearchService = new SongSearchService(songMapper, neteaseApiService,
                cacheService, esSearchService, new SimpleMeterRegistry());
        songSearchService.initMetrics();
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

        @Test @DisplayName("Redis 命中应直接返回缓存结果，不查 ES/API")
        void shouldReturnRedisCache() {
            List<SongDTO> cached = List.of(createSong("1", "晴天", "周杰伦"));
            when(cacheService.getSearchCache(eq("晴天:all"), eq(1))).thenReturn(cached);

            SearchResult result = songSearchService.search("晴天", 1, 20);

            assertEquals("redis", result.getSource());
            assertEquals(1, result.getList().size());
            verify(cacheService, never()).setSearchCache(anyString(), anyInt(), anyList(), anyBoolean());
            verify(esSearchService, never()).findByKeyword(anyString());
            verify(neteaseApiService, never()).searchNetease(anyString(), anyInt());
        }
    }

    @Nested @DisplayName("L2: ES 缓存命中")
    class EsHitTest {

        @Test @DisplayName("Redis 未命中 + ES 命中应返回 ES 结果并回写 Redis")
        void shouldReturnEsCacheAndBackfillRedis() {
            when(cacheService.getSearchCache(eq("七里香:all"), eq(1))).thenReturn(null);
            List<SongDTO> esResults = List.of(createSong("2", "七里香", "周杰伦"));
            when(esSearchService.findByKeyword("七里香")).thenReturn(esResults);

            SearchResult result = songSearchService.search("七里香", 1, 20);

            assertEquals("es", result.getSource());
            assertEquals(1, result.getList().size());
            verify(cacheService).setSearchCache(eq("七里香:all"), eq(1), eq(esResults), eq(true), eq(false));
        }
    }

    @Nested @DisplayName("L3: API 实时搜索")
    class ApiHitTest {

        @Test @DisplayName("Redis/ES 均未命中 + API 返回结果应聚合去重")
        void shouldSearchFromApiAndMerge() {
            when(cacheService.getSearchCache(anyString(), eq(1))).thenReturn(null);
            when(esSearchService.findByKeyword(anyString())).thenReturn(List.of());

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
            verify(cacheService).setSearchCache(anyString(), anyInt(), anyList(), eq(true), anyBoolean());
            verify(esSearchService).indexSearchResults(anyString(), anyList());
        }

        @Test @DisplayName("API 超时应降级返回空列表")
        void shouldReturnEmptyOnApiTimeout() {
            when(cacheService.getSearchCache(anyString(), eq(1))).thenReturn(null);
            when(esSearchService.findByKeyword(anyString())).thenReturn(List.of());
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
            when(cacheService.getSearchCache(eq("网易云歌:netease"), eq(1))).thenReturn(null);
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
            when(cacheService.getSearchCache(eq("QQ歌:qq"), eq(1))).thenReturn(null);
            when(neteaseApiService.searchQQ("QQ歌", 40))
                    .thenReturn(Map.of("data", List.of(
                            Map.of("id", "5", "name", "QQ歌曲", "artists", "歌手", "duration", 200000))));

            SearchResult result = songSearchService.search("QQ歌", 1, 20, "qq");

            assertEquals("api", result.getSource());
            verify(neteaseApiService, never()).searchNetease(anyString(), anyInt());
            verify(neteaseApiService, times(1)).searchQQ(anyString(), anyInt());
        }
    }

    @Nested @DisplayName("getRandomSongs 随机推荐")
    class RandomSongsTest {

        @Test @DisplayName("API 歌曲足够时随机打乱返回")
        void shouldShuffleApiResults() {
            when(cacheService.getSearchCache(eq("热歌:all"), eq(1))).thenReturn(null);
            when(esSearchService.findByKeyword("热歌")).thenReturn(List.of());

            var song = Map.of("id", "6", "name", "热歌", "artists", "歌手", "duration", 240000);
            when(neteaseApiService.searchNetease("热歌", 40)).thenReturn(Map.of("data", List.of(song)));
            when(neteaseApiService.searchQQ("热歌", 40)).thenReturn(Map.of("data", List.of()));

            List<SongDTO> result = songSearchService.getRandomSongs(1);

            assertFalse(result.isEmpty());
            assertEquals("热歌", result.get(0).getName());
        }
    }
}
