package com.vibemusic.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vibemusic.TransactionalServiceTest;
import com.vibemusic.common.exception.BusinessException;
import com.vibemusic.entity.User;
import com.vibemusic.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * UserService 网易云 per-user Cookie 持久化测试
 * <p>
 * CookieCryptoService 构造时 fail-fast（COOKIE_ENC_KEY 缺失即炸），
 * 此处经 @TestPropertySource 注入仅测试有效的密钥，主代码不加任何默认值。
 */
@TestPropertySource(properties = "COOKIE_ENC_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@DisplayName("UserService 网易云 Cookie 持久化测试")
class UserNeteaseCookieServiceTest extends TransactionalServiceTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    private static final String COOKIE = "MUSIC_U=abc123def456; __remember_me=true; NMTID=xyz";

    private static void assertBadRequest(Throwable t) {
        assertThat(t).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) t).getCode()).isEqualTo(400);
    }

    @Nested
    @DisplayName("save → status → resolve → clear 往返")
    class RoundTrip {
        @Test
        @DisplayName("保存后 has=true、解密还原原文、清除后 has=false")
        void shouldRoundTripAndClear() {
            assertThat(userService.getNeteaseCookieStatus(1L).has()).isFalse();
            assertThat(userService.resolveNeteaseCookie(1L)).isEmpty();

            userService.saveNeteaseCookie(1L, COOKIE);

            UserService.NeteaseCookieStatus status = userService.getNeteaseCookieStatus(1L);
            assertThat(status.has()).isTrue();
            assertThat(status.valid()).isNull();
            assertThat(status.updatedAt()).isNotNull();

            Optional<String> resolved = userService.resolveNeteaseCookie(1L);
            assertThat(resolved).hasValue(COOKIE);

            User stored = userMapper.selectById(1L);
            assertThat(stored.getNeteaseCookieEnc()).isNotNull().doesNotContain("abc123def456");
            assertThat(stored.getNeteaseCookieIv()).isNotNull().doesNotContain("abc123def456");

            userService.clearNeteaseCookie(1L);

            UserService.NeteaseCookieStatus cleared = userService.getNeteaseCookieStatus(1L);
            assertThat(cleared.has()).isFalse();
            assertThat(cleared.valid()).isNull();
            assertThat(cleared.updatedAt()).isNull();
            assertThat(userService.resolveNeteaseCookie(1L)).isEmpty();
        }

        @Test
        @DisplayName("重复保存覆盖旧值且 valid 重置为未知")
        void shouldOverwriteAndResetValid() {
            userService.saveNeteaseCookie(1L, COOKIE);
            userMapper.update(null, Wrappers.<User>lambdaUpdate()
                    .eq(User::getId, 1L)
                    .set(User::getNeteaseCookieValid, true));
            assertThat(userService.getNeteaseCookieStatus(1L).valid()).isTrue();

            String newer = "MUSIC_U=newervalue789; NMTID=aaa";
            userService.saveNeteaseCookie(1L, newer);

            assertThat(userService.getNeteaseCookieStatus(1L).valid()).isNull();
            assertThat(userService.resolveNeteaseCookie(1L)).hasValue(newer);
        }
    }

    @Nested
    @DisplayName("多用户隔离")
    class Isolation {
        @Test
        @DisplayName("用户之间 Cookie 互不可见，清除互不影响")
        void shouldIsolateAcrossUsers() {
            User other = userService.register("cookieuser2", "pass1234", null);
            String otherCookie = "MUSIC_U=otheruser999; NMTID=bbb";

            userService.saveNeteaseCookie(1L, COOKIE);
            assertThat(userService.getNeteaseCookieStatus(other.getId()).has()).isFalse();
            assertThat(userService.resolveNeteaseCookie(other.getId())).isEmpty();

            userService.saveNeteaseCookie(other.getId(), otherCookie);
            assertThat(userService.resolveNeteaseCookie(1L)).hasValue(COOKIE);
            assertThat(userService.resolveNeteaseCookie(other.getId())).hasValue(otherCookie);

            userService.clearNeteaseCookie(1L);
            assertThat(userService.getNeteaseCookieStatus(1L).has()).isFalse();
            assertThat(userService.getNeteaseCookieStatus(other.getId()).has()).isTrue();
            assertThat(userService.resolveNeteaseCookie(other.getId())).hasValue(otherCookie);
        }
    }

    @Nested
    @DisplayName("非法输入拒绝")
    class Rejection {
        @Test
        @DisplayName("空白 Cookie → 400")
        void shouldRejectBlank() {
            assertThatThrownBy(() -> userService.saveNeteaseCookie(1L, null))
                    .satisfies(UserNeteaseCookieServiceTest::assertBadRequest);
            assertThatThrownBy(() -> userService.saveNeteaseCookie(1L, ""))
                    .satisfies(UserNeteaseCookieServiceTest::assertBadRequest);
            assertThatThrownBy(() -> userService.saveNeteaseCookie(1L, "   "))
                    .satisfies(UserNeteaseCookieServiceTest::assertBadRequest);
        }

        @Test
        @DisplayName("缺 MUSIC_U → 400")
        void shouldRejectWithoutMusicU() {
            assertThatThrownBy(() -> userService.saveNeteaseCookie(1L, "FOO=bar; BAZ=qux"))
                    .satisfies(UserNeteaseCookieServiceTest::assertBadRequest);
        }

        @Test
        @DisplayName("超长 Cookie（>8KB）→ 400")
        void shouldRejectOversized() {
            String oversized = "MUSIC_U=" + "x".repeat(9000);
            assertThatThrownBy(() -> userService.saveNeteaseCookie(1L, oversized))
                    .satisfies(UserNeteaseCookieServiceTest::assertBadRequest);
            assertThat(userService.getNeteaseCookieStatus(1L).has()).isFalse();
        }
    }

    @Nested
    @DisplayName("不存在的用户")
    class UnknownUser {
        @Test
        @DisplayName("save/clear/status/resolve 均 → 404")
        void shouldThrowWhenUserNotFound() {
            assertThatThrownBy(() -> userService.saveNeteaseCookie(999L, COOKIE))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("用户不存在");
            assertThatThrownBy(() -> userService.clearNeteaseCookie(999L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("用户不存在");
            assertThatThrownBy(() -> userService.getNeteaseCookieStatus(999L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("用户不存在");
            assertThatThrownBy(() -> userService.resolveNeteaseCookie(999L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("用户不存在");
        }
    }
}
