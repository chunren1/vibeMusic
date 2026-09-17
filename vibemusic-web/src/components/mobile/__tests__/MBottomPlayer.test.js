import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { shallowMount } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { usePlayerStore } from '@/stores/player'

vi.mock('vue-router', () => ({
  useRoute: vi.fn(() => ({ path: '/m/home' })),
  useRouter: vi.fn(() => ({ push: vi.fn() })),
}))

import MBottomPlayer from '@/components/mobile/MBottomPlayer.vue'

function mountPlayer() {
  return shallowMount(MBottomPlayer, {
    global: {
      stubs: {
        SvgIcon: { template: '<span class="mock-svg" />', props: ['name', 'size', 'color'] },
      },
    },
  })
}

describe('MBottomPlayer 进度定时保存', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.useFakeTimers()
    const player = usePlayerStore()
    player.currentSong = { id: 's1', title: '晴天', artist: '周杰伦', coverUrl: '', duration: 269 }
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('挂载不抛异常且定时器真正启动（autosave 运行）', () => {
    const setSpy = vi.spyOn(globalThis, 'setInterval')
    let wrapper
    expect(() => { wrapper = mountPlayer() }).not.toThrow()
    expect(setSpy).toHaveBeenCalledWith(expect.any(Function), 5000)

    // 推进 5s：定时回调调用 savePlaybackTime 不抛异常
    const player = usePlayerStore()
    const saveSpy = vi.spyOn(player, 'savePlaybackTime')
    vi.advanceTimersByTime(5000)
    expect(saveSpy).toHaveBeenCalled()
    wrapper.unmount()
  })

  it('卸载时清理定时器（无泄漏）', () => {
    const clearSpy = vi.spyOn(globalThis, 'clearInterval')
    const wrapper = mountPlayer()
    const callsBefore = clearSpy.mock.calls.length
    wrapper.unmount()
    expect(clearSpy.mock.calls.length).toBeGreaterThan(callsBefore)
  })
})
