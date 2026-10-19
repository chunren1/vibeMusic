package com.vibemusic.service;

import com.vibemusic.entity.Song;
import com.vibemusic.entity.User;
import com.vibemusic.mapper.SongMapper;
import com.vibemusic.security.CustomUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * VIP 权限受限（共享 Cookie 掉 VIP）能力感知快切测试（纯 Mockito）。
 *
 * <p>复现的线上事实（2026-10）：主 Cookie 丢失 VIP 后，VIP-only 曲目取链返回
 * {@code code=200 + url=<30s 试听> + fee=1 + payed=0 + freeTrialInfo!=null}，
 * 登录态仍有效（need-login 信号永不出），故 need-login 快切永不触发、
 * 备 Cookie 永不接管，App 侧只能拿到试听被拒（404）。
 *
 * <p>本类锁定：共享链路 VIP 受限 → 恰好一次能力快切 + 重试一次（可播才采用）；
 * per-user（BYOC）路径永不快切；无版权（fee=0 + 空链）仍硬拦截；
 * PERMISSION_BLOCKED 不硬拦截、继续 QQ 降级。
 */
@DisplayName("VIP 权限受限能力快切测试")
class SongPlayVipBlockFailoverTest {

    private static final String NET_BLOCKED_ID = "273114";
    private static final String COOKIE = "MUSIC_U=user-cookie-a; NMTID=xyz";

    private SongMapper songMapper;
    private NeteaseApiService neteaseApiService;
    private StorageService storageService;
    private StringRedisTemplate stringRedisTemplate;
    private ThreadPoolTaskExecutor getUrlExecutor;
    private UserService userService;
    private SongPlayService songPlayService;

