import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

// 可控 playSong：测试里手动 resolve/reject，精确复现「后发先至」的网络时序
const { pending, playSongMock } = vi.hoisted(() => ({
  pending: new Map(),
  playSongMock: vi.fn(),
}))

vi.mock('@/api/song', () => ({
  playSong: playSongMock,
}))

import { usePlayerStore } from '@/stores/player'

class MockAudio {
  constructor() {
    this.src = ''
    this.volume = 1
    this.loop = false
    this.currentTime = 0
    this.duration = 0
    this.paused = true
    this.readyState = 0
    this.muted = false
    this._listeners = {}
  }
  addEventListener(event, fn) { this._listeners[event] = fn }
  removeEventListener(event) { delete this._listeners[event] }
  play() { this.paused = false; return Promise.resolve() }
  pause() { this.paused = true; return Promise.resolve() }
  load() {}
  dispatchEvent(e) { const fn = this._listeners[e.type]; if (fn) fn(e) }
}

/** 取出某次请求的 resolve/reject 控制权 */
function deferred(sourceId) {
  const d = pending.get(sourceId)
  if (!d) throw new Error(`no pending request for ${sourceId}`)
  return d
}

describe('PlayerStore.playSongFromApi 竞态守卫', () => {
  let store

  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    window.Audio = MockAudio
    delete window.vibeAudio
    pending.clear()
    playSongMock.mockReset()
    // 每次调用挂起，等测试手动结算
    playSongMock.mockImplementation((sourceId) => new Promise((resolve, reject) => {
      pending.set(sourceId, { resolve, reject })
    }))
    store = usePlayerStore()
  })

  it('先点 A 后点 B，A 的响应晚到不覆盖 B', async () => {
    const pA = store.playSongFromApi('A', '歌A', '歌手A')
    const pB = store.playSongFromApi('B', '歌B', '歌手B')

    // 后发的 B 先回来
    deferred('B').resolve()
    await pB
    expect(store.currentSong.id).toBe('B')
    expect(store.audio.src).toContain('sourceId=B')

    // 过期的 A 才回来：必须被丢弃
    deferred('A').resolve()
    await pA

    expect(store.currentSong.id).toBe('B')
    expect(store.audio.src).toContain('sourceId=B')
    // A 从未落地到队列，说明它没有执行 playBySourceId
    expect(store.queue.map(s => s.sourceId)).toEqual(['B'])
  })

  it('正常时序（A 先回、B 后回）依然只播放最后点选的 B', async () => {
    const pA = store.playSongFromApi('A', '歌A', '歌手A')
    const pB = store.playSongFromApi('B', '歌B', '歌手B')

    deferred('A').resolve()
    await pA
    // A 已被更新的 B 请求作废（后点选优先），不会先落地再被覆盖
    expect(store.currentSong.id).not.toBe('A')

    deferred('B').resolve()
    await pB
    expect(store.currentSong.id).toBe('B')
    expect(store.audio.src).toContain('sourceId=B')
  })

  it('过期请求失败也不影响当前歌曲与试听标记', async () => {
    const pA = store.playSongFromApi('A', '歌A', '歌手A')
    const pB = store.playSongFromApi('B', '歌B', '歌手B')

    deferred('B').resolve()
    await pB
    // B 成功分支：isTrialSong 置 false
    expect(store.isTrialSong).toBe(false)

    // 模拟「B 成功之后用户又如点了试听」的场景，标记为 true
    store.isTrialSong = true

    deferred('A').reject(new Error('stale request failed'))
    await pA

    expect(store.currentSong.id).toBe('B')
    expect(store.isTrialSong).toBe(true)
  })
})
