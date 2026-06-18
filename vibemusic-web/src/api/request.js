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
  if (_tokenCache) {
    config.headers.Authorization = `Bearer ${_tokenCache}`
  }
  // 幂等防护：每次写请求带唯一 Request-Id
  if (['post', 'put', 'delete'].includes(config.method)) {
    config.headers['X-Request-Id'] = generateUUID()
  }
  return config
})

request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code !== 200) {
      if ((res.code === 401 || res.code === 403) && !response.config.url.includes('/auth/')) {
        handleUnauthorized()
      }
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    return res
  },
  (error) => {
    const status = error.response?.status
    if ((status === 401 || status === 403) && !error.config?.url?.includes('/auth/')) {
      handleUnauthorized()
    }
    return Promise.reject(error)
  }
)

function handleUnauthorized() {
  // Bug5修复: 只通过 store.logout() 统一清理，避免双重删除
  import('@/stores/auth').then(({ useAuthStore }) => {
    const store = useAuthStore()
    store.logout()
    store.openLogin()
  })
}

export default request
