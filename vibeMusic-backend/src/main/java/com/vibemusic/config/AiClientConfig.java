package com.vibemusic.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.controller.AssistantClient;
import com.vibemusic.service.AiToolService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * AI 上游调用层的共享 Bean。
 *
 * <p>背景：{@link AssistantClient} 原先只由 AssistantController 在构造器里 new（controller 包内可见）；
 * AI 歌曲推荐（service 包）需要复用同一套 HTTP 调用/超时语义，因此在此暴露为 Bean。
 * AssistantController 仍自行 new 一份，行为与构造参数完全不变。
 *
 * <p>连接池复用 {@code aiRestTemplate}（60s 读超时，见 {@link RestTemplateConfig}），不另建客户端。
 */
@Configuration
public class AiClientConfig {

    @Bean
    public AssistantClient assistantClient(@Qualifier("aiRestTemplate") RestTemplate restTemplate,
                                           ObjectMapper objectMapper,
                                           AiToolService aiToolService,
                                           @Value("${ai.api-url:https://api.deepseek.com/chat/completions}") String apiUrl,
                                           @Value("${ai.api-key:}") String apiKey,
                                           @Value("${ai.model:deepseek-v4-flash}") String aiModel,
                                           @Value("${ai.thinking-mode:disabled}") String thinkingMode) {
        return new AssistantClient(restTemplate, objectMapper, aiToolService,
                apiUrl, apiKey, aiModel, thinkingMode);
    }
}
