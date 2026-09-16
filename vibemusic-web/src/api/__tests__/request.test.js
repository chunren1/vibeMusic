import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// test-setup.js 全局 mock 了 @/api/request，这里用 importActual 绕过 mock，测试真实实例与拦截器
const requestModule = await vi.importActual('@/api/request')
const request = requestModule.default
const { deepRewriteCoverUrl, deepRestoreProxiedUrl, isAuthUrl, setToken, getToken, refreshOnce } = requestModule

// handleUnauthorized 内部动态 import('@/stores/auth')，mock 之以便断言登出/弹窗
const authMocks = vi.hoisted(() => ({ logout: vi.fn(), openLogin: vi.fn() }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ isLoggedIn: true, logout: authMocks.logout, openLogin: authMocks.openLogin }),
}))

const QQ = 'https://y.gtimg.cn/music/photo_new/T001R300x300M000001.jpg'
const PROXIED = '/api/image-proxy?url=' + encodeURIComponent(QQ)

// 自定义 adapter：捕获请求配置并返回可配置的模拟响应
let captured = []
let respond = () => ({ code: 200, data: {} })
const adapter = async (config) => {
  captured.push(config)
  return { data: respond(), status: 200, statusText: 'OK', headers: {}, config }
}

// AxiosHeaders 大小写不敏感读取（拦截器写入后经 concat 会归一化为小写键）
function getHeader(config, name) {
  return config.headers.get ? config.headers.get(name) : config.headers[name]
}

beforeEach(() => {
  captured = []
  respond = () => ({ code: 200, data: {} })
  setToken(null)
  request.defaults.adapter = adapter
})

afterEach(() => {
  delete request.defaults.adapter
  setToken(null)
})

describe('deepRewriteCoverUrl', () => {
  it('改写嵌套数组/对象中的 QQ gtimg.cn 封面为代理地址', () => {
    const node = {
      songs: [{ coverUrl: QQ }],
      meta: { urls: [QQ, 'https://other.com/x.jpg'] },
      plain: 'hello',
    }
    const out = deepRewriteCoverUrl(node)
    expect(out.songs[0].coverUrl).toBe(PROXIED)
    expect(out.meta.urls[0]).toBe(PROXIED)
    expect(out.meta.urls[1]).toBe('https://other.com/x.jpg')
    expect(out.plain).toBe('hello')
  })

  it('改写网易云 126.net 封面为代理地址（p1-p4 子域 + 根域）', () => {
    const NET = 'https://p3.music.126.net/oumncltDKJBr1rEV4NZaQA==/109951171286223937.jpg'
    expect(deepRewriteCoverUrl(NET)).toBe('/api/image-proxy?url=' + encodeURIComponent(NET))
    const NET4 = 'https://p4.music.126.net/6ZqVrSqmchLIeWONaJg0_w==/109951164351480463.jpg'
    expect(deepRewriteCoverUrl(NET4)).toBe('/api/image-proxy?url=' + encodeURIComponent(NET4))
    const NET_ROOT = 'https://music.126.net/a.jpg'
    expect(deepRewriteCoverUrl(NET_ROOT)).toBe('/api/image-proxy?url=' + encodeURIComponent(NET_ROOT))
  })

  it('非 QQ/网易云域名 URL 与普通字符串保持不变', () => {
    expect(deepRewriteCoverUrl('https://other.com/a.jpg')).toBe('https://other.com/a.jpg')
    expect(deepRewriteCoverUrl(123)).toBe(123)
    expect(deepRewriteCoverUrl(null)).toBe(null)
  })

  it('超过 maxDepth 深度后不再递归改写', () => {
    let deep = QQ
    for (let i = 0; i < 25; i++) deep = { child: deep }
    const out = deepRewriteCoverUrl(deep)
    let n = out
    for (let i = 0; i < 25; i++) n = n.child
    expect(n).toBe(QQ)

    // 显式小深度：depth=1 时第二层不再改写
    const shallow = deepRewriteCoverUrl({ a: { b: QQ } }, 1)
    expect(shallow.a.b).toBe(QQ)
    // depth=0 直接原样返回
    expect(deepRewriteCoverUrl(QQ, 0)).toBe(QQ)
  })
})

