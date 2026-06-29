package com.vibemusic.security;

import com.vibemusic.common.utils.JwtUtils;
import com.vibemusic.entity.User;
import com.vibemusic.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;

    private static final Duration USER_CACHE_TTL = Duration.ofMinutes(5);
    private static final String USER_CACHE_PREFIX = "user:auth:";
    private static final String TOKEN_BLACKLIST_PREFIX = "token:blacklist:";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String token = extractToken(request);

        if (StringUtils.hasText(token) && jwtUtils.validateToken(token)) {
            // 检查 token 黑名单（已登出的 token 不可用）
            if (isBlacklisted(token)) {
                log.debug("JWT 已被加入黑名单，跳过认证");
                filterChain.doFilter(request, response);
                return;
            }
            // 拒绝 refresh token 访问普通接口
            if (jwtUtils.isRefreshToken(token)) {
                log.debug("拒绝 refresh token 访问业务接口");
                filterChain.doFilter(request, response);
                return;
            }
            String userIdStr = jwtUtils.getUserIdFromToken(token);
            try {
                Long userId = Long.parseLong(userIdStr);
                User user = getUserWithCache(userId);
                if (user != null) {
                    CustomUserDetails userDetails = new CustomUserDetails(user);
                    UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (Exception e) {
                log.warn("JWT auth failed: {}", e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }

    /** 检查 token 是否在黑名单中 */
    private boolean isBlacklisted(String token) {
        try {
            String key = TOKEN_BLACKLIST_PREFIX + token.hashCode();
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
        } catch (Exception e) {
            return false; // Redis 不可用时放行，不阻塞正常请求
        }
    }

    /**
     * 从 Redis 缓存获取用户，未命中则查 DB 并回填缓存
     */
    private User getUserWithCache(Long userId) {
        String cacheKey = USER_CACHE_PREFIX + userId;
        try {
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return objectMapper.readValue(cached, User.class);
            }
        } catch (Exception e) {
            log.debug("Redis 用户缓存读取失败，回退 DB: {}", e.getMessage());
        }
        User user = userMapper.selectById(userId);
        if (user != null) {
            try {
                stringRedisTemplate.opsForValue().set(cacheKey,
                    objectMapper.writeValueAsString(user), USER_CACHE_TTL);
            } catch (Exception e) {
                log.debug("Redis 用户缓存写入失败: {}", e.getMessage());
            }
        }
        return user;
    }

    private String extractToken(HttpServletRequest request) {
        // 优先从 Authorization header 读取
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        // 降级从 httpOnly cookie 读取（XSS 防护）
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if ("VIBE_TOKEN".equals(c.getName())) return c.getValue();
            }
        }
        return null;
    }
}
