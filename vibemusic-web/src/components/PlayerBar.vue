<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { API_HOST } from '@/api/request'
import { playSong as apiPlaySong, downloadSong as apiDownload, toggleFavorite, getFavoriteIds } from '@/api/song'
import LyricsView from '@/components/LyricsView.vue'

// ===== 全局播放队列（localStorage 持久化） =====
const STORAGE_KEY = 'vibe_queue'
const IDX_KEY = 'vibe_queue_idx'
const SONG_KEY = 'vibe_current_song'
const TIME_KEY = 'vibe_playback_time'
const VOL_KEY = 'vibe_volume'

function loadQueue() {
  try { return JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]') } catch { return [] }
}
function saveQueue() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(queue.value))
  localStorage.setItem(IDX_KEY, String(currentIdx.value))
  localStorage.setItem(SONG_KEY, JSON.stringify(currentSong.value))
}
function loadSong() {
  try { return JSON.parse(localStorage.getItem(SONG_KEY) || 'null') } catch { return null }
}
function savePlaybackTime() {
  if (audio.duration && !audio.paused) {
    localStorage.setItem(TIME_KEY, String(audio.currentTime))
  }
}

const queue = ref(loadQueue())
window.vibeQueue = queue
const currentIdx = ref(parseInt(localStorage.getItem(IDX_KEY) || '-1'))

watch(queue, saveQueue, { deep: true })
const cachedSong = loadSong()
const currentSong = ref(cachedSong || { id: '', title: '未播放', artist: '', coverUrl: '', duration: 0 })
watch(currentSong, () => saveQueue(), { deep: true })
const showLyrics = ref(false)
const isPlaying = ref(false)
const progress = ref(0)
const currentTime = ref('0:00')
const totalTime = ref('0:00')
const volume = ref(parseInt(localStorage.getItem(VOL_KEY) || '70'))
const isMuted = ref(false)

// 共享全局 Audio
const audio = window.vibeAudio || new Audio()
window.vibeAudio = audio

// 全局 AudioContext + AnalyserNode（频谱用）
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
    console.log('[PlayerBar] AudioContext 已创建, state:', ctx.state)
  } catch (e) {
    console.warn('[AudioCtx]', e.message)
    window._vibeAnalyser = null
  }
}
document.addEventListener('click', setupGlobalAnalyser, { once: true })

// 恢复被浏览器挂起的 AudioContext（解决首次播放无声问题）
function resumeAudioContext() {
  if (window._vibeAudioCtx && window._vibeAudioCtx.state === 'suspended') {
    window._vibeAudioCtx.resume().then(() => {
      console.log('[PlayerBar] AudioContext 已恢复')
    }).catch(() => {})
  }
}

function pad(n) { return String(Math.floor(n)).padStart(2, '0') }
function fmtSec(s) { if (!s) return ''; const m = Math.floor(s / 60); return m + ':' + pad(s % 60) }

function restorePlayback() {
  if (currentIdx.value >= 0 && currentIdx.value < queue.value.length) {
    const song = queue.value[currentIdx.value]
    if (!currentSong.value.id) {
      currentSong.value = {
        id: song.sourceId, title: song.name, artist: song.artist,
        coverUrl: song.coverUrl || '', duration: song.duration || 0,
      }
    }
    totalTime.value = fmtSec(song.duration || 0)
    const cachedTime = parseFloat(localStorage.getItem(TIME_KEY) || '0')
    audio.src = `${API_HOST}/api/songs/stream?sourceId=${encodeURIComponent(song.sourceId)}`
    if (cachedTime > 0) {
      audio.currentTime = cachedTime
      currentTime.value = fmtSec(cachedTime)
    }
    setupGlobalAnalyser()
  }
}

// ===== 播放队列操作 =====
function addToQueue(song) {
  const exists = queue.value.findIndex(s => s.sourceId === song.sourceId)
  if (exists >= 0) {
    Object.assign(queue.value[exists], song)
    currentIdx.value = exists
  } else {
    queue.value.push(song)
    currentIdx.value = queue.value.length - 1
  }
}

