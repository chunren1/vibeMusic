import './assets/main.css'
import './assets/mobile-theme.css'
import './assets/icons/iconfont.js' // Iconfont Symbol JS — 注入 SVG symbols 到 DOM

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import vLazyImg from './directives/vLazyImg'
import SvgIcon from './components/SvgIcon.vue'

// Sentry 前端错误监控（需安装: npm install @sentry/vue）
// 注册地址: https://sentry.io → 创建项目获取 DSN → 填入下方
const SENTRY_DSN = import.meta.env.VITE_SENTRY_DSN
if (SENTRY_DSN) {
  import('@sentry/vue').then((Sentry) => {
    Sentry.init({
      app,
      dsn: SENTRY_DSN,
      integrations: [Sentry.browserTracingIntegration({ router })],
      tracesSampleRate: 0.1,
      replaysSessionSampleRate: 0.1,
      replaysOnErrorSampleRate: 1.0,
    })
  })
}

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.directive('lazy-img', vLazyImg)
app.component('SvgIcon', SvgIcon)

// 全局错误处理（未配置 Sentry 时 fallback 到 console）
app.config.errorHandler = (err, vm, info) => {
  console.error('[Vue Error]', err, 'info:', info)
  if (SENTRY_DSN) {
    import('@sentry/vue').then((Sentry) => {
      Sentry.captureException(err, { data: { info } })
    })
  }
}

// 性能标记
app.config.performance = import.meta.env.DEV

app.mount('#app')
