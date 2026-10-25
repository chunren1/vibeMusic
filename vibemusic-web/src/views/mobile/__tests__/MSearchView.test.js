import { describe, it, expect, beforeEach, vi } from 'vitest'
import { shallowMount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: { q: '晴天' } }),
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('@/api/song', () => ({
  searchSongs: vi.fn(),
  downloadSong: vi.fn(() => Promise.resolve({})),
  getFavoriteIds: vi.fn().mockResolvedValue({ data: [] }),
  toggleFavorite: vi.fn(),
}))

import { searchSongs } from '@/api/song'
import MSearchView from '@/views/mobile/MSearchView.vue'

const SONGS = [
  { sourceId: '1', name: '晴天', artist: '周杰伦', coverUrl: '', platform: 'netease', duration: 120 },
  { sourceId: '2', name: '晴天 (Live)', artist: '周杰伦', coverUrl: '', platform: 'qq', duration: 400 },
]

/** 歌曲行与筛选条用真实组件，其余 stub */
function mountSearch() {
  return shallowMount(MSearchView, {
    global: { stubs: { MSearchRow: false, SearchFilterBar: false } },
  })
}

/** @param {import('@vue/test-utils').VueWrapper} wrapper @param {string} label */
function chip(wrapper, label) {
  const found = wrapper.findAll('.filter-chip').find(b => b.text() === label)
  if (!found) throw new Error(`chip not found: ${label}`)
  return found
}

describe('MSearchView 结果筛选', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('首屏按关键词搜索，平台切换后带 platform 重新请求', async () => {
    searchSongs.mockResolvedValue({ data: { list: SONGS, hasMore: false } })
    const wrapper = mountSearch()
    await flushPromises()

    expect(searchSongs).toHaveBeenCalledWith('晴天', 1, 20, null)
    expect(wrapper.findAll('.m-song-item')).toHaveLength(2)

    await chip(wrapper, '酷狗').trigger('click')
    await flushPromises()

    expect(searchSongs).toHaveBeenLastCalledWith('晴天', 1, 20, 'kugou')
    expect(chip(wrapper, '酷狗').attributes('aria-pressed')).toBe('true')
  })

  it('时长筛选是客户端过滤，显示隐藏条数且不重新请求', async () => {
    searchSongs.mockResolvedValue({ data: { list: SONGS, hasMore: false } })
    const wrapper = mountSearch()
    await flushPromises()

    await chip(wrapper, '5 分钟以上').trigger('click')
    await flushPromises()

    expect(searchSongs).toHaveBeenCalledTimes(1)
    expect(wrapper.findAll('.m-song-item')).toHaveLength(1)
    expect(wrapper.find('.m-filter-note').text()).toContain('已按条件隐藏 1 首')
  })

  it('换关键词时重置平台筛选', async () => {
    searchSongs.mockResolvedValue({ data: { list: SONGS, hasMore: false } })
    const wrapper = mountSearch()
    await flushPromises()

    await chip(wrapper, '酷狗').trigger('click')
    await flushPromises()

    await wrapper.find('.m-search-input').setValue('稻香')
    await vi.waitFor(
      () => expect(searchSongs).toHaveBeenLastCalledWith('稻香', 1, 20, null),
      { timeout: 2000 },
    )

    expect(chip(wrapper, '全部平台').attributes('aria-pressed')).toBe('true')
  })

  it('平台筛选返回空时提示可切回全部平台', async () => {
    searchSongs.mockResolvedValue({ data: { list: SONGS, hasMore: false } })
    const wrapper = mountSearch()
    await flushPromises()

    searchSongs.mockResolvedValue({ data: { list: [], hasMore: false } })
    await chip(wrapper, '咪咕').trigger('click')
    await flushPromises()

    const empty = wrapper.find('.m-empty').text()
    expect(empty).toContain('未找到相关歌曲')
    expect(empty).toContain('全部平台')
    expect(wrapper.find('.m-empty-action').exists()).toBe(true)
  })
})
