import axios from 'axios'

// 开发环境用 Vite proxy，生产环境用 VITE_API_HOST 环境变量
export const API_HOST = import.meta.env.VITE_API_HOST || ''
const API_BASE = API_HOST ? API_HOST + '/api' : '/api'

// token 内存缓存（cookie 由后端 httpOnly 管理，前端无需 localStorage）
let _tokenCache = null
export function getToken() { return _tokenCache }
export function setToken(t) { _tokenCache = t }

// UUID v4 生成（兼容旧手机浏览器不支持 crypto.randomUUID）
function generateUUID() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID()
  }
  // 降级：用 crypto.getRandomValues 手动构造 UUID v4
  if (typeof crypto !== 'undefined' && crypto.getRandomValues) {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
      const r = crypto.getRandomValues(new Uint8Array(1))[0]
      const v = c === 'x' ? (r & 15) : (r & 0x3 | 0x8)
      return v.toString(16)
    })
  }
  // 最终兜底（无 crypto API）
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = Math.random() * 16 | 0
    return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16)
  })
}

const request = axios.create({
  baseURL: API_BASE,
  timeout: 15000,
})

request.interceptors.request.use((config) => {
  // 记录请求发起时的 token，用于响应阶段判断 401 是否来自"旧 token"
  config._sentToken = _tokenCache
  if (_tokenCache) {
    config.headers.Authorization = `Bearer ${_tokenCache}`
  }
  // 幂等防护：每次写请求带唯一 Request-Id
  const method = (config.method || '').toLowerCase()
  if (['post', 'put', 'delete', 'patch'].includes(method)) {
    config.headers['X-Request-Id'] = generateUUID()
  }
  return config
})

request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code !== 200) {
      if ((res.code === 401 || res.code === 403) && !response.config.url.includes('/auth/')) {
        handleUnauthorized(response.config)
      }
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    return res
  },
  (error) => {
    const status = error.response?.status
    if ((status === 401 || status === 403) && !error.config?.url?.includes('/auth/')) {
      handleUnauthorized(error.config)
    }
    return Promise.reject(error)
  }
)

function handleUnauthorized(config) {
  // 防止旧请求（登录前发起的）的 401 误触发退出
  // 如果请求时的 token 与当前 token 不同，说明是旧请求，忽略
  if (config && config._sentToken !== _tokenCache) {
    return
  }
  // 仅在用户确实处于登录状态时才触发退出+弹窗；
  // 未登录用户访问公开页面时可能触发收藏等需要认证的接口，
  // 这些 401 不应强制弹出登录框。
  import('@/stores/auth').then(({ useAuthStore }) => {
    const store = useAuthStore()
    if (store.isLoggedIn) {
      store.logout()
      store.openLogin()
    }
  }).catch((e) => {
    console.warn('[request] store import failed:', e.message)
  })
}

export default request
