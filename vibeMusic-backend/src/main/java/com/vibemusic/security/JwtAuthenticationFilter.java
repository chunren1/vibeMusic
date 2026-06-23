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

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String token = extractToken(request);

        if (StringUtils.hasText(token) && jwtUtils.validateToken(token)) {
            String userIdStr = jwtUtils.getUserIdFromToken(token);
            try {
                Long userId = Long.parseLong(userIdStr);
                // Redis 缓存用户，避免每个请求都查 DB（TTL 5min）
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
