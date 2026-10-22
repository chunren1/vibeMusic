package com.vibemusic.dto;

/**
 * 前端错误上报载荷（P0-7，第一方上报，替代 Sentry）。
 *
 * <p>字段与 {@code vibemusic-web/src/utils/clientErrorReporter.js} 的契约一一对应；
 * 前端已做截断与一次脱敏，后端仍会二次脱敏并再次校验长度。</p>
 *
 * @param message 错误信息（已截断 ≤500）
 * @param stack   堆栈（已截断 ≤4000）
 * @param kind    error / unhandledrejection / vue / resource
 * @param route   仅 pathname
 * @param release 构建版本（git sha）
 * @param ua      User-Agent（≤300）
 * @param ts      ISO8601 时间戳
 */
public record ClientErrorReport(
        String message,
        String stack,
        String kind,
        String route,
        String release,
        String ua,
        String ts) {
}
