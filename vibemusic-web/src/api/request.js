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
  // 还原代理 URL，避免收藏/历史/歌单/下载把代理地址写入 DB/MinIO
  if (config.params) deepRestoreProxiedUrl(config.params)
  if (config.data) deepRestoreProxiedUrl(config.data)
  return config
})

// QQ 音乐封面 CDN 在部分网络下浏览器直连会 408/CORS，统一改写为后端 /api/image-proxy
const QQ_COVER_HOSTS = /^https?:\/\/(?:y|i|music)\.gtimg\.cn\//
const PROXY_URL_PREFIX = '/api/image-proxy?url='

function rewriteQQCoverUrl(value) {
  if (typeof value !== 'string' || !QQ_COVER_HOSTS.test(value)) return value
  // 拼 API_HOST：dev/prod web 为空走相对路径，Capacitor 有绝对地址才可访问
  return API_HOST + PROXY_URL_PREFIX + encodeURIComponent(value)
}

// 深递归改写响应中的 QQ 封面 URL（渲染处追加的 ?param= 会落入 url 参数内，后端按原样请求）
// maxDepth 限制递归深度，防止病态深嵌套/超大对象导致栈溢出或无谓遍历（默认 20 层）
export function deepRewriteCoverUrl(node, maxDepth = 20) {
  if (maxDepth <= 0) return node
  if (Array.isArray(node)) {
    for (let i = 0; i < node.length; i++) node[i] = deepRewriteCoverUrl(node[i], maxDepth - 1)
  } else if (node && typeof node === 'object') {
    for (const k of Object.keys(node)) node[k] = deepRewriteCoverUrl(node[k], maxDepth - 1)
  } else {
    return rewriteQQCoverUrl(node)
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
      if ((res.code === 401 || res.code === 403) && !isAuthUrl(response.config.url)) {
        handleUnauthorized(response.config)
      }
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    if (res.data) deepRewriteCoverUrl(res.data)
    return res
  },
  (error) => {
    const status = error.response?.status
    if ((status === 401 || status === 403) && !isAuthUrl(error.config?.url)) {
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
