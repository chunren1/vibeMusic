package com.vibemusic.common.utils;

import com.vibemusic.security.CustomUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("限流身份解析：已登录用户 > 设备 ID > X-Real-IP > remoteAddr")
class RequestIdentityTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("已登录用户优先，且忽略伪造的设备头")
    void authenticatedUserWins() {
        CustomUserDetails details = mock(CustomUserDetails.class);
        when(details.getUserId()).thenReturn(7L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, List.of()));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/songs/search");
        request.addHeader("X-Device-Id", "device-abc");

        assertEquals("user:7", RequestIdentity.resolve(request));
    }

    @Test
    @DisplayName("未登录时用设备 ID")
    void deviceIdWhenAnonymous() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/songs/search");
        request.addHeader("X-Device-Id", "device-abc");
        request.addHeader("X-Real-IP", "1.2.3.4");

        assertEquals("dev:device-abc", RequestIdentity.resolve(request));
    }

    @Test
    @DisplayName("无设备 ID 时用 nginx 设置的 X-Real-IP")
    void realIpWhenNoDevice() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/songs/search");
        request.addHeader("X-Real-IP", "1.2.3.4");
        request.setRemoteAddr("10.0.0.9");

        assertEquals("ip:1.2.3.4", RequestIdentity.resolve(request));
    }

    @Test
    @DisplayName("都没有时回落 remoteAddr")
    void remoteAddrFallback() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/songs/search");
        request.setRemoteAddr("172.18.0.1");

        assertEquals("ip:172.18.0.1", RequestIdentity.resolve(request));
    }

    @Test
    @DisplayName("非法设备 ID（超长/含非法字符）被丢弃，回落 IP —— 防止撑爆 Redis 键空间")
    void invalidDeviceIdIgnored() {
        MockHttpServletRequest tooLong = new MockHttpServletRequest("GET", "/api/songs/search");
        tooLong.addHeader("X-Device-Id", "x".repeat(65));
        tooLong.setRemoteAddr("10.0.0.1");
        assertEquals("ip:10.0.0.1", RequestIdentity.resolve(tooLong));

        MockHttpServletRequest illegal = new MockHttpServletRequest("GET", "/api/songs/search");
        illegal.addHeader("X-Device-Id", "bad id with spaces*");
        illegal.setRemoteAddr("10.0.0.2");
        assertEquals("ip:10.0.0.2", RequestIdentity.resolve(illegal));
    }

    @Test
    @DisplayName("入站 X-User-Id 不被采信（客户端可伪造，采信等于送额度）")
    void inboundUserIdHeaderIgnored() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/songs/search");
        request.addHeader("X-User-Id", "user:999");
        request.setRemoteAddr("10.0.0.3");

        assertEquals("ip:10.0.0.3", RequestIdentity.resolve(request));
    }
}
