import { describe, it, expect, beforeEach, vi } from 'vitest'
import { shallowMount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { useFavoriteStore } from '@/stores/favorite'

vi.mock('@/api/song', () => ({
  getFavorites: vi.fn(),
  getFavoriteIds: vi.fn(),
  toggleFavorite: vi.fn(),
  removeFavoritesBatch: vi.fn(),
}))

import { getFavorites, removeFavoritesBatch } from '@/api/song'
import MLikesView from '@/views/mobile/MLikesView.vue'

const SONGS = [
  { sourceId: 's1', songName: '晴天', artist: '周杰伦', coverUrl: '' },
  { sourceId: 's2', songName: '夜曲', artist: '周杰伦', coverUrl: '' },
]

function mountView() {
  return shallowMount(MLikesView, {
    global: {
      stubs: {
        SvgIcon: { template: '<span class="mock-svg" />', props: ['name', 'size', 'color'] },
      },
    },
  })
}

describe('MLikesView 批量取消收藏', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    window.toast = vi.fn()
    getFavorites.mockResolvedValue({ data: SONGS })
    removeFavoritesBatch.mockResolvedValue({ data: true })
  })

  it('成功时调批量接口、更新 favIds、显示成功并退出管理模式', async () => {
    const wrapper = mountView()
    await flushPromises()

    const favStore = useFavoriteStore()
    favStore.favIds = new Set(['s1', 's2'])

    await wrapper.find('.m-manage-btn').trigger('click')
    await wrapper.findAll('.m-item')[0].trigger('click')
    expect(wrapper.find('.m-batch-btn').exists()).toBe(true)

    await wrapper.find('.m-batch-btn').trigger('click')
    await flushPromises()

    expect(removeFavoritesBatch).toHaveBeenCalledWith(['s1'])
    expect(favStore.favIds.has('s1')).toBe(false)
    expect(favStore.favIds.has('s2')).toBe(true)
    expect(wrapper.findAll('.m-item')).toHaveLength(1)
    expect(window.toast).toHaveBeenCalledWith('已移除 1 首', 'success')
    expect(wrapper.find('.m-manage-btn').text()).toBe('管理')
    expect(wrapper.find('.m-batch-btn').exists()).toBe(false)
  })

  it('失败时显示错误且管理模式/选择被重置、可再次操作', async () => {
    removeFavoritesBatch.mockRejectedValueOnce(new Error('网络错误'))
    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('.m-manage-btn').trigger('click')
    await wrapper.findAll('.m-item')[0].trigger('click')
    await wrapper.find('.m-batch-btn').trigger('click')
    await flushPromises()

    expect(window.toast).toHaveBeenCalledWith('操作失败', 'error')
    expect(wrapper.find('.m-manage-btn').text()).toBe('管理')
    expect(wrapper.find('.m-batch-btn').exists()).toBe(false)
  })
})
