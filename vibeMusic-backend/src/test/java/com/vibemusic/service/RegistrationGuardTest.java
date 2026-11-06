package com.vibemusic.service;

import com.vibemusic.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("注册防滥用闸门")
class RegistrationGuardTest {

    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryAcquire(anyString(), anyInt(), any())).thenReturn(true);
    }

    private RegistrationGuard guard(String inviteCode, int dailyLimit) {
        return new RegistrationGuard(rateLimitService, inviteCode, dailyLimit);
    }

    private MockHttpServletRequest request(String ip) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Real-IP", ip);
        return req;
    }

    @Nested
    @DisplayName("用户名字符集")
    class Username {

        @Test
        @DisplayName("字母数字下划线放行")
        void acceptsWhitelisted() {
            RegistrationGuard g = guard("", 3);

            assertDoesNotThrow(() -> g.validateUsername("kk_star123"));
        }

        @Test
        @DisplayName("中文 / 空格 / 连字符 / emoji / 过短 一律 400")
        void rejectsOthers() {
            RegistrationGuard g = guard("", 3);

            for (String bad : new String[]{"新用户", "kk star", "kk-star", "kk😀", "kk"}) {
                BusinessException ex = assertThrows(BusinessException.class,
                        () -> g.validateUsername(bad), "应拒绝: " + bad);
                assertEquals(400, ex.getCode());
            }
        }
    }

    @Nested
    @DisplayName("邀请码")
    class InviteCode {

        @Test
        @DisplayName("未配置 → 任何 code（含 null）都放行，保证存量客户端可用")
        void disabledWhenBlank() {
            RegistrationGuard g = guard("", 3);

            assertFalse(g.isInviteCodeRequired());
            assertDoesNotThrow(() -> g.validateInviteCode(null));
            assertDoesNotThrow(() -> g.validateInviteCode("whatever"));
        }

        @Test
        @DisplayName("已配置 → 不匹配 403，前后空格容忍")
        void enforcesWhenSet() {
            RegistrationGuard g = guard(" open-sesame ", 3);

            assertTrue(g.isInviteCodeRequired());
            assertDoesNotThrow(() -> g.validateInviteCode("open-sesame"));
            assertDoesNotThrow(() -> g.validateInviteCode("  open-sesame  "));
            assertEquals(403, assertThrows(BusinessException.class,
                    () -> g.validateInviteCode("nope")).getCode());
            assertEquals(403, assertThrows(BusinessException.class,
                    () -> g.validateInviteCode(null)).getCode());
        }
    }

    @Nested
    @DisplayName("每日配额")
    class Quota {

        @Test
        @DisplayName("放行时窗口 24h、上限取自配置")
        void allowsWithinQuota() {
            guard("", 3).consumeQuota(request("203.0.113.7"));

            verify(rateLimitService).tryAcquire(eq("register:ip:203.0.113.7"), eq(3), eq(Duration.ofDays(1)));
        }

        @Test
        @DisplayName("超限 → 429，消息带剩余小时数（向上取整）")
        void rejectsWhenExceeded() {
            when(rateLimitService.tryAcquire(anyString(), anyInt(), any())).thenReturn(false);
            when(rateLimitService.remainingSeconds(anyString(), anyLong())).thenReturn(7200L);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> guard("", 3).consumeQuota(request("203.0.113.7")));

            assertEquals(429, ex.getCode());
            assertTrue(ex.getMessage().contains("3 次"), ex.getMessage());
            assertTrue(ex.getMessage().contains("2 小时后"), ex.getMessage());
        }

        @Test
        @DisplayName("IPv6 按 /64 聚合（否则一个 /64 等于无限个地址）")
        void aggregatesIpv6By64() {
            guard("", 3).consumeQuota(request("2408:8207:1234:5678:abcd:ef01:2345:6789"));

            verify(rateLimitService).tryAcquire(eq("register:ip:2408:8207:1234:5678:/64"), anyInt(), any());
        }

        @Test
        @DisplayName("缺 X-Real-IP 时回落 remoteAddr")
        void fallsBackToRemoteAddr() {
            MockHttpServletRequest noHeader = new MockHttpServletRequest();
            noHeader.setRemoteAddr("198.51.100.9");

            guard("", 3).consumeQuota(noHeader);

            verify(rateLimitService).tryAcquire(eq("register:ip:198.51.100.9"), anyInt(), any());
        }

        @Test
        @DisplayName("非法 IP 收敛为 unknown，不污染 Redis 键空间")
        void sanitizesIllegalIp() {
            guard("", 3).consumeQuota(request("bad ip; drop table"));

            verify(rateLimitService).tryAcquire(eq("register:ip:unknown"), anyInt(), any());
        }

        @Test
        @DisplayName("上限配 0 → 整条规则关闭（不碰 Redis）")
        void disabledWhenZero() {
            guard("", 0).consumeQuota(request("203.0.113.7"));

            verifyNoInteractions(rateLimitService);
        }
    }

    @Test
    @DisplayName("ipv6Prefix 对短写法原样兜底")
    void ipv6PrefixHandlesShortForms() {
        assertEquals("::1", RegistrationGuard.ipv6Prefix("::1"));
        assertEquals("2001:db8:0:0:/64", RegistrationGuard.ipv6Prefix("2001:db8:0:0:0:0:0:1"));
    }
}
