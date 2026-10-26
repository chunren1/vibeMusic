package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.common.utils.ClientErrorScrubber;
import com.vibemusic.common.utils.RequestIdentity;
import com.vibemusic.dto.ClientErrorReport;
import com.vibemusic.service.RateLimitService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * 前端错误上报接收端（P0-7，第一方，不接 Sentry）。
 *
 * <p>为什么落日志而不是落库：错误上报是<b>低频、突发、无界基数</b>的数据，建表就要考虑分区/清理；
 * 而 `docker logs` 已经是低成本、已备份、可 grep 的通道，配一个专用前缀
 * {@code [CLIENT-ERROR]} 就能查。同时打一个 bounded-cardinality 计数器
 * {@code client.error.reports{kind}}，可在 /actuator/metrics 看到趋势。</p>
 *
 * <p>接口刻意<b>无需鉴权</b>（游客态崩溃同样要看），因此必须自带防护：
 * 体积上限、每身份限流、内容二次脱敏、字段长度上限、kind 白名单。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ClientErrorController {

    /** 前端契约：≤16KB。超出直接 413，不解析、不落日志。 */
    private static final int MAX_BODY_BYTES = 16 * 1024;

    private static final int MAX_MESSAGE = 500;
    private static final int MAX_STACK = 4000;
    private static final int MAX_ROUTE = 200;
    private static final int MAX_RELEASE = 64;
    private static final int MAX_UA = 300;

    private static final int REPORTS_PER_MINUTE = 30;

    private static final Set<String> KINDS = Set.of("error", "unhandledrejection", "vue", "resource");

    private final RateLimitService rateLimitService;
    private final MeterRegistry meterRegistry;

    @PostMapping("/api/client-errors")
    public ResponseEntity<Object> report(@RequestBody(required = false) ClientErrorReport body,
                                         HttpServletRequest request) {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Result.error(413, "上报内容过大"));
        }
        if (body == null || isBlank(body.message())) {
            return ResponseEntity.badRequest().body(Result.error(400, "message 不能为空"));
        }
        if (!allowed(request)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "60")
                    .body(Result.error(429, "请求过于频繁，请稍后再试"));
        }

        String kind = normalizeKind(body.kind());
        String message = cut(ClientErrorScrubber.scrub(body.message()), MAX_MESSAGE);
        String stack = cut(ClientErrorScrubber.scrub(body.stack()), MAX_STACK);
        String route = cut(ClientErrorScrubber.pathOnly(body.route()), MAX_ROUTE);
        String release = cut(ClientErrorScrubber.scrub(body.release()), MAX_RELEASE);
        String ua = cut(ClientErrorScrubber.scrub(body.ua()), MAX_UA);

        // 单行输出 + 前缀，方便 `docker logs vibemusic-backend | grep CLIENT-ERROR`
        log.warn("[CLIENT-ERROR] kind={} release={} route={} ts={} ua=\"{}\" msg=\"{}\" stack=\"{}\"",
                kind, release, route, cut(body.ts(), 40), ua, message, stack);

        // 只按 kind 打标签（4 个取值，基数有界）；message/release 进标签会让时间序列爆炸
        meterRegistry.counter("client.error.reports", "kind", kind).increment();

        return ResponseEntity.noContent().build();
    }

    /** 每身份 30 次/分钟；限流组件故障时放行（同 RateLimitFilter 的 fail-open 取舍）。 */
    private boolean allowed(HttpServletRequest request) {
        String key = "clienterr:" + RequestIdentity.resolve(request);
        try {
            return rateLimitService.tryAcquire(key, REPORTS_PER_MINUTE, Duration.ofMinutes(1));
        } catch (RuntimeException e) {
            log.warn("[CLIENT-ERROR] 限流组件异常，放行本次上报 key={} err={}", key, e.toString());
            return true;
        }
    }

    private static String normalizeKind(String kind) {
        if (kind == null) {
            return "error";
        }
        String k = kind.trim().toLowerCase(Locale.ROOT);
        return KINDS.contains(k) ? k : "error";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