function playCurrent() {
  if (currentIdx.value < 0 || currentIdx.value >= queue.value.length) return
  const song = queue.value[currentIdx.value]
  currentSong.value = {
    id: song.sourceId,
    title: song.name,
    artist: song.artist,
    coverUrl: song.coverUrl || '',
    duration: song.duration || 0,
  }
  totalTime.value = fmtSec(song.duration || 0)

  const baseURL = API_HOST
  apiPlaySong(song.sourceId, song.name, song.artist).then(res => {
    if (res.data?.url) audio.src = res.data.url  // 直接用 CDN 地址
  }).catch(() => {})
  audio.src = `${baseURL}/api/songs/stream?sourceId=${encodeURIComponent(song.sourceId)}`
  resumeAudioContext()
  audio.play().catch(() => {})
  setupGlobalAnalyser()

  // 通知 HomeView 当前播放的歌曲已变化
  window.dispatchEvent(new CustomEvent('song-change', {
    detail: {
      title: song.name,
      artist: song.artist,
      sourceId: song.sourceId,
      coverUrl: song.coverUrl || '',
      duration: song.duration || 0,
    }
  }))
}

// 播放模式
const playMode = ref('sequential')
function toggleMode() {
  const modes = ['sequential', 'random', 'single']
  const idx = modes.indexOf(playMode.value)
  playMode.value = modes[(idx + 1) % 3]
}
window.vibePlayMode = (m) => { playMode.value = m }

function onEnded() {
  if (playMode.value === 'single') {
    audio.currentTime = 0
    audio.play().catch(() => {})
  } else {
    next()
  }
}

function prev() {
  if (queue.value.length === 0) return
  if (playMode.value === 'random') {
    const r = Math.floor(Math.random() * queue.value.length)
    currentIdx.value = r === currentIdx.value ? (r + 1) % queue.value.length : r
  } else {
    currentIdx.value = currentIdx.value <= 0 ? queue.value.length - 1 : currentIdx.value - 1
  }
  playCurrent()
}

function next() {
  if (queue.value.length === 0) return
  if (playMode.value === 'random') {
    const r = Math.floor(Math.random() * queue.value.length)
    currentIdx.value = r === currentIdx.value && queue.value.length > 1
      ? (r + 1) % queue.value.length : r
  } else {
    currentIdx.value = currentIdx.value >= queue.value.length - 1 ? 0 : currentIdx.value + 1
  }
  playCurrent()
}

// 暴露给移动端播放页调用
window.vibePrev = prev
window.vibeNext = next

function playIndex(idx) {
  currentIdx.value = idx
  playCurrent()
}
window.vibePlayQueue = playIndex

function removeFromQueue(idx) {
  queue.value.splice(idx, 1)
  if (currentIdx.value >= queue.value.length) currentIdx.value = queue.value.length - 1
  if (idx === currentIdx.value) playCurrent()
}

// ===== 监听外部播放（HomeView → PlayerBar） =====
function onSongChange(e) {
  const d = e.detail
  const cover = d.coverUrl || ''
  addToQueue({
    sourceId: d.id || d.sourceId,
    name: d.title || d.name,
    artist: d.artist || '',
    coverUrl: cover,
    duration: d.duration || 0,
  })
  const existIdx = queue.value.findIndex(s => s.sourceId === (d.id || d.sourceId))
  if (existIdx >= 0 && cover && !queue.value[existIdx].coverUrl) {
    queue.value[existIdx].coverUrl = cover
  }
  if (currentIdx.value >= 0) {
    const song = queue.value[currentIdx.value]
    currentSong.value = {
      id: song.sourceId, title: song.name, artist: song.artist,
      coverUrl: song.coverUrl || '', duration: song.duration || 0,
    }
    totalTime.value = fmtSec(song.duration || 0)
  }
}

