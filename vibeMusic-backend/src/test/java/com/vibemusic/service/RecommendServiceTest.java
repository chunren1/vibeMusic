package com.vibemusic.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.dto.RecommendResult;
import com.vibemusic.dto.SongDTO;
import com.vibemusic.entity.PlayHistory;
import com.vibemusic.entity.Song;
import com.vibemusic.mapper.PlayHistoryMapper;
import com.vibemusic.mapper.SongMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RecommendService 推荐引擎 v3 测试
 * <p>
 * 纯 Mockito 单元测试，Mock Redis/DB/搜索依赖。
 * 覆盖：游客推荐、个性化推荐、缓存命中/污染、异常降级。
 */
@DisplayName("RecommendService 推荐引擎 v3 测试")
class RecommendServiceTest {

    private PlayHistoryMapper playHistoryMapper;
    private SongMapper songMapper;
    private SongSearchService songSearchService;
    private StorageService storageService;
    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOps;
    private ObjectMapper objectMapper;
    private RecommendService recommendService;

    @BeforeEach
    void setUp() {
        playHistoryMapper = mock(PlayHistoryMapper.class);
        songMapper = mock(SongMapper.class);
        songSearchService = mock(SongSearchService.class);
        storageService = mock(StorageService.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        objectMapper = new ObjectMapper();
        recommendService = new RecommendService(playHistoryMapper, songMapper,
                songSearchService, storageService, stringRedisTemplate, objectMapper);
    }

    private SongDTO createSong(String sourceId, String name, String artist, String platform) {
        SongDTO s = new SongDTO();
        s.setSourceId(sourceId);
        s.setName(name);
        s.setArtist(artist);
        s.setPlatform(platform);
        s.setDuration(240);
        return s;
    }

    @Nested @DisplayName("游客推荐（未登录）")
    class GuestRecommendationTest {

        @Test @DisplayName("未登录用户应返回随机推荐")
        void shouldReturnRandomForGuest() {
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn(null);
            List<SongDTO> randomSongs = List.of(
                    createSong("r1", "随机1", "歌手A", "netease"),
                    createSong("r2", "随机2", "歌手B", "qq"));
            when(songSearchService.getRandomSongs(8)).thenReturn(randomSongs);
            when(songMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

            RecommendResult result = recommendService.getPersonalized(null, "device-123");

            assertEquals("random", result.getType());
            assertEquals(2, result.getSongs().size());
        }

        @Test @DisplayName("游客推荐失败应返回空列表")
        void shouldReturnEmptyOnGuestFailure() {
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn(null);
            when(songSearchService.getRandomSongs(8)).thenThrow(new RuntimeException("API超时"));

            RecommendResult result = recommendService.getPersonalized(null, "device-123");

            assertTrue(result.getSongs().isEmpty());
            assertEquals("推荐服务暂时不可用", result.getGreeting());
        }
    }

    @Nested @DisplayName("个性化推荐（已登录）")
    class PersonalizedRecommendationTest {

        @Test @DisplayName("有播放历史应基于歌手权重推荐")
        void shouldBuildFromHistory() {
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn(null);

            PlayHistory history = new PlayHistory();
            history.setUserId(1L);
            history.setSourceId("h1");
            history.setSongName("晴天");
            history.setArtist("周杰伦");
            history.setPlayedAt(LocalDateTime.now());
            when(playHistoryMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(history));

            List<SongDTO> randomBase = List.of(createSong("r1", "基础随机", "歌手A", "netease"));
            when(songSearchService.getRandomSongs(4)).thenReturn(randomBase);
            when(songSearchService.getRandomSongs(anyInt())).thenAnswer(invocation -> {
                int n = invocation.getArgument(0);
                return n == 4 ? randomBase : List.of();
            });

            List<SongDTO> artistSongs = List.of(
                    createSong("a1", "周式情歌", "周杰伦", "netease"));
            when(songSearchService.search(eq("周杰伦"), eq(1), eq(10), isNull()))
                    .thenReturn(com.vibemusic.dto.SearchResult.of(artistSongs, 1, 1, 10, "api"));

            when(songMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

            RecommendResult result = recommendService.getPersonalized(1L, "device-123");

            assertEquals("personalized", result.getType());
            assertFalse(result.getSongs().isEmpty());
        }
    }

    @Nested @DisplayName("缓存机制")
    class CacheTest {

        @Test @DisplayName("Redis 缓存命中应直接返回")
        void shouldReturnCachedResult() throws Exception {
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
            RecommendResult cached = RecommendResult.builder()
                    .songs(List.of(createSong("c1", "缓存歌", "歌手", "netease")))
                    .greeting("缓存欢迎语").type("personalized").build();
            when(valueOps.get("recommend:v3:user:1"))
                    .thenReturn(objectMapper.writeValueAsString(cached));

            RecommendResult result = recommendService.getPersonalized(1L, "device-123");

            assertEquals("缓存欢迎语", result.getGreeting());
            verify(songSearchService, never()).getRandomSongs(anyInt());
        }

        @Test @DisplayName("refresh=true 应跳过缓存")
        void shouldSkipCacheWhenRefresh() {
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn("{\"songs\":[],\"greeting\":\"旧缓存\",\"type\":\"random\"}");
            when(songSearchService.getRandomSongs(8)).thenReturn(List.of());
            when(songMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

            RecommendResult result = recommendService.getPersonalized(1L, "device-123", true);

            verify(songSearchService, atLeastOnce()).getRandomSongs(anyInt());
        }
    }

    @Nested @DisplayName("缓存污染检测")
    class CachePollutionTest {

        @Test @DisplayName("单平台缓存仅告警不清空")
        void shouldDetectAndCleanPollutedCache() throws Exception {
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
            // 模拟缓存中 8 首歌全来自 netease
            RecommendResult polluted = RecommendResult.builder()
                    .songs(List.of(
                            createSong("p1","a","歌手","netease"), createSong("p2","b","歌手","netease"),
                            createSong("p3","c","歌手","netease"), createSong("p4","d","歌手","netease"),
                            createSong("p5","e","歌手","netease"), createSong("p6","f","歌手","netease"),
                            createSong("p7","g","歌手","netease"), createSong("p8","h","歌手","netease")))
                    .greeting("污染").type("random").build();
            when(valueOps.get("recommend:v3:user:1"))
                    .thenReturn(objectMapper.writeValueAsString(polluted));

            // 策略改为仅告警不清空，应直接返回缓存结果
            RecommendResult result = recommendService.getPersonalized(1L, "device-123");

            verify(stringRedisTemplate, never()).delete("recommend:v3:user:1");
            assertNotNull(result);
            assertEquals("污染", result.getGreeting());
        }

        @Test @DisplayName("4 首以下不应触发污染检测")
        void shouldNotDetectForSmallResults() throws Exception {
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
            RecommendResult small = RecommendResult.builder()
                    .songs(List.of(
                            createSong("s1","a","歌手","netease"),
                            createSong("s2","b","歌手","netease")))
                    .greeting("小结果").type("random").build();
            when(valueOps.get("recommend:v3:user:1"))
                    .thenReturn(objectMapper.writeValueAsString(small));

            RecommendResult result = recommendService.getPersonalized(1L, "device-123");

            verify(stringRedisTemplate, never()).delete(anyString());
            assertEquals("小结果", result.getGreeting());
        }
    }

    @Nested @DisplayName("evictUserCache 缓存清理")
    class EvictTest {

        @Test @DisplayName("删除用户推荐缓存")
        void shouldEvictUserCache() {
            recommendService.evictUserCache(1L);

            verify(stringRedisTemplate).delete("recommend:v3:user:1");
        }
    }
}
