package com.vibemusic.config;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * HTTP 客户端连接池统一配置
 * <p>
 * 所有 HTTP 客户端共享同一个 Apache HttpClient 5 连接池，避免各自创建独立连接导致 socket 耗尽。
 * <p>
 * <b>超时预算对齐（2026-10-22）</b>：此前全局响应超时 45s，而业务预算是搜索 4s / 取链 8s，
 * 且 {@code Future.cancel} 只能标记 future、<b>无法中断已发出的阻塞式 HTTP 读</b>——慢上游会
 * 把搜索/取链线程/连接占住最长 45s（连接池 200 条，几个慢请求就能拖垮整条链路）。
 * 因此按<b>用途</b>拆分客户端，统一"内层必须小于外层预算"：
 * <ul>
 *   <li>{@code httpClient}（默认 5s 响应）：上游元数据 JSON，如取链 8s 预算内的各平台取链；</li>
 *   <li>{@code searchHttpClient}（3s 响应）：五平台搜索扇出，外层 fanout 预算 4s；</li>
 *   <li>{@code streamHttpClient}（30s 读空闲）：音频字节流，受客户端背压，读间隔天然很长；</li>
 *   <li>{@code aiHttpClient}（60s 响应）：LLM 非流式补全，单次生成常 10s+。</li>
 * </ul>
 * 四者共用同一个连接池：慢请求只影响自己的 future，不再靠 45s 的"长尾保险丝"兜底。
 * 时间线可用性说明：把 JSON 默认超时从 45s 收到 5s 只影响"已卡死"的请求；正常上游
 * （本地 musicapi 网关 + 各平台，p99 < 2s）不会触碰该阈值。
 */
@Configuration
public class RestTemplateConfig {

    /** 建连超时：本地 musicapi 网关实测 < 1ms，3s 足以覆盖 DNS/路由抖动。 */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /**
     * 默认响应（socket read）超时，用于上游元数据/取链 JSON。
     * 必须小于取链外层预算 {@link com.vibemusic.service.SongPlayService#PLAY_BUDGET_MS}（8s）。
     */
    public static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 从连接池取连接的超时，保持 3s：池 200/路由 50，扇出时同一网关仅 5 条并发，
     * 只在真正过载时阻塞；降到 1s 会让突发流量直接误判失败，省下的 2s 也只出现在
     * 过载这一极端路径上（该路径本就是快失败语义），换不来可用性收益。
     */
    public static final Duration CONNECTION_REQUEST_TIMEOUT = Duration.ofSeconds(3);

    /**
     * 搜索扇出专用响应超时，必须严格小于
     * {@link com.vibemusic.service.SongSearchService#SEARCH_TIMEOUT_SEC}（4s）。
     * 取 3s 留 1s 给线程池调度 + 结果合并，且覆盖健康上游（实测 < 2s）的长尾。
     */
    public static final Duration SEARCH_RESPONSE_TIMEOUT = Duration.ofSeconds(3);

    /**
     * 音频字节流读超时（30s）：流式代理是 {@code InputStream -> OutputStream} 拷贝，
     * 会被手机端背压节流（播放缓冲/手动暂停），CDN socket 出现 > 5s 的读空闲是正常现象。
     * 沿用 5s 会在播放中途抛 SocketTimeoutException，故单独放宽；30s 只用于判定"链路已死"。
     */
    public static final Duration STREAM_RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    /** LLM 非流式补全响应超时：DeepSeek 带 thinking 的 800 token 生成常超 5s，独立放宽到 60s。 */
    public static final Duration AI_RESPONSE_TIMEOUT = Duration.ofSeconds(60);

    /** 建连配置（所有用途一致：建连慢=网络坏，无需按用途区分）。 */
    static ConnectionConfig defaultConnectionConfig() {
        return ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .build();
    }

    /** 单一事实源的请求配置工厂：连接池取用超时 + 指定响应超时。 */
    static RequestConfig requestConfig(Duration responseTimeout) {
        return RequestConfig.custom()
                .setResponseTimeout(Timeout.of(responseTimeout.toMillis(), TimeUnit.MILLISECONDS))
                .setConnectionRequestTimeout(Timeout.of(CONNECTION_REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .build();
    }

    /** 默认客户端使用的请求配置（供测试断言）。 */
    static RequestConfig defaultRequestConfig() {
        return requestConfig(RESPONSE_TIMEOUT);
    }

    /**
     * 连接池独立成 bean：多个不同超时的客户端共享同一池（每个 client 仍是独立实例，
     * 各自持有 default RequestConfig），池的生命周期只由本 bean 负责。
     */
    @Bean(destroyMethod = "close")
    public PoolingHttpClientConnectionManager connectionManager() {
        return PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(defaultConnectionConfig())
                .setMaxConnTotal(200)
                .setMaxConnPerRoute(50)
                .build();
    }

    /** 按响应超时构建客户端；共享池 + 共享标志，client.close() 不会关掉共用连接池。 */
    static CloseableHttpClient buildClient(PoolingHttpClientConnectionManager cm, Duration responseTimeout) {
        return HttpClients.custom()
                .setConnectionManager(cm)
                .setConnectionManagerShared(true)
                .setDefaultRequestConfig(requestConfig(responseTimeout))
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(60))
                .build();
    }

    /** 默认客户端：上游元数据/取链 JSON（5s）。 */
    @Bean(destroyMethod = "close")
    public CloseableHttpClient httpClient(PoolingHttpClientConnectionManager connectionManager) {
        return buildClient(connectionManager, RESPONSE_TIMEOUT);
    }

    /** 搜索扇出客户端（3s，< 搜索 4s 预算）。 */
    @Bean(destroyMethod = "close")
    public CloseableHttpClient searchHttpClient(PoolingHttpClientConnectionManager connectionManager) {
        return buildClient(connectionManager, SEARCH_RESPONSE_TIMEOUT);
    }

    /** 音频字节流客户端（30s 读空闲）。 */
    @Bean(destroyMethod = "close")
    public CloseableHttpClient streamHttpClient(PoolingHttpClientConnectionManager connectionManager) {
        return buildClient(connectionManager, STREAM_RESPONSE_TIMEOUT);
    }

    /** LLM 客户端（60s）。 */
    @Bean(destroyMethod = "close")
    public CloseableHttpClient aiHttpClient(PoolingHttpClientConnectionManager connectionManager) {
        return buildClient(connectionManager, AI_RESPONSE_TIMEOUT);
    }

    /** 默认 RestTemplate（5s），其余 RestTemplate 注入点请显式 @Qualifier。 */
    @Bean
    @Primary
    public RestTemplate restTemplate(CloseableHttpClient httpClient) {
        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
    }

    /** 搜索专用 RestTemplate（3s）：仅用于五平台 search 调用。 */
    @Bean
    public RestTemplate searchRestTemplate(CloseableHttpClient searchHttpClient) {
        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(searchHttpClient));
    }

    /** AI 专用 RestTemplate（60s）：LLM 非流式补全。 */
    @Bean
    public RestTemplate aiRestTemplate(CloseableHttpClient aiHttpClient) {
        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(aiHttpClient));
    }

    /** 默认 RestClient 构建器（5s）。 */
    @Bean
    @Primary
    public RestClient.Builder restClientBuilder(CloseableHttpClient httpClient) {
        return RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient));
    }

    /** 音频流专用 RestClient（30s 读空闲）：StreamController / 流式下载使用。 */
    @Bean
    public RestClient streamRestClient(CloseableHttpClient streamHttpClient) {
        return RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(streamHttpClient))
                .build();
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
