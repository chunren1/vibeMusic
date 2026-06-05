package com.vibemusic.config;

import com.vibemusic.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 安全配置
 *
 * 策略：前后端分离 + JWT 无状态认证
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * 安全过滤器链
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 禁用 CSRF（前后端分离 + JWT，不需要 CSRF）
            .csrf(AbstractHttpConfigurer::disable)

            // 无状态会话（不用 HttpSession，JWT 自包含）
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // 路径鉴权规则
            .authorizeHttpRequests(auth -> auth
                // 公开接口（无需登录）
                .requestMatchers(
                    "/api/auth/**",                 // 登录、注册
                    "/swagger-ui/**",               // API 文档
                    "/v3/api-docs/**",
                    "/doc.html",
                    "/favicon.ico"
                ).permitAll()

                // 歌曲相关 GET 接口公开（未登录可浏览）
                .requestMatchers(HttpMethod.GET,
                    "/api/songs/**",
                    "/api/download/**",
                    "/api/playlists/public/**"
                ).permitAll()

                // 下载接口（登录后可下载）
                .requestMatchers(HttpMethod.POST,
                    "/api/download/**"
                ).permitAll()

                // 收藏接口（无需登录）
                .requestMatchers("/api/favorites/**").permitAll()

                // 歌单接口（无需登录）
                .requestMatchers("/api/playlists/**").permitAll()

                // 静态资源
                .requestMatchers(
                    "/static/**",
                    "/public/**"
                ).permitAll()

                // 其他所有请求需要认证
                .anyRequest().authenticated()
            )

            // JWT 过滤器（在 UsernamePasswordAuthenticationFilter 之前）
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 密码编码器（BCrypt）
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 认证管理器（供登录接口使用）
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
