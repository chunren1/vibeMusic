import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useFavoriteStore } from '@/stores/favorite'

// Mock API 请求层
vi.mock('@/api/song', () => ({
  getFavoriteIds: vi.fn(),
  toggleFavorite: vi.fn(),
}))

import { getFavoriteIds, toggleFavorite } from '@/api/song'

describe('FavoriteStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
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

    it('加载中重复调用应跳过', async () => {
      getFavoriteIds.mockResolvedValue({ data: ['song1'] })
      const fav = useFavoriteStore()
      fav.loading = true

      await fav.fetchFavIds()

      expect(getFavoriteIds).not.toHaveBeenCalled()
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
})
