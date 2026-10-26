import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { nextTick } from 'vue'

const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }))

vi.mock('vue-router', () => ({
  useRoute: () => ({ path: '/m' }),
  useRouter: () => ({ push: pushMock }),
}))

import SearchResultTable from '@/components/SearchResultTable.vue'
import MTabBar from '@/components/mobile/MTabBar.vue'
import LoginModal from '@/components/LoginModal.vue'
import LyricsView from '@/components/LyricsView.vue'
import MSearchRow from '@/components/mobile/MSearchRow.vue'
import { usePlayerStore } from '@/stores/player'

const SONG = {
  sourceId: 's1',
  name: '晴天',
  artist: '周杰伦',
  coverUrl: '',
  platform: 'qq',
  duration: 240,
}

describe('键盘可访问性', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    document.body.innerHTML = ''
    window.vibeAudio = null
  })

  afterEach(() => {
    window.vibeAudio = null
  })

  describe('搜索结果行（SearchResultTable）', () => {
    it('行是 role=button + tabindex=0，并带可读 aria-label', () => {
      const wrapper = mount(SearchResultTable, { props: { songs: [SONG] } })
      const row = wrapper.find('.table-row')

      expect(row.attributes('role')).toBe('button')
      expect(row.attributes('tabindex')).toBe('0')
      expect(row.attributes('aria-label')).toContain('晴天')
    })

    it('Enter 与 Space 都能触发播放（与鼠标点击同一行为）', async () => {
      const wrapper = mount(SearchResultTable, { props: { songs: [SONG] } })
      const player = usePlayerStore()
      const spy = vi.spyOn(player, 'playSongFromApi')

      const row = wrapper.find('.table-row')
      await row.trigger('keydown', { key: 'Enter' })
      await row.trigger('keydown', { key: ' ' })

      expect(spy).toHaveBeenCalledTimes(2)
      expect(spy).toHaveBeenCalledWith('s1', '晴天', '周杰伦', '', 'qq')
    })

    it('行内按钮的 Enter 不会冒泡成「播放整行」', async () => {
      const wrapper = mount(SearchResultTable, { props: { songs: [SONG] } })
      const player = usePlayerStore()
      const spy = vi.spyOn(player, 'playSongFromApi')

      const favBtn = wrapper.find('.td-actions button')
      await favBtn.trigger('keydown', { key: 'Enter' })

      expect(spy).not.toHaveBeenCalled()
    })
  })

  describe('移动端 Tab 栏（MTabBar）', () => {
    it('6 个 tab 是带 href 的链接，只有当前项带 aria-current="page"', () => {
      const wrapper = mount(MTabBar)
      const items = wrapper.findAll('.mtab-item')

      expect(items).toHaveLength(6)
      expect(items.every(i => i.element.tagName === 'A')).toBe(true)
      expect(items.every(i => (i.attributes('href') || '').startsWith('/m'))).toBe(true)
      // 路由 mock 成 /m，只有首页是当前项
      expect(items[0].attributes('aria-current')).toBe('page')
      expect(items.slice(1).every(i => i.attributes('aria-current') === undefined)).toBe(true)
    })
  })

  describe('登录弹窗（LoginModal）', () => {
    it('Esc 关闭：emit update:visible=false', async () => {
      const wrapper = mount(LoginModal, { props: { visible: true }, attachTo: document.body })
      await nextTick()

      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
      await nextTick()

      expect(wrapper.emitted('update:visible')).toBeTruthy()
      expect(wrapper.emitted('update:visible')[0]).toEqual([false])
    })

    it('弹窗未打开时 Esc 不触发关闭（常驻挂载不抢键盘）', async () => {
      const wrapper = mount(LoginModal, { props: { visible: false }, attachTo: document.body })
      await nextTick()

      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
      await nextTick()

      expect(wrapper.emitted('update:visible')).toBeFalsy()
    })

    it('打开时焦点移入弹层（面板 role=dialog + aria-modal）', async () => {
      mount(LoginModal, { props: { visible: true }, attachTo: document.body })
      await nextTick()
      await nextTick()

      const panel = document.body.querySelector('.modal-content')
      expect(panel?.getAttribute('role')).toBe('dialog')
      expect(panel?.getAttribute('aria-modal')).toBe('true')
      expect(document.activeElement?.classList.contains('input')).toBe(true)
    })
  })

  describe('歌词页进度条 / 音量条（role=slider）', () => {
    /** 歌词页整块在 <Teleport to="body"> 里，必须挂到 body 再用 document 查询 */
    async function mountLyrics(duration = 300) {
      const wrapper = mount(LyricsView, {
        props: { visible: true, currentSong: { id: 's1', title: '晴天' }, duration },
        attachTo: document.body,
      })
      await nextTick()
      await nextTick()
      return wrapper
    }

    /** @param {Element|null} el @param {string} key @param {KeyboardEventInit} [init] */
    async function press(el, key, init = {}) {
      el.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init }))
      await nextTick()
    }

    it('进度条与音量条都是 role=slider 且可聚焦', async () => {
      await mountLyrics()
      for (const sel of ['.progress-track', '.vol-bar']) {
        const el = document.body.querySelector(sel)
        expect(el, sel + ' 未渲染').toBeTruthy()
        expect(el.getAttribute('role')).toBe('slider')
        expect(el.getAttribute('tabindex')).toBe('0')
        expect(el.getAttribute('aria-valuemin')).toBe('0')
      }
      expect(document.body.querySelector('.progress-track').getAttribute('aria-valuemax')).toBe('300')
      expect(document.body.querySelector('.vol-bar').getAttribute('aria-valuemax')).toBe('100')
    })

    it('←/→ 调进度 ±5 秒（Shift ±30），Home/End 首尾', async () => {
      await mountLyrics(300)
      const audio = { currentTime: 0, volume: 1, muted: false }
      window.vibeAudio = audio
      const track = document.body.querySelector('.progress-track')

      await press(track, 'ArrowRight')
      expect(audio.currentTime).toBe(5)
      await press(track, 'ArrowRight', { shiftKey: true })
      expect(audio.currentTime).toBe(35)
      await press(track, 'ArrowLeft')
      expect(audio.currentTime).toBe(30)
      await press(track, 'Home')
      expect(audio.currentTime).toBe(0)
      await press(track, 'End')
      expect(audio.currentTime).toBe(300)
    })

    it('↑/↓ 调音量 ±5，且不会越界到 0 以下', async () => {
      await mountLyrics()
      const audio = { currentTime: 0, volume: 0.5, muted: false }
      window.vibeAudio = audio
      const bar = document.body.querySelector('.vol-bar')

      const before = Number(bar.getAttribute('aria-valuenow'))
      await press(bar, 'ArrowUp')
      expect(audio.volume).toBeCloseTo(Math.min(100, before + 5) / 100, 5)
      for (let i = 0; i < 40; i++) await press(bar, 'ArrowDown')
      expect(audio.volume).toBe(0)
    })
  })

  describe('移动端搜索行（MSearchRow）', () => {
    it('行是 role=button + tabindex=0，Enter 触发播放', async () => {
      const wrapper = mount(MSearchRow, { props: { song: SONG } })
      const player = usePlayerStore()
      const spy = vi.spyOn(player, 'playSongFromApi')
      const row = wrapper.find('.m-song-item')

      expect(row.attributes('role')).toBe('button')
      expect(row.attributes('tabindex')).toBe('0')

      await row.trigger('keydown', { key: 'Enter' })
      expect(spy).toHaveBeenCalledTimes(1)
    })
  })
})
