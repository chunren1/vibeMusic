package com.vibemusic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("SongCacheService 分布式锁测试")
class SongCacheServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private SongCacheService cacheService;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        cacheService = new SongCacheService(redisTemplate, new ObjectMapper());
    }

    @Test
    @DisplayName("tryLock 成功时返回 lockValue")
    void shouldAcquireLock() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        String lock = cacheService.tryLock("周杰伦:all");
        assertNotNull(lock);
        assertFalse(lock.isEmpty());
        verify(valueOps).setIfAbsent(contains("周杰伦:all"), eq(lock), eq(Duration.ofSeconds(10)));
    }

    @Test
    @DisplayName("tryLock 失败时返回 null")
    void shouldFailToAcquireLock() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        String lock = cacheService.tryLock("周杰伦:all");
        assertNull(lock);
    }

    @Test
    @DisplayName("tryLock Redis 异常时降级返回 null")
    void shouldReturnNullWhenRedisFails() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenThrow(new RuntimeException("Redis down"));
        String lock = cacheService.tryLock("周杰伦:all");
        assertNull(lock);
    }

    @Test
    @DisplayName("releaseLock 为 null 时不操作")
    void shouldSkipReleaseWhenLockValueIsNull() {
        cacheService.releaseLock("周杰伦:all", null);
        verify(redisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), anyString());
    }

    @Test
    @DisplayName("releaseLock 执行 Lua 脚本")
    void shouldReleaseLockViaLua() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString())).thenReturn(1L);
        cacheService.releaseLock("周杰伦:all", "test-lock-value");
        verify(redisTemplate).execute(any(DefaultRedisScript.class), eq(List.of("song:search:lock:周杰伦:all")), eq("test-lock-value"));
    }

    @Test
    @DisplayName("getSearchCache 命中时反序列化")
    void shouldGetCacheWhenHit() {
        String json = "[{\"sourceId\":\"1\",\"name\":\"晴天\",\"artist\":\"周杰伦\",\"platform\":\"netease\"}]";
        when(valueOps.get("song:search:v6:晴天:all")).thenReturn(json);
        var result = cacheService.getSearchCache("晴天:all");
        assertFalse(result.isEmpty());
        assertEquals("晴天", result.get(0).getName());
    }

    @Test
    @DisplayName("getSearchCache 未命中返回 null（调用方可穿透）")
    void shouldReturnNullWhenCacheMiss() {
        when(valueOps.get(anyString())).thenReturn(null);
        var result = cacheService.getSearchCache("不存在:all");
        assertNull(result);
    }

    @Test
    @DisplayName("空结果哨兵写入后读回为空 List（非 null，不再穿透）")
    void shouldRoundTripEmptySentinel() {
        var songs = List.of(new com.vibemusic.dto.SongDTO());
        cacheService.setSearchCache("无结果词:all", songs, false);
        verify(valueOps).set(eq("song:search:v6:无结果词:all"),
                eq(SongCacheService.EMPTY_SENTINEL_JSON), any(Duration.class));

        when(valueOps.get("song:search:v6:无结果词:all"))
                .thenReturn(SongCacheService.EMPTY_SENTINEL_JSON);
        var result = cacheService.getSearchCache("无结果词:all");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("损坏数据按未命中处理，返回 null 而非空结果")
    void shouldReturnNullWhenCacheCorrupted() {
        when(valueOps.get(anyString())).thenReturn("not-json{{{");
        var result = cacheService.getSearchCache("坏数据:all");
        assertNull(result);
    }

    @Test
    @DisplayName("setSearchCache 写入成功")
    void shouldSetCache() {
        var songs = List.of(new com.vibemusic.dto.SongDTO());
        // 不抛异常即视为成功
        cacheService.setSearchCache("周杰伦:all", songs, true);
        verify(valueOps).set(eq("song:search:v6:周杰伦:all"), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("空串缓存按未命中处理，返回 null")
    void shouldTreatEmptyStringAsMiss() {
        when(valueOps.get(anyString())).thenReturn("");
        assertNull(cacheService.getSearchCache("空串:all"));
    }

    @Test
    @DisplayName("单平台缓存只告警不清空：保留结果避免穿透")
    void shouldKeepSinglePlatformCache() {
        var onlyNetease = new com.vibemusic.dto.SongDTO();
        onlyNetease.setPlatform("netease");
        var songs = List.of(onlyNetease, onlyNetease, onlyNetease, onlyNetease);
        String json;
        try {
            json = new ObjectMapper().writeValueAsString(songs);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(valueOps.get("song:search:v6:告白气球:all")).thenReturn(json);

        var result = cacheService.getSearchCache("告白气球:all");
        assertNotNull(result);
        assertEquals(4, result.size());
    }

    @Test
    @DisplayName("不完整结果用 30 秒短 TTL，便于恢复后快速生效")
    void shouldUseShortTtlForIncomplete() {
        var songs = List.of(new com.vibemusic.dto.SongDTO());
        cacheService.setSearchCache("周杰伦:all", songs, true, true);
        verify(valueOps).set(eq("song:search:v6:周杰伦:all"), anyString(), eq(Duration.ofSeconds(30)));
    }

    @Test
    @DisplayName("写入异常静默吞错，不抛到调用方")
    void shouldSwallowWriteFailure() {
        doThrow(new RuntimeException("redis down")).when(valueOps)
                .set(anyString(), anyString(), any(Duration.class));
        var songs = List.of(new com.vibemusic.dto.SongDTO());
        assertDoesNotThrow(() -> cacheService.setSearchCache("k:all", songs, true));
        assertDoesNotThrow(() -> cacheService.setSearchCache("k:all", songs, false));
    }
}
