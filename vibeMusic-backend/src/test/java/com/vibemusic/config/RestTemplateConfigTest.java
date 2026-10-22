package com.vibemusic.config;

import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 超时预算对齐回归测试（2026-10-22）。
 * <p>
 * 断言三件事：
 * <ol>
 *   <li>全局/搜索客户端的具体超时取值（45s → 5s，连接 10s → 3s，取连接保持 3s）；</li>
 *   <li>搜索结果按 {@link com.vibemusic.service.SongSearchService} 的 4s / 取链 8s 预算
 *       对齐（预算关系见 {@code TimeoutBudgetAlignmentTest}）；</li>
 *   <li>超时真的生效（本地慢服务实测），且多客户端共用连接池——关闭一个客户端
 *       不会关闭共用池（否则另一条链路会突然全部失败）。</li>
 * </ol>
 */
class RestTemplateConfigTest {

    @Test
    void globalTimeoutsAreFiveSecondsResponseAndThreeSecondsConnect() {
        RequestConfig requestConfig = RestTemplateConfig.defaultRequestConfig();
        assertEquals(5, requestConfig.getResponseTimeout().toSeconds(),
                "响应超时必须收敛到 5s（原 45s 会让 Future.cancel 之后仍占线程/连接几十秒）");
        assertEquals(3, requestConfig.getConnectionRequestTimeout().toSeconds(),
                "从连接池取连接超时保持 3s（池 200/路由 50，仅在真过载时阻塞）");
        assertEquals(3, RestTemplateConfig.defaultConnectionConfig().getConnectTimeout().toSeconds(),
                "建连超时从 10s 降到 3s：本地网关 <1ms，3s 足够覆盖抖动");
    }

    @Test
    void eachPurposeClientHasItsOwnResponseTimeout() {
        assertEquals(Duration.ofSeconds(3), RestTemplateConfig.SEARCH_RESPONSE_TIMEOUT);
        assertEquals(Duration.ofSeconds(30), RestTemplateConfig.STREAM_RESPONSE_TIMEOUT);
        assertEquals(Duration.ofSeconds(60), RestTemplateConfig.AI_RESPONSE_TIMEOUT);
        assertEquals(Duration.ofSeconds(5), RestTemplateConfig.RESPONSE_TIMEOUT);
    }

    @Test
    void responseTimeoutActuallyAbortsSlowUpstreamButSharedPoolSurvivesClientClose() throws Exception {
        ExecutorService serverPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "slow-http-" + r.hashCode());
            t.setDaemon(true);
            return t;
        });
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverPool);
        // 慢上游：1200ms 后才回第一个字节
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(1200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        RestTemplateConfig config = new RestTemplateConfig();
        PoolingHttpClientConnectionManager cm = config.connectionManager();
        CloseableHttpClient fastFailClient = RestTemplateConfig.buildClient(cm, Duration.ofMillis(250));
        CloseableHttpClient patientClient = RestTemplateConfig.buildClient(cm, Duration.ofSeconds(3));
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/slow";
        try {
            RestTemplate fastFail = new RestTemplate(new HttpComponentsClientHttpRequestFactory(fastFailClient));
            long start = System.currentTimeMillis();
            assertThrows(ResourceAccessException.class, () -> fastFail.getForObject(url, String.class),
                    "250ms 响应超时必须中断慢上游（等价于搜索 3s 内层超时对慢源的快失败）");
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed < 1100, "应在内层超时附近失败，实测 " + elapsed + "ms");

            // 关闭一个客户端后，共享池仍可用 → 另一用途的请求不受影响
            fastFailClient.close();
            RestTemplate patient = new RestTemplate(new HttpComponentsClientHttpRequestFactory(patientClient));
            assertEquals("ok", patient.getForObject(url, String.class));
        } finally {
            patientClient.close();
            cm.close();
            server.stop(0);
            serverPool.shutdownNow();
        }
    }

    @Test
    void clientsShareSingleConnectionPoolAndStayDistinct() {
        RestTemplateConfig config = new RestTemplateConfig();
        PoolingHttpClientConnectionManager cm = config.connectionManager();
        try (CloseableHttpClient def = config.httpClient(cm);
             CloseableHttpClient search = config.searchHttpClient(cm);
             CloseableHttpClient stream = config.streamHttpClient(cm);
             CloseableHttpClient ai = config.aiHttpClient(cm)) {
            assertNotNull(def);
            assertNotSame(def, search);
            assertNotSame(search, stream);
            assertNotSame(stream, ai);
            assertEquals(200, cm.getMaxTotal());
        } catch (Exception e) {
            throw new AssertionError("客户端构建失败", e);
        } finally {
            cm.close();
        }
    }
}
