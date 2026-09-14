package com.vibemusic.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.dto.RecommendResult;
import com.vibemusic.dto.SearchResult;
import com.vibemusic.dto.SongDTO;
import com.vibemusic.entity.PlayHistory;
import com.vibemusic.entity.Song;
import com.vibemusic.mapper.PlayHistoryMapper;
import com.vibemusic.mapper.SongMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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
    private ThreadPoolTaskExecutor searchExecutor;
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
        searchExecutor = new ThreadPoolTaskExecutor();
        searchExecutor.setCorePoolSize(4);
        searchExecutor.setMaxPoolSize(4);
        searchExecutor.setQueueCapacity(20);
        searchExecutor.setThreadNamePrefix("test-recommend-");
        searchExecutor.setDaemon(true);
        searchExecutor.initialize();
        recommendService = new RecommendService(playHistoryMapper, songMapper,
                songSearchService, storageService, stringRedisTemplate, objectMapper, searchExecutor);
    }

    @AfterEach
    void tearDown() {
        searchExecutor.shutdown();
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
            when(playHistoryMapper.selectPage(any(), any(LambdaQueryWrapper.class)))
                    .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<PlayHistory>()
                            .setRecords(List.of(history)));

            List<SongDTO> randomBase = List.of(createSong("r1", "基础随机", "歌手A", "netease"));
            when(songSearchService.getRandomSongs(4)).thenReturn(randomBase);
            when(songSearchService.getRandomSongs(anyInt())).thenAnswer(invocation -> {
                int n = invocation.getArgument(0);
                return n == 4 ? randomBase : List.of();
            });

            List<SongDTO> artistSongs = List.of(
                    createSong("a1", "周式情歌", "周杰伦", "netease"));
            when(songSearchService.search(eq("周杰伦"), eq(1), eq(10), any()))
                    .thenReturn(com.vibemusic.dto.SearchResult.of(artistSongs, 1, 1, 10, "api"));

            when(songMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

            RecommendResult result = recommendService.getPersonalized(1L, "device-123");

            // 有播放历史 + 歌手搜索有结果 → personal 类型
            assertNotNull(result);
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

            // 污染缓存现在会检测后删除并触发重算 (2026-07-06 更新)
            RecommendResult result = recommendService.getPersonalized(1L, "device-123");

            verify(stringRedisTemplate, atLeastOnce()).delete("recommend:v3:user:1");
            assertNotNull(result);
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

    @Nested @DisplayName("Top歌手并行搜索")
    class ParallelFanoutTest {

        private PlayHistory historyOf(String sourceId, String artist) {
            PlayHistory h = new PlayHistory();
            h.setUserId(1L);
            h.setSourceId(sourceId);
            h.setSongName("歌-" + sourceId);
            h.setArtist(artist);
            h.setPlayedAt(LocalDateTime.now());
            return h;
        }

        @Test @DisplayName("三歌手搜索并行执行并受总deadline约束")
        void shouldBoundFanoutByDeadline() {
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn(null);
            when(playHistoryMapper.selectPage(any(), any(LambdaQueryWrapper.class)))
                    .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<PlayHistory>()
                            .setRecords(List.of(
                                    historyOf("h1", "歌手A"),
                                    historyOf("h2", "歌手B"),
                                    historyOf("h3", "歌手C"))));
            List<SongDTO> base = List.of(
                    createSong("r1", "基础1", "路人甲", "netease"),
                    createSong("r2", "基础2", "路人乙", "qq"),
                    createSong("r3", "基础3", "路人丙", "migu"),
                    createSong("r4", "基础4", "路人丁", "netease"));
            when(songSearchService.getRandomSongs(anyInt())).thenReturn(base);
            // 三个歌手搜索全部阻塞 20s：串行需 60s，并行+5s deadline 应快速返回
            when(songSearchService.search(anyString(), eq(1), eq(10), isNull())).thenAnswer(inv -> {
                Thread.sleep(20000);
                return SearchResult.of(List.of(), 0, 1, 10, "api");
            });
            when(songMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

            long start = System.currentTimeMillis();
            RecommendResult result = recommendService.getPersonalized(1L, "device-123");
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(elapsed < 15000, "并行 fan-out 应受总 deadline 约束，实际耗时=" + elapsed + "ms");
            verify(songSearchService, times(3)).search(anyString(), eq(1), eq(10), isNull());
            assertNotNull(result);
            assertEquals("personalized", result.getType());
            assertFalse(result.getSongs().isEmpty());
        }
    }

    @Nested @DisplayName("故障期缓存降级")
    class OutageDegradeTest {

        @Test @DisplayName("QQ熔断期污染缓存不删除直接返回旧缓存")
        void shouldServeStaleDuringOutage() throws Exception {
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
            RecommendResult polluted = RecommendResult.builder()
                    .songs(List.of(
                            createSong("p1","a","歌手","netease"), createSong("p2","b","歌手","netease"),
                            createSong("p3","c","歌手","netease"), createSong("p4","d","歌手","netease"),
                            createSong("p5","e","歌手","netease"), createSong("p6","f","歌手","netease"),
                            createSong("p7","g","歌手","netease"), createSong("p8","h","歌手","netease")))
                    .greeting("污染").type("random").build();
            when(valueOps.get("recommend:v3:user:1"))
                    .thenReturn(objectMapper.writeValueAsString(polluted));
            when(songSearchService.shouldSkipQq()).thenReturn(true);

            RecommendResult result = recommendService.getPersonalized(1L, "device-123");

            verify(stringRedisTemplate, never()).delete(anyString());
            verify(songSearchService, never()).getRandomSongs(anyInt());
            assertEquals("污染", result.getGreeting());
            assertEquals(8, result.getSongs().size());
        }

        @Test @DisplayName("全咪咕缓存同样判定为污染并重算")
        void shouldDetectMiguPollution() throws Exception {
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
            RecommendResult polluted = RecommendResult.builder()
                    .songs(List.of(
                            createSong("m1","a","歌手","migu"), createSong("m2","b","歌手","migu"),
                            createSong("m3","c","歌手","migu"), createSong("m4","d","歌手","migu"),
                            createSong("m5","e","歌手","migu"), createSong("m6","f","歌手","migu"),
                            createSong("m7","g","歌手","migu"), createSong("m8","h","歌手","migu")))
                    .greeting("污染").type("random").build();
            when(valueOps.get("recommend:v3:user:1"))
                    .thenReturn(objectMapper.writeValueAsString(polluted));
            when(songSearchService.shouldSkipQq()).thenReturn(false);

            RecommendResult result = recommendService.getPersonalized(1L, "device-123");

            verify(stringRedisTemplate, atLeastOnce()).delete("recommend:v3:user:1");
            assertNotNull(result);
        }
    }

    @Nested @DisplayName("deviceId 清洗")
    class DeviceIdSanitizeTest {

        @Test @DisplayName("非法字符的deviceId清洗后拼Redis键")
        void shouldSanitizeDeviceIdForKey() {
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn(null);
            when(songSearchService.getRandomSongs(8)).thenReturn(List.of());
            when(songMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

            recommendService.getPersonalized(null, "../../evil key!!!");

            verify(valueOps).get("recommend:v3:guest:evilkey");
        }

        @Test @DisplayName("超长deviceId截断至64字符")
        void shouldTruncateLongDeviceId() {
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn(null);
            when(songSearchService.getRandomSongs(8)).thenReturn(List.of());
            when(songMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

            String longId = "a".repeat(100);
            recommendService.getPersonalized(null, longId);

            verify(valueOps).get("recommend:v3:guest:" + "a".repeat(64));
        }

        @Test @DisplayName("空deviceId回退anon键")
        void shouldFallbackToAnon() {
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn(null);
            when(songSearchService.getRandomSongs(8)).thenReturn(List.of());
            when(songMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

            recommendService.getPersonalized(null, null);

            verify(valueOps).get("recommend:v3:guest:anon");
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
