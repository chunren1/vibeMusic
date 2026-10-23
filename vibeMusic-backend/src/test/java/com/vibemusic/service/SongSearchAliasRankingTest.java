package com.vibemusic.service;

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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 搜索译名（别名）相关性与平台内去重回归测试。
 *
 * <p>背景 bug（2026-10）：用户搜「圣诞快乐劳伦斯先生」只出杨振龙等中文翻唱，
 * 坂本龙一原版（曲名英文 {@code Merry Christmas Mr. Lawrence}）整条消失。两个独立成因：
 * <ol>
 *   <li>网关把网易的 {@code tns}（中文译名）丢掉了，后端无法把中文查询对上英文曲名；</li>
 *   <li>netease 分支对同键重复项是「后者覆盖前者」，6 条同名原版里名次最低的那条
 *       覆盖掉了第 1 名，分数从 2.0 掉到 0.05，被挤出结果集。</li>
 * </ol>
 */
@DisplayName("搜索译名（别名）相关性")
class SongSearchAliasRankingTest {

    private static final String KEYWORD = "圣诞快乐劳伦斯先生";
    private static final String ORIGINAL_NAME = "Merry Christmas Mr. Lawrence";
    private static final String ORIGINAL_ARTIST = "坂本龍一";

    private SongMapper songMapper;
    private NeteaseApiService neteaseApiService;
    private SongCacheService cacheService;
    private StringRedisTemplate redisTemplate;
    private ThreadPoolTaskExecutor searchExec;
    private ThreadPoolTaskExecutor warmExec;
    private SongSearchService service;

