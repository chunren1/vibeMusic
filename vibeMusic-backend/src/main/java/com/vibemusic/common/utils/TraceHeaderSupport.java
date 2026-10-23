package com.vibemusic.common.utils;

import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;

/**
 * 出站 HTTP 请求头的 traceId 透传工具。
 *
 * <p>把当前线程 MDC 里的 traceId 写成 {@code X-Trace-Id} 请求头，
 * 让下游网关（musicapi）的访问日志能与后端请求日志按同一 traceId 对齐。
 * 抽成独立工具类而非内联在 {@code NeteaseApiService} 的私有方法里，便于单测直接断言。
 *
 * <p>只透传 traceId：deviceId/userId 等身份信息不进上游请求头（避免越权信任与隐私外泄）。
 */
public final class TraceHeaderSupport {

    /** 与 {@code TraceIdFilter} 一致的出站请求头名。 */
    public static final String TRACE_HEADER = "X-Trace-Id";

    /** 与 logback {@code %X{traceId}} 一致的 MDC 键。 */
    public static final String MDC_KEY = "traceId";

    /** 防御性上限：traceId 只用于日志关联，超长值不进请求头（也避免异常头值拖垮出站请求）。 */
    private static final int MAX_TRACE_ID_LENGTH = 256;

    private TraceHeaderSupport() {
    }

    /**
     * MDC 中存在合法 traceId 时注入 {@code X-Trace-Id}；否则保持 headers 不变（不改写已有头）。
     *
     * @param headers 待发送的请求头，null 时直接忽略
     */
    public static void apply(HttpHeaders headers) {
        if (headers == null) {
            return;
        }
        String traceId = MDC.get(MDC_KEY);
        if (traceId == null) {
            return;
        }
        String value = traceId.trim();
        // 空值/超长/含 CRLF 一律不透传：header 注入不可信，宁可丢关联也不能发出畸形请求
        if (value.isEmpty()
                || value.length() > MAX_TRACE_ID_LENGTH
                || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            return;
        }
        headers.set(TRACE_HEADER, value);
    }
}
