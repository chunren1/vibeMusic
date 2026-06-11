import axios from 'axios'

// 开发环境用 Vite proxy，生产环境用 VITE_API_HOST 环境变量
export const API_HOST = import.meta.env.VITE_API_HOST || ''
const API_BASE = API_HOST ? API_HOST + '/api' : '/api'

// token 内存缓存 — 避免每次请求都读 localStorage（同步 I/O）
let _tokenCache = localStorage.getItem('token') || null
export function getToken() { return _tokenCache }
export function setToken(t) { _tokenCache = t; if (t) localStorage.setItem('token', t); else localStorage.removeItem('token') }

const request = axios.create({
  baseURL: API_BASE,
  timeout: 15000,
})

request.interceptors.request.use((config) => {
  if (_tokenCache) {
    config.headers.Authorization = `Bearer ${_tokenCache}`
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
