package com.vibemusic.common.utils;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
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
        } catch (JwtException e) {
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

    // ==================== Getter ====================

    public long getAccessExpiration() { return accessExpiration; }
    public long getRefreshExpiration() { return refreshExpiration; }
}
