/**
 * 全局安全错误捕获工具（本地 console 记录 + 第一方上报）
 * 用法: safeCapture(error, "HomeView.search")
 * 说明：已移除 Sentry 依赖，远程上报统一走 clientErrorReporter（同源 /api/client-errors）
 */
import { reportClientError } from "./clientErrorReporter";

export function safeCapture(error, context = "unknown", extra = {}) {
  const msg = error?.message || String(error);
  console.error("[safeCapture] " + context, msg, extra);
  reportClientError({ message: context ? `${msg} [${context}]` : msg, stack: error?.stack || "", kind: "error" });
}

export default safeCapture;
