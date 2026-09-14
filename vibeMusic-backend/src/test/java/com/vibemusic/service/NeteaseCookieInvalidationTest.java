package com.vibemusic.service;

import com.vibemusic.TransactionalServiceTest;
import com.vibemusic.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * BYOC Task 7：过期/失效探测 + 重绑流程（service 层）。
 *
 * <p>密钥复用 {@code application-test.yml} 的 {@code COOKIE_ENC_KEY}，无新增 key plumbing。
 * 状态契约：{@code {has, valid, updatedAt, needsRebind}}，其中
 * {@code needsRebind = has && valid == false} 由 {@code CookieController} 组装，
 * 此处只断言底座（{@code has/valid/updatedAt} 翻转语义）。
 */
@DisplayName("网易云 Cookie 失效标记测试")
class NeteaseCookieInvalidationTest extends TransactionalServiceTest {

    @Autowired
    private UserService userService;

    private static final String COOKIE = "MUSIC_U=expiryprobe123; NMTID=xyz";

    private static Map<String, Object> songUrlPayload(Object code, String url, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("data", List.of(new HashMap<>(Map.of("id", "123456", "url", url == null ? "" : url))));
        if (message != null) body.put("message", message);
        return body;
    }

    @Nested
    @DisplayName("isNeedLoginPayload 信号判定")
    class SignalDetection {
        @Test
        @DisplayName("code 301/-101 → true（含字符串形）")
        void shouldFlagNeedLoginCodes() {
            assertThat(NeteaseApiService.isNeedLoginPayload(songUrlPayload(301, "", null))).isTrue();
            assertThat(NeteaseApiService.isNeedLoginPayload(songUrlPayload(-101, "", null))).isTrue();
            assertThat(NeteaseApiService.isNeedLoginPayload(songUrlPayload("301", "", null))).isTrue();
        }

        @Test
        @DisplayName("message 需要登录/need login → true")
        void shouldFlagNeedLoginMessages() {
            assertThat(NeteaseApiService.isNeedLoginPayload(songUrlPayload(200, "", "需要登录"))).isTrue();
            assertThat(NeteaseApiService.isNeedLoginPayload(
                    songUrlPayload(200, "", "Need Login, please retry"))).isTrue();
        }

        @Test
        @DisplayName("login_status 匿名形 {account:{anonymous:true}} → true")
        void shouldFlagAnonymousLoginStatus() {
            Map<String, Object> body = new HashMap<>(Map.of("code", 200));
            body.put("account", Map.of("anonymous", true));
            assertThat(NeteaseApiService.isNeedLoginPayload(body)).isTrue();
        }

        @Test
        @DisplayName("非信号：null/code200+有链/code200+空链（无版权）/已登录态 → false")
        void shouldNotFlagNonSignals() {
            assertThat(NeteaseApiService.isNeedLoginPayload(null)).isFalse();
            assertThat(NeteaseApiService.isNeedLoginPayload(
                    songUrlPayload(200, "https://cdn.example/x.mp3", null))).isFalse();
            // 无版权：code 200 + 空 url 绝不能误判为失效
            assertThat(NeteaseApiService.isNeedLoginPayload(songUrlPayload(200, "", null))).isFalse();
            Map<String, Object> authed = new HashMap<>(Map.of("code", 200));
            authed.put("account", Map.of("anonymous", false));
            assertThat(NeteaseApiService.isNeedLoginPayload(authed)).isFalse();
        }

        @Test
        @DisplayName("extractUpstreamCode 透出顶层 code，null 体返回 null")
        void shouldExtractUpstreamCode() {
            assertThat(NeteaseApiService.extractUpstreamCode(songUrlPayload(301, "", null))).isEqualTo(301);
            assertThat(NeteaseApiService.extractUpstreamCode(null)).isNull();
        }
    }

    @Nested
    @DisplayName("valid 翻转：simulated need-login payload → mark → 0")
    class Flip {
        @Test
        @DisplayName("need-login 载荷判定 true 后标记，valid 由 null 翻为 false，has 与 updatedAt 不变")
        void shouldFlipValidToFalseOnNeedLogin() {
            userService.saveNeteaseCookie(1L, COOKIE);
            assertThat(userService.getNeteaseCookieStatus(1L).valid()).isNull();

            Map<String, Object> payload = songUrlPayload(301, "", null);
            assertThat(NeteaseApiService.isNeedLoginPayload(payload)).isTrue();

            userService.markNeteaseCookieInvalid(1L, NeteaseApiService.extractUpstreamCode(payload));

            UserService.NeteaseCookieStatus status = userService.getNeteaseCookieStatus(1L);
            assertThat(status.has()).isTrue();
            assertThat(status.valid()).isFalse();
            assertThat(status.updatedAt()).isNotNull();
        }

        @Test
        @DisplayName("重复标记幂等：保持 false 不抛错")
        void shouldStayFalseOnRepeatedMark() {
            userService.saveNeteaseCookie(1L, COOKIE);
            userService.markNeteaseCookieInvalid(1L, 301);
            userService.markNeteaseCookieInvalid(1L, 301);
            assertThat(userService.getNeteaseCookieStatus(1L).valid()).isFalse();
        }

        @Test
        @DisplayName("重绑后 valid 重置未知：失效→save→null（重绑闭环）")
        void shouldResetToUnknownOnRebind() {
            userService.saveNeteaseCookie(1L, COOKIE);
            userService.markNeteaseCookieInvalid(1L, 301);
            assertThat(userService.getNeteaseCookieStatus(1L).valid()).isFalse();

            userService.saveNeteaseCookie(1L, "MUSIC_U=rebound456; NMTID=xyz");
            assertThat(userService.getNeteaseCookieStatus(1L).valid()).isNull();
        }

        @Test
        @DisplayName("不存在的用户 → 404")
        void shouldThrowWhenUserNotFound() {
            assertThatThrownBy(() -> userService.markNeteaseCookieInvalid(999L, 301))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("用户不存在");
        }
    }
}
