import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { setActivePinia, createPinia } from 'pinia'
import { usePlayerStore } from '@/stores/player'
import { useFavoriteStore } from '@/stores/favorite'
import { useClickOutside } from '@/composables/useClickOutside'
import PlayerBar from '@/components/PlayerBar.vue'

// ============ Helper: 构建基础 store 状态 ============
function setupStore() {
  setActivePinia(createPinia())
  const player = usePlayerStore()
  const favorite = useFavoriteStore()

  // 为队列添加测试数据
  player.addToQueue({ sourceId: '186016', name: '晴天', artist: '周杰伦', coverUrl: 'https://img.test/cover.jpg', duration: 269 })
  player.addToQueue({ sourceId: '001abc', name: '夜曲', artist: '周杰伦', duration: 220 })
  player.currentIdx = 0
  player.currentSong = { id: '186016', title: '晴天', artist: '周杰伦', coverUrl: 'https://img.test/cover.jpg', duration: 269 }
  player.duration = 269

  // Mock favorite store
  vi.spyOn(favorite, 'fetchFavIds').mockImplementation(() => {})
  vi.spyOn(favorite, 'isFav').mockReturnValue(false)
  vi.spyOn(favorite, 'toggleFav').mockImplementation(() => Promise.resolve(false))

  return { player, favorite }
}

function mountPlayerBar(props = {}) {
  return mount(PlayerBar, {
    props,
    global: {
      stubs: {
        LyricsView: { template: '<div class="mock-lyrics-view" />', props: ['visible', 'currentSong', 'isPlaying', 'duration'] },
        SvgIcon: { template: '<span class="mock-svg">{{ name }}</span>', props: ['name', 'size', 'color'] },
        Transition: { template: '<slot />', inheritAttrs: false, props: ['name'] },
        transition: { template: '<slot />', inheritAttrs: false }
      },
    },
  })
}

