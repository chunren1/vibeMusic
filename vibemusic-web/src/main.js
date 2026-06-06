import './assets/main.css'

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'

const app = createApp(App)
app.use(createPinia())
app.use(router)

// 全局错误处理 — 防止未捕获异常导致白屏
app.config.errorHandler = (err, vm, info) => {
  console.error('[Vue Error]', err, 'info:', info)
  // 生产环境可替换为 Sentry/上报接口
}

app.mount('#app')
