import { ref, watch, computed } from 'vue'
import { defineStore } from 'pinia'
import { API_HOST } from '@/api/request'
import { playSong as apiPlaySong } from '@/api/song'

// ===== localStorage keys =====
const STORAGE_KEY = 'vibe_queue'
const IDX_KEY = 'vibe_queue_idx'
const SONG_KEY = 'vibe_current_song'
const TIME_KEY = 'vibe_playback_time'
const VOL_KEY = 'vibe_volume'
const MODE_KEY = 'vibe_play_mode'

function tryParse(key, fallback) {
  try { return JSON.parse(localStorage.getItem(key)) || fallback } catch { return fallback }
}

export const usePlayerStore = defineStore('player', () => {
  // ===== 共享 Audio 元素 =====
  const audio = window.vibeAudio || new Audio()
  window.vibeAudio = audio

  // ===== 响应式状态 =====
  const queue = ref(tryParse(STORAGE_KEY, []))
  const currentIdx = ref(parseInt(localStorage.getItem(IDX_KEY) || '-1'))
  const currentSong = ref(tryParse(SONG_KEY, null) || { id: '', title: '未播放', artist: '', coverUrl: '', duration: 0 })
  const isPlaying = ref(false)
  const isTrialSong = ref(false)
  const quality = ref('STANDARD')
  const qualityLabel = ref('标准')
  const playMode = ref(localStorage.getItem(MODE_KEY) || 'list-loop')
  const volume = ref(parseInt(localStorage.getItem(VOL_KEY) || '70'))
  const progress = ref(0)
  const currentTime = ref(0)
  const duration = ref(0)
  const isMuted = ref(false)

  const modeLabels = { 'list-loop': '列表循环', 'single': '单曲循环', 'shuffle': '随机播放', 'sequential': '顺序播放' }

  // ===== 持久化 =====
  function saveToStorage() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(queue.value))
    localStorage.setItem(IDX_KEY, String(currentIdx.value))
    localStorage.setItem(SONG_KEY, JSON.stringify(currentSong.value))
  }

  watch(queue, saveToStorage, { deep: true })
  watch(currentSong, saveToStorage, { deep: true })
  watch(playMode, (m) => {
    localStorage.setItem(MODE_KEY, m)
    audio.loop = m === 'single'
  })
  watch(volume, (v) => {
    audio.volume = v / 100
    localStorage.setItem(VOL_KEY, String(v))
  })

  // ===== Audio 事件 =====
  audio.addEventListener('timeupdate', () => {
    if (audio.duration) {
      progress.value = (audio.currentTime / audio.duration) * 100
      currentTime.value = audio.currentTime
      duration.value = audio.duration
    }
  })
  audio.addEventListener('play', () => { isPlaying.value = true })
  audio.addEventListener('pause', () => { isPlaying.value = false })
  audio.addEventListener('ended', () => onEnded())
  audio.volume = volume.value / 100
  audio.loop = playMode.value === 'single'

  // ===== 核心播放方法 =====

  /** 通过 sourceId 直接播放（使用 stream 代理 URL） */
  function playBySourceId(sourceId, name, artist, coverUrl, duration = 0) {
    if (!sourceId) return
    addToQueue({ sourceId, name, artist, coverUrl, duration })
    const idx = queue.value.findIndex(s => s.sourceId === sourceId)
    if (idx >= 0) currentIdx.value = idx

    currentSong.value = { id: sourceId, title: name || '', artist: artist || '', coverUrl: coverUrl || '', duration }
    audio.src = `${API_HOST}/api/songs/stream?sourceId=${encodeURIComponent(sourceId)}`
    audio.loop = playMode.value === 'single'
    resumeAudioContext()
    audio.play().catch(() => {})
    isPlaying.value = true

    dispatchSongChange()
    saveToStorage()
  }

  /** 调用后端 API 获取播放 URL 并播放（记录播放历史） */
  async function playSongFromApi(sourceId, name, artist, coverUrl) {
    if (!sourceId) return
    resumeAudioContext()
    try {
      const res = await apiPlaySong(sourceId, name, artist, coverUrl || '')
      isTrialSong.value = res.data?.isTrial || false
      quality.value = res.data?.quality || 'STANDARD'
      qualityLabel.value = res.data?.qualityLabel || '标准'
      // 始终使用 stream 代理 URL，不使用 API 返回的远程 URL
      playBySourceId(sourceId, name, artist, coverUrl, res.data?.duration || 0)
    } catch {
      // 即使 API 失败，仍尝试通过 stream 代理播放
      playBySourceId(sourceId, name, artist, coverUrl, 0)
    }
  }

  /** 播放队列中当前索引的歌曲 */
  function playCurrent() {
    if (currentIdx.value < 0 || currentIdx.value >= queue.value.length) return
    const song = queue.value[currentIdx.value]
    playBySourceId(song.sourceId, song.name, song.artist, song.coverUrl, song.duration)
  }

  /** 上一首 */
  function prev() {
    if (queue.value.length === 0) return
    if (playMode.value === 'shuffle') {
      const r = Math.floor(Math.random() * queue.value.length)
      currentIdx.value = r === currentIdx.value ? (r + 1) % queue.value.length : r
    } else {
      currentIdx.value = currentIdx.value <= 0 ? queue.value.length - 1 : currentIdx.value - 1
    }
    playCurrent()
  }

  /** 下一首 */
  function next() {
    if (queue.value.length === 0) return
    if (playMode.value === 'shuffle') {
      const r = Math.floor(Math.random() * queue.value.length)
      currentIdx.value = r === currentIdx.value && queue.value.length > 1
        ? (r + 1) % queue.value.length : r
    } else if (playMode.value === 'sequential') {
      if (currentIdx.value >= queue.value.length - 1) return
      currentIdx.value = currentIdx.value + 1
    } else {
      // list-loop / single 的 next 逻辑一样（循环）
      currentIdx.value = (currentIdx.value + 1) % queue.value.length
    }
    playCurrent()
  }

  /** 歌曲播放结束处理 */
  function onEnded() {
    // single 模式由 audio.loop=true 处理，不会触发 ended
    if (playMode.value === 'shuffle' || playMode.value === 'list-loop') {
      next()
    } else if (playMode.value === 'sequential') {
      if (currentIdx.value < queue.value.length - 1) next()
      else isPlaying.value = false
    }
  }

  /** 切换播放模式 */
  function toggleMode() {
    const modes = ['list-loop', 'single', 'shuffle', 'sequential']
    const idx = modes.indexOf(playMode.value)
    playMode.value = modes[(idx + 1) % modes.length]
    window.dispatchEvent(new CustomEvent('play-mode-change', { detail: playMode.value }))
  }

  /** 播放/暂停切换 */
  function togglePlay() {
    if (isPlaying.value) {
      audio.pause()
    } else {
      resumeAudioContext()
      if (!audio.src || audio.readyState === 0) audio.load()
      audio.play().catch(() => { isPlaying.value = false })
    }
  }

  /** 切换静音 */
  function toggleMute() {
    isMuted.value = !isMuted.value
    audio.muted = isMuted.value
  }

  /** 添加歌曲到队列（去重） */
  function addToQueue(song) {
    const exists = queue.value.findIndex(s => s.sourceId === song.sourceId)
    if (exists >= 0) {
      Object.assign(queue.value[exists], song)
    } else {
      queue.value.push(song)
    }
  }

  /** 从队列中移除歌曲 */
  function removeFromQueue(idx) {
    queue.value.splice(idx, 1)
    if (currentIdx.value >= queue.value.length) currentIdx.value = queue.value.length - 1
    if (idx === currentIdx.value && queue.value.length > 0) playCurrent()
    else if (queue.value.length === 0) {
      currentSong.value = { id: '', title: '未播放', artist: '', coverUrl: '', duration: 0 }
      audio.pause()
      audio.src = ''
      isPlaying.value = false
    }
  }

  /** 播放队列中指定索引 */
  function playIndex(idx) {
    currentIdx.value = idx
    playCurrent()
  }

  /** 清空队列 */
  function clearQueue() {
    queue.value = []
    currentIdx.value = -1
    currentSong.value = { id: '', title: '未播放', artist: '', coverUrl: '', duration: 0 }
    audio.pause()
    audio.src = ''
    isPlaying.value = false
    saveToStorage()
  }

  /** 进度跳转 */
  function seekTo(pct) {
    if (audio.duration) audio.currentTime = pct * audio.duration
  }

  function seekToTime(time) {
    audio.currentTime = time
  }

  /** 保存播放进度 */
  function savePlaybackTime() {
    if (audio.duration && !audio.paused) {
      localStorage.setItem(TIME_KEY, String(audio.currentTime))
    }
  }

  /** 页面刷新后恢复播放状态 */
  function restorePlayback() {
    if (currentIdx.value >= 0 && currentIdx.value < queue.value.length) {
      const song = queue.value[currentIdx.value]
      if (!currentSong.value.id) {
        currentSong.value = {
          id: song.sourceId, title: song.name, artist: song.artist,
          coverUrl: song.coverUrl || '', duration: song.duration || 0,
        }
      }
      const cachedTime = parseFloat(localStorage.getItem(TIME_KEY) || '0')
      audio.src = `${API_HOST}/api/songs/stream?sourceId=${encodeURIComponent(song.sourceId)}`
      audio.loop = playMode.value === 'single'
      if (cachedTime > 0) {
        audio.currentTime = cachedTime
        currentTime.value = cachedTime
      }
    }
  }

  /** 恢复 AudioContext */
  function resumeAudioContext() {
    if (window._vibeAudioCtx && window._vibeAudioCtx.state === 'suspended') {
      window._vibeAudioCtx.resume().catch(() => {})
    }
  }

  /** 设置 AudioContext Analyser（桌面端 PlayerBar 调用） */
  function setupGlobalAnalyser() {
    if (window._vibeAnalyser) return
    try {
      const ctx = new (window.AudioContext || window.webkitAudioContext)()
      const analyser = ctx.createAnalyser()
      analyser.fftSize = 512
      analyser.smoothingTimeConstant = 0.8
      const src = ctx.createMediaElementSource(audio)
      src.connect(analyser)
      analyser.connect(ctx.destination)
      window._vibeAudioCtx = ctx
      window._vibeAnalyser = analyser
    } catch (e) {
      console.warn('[PlayerStore] AudioContext setup failed:', e.message)
      window._vibeAnalyser = null
    }
  }

  // ===== 派发 song-change 事件 =====
  function dispatchSongChange() {
    window.dispatchEvent(new CustomEvent('song-change', {
      detail: {
        sourceId: currentSong.value.id,
        title: currentSong.value.title,
        artist: currentSong.value.artist,
        coverUrl: currentSong.value.coverUrl,
        duration: currentSong.value.duration,
        isTrial: isTrialSong.value,
        quality: quality.value,
        qualityLabel: qualityLabel.value,
      }
    }))
  }

  // ===== 格式化时间 =====
  function fmtSec(s) {
    if (!s || !isFinite(s)) return '0:00'
    const m = Math.floor(s / 60)
    return m + ':' + String(Math.floor(s % 60)).padStart(2, '0')
  }

  // ===== 注册 window 全局（向后兼容 + 移动端使用） =====
  window.vibeQueue = queue
  window.vibeAddToQueue = addToQueue
  window.vibePlayMode = (m) => { playMode.value = m }
  window.vibePrev = prev
  window.vibeNext = next
  window.vibePlayQueue = playIndex
  window.vibeAudioSetSrc = (url, sourceId, songName, songArtist, coverUrl) => {
    playBySourceId(sourceId, songName, songArtist, coverUrl, 0)
  }
  // 新增：移动端直接播放入口
  window.vibePlay = playSongFromApi
  window.vibePlayBySourceId = playBySourceId
  window.vibeToggleMode = toggleMode
  window.vibeFavIds = new Set() // 收藏 ID 集合（由各组件维护）

  return {
    audio, queue, currentIdx, currentSong, isPlaying, isTrialSong,
    quality, qualityLabel,
    playMode, volume, progress, currentTime, duration, isMuted,
    modeLabels,
    playBySourceId, playSongFromApi, playCurrent, next, prev,
    toggleMode, togglePlay, toggleMute, addToQueue, removeFromQueue,
    playIndex, clearQueue, seekTo, seekToTime,
    savePlaybackTime, restorePlayback, resumeAudioContext,
    setupGlobalAnalyser, dispatchSongChange, fmtSec,
  }
})
