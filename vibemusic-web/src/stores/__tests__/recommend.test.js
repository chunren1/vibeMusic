import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

// 推荐 store 此前没有任何测试，覆盖率门禁（vitest.config.js lines 37%）在 36.95% 卡住。
// 这里覆盖 store 的四条真实路径：正常返回 / 并发重入 / 降级随机 / 双失败清空。
vi.mock('@/api/song', () => ({
  getPersonalizedRecommend: vi.fn(),
  getRandomSongs: vi.fn(),
}))

import { getPersonalizedRecommend, getRandomSongs } from '@/api/song'
import { useRecommendStore } from '@/stores/recommend'

const DEVICE_ID_KEY = 'vibe_device_id'

describe('RecommendStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    localStorage.clear()
  })

  afterEach(() => {
    localStorage.clear()
  })

  it('初始状态为空且 hasData=false', () => {
    const store = useRecommendStore()
    expect(store.songs).toEqual([])
    expect(store.greeting).toBe('')
    expect(store.error).toBe('')
    expect(store.loading).toBe(false)
    expect(store.hasData).toBe(false)
  })

  it('deviceId 首次生成并持久化，第二次复用同一个值', async () => {
    getPersonalizedRecommend.mockResolvedValue({ data: { songs: [], greeting: '' } })
    const store = useRecommendStore()
    await store.fetchRecommend()
    const first = localStorage.getItem(DEVICE_ID_KEY)
    expect(first).toMatch(/^dev_/)

    // 重新建 store（模拟刷新页面）应复用同一 deviceId
    setActivePinia(createPinia())
    const store2 = useRecommendStore()
    await store2.fetchRecommend()
    expect(localStorage.getItem(DEVICE_ID_KEY)).toBe(first)
    expect(getPersonalizedRecommend).toHaveBeenLastCalledWith(first, false)
  })

  it('正常返回：写入 songs（补 coverColor）与 greeting', async () => {
    getPersonalizedRecommend.mockResolvedValue({
      data: {
        songs: [{ id: '1', name: '晴天' }, { id: '2', name: '夜曲' }],
        greeting: '早上好',
      },
    })
    const store = useRecommendStore()
    await store.fetchRecommend(true)

    expect(store.songs).toHaveLength(2)
    expect(store.songs[0]).toMatchObject({ id: '1', name: '晴天' })
    expect(typeof store.songs[0].coverColor).toBe('string')
    expect(store.greeting).toBe('早上好')
    expect(store.hasData).toBe(true)
    expect(store.loading).toBe(false)
    expect(store.error).toBe('')
    expect(getPersonalizedRecommend).toHaveBeenCalledWith(expect.stringMatching(/^dev_/), true)
  })

  it('返回 data 为空时不清空已有歌曲（只在 data 存在时覆盖）', async () => {
    getPersonalizedRecommend.mockResolvedValue({ data: null })
    const store = useRecommendStore()
    store.songs = [{ id: 'old' }]
    await store.fetchRecommend()
    expect(store.songs).toEqual([{ id: 'old' }])
    expect(store.loading).toBe(false)
  })

  it('并发重入：loading 期间再次调用直接返回，不发第二次请求', async () => {
    let resolveFirst
    getPersonalizedRecommend.mockImplementation(
      () => new Promise((res) => { resolveFirst = res })
    )
    const store = useRecommendStore()
    const p1 = store.fetchRecommend()
    expect(store.loading).toBe(true)
    await store.fetchRecommend() // 应立刻返回
    expect(getPersonalizedRecommend).toHaveBeenCalledTimes(1)
    resolveFirst({ data: { songs: [{ id: 'x' }], greeting: 'hi' } })
    await p1
    expect(store.loading).toBe(false)
  })

  it('推荐接口异常：降级为随机推荐并给出提示', async () => {
    getPersonalizedRecommend.mockRejectedValue(new Error('500'))
    getRandomSongs.mockResolvedValue({ data: [{ id: 'r1' }, { id: 'r2' }] })
    const store = useRecommendStore()
    await store.fetchRecommend()

    expect(store.error).toBe('推荐服务暂时不可用，已为您展示精选歌曲')
    expect(store.greeting).toBe('为你精选了一些好歌~')
    expect(store.songs).toHaveLength(2)
    expect(store.songs[0].coverColor).toBeDefined()
    expect(getRandomSongs).toHaveBeenCalledWith(8)
  })

  it('降级也失败：清空歌曲与问候语，loading 复位', async () => {
    getPersonalizedRecommend.mockRejectedValue(new Error('500'))
    getRandomSongs.mockRejectedValue(new Error('502'))
    const store = useRecommendStore()
    await store.fetchRecommend()

    expect(store.songs).toEqual([])
    expect(store.greeting).toBe('')
    expect(store.loading).toBe(false)
    expect(store.hasData).toBe(false)
  })

  it('localStorage 不可用时 deviceId 退化为 null，仍能请求', async () => {
    const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    setActivePinia(createPinia())
    getPersonalizedRecommend.mockResolvedValue({ data: { songs: [], greeting: '' } })
    const store = useRecommendStore()
    await store.fetchRecommend()
    expect(getPersonalizedRecommend).toHaveBeenCalledWith(null, false)
    spy.mockRestore()
  })
})
