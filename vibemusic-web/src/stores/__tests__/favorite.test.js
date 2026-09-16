import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useFavoriteStore } from '@/stores/favorite'

// Mock API 请求层
vi.mock('@/api/song', () => ({
  getFavoriteIds: vi.fn(),
  toggleFavorite: vi.fn(),
}))

// Mock auth store
let mockIsLoggedIn = true
vi.mock('@/stores/auth', () => ({
  useAuthStore: vi.fn(() => ({
    get isLoggedIn() { return mockIsLoggedIn },
  })),
}))

import { getFavoriteIds, toggleFavorite } from '@/api/song'

describe('FavoriteStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mockIsLoggedIn = true
    window.vibeFavIds = undefined
  })

  describe('初始状态', () => {
    it('初始 favIds 应为空 Set', () => {
      const fav = useFavoriteStore()
      expect(fav.favIds.size).toBe(0)
      expect(fav.loaded).toBe(false)
      expect(fav.loading).toBe(false)
    })
  })

  describe('fetchFavIds 加载收藏列表', () => {
    it('成功加载应填充 favIds 并标记 loaded', async () => {
      getFavoriteIds.mockResolvedValue({ data: ['song1', 'song2', 'song3'] })
      const fav = useFavoriteStore()

      await fav.fetchFavIds()

      expect(fav.favIds.size).toBe(3)
      expect(fav.favIds.has('song1')).toBe(true)
      expect(fav.loaded).toBe(true)
      expect(fav.loading).toBe(false)
      expect(window.vibeFavIds).toBeDefined()
    })

    it('并发同账号请求：旧代际响应被丢弃，新代际获胜且 loading 正确复位', async () => {
      let resolveFirst, resolveSecond
      getFavoriteIds
        .mockImplementationOnce(() => new Promise((r) => { resolveFirst = r }))
        .mockImplementationOnce(() => new Promise((r) => { resolveSecond = r }))
      const fav = useFavoriteStore()

      const p1 = fav.fetchFavIds()
      const p2 = fav.fetchFavIds()
      resolveFirst({ data: ['stale'] })
      await p1
      // 旧代际已过期：不回填、不复位 loading（新请求仍在飞）
      expect(fav.favIds.size).toBe(0)
      expect(fav.loading).toBe(true)
      resolveSecond({ data: ['fresh'] })
      await p2

      expect(fav.favIds.has('fresh')).toBe(true)
      expect(fav.favIds.has('stale')).toBe(false)
      expect(fav.loaded).toBe(true)
      expect(fav.loading).toBe(false)
    })

    it('换账号竞态：A 在飞时登出+B 登录，A 的迟到响应不覆盖 B 的数据', async () => {
      let resolveA, resolveB
      getFavoriteIds
        .mockImplementationOnce(() => new Promise((r) => { resolveA = r }))
        .mockImplementationOnce(() => new Promise((r) => { resolveB = r }))
      const fav = useFavoriteStore()

      mockIsLoggedIn = true
      const pA = fav.fetchFavIds() // A 的请求在飞
      mockIsLoggedIn = false // A 登出
      mockIsLoggedIn = true // B 登录
      const pB = fav.fetchFavIds() // B 的请求（旧 loading 门控下会被跳过）
      expect(getFavoriteIds).toHaveBeenCalledTimes(2)
      resolveA({ data: ['songA1', 'songA2'] })
      await pA
      resolveB({ data: ['songB1'] })
      await pB

      expect(fav.favIds.has('songB1')).toBe(true)
      expect(fav.favIds.has('songA1')).toBe(false)
      expect(fav.loading).toBe(false)
      expect(window.vibeFavIds.has('songB1')).toBe(true)
    })

    it('登出后返回的响应直接丢弃，不回填收藏', async () => {
      let resolvePending
      getFavoriteIds.mockImplementationOnce(() => new Promise((r) => { resolvePending = r }))
      const fav = useFavoriteStore()

      mockIsLoggedIn = true
      const p = fav.fetchFavIds()
      mockIsLoggedIn = false // 响应回来前已登出
      resolvePending({ data: ['songA1'] })
      await p

      expect(fav.favIds.size).toBe(0)
      expect(fav.loaded).toBe(false)
      expect(fav.loading).toBe(false)
    })

    it('加载失败应静默处理不抛异常', async () => {
      getFavoriteIds.mockRejectedValue(new Error('网络错误'))
      const fav = useFavoriteStore()

      await expect(fav.fetchFavIds()).resolves.toBeUndefined()
      expect(fav.loaded).toBe(false)
      expect(fav.loading).toBe(false)
    })

    it('空数据不应覆盖已有收藏', async () => {
      getFavoriteIds.mockResolvedValue({ data: null })
      const fav = useFavoriteStore()
      fav.favIds = new Set(['existing'])

      await fav.fetchFavIds()

      expect(fav.favIds.size).toBe(1) // 不被 null 覆盖
    })
  })

  describe('isFav 判断收藏状态', () => {
    it('已收藏的 sourceId 应返回 true', () => {
      const fav = useFavoriteStore()
      fav.favIds = new Set(['song1', 'song2'])

      expect(fav.isFav('song1')).toBe(true)
      expect(fav.isFav('song2')).toBe(true)
    })

    it('未收藏的 sourceId 应返回 false', () => {
      const fav = useFavoriteStore()
      fav.favIds = new Set(['song1'])

      expect(fav.isFav('song999')).toBe(false)
    })

    it('null/undefined sourceId 应返回 false', () => {
      const fav = useFavoriteStore()
      expect(fav.isFav(null)).toBe(false)
      expect(fav.isFav(undefined)).toBe(false)
      expect(fav.isFav('')).toBe(false)
    })
  })

  describe('toggleFav 切换收藏（乐观更新 + 回滚）', () => {
    it('收藏未收藏的歌曲：乐观添加 + 后端确认', async () => {
      toggleFavorite.mockResolvedValue({ data: true })
      const fav = useFavoriteStore()
      fav.favIds = new Set()

      await fav.toggleFav({ sourceId: 'song1', name: '晴天', artist: '周杰伦', coverUrl: '' })

      expect(fav.favIds.has('song1')).toBe(true)
      expect(window.vibeFavIds.has('song1')).toBe(true)
    })

    it('取消已收藏的歌曲：乐观删除 + 后端确认', async () => {
      toggleFavorite.mockResolvedValue({ data: false })
      const fav = useFavoriteStore()
      fav.favIds = new Set(['song1'])

      await fav.toggleFav({ sourceId: 'song1' })

      expect(fav.favIds.has('song1')).toBe(false)
    })

    it('后端返回 true 但前端已删除：以后端为准重新添加', async () => {
      toggleFavorite.mockResolvedValue({ data: true })
      const fav = useFavoriteStore()
      fav.favIds = new Set(['song1']) // 原本已收藏

      await fav.toggleFav({ sourceId: 'song1' })

      // 乐观先删除，后端说 true（已收藏），所以重新添加
      expect(fav.favIds.has('song1')).toBe(true)
    })

    it('网络异常应回滚到原始状态', async () => {
      toggleFavorite.mockRejectedValue(new Error('网络错误'))
      const fav = useFavoriteStore()
      fav.favIds = new Set(['song1']) // 原本已收藏

      await fav.toggleFav({ sourceId: 'song1' })

      // 乐观先删除，网络失败后回滚，应该还在收藏中
      expect(fav.favIds.has('song1')).toBe(true)
    })

    it('网络异常应 toast 提示（静默失败不可接受）', async () => {
      toggleFavorite.mockRejectedValue(new Error('网络错误'))
      window.toast = vi.fn()
      const fav = useFavoriteStore()
      fav.favIds = new Set()

      await fav.toggleFav({ sourceId: 'song9' })

      expect(window.toast).toHaveBeenCalledWith('操作失败', 'error')
      window.toast = undefined
    })

    it('无 sourceId 的歌曲应直接跳过', async () => {
      const fav = useFavoriteStore()
      await fav.toggleFav({})
      expect(toggleFavorite).not.toHaveBeenCalled()
    })

    it('应兼容不同字段名（sourceId / id / songId）', async () => {
      toggleFavorite.mockResolvedValue({ data: true })
      const fav = useFavoriteStore()

      await fav.toggleFav({ id: 'alt-id', name: '测试' })
      expect(fav.favIds.has('alt-id')).toBe(true)
      expect(toggleFavorite).toHaveBeenCalledWith('alt-id', '测试', '', '')
    })
  })

  describe('fetchFavIds 登录门控', () => {
    it('未登录时不应发起请求', async () => {
      mockIsLoggedIn = false
      const fav = useFavoriteStore()
      await fav.fetchFavIds()
      expect(getFavoriteIds).not.toHaveBeenCalled()
      expect(fav.favIds.size).toBe(0)
      expect(fav.loaded).toBe(false)
    })
  })
})
