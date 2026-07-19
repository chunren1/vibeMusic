package com.vibemusic.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;

/**
 * CORS 跨域配置
 *
 * 允许前端（本地开发 + 生产部署）跨域访问后端 API
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // CORS 白名单：本地开发 + 内网穿透 (cpolar)
        // 生产环境通过环境变量 CORS_ORIGINS 注入实际域名（逗号分隔），默认覆盖本地调试场景
        String envOrigins = System.getenv().getOrDefault("CORS_ORIGINS", "");
        if (!envOrigins.isBlank()) {
            // 生产模式：仅允许 CORS_ORIGINS 中的域名
            config.setAllowedOriginPatterns(Arrays.asList(envOrigins.split(",")));
        } else {
            // 开发模式：允许 localhost 所有端口 + cpolar 隧道域名 + ngrok
            config.setAllowedOriginPatterns(Arrays.asList(
                    "http://localhost:*",
                    "https://localhost:*",
                    "https://*.cpolar.top",
                    "https://*.cpolar.to",
                    "https://*.cpolar.cn",
                    "https://*.ngrok-free.app"
            ));
        }
        config.setAllowCredentials(true);

        // 允许的 HTTP 方法
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));

        // 允许的请求头
        config.setAllowedHeaders(Arrays.asList(
                "Authorization",
                "Content-Type",
                "X-Requested-With",
                "Accept",
                "Origin",
                "Access-Control-Request-Method",
                "Access-Control-Request-Headers"
        ));

        // 预检请求缓存时间（秒）
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }
}
