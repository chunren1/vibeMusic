package com.vibemusic.config;

import com.vibemusic.common.filter.RateLimitFilter;
import com.vibemusic.security.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.service.RateLimitService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
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

import java.util.Map;

/**
 * Spring Security 安全配置
 * 策略：前后端分离 + JWT 无状态认证
 */
@Slf4j
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, RateLimitFilter rateLimitFilter) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // CORS 预检请求放行
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // 公开接口
                .requestMatchers(
                    "/api/auth/**",
                    // 前端错误上报：游客态崩溃同样要能报，接口自带体积上限 + 每身份限流 + 二次脱敏
                    "/api/client-errors",
                    "/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html", "/favicon.ico",
                    "/uploads/**",
                    "/actuator/**"
                ).permitAll()

                // 歌曲搜索/浏览 GET 公开
                .requestMatchers(HttpMethod.GET,
                    "/api/songs/**",
                    "/api/recommend/**"
                ).permitAll()

                // 歌单详情 & 歌曲查看 & 推荐歌单公开（未登录首页可展示封面）
                .requestMatchers(HttpMethod.GET,
                    "/api/playlists/songs",
                    "/api/playlists/detail",
                    "/api/playlists/recommend"
                ).permitAll()

                // AI 助手公开访问（Controller 层有速率限制保护）
                .requestMatchers("/api/assistant/**").permitAll()

                // 图片代理公开（封面图无 JWT）
                .requestMatchers(HttpMethod.GET,
                    "/api/image-proxy"
                ).permitAll()

                // 下载文件流 & 缓存检查公开（浏览器 <a> 下载不带 JWT）
                .requestMatchers(HttpMethod.GET,
                    "/api/download/file/**",
                    "/api/download/check/**"
                ).permitAll()

                // 其余请求需认证（收藏、歌单管理、播放记录等由 Controller 层二次校验兜底）
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            // 限流必须排在 JWT 之后：身份解析依赖 SecurityContext 里已认证的用户
            .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class)
            // 未认证 → 返回 JSON 401（而非默认 HTML 跳转）
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    objectMapper.writeValue(response.getOutputStream(),
                        Map.of("code", 401, "message", "登录已过期，请重新登录"));
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    log.warn("[Security] 403 拒绝: {} {} (auth={})",
                            request.getMethod(), request.getRequestURI(),
                            request.getUserPrincipal());
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    objectMapper.writeValue(response.getOutputStream(),
                        Map.of("code", 403, "message", "无权限访问，请重新登录"));
                })
            );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 贵端点限流器（P0-3）。
     * <p>手工构造而不是给 {@link RateLimitFilter} 加 {@code @Component}：见下面的注册禁用说明。</p>
     */
    @Bean
    public RateLimitFilter rateLimitFilter(RateLimitService rateLimitService, RateLimitProperties properties) {
        return new RateLimitFilter(rateLimitService, properties, objectMapper);
    }

    /**
     * 关键：把限流器从“普通 Servlet 过滤器链”里摘掉。
     * <p>Boot 会把容器里每个 {@link jakarta.servlet.Filter} Bean 自动注册一遍；而本限流器只应作为
     * Spring Security 链的一环、紧跟在 JWT 之后执行。不禁用的话同一次请求会被计数两次
     * （额度直接腰斩），且第一次执行时 SecurityContext 还是空的（身份退化成 IP）。</p>
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