    @BeforeEach
    void setUp() {
        songMapper = mock(SongMapper.class);
        neteaseApiService = mock(NeteaseApiService.class);
        cacheService = mock(SongCacheService.class);
        redisTemplate = mock(StringRedisTemplate.class);
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
                new SimpleMeterRegistry(), searchExec, warmExec, redisTemplate);
        service.initMetrics();
        when(cacheService.getSearchCache(anyString())).thenReturn(null);
        lenient().when(cacheService.tryLockResult(anyString()))
                .thenReturn(SongCacheService.LockResult.acquired("default-lock"));
    }

    @AfterEach
    void tearDown() {
        if (searchExec != null) searchExec.shutdown();
        if (warmExec != null) warmExec.shutdown();
    }

    private void stubEmptyOtherPlatforms() {
        when(neteaseApiService.searchQQ(anyString(), anyInt())).thenReturn(Map.of("data", List.of()));
        when(neteaseApiService.searchMigu(anyString(), anyInt())).thenReturn(Map.of("data", List.of()));
        when(neteaseApiService.searchKugou(anyString(), anyInt())).thenReturn(Map.of("data", List.of()));
        when(neteaseApiService.searchBili(anyString(), anyInt())).thenReturn(Map.of("data", List.of()));
    }

    private Map<String, Object> song(String id, String name, String artist, int durationMs, List<String> alias) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("artists", artist);
        m.put("album", "album-" + id);
        m.put("cover", "https://example.com/" + id + ".jpg");
        m.put("duration", durationMs);
        if (alias != null) m.put("alias", alias);
        return m;
    }

    private static SongDTO dto(String name, String artist, double score, List<String> alias) {
        SongDTO d = new SongDTO();
        d.setName(name);
        d.setArtist(artist);
        d.setDuration(240);
        d.setFinalScore(score);
        d.setAlias(alias);
        return d;
    }

    @Nested
    @DisplayName("译名参与相关性打分")
    class AliasBonusTest {

        @Test
        @DisplayName("中文查询命中英文曲名的译名时，分数应压过同名中文翻唱")
        void aliasExactBeatChineseCover() {
            SongDTO original = dto(ORIGINAL_NAME, ORIGINAL_ARTIST, 2.0, List.of("圣诞快乐，劳伦斯先生"));
            SongDTO cover = dto("圣诞快乐劳伦斯先生", "杨振龙", 1.4, null);
            List<SongDTO> merged = new ArrayList<>(List.of(original, cover));

            SongRanking.applyRelevanceBonus(merged, KEYWORD);

            // 原版：2.0 + 别名精确 2.0 = 4.0；翻唱：1.4 + 歌名精确 2.0 = 3.4
            assertEquals(4.0, original.getFinalScore(), 1e-9);
            assertEquals(3.4, cover.getFinalScore(), 1e-9);
            assertTrue(original.getFinalScore() > cover.getFinalScore());
        }

        @Test
        @DisplayName("译名只部分命中时应得包含分 +1.0，而非精确分")
        void aliasPartialMatchGivesContainsBonus() {
            SongDTO original = dto(ORIGINAL_NAME, ORIGINAL_ARTIST, 2.0, List.of("劳伦斯先生"));
            List<SongDTO> merged = new ArrayList<>(List.of(original));

            SongRanking.applyRelevanceBonus(merged, KEYWORD);

            assertEquals(1.0, original.getFinalScore() - 2.0, 1e-9);
        }

        @Test
        @DisplayName("无别名/空别名时打分与旧行为完全一致")
        void nullAliasKeepsLegacyScoring() {
            SongDTO noAlias = dto(ORIGINAL_NAME, ORIGINAL_ARTIST, 2.0, null);
            SongDTO emptyAlias = dto(ORIGINAL_NAME, ORIGINAL_ARTIST, 2.0, List.of());
            List<SongDTO> merged = new ArrayList<>(List.of(noAlias, emptyAlias));

            SongRanking.applyRelevanceBonus(merged, KEYWORD);

            assertEquals(2.0, noAlias.getFinalScore(), 1e-9);
            assertEquals(2.0, emptyAlias.getFinalScore(), 1e-9);
        }
    }

    @Nested
    @DisplayName("网关译名字段解析")
    class ParseAliasTest {

        @Test
        @DisplayName("数组形态去空去重，单字符串也接受")
        void parseAliasTolerant() {
            assertEquals(List.of("圣诞快乐，劳伦斯先生"), SongSearchService.parseAlias(List.of(" 圣诞快乐，劳伦斯先生 ", "")));
            assertEquals(List.of("A"), SongSearchService.parseAlias(" A "));
            assertTrue(SongSearchService.parseAlias(null).isEmpty());
            assertTrue(SongSearchService.parseAlias(42).isEmpty());
            assertEquals(List.of("X"), SongSearchService.parseAlias(List.of("X", "X")));
        }
    }

    @Nested
    @DisplayName("平台内重复项不再覆盖高分条目")
    class DuplicateMergeTest {

        @Test
        @DisplayName("网易同名同歌手多条时，第 1 名不得被后面低分条目覆盖")
        void neteaseDuplicateKeepsHighestScore() {
            stubEmptyOtherPlatforms();
            List<Map<String, Object>> list = new ArrayList<>();
            for (int i = 1; i <= 6; i++) {
                list.add(song("id-" + i, ORIGINAL_NAME, ORIGINAL_ARTIST, 290_000,
                        List.of("圣诞快乐，劳伦斯先生")));
            }
            when(neteaseApiService.searchNetease(eq(KEYWORD), anyInt())).thenReturn(Map.of("data", list));

            SearchResult result = service.search(KEYWORD, 1, 40);

            assertEquals(1, result.getList().size(), "同键重复项应只保留一条");
            SongDTO kept = result.getList().get(0);
            assertEquals("id-1", kept.getSourceId(), "应保留名次最高（分最高）的第 1 条");
            // 1.5(网易第1基准) + 0.5(网易头名) + 2.0(译名精确) = 4.0
            assertEquals(4.0, kept.getFinalScore(), 1e-9);
        }

        @Test
        @DisplayName("译名让英文曲名原版排在中文翻唱之前（端到端）")
        void originalWithAliasRanksAboveCover() {
            stubEmptyOtherPlatforms();
            List<Map<String, Object>> list = List.of(
                    song("28191836", ORIGINAL_NAME, ORIGINAL_ARTIST, 290_000,
                            List.of("圣诞快乐，劳伦斯先生")),
                    song("cover-1", "圣诞快乐劳伦斯先生", "杨振龙", 260_000, null));
            when(neteaseApiService.searchNetease(eq(KEYWORD), anyInt())).thenReturn(Map.of("data", list));

            SearchResult result = service.search(KEYWORD, 1, 40);

            assertNotNull(result.getList());
            assertTrue(result.getList().size() >= 2);
            assertEquals("28191836", result.getList().get(0).getSourceId());
            assertEquals("圣诞快乐劳伦斯先生", result.getList().get(1).getName());
        }
    }
}
