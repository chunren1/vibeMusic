import './assets/main.css'
import './assets/mobile-theme.css'
import './assets/icons/iconfont.js'

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import vLazyImg from './directives/vLazyImg'
import SvgIcon from './components/SvgIcon.vue'
import { safeCapture } from './utils/safeCapture'

// Sentry 前端错误监控
// 注册 https://sentry.io → 创建 Vue 项目 → DSN 填入 .env.production 的 VITE_SENTRY_DSN
const SENTRY_DSN = import.meta.env.VITE_SENTRY_DSN
let Sentry = null
if (SENTRY_DSN) {
  Sentry = await import('@sentry/vue')
}

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.directive('lazy-img', vLazyImg)
app.component('SvgIcon', SvgIcon)

if (Sentry && SENTRY_DSN) {
  Sentry.init({
    app,
    dsn: SENTRY_DSN,
    integrations: [Sentry.browserTracingIntegration({ router })],
    tracesSampleRate: 0.1,
    replaysSessionSampleRate: 0.1,
    replaysOnErrorSampleRate: 1.0,
  })
  // 暴露 Sentry 给 safeCapture 使用
  window.__SENTRY__ = Sentry
}

// 全局错误处理（Sentry.init({ app }) 已注入原 handler，先链式调用再补充上报，避免覆盖丢失面包屑）
const _sentryHandler = app.config.errorHandler
app.config.errorHandler = (err, vm, info) => {
  try { _sentryHandler?.(err, vm, info) } catch {}
  console.error('[Vue Error]', err, 'info:', info)
  safeCapture(err, `Vue ErrorHandler [${info}]`)
  if (Sentry) Sentry.captureException(err, { extra: { info } })
}

// 全局未捕获 Promise 错误：只上报裁剪后的 message/stack 副本，
// 避免把 AxiosError 原样上送（可能携带 Authorization 头与请求参数）
window.addEventListener('unhandledrejection', (event) => {
  const reason = event.reason
  const trimmed = reason instanceof Error
    ? { message: reason.message, stack: reason.stack }
    : { message: String(reason) }
  safeCapture(reason, 'Unhandled Promise Rejection', trimmed)
})

// 性能标记
app.config.performance = import.meta.env.DEV

app.mount('#app')

// Service Worker 注册（原为 index.html 内联脚本；为配合 nginx 严格 CSP script-src 'self' 移入打包产物）
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => {})
  })
}
