package com.vibemusic.service;

import com.vibemusic.dto.SearchResult;
import com.vibemusic.dto.SongDTO;
import com.vibemusic.entity.Song;
import com.vibemusic.entity.User;
import com.vibemusic.mapper.SongMapper;
import com.vibemusic.security.CustomUserDetails;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
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
 * SongPlayService per-user Cookie 测试（纯 Mockito）。
 *
 * <p>验证：登录用户取链走 3 参数 getSongUrl 并透传 Cookie；
 * 匿名走旧 2 参数签名；per-user 绕过共享 Redis exists 缓存。
 */
@TestPropertySource(properties = "COOKIE_ENC_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@DisplayName("SongPlayService per-user Cookie 测试")
class SongPlayUserCookieTest {

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
        var auth = new UsernamePasswordAuthenticationToken(
                new CustomUserDetails(user), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static Map<String, Object> urlPayload(String url) {
        return Map.of("data", List.of(Map.of("url", url)));
    }

    private static Map<String, Object> emptyPayload() {
        return Map.of("data", List.of());
    }

    @Test
    @DisplayName("匿名取链：走旧 2 参数签名，不带用户 Cookie")
    void anonymousUsesLegacySignature() {
        when(neteaseApiService.getSongUrl(eq("123456"), anyString()))
                .thenReturn(urlPayload("https://ne-cdn.example/vip.mp3"));

        String url = songPlayService.getPlayUrl("123456");

        assertEquals("https://ne-cdn.example/vip.mp3", url);
        verify(neteaseApiService, never()).getSongUrl(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("登录用户取链：3 参数透传 Cookie，且不走匿名签名")
    void loggedInUserAttachesCookie() {
        loginAs(1L);
        when(userService.resolveNeteaseCookie(1L)).thenReturn(Optional.of(COOKIE));
        when(neteaseApiService.getSongUrl(eq("123456"), anyString(), eq(COOKIE)))
                .thenReturn(urlPayload("https://ne-cdn.example/user.mp3"));

        String url = songPlayService.getPlayUrl("123456", "晴天", "周杰伦", "netease");

        assertEquals("https://ne-cdn.example/user.mp3", url);
        verify(neteaseApiService, atLeastOnce()).getSongUrl(eq("123456"), anyString(), eq(COOKIE));
        verify(neteaseApiService, never()).getSongUrl(anyString(), anyString());
        verify(neteaseApiService, never()).searchNetease(anyString(), anyInt());
    }

    @Test
    @DisplayName("登录但未绑定 Cookie：回落匿名签名")
    void loggedInWithoutCookieFallsBackToAnonymous() {
        loginAs(1L);
        when(userService.resolveNeteaseCookie(1L)).thenReturn(Optional.empty());
        when(neteaseApiService.getSongUrl(eq("123456"), anyString()))
                .thenReturn(urlPayload("https://ne-cdn.example/shared.mp3"));

        String url = songPlayService.getPlayUrl("123456");

        assertEquals("https://ne-cdn.example/shared.mp3", url);
        verify(neteaseApiService, never()).getSongUrl(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("per-user 绕过共享 Redis exists 缓存：直探 MinIO 且不读写 Redis")
    void perUserBypassesSharedMinioExistsCache() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("minio:exists:v1:123456")).thenReturn("1");
        when(storageService.exists("songs/123456.mp3")).thenReturn(true);
        when(storageService.getDirectUrl("songs/123456.mp3")).thenReturn("https://minio.example/x.mp3");
        SongPlayService svc = new SongPlayService(songMapper, neteaseApiService,
                storageService, redis, getUrlExecutor);
        svc.setUserService(userService);

        loginAs(1L);
        when(userService.resolveNeteaseCookie(1L)).thenReturn(Optional.of(COOKIE));

        String url = svc.getPlayUrl("123456");

        assertEquals("https://minio.example/x.mp3", url);
        verify(redis, never()).opsForValue();
        verify(storageService, times(1)).exists("songs/123456.mp3");
        verify(neteaseApiService, never()).getSongUrl(anyString(), anyString(), anyString());
        verify(neteaseApiService, never()).getSongUrl(anyString(), anyString());
    }
}
