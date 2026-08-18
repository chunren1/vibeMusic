import { describe, it, expect, beforeEach, vi } from 'vitest'
import { shallowMount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'

vi.mock('@/api/request', () => ({
  default: { get: vi.fn().mockResolvedValue({ data: [] }) },
  API_HOST: '',
  getToken: vi.fn().mockReturnValue(null),
  setToken: vi.fn(),
}))

vi.mock('@/api/song', () => ({
  searchSongs: vi.fn(),
  downloadSong: vi.fn().mockResolvedValue({ data: { fileUrl: '/x.mp3' } }),
}))

import { searchSongs } from '@/api/song'
import HomeView from '@/views/HomeView.vue'

describe('HomeView 搜索防抖', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('连续输入时 300ms 内只发 1 次搜索建议请求（size=8）', async () => {
    searchSongs.mockResolvedValue({ data: { list: [] } })
    const wrapper = shallowMount(HomeView)
    await flushPromises()

    const input = wrapper.find('input.search-input')
    // 模拟逐键输入 x → xi → xia（300ms 防抖窗口内）
    for (const kw of ['x', 'xi', 'xia']) {
      await input.setValue(kw)
      await input.trigger('input')
      vi.advanceTimersByTime(100)
    }
    vi.advanceTimersByTime(300)
    await flushPromises()

    expect(searchSongs).toHaveBeenCalledTimes(1)
    expect(searchSongs).toHaveBeenCalledWith('xia', 1, 8)
  })

  it('停止输入超过防抖窗口后再次输入会发新请求', async () => {
    searchSongs.mockResolvedValue({ data: { list: [] } })
    const wrapper = shallowMount(HomeView)
    await flushPromises()

    const input = wrapper.find('input.search-input')
    await input.setValue('x')
    await input.trigger('input')
    vi.advanceTimersByTime(400)
    await flushPromises()
    expect(searchSongs).toHaveBeenCalledTimes(1)

    await input.setValue('xia')
    await input.trigger('input')
    vi.advanceTimersByTime(400)
    await flushPromises()
    expect(searchSongs).toHaveBeenCalledTimes(2)
  })
})