package com.vibemusic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ChatMemoryService 匿名隔离测试（Mockito 纯单元测试，不依赖 Redis）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatMemoryService 匿名隔离测试")
class ChatMemoryServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private ChatMemoryService chatMemoryService;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        chatMemoryService = new ChatMemoryService(stringRedisTemplate, new ObjectMapper());
    }

    @Test
    @DisplayName("两个不同匿名标识应读写隔离的 Redis Key")
    void shouldIsolateDifferentAnonymousIds() {
        chatMemoryService.appendMessage(null, "user", "A 的消息", "device-A");
        chatMemoryService.appendMessage(null, "user", "B 的消息", "device-B");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps, times(2)).set(keyCaptor.capture(), anyString(), any());
        List<String> keys = keyCaptor.getAllValues();
        assertEquals("chat:session:anon:device-A", keys.get(0));
        assertEquals("chat:session:anon:device-B", keys.get(1));
        assertNotEquals(keys.get(0), keys.get(1));
    }

    @Test
    @DisplayName("不同匿名标识读取时应命中各自的 Key")
    void shouldReadFromOwnKey() {
        when(valueOps.get("chat:session:anon:device-A"))
                .thenReturn("[{\"role\":\"user\",\"content\":\"A 的消息\"}]");
        when(valueOps.get("chat:session:anon:device-B")).thenReturn(null);

        var historyA = chatMemoryService.getHistory(null, "device-A");
        var historyB = chatMemoryService.getHistory(null, "device-B");

        assertEquals(1, historyA.size());
        assertEquals("A 的消息", historyA.get(0).get("content"));
        assertTrue(historyB.isEmpty());
    }

    @Test
    @DisplayName("已登录用户 Key 应保持 chat:session:{userId} 不变")
    void shouldKeepLoggedInKeyUnchanged() {
        chatMemoryService.appendMessage(42L, "user", "hello", "device-A");

        verify(valueOps).set(eq("chat:session:42"), anyString(), any());
    }

    @Test
    @DisplayName("匿名清除历史应只删除自己的 Key")
    void shouldClearOnlyOwnAnonymousKey() {
        chatMemoryService.clearHistory(null, "device-A");

        verify(stringRedisTemplate).delete("chat:session:anon:device-A");
        verify(stringRedisTemplate, never()).delete("chat:session:anon:device-B");
    }

    @Test
    @DisplayName("恶意标识应被清洗后才嵌入 Key")
    void shouldSanitizeMaliciousId() {
        chatMemoryService.appendMessage(null, "user", "hi", "../../evil key:*\n");

        verify(valueOps).set(eq("chat:session:anon:evilkey"), anyString(), any());
    }

    @Test
    @DisplayName("缺省匿名标识应回退到 anon")
    void shouldFallbackToAnon() {
        chatMemoryService.appendMessage(null, "user", "hi", null);

        verify(valueOps).set(eq("chat:session:anon:anon"), anyString(), any());
    }

    @Test
    @DisplayName("Redis 异常时读写清一律降级为空，不抛错")
    void shouldDegradeOnRedisFailure() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));
        assertTrue(chatMemoryService.getHistory(1L, null).isEmpty());
        assertTrue(chatMemoryService.getHistory(null, "device-X").isEmpty());

        doThrow(new RuntimeException("redis down")).when(valueOps)
                .set(anyString(), anyString(), any());
        assertDoesNotThrow(() -> chatMemoryService.appendMessage(1L, "user", "hi", null));
        doThrow(new RuntimeException("redis down")).when(stringRedisTemplate).delete(anyString());
        assertDoesNotThrow(() -> chatMemoryService.clearHistory(1L, null));
    }

    @Test
    @DisplayName("超 20 条时只保留最近 20 条")
    void shouldTrimToMaxMessages() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"role\":\"user\",\"content\":\"m").append(i).append("\"}");
        }
        sb.append(']');
        when(valueOps.get("chat:session:9")).thenReturn(sb.toString());

        chatMemoryService.appendMessage(9L, "assistant", "new", null);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("chat:session:9"), jsonCaptor.capture(), any());
        String saved = jsonCaptor.getValue();
        assertTrue(saved.contains("\"content\":\"new\""), "新消息应保留");
        assertFalse(saved.contains("\"content\":\"m0\""), "最旧消息应被裁掉");
        int count = saved.split("\"role\"", -1).length - 1;
        assertEquals(20, count);
    }
}