describe('deepRestoreProxiedUrl', () => {
  it('还原单层编码的代理 URL', () => {
    const out = deepRestoreProxiedUrl({ coverUrl: PROXIED, list: [PROXIED] })
    expect(out.coverUrl).toBe(QQ)
    expect(out.list[0]).toBe(QQ)
  })

  it('还原双层编码的代理 URL', () => {
    const doubleProxied = '/api/image-proxy?url=' + encodeURIComponent(encodeURIComponent(QQ))
    expect(deepRestoreProxiedUrl(doubleProxied)).toBe(QQ)
  })

  it('普通 URL / 非字符串保持不变', () => {
    expect(deepRestoreProxiedUrl(QQ)).toBe(QQ)
    expect(deepRestoreProxiedUrl('https://other.com/x.jpg')).toBe('https://other.com/x.jpg')
    expect(deepRestoreProxiedUrl(42)).toBe(42)
  })
})

describe('isAuthUrl', () => {
  it('相对路径 /api/auth/* 判定为认证接口', () => {
    expect(isAuthUrl('/api/auth/login')).toBe(true)
    expect(isAuthUrl('/auth/register')).toBe(true)
  })

  it('非认证接口返回 false', () => {
    expect(isAuthUrl('/api/songs/search')).toBe(false)
    // 子串误伤场景：路径含 "auth" 字样但不是 /auth/ 段
    expect(isAuthUrl('/api/musicauth/check')).toBe(false)
  })

  it('带 query 的 URL 与绝对 URL 均正确解析', () => {
    expect(isAuthUrl('/api/auth/me?tab=profile')).toBe(true)
    expect(isAuthUrl('https://vibemusic.example.com/api/auth/refresh')).toBe(true)
    expect(isAuthUrl('https://vibemusic.example.com/api/songs/search?kw=a')).toBe(false)
  })

  it('undefined / null / 非法 URL 返回 false', () => {
    expect(isAuthUrl(undefined)).toBe(false)
    expect(isAuthUrl(null)).toBe(false)
    expect(isAuthUrl('')).toBe(false)
  })
})

