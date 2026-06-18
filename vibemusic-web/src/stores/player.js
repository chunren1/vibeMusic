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

  const modeLabels = { 'list-loop': '顺序播放', 'single': '单曲循环', 'shuffle': '随机播放' }

  // ===== 持久化 =====
  function saveToStorage() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(queue.value))
    localStorage.setItem(IDX_KEY, String(currentIdx.value))
    localStorage.setItem(SONG_KEY, JSON.stringify(currentSong.value))
  }

  let saveTimer = null
  function debouncedSave() {
    clearTimeout(saveTimer)
    saveTimer = setTimeout(saveToStorage, 300)
  }

  /** 强制立即保存所有状态（beforeunload 时调用） */
  function flushSave() {
    clearTimeout(saveTimer)
    saveToStorage()
    if (audio.duration > 0) {
      localStorage.setItem(TIME_KEY, String(audio.currentTime))
    }
  }

  watch(queue, debouncedSave, { deep: true })
  watch(currentSong, debouncedSave, { deep: true })
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
  function playBySourceId(sourceId, name, artist, coverUrl, duration = 0, platform = '') {
    if (!sourceId) return
    addToQueue({ sourceId, name, artist, coverUrl, duration, platform })
    const idx = queue.value.findIndex(s => s.sourceId === sourceId)
    if (idx >= 0) currentIdx.value = idx

    currentSong.value = { id: sourceId, title: name || '', artist: artist || '', coverUrl: coverUrl || '', duration }
    const plat = platform || (queue.value[idx]?.platform || '')
    audio.src = `${API_HOST}/api/songs/stream?sourceId=${encodeURIComponent(sourceId)}&name=${encodeURIComponent(name||'')}&artist=${encodeURIComponent(artist||'')}${plat ? '&platform=' + plat : ''}`
    audio.loop = playMode.value === 'single'
    resumeAudioContext()
    audio.play().catch(err => {
      console.warn('[Player] 播放失败:', err.message)
      isPlaying.value = false
    })
    isPlaying.value = true

    dispatchSongChange()
    saveToStorage()
  }

  /** 调用后端 API 获取播放 URL 并播放（记录播放历史） */
  async function playSongFromApi(sourceId, name, artist, coverUrl, platform = '') {
    if (!sourceId) return
    resumeAudioContext()
    try {
      const res = await apiPlaySong(sourceId, name, artist, coverUrl || '')
      if (res.data?.fromCache) {
        quality.value = 'LOCAL'
        qualityLabel.value = '本地缓存'
        isTrialSong.value = false
      } else {
        quality.value = 'STANDARD'
        qualityLabel.value = '标准'
        isTrialSong.value = false
      }
      playBySourceId(sourceId, name, artist, coverUrl, res.data?.duration || 0, platform)
    } catch {
      playBySourceId(sourceId, name, artist, coverUrl, 0, platform)
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
    } else {
      currentIdx.value = (currentIdx.value + 1) % queue.value.length
    }
    playCurrent()
  }

  /** 歌曲播放结束处理 */
  function onEnded() {
    // single 模式由 audio.loop=true 处理，不会触发 ended
    if (playMode.value === 'shuffle' || playMode.value === 'list-loop') {
      next()
    }
  }

  /** 切换播放模式 */
  function toggleMode() {
    const modes = ['list-loop', 'single', 'shuffle']
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
      audio.play().catch(err => {
        console.warn('[Player] 播放失败:', err.message)
        isPlaying.value = false
      })
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

  /** 页面刷新后恢复播放状态（从队列恢复） */
  function restorePlayback() {
    if (currentIdx.value >= 0 && currentIdx.value < queue.value.length) {
      const song = queue.value[currentIdx.value]
      restoreAudioFromSong(song)
    }
  }

  /** 断网重连后强制刷新音频源 */
  function onNetworkRecovery() {
    if (!currentSong.value.id) return
    const name = currentSong.value.title || ''
    const artist = currentSong.value.artist || ''
    const cachedTime = audio.currentTime || 0
    audio.src = `${API_HOST}/api/songs/stream?sourceId=${encodeURIComponent(currentSong.value.id)}&name=${encodeURIComponent(name)}&artist=${encodeURIComponent(artist)}`
    audio.loop = playMode.value === 'single'
    const onMeta = () => {
      if (cachedTime > 0 && audio.duration > 0) {
        audio.currentTime = Math.min(cachedTime, audio.duration)
      }
      audio.removeEventListener('loadedmetadata', onMeta)
      audio.play().catch(err => {
        console.warn('[Player] 音频重载播放失败:', err.message)
      })
    }
    audio.addEventListener('loadedmetadata', onMeta)
    audio.load()
  }

  if (typeof window !== 'undefined') {
    window.addEventListener('online', onNetworkRecovery)
  }

  /** 页面刷新后恢复播放状态（从 currentSong localStorage 兜底，不依赖队列） */
  function restoreFromCurrentSong() {
    const savedSong = currentSong.value
    if (!savedSong.id) return
    restoreAudioFromSong(savedSong)
  }

  /** 通用：从一首歌恢复音频源 + seek 到保存的进度 */
  function restoreAudioFromSong(song) {
    if (!song.sourceId && !song.id) return
    const id = song.sourceId || song.id

    // 恢复歌曲元数据
    if (!currentSong.value.id || currentSong.value.id !== id) {
      currentSong.value = {
        id: id, title: song.songName || song.name || song.title || '',
        artist: song.artist || '', coverUrl: song.coverUrl || '', duration: song.duration || 0,
      }
    }

    const name = song.songName || song.name || song.title || ''
    const artist = song.artist || ''
    const expectedSrc = `${API_HOST}/api/songs/stream?sourceId=${encodeURIComponent(id)}&name=${encodeURIComponent(name)}&artist=${encodeURIComponent(artist)}`
    if (audio.src !== expectedSrc && (!audio.src || audio.src === window.location.href)) {
      const cachedTime = parseFloat(localStorage.getItem(TIME_KEY) || '0')
      audio.src = expectedSrc
      audio.loop = playMode.value === 'single'

      const onMeta = () => {
        if (cachedTime > 0 && audio.duration > 0) {
          audio.currentTime = Math.min(cachedTime, audio.duration)
        }
        audio.removeEventListener('loadedmetadata', onMeta)
      }
      audio.addEventListener('loadedmetadata', onMeta)
      audio.load()
    }

    isPlaying.value = !audio.paused
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

  /** 替换队列为歌单全部歌曲并从头播放 */
  function playPlaylist(songs) {
    if (!songs || songs.length === 0) return
    clearQueue()
    songs.forEach(s => addToQueue({
      sourceId: s.sourceId,
      name: s.songName || s.name || '',
      artist: s.artist || '',
      coverUrl: s.coverUrl || '',
      duration: s.duration || 0,
      platform: s.platform || '',
    }))
    playIndex(0)
  }

  return {
    audio, queue, currentIdx, currentSong, isPlaying, isTrialSong,
    quality, qualityLabel,
    playMode, volume, progress, currentTime, duration, isMuted,
    modeLabels,
    playBySourceId, playSongFromApi, playCurrent, next, prev,
    toggleMode, togglePlay, toggleMute, addToQueue, removeFromQueue,
    playIndex, clearQueue, seekTo, seekToTime, playPlaylist,
    savePlaybackTime, flushSave, restorePlayback, restoreFromCurrentSong, resumeAudioContext,
    setupGlobalAnalyser, dispatchSongChange, fmtSec,
  }
})
