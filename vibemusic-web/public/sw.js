// vibeMusic Service Worker — 离线缓存 + 秒开
const CACHE_NAME = 'vibemusic-v4'
// 预缓存的离线核心资源（首次安装后即可离线访问）
const ASSETS_TO_CACHE = [
  '/',              // 主页 (SPA entry)
  '/m',             // 移动版主页
  '/manifest.json',
]

// 安装：预缓存核心资源
self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_NAME).then(cache => {
      return cache.addAll(ASSETS_TO_CACHE).catch(() => {})
    })
  )
  self.skipWaiting()
})

// 激活：清理旧缓存
self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys().then(keys => {
      return Promise.all(
        keys.filter(k => k !== CACHE_NAME).map(k => caches.delete(k))
      )
    })
  )
  self.clients.claim()
})

// 请求拦截：缓存优先 + 网络回退
self.addEventListener('fetch', event => {
  // 只处理 GET 请求
  if (event.request.method !== 'GET') return

  // 跳过 chrome-extension 和 API 请求
  const url = new URL(event.request.url)
  if (url.protocol === 'chrome-extension:') return
  if (url.pathname.startsWith('/api/')) return
  // 跳过跨域请求（网易云封面等图片走 CDN 强缓存，Cache API 无法缓存 no-cors opaque 响应，
  // 拦截后反而绕过浏览器 HTTP 缓存导致每次渲染重复请求）
  if (url.origin !== self.location.origin) return
  // 跳过音频/视频流（Range 请求返回 206，Cache API 不支持）
  const dest = event.request.destination
  if (dest === 'audio' || dest === 'video') return

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
        // 离线且无缓存 → 返回离线页
        if (event.request.mode === 'navigate') {
          return caches.match('/m')
        }
        return new Response('', { status: 408 })
      })
    })
  )
})
