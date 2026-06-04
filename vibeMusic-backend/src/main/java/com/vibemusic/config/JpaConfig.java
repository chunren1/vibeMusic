package com.vibemusic.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA 审计配置
 *
 * 启用后，实体类可使用 @CreatedDate、@LastModifiedDate 自动填充时间字段
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
