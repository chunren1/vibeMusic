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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SongSearchService per-user Cookie 隔离测试（纯 Mockito）。
 *
 * <p>隔离策略（已定）：per-user Cookie 存在时绕过全部共享缓存读写
 * （Redis 读/写、单飞锁），直查上游且结果仅当次返回。
 * A 用户的 VIP 结果永不写入共享键，也永不读到 B/匿名缓存。
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
    @DisplayName("per-user：绕过 Redis 读与写，直查上游并透传 Cookie")
    void perUserBypassesSharedCacheReadsAndWrites() {
        loginAs(2L);
        when(userService.resolveNeteaseCookie(2L)).thenReturn(Optional.of(COOKIE_B));
        when(cacheService.getSearchCache(anyString()))
                .thenReturn(List.of(song("shared-1", "共享旧结果")));
        stubOtherPlatformsEmpty();
        when(neteaseApiService.searchNetease(eq("晴天"), eq(40), eq(COOKIE_B)))
                .thenReturn(apiPayload("vip-1", "晴天VIP版"));

        SearchResult result = songSearchService.search("晴天", 1, 20);

        assertEquals("api", result.getSource());
        assertEquals(1, result.getList().size());
        assertEquals("vip-1", result.getList().get(0).getSourceId());
        verify(cacheService, never()).getSearchCache(anyString());
        verify(cacheService, never()).setSearchCache(anyString(), anyList(), anyBoolean());
        verify(cacheService, never()).setSearchCache(anyString(), anyList(), anyBoolean(), anyBoolean());
        verify(cacheService, never()).tryLock(anyString());
        verify(cacheService, never()).releaseLock(anyString(), anyString());

        verify(neteaseApiService, times(1)).searchNetease(eq("晴天"), eq(40), eq(COOKIE_B));
        verify(neteaseApiService, never()).searchNetease(anyString(), anyInt());
    }

    @Test
    @DisplayName("隔离：B 用户读不到共享缓存的 A 结果，且 B 的结果不回写共享")
    void userBIsolatedFromSharedCache() {
        stubOtherPlatformsEmpty();
        when(cacheService.getSearchCache(anyString())).thenReturn(null);
        when(cacheService.tryLock(anyString())).thenReturn("lock-anon");
        when(neteaseApiService.searchNetease(eq("夜曲"), eq(40)))
                .thenReturn(apiPayload("shared-a", "夜曲"));
        when(neteaseApiService.searchNetease(eq("夜曲"), eq(40), eq(COOKIE_B)))
                .thenReturn(apiPayload("vip-b", "夜曲VIP版"));

        SearchResult anon = songSearchService.search("夜曲", 1, 20);
        assertEquals("api", anon.getSource());
        assertEquals("shared-a", anon.getList().get(0).getSourceId());

        clearInvocations(cacheService);
        when(cacheService.getSearchCache(anyString()))
                .thenReturn(List.of(song("shared-a", "夜曲")));

        loginAs(2L);
        when(userService.resolveNeteaseCookie(2L)).thenReturn(Optional.of(COOKIE_B));

        SearchResult bResult = songSearchService.search("夜曲", 1, 20);

        assertEquals("vip-b", bResult.getList().get(0).getSourceId());
        verify(cacheService, never()).getSearchCache(anyString());
        verify(cacheService, never()).setSearchCache(anyString(), anyList(), anyBoolean());
        verify(cacheService, never()).setSearchCache(anyString(), anyList(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("匿名：回退行为不变（读缓存、2 参数签名、命中写回）")
    void anonymousFallbackUnchanged() {
        when(cacheService.getSearchCache(eq("稻香:all")))
                .thenReturn(List.of(song("c-1", "稻香")));
        stubOtherPlatformsEmpty();

        SearchResult hit = songSearchService.search("稻香", 1, 20);

        assertEquals("redis", hit.getSource());
        assertEquals("c-1", hit.getList().get(0).getSourceId());
        verify(neteaseApiService, never()).searchNetease(anyString(), anyInt(), anyString());
        verify(neteaseApiService, never()).searchNetease(anyString(), anyInt());

        clearInvocations(cacheService, neteaseApiService);
        when(cacheService.getSearchCache(eq("夜曲:all"))).thenReturn(null);
        when(cacheService.tryLock(anyString())).thenReturn(null);
        when(neteaseApiService.searchNetease(eq("夜曲"), eq(40)))
                .thenReturn(apiPayload("n-1", "夜曲"));

        SearchResult miss = songSearchService.search("夜曲", 1, 20);

        assertEquals("api", miss.getSource());
        verify(neteaseApiService, times(1)).searchNetease(eq("夜曲"), eq(40));
        verify(neteaseApiService, never()).searchNetease(anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("单平台 netease + per-user：透传 Cookie 且不写单平台缓存")
    void singlePlatformNeteasePerUserNoWrite() {
        loginAs(2L);
        when(userService.resolveNeteaseCookie(2L)).thenReturn(Optional.of(COOKIE_B));
        when(neteaseApiService.searchNetease(eq("七里香"), eq(40), eq(COOKIE_B)))
                .thenReturn(apiPayload("vip-7", "七里香VIP版"));

        SearchResult result = songSearchService.search("七里香", 1, 20, "netease");

        assertEquals("api", result.getSource());
        assertEquals("vip-7", result.getList().get(0).getSourceId());
        verify(cacheService, never()).getSearchCache(anyString());
        verify(cacheService, never()).setSearchCache(anyString(), anyList(), anyBoolean());
        verify(cacheService, never()).setSearchCache(anyString(), anyList(), anyBoolean(), anyBoolean());
    }
}
