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

// 全局错误处理
app.config.errorHandler = (err, vm, info) => {
  console.error('[Vue Error]', err, 'info:', info)
  safeCapture(err, `Vue ErrorHandler [${info}]`)
  if (Sentry) Sentry.captureException(err, { data: { info } })
}

// 全局未捕获 Promise 错误
window.addEventListener('unhandledrejection', (event) => {
  safeCapture(event.reason, 'Unhandled Promise Rejection')
})

// 性能标记
app.config.performance = import.meta.env.DEV

app.mount('#app')
