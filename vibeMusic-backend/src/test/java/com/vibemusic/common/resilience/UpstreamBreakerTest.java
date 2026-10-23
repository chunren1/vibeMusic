package com.vibemusic.common.resilience;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 网关整体熔断器状态机测试（假时钟，全程不 sleep）。
 */
@DisplayName("UpstreamBreaker 网关整体熔断器")
class UpstreamBreakerTest {

    private static final int THRESHOLD = 3;
    private static final long WINDOW_MS = 30_000L;

    private AtomicLong now;
    private UpstreamBreaker breaker;

    @BeforeEach
    void setUp() {
        now = new AtomicLong(1_000_000L);
        breaker = new UpstreamBreaker(THRESHOLD, WINDOW_MS, now::get);
    }

    private void failTimes(int n) {
        for (int i = 0; i < n; i++) breaker.recordAttemptFailure(true);
    }

    @Nested
    @DisplayName("闭路 → 开路")
    class OpenCircuit {

        @Test
        @DisplayName("连续传输失败未达阈值：保持闭路并放行")
        void belowThresholdStaysClosed() {
            failTimes(THRESHOLD - 1);

            assertFalse(breaker.isOpen());
            assertTrue(breaker.allow());
            assertEquals(THRESHOLD - 1, breaker.consecutiveFailures());
        }

        @Test
        @DisplayName("连续传输失败达阈值：开路，allow() 全部返回 false")
        void reachingThresholdOpens() {
            failTimes(THRESHOLD);

            assertTrue(breaker.isOpen());
            assertFalse(breaker.allow());
            assertFalse(breaker.allow());
            assertEquals(WINDOW_MS, breaker.remainingOpenMs());
        }

        @Test
        @DisplayName("非传输失败（4xx/解析）不计数，不清零也不开路")
        void nonTransportFailuresDoNotCount() {
            failTimes(2);

            breaker.recordAttemptFailure(false);
            breaker.recordAttemptFailure(false);

            assertFalse(breaker.isOpen());
            assertEquals(2, breaker.consecutiveFailures());
            assertTrue(breaker.allow());
        }

        @Test
        @DisplayName("成功一次清零连续失败数，后续需重新累计到阈值才开路")
        void successResetsFailureCount() {
            failTimes(THRESHOLD - 1);
            breaker.recordSuccess();

            assertEquals(0, breaker.consecutiveFailures());
            assertFalse(breaker.isOpen());

            failTimes(THRESHOLD - 1);
            assertFalse(breaker.isOpen(), "清零后剩余 2 次失败不应开路");
        }
    }

    @Nested
    @DisplayName("开路 → 半开 → 闭路/重开")
    class HalfOpen {

        @Test
        @DisplayName("窗口未到期：继续全部拒绝")
        void beforeWindowExpiryDenies() {
            failTimes(THRESHOLD);
            now.addAndGet(WINDOW_MS - 1);

            assertFalse(breaker.allow());
        }

        @Test
        @DisplayName("窗口到期：只放行 1 个探针，其余并发请求继续被拒")
        void windowExpiryAllowsSingleProbe() {
            failTimes(THRESHOLD);
            now.addAndGet(WINDOW_MS);

            assertTrue(breaker.allow(), "第一个请求应作为半开探针被放行");
            assertFalse(breaker.allow(), "其余请求在探针进行中必须继续跳过");
            assertFalse(breaker.allow());
        }

        @Test
        @DisplayName("探针成功：立即闭路并清零")
        void probeSuccessClosesCircuit() {
            failTimes(THRESHOLD);
            now.addAndGet(WINDOW_MS);
            assertTrue(breaker.allow());

            breaker.recordSuccess();

            assertFalse(breaker.isOpen());
            assertEquals(0, breaker.consecutiveFailures());
            assertTrue(breaker.allow(), "闭路后恢复全部放行");
            assertTrue(breaker.allow());
        }

        @Test
        @DisplayName("探针失败：重新开路并重置窗口计时")
        void probeFailureReopensAndResetsTimer() {
            failTimes(THRESHOLD);
            now.addAndGet(WINDOW_MS);
            assertTrue(breaker.allow());

            breaker.recordAttemptFailure(true);

            assertTrue(breaker.isOpen());
            assertEquals(WINDOW_MS, breaker.remainingOpenMs(), "计时应从探针失败时刻重新开始");
            now.addAndGet(WINDOW_MS - 1);
            assertFalse(breaker.allow(), "重置后的窗口内仍应拒绝");
            now.addAndGet(1);
            assertTrue(breaker.allow(), "新窗口到期后再次放行探针");
        }

        @Test
        @DisplayName("探针丢失（超时未回传结果）：视为放弃并放行新探针，不会永久半开")
        void lostProbeIsAbandoned() {
            failTimes(THRESHOLD);
            now.addAndGet(WINDOW_MS);
            assertTrue(breaker.allow());
            assertFalse(breaker.allow(), "探针进行中其余请求继续跳过");

            now.addAndGet(UpstreamBreaker.PROBE_ABANDON_MS);

            assertTrue(breaker.allow(), "丢失的探针超时后必须放行新探针，否则熔断器永久卡在半开");
        }

        @Test
        @DisplayName("探针收到业务失败（非传输层）：不重开也不卡死，后续仍可再探")
        void probeBusinessFailureReleasesProbe() {
            failTimes(THRESHOLD);
            now.addAndGet(WINDOW_MS);
            assertTrue(breaker.allow());

            breaker.recordAttemptFailure(false);

            assertTrue(breaker.isOpen(), "业务失败不改变开路状态");
            assertTrue(breaker.allow(), "探针占位必须释放，否则永久半开");
        }
    }

    @Nested
    @DisplayName("传输层失败判定口径")
    class Classification {

        @Test
        @DisplayName("连接拒绝/超时/连接重置/IO 异常/5xx 计入")
        void transportErrorsCount() {
            assertTrue(UpstreamBreaker.isTransportFailure(new ConnectException("Connection refused")));
            assertTrue(UpstreamBreaker.isTransportFailure(new SocketTimeoutException("Read timed out")));
            assertTrue(UpstreamBreaker.isTransportFailure(new java.net.SocketException("Connection reset")));
            assertTrue(UpstreamBreaker.isTransportFailure(new ResourceAccessException(
                    "I/O error", new java.net.SocketException("Connection reset"))));
            assertTrue(UpstreamBreaker.isTransportFailure(
                    new ResourceAccessException("I/O error", new ConnectException("refused"))));
            assertTrue(UpstreamBreaker.isTransportFailure(
                    HttpServerErrorException.create(org.springframework.http.HttpStatus.BAD_GATEWAY,
                            "Bad Gateway", null, null, null)));
        }

        @Test
        @DisplayName("4xx/普通运行时异常/解析类 IOException（如 JSON 解析）/空指针不计入")
        void businessErrorsDoNotCount() {
            assertFalse(UpstreamBreaker.isTransportFailure(
                    HttpClientErrorException.create(org.springframework.http.HttpStatus.BAD_REQUEST,
                            "Bad Request", null, null, null)));
            assertFalse(UpstreamBreaker.isTransportFailure(new RuntimeException("parse failed")));
            assertFalse(UpstreamBreaker.isTransportFailure(new NullPointerException("npe")));
            // Jackson 的 JsonProcessingException 继承 IOException：不能把"解析失败"当传输失败
            assertFalse(UpstreamBreaker.isTransportFailure(
                    new java.io.CharConversionException("malformed json")));
            assertFalse(UpstreamBreaker.isTransportFailure(null));
        }
    }
}