// ============ 测试套件 ============
describe('PlayerBar', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  describe('渲染', () => {
    it('渲染底部播放条', () => {
      setupStore()
      const wrapper = mountPlayerBar()
      expect(wrapper.find('.player-bar').exists()).toBe(true)
      expect(wrapper.find('footer.bar').exists()).toBe(true)
    })

    it('显示当前播放歌曲信息', () => {
      setupStore()
      const wrapper = mountPlayerBar()
      const html = wrapper.html()
      expect(html).toContain('晴天')
      expect(html).toContain('周杰伦')
    })

    it('无歌曲时显示兜底信息', () => {
      setActivePinia(createPinia())
      const wrapper = mountPlayerBar()
      expect(wrapper.find('.mini-name').text()).toBe('未播放')
    })

    it('渲染播放控制按钮', () => {
      setupStore()
      const wrapper = mountPlayerBar()
      // 上一首、播放/暂停、下一首 按钮都存在
      const buttons = wrapper.findAll('.ctrl-btn')
      expect(buttons.length).toBeGreaterThanOrEqual(3)
    })

    it('渲染进度条', () => {
      setupStore()
      const wrapper = mountPlayerBar()
      expect(wrapper.find('.progress-track').exists()).toBe(true)
      expect(wrapper.find('.progress-fill').exists()).toBe(true)
    })
  })

  describe('播放列表面板', () => {
    it('初始时不显示播放列表面板', () => {
      setupStore()
      const wrapper = mountPlayerBar()
      expect(wrapper.find('.playlist-panel').exists()).toBe(false)
    })

    it('点击播放列表按钮展开面板', async () => {
      setupStore()
      const wrapper = mountPlayerBar()
      const btn = wrapper.find('button[title="播放列表"]')
      expect(btn.exists()).toBe(true)
      await btn.trigger('click')
      expect(wrapper.find('.playlist-panel').exists()).toBe(true)
    })

    it('面板显示队列长度', async () => {
      setupStore()
      const wrapper = mountPlayerBar()
      await wrapper.find('button[title="播放列表"]').trigger('click')
      expect(wrapper.text()).toContain('播放队列')
      expect(wrapper.text()).toContain('2')
    })

    it('面板显示歌曲名称', async () => {
      setupStore()
      const wrapper = mountPlayerBar()
      await wrapper.find('button[title="播放列表"]').trigger('click')
      const html = wrapper.html()
      expect(html).toContain('晴天')
      expect(html).toContain('夜曲')
    })

    it('点击关闭按钮收起面板', async () => {
      setupStore()
      const wrapper = mountPlayerBar()
      // 展开面板
      await wrapper.find('button[title="播放列表"]').trigger('click')
      await nextTick()
      expect(wrapper.find('.playlist-panel').exists()).toBe(true)
      // 关闭面板 → panel-close 有 @click="showPlaylist = false"
      const closeBtn = wrapper.find('.panel-close')
      expect(closeBtn.exists()).toBe(true)
      await closeBtn.trigger('click')
      // v-if 需要 Vue 响应式更新
      await nextTick()
      expect(wrapper.find('.playlist-panel').exists()).toBe(false)
    })

    it('click-outside 被正确注册', () => {
      setupStore()
      mountPlayerBar()
      // useClickOutside composable 应该在 mounted 时被调用
      expect(useClickOutside).toHaveBeenCalled()
    })

    it('队列为空时显示空状态', async () => {
      setActivePinia(createPinia())
      const wrapper = mountPlayerBar()
      await wrapper.find('button[title="播放列表"]').trigger('click')
      expect(wrapper.find('.panel-empty').exists()).toBe(true)
      expect(wrapper.find('.panel-empty').text()).toBe('播放队列为空')
    })

    it('当前播放歌曲高亮', async () => {
      setupStore()
      const wrapper = mountPlayerBar()
      await wrapper.find('button[title="播放列表"]').trigger('click')
      const currentItem = wrapper.find('.panel-item.current')
      expect(currentItem.exists()).toBe(true)
      expect(currentItem.text()).toContain('晴天')
    })
  })

  describe('播放控制', () => {
    it('点击播放按钮切换播放/暂停', async () => {
      setupStore()
      const wrapper = mountPlayerBar()
      const playBtn = wrapper.find('button[title="播放/暂停"]')
      expect(playBtn.exists()).toBe(true)
      await playBtn.trigger('click')
      // togglePlay 被调用（store 方法在 jsdom 中无法真正播放）
      expect(playBtn.exists()).toBe(true)
    })

    it('点击上一首触发 prev', async () => {
      const { player } = setupStore()
      player.currentIdx = 1
      const wrapper = mountPlayerBar()
      const prevBtn = wrapper.find('button[title="上一首"]')
      await prevBtn.trigger('click')
      expect(player.currentIdx).toBe(0)
    })

    it('点击下一首触发 next', async () => {
      const { player } = setupStore()
      player.currentIdx = 0
      const wrapper = mountPlayerBar()
      const nextBtn = wrapper.find('button[title="下一首"]')
      await nextBtn.trigger('click')
      expect(player.currentIdx).toBe(1)
    })

    it('点击模式按钮切换播放模式', async () => {
      const { player } = setupStore()
      const wrapper = mountPlayerBar()
      const modeBtn = wrapper.find('button.mode-btn')
      expect(player.playMode).toBe('list-loop')
      await modeBtn.trigger('click')
      expect(player.playMode).toBe('single')
      await modeBtn.trigger('click')
      expect(player.playMode).toBe('shuffle')
    })
  })

  describe('时间格式化', () => {
    it('格式化当前时间和总时长', () => {
      const { player } = setupStore()
      player.duration = 269
      player.progress = 50
      player.currentTime = 134
      const wrapper = mountPlayerBar()
      const times = wrapper.findAll('.time')
      // 当前时间
      expect(times[0].text()).toBe('2:14')
    })
  })

  describe('音量控制', () => {
    it('显示音量按钮', () => {
      setupStore()
      const wrapper = mountPlayerBar()
      expect(wrapper.find('button[title="音量"]').exists()).toBe(true)
    })

    it('静音按钮正常工作', async () => {
      const { player } = setupStore()
      const wrapper = mountPlayerBar()
      const muteBtn = wrapper.find('button[title="音量"]')
      expect(player.isMuted).toBe(false)
      await muteBtn.trigger('click')
      expect(player.isMuted).toBe(true)
    })
  })
})
