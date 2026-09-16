// vibeMusic Service Worker — 离线缓存 + 秒开
// 缓存名跟随构建变化：对 workbox 注入清单的 url+revision 做指纹，
// 发版后旧缓存整体淘汰（activate 按前缀清理），避免 hash 资源永久堆积
const WB_MANIFEST = self.__WB_MANIFEST || []
function manifestId(list) {
  const s = list.map(e => typeof e === 'string' ? e : ((e && e.url || '') + '@' + (e && e.revision || ''))).join('\n')
  let h = 5381
  for (let i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) >>> 0
  return h.toString(36)
}
const CACHE_PREFIX = 'vibemusic-'
const CACHE_NAME = CACHE_PREFIX + (WB_MANIFEST.length ? manifestId(WB_MANIFEST) : 'dev')
// 预缓存的离线核心资源（首次安装后即可离线访问；生产构建时被 workbox 注入的 hash 清单替代）
const ASSETS_TO_CACHE = [
  '/',              // 主页 (SPA entry)
  '/m',             // 移动版主页
  '/manifest.json',
]

// 离线导航回退页：移动端路由前缀 /m 回退移动页，桌面端回退主页
const offlineFallbackFor = requestUrl => {
  try {
    return new URL(requestUrl).pathname.startsWith('/m') ? '/m' : '/'
  } catch {
    return '/m'
  }
}

// 安装：预缓存核心资源（生产构建时 self.__WB_MANIFEST 已被 workbox 替换为 hash 资源清单；
// 开发/未处理模式保持手写回退）
self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_NAME).then(cache => {
      const assets = (WB_MANIFEST.length ? WB_MANIFEST : ASSETS_TO_CACHE).map(entry =>
        typeof entry === 'string' ? entry : entry.url
      )
      return cache.addAll(assets).catch((err) => {
        console.warn('[SW] precache 部分资源失败，离线能力可能不完整:', err && err.message || err)
      })
    })
  )
  self.skipWaiting()
})

// 激活：清理旧缓存（同前缀不同构建指纹一律删除）
self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys().then(keys => {
      return Promise.all(
        keys.filter(k => k.startsWith(CACHE_PREFIX) && k !== CACHE_NAME).map(k => caches.delete(k))
      )
    })
  )
  self.clients.claim()
})

// 请求拦截：导航请求 network-first（避免陈旧 HTML + 已删除 hash 资源 404），
// 静态资源 cache-first + 后台更新
self.addEventListener('fetch', event => {
  // 只处理 GET 请求
  if (event.request.method !== 'GET') return

  // 跳过 chrome-extension 和 API 请求
  const url = new URL(event.request.url)
  if (url.protocol === 'chrome-extension:') return
  if (url.pathname.startsWith('/api/')) return
  // 用户上传内容（头像/背景图）不缓存：登出/换账号后同设备不可再读
  if (url.pathname.startsWith('/uploads/')) return
  // 跳过跨域请求（网易云封面等图片走 CDN 强缓存，Cache API 无法缓存 no-cors opaque 响应，
  // 拦截后反而绕过浏览器 HTTP 缓存导致每次渲染重复请求）
  if (url.origin !== self.location.origin) return
  // 跳过音频/视频流（Range 请求返回 206，Cache API 不支持）
  const dest = event.request.destination
  if (dest === 'audio' || dest === 'video') return

  if (event.request.mode === 'navigate') {
    // 导航（HTML）：network-first，失败才回退缓存/离线页
    event.respondWith(
      fetch(event.request).then(response => {
        if (response.ok) {
          const clone = response.clone()
          caches.open(CACHE_NAME).then(cache => cache.put(event.request, clone))
        }
        return response
      }).catch(() =>
        caches.match(event.request).then(cached =>
          cached || caches.match(offlineFallbackFor(event.request.url))
        )
      )
    )
    return
  }

  event.respondWith(
    caches.match(event.request).then(cached => {
      if (cached) {
        // 缓存命中 → 立即返回，后台更新
        fetch(event.request).then(response => {
          if (response.ok) {
            caches.open(CACHE_NAME).then(cache => {
              cache.put(event.request, response)
            })
          }
        }).catch(() => {})
        return cached
      }
      // 缓存未命中 → 网络请求 → 缓存
      return fetch(event.request).then(response => {
        if (!response.ok) return response
        const clone = response.clone()
        caches.open(CACHE_NAME).then(cache => {
          cache.put(event.request, clone)
        })
        return response
      }).catch(() => {
        // 离线且无缓存 → 返回离线页（按 /m 前缀区分设备）
        if (event.request.mode === 'navigate') {
          return caches.match(offlineFallbackFor(event.request.url))
        }
        return new Response('', { status: 408 })
      })
    })
  )
})
