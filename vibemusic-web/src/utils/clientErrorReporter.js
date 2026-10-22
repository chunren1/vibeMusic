/**
 * 第一方极简前端错误上报（替代 Sentry：零第三方依赖、零外部账号、无隐私外流）
 * 覆盖：JS 运行时错误 + 资源加载失败（window 捕获阶段 error 事件，资源失败不触发
 * window.onerror）、未处理 Promise 拒绝、Vue 组件错误。
 * 上报用原生 sendBeacon/fetch + 同源 POST /api/client-errors，刻意不用 axios：
 * request.js 的 401 拦截器会把上报失败变成续签风暴。载荷只含错误信息与 pathname
 * （无 token/cookie/query），内部异常一律吞掉降级为 console.error。
 */
const ENDPOINT = '/api/client-errors'
const MAX_REPORTS = 10 // 每页面会话最多上报条数
const MAX_SEEN = 50 // 去重表上限，防无界增长
const MAX_MESSAGE = 500
const MAX_STACK = 4000
const MAX_UA = 300
/** @type {Set<string>} */
const seen = new Set()
let sentCount = 0
let installed = false
function truncate(value, max) {
  if (value == null) return ''
  const str = typeof value === 'string' ? value : String(value)
  return str.length > max ? str.slice(0, max) : str
}
/** 抹掉 message/stack 里可能夹带的凭据型 query 参数（route 只取 pathname） */
function scrub(str) {
  return str.replace(/([?&](?:token|access_token|refresh_token|code|key|password|pwd)=)[^&\s)]+/gi, '$1[redacted]')
}
const currentRoute = () => (typeof window === 'undefined' ? '/' : window.location.pathname || '/')
const currentRelease = () => import.meta.env.VITE_APP_RELEASE || 'dev'
function send(body) {
  const payload = JSON.stringify(body)
  try {
    if (typeof navigator !== 'undefined' && typeof navigator.sendBeacon === 'function') {
      const blob = new Blob([payload], { type: 'application/json' })
      if (navigator.sendBeacon(ENDPOINT, blob)) return
    }
  } catch { /* beacon 不可用 → 回退 fetch */ }
  try {
    if (typeof fetch !== 'function') return
    const options = /** @type {RequestInit} */ ({ method: 'POST', headers: { 'Content-Type': 'application/json' }, body: payload, keepalive: true, credentials: 'same-origin' })
    fetch(ENDPOINT, options).catch(() => { /* 静默降级，避免二次未处理拒绝 */ })
  } catch { /* ignore */ }
}
/**
 * 错误上报载荷字段契约（后端按此实现）：message≤500 / stack≤4000 / ua≤300
 * @typedef {{ message?: string, stack?: string, kind?: 'error'|'unhandledrejection'|'vue'|'resource' }} ClientErrorInput
 * 上报一条客户端错误；@returns {boolean} 是否真正上报（去重/限流命中为 false）
 * @param {ClientErrorInput} [input]
 */
export function reportClientError(input = {}) {
  try {
    const { message = '', stack = '', kind = 'error' } = input
    if (sentCount >= MAX_REPORTS) return false
    const safeMessage = scrub(truncate(message || 'Unknown error', MAX_MESSAGE))
    const safeStack = scrub(truncate(stack, MAX_STACK))
    const key = `${safeMessage}|${safeStack.slice(0, 200)}`
    if (seen.has(key)) return false
    seen.add(key)
    if (seen.size > MAX_SEEN) seen.delete(seen.values().next().value)
    sentCount += 1
    send({
      message: safeMessage, stack: safeStack, kind, route: currentRoute(), release: currentRelease(),
      ua: truncate(typeof navigator === 'undefined' ? '' : navigator.userAgent, MAX_UA), ts: new Date().toISOString(),
    })
    return true
  } catch (err) {
    console.error('[clientErrorReporter] 上报失败（已忽略）', err)
    return false
  }
}
function onWindowError(event) {
  const target = event.target
  // 资源加载失败：event.target 不是 window，而是 img/script/link 等元素
  if (target && target !== window && target.tagName) {
    const src = String(target.currentSrc || target.src || target.href || '').split(/[?#]/)[0] // 去掉 query/hash
    reportClientError({ message: `资源加载失败 <${String(target.tagName).toLowerCase()}> ${src}`, kind: 'resource' })
    return
  }
  reportClientError({ message: event.message || event.error?.message || 'Script error', stack: event.error?.stack || '', kind: 'error' })
}

function onUnhandledRejection(event) {
  const reason = event.reason
  const fields = reason instanceof Error ? { message: reason.message, stack: reason.stack || '' } : { message: String(reason) }
  reportClientError({ ...fields, kind: 'unhandledrejection' })
}

/** 挂 window 级监听（幂等，捕获阶段覆盖资源加载失败） */
export function installGlobalErrorReporting() {
  if (installed || typeof window === 'undefined') return
  installed = true
  window.addEventListener('error', onWindowError, true)
  window.addEventListener('unhandledrejection', onUnhandledRejection)
}

/** 挂 Vue 全局错误处理，链式保留已有 handler（不吞错） */
export function installErrorHandler(app) {
  const previous = app.config.errorHandler
  app.config.errorHandler = (err, instance, info) => {
    try {
      previous?.(err, instance, info)
    } catch (prevErr) {
      console.error('[clientErrorReporter] 既有 errorHandler 抛出异常', prevErr)
    }
    console.error('[Vue Error]', err, 'info:', info)
    const vueMessage = info ? `${err?.message || String(err)} [${info}]` : err?.message || String(err)
    reportClientError({ message: vueMessage, stack: err?.stack || '', kind: 'vue' })
  }
}

/** 重置内部状态并卸载监听（供测试隔离使用） */
export function resetErrorReporter() {
  if (typeof window !== 'undefined' && installed) {
    window.removeEventListener('error', onWindowError, true)
    window.removeEventListener('unhandledrejection', onUnhandledRejection)
  }
  installed = false
  sentCount = 0
  seen.clear()
}