describe('响应拦截器', () => {
  it('code=200 时返回信封并改写响应中的 QQ 封面', async () => {
    respond = () => ({ code: 200, data: { songs: [{ coverUrl: QQ }] } })
    const res = await request.get('/songs/search')
    expect(res.code).toBe(200)
    expect(res.data.songs[0].coverUrl).toBe(PROXIED)
  })

  it('code=200 时改写响应中的网易云 banner 封面', async () => {
    const NET = 'https://p3.music.126.net/oumncltDKJBr1rEV4NZaQA==/109951171286223937.jpg'
    respond = () => ({ code: 200, data: [{ coverUrl: NET }] })
    const res = await request.get('/songs/banner')
    expect(res.data[0].coverUrl).toBe('/api/image-proxy?url=' + encodeURIComponent(NET))
  })

  it('code=401 且非认证接口时触发登出+登录弹窗', async () => {
    respond = () => ({ code: 401, message: '登录过期' })
    await expect(request.get('/favorites/list')).rejects.toThrow('登录过期')
    await vi.waitFor(() => expect(authMocks.logout).toHaveBeenCalled())
    expect(authMocks.openLogin).toHaveBeenCalled()
  })

  it('code=401 时先静默续签，成功则重发原请求且不登出', async () => {
    let refreshCalls = 0
    request.defaults.adapter = async (config) => {
      captured.push(config)
      if (config.url && config.url.includes('/auth/refresh')) {
        refreshCalls++
        return { data: { code: 200, data: { token: 'new-token' } }, status: 200, statusText: 'OK', headers: {}, config }
      }
      if (config._retry) {
        return { data: { code: 200, data: { ok: true } }, status: 200, statusText: 'OK', headers: {}, config }
      }
      return { data: { code: 401, message: '登录过期' }, status: 200, statusText: 'OK', headers: {}, config }
    }
    authMocks.logout.mockClear()
    authMocks.openLogin.mockClear()
    setToken('old-token')
    const res = await request.get('/favorites/list')
    expect(res.code).toBe(200)
    expect(getToken()).toBe('new-token')
    expect(refreshCalls).toBe(1)
    expect(authMocks.logout).not.toHaveBeenCalled()
  })

  it('并发 401 只触发一次 refresh（单飞）', async () => {
    let refreshCalls = 0
    request.defaults.adapter = async (config) => {
      captured.push(config)
      if (config.url && config.url.includes('/auth/refresh')) {
        refreshCalls++
        await new Promise((r) => setTimeout(r, 30))
        return { data: { code: 200, data: { token: 'new-token' } }, status: 200, statusText: 'OK', headers: {}, config }
      }
      if (config._retry) {
        return { data: { code: 200, data: {} }, status: 200, statusText: 'OK', headers: {}, config }
      }
      return { data: { code: 401, message: '登录过期' }, status: 200, statusText: 'OK', headers: {}, config }
    }
    authMocks.logout.mockClear()
    setToken('old-token')
    const results = await Promise.all([
      request.get('/favorites/list'),
      request.get('/playlists/list'),
      request.get('/songs/history'),
    ])
    expect(results).toHaveLength(3)
    expect(refreshCalls).toBe(1)
    expect(authMocks.logout).not.toHaveBeenCalled()
  })

  it('刷新完成后到达的迟到 401 用新 token 重发一次（不再直接拒绝）', async () => {
    request.defaults.adapter = async (config) => {
      captured.push(config)
      if (config._retry) {
        return { data: { code: 200, data: { ok: true } }, status: 200, statusText: 'OK', headers: {}, config }
      }
      // 模拟时序：401 响应到达前，刷新已在别处完成、token 已更新
      setToken('new-token')
      return { data: { code: 401, message: '登录过期' }, status: 200, statusText: 'OK', headers: {}, config }
    }
    authMocks.logout.mockClear()
    authMocks.openLogin.mockClear()
    setToken('old-token')
    const res = await request.get('/favorites/list')
    expect(res.code).toBe(200)
    // 重发请求携带新 token
    expect(getHeader(captured[1], 'Authorization')).toBe('Bearer new-token')
    expect(authMocks.logout).not.toHaveBeenCalled()
  })

    it('refreshOnce 并发调用只发一次 refresh（全局单飞，可供 restore 复用）', async () => {
    let refreshCalls = 0
    request.defaults.adapter = async (config) => {
      captured.push(config)
      if (config.url && config.url.includes('/auth/refresh')) {
        refreshCalls++
        await new Promise((r) => setTimeout(r, 20))
        return { data: { code: 200, data: { token: 't' } }, status: 200, statusText: 'OK', headers: {}, config }
      }
      return { data: { code: 200, data: {} }, status: 200, statusText: 'OK', headers: {}, config }
    }
    setToken('old-token')
    const [a, b] = await Promise.all([refreshOnce(), refreshOnce()])
    expect(a).toBe(true)
    expect(b).toBe(true)
    expect(refreshCalls).toBe(1)
    expect(getToken()).toBe('t')
    expect(authMocks.logout).not.toHaveBeenCalled()
  })

  it('迟到 401 但请求已重试过则直接拒绝（防循环）', async () => {
    request.defaults.adapter = async (config) => {
      captured.push(config)
      setToken('new-token')
      return { data: { code: 401, message: '登录过期' }, status: 200, statusText: 'OK', headers: {}, config }
    }
    authMocks.logout.mockClear()
    setToken('old-token')
    // 手动标记已重试：模拟重发后再次 401
    await expect(request.get('/favorites/list', {})).rejects.toThrow()
    await new Promise((r) => setTimeout(r, 20))
  })

  it('续签请求网络失败（超时）时不断开登录态', async () => {
    request.defaults.adapter = async (config) => {
      captured.push(config)
      if (config.url && config.url.includes('/auth/refresh')) {
        const err = new Error('timeout of 15000ms exceeded')
        err.code = 'ECONNABORTED'
        err.config = config
        err.request = {}
        throw err
      }
      return { data: { code: 401, message: '登录过期' }, status: 200, statusText: 'OK', headers: {}, config }
    }
    authMocks.logout.mockClear()
    authMocks.openLogin.mockClear()
    setToken('old-token')
    await expect(request.get('/favorites/list')).rejects.toThrow('登录过期')
    // 留出动态 import 完成的机会窗口后断言未触发登出
    await new Promise((r) => setTimeout(r, 20))
    expect(authMocks.logout).not.toHaveBeenCalled()
    expect(authMocks.openLogin).not.toHaveBeenCalled()
  })

  it('重发后仍 401 则登出且不再续签（防循环）', async () => {
    let refreshCalls = 0
    request.defaults.adapter = async (config) => {
      captured.push(config)
      if (config.url && config.url.includes('/auth/refresh')) {
        refreshCalls++
        return { data: { code: 200, data: { token: 't2' } }, status: 200, statusText: 'OK', headers: {}, config }
      }
      return { data: { code: 401, message: '登录过期' }, status: 200, statusText: 'OK', headers: {}, config }
    }
    authMocks.logout.mockClear()
    setToken('old-token')
    await expect(request.get('/favorites/list')).rejects.toThrow('登录过期')
    expect(refreshCalls).toBe(1)
    await vi.waitFor(() => expect(authMocks.logout).toHaveBeenCalled())
  })

  it('code=401 但为认证接口时不触发登出', async () => {
    respond = () => ({ code: 401, message: '密码错误' })
    await expect(request.post('/auth/login', { username: 'a' })).rejects.toThrow('密码错误')
    // 留出动态 import 完成的机会窗口后断言未触发
    await new Promise((r) => setTimeout(r, 20))
    expect(authMocks.logout).not.toHaveBeenCalled()
    expect(authMocks.openLogin).not.toHaveBeenCalled()
  })

  it('code=200 的认证接口请求不触发登出', async () => {
    respond = () => ({ code: 200, data: { user: { id: 1 } } })
    const res = await request.get('/auth/me')
    expect(res.data.user.id).toBe(1)
    expect(authMocks.logout).not.toHaveBeenCalled()
  })

  it('HTTP 层 401（adapter 拒绝）且非认证接口时触发登出', async () => {
    request.defaults.adapter = async (config) => {
      captured.push(config)
      const err = new Error('Unauthorized')
      err.response = { status: 401, data: {}, headers: {}, config }
      throw err
    }
    await expect(request.get('/songs/search')).rejects.toThrow('Unauthorized')
    await vi.waitFor(() => expect(authMocks.logout).toHaveBeenCalled())
    expect(authMocks.openLogin).toHaveBeenCalled()
  })
})

