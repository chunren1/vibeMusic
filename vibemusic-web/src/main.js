import './assets/main.css'
import './assets/mobile-theme.css'
import './assets/icons/iconfont.js'

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import vLazyImg from './directives/vLazyImg'
import SvgIcon from './components/SvgIcon.vue'
import { installErrorHandler, installGlobalErrorReporting } from './utils/clientErrorReporter'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.directive('lazy-img', vLazyImg)
app.component('SvgIcon', SvgIcon)

// 全局错误处理：生产环境不引入第三方监控（无 Sentry、无 DSN、无外部数据外流），
// 改用第一方极简上报 src/utils/clientErrorReporter.js → 同源 POST /api/client-errors
// （原生 sendBeacon/fetch，绕开 axios 401 拦截器避免续签风暴；载荷不含 token/cookie/query）。
// Vue 组件错误走 errorHandler 链式注入，JS 运行时错误 + 资源加载失败 + 未处理 Promise 拒绝走 window 监听。
installErrorHandler(app)
installGlobalErrorReporting()

// 性能标记
app.config.performance = import.meta.env.DEV

app.mount('#app')

// Service Worker 注册（原为 index.html 内联脚本；为配合 nginx 严格 CSP script-src 'self' 移入打包产物）
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => {})
  })
}
