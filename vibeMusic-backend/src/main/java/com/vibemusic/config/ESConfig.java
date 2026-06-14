package com.vibemusic.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchConfiguration;

/**
 * 显式 ES 配置，覆盖 Spring Boot 4 自动装配的默认 localhost:9200
 */
@Configuration
public class ESConfig extends ElasticsearchConfiguration {

    @Value("${spring.elasticsearch.uris:http://localhost:9201}")
    private String esUris;

    @Override
    public ClientConfiguration clientConfiguration() {
        return ClientConfiguration.builder()
                .connectedTo(parseHostAndPort())
                .build();
    }

    private String parseHostAndPort() {
        // 去掉协议前缀和端口后缀
        String host = esUris
                .replace("http://", "")
                .replace("https://", "");
        if (!host.contains(":")) host = host + ":9200";
        return host;
    }
}
