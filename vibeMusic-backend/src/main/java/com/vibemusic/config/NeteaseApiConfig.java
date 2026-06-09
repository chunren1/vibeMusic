package com.vibemusic.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 网易云音乐 API 配置
 * Cookie 已集中到 musicapi/config.js 统一管理
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "netease.api")
public class NeteaseApiConfig {

    /** API 基础地址 */
    private String baseUrl = "http://localhost:3000";

    /** 连接超时 (毫秒) */
    private int timeout = 10000;

    /** Cookie 已迁移至 musicapi/config.js，不再由后端持有 */
    // private String cookie;
}
