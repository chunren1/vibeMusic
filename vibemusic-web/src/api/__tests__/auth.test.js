import { describe, it, expect, vi, beforeEach } from 'vitest'
import request from '../request'
import { logout, saveNeteaseCookie, deleteNeteaseCookie, cookieStatus } from '../auth'

vi.mock('../request', () => ({
  default: {
    post: vi.fn(),
    get: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}))

describe('auth api', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('logout 应 POST /auth/logout 且超时 5s', async () => {
    request.post.mockResolvedValue({ code: 200, data: 'ok' })

    await logout()

    expect(request.post).toHaveBeenCalledTimes(1)
    expect(request.post).toHaveBeenCalledWith('/auth/logout', {}, { timeout: 5000 })
  })

  it('saveNeteaseCookie 应 PUT /cookies/netease 且不经 localStorage', async () => {
    request.put.mockResolvedValue({ code: 200, data: { bound: true } })

    const res = await saveNeteaseCookie('MUSIC_U=xxx')

    expect(request.put).toHaveBeenCalledTimes(1)
    expect(request.put).toHaveBeenCalledWith('/cookies/netease', { cookie: 'MUSIC_U=xxx' })
    expect(res.data.bound).toBe(true)
    expect(localStorage.getItem('netease_cookie')).toBeNull()
  })

  it('deleteNeteaseCookie 应 DELETE /cookies/netease', async () => {
    request.delete.mockResolvedValue({ code: 200, data: 'ok' })

    await deleteNeteaseCookie()

    expect(request.delete).toHaveBeenCalledTimes(1)
    expect(request.delete).toHaveBeenCalledWith('/cookies/netease')
  })

  it('cookieStatus 应 GET /cookies/status', async () => {
    request.get.mockResolvedValue({ code: 200, data: { netease: { has: true } } })

    const res = await cookieStatus()

    expect(request.get).toHaveBeenCalledTimes(1)
    expect(request.get).toHaveBeenCalledWith('/cookies/status')
    expect(res.data.netease.has).toBe(true)
  })
})
