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
  // refresh token 走 httpOnly Cookie，跨域续签必须带上
  withCredentials: true,
})

// 静默续签单飞：并发 401 共用一次 refresh，避免雪崩打爆后端
let _refreshPromise = null
// 返回 true=续签成功；false=凭据失效；'network'=网络层失败（超时/断网，见 catch）
function trySilentRefresh() {
  if (_refreshPromise) return _refreshPromise
  _refreshPromise = request
    .post('/auth/refresh', {}, { _isRefresh: true })
    .then((res) => {
      const token = res && res.data && res.data.token
      if (res && res.code === 200 && token) {
        setToken(token)
        return true
      }
      return false
    })
    .catch((e) => {
      // 区分"网络失败"与"凭据失效"：无 response 的 axios 网络层错误
      // （超时 ECONNABORTED / 断网 ERR_NETWORK / 取消）不断开登录态；
      // 信封 401/403（new Error，无 response 无 code）与 HTTP 401（有 response）均为凭据失效。
      if (e && !e.response && (e.code === 'ECONNABORTED' || e.code === 'ERR_NETWORK' || e.code === 'ERR_CANCELED')) return 'network'
      return false
    })
    .finally(() => { _refreshPromise = null })
  return _refreshPromise
}

// 全局单飞续签入口：restore 与 401 静默续签共用，确保同一时刻只有一次 refresh 在飞
// （后端 refresh token 一次性轮换，并发 refresh 会使旧 token 被拉黑导致互踢下线）
export function refreshOnce() {
  return trySilentRefresh()
}

function forceLogout() {
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

async function isLoggedInAsync() {
  if (_tokenCache) return true
  try {
    const { useAuthStore } = await import('@/stores/auth')
    return !!useAuthStore().isLoggedIn
  } catch {
    return false
  }
}

// 401/403 统一处理：先静默续签，重试一次；续签失败才登出
async function onUnauthorized(config, originalError) {
  if (!config) {
    forceLogout()
    return Promise.reject(originalError)
  }
  if (config._isRefresh || config._retry || isAuthUrl(config.url)) {
    if (!config._isRefresh && !isAuthUrl(config.url)) forceLogout()
    return Promise.reject(originalError)
  }
  // 迟到 401：请求发出后 token 已更新（刷新在别处完成），旧 token 的 401 不应直接丢弃；
  // 若该请求尚未重试过且新旧 token 均有效，用新 token 重发一次。
  // _sentToken 为空（登录前发出的旧请求）则沿用原语义直接拒绝，避免误触发退出。
  if (config._sentToken !== _tokenCache) {
    if (!config._retry && config._sentToken && _tokenCache) {
      config._retry = true
      return request(config)
    }
    return Promise.reject(originalError)
  }
  if (!(await isLoggedInAsync())) {
    return Promise.reject(originalError)
  }
  const ok = await trySilentRefresh()
  // 离线抖动：续签请求根本没发出去（超时/断网），不断开登录态、不弹窗，直接拒绝由调用方提示
  if (ok === 'network') {
    return Promise.reject(originalError)
  }
  if (!ok) {
    forceLogout()
    return Promise.reject(originalError)
  }
  config._retry = true
  return request(config)
}

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
  // 还原代理 URL，避免收藏/历史/歌单/下载把代理地址写入 DB/MinIO
  if (config.params) deepRestoreProxiedUrl(config.params)
  if (config.data) deepRestoreProxiedUrl(config.data)
  return config
})

// 音乐平台封面 CDN 直连在部分网络/浏览器下会被 408/CORS/广告拦截（如网易云 126.net 被客户端拦截），
// 统一改写为后端 /api/image-proxy（白名单见 ProxyController.ALLOWED_HOSTS，含 gtimg.cn 与 p1-p4.music.126.net）
const COVER_CDN_HOSTS = /^https?:\/\/(?:(?:y|i|music)\.gtimg\.cn|(?:p[1-4]\.)?music\.126\.net)\//
const PROXY_URL_PREFIX = '/api/image-proxy?url='

function rewriteCoverUrl(value) {
  if (typeof value !== 'string' || !COVER_CDN_HOSTS.test(value)) return value
  // 拼 API_HOST：dev/prod web 为空走相对路径（原生 App 另行处理，不走此分支）
  return API_HOST + PROXY_URL_PREFIX + encodeURIComponent(value)
}

// 深递归改写响应中的封面 URL（渲染处追加的 ?param= 会落入 url 参数内，后端按原样请求）
// maxDepth 限制递归深度，防止病态深嵌套/超大对象导致栈溢出或无谓遍历（默认 20 层）
export function deepRewriteCoverUrl(node, maxDepth = 20) {
  if (maxDepth <= 0) return node
  if (Array.isArray(node)) {
    for (let i = 0; i < node.length; i++) node[i] = deepRewriteCoverUrl(node[i], maxDepth - 1)
  } else if (node && typeof node === 'object') {
    for (const k of Object.keys(node)) node[k] = deepRewriteCoverUrl(node[k], maxDepth - 1)
  } else {
    return rewriteCoverUrl(node)
  }
  return node
}

// 回写后端前还原代理 URL，DB/MinIO 只存原始 CDN 地址
function restoreProxiedUrl(value) {
  if (typeof value !== 'string') return value
  const idx = value.indexOf(PROXY_URL_PREFIX)
  if (idx === -1) return value
  let encoded = value.slice(idx + PROXY_URL_PREFIX.length)
  // 双层编码兜底：最多解码 2 次；解码结果已是完整 URL 即停止，
  // 避免把原始 URL 内合法的 %XX 转义二次解码
  for (let i = 0; i < 2; i++) {
    if (!encoded.includes('%')) break
    try {
      const decoded = decodeURIComponent(encoded)
      if (decoded.startsWith('http://') || decoded.startsWith('https://') || decoded.startsWith('//')) {
        return decoded
      }
      encoded = decoded
    } catch {
      break
    }
  }
  return encoded
}

export function deepRestoreProxiedUrl(node, maxDepth = 20) {
  if (maxDepth <= 0) return node
  if (Array.isArray(node)) {
    for (let i = 0; i < node.length; i++) node[i] = deepRestoreProxiedUrl(node[i], maxDepth - 1)
  } else if (node && typeof node === 'object') {
    for (const k of Object.keys(node)) node[k] = deepRestoreProxiedUrl(node[k], maxDepth - 1)
  } else {
    return restoreProxiedUrl(node)
  }
  return node
}

// 精确匹配 /auth/ 路径：相对路径基于 window.location.origin 解析，绝对 URL 直接解析；
// 避免 includes('/auth/') 子串误伤未来含 "auth" 字样的端点
export function isAuthUrl(url) {
  if (!url) return false
  try {
    const { pathname } = new URL(url, window.location.origin)
    return pathname.includes('/auth/')
  } catch {
    return false
  }
}

request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code !== 200) {
      if ((res.code === 401 || res.code === 403) && !response.config._isRefresh) {
        return onUnauthorized(response.config, new Error(res.message || '请求失败'))
      }
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    if (res.data) deepRewriteCoverUrl(res.data)
    return res
  },
  (error) => {
    const status = error.response?.status
    if ((status === 401 || status === 403) && !error.config?._isRefresh) {
      return onUnauthorized(error.config, error)
    }
    return Promise.reject(error)
  }
)

export default request
