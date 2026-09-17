import { describe, it, expect, beforeEach, vi } from 'vitest'
import { shallowMount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: { keyword: '青花瓷' } }),
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('@/api/request', () => ({
  default: { get: vi.fn() },
  API_HOST: '',
  getToken: vi.fn().mockReturnValue(null),
  setToken: vi.fn(),
}))

vi.mock('@/api/song', () => ({
  searchSongs: vi.fn(),
  playSong: vi.fn(),
  getFavoriteIds: vi.fn().mockResolvedValue({ data: [] }),
  toggleFavorite: vi.fn(),
}))

import { searchSongs } from '@/api/song'
import SearchView from '@/views/SearchView.vue'

const SONGS = [
  { sourceId: '123', name: '青花瓷', artist: '周杰伦', coverUrl: '', platform: 'netease', duration: 239 },
  { sourceId: '456', name: '青花瓷', artist: '周杰伦', coverUrl: '', platform: 'qq', duration: 240 },
]

describe('SearchView 桌面搜索结果渲染', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('挂载时带 keyword 自动搜索并渲染 data.list 歌曲行', async () => {
    searchSongs.mockResolvedValue({ data: { list: SONGS, total: 2, hasMore: false } })
    const wrapper = shallowMount(SearchView)
    await flushPromises()

    expect(searchSongs).toHaveBeenCalledWith('青花瓷', 1, 40)
    const rows = wrapper.findAll('.table-row')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('青花瓷')
    expect(rows[0].text()).toContain('周杰伦')
  })

  it('后端返回空 list 时显示“未找到结果”而非崩溃', async () => {
    searchSongs.mockResolvedValue({ data: { list: [], total: 0, hasMore: false } })
    const wrapper = shallowMount(SearchView)
    await flushPromises()

    expect(wrapper.findAll('.table-row')).toHaveLength(0)
    expect(wrapper.find('.empty').text()).toContain('未找到结果')
  })

  it('搜索失败时清空结果并显示空状态', async () => {
    searchSongs.mockRejectedValue(new Error('network down'))
    const wrapper = shallowMount(SearchView)
    await flushPromises()

    expect(wrapper.findAll('.table-row')).toHaveLength(0)
    expect(wrapper.find('.empty').exists()).toBe(true)
  })
})
