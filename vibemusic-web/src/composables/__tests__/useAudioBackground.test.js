import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'

// test-setup.js 全局 mock 了这个 composable（组件测试只需要空实现），
// 本文件要验证真实实现，必须显式取消 mock。
vi.unmock('@/composables/useAudioBackground')

import { useAudioBackground } from '@/composables/useAudioBackground'

// Worker mock（jsdom 没有 Worker）：记录实例、消息与 terminate 次数，
// emitTick 模拟 Worker 线程 postMessage('tick') 到达主线程
class MockWorker {
  static instances = []
  constructor() {
    this.posted = []
    this.onmessage = null
    this.terminated = false
    MockWorker.instances.push(this)
  }
  postMessage(msg) { this.posted.push(msg) }
  terminate() { this.terminated = true }
  emitTick() { this.onmessage?.({ data: 'tick' }) }
}
MockWorker.instances.length = 0

let apiRef = null

/** 每次都调用 useAudioBackground 的测试组件 */
const BgConsumer = defineComponent({
  name: 'BgConsumer',
  setup() {
    apiRef = useAudioBackground()
    return () => h('div')
  },
})

beforeEach(() => {
  MockWorker.instances = []
  window.Worker = MockWorker
  // startBgEndedCheck 需要 window.vibeAudio 才注册 Worker 心跳
  window.vibeAudio = {
    src: '', paused: true, loop: false, ended: false, duration: 0, currentTime: 0, readyState: 0,
    addEventListener() {}, removeEventListener() {}, play() { return Promise.resolve() },
  }
  localStorage.clear()
})

afterEach(() => {
  apiRef = null
})

describe('useAudioBackground 单例', () => {
  it('多次调用返回同一个 api 对象，且只注册一次 visibilitychange 监听', () => {
    const addSpy = vi.spyOn(document, 'addEventListener')

    const w1 = mount(BgConsumer)
    const api1 = apiRef
    const afterFirst = addSpy.mock.calls.filter(([ev]) => ev === 'visibilitychange').length

    const w2 = mount(BgConsumer)
    const api2 = apiRef
    const afterSecond = addSpy.mock.calls.filter(([ev]) => ev === 'visibilitychange').length

    // 第二次调用复用同一实例：对象同一、监听数量不增长
    expect(api2).toBe(api1)
    expect(afterFirst).toBeGreaterThan(0)
    expect(afterSecond).toBe(afterFirst)

    w1.unmount()
    w2.unmount()
  })

  it('引用计数归零前不 teardown，归零后才移除监听并 terminate Worker', () => {
    const removeSpy = vi.spyOn(document, 'removeEventListener')

    const w1 = mount(BgConsumer)
    const w2 = mount(BgConsumer)

    // 挂载期间至少创建了一个 Worker（后台结束检测心跳）
    expect(MockWorker.instances.length).toBe(1)
    const worker = MockWorker.instances[0]

    w1.unmount()
    // 还有第二个持有者：监听与 Worker 都保留
    expect(removeSpy.mock.calls.filter(([ev]) => ev === 'visibilitychange')).toHaveLength(0)
    expect(worker.terminated).toBe(false)

    w2.unmount()
    // 最后一个持有者卸载：visibilitychange 监听被移除、Worker 被 terminate
    expect(removeSpy.mock.calls.filter(([ev]) => ev === 'visibilitychange').length).toBeGreaterThan(0)
    expect(worker.terminated).toBe(true)
  })
})

describe('useAudioBackground Worker 心跳多订阅者', () => {
  it('两个回调都收到 tick；stopWorkerTimer 后都不再触发', () => {
    const wrapper = mount(BgConsumer)
    const api = apiRef
    const worker = MockWorker.instances[0]
    const cbA = vi.fn()
    const cbB = vi.fn()

    api.startWorkerTimer(cbA, 250)
    api.startWorkerTimer(cbB, 250)
    worker.emitTick()

    // 修复前单槽位会互相顶掉，只会有最后一个回调被调用
    expect(cbA).toHaveBeenCalledTimes(1)
    expect(cbB).toHaveBeenCalledTimes(1)

    api.stopWorkerTimer()
    worker.emitTick()
    expect(cbA).toHaveBeenCalledTimes(1)
    expect(cbB).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })
})
