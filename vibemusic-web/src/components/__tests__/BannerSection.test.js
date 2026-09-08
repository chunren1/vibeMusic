import { describe, it, expect, beforeEach, vi } from 'vitest'
import { shallowMount, flushPromises } from '@vue/test-utils'
import BannerSection from '@/components/BannerSection.vue'

// 模拟 API：默认返回合法 banner；单测内按需覆写
vi.mock('@/api/song', () => ({
  getBanners: vi.fn(),
}))

import { getBanners } from '@/api/song'

describe('BannerSection preload 首图', () => {
  let createdImages = []
  beforeEach(() => {
    vi.clearAllMocks()
    createdImages = []
    vi.stubGlobal('Image', vi.fn(function ImageMock() { createdImages.push(this) }))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('正常 coverUrl 时用 Image 预取并带 param', async () => {
    getBanners.mockResolvedValue({ data: [{ coverUrl: 'https://p4.music.126.net/abc.jpg', name: 'b1' }] })
    shallowMount(BannerSection)
    await flushPromises()
    expect(createdImages).toHaveLength(1)
    expect(createdImages[0].src).toBe('https://p4.music.126.net/abc.jpg?param=1600y900')
  })

  it('coverUrl 已带 query 时用 & 追加 param（不产生双 ?）', async () => {
    getBanners.mockResolvedValue({ data: [{ coverUrl: 'https://p4.music.126.net/abc.jpg?param=200y200', name: 'b1' }] })
    shallowMount(BannerSection)
    await flushPromises()
    expect(createdImages).toHaveLength(1)
    expect(createdImages[0].src).toBe('https://p4.music.126.net/abc.jpg?param=200y200&param=1600y900')
  })

  it('coverUrl 含空格时被安全编码', async () => {
    getBanners.mockResolvedValue({ data: [{ coverUrl: 'https://p4.music.126.net/ab c.jpg', name: 'b1' }] })
    shallowMount(BannerSection)
    await flushPromises()
    expect(createdImages).toHaveLength(1)
    expect(createdImages[0].src).toBe('https://p4.music.126.net/ab%20c.jpg?param=1600y900')
  })

  it('完全无法解析的 URL（host 含空格）不做 Image 预取', async () => {
    getBanners.mockResolvedValue({ data: [{ coverUrl: 'https://exa mple.com/x.jpg', name: 'b1' }] })
    shallowMount(BannerSection)
    await flushPromises()
    expect(createdImages).toHaveLength(0)
  })

  it('空 coverUrl 时不做 Image 预取', async () => {
    getBanners.mockResolvedValue({ data: [{ coverUrl: '', name: 'b1' }] })
    shallowMount(BannerSection)
    await flushPromises()
    expect(createdImages).toHaveLength(0)
  })

  it('只有激活的 slide 设置 background-image', async () => {
    getBanners.mockResolvedValue({
      data: [
        { coverUrl: 'https://p4.music.126.net/a.jpg', name: 'b1' },
        { coverUrl: 'https://p4.music.126.net/b.jpg', name: 'b2' },
        { coverUrl: 'https://p4.music.126.net/c.jpg', name: 'b3' },
      ]
    })
    const wrapper = shallowMount(BannerSection)
    await flushPromises()
    const slides = wrapper.findAll('.banner-slide')
    expect(slides).toHaveLength(3)
    const activeStyles = slides.map(s => s.attributes('style'))
    const withBg = activeStyles.filter(s => s && s.includes('background-image'))
    expect(withBg).toHaveLength(1)
  })
})