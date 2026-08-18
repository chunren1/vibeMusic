import { describe, it, expect, beforeEach, vi } from 'vitest'
import { shallowMount, flushPromises } from '@vue/test-utils'
import BannerSection from '@/components/BannerSection.vue'

// 模拟 API：默认返回合法 banner；单测内按需覆写
vi.mock('@/api/song', () => ({
  getBanners: vi.fn(),
}))

import { getBanners } from '@/api/song'

function getPreloadLink() {
  return document.head.querySelector('link[data-banner-preload]')
}

describe('BannerSection preload 首图', () => {
  beforeEach(() => {
    document.head.querySelectorAll('link[data-banner-preload]').forEach(l => l.remove())
    vi.clearAllMocks()
  })

  it('正常 coverUrl 时注入 href 并带 param', async () => {
    getBanners.mockResolvedValue({ data: [{ coverUrl: 'https://p4.music.126.net/abc.jpg', name: 'b1' }] })
    shallowMount(BannerSection)
    await flushPromises()
    const link = getPreloadLink()
    expect(link).toBeTruthy()
    expect(link.href).toBe('https://p4.music.126.net/abc.jpg?param=1600y900')
  })

  it('coverUrl 已带 query 时不产生双 ? 拼接', async () => {
    getBanners.mockResolvedValue({ data: [{ coverUrl: 'https://p4.music.126.net/abc.jpg?param=200y200', name: 'b1' }] })
    shallowMount(BannerSection)
    await flushPromises()
    const link = getPreloadLink()
    expect(link).toBeTruthy()
    // 不允许出现非法双 ?（valid href 的 query 中只允许一个 ? 分隔符）
    expect(link.href).not.toContain('?param=200y200?')
    expect(link.href).toBe('https://p4.music.126.net/abc.jpg?param=1600y900')
  })

  it('coverUrl 含空格时被安全编码（不再产生 invalid href value）', async () => {
    getBanners.mockResolvedValue({ data: [{ coverUrl: 'https://p4.music.126.net/ab c.jpg', name: 'b1' }] })
    shallowMount(BannerSection)
    await flushPromises()
    const link = getPreloadLink()
    expect(link).toBeTruthy()
    expect(link.href).toBe('https://p4.music.126.net/ab%20c.jpg?param=1600y900')
  })

  it('完全无法解析的 URL（host 含空格）不注入 preload', async () => {
    getBanners.mockResolvedValue({ data: [{ coverUrl: 'https://exa mple.com/x.jpg', name: 'b1' }] })
    shallowMount(BannerSection)
    await flushPromises()
    expect(getPreloadLink()).toBeNull()
  })

  it('空 coverUrl 时不注入 preload', async () => {
    getBanners.mockResolvedValue({ data: [{ coverUrl: '', name: 'b1' }] })
    shallowMount(BannerSection)
    await flushPromises()
    expect(getPreloadLink()).toBeNull()
  })
})