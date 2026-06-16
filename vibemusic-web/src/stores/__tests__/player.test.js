import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { usePlayerStore } from '@/stores/player'

// Mock Audio
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
  removeEventListener(event, fn) { delete this._listeners[event] }
  play() { this.paused = false; return Promise.resolve() }
  pause() { this.paused = true; return Promise.resolve() }
  load() {}
  dispatchEvent(e) { const fn = this._listeners[e.type]; if (fn) fn(e) }
}

describe('PlayerStore', () => {
  beforeEach(() => {
    // 重置 Pinia
    setActivePinia(createPinia())
    // Mock localStorage
    localStorage.clear()
    // Mock window Audio
    window.Audio = MockAudio
    delete window.vibeAudio
  })

  describe('初始状态', () => {
    it('队列默认为空数组', () => {
      const store = usePlayerStore()
      expect(store.queue).toEqual([])
    })

    it('当前索引默认为 -1', () => {
      const store = usePlayerStore()
      expect(store.currentIdx).toBe(-1)
    })

    it('默认播放模式为 list-loop', () => {
      const store = usePlayerStore()
      expect(store.playMode).toBe('list-loop')
    })

    it('默认音量 70', () => {
      const store = usePlayerStore()
      expect(store.volume).toBe(70)
    })

    it('未播放状态', () => {
      const store = usePlayerStore()
      expect(store.isPlaying).toBe(false)
    })
  })

  describe('addToQueue', () => {
    it('添加歌曲到队列', () => {
      const store = usePlayerStore()
      store.addToQueue({ sourceId: '123', name: 'Test Song', artist: 'Tester' })
      expect(store.queue).toHaveLength(1)
      expect(store.queue[0].sourceId).toBe('123')
      expect(store.queue[0].name).toBe('Test Song')
    })

    it('重复 sourceId 不新增，只更新', () => {
      const store = usePlayerStore()
      store.addToQueue({ sourceId: '123', name: 'Old Name' })
      store.addToQueue({ sourceId: '123', name: 'New Name' })
      expect(store.queue).toHaveLength(1)
      expect(store.queue[0].name).toBe('New Name')
    })
  })

  describe('removeFromQueue', () => {
    it('从队列移除歌曲', () => {
      const store = usePlayerStore()
      store.addToQueue({ sourceId: '1', name: 'S1' })
      store.addToQueue({ sourceId: '2', name: 'S2' })
      store.removeFromQueue(0)
      expect(store.queue).toHaveLength(1)
      expect(store.queue[0].sourceId).toBe('2')
    })

    it('移除最后一首歌时重置状态', () => {
      const store = usePlayerStore()
      store.addToQueue({ sourceId: '1', name: 'S1' })
      store.removeFromQueue(0)
      expect(store.queue).toHaveLength(0)
      expect(store.currentSong.title).toBe('未播放')
    })
  })

  describe('clearQueue', () => {
    it('清空队列并重置所有状态', () => {
      const store = usePlayerStore()
      store.addToQueue({ sourceId: '1', name: 'S1' })
      store.addToQueue({ sourceId: '2', name: 'S2' })
      store.clearQueue()
      expect(store.queue).toHaveLength(0)
      expect(store.currentIdx).toBe(-1)
      expect(store.isPlaying).toBe(false)
      expect(store.currentSong.title).toBe('未播放')
    })
  })

  describe('next / prev', () => {
    it('下一首 → 索引递增', () => {
      const store = usePlayerStore()
      store.addToQueue({ sourceId: '1', name: 'S1' })
      store.addToQueue({ sourceId: '2', name: 'S2' })
      store.addToQueue({ sourceId: '3', name: 'S3' })
      store.currentIdx = 0
      store.next()
      expect(store.currentIdx).toBe(1)
    })

    it('最后一首下一首 → 循环到第一首', () => {
      const store = usePlayerStore()
      store.addToQueue({ sourceId: '1', name: 'S1' })
      store.addToQueue({ sourceId: '2', name: 'S2' })
      store.currentIdx = 1
      store.next()
      expect(store.currentIdx).toBe(0)
    })

    it('上一首 → 索引递减', () => {
      const store = usePlayerStore()
      store.addToQueue({ sourceId: '1', name: 'S1' })
      store.addToQueue({ sourceId: '2', name: 'S2' })
      store.currentIdx = 1
      store.prev()
      expect(store.currentIdx).toBe(0)
    })

    it('空队列 next/prev 安全返回', () => {
      const store = usePlayerStore()
      expect(() => store.next()).not.toThrow()
      expect(() => store.prev()).not.toThrow()
    })
  })

  describe('toggleMode', () => {
    it('顺序切换：list-loop → single → shuffle → list-loop', () => {
      const store = usePlayerStore()
      expect(store.playMode).toBe('list-loop')
      store.toggleMode()
      expect(store.playMode).toBe('single')
      store.toggleMode()
      expect(store.playMode).toBe('shuffle')
      store.toggleMode()
      expect(store.playMode).toBe('list-loop')
    })
  })

  describe('toggleMute', () => {
    it('切换静音状态', () => {
      const store = usePlayerStore()
      expect(store.isMuted).toBe(false)
      store.toggleMute()
      expect(store.isMuted).toBe(true)
      store.toggleMute()
      expect(store.isMuted).toBe(false)
    })
  })

  describe('fmtSec', () => {
    it('格式化秒数为 m:ss', () => {
      const store = usePlayerStore()
      expect(store.fmtSec(0)).toBe('0:00')
      expect(store.fmtSec(65)).toBe('1:05')
      expect(store.fmtSec(3661)).toBe('61:01')
    })

    it('非法输入返回 0:00', () => {
      const store = usePlayerStore()
      expect(store.fmtSec(null)).toBe('0:00')
      expect(store.fmtSec(NaN)).toBe('0:00')
    })
  })

  describe('持久化', () => {
    it('队列变更后 localStorage 保存', async () => {
      const store = usePlayerStore()
      store.addToQueue({ sourceId: 'persist1', name: 'P1' })
      // 等待 debounce (300ms)
      await new Promise(r => setTimeout(r, 350))
      const saved = JSON.parse(localStorage.getItem('vibe_queue'))
      expect(saved).toHaveLength(1)
      expect(saved[0].sourceId).toBe('persist1')
    }, 1000)

    it('播放模式变化后 localStorage 保存', async () => {
      const store = usePlayerStore()
      store.toggleMode()
      // watcher 异步写入 localStorage，等待微任务队列
      await Promise.resolve()
      expect(localStorage.getItem('vibe_play_mode')).toBe('single')
    })

    it('音量变化后 localStorage 保存', async () => {
      const store = usePlayerStore()
      store.volume = 50
      await Promise.resolve()
      expect(localStorage.getItem('vibe_volume')).toBe('50')
    })
  })
})
