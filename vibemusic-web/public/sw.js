// vibeMusic Service Worker — 离线缓存 + 秒开
const CACHE_NAME = 'vibemusic-v1'
const ASSETS_TO_CACHE = [
  '/m',
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
