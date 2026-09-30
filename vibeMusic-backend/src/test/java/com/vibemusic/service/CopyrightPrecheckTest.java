package com.vibemusic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 版权预检纯函数测试（audio-source batch c）。
 *
 * <p>锁定 fast-fail 口径：只有"data 非空 + 全部 url 空 + 无试听信息"才判
 * NO_COPYRIGHT；need-login、试听版、空结果一律不拦截（保守走完整降级链）。
 */
@DisplayName("版权预检分类测试")
class CopyrightPrecheckTest {

    @Test
    @DisplayName("null/空 data → UNKNOWN（不误杀 transient 空结果）")
    void nullAndEmptyAreUnknown() {
        assertEquals(NeteaseApiService.CopyrightPrecheck.UNKNOWN,
                NeteaseApiService.precheckUrlPayload(null));
        assertEquals(NeteaseApiService.CopyrightPrecheck.UNKNOWN,
                NeteaseApiService.precheckUrlPayload(Map.of("code", 200, "data", List.of())));
        assertEquals(NeteaseApiService.CopyrightPrecheck.UNKNOWN,
                NeteaseApiService.precheckUrlPayload(Map.of("code", 200)));
    }

    @Test
    @DisplayName("need-login 三形态 → NEED_LOGIN（可快切，不属版权拦截）")
    void needLoginSignals() {
        assertEquals(NeteaseApiService.CopyrightPrecheck.NEED_LOGIN,
                NeteaseApiService.precheckUrlPayload(Map.of("code", 301)));
        assertEquals(NeteaseApiService.CopyrightPrecheck.NEED_LOGIN,
                NeteaseApiService.precheckUrlPayload(Map.of("code", -101)));
        assertEquals(NeteaseApiService.CopyrightPrecheck.NEED_LOGIN,
                NeteaseApiService.precheckUrlPayload(Map.of("code", 200, "message", "需要登录")));
    }

    @Test
    @DisplayName("data 全员 url 空且无试听 → NO_COPYRIGHT")
    void allBlockedIsNoCopyright() {
        Map<String, Object> body = Map.of("code", 200, "data", List.of(
                Map.of("id", "1"), Map.of("id", "2", "url", "")));
        assertEquals(NeteaseApiService.CopyrightPrecheck.NO_COPYRIGHT,
                NeteaseApiService.precheckUrlPayload(body));
    }

    @Test
    @DisplayName("任一条目有可用 url → PLAYABLE")
    void anyUrlIsPlayable() {
        Map<String, Object> body = Map.of("code", 200, "data", List.of(
                Map.of("id", "1"), Map.of("id", "2", "url", "https://cdn.example/x.mp3")));
        assertEquals(NeteaseApiService.CopyrightPrecheck.PLAYABLE,
                NeteaseApiService.precheckUrlPayload(body));
    }

    @Test
    @DisplayName("试听版（有 url + 试听信息）→ PLAYABLE（试听判定仍由探针负责）")
    void trialIsPlayable() {
        Map<String, Object> item = new HashMap<>();
        item.put("id", "1");
        item.put("url", "https://cdn.example/trial.mp3");
        item.put("freeTrialInfo", Map.of("start", 0, "end", 30));
        Map<String, Object> body = Map.of("code", 200, "data", List.of(item));
        assertEquals(NeteaseApiService.CopyrightPrecheck.PLAYABLE,
                NeteaseApiService.precheckUrlPayload(body));
    }

    @Test
    @DisplayName("url 空但有试听信息 → UNKNOWN（保守走降级链，不硬拦截）")
    void blockedButTrialIsUnknown() {
        Map<String, Object> item = new HashMap<>();
        item.put("id", "1");
        item.put("freeTrialInfo", Map.of("start", 0, "end", 30));
        Map<String, Object> body = Map.of("code", 200, "data", List.of(item));
        assertEquals(NeteaseApiService.CopyrightPrecheck.UNKNOWN,
                NeteaseApiService.precheckUrlPayload(body));
    }

    @Test
    @DisplayName("咪咕 200000 无试听地址 → NO_COPYRIGHT")
    void miguNoTrialIsNoCopyright() {
        assertEquals(NeteaseApiService.CopyrightPrecheck.NO_COPYRIGHT,
                NeteaseApiService.precheckUrlPayload(Map.of("code", 200000, "info", "暂不提供试听地址")));
    }
}