// HomeView 播放歌曲时设置 Audio 源
function setAudioSrc(url, sourceId, songName, songArtist, coverUrl) {
  if (!url) return
  const finalUrl = sourceId
    ? `${API_HOST}/api/songs/stream?sourceId=${encodeURIComponent(sourceId)}`
    : url
  console.log('[PlayerBar] setAudioSrc:', songName, 'sourceId:', sourceId)
  audio.src = finalUrl
  resumeAudioContext()
  audio.play().then(() => {
    console.log('[PlayerBar] 播放成功:', songName)
  }).catch(err => {
    console.warn('[PlayerBar] 播放被阻止:', err.message)
  })
  isPlaying.value = true
  if (sourceId) { currentSong.value = { id: sourceId, title: songName || "", artist: songArtist || "", coverUrl: coverUrl || "", duration: currentSong.value.duration || 0 } }
  setupGlobalAnalyser()
}
window.vibeAudioSetSrc = setAudioSrc

// 歌词进度跳转
function onLyricsSeek(time) {
  audio.currentTime = time
}

// Audio 事件
audio.addEventListener('timeupdate', () => {
  if (audio.duration) {
    progress.value = (audio.currentTime / audio.duration) * 100
    currentTime.value = fmtSec(audio.currentTime)
  }
})
audio.addEventListener('play', () => { isPlaying.value = true })
audio.addEventListener('pause', () => { isPlaying.value = false })
audio.addEventListener('ended', () => { onEnded() })

onMounted(() => {
  window.addEventListener('song-change', onSongChange)
  restorePlayback()
  setInterval(savePlaybackTime, 5000)
  window.addEventListener('beforeunload', savePlaybackTime)
})
onUnmounted(() => {
  window.removeEventListener('song-change', onSongChange)
  savePlaybackTime()
})

function togglePlay() {
  if (isPlaying.value) {
    audio.pause()
  } else {
    resumeAudioContext()
    if (!audio.src || audio.readyState === 0) {
      audio.load()
    }
    audio.play().catch(() => { isPlaying.value = false })
  }
}
function toggleMute() { isMuted.value = !isMuted.value; audio.muted = isMuted.value }
function seekBar(e) { audio.currentTime = (e.offsetX / e.target.offsetWidth) * audio.duration }
watch(volume, v => { audio.volume = v / 100; localStorage.setItem(VOL_KEY, String(v)) })

// 收藏 & 下载
const favIds = ref(new Set())
const downloadingIds = ref(new Set())

getFavoriteIds().then(res => {
  if (res.data) favIds.value = new Set(res.data)
}).catch(() => {})

function toggleFav(song) {
  const isFav = favIds.value.has(song.sourceId)
  if (isFav) favIds.value.delete(song.sourceId)
  else favIds.value.add(song.sourceId)
  toggleFavorite(song.sourceId, song.name, song.artist).then(res => {
    if (res.data === true) favIds.value.add(song.sourceId)
    else favIds.value.delete(song.sourceId)
  }).catch(err => {
    console.error('收藏失败:', err)
    if (isFav) favIds.value.add(song.sourceId)
    else favIds.value.delete(song.sourceId)
  })
}

function handleDownload(song) {
  if (downloadingIds.value.has(song.sourceId)) return
  downloadingIds.value.add(song.sourceId)
  apiDownload(song.sourceId, song).then(() => {
    downloadingIds.value.delete(song.sourceId)
    // 下载到 RustFS 成功后触发浏览器下载
    const a = document.createElement('a')
    a.href = `${API_HOST}/api/download/file/${song.sourceId}`
    a.download = `${song.name || song.sourceId}.mp3`
    a.click()
  }).catch(() => {
    downloadingIds.value.delete(song.sourceId)
  })
}

// 播放列表面板
const showPlaylist = ref(false)
function togglePlaylist() { showPlaylist.value = !showPlaylist.value }
</script>


