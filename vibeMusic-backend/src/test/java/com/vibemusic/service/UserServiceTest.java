package com.vibemusic.service;

import com.vibemusic.TransactionalServiceTest;
import com.vibemusic.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.assertj.core.api.Assertions.*;

@DisplayName("UserService 单元测试")
class UserServiceTest extends TransactionalServiceTest {

    @Autowired
    private UserService userService;

    @Nested
    @DisplayName("loadUserByUsername")
    class LoadUserByUsername {
        @Test
        @DisplayName("存在的用户 → 返回 UserDetails")
        void shouldLoadExistingUser() {
            UserDetails details = userService.loadUserByUsername("testuser");
            assertThat(details).isNotNull();
            assertThat(details.getUsername()).isEqualTo("testuser");
        }

        @Test
        @DisplayName("不存在的用户 → 抛出 UsernameNotFoundException")
        void shouldThrowWhenUserNotFound() {
            assertThatThrownBy(() -> userService.loadUserByUsername("nobody"))
                    .isInstanceOf(UsernameNotFoundException.class)
                    .hasMessageContaining("用户不存在");
        }
    }

    @Nested
    @DisplayName("register")
    class Register {
        @Test
        @DisplayName("注册新用户 → 成功")
        void shouldRegisterNewUser() {
            User user = userService.register("newuser", "pass1234", "新人");
            assertThat(user).isNotNull();
            assertThat(user.getId()).isNotNull();
            assertThat(user.getUsername()).isEqualTo("newuser");
            assertThat(user.getNickname()).isEqualTo("新人");
            assertThat(user.getPassword()).isNotEqualTo("pass1234"); // 已加密
        }

        @Test
        @DisplayName("重复用户名 → 抛出异常")
        void shouldThrowWhenDuplicateUsername() {
            userService.register("dupuser", "pass1234", null);
            assertThatThrownBy(() -> userService.register("dupuser", "pass1234", null))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("用户名已存在");
        }

        @Test
        @DisplayName("昵称为空 → 使用用户名作为昵称")
        void shouldUseUsernameAsNicknameWhenNull() {
            User user = userService.register("nonick", "pass1234", null);
            assertThat(user.getNickname()).isEqualTo("nonick");
        }
    }

    @Nested
    @DisplayName("findByUsername")
    class FindByUsername {
        @Test
        @DisplayName("查找存在的用户")
        void shouldFindExistingUser() {
            User user = userService.findByUsername("testuser");
            assertThat(user).isNotNull();
            assertThat(user.getUsername()).isEqualTo("testuser");
        }

        @Test
        @DisplayName("查找不存在的用户 → 抛出异常")
        void shouldThrowWhenNotFound() {
            assertThatThrownBy(() -> userService.findByUsername("ghost"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("用户不存在");
        }
    }

    @Nested
    @DisplayName("changePassword")
    class ChangePassword {
        @Test
        @DisplayName("原密码错误 → 抛出异常")
        void shouldThrowWhenOldPasswordWrong() {
            assertThatThrownBy(() -> userService.changePassword(1L, "wrongpass", "newpass"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("原密码错误");
        }
    }

    @Nested
    @DisplayName("updateProfile")
    class UpdateProfile {
        @Test
        @DisplayName("更新昵称 → 成功")
        void shouldUpdateNickname() {
            User user = userService.updateProfile(1L, "新昵称", null, null);
            assertThat(user.getNickname()).isEqualTo("新昵称");
        }

        @Test
        @DisplayName("不存在的用户 → 抛出异常")
        void shouldThrowWhenUserNotFound() {
            assertThatThrownBy(() -> userService.updateProfile(999L, "x", null, null))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("用户不存在");
        }
    }
}
