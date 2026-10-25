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

const QQ_ONLY = [
  { sourceId: '456', name: '青花瓷 (QQ)', artist: '周杰伦', coverUrl: '', platform: 'qq', duration: 240 },
]

const DURATION_SONGS = [
  { sourceId: '1', name: '短歌', artist: 'A', coverUrl: '', platform: 'netease', duration: 120 },
  { sourceId: '2', name: '长歌', artist: 'B', coverUrl: '', platform: 'netease', duration: 320 },
  { sourceId: '3', name: '时长未知', artist: 'C', coverUrl: '', platform: 'netease' },
]

/**
 * 搜索结果表与筛选条是真实子组件（断言要用它们的 DOM），其余子组件照常 stub。
 */
function mountSearch() {
  return shallowMount(SearchView, {
    global: { stubs: { SearchResultTable: false, SearchFilterBar: false } },
  })
}

/** @param {import('@vue/test-utils').VueWrapper} wrapper @param {string} label */
function chip(wrapper, label) {
  const found = wrapper.findAll('.filter-chip').find(b => b.text() === label)
  if (!found) throw new Error(`chip not found: ${label}`)
  return found
}

describe('SearchView 桌面搜索结果渲染', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  /**
   * 等待 URL keyword 触发的首次搜索落地。
   * round6 起 URL keyword 只同步输入框，搜索统一走 300ms 防抖 watcher
   * （此前 immediate 分支会直搜一次，与防抖 watcher 竞态，先发的被 abort 后
   * 会把结果清空、闪一下"未找到结果"）。
   */
  async function flushDebouncedSearch() {
    await vi.waitFor(() => expect(searchSongs).toHaveBeenCalled(), { timeout: 1500 })
    await flushPromises()
  }

  it('挂载时带 keyword 自动搜索并渲染 data.list 歌曲行', async () => {
    searchSongs.mockResolvedValue({ data: { list: SONGS, total: 2, hasMore: false } })
    const wrapper = mountSearch()
    await flushDebouncedSearch()

    expect(searchSongs).toHaveBeenCalledWith('青花瓷', 1, 40)
    const rows = wrapper.findAll('.table-row')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('青花瓷')
    expect(rows[0].text()).toContain('周杰伦')
  })

  it('带 keyword 挂载只发一次搜索请求（防抖直搜不再重复触发）', async () => {
    searchSongs.mockResolvedValue({ data: { list: SONGS, total: 2, hasMore: false } })
    mountSearch()
    await flushDebouncedSearch()
    // 再等一个防抖周期，确认没有第二次请求（旧实现 URL keyword 会直搜 + 防抖各一次）
    await new Promise((r) => setTimeout(r, 400))
    await flushPromises()
    expect(searchSongs).toHaveBeenCalledTimes(1)
  })

  it('后端返回空 list 时显示“未找到结果”而非崩溃', async () => {
    searchSongs.mockResolvedValue({ data: { list: [], total: 0, hasMore: false } })
    const wrapper = mountSearch()
    await flushDebouncedSearch()

    expect(wrapper.findAll('.table-row')).toHaveLength(0)
    expect(wrapper.find('.empty').text()).toContain('未找到结果')
  })

  it('搜索失败时清空结果并显示空状态', async () => {
    searchSongs.mockRejectedValue(new Error('network down'))
    const wrapper = mountSearch()
    await flushDebouncedSearch()

    expect(wrapper.findAll('.table-row')).toHaveLength(0)
    expect(wrapper.find('.empty').exists()).toBe(true)
  })
})