<template>
  <div class="player-bar">
    <footer class="bar">
      <div class="progress-wrap">
        <span class="time">{{ currentTime }}</span>
        <div class="progress-track" @click="seekBar">
          <div class="progress-fill" :style="{ width: progress + '%' }">
            <div class="thumb"></div>
          </div>
        </div>
        <span class="time">{{ totalTime || '0:00' }}</span>
      </div>

      <div class="ctrl-wrap">
        <div class="left-info">
          <div
            class="mini-cover"
            :class="{ active: showLyrics }"
            :style="currentSong.coverUrl ? { backgroundImage: 'url(' + currentSong.coverUrl + '?param=80y80)' } : {}"
            @click="showLyrics = !showLyrics"
            title="歌词"
          >
            <span v-if="!currentSong.coverUrl">♪</span>
          </div>
          <div class="mini-song">
            <span class="mini-name">{{ currentSong.title }}</span>
            <span class="mini-artist"> - {{ currentSong.artist }}</span>
          </div>
          <button class="func-btn" :class="{ fav: favIds.has(currentSong.id) }" @click="toggleFav(currentSong)" title="收藏">
            <svg viewBox="0 0 24 24" width="22" height="22" :fill="favIds.has(currentSong.id) ? '#ec4141' : 'none'" stroke="currentColor" stroke-width="2"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>
          </button>
        </div>

        <div class="center-ctrl">
          <button class="ctrl-btn mode-btn" @click="toggleMode" :title="playMode === 'sequential' ? '顺序播放' : playMode === 'random' ? '随机播放' : '单曲循环'">
            <svg v-if="playMode === 'sequential'" viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 1l4 4-4 4"/><path d="M3 11V9a4 4 0 0 1 4-4h14"/><path d="M7 23l-4-4 4-4"/><path d="M21 13v2a4 4 0 0 1-4 4H3"/></svg>
            <svg v-else-if="playMode === 'random'" viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 1l4 4-4 4"/><path d="M3 11V9a4 4 0 0 1 4-4h14"/><path d="M7 23l-4-4 4-4"/><path d="M21 13v2a4 4 0 0 1-4 4H3"/></svg>
            <svg v-else viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 1l4 4-4 4"/><path d="M3 11V9a4 4 0 0 1 4-4h14"/><rect x="7" y="13" width="10" height="10" rx="2"/><text x="12" y="21" text-anchor="middle" font-size="8" fill="currentColor" stroke="none">1</text></svg>
          </button>
          <button class="ctrl-btn skip" @click="prev" title="上一首">
            <svg viewBox="0 0 24 24" width="28" height="28" fill="currentColor"><path d="M6 6h2v12H6zm3.5 6 8.5 6V6z"/></svg>
          </button>
          <button class="ctrl-btn main" @click="togglePlay" title="播放/暂停">
            <svg v-if="isPlaying" viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/></svg>
            <svg v-else viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><polygon points="8,5 19,12 8,19"/></svg>
          </button>
          <button class="ctrl-btn skip" @click="next" title="下一首">
            <svg viewBox="0 0 24 24" width="28" height="28" fill="currentColor"><path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/></svg>
          </button>
        </div>

        <div class="right-actions">
          <button class="act-icon" :class="{ downloading: downloadingIds.has(currentSong.id) }" @click="handleDownload(currentSong)" title="下载">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
          </button>
          <div class="vol-group">
            <button class="act-icon" @click="toggleMute" title="音量">
              <svg v-if="isMuted || volume === 0" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><line x1="23" y1="9" x2="17" y2="15"/><line x1="17" y1="9" x2="23" y2="15"/></svg>
              <svg v-else viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><path d="M15.54 8.46a5 5 0 0 1 0 7.07"/></svg>
            </button>
            <div class="vol-bar" @click="e => volume = Math.round((e.offsetX / e.target.offsetWidth) * 100)">
              <div class="vol-fill" :style="{ width: (isMuted ? 0 : volume) + '%' }"></div>
            </div>
          </div>
          <button class="act-icon" :class="{ active: showPlaylist }" @click="togglePlaylist" title="播放列表">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/></svg>
          </button>
        </div>
      </div>
    </footer>

    <!-- 播放列表面板 -->
    <Transition name="panel">
      <div v-if="showPlaylist" class="playlist-panel">
        <div class="panel-header">
          <span>播放队列 ({{ queue.length }})</span>
          <div class="panel-header-actions">
            <button v-if="queue.length" class="panel-clear-btn" @click="queue = []; currentIdx = -1">清空</button>
            <button class="panel-close" @click="showPlaylist = false">✕</button>
          </div>
        </div>
        <div class="panel-list">
          <div
            v-for="(song, idx) in queue"
            :key="song.sourceId + '_' + idx"
            class="panel-item"
            :class="{ current: idx === currentIdx }"
            @click="playIndex(idx)"
          >
            <div class="pi-cover" :style="song.coverUrl ? { backgroundImage: 'url(' + song.coverUrl + '?param=60y60)' } : {}">
              <span v-if="!song.coverUrl">♪</span>
            </div>
            <div class="pi-info">
              <span class="pi-name" :class="{ hl: idx === currentIdx }">{{ song.name }}</span>
              <span class="pi-artist">{{ song.artist }}</span>
            </div>
            <button class="pi-fav" :class="{ faved: favIds.has(song.sourceId) }" @click.stop="toggleFav(song)" :title="favIds.has(song.sourceId) ? '取消收藏' : '收藏'">⭐</button>
            <button class="pi-dl" @click.stop="handleDownload(song)" :title="downloadingIds.has(song.sourceId) ? '下载中' : '下载'">{{ downloadingIds.has(song.sourceId) ? '⏳' : '⬇' }}</button>
            <button class="pi-remove" @click.stop="removeFromQueue(idx)" title="移除">✕</button>
          </div>
          <div v-if="queue.length === 0" class="panel-empty">播放队列为空</div>
        </div>
      </div>
    </Transition>
  </div>
  <LyricsView
    :visible="showLyrics"
    :currentSong="currentSong"
    :isPlaying="isPlaying"
    :duration="audio.duration || 0"
    @update:visible="showLyrics = $event"
    @togglePlay="togglePlay"
    @prev="prev"
    @next="next"
    @seek="onLyricsSeek"
  />