    @BeforeAll
    static void initMyBatisPlusLambdaCache() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                Song.class);
    }

    @BeforeEach
    void setUp() {
        songMapper = mock(SongMapper.class);
        neteaseApiService = mock(NeteaseApiService.class);
        storageService = mock(StorageService.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        when(stringRedisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));
        getUrlExecutor = new ThreadPoolTaskExecutor();
        getUrlExecutor.setCorePoolSize(2);
        getUrlExecutor.setMaxPoolSize(2);
        getUrlExecutor.setQueueCapacity(10);
        getUrlExecutor.initialize();
        userService = mock(UserService.class);
        songPlayService = new SongPlayService(songMapper, neteaseApiService,
                storageService, stringRedisTemplate, getUrlExecutor);
        songPlayService.setUserService(userService);
        when(songMapper.selectOne(any())).thenReturn(null);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (getUrlExecutor != null) getUrlExecutor.shutdown();
    }

    private static void loginAs(Long userId) {
        User user = new User();
        user.setId(userId);
        var auth = new UsernamePasswordAuthenticationToken(new CustomUserDetails(user), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** 主 Cookie 掉 VIP 的实测响应形：30s 试听 + fee=1 + payed=0 + freeTrialInfo。 */
    private static Map<String, Object> vipBlockedPreview() {
        return Map.of("code", 200, "data", List.of(Map.of(
                "id", NET_BLOCKED_ID, "url", "https://ne-cdn.example/preview.mp3",
                "fee", 1, "payed", 0, "level", "standard", "time", 30000,
                "freeTrialInfo", Map.of("start", 0, "end", 30))));
    }

    /** 换用有 VIP 的 Cookie 后的响应形：payed=1 + 无试听信息。 */
    private static Map<String, Object> fullPlayable() {
        return Map.of("code", 200, "data", List.of(Map.of(
                "id", NET_BLOCKED_ID, "url", "https://ne-cdn.example/full.mp3",
                "fee", 1, "payed", 1, "level", "exhigh", "time", 240000)));
    }

    private void stubQqFallback(String qqUrl) {
        when(neteaseApiService.searchQQ(anyString(), anyInt()))
                .thenReturn(Map.of("data", List.of(
                        Map.of("id", "qq123", "name", "失眠", "artists", "歌手", "duration", 240000))));
        when(neteaseApiService.getQQSongUrl("qq123"))
                .thenReturn(Map.of("data", List.of(Map.of("url", qqUrl))));
    }

    @Test
    @DisplayName("共享链路 VIP 受限：快切一次并采用重试结果（完整可播才采信）")
    void sharedVipBlockedRotatesOnceAndAdoptsRetry() {
        AtomicBoolean firstCall = new AtomicBoolean(true);
        when(neteaseApiService.getSongUrl(eq(NET_BLOCKED_ID), anyString()))
                .thenAnswer(inv -> firstCall.getAndSet(false) ? vipBlockedPreview() : fullPlayable());
        when(neteaseApiService.rotateSharedCookie(true)).thenReturn(true);
        when(neteaseApiService.getQQSongUrl(anyString())).thenReturn(Map.of("data", List.of()));
        when(neteaseApiService.searchNetease(anyString(), anyInt()))
                .thenReturn(Map.of("data", List.of(Map.of("id", NET_BLOCKED_ID, "duration", 240000))));

        String url = songPlayService.getPlayUrl("001Qu4I30eVFYb", "失眠", "歌手", "qq");

        assertEquals("https://ne-cdn.example/full.mp3", url);
        verify(neteaseApiService, times(1)).rotateSharedCookie(true);
        verify(neteaseApiService, times(2)).getSongUrl(eq(NET_BLOCKED_ID), anyString());
        verify(neteaseApiService, never()).rotateSharedCookie();
    }

    @Test
    @DisplayName("并行取链 VIP 受限：冷却窗口内只快切一次，重试仍试听则不采信、继续降级链")
    void parallelVipBlockedRotatesOnceWithinCooldown() {
        when(neteaseApiService.getSongUrl(eq(NET_BLOCKED_ID), anyString())).thenReturn(vipBlockedPreview());
        when(neteaseApiService.rotateSharedCookie(true)).thenReturn(true);
        when(neteaseApiService.searchQQ(anyString(), anyInt())).thenReturn(Map.of("data", List.of()));

        String url = songPlayService.getPlayUrl(NET_BLOCKED_ID, "失眠", "歌手");

        assertNull(url);
        verify(neteaseApiService, times(1)).rotateSharedCookie(true);
        verify(neteaseApiService, never()).rotateSharedCookie();
        // 重试仍是试听 → 保留原响应（试听）继续 QQ 降级链
        verify(neteaseApiService, atLeastOnce()).searchQQ(anyString(), anyInt());
    }

    @Test
    @DisplayName("per-user VIP 受限：永不快切、永不误标失效（BYOC 语义不变）")
    void perUserVipBlockedNeverRotates() {
        loginAs(1L);
        when(userService.resolveCurrentNeteaseCookie()).thenReturn(COOKIE);
        when(neteaseApiService.getSongUrl(eq("123456"), anyString(), eq(COOKIE)))
                .thenReturn(vipBlockedPreview());
        when(neteaseApiService.searchQQ(anyString(), anyInt())).thenReturn(Map.of("data", List.of()));

        songPlayService.getPlayUrl("123456", "失眠", "歌手", "netease");

        verify(neteaseApiService, never()).rotateSharedCookie(anyBoolean());
        verify(userService, never()).markNeteaseCookieInvalid(any(), any());
    }

    @Test
    @DisplayName("真无版权（fee=0 + 空链）：仍硬拦截，不快切不降级")
    void genuineNoCopyrightStillHardBlocks() {
        when(neteaseApiService.getSongUrl(eq("186016"), anyString()))
                .thenReturn(Map.of("code", 200, "data", List.of(
                        Map.of("id", "186016", "url", "", "fee", 0))));

        String url = songPlayService.getPlayUrl("186016", "某曲", "某歌手");

        assertNull(url);
        verify(neteaseApiService, never()).rotateSharedCookie(anyBoolean());
        verify(neteaseApiService, never()).searchQQ(anyString(), anyInt());
    }

    @Test
    @DisplayName("付费曲目空链（fee=1 无试听）：PERMISSION_BLOCKED 不硬拦截，继续 QQ 降级")
    void permissionBlockedStillDegradesToQq() {
        when(neteaseApiService.getSongUrl(eq(NET_BLOCKED_ID), anyString()))
                .thenReturn(Map.of("code", 200, "data", List.of(
                        Map.of("id", NET_BLOCKED_ID, "url", "", "fee", 1, "payed", 0))));
        stubQqFallback("https://qq-cdn.example/x.mp3");

        String url = songPlayService.getPlayUrl(NET_BLOCKED_ID, "失眠", "歌手");

        assertEquals("https://qq-cdn.example/x.mp3", url);
        verify(neteaseApiService, atLeastOnce()).searchQQ(anyString(), anyInt());
    }
}
