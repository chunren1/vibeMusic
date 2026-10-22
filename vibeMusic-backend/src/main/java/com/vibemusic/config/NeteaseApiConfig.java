package com.vibemusic.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 网易云音乐 API 配置
 * Cookie 已集中到 musicapi/config.js 统一管理
 * <p>
 * 超时<b>不在本类配置</b>：原 {@code timeout=10000} 字段全仓无引用（死配置，从未影响任何请求），
 * 已于 2026-10-22 删除，避免出现"看着能调、实际无效"的第二套超时真相源。
 * HTTP 超时现按用途收敛在 {@link RestTemplateConfig}（默认 5s / 搜索 3s / 流 30s / AI 60s）。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "netease.api")
public class NeteaseApiConfig {

    /** API 基础地址（本地 musicapi 网关） */
    private String baseUrl = "http://localhost:3000";

    /** Cookie 已迁移至 musicapi/config.js，不再由后端持有 */
    // private String cookie;
}