</template>


<style scoped>
.player-bar {
  position: fixed; bottom: 0; left: 0; right: 0; z-index: 100;
}

.bar { padding: 10px 28px 16px; background: #fff; border-top: 1px solid #e8e8e8; box-shadow: 0 -2px 8px rgba(0,0,0,.05); }

.progress-wrap { display: flex; align-items: center; gap: 14px; margin-bottom: 14px; }
.time { font-size: 13px; color: #999; min-width: 44px; font-variant-numeric: tabular-nums; text-align: center; }
.progress-track { flex: 1; height: 4px; background: #e8e8e8; border-radius: 2px; cursor: pointer; position: relative; }
.progress-track:hover { height: 6px; }
.progress-fill { height: 100%; background: #31c27c; border-radius: 2px; position: relative; }
.thumb { position: absolute; right: -6px; top: 50%; transform: translateY(-50%); width: 12px; height: 12px; background: #31c27c; border-radius: 50%; opacity: 0; transition: opacity .2s; }
.progress-track:hover .thumb { opacity: 1; }

.ctrl-wrap { display: flex; align-items: center; justify-content: space-between; }

.left-info { display: flex; align-items: center; gap: 14px; min-width: 200px; }
.mini-cover {
  width: 44px; height: 44px; border-radius: 6px; flex-shrink: 0;
  background: #eee; display: flex; align-items: center; justify-content: center;
  font-size: 18px; color: #31c27c; cursor: pointer;
  background-size: cover; background-position: center;
  transition: transform .15s;
}
.mini-cover:hover { transform: scale(1.08); }
.mini-cover.active { box-shadow: 0 0 0 2px rgba(49,194,124,0.5); }
.mini-song { color: #666; font-size: 13px; max-width: 120px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mini-name { color: #1a1a1a; font-weight: 500; }
.mini-artist { color: #999; }
.func-btn { width: 40px; height: 40px; border: none; background: none; border-radius: 50%; color: #999; cursor: pointer; display: flex; align-items: center; justify-content: center; transition: .15s; }
.func-btn:hover { background: rgba(0,0,0,.05); color: #ec4141; }
.func-btn.fav { color: #ec4141; }

.center-ctrl { display: flex; align-items: center; gap: 12px; }
.ctrl-btn { border: none; background: none; color: #555; cursor: pointer; display: flex; align-items: center; justify-content: center; transition: .15s; }
.ctrl-btn:hover { color: #1a1a1a; }
.ctrl-btn.mode-btn { width: 40px; height: 40px; border-radius: 50%; color: #999; }
.ctrl-btn.mode-btn:hover { color: #31c27c; background: rgba(49,194,124,0.12); }
.ctrl-btn.skip { opacity: 0.75; }
.ctrl-btn.skip:hover { opacity: 1; }
.ctrl-btn.main { width: 52px; height: 28px; border-radius: 7px; background: #31c27c; color: #fff; }
.ctrl-btn.main:hover { transform: scale(1.06); }

.right-actions { display: flex; align-items: center; gap: 12px; min-width: 180px; justify-content: flex-end; }
.act-icon { width: 38px; height: 38px; border: none; background: none; border-radius: 50%; color: #999; cursor: pointer; display: flex; align-items: center; justify-content: center; transition: .15s; }
.act-icon:hover { color: #333; background: rgba(0,0,0,.05); }
.act-icon.active { color: #31c27c; background: rgba(49,194,124,0.1); }
.act-icon.downloading { color: #31c27c; }
.vol-group { display: flex; align-items: center; gap: 8px; }
.vol-bar { width: 70px; height: 4px; background: #e0e0e0; border-radius: 2px; cursor: pointer; }
.vol-bar:hover { height: 6px; }
.vol-fill { height: 100%; background: #aaa; border-radius: 2px; }
.vol-bar:hover .vol-fill { background: #31c27c; }

/* 播放列表面板 */
.playlist-panel {
  position: fixed; right: 0; bottom: 0; top: 0; width: 320px;
  background: #fff;
  border-left: 1px solid #e0e0e0;
  z-index: 110; display: flex; flex-direction: column;
}
.panel-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 18px 20px; border-bottom: 1px solid #eee;
  font-size: 15px; color: #333; flex-shrink: 0;
}
.panel-header-actions { display: flex; align-items: center; gap: 12px; }
.panel-clear-btn { background: none; border: 1px solid #ddd; border-radius: 4px; color: #999; font-size: 12px; padding: 2px 8px; cursor: pointer; }
.panel-clear-btn:hover { color: #ec4141; border-color: #ec4141; }
.panel-close { background: none; border: none; color: #999; font-size: 16px; cursor: pointer; }
.panel-close:hover { color: #333; }
.panel-list { flex: 1; overflow-y: auto; padding: 8px 0; }
.panel-empty { text-align: center; color: #999; padding: 80px 0; font-size: 13px; }

.panel-item { display: flex; align-items: center; gap: 12px; padding: 10px 20px; cursor: pointer; transition: .12s; }
.panel-item:hover { background: #f5f5f5; }
.panel-item.current { background: rgba(49,194,124,0.1); }
.pi-cover {
  width: 38px; height: 38px; border-radius: 5px; flex-shrink: 0;
  background: #eee; display: flex; align-items: center; justify-content: center;
  font-size: 14px; color: #31c27c;
  background-size: cover; background-position: center;
}
.pi-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.pi-name { font-size: 13px; color: #1a1a1a; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.pi-name.hl { color: #31c27c; }
.pi-artist { font-size: 11px; color: #888; }
.pi-fav, .pi-dl, .pi-remove { background: none; border: none; color: #ccc; font-size: 11px; cursor: pointer; opacity: 0; padding: 2px 4px; border-radius: 3px; }
.panel-item:hover .pi-fav, .panel-item:hover .pi-dl, .panel-item:hover .pi-remove { opacity: 1; }
.pi-fav:hover { color: #f0c040; }
.pi-fav.faved { color: #f0c040; opacity: 1; }
.pi-dl:hover { color: #31c27c; }
.pi-remove:hover { color: #ec4141; }

.panel-enter-active, .panel-leave-active { transition: transform .25s ease; }
.panel-enter-from, .panel-leave-to { transform: translateX(100%); }
</style>
