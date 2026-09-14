package com.vibemusic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("JsonCacheService 负缓存哨兵测试")
class JsonCacheServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private JsonCacheService cacheService;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        cacheService = new JsonCacheService(redisTemplate, new ObjectMapper());
    }

    @Test
    @DisplayName("setEmpty 后 getAsList 读回空 List（非 null，不再穿透）")
    void shouldReadBackEmptyListAfterSetEmpty() {
        cacheService.setEmpty("lyric:v2:123", Duration.ofHours(1));
        verify(valueOps).set(eq("lyric:v2:123"),
                eq(JsonCacheService.EMPTY_SENTINEL_JSON), any(Duration.class));

        when(valueOps.get("lyric:v2:123")).thenReturn(JsonCacheService.EMPTY_SENTINEL_JSON);
        List<Map<String, Object>> result = cacheService.getAsList("lyric:v2:123");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("哨兵下 getAsMap 读回空 Map（非 null，不再穿透）")
    void shouldReadBackEmptyMapAfterSetEmpty() {
        when(valueOps.get(anyString())).thenReturn(JsonCacheService.EMPTY_SENTINEL_JSON);
        Map<String, Object> result = cacheService.getAsMap("some:key");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("key 缺失时 getAsList 返回 null（调用方可穿透）")
    void shouldReturnNullWhenKeyMissing() {
        when(valueOps.get(anyString())).thenReturn(null);
        assertNull(cacheService.getAsList("lyric:v2:missing"));
        assertNull(cacheService.getAsMap("some:missing"));
    }

    @Test
    @DisplayName("正常 JSON 仍可反序列化为 List")
    void shouldDeserializeNormalList() {
        when(valueOps.get(anyString())).thenReturn("[{\"time\":1.0,\"text\":\"hi\"}]");
        List<Map<String, Object>> result = cacheService.getAsList("lyric:v2:456");
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("hi", result.get(0).get("text"));
    }

    @Test
    @DisplayName("get 命中正常 JSON 时按 TypeReference 反序列化")
    void shouldDeserializeGenericGet() {
        when(valueOps.get("k1")).thenReturn("{\"a\":1}");
        Map<String, Object> result = cacheService.get("k1",
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertNotNull(result);
        assertEquals(1, result.get("a"));
    }

    @Test
    @DisplayName("get 命中哨兵/缺失/坏数据时返回 null（调用方可穿透）")
    void shouldReturnNullOnSentinelMissAndCorrupt() {
        when(valueOps.get("k-sentinel")).thenReturn(JsonCacheService.EMPTY_SENTINEL_JSON);
        assertNull(cacheService.get("k-sentinel",
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        when(valueOps.get("k-miss")).thenReturn(null);
        assertNull(cacheService.get("k-miss",
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        when(valueOps.get("k-bad")).thenReturn("not-json{{{");
        assertNull(cacheService.get("k-bad",
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
    }

    @Test
    @DisplayName("getAsMap 正常 JSON 反序列化，坏数据返回 null")
    void shouldDeserializeMapAndTolerateCorrupt() {
        when(valueOps.get("k-map")).thenReturn("{\"x\":\"y\"}");
        Map<String, Object> ok = cacheService.getAsMap("k-map");
        assertNotNull(ok);
        assertEquals("y", ok.get("x"));
        when(valueOps.get("k-bad")).thenReturn("not-json{{{");
        assertNull(cacheService.getAsMap("k-bad"));
    }

    @Test
    @DisplayName("getAsList 坏数据返回 null，不抛错")
    void shouldReturnNullOnCorruptList() {
        when(valueOps.get(anyString())).thenReturn("not-json{{{");
        assertNull(cacheService.getAsList("lyric:v2:bad"));
    }

    @Test
    @DisplayName("set 写入 JSON 序列化结果")
    void shouldWriteJsonOnSet() {
        cacheService.set("k:set", Map.of("a", 1), Duration.ofMinutes(5));
        verify(valueOps).set(eq("k:set"), eq("{\"a\":1}"), eq(Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("Redis 不可用时 set/setEmpty/delete/setString 静默吞错")
    void shouldSwallowWriteFailures() {
        doThrow(new RuntimeException("redis down")).when(valueOps)
                .set(anyString(), anyString(), any(Duration.class));
        assertDoesNotThrow(() -> cacheService.set("k", Map.of("a", 1), Duration.ofMinutes(1)));
        assertDoesNotThrow(() -> cacheService.setEmpty("k", Duration.ofMinutes(1)));
        assertDoesNotThrow(() -> cacheService.setString("k", "v", Duration.ofMinutes(1)));
        doThrow(new RuntimeException("redis down")).when(redisTemplate).delete(anyString());
        assertDoesNotThrow(() -> cacheService.delete("k"));
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));
        assertNull(cacheService.getAsList("k"));
        assertNull(cacheService.getAsMap("k"));
        assertNull(cacheService.get("k",
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
    }

    @Test
    @DisplayName("delete 删除 key，exists 按 hasKey 返回，异常时返回 false")
    void shouldDeleteAndCheckExists() {
        cacheService.delete("k:del");
        verify(redisTemplate).delete("k:del");
        when(redisTemplate.hasKey("k:yes")).thenReturn(true);
        assertTrue(cacheService.exists("k:yes"));
        when(redisTemplate.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));
        assertFalse(cacheService.exists("k:no"));
    }

    @Test
    @DisplayName("setString 透传原始字符串")
    void shouldWriteRawString() {
        cacheService.setString("k:str", "raw", Duration.ofSeconds(30));
        verify(valueOps).set("k:str", "raw", Duration.ofSeconds(30));
    }
}
