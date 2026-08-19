import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import vLazyImg from '@/directives/vLazyImg'

// jsdom 无 IntersectionObserver，mock 之
class MockIntersectionObserver {
  constructor(callback) { this.callback = callback; this.observed = [] }
  observe(el) { this.observed.push(el) }
  unobserve() {}
  disconnect() {}
}
vi.stubGlobal('IntersectionObserver', MockIntersectionObserver)

/** 构造虚拟 DOM 元素（前 6 个兄弟会立即加载，需模拟 parentNode） */
function makeEl({ siblings = 8, idx = 0 } = {}) {
  const children = Array.from({ length: siblings }, () => ({ tagName: 'DIV' }))
  const el = { tagName: 'DIV', style: { setProperty: vi.fn(), backgroundColor: '' } }
  children[idx] = el
  el.parentNode = { children }
  return { el, children }
}

function binding(value, arg = 'bg') {
  return { value, arg, instance: null, dir: {}, oldValue: undefined, modifiers: {} }
}

describe('vLazyImg', () => {
  afterEach(() => vi.restoreAllMocks())

  describe('bg 模式', () => {
    it('绝对 http URL → 设置 background-image', () => {
      const { el } = makeEl({ idx: 0 })
      vLazyImg.mounted(el, binding('https://p3.music.126.net/a.jpg?param=200y200', 'bg'))
      expect(el.style.setProperty).toHaveBeenCalledWith('background-image', 'url(https://p3.music.126.net/a.jpg?param=200y200)', 'important')
    })

    it('协议相对 URL // → 设置 background-image', () => {
      const { el } = makeEl({ idx: 0 })
      vLazyImg.mounted(el, binding('//p3.music.126.net/a.jpg', 'bg'))
      expect(el.style.setProperty).toHaveBeenCalledWith('background-image', 'url(//p3.music.126.net/a.jpg)', 'important')
    })

    it('相对路径代理 URL /api/image-proxy?... → 设置 background-image（QQ 封面代理地址）', () => {
      const { el } = makeEl({ idx: 0 })
      const proxied = '/api/image-proxy?url=https%3A%2F%2Fy.gtimg.cn%2Fa.jpg'
      vLazyImg.mounted(el, binding(proxied, 'bg'))
      expect(el.style.setProperty).toHaveBeenCalledWith('background-image', `url(${proxied})`, 'important')
    })

    it('纯色值 #31c27c → 设背景色而非背景图', () => {
      const { el } = makeEl({ idx: 0 })
      vLazyImg.mounted(el, binding('#31c27c', 'bg'))
      expect(el.style.setProperty).toHaveBeenCalledWith('background-image', 'none', 'important')
      expect(el.style.backgroundColor).toBe('#31c27c')
    })

    it('第 7 个及之后的兄弟元素交给 IntersectionObserver，不立即加载', () => {
      const { el } = makeEl({ idx: 7 })
      const obs = new MockIntersectionObserver(() => {})
      const observeSpy = vi.spyOn(obs, 'observe')
      vi.spyOn(vLazyImg, 'unmounted').mockImplementation(() => {})
      // getObserver 返回全局 observer，这里验证行为：未立即 apply → style 无 background-image
      vLazyImg.mounted(el, binding('https://p3.music.126.net/b.jpg', 'bg'))
      expect(el.style.setProperty).not.toHaveBeenCalled()
      expect(observeSpy).not.toHaveBeenCalledWith(el) // 观察器由全局单例管理，此处仅确认未立即加载
    })
  })

  describe('img 模式', () => {
    it('<img> 设置 src', () => {
      const { el } = makeEl({ idx: 0 })
      el.tagName = 'IMG'
      vLazyImg.mounted(el, binding('https://p3.music.126.net/a.jpg', 'img'))
      expect(el.src).toBe('https://p3.music.126.net/a.jpg')
    })

    it('<img> 相对路径代理 URL 也设置 src', () => {
      const { el } = makeEl({ idx: 0 })
      el.tagName = 'IMG'
      const proxied = '/api/image-proxy?url=https%3A%2F%2Fy.gtimg.cn%2Fa.jpg'
      vLazyImg.mounted(el, binding(proxied, 'img'))
      expect(el.src).toBe(proxied)
    })
  })

  describe('updated', () => {
    it('URL 变化时重置并重新加载（bg 模式相对路径）', () => {
      const { el } = makeEl({ idx: 0 })
      vLazyImg.mounted(el, binding('/api/image-proxy?url=A', 'bg'))
      el.style.setProperty.mockClear()
      vLazyImg.updated(el, binding('/api/image-proxy?url=B', 'bg'))
      expect(el.style.setProperty).toHaveBeenCalledWith('background-image', 'url(/api/image-proxy?url=B)', 'important')
    })

    it('URL 未变化时观察器元素不重复重置（_lazyUrl 保留）', () => {
      // 第 7+ 个元素进入观察器，mounted 保留 _lazyUrl
      const { el } = makeEl({ idx: 7 })
      vLazyImg.mounted(el, binding('https://p3.music.126.net/a.jpg', 'bg'))
      expect(el._lazyUrl).toBe('https://p3.music.126.net/a.jpg')
      // updated 同值 → guard 命中，_lazyUrl 不被删除
      vLazyImg.updated(el, binding('https://p3.music.126.net/a.jpg', 'bg'))
      expect(el._lazyUrl).toBe('https://p3.music.126.net/a.jpg')
    })
  })
})