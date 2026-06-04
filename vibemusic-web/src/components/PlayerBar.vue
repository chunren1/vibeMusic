<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { playSong as apiPlaySong, downloadSong as apiDownload, toggleFavorite, getFavoriteIds } from '@/api/song'

// ===== 全局播放队列（localStorage 持久化） =====
const STORAGE_KEY = 'vibe_queue'
const IDX_KEY = 'vibe_queue_idx'

function loadQueue() {
  try { return JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]') } catch { return [] }
}
function saveQueue(q) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(q))
  localStorage.setItem(IDX_KEY, String(currentIdx.value))
}

const queue = ref(loadQueue())
window.vibeQueue = queue
const currentIdx = ref(parseInt(localStorage.getItem(IDX_KEY) || '-1'))

// 队列变化时自动保存
watch(queue, saveQueue, { deep: true })

const currentSong = ref({ id: '', title: '未播放', artist: '', coverUrl: '', duration: 0 })
const isPlaying = ref(false)
const progress = ref(0)
const currentTime = ref('0:00')
const totalTime = ref('0:00')
const volume = ref(70)
const isMuted = ref(false)

// 共享全局 Audio
const audio = window.vibeAudio || new Audio()
window.vibeAudio = audio

function pad(n) { return String(Math.floor(n)).padStart(2, '0') }
function fmtSec(s) { if (!s) return ''; const m = Math.floor(s / 60); return m + ':' + pad(s % 60) }

// ===== 播放队列操作 =====
function addToQueue(song) {
  // song = { sourceId, name, artist, coverUrl, duration }
  const exists = queue.value.findIndex(s => s.sourceId === song.sourceId)
  if (exists >= 0) {
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

  // 获取实际播放 URL
  apiPlaySong(song.sourceId, song.name, song.artist).then(res => {
    const url = res.data?.url
    if (url) {
      audio.src = url
      audio.play().catch(() => {})
    }
  }).catch(() => {})
}

// 播放模式: sequential(顺序) | random(随机) | single(单曲循环)
const playMode = ref('sequential')
const modeIcons = {
  sequential: '↻',   // 循环箭头
  random: '⇄',       // 交叉箭头
  single: '↻¹'      // 循环 + 1
}
function toggleMode() {
  const modes = ['sequential', 'random', 'single']
  const idx = modes.indexOf(playMode.value)
  playMode.value = modes[(idx + 1) % 3]
}

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

function playIndex(idx) {
  currentIdx.value = idx
  playCurrent()
}

function removeFromQueue(idx) {
  queue.value.splice(idx, 1)
  if (currentIdx.value >= queue.value.length) currentIdx.value = queue.value.length - 1
  if (idx === currentIdx.value) playCurrent()
}

// ===== 监听外部播放（HomeView → PlayerBar） =====
function onSongChange(e) {
  const d = e.detail
  addToQueue({
    sourceId: d.id || d.sourceId,
    name: d.title || d.name,
    artist: d.artist || '',
    coverUrl: d.coverUrl || '',
    duration: d.duration || 0,
  })
  // 仅更新 UI，不重复获取 URL（HomeView 已获取）
  if (currentIdx.value >= 0) {
    const song = queue.value[currentIdx.value]
    currentSong.value = {
      id: song.sourceId, title: song.name, artist: song.artist,
      coverUrl: song.coverUrl || '', duration: song.duration || 0,
    }
    totalTime.value = fmtSec(song.duration || 0)
  }
}

// HomeView 获取到 URL 后通过此函数设置 Audio 源
function setAudioSrc(url) {
  if (!url) return
  audio.src = url
  audio.play().catch(() => {})
  isPlaying.value = true
}
window.vibeAudioSetSrc = setAudioSrc

// ===== Audio 事件 =====
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
})
onUnmounted(() => {
  window.removeEventListener('song-change', onSongChange)
})

function togglePlay() {
  if (isPlaying.value) { audio.pause() } else { audio.play().catch(() => {}) }
}
function toggleMute() {
  isMuted.value = !isMuted.value
  audio.muted = isMuted.value
}
function seekBar(e) {
  audio.currentTime = (e.offsetX / e.target.offsetWidth) * audio.duration
}
watch(volume, v => { audio.volume = v / 100 })

// 收藏 & 下载
const favIds = ref(new Set())
const downloadingIds = ref(new Set())

getFavoriteIds().then(res => {
  if (res.data) favIds.value = new Set(res.data)
}).catch(() => {})

