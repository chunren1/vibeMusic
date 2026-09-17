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
import LikesView from '@/views/LikesView.vue'

const SONGS = [
  { sourceId: 's1', songName: '晴天', artist: '周杰伦', coverUrl: '', createdAt: '2026-01-01' },
  { sourceId: 's2', songName: '夜曲', artist: '周杰伦', coverUrl: '', createdAt: '2026-01-02' },
]

function mountView() {
  return shallowMount(LikesView, {
    global: {
      stubs: {
        TopBar: { template: '<div class="mock-topbar" />' },
        PlaylistPopup: { template: '<div class="mock-playlist-popup" />' },
        SvgIcon: { template: '<span class="mock-svg" />', props: ['name', 'size'] },
      },
    },
  })
}

describe('LikesView 批量取消收藏', () => {
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

    // 进入管理模式并选中第一首
    await wrapper.find('.btn-manage').trigger('click')
    await wrapper.findAll('.table-row')[0].trigger('click')
    expect(wrapper.find('.batch-btn').exists()).toBe(true)

    await wrapper.find('.batch-btn').trigger('click')
    await flushPromises()

    // 真实结果：接口被调、本地列表与 store 同步更新
    expect(removeFavoritesBatch).toHaveBeenCalledWith(['s1'])
    expect(favStore.favIds.has('s1')).toBe(false)
    expect(favStore.favIds.has('s2')).toBe(true)
    expect(wrapper.findAll('.table-row')).toHaveLength(1)
    // 成功提示 + 退出管理模式
    expect(window.toast).toHaveBeenCalledWith('已移除', 'success')
    expect(wrapper.find('.btn-manage').text()).toBe('管理')
    expect(wrapper.find('.batch-btn').exists()).toBe(false)
  })

  it('失败时显示错误且管理模式/选择被重置、可再次操作', async () => {
    removeFavoritesBatch.mockRejectedValueOnce(new Error('网络错误'))
    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('.btn-manage').trigger('click')
    await wrapper.findAll('.table-row')[0].trigger('click')
    await wrapper.find('.batch-btn').trigger('click')
    await flushPromises()

    expect(window.toast).toHaveBeenCalledWith('操作失败', 'error')
    expect(wrapper.find('.btn-manage').text()).toBe('管理')
    expect(wrapper.find('.batch-btn').exists()).toBe(false)
  })
})
