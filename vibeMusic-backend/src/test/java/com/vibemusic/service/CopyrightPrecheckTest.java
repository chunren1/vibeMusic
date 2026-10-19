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

    @Test
    @DisplayName("空链 + 付费曲目未授权（fee=1,payed=0）→ PERMISSION_BLOCKED（VIP 掉档，非无版权）")
    void paidBlockedIsPermissionBlocked() {
        assertEquals(NeteaseApiService.CopyrightPrecheck.PERMISSION_BLOCKED,
                NeteaseApiService.precheckUrlPayload(Map.of("code", 200, "data", List.of(
                        Map.of("id", "273114", "url", "", "fee", 1, "payed", 0)))));
        // 字段缺失 url（无 url 键）同样命中
        assertEquals(NeteaseApiService.CopyrightPrecheck.PERMISSION_BLOCKED,
                NeteaseApiService.precheckUrlPayload(Map.of("code", 200, "data", List.of(
                        Map.of("id", "273114", "fee", "1", "payed", "0")))));
    }

    @Test
    @DisplayName("空链 + fee=0（真无版权）→ 仍 NO_COPYRIGHT（不误判成权限受限）")
    void freeBlankIsStillNoCopyright() {
        assertEquals(NeteaseApiService.CopyrightPrecheck.NO_COPYRIGHT,
                NeteaseApiService.precheckUrlPayload(Map.of("code", 200, "data", List.of(
                        Map.of("id", "186016", "url", "", "fee", 0)))));
    }

    @Test
    @DisplayName("付费曲目已授权（payed=1）但空链 → NO_COPYRIGHT（无权限信号不足，按无版权处理）")
    void paidButAlreadyPayedBlankIsNoCopyright() {
        assertEquals(NeteaseApiService.CopyrightPrecheck.NO_COPYRIGHT,
                NeteaseApiService.precheckUrlPayload(Map.of("code", 200, "data", List.of(
                        Map.of("id", "273114", "url", "", "fee", 1, "payed", 1)))));
    }

    @Test
    @DisplayName("付费曲目空链且有试听信息 → PERMISSION_BLOCKED（仍是权限受限，非无版权）")
    void paidWithTrialInfoIsPermissionBlocked() {
        Map<String, Object> item = new HashMap<>();
        item.put("id", "273114");
        item.put("fee", 1);
        item.put("payed", 0);
        item.put("freeTrialInfo", Map.of("start", 0, "end", 30));
        assertEquals(NeteaseApiService.CopyrightPrecheck.PERMISSION_BLOCKED,
                NeteaseApiService.precheckUrlPayload(Map.of("code", 200, "data", List.of(item))));
    }

    @Test
    @DisplayName("条目级判定：VIP 受限 / 完整可播 / 试听 三态口径")
    void payloadPredicates() {
        Map<String, Object> trialItem = Map.of("url", "https://cdn.example/preview.mp3",
                "fee", 1, "payed", 0, "freeTrialInfo", Map.of("start", 0, "end", 30));
        Map<String, Object> blankPaidItem = Map.of("url", "", "fee", 1, "payed", 0);
        Map<String, Object> fullItem = Map.of("url", "https://cdn.example/full.mp3", "fee", 1, "payed", 1);
        Map<String, Object> freeBlankItem = Map.of("url", "", "fee", 0);

        assertTrue(NeteaseApiService.isVipBlockedPayload(Map.of("data", List.of(trialItem))));
        assertTrue(NeteaseApiService.isVipBlockedPayload(Map.of("data", List.of(blankPaidItem))));
        assertFalse(NeteaseApiService.isVipBlockedPayload(Map.of("data", List.of(fullItem))));
        assertFalse(NeteaseApiService.isVipBlockedPayload(Map.of("data", List.of(freeBlankItem))));
        assertFalse(NeteaseApiService.isVipBlockedPayload(null));
        assertFalse(NeteaseApiService.isVipBlockedPayload(Map.of("code", 200)));

        Map<String, Object> playableItem = Map.of("url", "https://cdn.example/full.mp3", "time", 240000);
        assertTrue(NeteaseApiService.isFullPlayablePayload(Map.of("data", List.of(playableItem))));
        assertFalse(NeteaseApiService.isFullPlayablePayload(Map.of("data", List.of(trialItem))));
        assertFalse(NeteaseApiService.isFullPlayablePayload(Map.of("data", List.of(Map.of("url", "")))));
    }
}