function toggleFav(song) {
  toggleFavorite(song.sourceId, song.name, song.artist).then(res => {
    if (res.data === true) favIds.value.add(song.sourceId)
    else favIds.value.delete(song.sourceId)
  }).catch(() => {})
}

function handleDownload(song) {
  if (downloadingIds.value.has(song.sourceId)) return
  downloadingIds.value.add(song.sourceId)
  apiDownload(song.sourceId, song).then(() => {
    downloadingIds.value.delete(song.sourceId)
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
    <div class="song-info">
      <div
        class="mini-cover"
        :style="currentSong.coverUrl ? { backgroundImage: 'url(' + currentSong.coverUrl + '?param=100y100)' } : {}"
      >
        <span v-if="!currentSong.coverUrl">♪</span>
      </div>
      <div class="info-text">
        <p class="song-title">{{ currentSong.title }}</p>
        <p class="song-artist">{{ currentSong.artist }}</p>
      </div>
    </div>

    <div class="player-controls">
      <div class="control-btns">
        <button class="ctrl-btn mode-btn" :class="{ active: playMode !== 'sequential' }" @click="toggleMode" :title="'模式: ' + (playMode === 'sequential' ? '顺序播放' : playMode === 'random' ? '随机播放' : '单曲循环')">
          {{ modeIcons[playMode] }}
        </button>
        <button class="ctrl-btn" @click="prev" title="上一首">⏮</button>
        <button class="ctrl-btn play-btn" @click="togglePlay" :title="isPlaying ? '暂停' : '播放'">
          {{ isPlaying ? '⏸' : '▶' }}
        </button>
        <button class="ctrl-btn" @click="next" title="下一首">⏭</button>
      </div>
      <div class="progress-area">
        <span class="time">{{ currentTime }}</span>
        <div class="progress-bar" @click="seekBar">
          <div class="progress-fill" :style="{ width: progress + '%' }"></div>
        </div>
        <span class="time">{{ totalTime || '0:00' }}</span>
      </div>
    </div>

    <div class="right-area">
      <div class="volume-area">
        <button class="mute-btn" @click="toggleMute">{{ isMuted ? '🔇' : '🔊' }}</button>
        <div class="volume-bar" @click="e => volume = (e.offsetX / e.target.offsetWidth) * 100">
          <div class="volume-fill" :style="{ width: volume + '%' }"></div>
        </div>
      </div>
      <button class="panel-btn" :class="{ active: showPlaylist }" @click="togglePlaylist" title="播放列表">
        📋
        <span v-if="queue.length" class="queue-count">{{ queue.length }}</span>
      </button>
    </div>

    <!-- 播放列表面板 -->
    <Transition name="panel">
      <div v-if="showPlaylist" class="playlist-panel">
        <div class="panel-header">
          <span>播放队列 ({{ queue.length }})</span>
          <button class="panel-close" @click="showPlaylist = false">✕</button>
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
            <button
              class="pi-fav"
              :class="{ faved: favIds.has(song.sourceId) }"
              @click.stop="toggleFav(song)"
              :title="favIds.has(song.sourceId) ? '取消收藏' : '收藏'"
            >{{ favIds.has(song.sourceId) ? '⭐' : '☆' }}</button>
            <button
              class="pi-dl"
              @click.stop="handleDownload(song)"
              :title="downloadingIds.has(song.sourceId) ? '下载中' : '下载'"
            >{{ downloadingIds.has(song.sourceId) ? '⏳' : '⬇' }}</button>
            <button class="pi-remove" @click.stop="removeFromQueue(idx)" title="移除">✕</button>
          </div>
          <div v-if="queue.length === 0" class="panel-empty">播放队列为空</div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.player-bar {
  position: fixed; bottom: 0; left: 0; right: 0; height: 80px;
  background: #fff; border-top: 1px solid #e0e0e0;
  display: flex; align-items: center; gap: 20px;
  padding: 0 24px; z-index: 100;
  box-shadow: 0 -2px 8px rgba(0,0,0,.06);
}
.song-info {
  display: flex; align-items: center; gap: 14px; width: 240px; flex-shrink: 0;
}
.mini-cover {
  width: 54px; height: 54px; border-radius: 8px;
  background: #e0e0e0;
  display: flex; align-items: center; justify-content: center;
  font-size: 22px; color: #31c27c; flex-shrink: 0;
  background-size: cover; background-position: center;
}
.info-text { flex: 1; min-width: 0; }
.song-title { font-size: 15px; color: #333; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.song-artist { font-size: 13px; color: #777; margin-top: 3px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.player-controls { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 8px; }
.control-btns { display: flex; align-items: center; gap: 24px; }
.ctrl-btn { background: none; border: none; color: #555; font-size: 22px; cursor: pointer; }
.ctrl-btn:hover { color: #1a1a1a; }
.mode-btn { font-size: 16px; color: #999; position: relative; }
.mode-btn:hover { color: #1a1a1a; }
.mode-btn.active { color: #31c27c; }
.play-btn {
  width: 44px; height: 44px; border-radius: 50%;
  background: #31c27c; color: #fff; font-size: 16px;
  display: flex; align-items: center; justify-content: center;
}
.play-btn:hover { background: #28a86b; }
.progress-area { display: flex; align-items: center; gap: 12px; width: 100%; max-width: 520px; }
.time { font-size: 13px; color: #888; width: 40px; text-align: center; }
.progress-bar {
  flex: 1; height: 5px; background: #e0e0e0; border-radius: 3px;
  position: relative; cursor: pointer;
}
.progress-fill { height: 100%; background: #31c27c; border-radius: 3px; transition: width .2s; }

.right-area { display: flex; align-items: center; gap: 14px; flex-shrink: 0; }
.panel-btn {
  position: relative; background: none; border: none;
  color: #888; font-size: 22px; cursor: pointer; padding: 6px; border-radius: 6px;
}
.panel-btn:hover { color: #333; background: rgba(0,0,0,.04); }
.panel-btn.active { color: #31c27c; }
.queue-count {
  position: absolute; top: -2px; right: -4px;
  background: #31c27c; color: #fff; font-size: 10px;
  border-radius: 50%; width: 16px; height: 16px;
  display: flex; align-items: center; justify-content: center;
}

.volume-area { display: flex; align-items: center; gap: 10px; }
.mute-btn { background: none; border: none; font-size: 20px; cursor: pointer; color: #888; }
.volume-bar { width: 100px; height: 5px; background: #e0e0e0; border-radius: 3px; cursor: pointer; }
.volume-fill { height: 100%; background: #999; border-radius: 3px; }

/* ===== 播放列表面板 ===== */
.playlist-panel {
  position: fixed; right: 0; bottom: 80px; top: 0; width: 320px;
  background: #fff; border-left: 1px solid #e0e0e0;
  z-index: 99; display: flex; flex-direction: column;
  box-shadow: -4px 0 24px rgba(0,0,0,.08);
}
.panel-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 18px 20px; border-bottom: 1px solid #eee;
  font-size: 15px; color: #333; flex-shrink: 0;
}
.panel-close { background: none; border: none; color: #999; font-size: 16px; cursor: pointer; }
.panel-close:hover { color: #333; }
.panel-list { flex: 1; overflow-y: auto; padding: 8px 0; }
.panel-empty { text-align: center; color: #999; padding: 60px 0; font-size: 13px; }

.panel-item {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 20px; cursor: pointer; transition: .12s;
}
.panel-item:hover { background: #f0f0f0; }
.panel-item.current { background: rgba(49,194,124,.1); }
.pi-cover {
  width: 38px; height: 38px; border-radius: 5px; flex-shrink: 0;
  background: #e0e0e0; display: flex; align-items: center; justify-content: center;
  font-size: 14px; color: #999;
  background-size: cover; background-position: center;
}
.pi-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.pi-name { font-size: 13px; color: #1a1a1a; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.pi-name.hl { color: #31c27c; }
.pi-artist { font-size: 11px; color: #888; }
.pi-fav, .pi-dl, .pi-remove {
  background: none; border: none; color: #444; font-size: 12px;
  cursor: pointer; opacity: 0; padding: 2px 4px; border-radius: 3px;
}
.panel-item:hover .pi-fav, .panel-item:hover .pi-dl, .panel-item:hover .pi-remove { opacity: 1; }
.pi-fav:hover { color: #f0c040; background: rgba(255,255,255,.05); }
.pi-fav.faved { color: #f0c040; opacity: 1; }
.pi-dl:hover { color: #31c27c; background: rgba(255,255,255,.05); }
.pi-remove:hover { color: #e84c3d; background: rgba(255,255,255,.05); }

.panel-enter-active, .panel-leave-active { transition: transform .25s ease; }
.panel-enter-from, .panel-leave-to { transform: translateX(100%); }
</style>
