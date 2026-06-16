package com.vibemusic;

import com.vibemusic.entity.User;
import com.vibemusic.security.CustomUserDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

/**
 * 测试基类：加载 Spring 上下文，使用 H2 内存数据库 + test profile
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Sql(scripts = "/schema-test.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
public abstract class BaseTest {

    /** 模拟已登录用户（userId=1, testuser） */
    protected void loginAsTestUser() {
        User user = User.builder().id(1L).username("testuser").password("encoded").enabled(true).build();
        CustomUserDetails details = new CustomUserDetails(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    /** 清除认证状态 */
    protected void clearAuth() {
        SecurityContextHolder.clearContext();
    }
}
