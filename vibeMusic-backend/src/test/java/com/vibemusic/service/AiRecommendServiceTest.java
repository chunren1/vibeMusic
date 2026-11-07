package com.vibemusic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.common.exception.BusinessException;
import com.vibemusic.controller.AssistantClient;
import com.vibemusic.dto.RecommendResult;
import com.vibemusic.dto.SearchResult;
import com.vibemusic.dto.SongDTO;
import com.vibemusic.entity.PlayHistory;
import com.vibemusic.entity.UserFavorite;
import com.vibemusic.mapper.PlayHistoryMapper;
import com.vibemusic.mapper.UserFavoriteMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiRecommendService 测试（纯 Mockito，不加载 Spring）。
 *
 * <p>覆盖：缓存命中、每日配额、LLM 异常降级、围栏 JSON 容错、幻觉过滤、
 * 命中阈值、匹配规则真值表、空历史、最近已听过滤。
 */
@DisplayName("AiRecommendService AI 推荐测试")
class AiRecommendServiceTest {

    private static final Long USER_ID = 1L;

    private PlayHistoryMapper playHistoryMapper;
    private UserFavoriteMapper userFavoriteMapper;
    private SongSearchService songSearchService;
    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOps;
    private RateLimitService rateLimitService;
    private ThreadPoolTaskExecutor searchExecutor;
    private AssistantClient assistantClient;
    private AiRecommendService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        playHistoryMapper = mock(PlayHistoryMapper.class);
        userFavoriteMapper = mock(UserFavoriteMapper.class);
        songSearchService = mock(SongSearchService.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        rateLimitService = mock(RateLimitService.class);
        assistantClient = mock(AssistantClient.class);

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(rateLimitService.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
        when(playHistoryMapper.selectPage(any(), any()))
                .thenReturn(page(List.of(history("h0", "晴天", "周杰伦"))));
        when(userFavoriteMapper.selectPage(any(), any())).thenReturn(page(List.of()));

        searchExecutor = new ThreadPoolTaskExecutor();
        searchExecutor.setCorePoolSize(4);
        searchExecutor.setMaxPoolSize(4);
        searchExecutor.setQueueCapacity(20);
        searchExecutor.setThreadNamePrefix("test-ai-recommend-");
        searchExecutor.setDaemon(true);
        searchExecutor.initialize();

        service = new AiRecommendService(playHistoryMapper, userFavoriteMapper, songSearchService,
                stringRedisTemplate, objectMapper, rateLimitService, searchExecutor, assistantClient,
                true, 3, 12, 6, 20, 10);
    }

    @AfterEach
    void tearDown() {
        searchExecutor.shutdown();
    }

    // ============================ 缓存与配额 ============================

    @Nested
    @DisplayName("缓存与每日配额")
    class CacheAndQuotaTest {

        @Test
        @DisplayName("缓存命中时不调用 LLM，也不消耗配额")
        void shouldReturnCachedWithoutCallingLlm() throws Exception {
            RecommendResult cached = RecommendResult.builder()
                    .songs(List.of(song("c1", "缓存歌", "缓存歌手"), song("c2", "缓存歌2", "缓存歌手2")))
                    .reason("缓存理由")
                    .type("ai")
                    .build();
            when(valueOps.get("recommend:ai:v1:user:" + USER_ID))
                    .thenReturn(objectMapper.writeValueAsString(cached));

            RecommendResult result = service.getAiRecommend(USER_ID, false);

            assertEquals(2, result.getSongs().size());
            assertEquals("缓存理由", result.getReason());
            verify(assistantClient, never()).callLLM(anyList(), any(AiCallBudget.class));
            verify(rateLimitService, never()).tryAcquire(anyString(), anyInt(), any(Duration.class));
        }

        @Test
        @DisplayName("refresh=true 且配额用完应抛 429")
        void shouldThrow429WhenRefreshAndQuotaExhausted() {
            when(rateLimitService.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(false);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.getAiRecommend(USER_ID, true));

            assertEquals(429, ex.getCode());
            assertTrue(ex.getMessage().contains("3 次/天"));
            verify(assistantClient, never()).callLLM(anyList(), any(AiCallBudget.class));
        }

        @Test
        @DisplayName("refresh=false 且配额用完应返回空，不报错")
        void shouldReturnEmptyWhenQuotaExhaustedWithoutRefresh() {
            when(valueOps.get(anyString())).thenReturn(null);
            when(rateLimitService.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(false);

            RecommendResult result = service.getAiRecommend(USER_ID, false);

            assertTrue(result.getSongs().isEmpty());
            verify(assistantClient, never()).callLLM(anyList(), any(AiCallBudget.class));
        }

        @Test
        @DisplayName("未登录应返回空且不查库、不调 LLM")
        void shouldReturnEmptyForAnonymous() {
            RecommendResult result = service.getAiRecommend(null, true);

            assertTrue(result.getSongs().isEmpty());
            assertEquals("", result.getReason());
            verify(assistantClient, never()).callLLM(anyList(), any(AiCallBudget.class));
            verify(playHistoryMapper, never()).selectPage(any(), any());
        }

        @Test
        @DisplayName("配额键按用户+日期隔离")
        void shouldUseDailyQuotaKey() {
            stubLlm(llmJson("理由", 0, 6));
            for (int i = 0; i < 6; i++) stubSearchHit(i);

            service.getAiRecommend(USER_ID, true);

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimitService).tryAcquire(keyCaptor.capture(), eq(3), eq(Duration.ofDays(1)));
            assertTrue(keyCaptor.getValue().matches("recommend:ai:user:1:\\d{4}-\\d{2}-\\d{2}"));
        }
    }

    // ============================ 生成与降级 ============================

    @Nested
    @DisplayName("生成与降级")
    class GenerateTest {

        @Test
        @DisplayName("LLM 抛异常应返回空且不写缓存")
        void shouldDegradeWhenLlmThrows() {
            when(assistantClient.callLLM(anyList(), any(AiCallBudget.class)))
                    .thenThrow(new RuntimeException("upstream timeout"));

            RecommendResult result = service.getAiRecommend(USER_ID, false);

            assertTrue(result.getSongs().isEmpty());
            verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("坏 JSON 应降级为空且不写缓存")
        void shouldDegradeOnBrokenJson() {
            stubLlm("抱歉，我暂时无法生成推荐。");

            RecommendResult result = service.getAiRecommend(USER_ID, false);

            assertTrue(result.getSongs().isEmpty());
            verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("```json 围栏 + 前后废话仍能解析")
        void shouldParseFencedJsonWithChatter() {
            stubLlm("好的，以下是推荐：\n```json\n" + llmJson("适合深夜", 0, 6) + "\n```\n希望你喜欢");
            for (int i = 0; i < 6; i++) stubSearchHit(i);

            RecommendResult result = service.getAiRecommend(USER_ID, false);

            assertEquals(6, result.getSongs().size());
            assertEquals("适合深夜", result.getReason());
            assertEquals("ai", result.getType());
        }

        @Test
        @DisplayName("搜索结果为空（幻觉歌）应被丢弃")
        void shouldDropHallucinatedSongs() {
            stubLlm(llmJson("理由", 0, 7));
            for (int i = 0; i < 7; i++) {
                if (i == 6) {
                    when(songSearchService.search("歌曲6 歌手6", 1, 3))
                            .thenReturn(SearchResult.of(List.of(), 0, 1, 3, "api"));
                } else {
                    stubSearchHit(i);
                }
            }

            RecommendResult result = service.getAiRecommend(USER_ID, false);

            assertEquals(6, result.getSongs().size());
            assertFalse(result.getSongs().stream().anyMatch(s -> "s6".equals(s.getSourceId())));
        }

        @Test
        @DisplayName("命中不足 minResolved 应返回空且不写缓存")
        void shouldNotCacheWhenHitsBelowThreshold() {
            stubLlm(llmJson("理由", 0, 12));
            for (int i = 0; i < 5; i++) stubSearchHit(i);
            for (int i = 5; i < 12; i++) {
                when(songSearchService.search("歌曲" + i + " 歌手" + i, 1, 3))
                        .thenReturn(SearchResult.of(List.of(), 0, 1, 3, "api"));
            }

            RecommendResult result = service.getAiRecommend(USER_ID, false);

            assertTrue(result.getSongs().isEmpty());
            verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("命中达到阈值应写 24h 缓存并返回正确条数")
        void shouldCacheWhenHitsReachThreshold() {
            stubLlm(llmJson("理由", 0, 12));
            for (int i = 0; i < 12; i++) stubSearchHit(i);

            RecommendResult result = service.getAiRecommend(USER_ID, false);

            assertEquals(12, result.getSongs().size());
            ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
            verify(valueOps).set(eq("recommend:ai:v1:user:" + USER_ID), anyString(), ttlCaptor.capture());
            assertEquals(Duration.ofHours(24), ttlCaptor.getValue());
        }

        @Test
        @DisplayName("播放历史为空应返回空且不调 LLM")
        void shouldReturnEmptyWhenHistoryMissing() {
            when(playHistoryMapper.selectPage(any(), any())).thenReturn(page(List.of()));

            RecommendResult result = service.getAiRecommend(USER_ID, false);

            assertTrue(result.getSongs().isEmpty());
            verify(assistantClient, never()).callLLM(anyList(), any(AiCallBudget.class));
        }

        @Test
        @DisplayName("最近已听过的 sourceId 应被过滤掉")
        void shouldFilterRecentlyPlayedSourceIds() {
            when(playHistoryMapper.selectPage(any(), any()))
                    .thenReturn(page(List.of(history("s3", "歌曲3", "歌手3"))));
            stubLlm(llmJson("理由", 0, 7));
            for (int i = 0; i < 7; i++) stubSearchHit(i);

            RecommendResult result = service.getAiRecommend(USER_ID, false);

            assertEquals(6, result.getSongs().size());
            assertFalse(result.getSongs().stream().anyMatch(s -> "s3".equals(s.getSourceId())));
        }
    }

    // ============================ 匹配规则真值表 ============================

    @Nested
    @DisplayName("匹配规则（纯函数）")
    class MatchingTest {

        @Test
        @DisplayName("歌名归一化后完全相等 → 命中")
        void shouldMatchExactName() {
            assertTrue(AiRecommendVerifier.matches("晴天", "周杰伦", song("1", "晴天", "周杰伦")));
        }

        @Test
        @DisplayName("一方包含另一方（查询名长度≥3）→ 命中")
        void shouldMatchContainedName() {
            assertTrue(AiRecommendVerifier.matches("起风了", "买辣椒也用券",
                    song("1", "起风了 (Live)", "买辣椒也用券")));
            assertTrue(AiRecommendVerifier.matches("起风了 (Live)", "买辣椒也用券",
                    song("1", "起风了", "买辣椒也用券")));
        }

        @Test
        @DisplayName("查询名短于 3 字时不做包含匹配")
        void shouldNotMatchShortQueryByContainment() {
            assertFalse(AiRecommendVerifier.matches("晴", "周杰伦", song("1", "晴天", "周杰伦")));
        }

        @Test
        @DisplayName("歌手不匹配 → 丢弃")
        void shouldRejectArtistMismatch() {
            assertFalse(AiRecommendVerifier.matches("晴天", "周杰伦", song("1", "晴天", "五月天")));
        }

        @Test
        @DisplayName("标点/全角括号/空白差异不影响匹配")
        void shouldIgnorePunctuationAndFullWidth() {
            assertTrue(AiRecommendVerifier.matches("七里香（Live）", "周杰伦",
                    song("1", "七里香 Live", "周杰伦")));
            assertTrue(AiRecommendVerifier.matches("Hello，World", "Adele",
                    song("1", "hello world", "Adele")));
        }

        @Test
        @DisplayName("翻唱/搬运号（歌手名只是长后缀）→ 丢弃")
        void shouldRejectCoverAccountArtist() {
            // 线上实测：LLM 提名「小幸运 - 田馥甄」，搜索结果里只有翻唱号「田馥甄的小迷妹」
            assertFalse(AiRecommendVerifier.matches("小幸运", "田馥甄",
                    song("1", "小幸运", "田馥甄的小迷妹")));
            assertFalse(AiRecommendVerifier.matches("晴天", "周杰伦",
                    song("1", "晴天", "周杰伦粉丝团")));
        }

        @Test
        @DisplayName("多歌手用 / & feat 分隔时，任一分量相等 → 命中")
        void shouldMatchMultiArtistByToken() {
            assertTrue(AiRecommendVerifier.matches("晴天", "周杰伦",
                    song("1", "晴天", "周杰伦 / 温岚")));
            assertTrue(AiRecommendVerifier.matches("Stay", "Justin Bieber",
                    song("1", "Stay", "The Kid LAROI feat. Justin Bieber")));
            assertTrue(AiRecommendVerifier.matches("珊瑚海", "周杰伦、梁心颐",
                    song("1", "珊瑚海", "周杰伦&梁心颐")));
        }

        @Test
        @DisplayName("同一歌手的写法差异（G.E.M. 前缀）仍命中")
        void shouldMatchArtistNameVariant() {
            assertTrue(AiRecommendVerifier.matches("泡沫", "邓紫棋",
                    song("1", "泡沫", "G.E.M.邓紫棋")));
        }

        @Test
        @DisplayName("任一方歌手为空 → 放行")
        void shouldAllowEmptyArtist() {
            assertTrue(AiRecommendVerifier.matches("晴天", "", song("1", "晴天", "周杰伦")));
            assertTrue(AiRecommendVerifier.matches("晴天", "周杰伦", song("1", "晴天", null)));
            assertTrue(AiRecommendVerifier.matches("晴天", null, song("1", "晴天", "周杰伦")));
        }

        @Test
        @DisplayName("多个候选取 finalScore 最高者")
        void shouldPickHighestFinalScore() {
            SongDTO low = song("1", "晴天", "周杰伦");
            low.setFinalScore(1.0);
            SongDTO high = song("2", "晴天", "周杰伦");
            high.setFinalScore(9.5);
            SongDTO nullScore = song("3", "晴天", "周杰伦");

            SongDTO best = AiRecommendVerifier.pickBestMatch("晴天", "周杰伦", List.of(low, nullScore, high));

            assertNotNull(best);
            assertEquals("2", best.getSourceId());
        }

        @Test
        @DisplayName("没有匹配项 → null")
        void shouldReturnNullWhenNothingMatches() {
            assertNull(AiRecommendVerifier.pickBestMatch("完全不存在的歌", "无名氏",
                    List.of(song("1", "晴天", "周杰伦"))));
        }
    }

    // ============================ helpers ============================

    private void stubLlm(String content) {
        when(assistantClient.callLLM(anyList(), any(AiCallBudget.class)))
                .thenReturn(java.util.Map.of("message",
                        java.util.Map.of("role", "assistant", "content", content)));
    }

    private void stubSearchHit(int i) {
        SongDTO song = song("s" + i, "歌曲" + i, "歌手" + i);
        song.setFinalScore(10.0 - i);
        when(songSearchService.search("歌曲" + i + " 歌手" + i, 1, 3))
                .thenReturn(SearchResult.of(List.of(song), 1, 1, 3, "api"));
    }

    private static String llmJson(String reason, int from, int count) {
        StringBuilder sb = new StringBuilder("{\"reason\":\"").append(reason).append("\",\"songs\":[");
        for (int i = from; i < from + count; i++) {
            if (i > from) sb.append(',');
            sb.append("{\"name\":\"歌曲").append(i).append("\",\"artist\":\"歌手").append(i).append("\"}");
        }
        return sb.append("]}").toString();
    }

    private static SongDTO song(String sourceId, String name, String artist) {
        SongDTO song = new SongDTO();
        song.setSourceId(sourceId);
        song.setName(name);
        song.setArtist(artist);
        song.setPlatform("netease");
        return song;
    }

    private static PlayHistory history(String sourceId, String songName, String artist) {
        PlayHistory h = new PlayHistory();
        h.setSourceId(sourceId);
        h.setSongName(songName);
        h.setArtist(artist);
        h.setPlayedAt(LocalDateTime.now());
        return h;
    }

    private static <T> com.baomidou.mybatisplus.extension.plugins.pagination.Page<T> page(List<T> records) {
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<T>(1, 20);
        page.setRecords(new ArrayList<>(records));
        return page;
    }
}
