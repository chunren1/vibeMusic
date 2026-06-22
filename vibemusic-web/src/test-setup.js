import { vi } from 'vitest'

// ============ Mock Audio ============
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

// ============ Global mocks ============
window.Audio = MockAudio
window.vibeAudio = null

// ============ Mock ResizeObserver (jsdom 不原生支持) ============
window.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
}

// ============ Mock composables ============
vi.mock('@/composables/useAudioBackground', () => ({
  useAudioBackground: () => ({
    startWorkerTimer: vi.fn(),
    stopWorkerTimer: vi.fn(),
  }),
}))

vi.mock('@/composables/useClickOutside', () => ({
  useClickOutside: vi.fn(),
}))

// ============ Mock API ============
vi.mock('@/api/song', () => ({
  downloadSong: vi.fn(() => Promise.resolve()),
  getPlaylists: vi.fn(() => Promise.resolve([])),
  createPlaylist: vi.fn(() => Promise.resolve({ id: 1 })),
  addToPlaylist: vi.fn(() => Promise.resolve()),
  getLyric: vi.fn(() => Promise.resolve({ data: [] })),
}))

vi.mock('@/api/request', () => ({
  API_HOST: 'http://localhost:8081',
}))

// ============ Mock SvgIcon（全局注册，避免测试中渲染真实 SVG） ============
vi.mock('@/components/SvgIcon.vue', () => ({
  default: {
    name: 'SvgIcon',
    props: ['name', 'size', 'color'],
    template: '<span class="mock-svg-icon">{{ name }}</span>',
  },
}))
