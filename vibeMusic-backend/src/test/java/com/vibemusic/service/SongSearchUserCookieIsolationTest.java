package com.vibemusic.service;

import com.vibemusic.dto.SearchResult;
import com.vibemusic.dto.SongDTO;
import com.vibemusic.entity.User;
import com.vibemusic.mapper.SongMapper;
import com.vibemusic.security.CustomUserDetails;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SongSearchService per-user Cookie 隔离测试（纯 Mockito）。
 *
 * <p>隔离策略（2026-11-11 调整）：per-user 结果按用户隔离缓存，键前缀 {@code u<id>:}。
 * 同一用户重复搜索命中自己的键（不再每次穿透 5 个上游），不同用户与匿名用户互不可见；
 * 取不到用户标识时退回「不读不写缓存」，保证任何情况下都不串号。
 */
@TestPropertySource(properties = "COOKIE_ENC_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@DisplayName("SongSearchService per-user Cookie 隔离测试")
class SongSearchUserCookieIsolationTest {

    private static final String COOKIE_B = "MUSIC_U=user-b-cookie; NMTID=bbb";

    private SongMapper songMapper;
    private NeteaseApiService neteaseApiService;
    private SongCacheService cacheService;
    private StringRedisTemplate redisTemplate;
    private ThreadPoolTaskExecutor searchExec;
    private ThreadPoolTaskExecutor warmExec;
    private UserService userService;
    private SongSearchService songSearchService;

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
        userService = mock(UserService.class);
        songSearchService = new SongSearchService(songMapper, neteaseApiService,
                cacheService, new SimpleMeterRegistry(),
                searchExec, warmExec, redisTemplate);
        songSearchService.setUserService(userService);
        songSearchService.initMetrics();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (searchExec != null) searchExec.shutdown();
        if (warmExec != null) warmExec.shutdown();
    }

    private static void loginAs(Long userId) {
        User user = new User();
        user.setId(userId);
        var auth = new UsernamePasswordAuthenticationToken(
                new CustomUserDetails(user), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static SongDTO song(String sourceId, String name) {
        SongDTO s = new SongDTO();
        s.setSourceId(sourceId);
        s.setName(name);
        s.setArtist("周杰伦");
        s.setDuration(240);
        return s;
    }

    private static Map<String, Object> apiPayload(String id, String name) {
        return Map.of("data", List.of(Map.of(
                "id", id, "name", name, "artists", "周杰伦", "duration", 240000)));
    }

    private static Map<String, Object> emptyPayload() {
        return Map.of("data", List.of());
    }

    private void stubOtherPlatformsEmpty() {
        lenient().when(neteaseApiService.searchQQ(anyString(), anyInt())).thenReturn(emptyPayload());
        lenient().when(neteaseApiService.searchMigu(anyString(), anyInt())).thenReturn(emptyPayload());
        lenient().when(neteaseApiService.searchKugou(anyString(), anyInt())).thenReturn(emptyPayload());
        lenient().when(neteaseApiService.searchBili(anyString(), anyInt())).thenReturn(emptyPayload());
    }

    @Test
    @DisplayName("per-user：读写隔离键 u<id>:，不碰共享键，Cookie 透传")
    void perUserUsesIsolatedCacheKey() {
        loginAs(2L);
        when(userService.resolveCurrentNeteaseCookie()).thenReturn(COOKIE_B);
        lenient().when(cacheService.getSearchCache(anyString())).thenReturn(null);
        when(cacheService.tryLockResult(eq("u2:晴天:all")))
                .thenReturn(SongCacheService.LockResult.acquired("lock-u2"));
        stubOtherPlatformsEmpty();
        when(neteaseApiService.searchNetease(eq("晴天"), eq(40), eq(COOKIE_B)))
                .thenReturn(apiPayload("vip-1", "晴天VIP版"));

        SearchResult result = songSearchService.search("晴天", 1, 20);

        assertEquals("api", result.getSource());
        assertEquals(1, result.getList().size());
        assertEquals("vip-1", result.getList().get(0).getSourceId());

        verify(cacheService).getSearchCache(eq("u2:晴天:all"));
        verify(cacheService, never()).getSearchCache(eq("晴天:all"));
        verify(cacheService).setSearchCache(eq("u2:晴天:all"), anyList(), eq(true), anyBoolean(), any());
        verify(cacheService, never()).setSearchCache(eq("晴天:all"), anyList(), anyBoolean(), anyBoolean(), any());
        verify(neteaseApiService, times(1)).searchNetease(eq("晴天"), eq(40), eq(COOKIE_B));
    }

    @Test
    @DisplayName("隔离：B 用户命中自己的键，读不到共享/他人结果")
    void userBIsolatedFromSharedCache() {
        stubOtherPlatformsEmpty();
        when(cacheService.getSearchCache(eq("夜曲:all")))
                .thenReturn(List.of(song("shared-a", "夜曲")));
        SearchResult anon = songSearchService.search("夜曲", 1, 20);
        assertEquals("redis", anon.getSource());
        assertEquals("shared-a", anon.getList().get(0).getSourceId());

        clearInvocations(cacheService);
        when(cacheService.getSearchCache(eq("u2:夜曲:all")))
                .thenReturn(List.of(song("vip-b", "夜曲VIP版")));

        loginAs(2L);
        when(userService.resolveCurrentNeteaseCookie()).thenReturn(COOKIE_B);

        SearchResult bResult = songSearchService.search("夜曲", 1, 20);

        assertEquals("vip-b", bResult.getList().get(0).getSourceId());
        verify(cacheService, never()).getSearchCache(eq("夜曲:all"));
        verify(neteaseApiService, never()).searchNetease(anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("per-user 但取不到用户标识：退回不读不写缓存")
    void perUserWithoutIdDoesNotTouchCache() {
        when(userService.resolveCurrentNeteaseCookie()).thenReturn(COOKIE_B);
        when(cacheService.getSearchCache(anyString()))
                .thenReturn(List.of(song("shared-1", "共享旧结果")));
        stubOtherPlatformsEmpty();
        when(neteaseApiService.searchNetease(eq("晴天"), eq(40), eq(COOKIE_B)))
                .thenReturn(apiPayload("vip-1", "晴天VIP版"));

        SearchResult result = songSearchService.search("晴天", 1, 20);

        assertEquals("api", result.getSource());
        assertEquals("vip-1", result.getList().get(0).getSourceId());
        verify(cacheService, never()).getSearchCache(anyString());
        verify(cacheService, never()).setSearchCache(anyString(), anyList(), anyBoolean(), anyBoolean(), any());
        verify(cacheService, never()).tryLockResult(anyString());
    }

    @Test
    @DisplayName("匿名：行为不变（读共享键、命中直接返回）")
    void anonymousFallbackUnchanged() {
        when(cacheService.getSearchCache(eq("稻香:all")))
                .thenReturn(List.of(song("c-1", "稻香")));
        stubOtherPlatformsEmpty();

        SearchResult hit = songSearchService.search("稻香", 1, 20);

        assertEquals("redis", hit.getSource());
        assertEquals("c-1", hit.getList().get(0).getSourceId());
        verify(neteaseApiService, never()).searchNetease(anyString(), anyInt(), anyString());
        verify(neteaseApiService, never()).searchNetease(anyString(), anyInt());
    }

    @Test
    @DisplayName("单平台 netease + per-user：写隔离键而非共享键")
    void singlePlatformNeteasePerUserWritesIsolatedKey() {
        loginAs(2L);
        when(userService.resolveCurrentNeteaseCookie()).thenReturn(COOKIE_B);
        lenient().when(cacheService.getSearchCache(anyString())).thenReturn(null);
        when(cacheService.tryLockResult(eq("u2:七里香:netease")))
                .thenReturn(SongCacheService.LockResult.acquired("lock-u2-ne"));
        when(neteaseApiService.searchNetease(eq("七里香"), eq(40), eq(COOKIE_B)))
                .thenReturn(apiPayload("vip-7", "七里香VIP版"));

        SearchResult result = songSearchService.search("七里香", 1, 20, "netease");

        assertEquals("api", result.getSource());
        assertEquals("vip-7", result.getList().get(0).getSourceId());
        verify(cacheService).setSearchCache(eq("u2:七里香:netease"), anyList(), eq(true), anyBoolean(), any());
        verify(cacheService, never()).setSearchCache(eq("七里香:netease"), anyList(), anyBoolean(), anyBoolean(), any());
    }
}