describe('SearchView 结果筛选', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('平台单选后带 platform 重新请求，并用后端返回替换结果', async () => {
    searchSongs.mockResolvedValue({ data: { list: SONGS, total: 2 } })
    const wrapper = mountSearch()
    await vi.waitFor(() => expect(searchSongs).toHaveBeenCalled(), { timeout: 1500 })
    await flushPromises()

    searchSongs.mockResolvedValue({ data: { list: QQ_ONLY, total: 1 } })
    await chip(wrapper, 'QQ 音乐').trigger('click')
    await flushPromises()

    expect(searchSongs).toHaveBeenLastCalledWith('青花瓷', 1, 40, 'qq')
    expect(chip(wrapper, 'QQ 音乐').attributes('aria-pressed')).toBe('true')
    expect(wrapper.findAll('.table-row')).toHaveLength(1)
    expect(wrapper.find('.table-row').text()).toContain('QQ')
  })

  it('切回「全部平台」不再带 platform 参数', async () => {
    searchSongs.mockResolvedValue({ data: { list: SONGS, total: 2 } })
    const wrapper = mountSearch()
    await vi.waitFor(() => expect(searchSongs).toHaveBeenCalled(), { timeout: 1500 })
    await flushPromises()

    await chip(wrapper, 'QQ 音乐').trigger('click')
    await flushPromises()
    await chip(wrapper, '全部平台').trigger('click')
    await flushPromises()

    expect(searchSongs).toHaveBeenLastCalledWith('青花瓷', 1, 40)
    expect(chip(wrapper, '全部平台').attributes('aria-pressed')).toBe('true')
  })

  it('时长筛选走客户端过滤，不重新请求，并显示被筛掉条数', async () => {
    searchSongs.mockResolvedValue({ data: { list: DURATION_SONGS, total: 3 } })
    const wrapper = mountSearch()
    await vi.waitFor(() => expect(searchSongs).toHaveBeenCalled(), { timeout: 1500 })
    await flushPromises()

    await chip(wrapper, '3 分钟内').trigger('click')
    await flushPromises()

    expect(searchSongs).toHaveBeenCalledTimes(1)
    const rows = wrapper.findAll('.table-row')
    expect(rows).toHaveLength(1)
    expect(rows[0].text()).toContain('短歌')
    expect(wrapper.find('.result-hidden').text()).toContain('已按条件隐藏 2 首')
    expect(wrapper.find('.result-meta').text()).toContain('共 1 首')
  })

  it('换关键词时重置筛选，新搜索不再带旧平台条件', async () => {
    searchSongs.mockResolvedValue({ data: { list: SONGS, total: 2 } })
    const wrapper = mountSearch()
    await vi.waitFor(() => expect(searchSongs).toHaveBeenCalled(), { timeout: 1500 })
    await flushPromises()

    await chip(wrapper, 'QQ 音乐').trigger('click')
    await flushPromises()
    await chip(wrapper, '3 分钟内').trigger('click')
    await flushPromises()

    await wrapper.find('.search-input').setValue('稻香')
    await vi.waitFor(
      () => expect(searchSongs).toHaveBeenLastCalledWith('稻香', 1, 40),
      { timeout: 1500 },
    )
    await flushPromises()

    expect(chip(wrapper, '全部平台').attributes('aria-pressed')).toBe('true')
    expect(chip(wrapper, '全部时长').attributes('aria-pressed')).toBe('true')
  })

  it('清除筛选回到全量：平台有限制时重新请求且不带 platform', async () => {
    searchSongs.mockResolvedValue({ data: { list: SONGS, total: 2 } })
    const wrapper = mountSearch()
    await vi.waitFor(() => expect(searchSongs).toHaveBeenCalled(), { timeout: 1500 })
    await flushPromises()

    await chip(wrapper, 'QQ 音乐').trigger('click')
    await flushPromises()
    expect(searchSongs).toHaveBeenLastCalledWith('青花瓷', 1, 40, 'qq')

    await wrapper.find('.filter-clear').trigger('click')
    await flushPromises()

    expect(searchSongs).toHaveBeenLastCalledWith('青花瓷', 1, 40)
    expect(wrapper.findAll('.table-row')).toHaveLength(2)
    expect(wrapper.find('.filter-clear').exists()).toBe(false)
  })

  it('时长筛掉全部结果时给出明确文案 + 一键清除', async () => {
    searchSongs.mockResolvedValue({ data: { list: [DURATION_SONGS[1]] } })
    const wrapper = mountSearch()
    await vi.waitFor(() => expect(searchSongs).toHaveBeenCalled(), { timeout: 1500 })
    await flushPromises()

    await chip(wrapper, '3 分钟内').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.table-row')).toHaveLength(0)
    expect(wrapper.find('.empty').text()).toContain('当前筛选下没有歌曲')
    expect(wrapper.find('.empty-action').exists()).toBe(true)

    await wrapper.find('.empty-action').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.table-row')).toHaveLength(1)
  })

  it('平台筛选返回空结果时提示可切回全部平台', async () => {
    searchSongs.mockResolvedValue({ data: { list: SONGS, total: 2 } })
    const wrapper = mountSearch()
    await vi.waitFor(() => expect(searchSongs).toHaveBeenCalled(), { timeout: 1500 })
    await flushPromises()

    searchSongs.mockResolvedValue({ data: { list: [], total: 0 } })
    await chip(wrapper, '咪咕').trigger('click')
    await flushPromises()

    const empty = wrapper.find('.empty').text()
    expect(empty).toContain('未找到结果')
    expect(empty).toContain('全部平台')
    expect(wrapper.find('.empty-action').exists()).toBe(true)
  })

  it('未输入关键词时不显示筛选条', async () => {
    searchSongs.mockResolvedValue({ data: { list: [] } })
    const wrapper = mountSearch()
    await vi.waitFor(() => expect(searchSongs).toHaveBeenCalled(), { timeout: 1500 })
    await flushPromises()

    await wrapper.find('.search-input').setValue('')
    await flushPromises()

    expect(wrapper.find('.filter-bar').exists()).toBe(false)
    expect(wrapper.find('.empty').text()).toContain('输入关键词搜索歌曲')
  })
})
