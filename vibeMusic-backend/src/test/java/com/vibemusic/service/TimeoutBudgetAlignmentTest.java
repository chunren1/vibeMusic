package com.vibemusic.service;

import com.vibemusic.config.NeteaseApiConfig;
import com.vibemusic.config.RestTemplateConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 外层业务预算 vs 内层 HTTP 超时的自洽性测试（2026-10-22）。
 * <p>
 * 背景：{@code Future.cancel} 只能标记 future，<b>中断不了已发出的阻塞式 HTTP 读</b>，
 * 因此"内层超时必须小于外层预算"是硬约束——否则一次慢上游就能吃穿整条链路，
 * 并让线程/连接在预算之外继续被占用。
 * <p>
 * 预算关系（单位 ms）：
 * <pre>
 *   搜索 fanout 4000  >  搜索 HTTP 3000（留 1s 调度/合并）  >  QQ 子预算 2000
 *   取链总预算 8000   >  默认 HTTP 5000（取链/降级链）
 *   音频流读超时 30000 / AI 60000：各自独立端点，不参与上面两条预算
 * </pre>
 */
class TimeoutBudgetAlignmentTest {

    /** 搜索内层（3s）严格小于 fanout 外层（4s）。 */
    @Test
    void searchInnerTimeoutIsSmallerThanSearchFanoutBudget() {
        long fanoutBudgetMs = SongSearchService.SEARCH_TIMEOUT_SEC * 1000L;
        long innerTimeoutMs = RestTemplateConfig.SEARCH_RESPONSE_TIMEOUT.toMillis();
        assertTrue(innerTimeoutMs < fanoutBudgetMs,
                "搜索内层超时 " + innerTimeoutMs + "ms 必须 < fanout 预算 " + fanoutBudgetMs + "ms");
        // 建连同样计入内层：3s 建连 + 3s 读已是最坏情况，靠 fanout Future 兜底截断
        assertTrue(RestTemplateConfig.CONNECT_TIMEOUT.toMillis() < fanoutBudgetMs);
    }

    /** 取链内层（5s）严格小于取链外层（8s）。 */
    @Test
    void playInnerTimeoutIsSmallerThanPlayBudget() {
        long innerTimeoutMs = RestTemplateConfig.RESPONSE_TIMEOUT.toMillis();
        assertTrue(innerTimeoutMs < SongPlayService.PLAY_BUDGET_MS,
                "取链内层超时 " + innerTimeoutMs + "ms 必须 < 取链预算 " + SongPlayService.PLAY_BUDGET_MS + "ms");
    }

    /** QQ 长尾子预算更短（2s），短路时 socket 最多再存活到内层 3s，有界不失控。 */
    @Test
    void qqTailBudgetIsTighterThanSearchBudget() {
        assertEquals(2, SongSearchService.QQ_TIMEOUT_SEC);
        assertTrue(SongSearchService.QQ_TIMEOUT_SEC * 1000L < SongSearchService.SEARCH_TIMEOUT_SEC * 1000L);
        long overshootMs = RestTemplateConfig.SEARCH_RESPONSE_TIMEOUT.toMillis()
                - SongSearchService.QQ_TIMEOUT_SEC * 1000L;
        assertTrue(overshootMs <= 1000,
                "QQ 被 cancel 后 socket 仍在读，超调必须 <=1s（实测 " + overshootMs + "ms），否则等于没设预算");
    }

    /** 流式/AI 的放宽超时不能反过来污染 JSON 主链路。 */
    @Test
    void streamAndAiTimeoutsDoNotLeakIntoJsonBudget() {
        assertTrue(RestTemplateConfig.STREAM_RESPONSE_TIMEOUT.toMillis()
                        > RestTemplateConfig.RESPONSE_TIMEOUT.toMillis(),
                "音频字节流读空闲（客户端背压）天然长于 JSON 调用，必须独立放宽");
        assertTrue(RestTemplateConfig.AI_RESPONSE_TIMEOUT.toMillis()
                        > SongPlayService.PLAY_BUDGET_MS,
                "LLM 非流式补全独立于取链预算，否则 5s 会打断正常生成");
        assertTrue(RestTemplateConfig.RESPONSE_TIMEOUT.toMillis()
                        < SongPlayService.PLAY_BUDGET_MS,
                "放宽流式/AI 时不得回退全局 45s 长尾");
    }

    /**
     * 五个平台 search 必须真的走 3s 搜索客户端——否则"配置了却没接上"等于没改。
     * 默认客户端一旦被调用即失败断言。
     */
    @Test
    void platformSearchesUseDedicatedSearchClient() {
        RestTemplate defaultClient = mock(RestTemplate.class);
        RestTemplate searchClient = mock(RestTemplate.class);
        when(defaultClient.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new AssertionError("平台搜索不得走默认 5s 客户端"));
        when(searchClient.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("code", 200), HttpStatus.OK));

        NeteaseApiService apiService =
                new NeteaseApiService(new NeteaseApiConfig(), defaultClient, RestClient.builder());
        apiService.setSearchRestTemplate(searchClient);

        apiService.searchNetease("晴天", 5);
        apiService.searchQQ("晴天", 5);
        apiService.searchMigu("晴天", 5);
        apiService.searchKugou("晴天", 5);
        apiService.searchBili("晴天", 5);

        verify(searchClient, times(5))
                .exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
        verifyNoInteractions(defaultClient);
    }
}
