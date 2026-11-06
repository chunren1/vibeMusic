package com.vibemusic.common.utils;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类（双 Token：access 15min + refresh 7d）
 */
@Slf4j
@Component
public class JwtUtils {

    private final SecretKey secretKey;
    private final long accessExpiration;
    private final long refreshExpiration;

    public JwtUtils(@Value("${jwt.secret}") String secret,
                    @Value("${jwt.access-expiration}") long accessExpiration,
                    @Value("${jwt.refresh-expiration}") long refreshExpiration) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpiration = accessExpiration;
        this.refreshExpiration = refreshExpiration;
    }

    // ==================== 双 Token 生成 ====================

    public String generateAccessToken(String userId) {
        return buildToken(userId, accessExpiration, "access");
    }

    public String generateRefreshToken(String userId) {
        return buildToken(userId, refreshExpiration, "refresh");
    }

    /** 兼容旧调用：生成 access token */
    public String generateToken(String userId) {
        return generateAccessToken(userId);
    }

    private String buildToken(String userId, long expiration, String type) {
        Date now = new Date();
        return Jwts.builder()
                .subject(userId)
                .claim("type", type)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiration))
                .signWith(secretKey)
                .compact();
    }

    // ==================== 解析 & 校验 ====================

    public String getUserIdFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    public long getExpirationFromToken(String token) {
        return parseClaims(token).getExpiration().getTime();
    }

    public boolean isRefreshToken(String token) {
        try {
            return "refresh".equals(parseClaims(token).get("type"));
        } catch (Exception e) {
            return false;
        }
    }

    public boolean validateToken(String token) {
        if (token == null || token.isBlank()) return false;
        try {
            parseClaims(token);
            return true;
        } catch (Exception e) {
            log.warn("JWT 校验失败: {}", e.getMessage());
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    // ==================== Token 提取 ====================

    /**
     * 新 access Cookie 名（2026-11-06 起）：{@code __Host-} 前缀由浏览器强制
     * Secure + Path=/ + 无 Domain，防止子域覆盖写入。
     */
    public static final String ACCESS_COOKIE_NAME = "__Host-VIBE_TOKEN";

    /**
     * 旧 access Cookie 名。迁移期双读：旧 App/旧页面手里的 cookie 在自身
     * Max-Age（15min）到点后自然消失，这期间服务端两个名字都认，不会把
     * 已登录用户踢下线；明文 http 调试环境也仍写这个名字。
     */
    public static final String LEGACY_ACCESS_COOKIE_NAME = "VIBE_TOKEN";

    /** 从请求中提取 access token：Authorization: Bearer xxx → Cookie（新名优先，旧名回落）。 */
    public static String extractAccessToken(HttpServletRequest request) {
        String token = extractTokenFromRequest(request, ACCESS_COOKIE_NAME);
        if (token != null) return token;
        return extractTokenFromRequest(request, LEGACY_ACCESS_COOKIE_NAME);
    }

    /** 从请求中提取 token：Authorization: Bearer xxx → Cookie {@code cookieName} */
    public static String extractTokenFromRequest(HttpServletRequest request, String cookieName) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) return header.substring(7);
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if (cookieName.equals(c.getName())) return c.getValue();
            }
        }
        return null;
    }

    // ==================== Getter ====================

    public long getAccessExpiration() { return accessExpiration; }
    public long getRefreshExpiration() { return refreshExpiration; }
}