describe('请求拦截器', () => {
  it('写方法自动携带 X-Request-Id，GET 不携带', async () => {
    await request.get('/songs/search')
    await request.post('/favorites/toggle', { sourceId: 1 })
    await request.put('/auth/profile', { name: 'x' })
    await request.delete('/playlists/delete', { params: { playlistId: 1 } })
    await request.patch('/songs/update', { id: 1 })

    expect(getHeader(captured[0], 'X-Request-Id')).toBeUndefined()
    const uuidRe = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
    for (let i = 1; i < 5; i++) {
      expect(getHeader(captured[i], 'X-Request-Id')).toMatch(uuidRe)
    }
  })

  it('设置 token 后携带 Bearer，未设置则不携带', async () => {
    setToken('test-token')
    expect(getToken()).toBe('test-token')
    await request.get('/songs/search')
    expect(getHeader(captured[0], 'Authorization')).toBe('Bearer test-token')

    setToken(null)
    await request.get('/songs/search')
    expect(getHeader(captured[1], 'Authorization')).toBeUndefined()
  })

  it('写请求体中的代理 URL 在发出前被还原', async () => {
    await request.post('/favorites/toggle', { coverUrl: PROXIED })
    // transformRequest 会把对象体序列化为 JSON 字符串
    expect(JSON.parse(captured[0].data).coverUrl).toBe(QQ)
  })
})