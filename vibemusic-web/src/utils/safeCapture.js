/**
 * 全局安全错误捕获工具
 * 用法: safeCapture(error, "HomeView.search")
 */
export function safeCapture(error, context = "unknown", extra = {}) {
  const msg = error?.message || String(error);
  console.error("[safeCapture] " + context, msg, extra);
  try {
    const Sentry = window?.__SENTRY__;
    if (Sentry) Sentry.captureException(error, { tags: { context }, extra });
  } catch (e) {}
}

export function silentCapture(error, context) {
  console.warn("[silent] " + context + ":", error?.message || error);
}

export default safeCapture;
