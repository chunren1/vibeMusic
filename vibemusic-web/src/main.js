import './assets/main.css'
import './assets/mobile-theme.css'
import './assets/icons/iconfont.js' // Iconfont Symbol JS — 注入 SVG symbols 到 DOM

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import vLazyImg from './directives/vLazyImg'
import SvgIcon from './components/SvgIcon.vue'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.directive('lazy-img', vLazyImg)
app.component('SvgIcon', SvgIcon)

// 全局错误处理
app.config.errorHandler = (err, vm, info) => {
  console.error('[Vue Error]', err, 'info:', info)
}

// 性能标记
app.config.performance = import.meta.env.DEV

app.mount('#app')
