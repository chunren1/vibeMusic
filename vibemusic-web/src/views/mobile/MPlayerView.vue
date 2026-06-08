<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { API_HOST } from '@/api/request'
import { useRouter } from 'vue-router'
import { getLyric, toggleFavorite, getFavoriteIds, downloadSong as apiDownload } from '@/api/song'
import PlaylistPopup from '@/components/PlaylistPopup.vue'
import MQueuePopup from '@/components/mobile/MQueuePopup.vue'

const router = useRouter()
const audio = window.vibeAudio

// 当前歌曲：直接从 localStorage 读取（和底部栏同一数据源）
const song = ref({ id: '', title: '', artist: '', coverUrl: '' })
const isPlaying = ref(!audio.paused)
const currentTime = ref(0)
const duration = ref(0)
const progress = ref(0)
const lyrics = ref([])
const loadingLyric = ref(false)
const currentLyricIdx = ref(-1)
const lastSongId = ref('')

// 收藏
const favIds = ref(new Set())
const isFaved = ref(false)
getFavoriteIds().then(r => { if (r.data) favIds.value = new Set(r.data) }).catch(() => {})
function toggleFav() {
  const id = song.value.id
  if (!id) return
  const was = favIds.value.has(id)
  favIds.value[was ? 'delete' : 'add'](id)
  isFaved.value = !was
  toggleFavorite(id, song.value.title, song.value.artist, song.value.coverUrl || '').catch(() => {
    favIds.value[was ? 'add' : 'delete'](id)
    isFaved.value = was
  })
}

// 下载
const downloading = ref(false)
function doDownload() {
  if (downloading.value || !song.value.id) return
  downloading.value = true
  const audioUrl = window.vibeAudio?.src
  if (audioUrl?.startsWith('http')) {
    fetch(audioUrl).then(r => r.blob()).then(blob => {
      const u = URL.createObjectURL(blob)
      const a = document.createElement('a'); a.href = u; a.download = `${song.value.title}.mp3`; a.click()
      URL.revokeObjectURL(u)
    }).catch(() => downloadViaBackend()).finally(() => { downloading.value = false })
  } else { downloadViaBackend() }
}
function downloadViaBackend() {
  apiDownload(song.value.id, { name: song.value.title, artist: song.value.artist, coverUrl: song.value.coverUrl }).then(res => {
    const u = res.data?.fileUrl || `${API_HOST}/api/download/file/${song.value.id}`
    const a = document.createElement('a'); a.href = u; a.download = `${song.value.title}.mp3`; a.click()
  }).catch(() => {
    const a = document.createElement('a'); a.href = `${API_HOST}/api/download/file/${song.value.id}`; a.download = `${song.value.title}.mp3`; a.click()
  }).finally(() => { downloading.value = false })
}

// 加入歌单
const showPlaylistPopup = ref(false)
const showQueuePopup = ref(false)

// 播放模式
const playMode = ref('sequential')
function toggleMode() {
  const modes = ['sequential', 'random', 'single']
  const idx = modes.indexOf(playMode.value)
  playMode.value = modes[(idx + 1) % 3]
  // 同步到 PlayerBar（如果存在）
  window.vibePlayMode?.(playMode.value)
  localStorage.setItem('vibe_play_mode', playMode.value)
}

let timeSync = null

// 加载当前歌曲（从 localStorage）
function loadCurrentSong() {
  try {
    const s = JSON.parse(localStorage.getItem('vibe_current_song') || 'null')
    if (s?.id && s.id !== lastSongId.value) {
      lastSongId.value = s.id
      song.value = { id: s.id, title: s.title || '', artist: s.artist || '', coverUrl: s.coverUrl || '' }
      isFaved.value = favIds.value.has(s.id)
      lyrics.value = []
      currentLyricIdx.value = -1
      fetchLyric(s.id)
    }
  } catch {}
}

// 监听切歌事件（PlayerBar / 首页 / 搜索页 都会派发，底部栏已验证可用）
function onSongChange(e) {
  const d = e.detail
  const id = d.sourceId || d.id
  if (!id || id === lastSongId.value) return
  lastSongId.value = id
  song.value = { id, title: d.title || '', artist: d.artist || '', coverUrl: d.coverUrl || '' }
  isFaved.value = favIds.value.has(id)
  lyrics.value = []
  currentLyricIdx.value = -1
  fetchLyric(id)
}

window.addEventListener('song-change', onSongChange)

async function fetchLyric(sourceId) {
  if (!sourceId) { lyrics.value = []; return }
  loadingLyric.value = true
  try {
    const r = await getLyric(sourceId)
    lyrics.value = r.data || []
  } catch { lyrics.value = [] }
  finally { loadingLyric.value = false }
}

// 每 250ms 同步播放状态（不再轮询 localStorage，防止旧数据覆盖事件更新）
function startTimeSync() {
  stopTimeSync()
  timeSync = setInterval(() => {
    currentTime.value = audio.currentTime || 0
    duration.value = audio.duration || 0
    if (duration.value && duration.value > 0) progress.value = (currentTime.value / duration.value) * 100
    isPlaying.value = !audio.paused
    currentLyricIdx.value = activeIdx()
  }, 250)
}
function stopTimeSync() { if (timeSync) { clearInterval(timeSync); timeSync = null } }

function fmt(s) {
  if (!s || !isFinite(s)) return '0:00'
  const m = Math.floor(s / 60), sec = Math.floor(s % 60)
  return m + ':' + String(sec).padStart(2, '0')
}

function togglePlay() {
  if (audio.paused) audio.play().catch(() => {})
  else audio.pause()
}

function onSeek(e) {
  const rect = e.target.getBoundingClientRect()
  const pct = (e.clientX - rect.left) / rect.width
  audio.currentTime = pct * audio.duration
}

function activeIdx() {
  if (!lyrics.value.length) return -1
  const t = audio.currentTime
  for (let i = lyrics.value.length - 1; i >= 0; i--) {
    if (t >= (lyrics.value[i].time || 0)) return i
  }
  return -1
}

// 上一首 / 下一首
function loadQueue() {
  try { return JSON.parse(localStorage.getItem('vibe_queue') || '[]') } catch { return [] }
}

function switchTo(idx) {
  const q = loadQueue()
  if (!q.length || idx < 0 || idx >= q.length) return
  const s = q[idx]
  localStorage.setItem('vibe_queue_idx', String(idx))
  // 更新播放轨道
  audio.src = `${API_HOST}/api/songs/stream?sourceId=${encodeURIComponent(s.sourceId)}`
  audio.play().catch(() => {})
  // 同步 localStorage（底部栏会读到这个）
  localStorage.setItem('vibe_current_song', JSON.stringify({
    id: s.sourceId, title: s.name, artist: s.artist,
    coverUrl: s.coverUrl || '', duration: s.duration || 0,
  }))
  // 派发 song-change 通知所有监听者（包括本页面的 onSongChange）
  window.dispatchEvent(new CustomEvent('song-change', {
    detail: {
      sourceId: s.sourceId, title: s.name, artist: s.artist,
      coverUrl: s.coverUrl || '', duration: s.duration || 0,
    }
  }))
}

function prevSong() {
  const q = loadQueue()
  if (!q.length) return
  let idx = parseInt(localStorage.getItem('vibe_queue_idx') || '-1')
  idx = idx <= 0 ? q.length - 1 : idx - 1
  switchTo(idx)
}

function nextSong() {
  const q = loadQueue()
  if (!q.length) return
  let idx = parseInt(localStorage.getItem('vibe_queue_idx') || '-1')
  idx = idx >= q.length - 1 ? 0 : idx + 1
  switchTo(idx)
}

onMounted(() => {
  loadCurrentSong()
  startTimeSync()
})
onUnmounted(() => {
  stopTimeSync()
  window.removeEventListener('song-change', onSongChange)
})
</script>

<template>
  <div class="mp">
    <!-- 顶栏 -->
    <div class="mp-bar">
      <button class="mp-back" @click="router.push('/m')">‹</button>
      <div class="mp-bar-center">
        <div class="mp-name">{{ song.title || '未在播放' }}</div>
        <div class="mp-artist">{{ song.artist }}</div>
      </div>
      <div class="mp-spacer"></div>
    </div>

    <!-- 封面（缩小） -->
    <div class="mp-cover-section">
      <div
        class="mp-cover"
        :class="{ spinning: isPlaying }"
        :style="song.coverUrl ? { backgroundImage: `url(${song.coverUrl}?param=200y200)` } : {}"
      >
        <div v-if="!song.coverUrl" class="mp-cover-empty">♪</div>
      </div>
    </div>

    <!-- 歌词（四行） -->
    <div class="mp-lyric-section">
      <div v-if="loadingLyric" class="mp-lyric-placeholder">加载歌词中...</div>
      <div v-else-if="!lyrics.length" class="mp-lyric-placeholder">暂无歌词</div>
      <template v-else>
        <div v-if="currentLyricIdx > 1" class="mp-lyric-prev">{{ lyrics[currentLyricIdx - 2]?.text }}</div>
        <div v-else class="mp-lyric-prev"></div>
        <div v-if="currentLyricIdx > 0" class="mp-lyric-prev">{{ lyrics[currentLyricIdx - 1]?.text }}</div>
        <div v-else class="mp-lyric-prev"></div>
        <div class="mp-lyric-curr">{{ lyrics[currentLyricIdx]?.text || '' }}</div>
        <div v-if="currentLyricIdx < lyrics.length - 1" class="mp-lyric-next">{{ lyrics[currentLyricIdx + 1]?.text }}</div>
        <div v-else class="mp-lyric-next"></div>
      </template>
    </div>

    <!-- 进度条上方功能按钮 -->
    <div class="mp-top-actions">
      <button class="mp-top-btn" :class="{ faved: isFaved }" @click="toggleFav">
        <svg viewBox="0 0 24 24" width="22" height="22" :fill="isFaved ? '#ffc107' : 'none'" stroke="currentColor" stroke-width="2"><polygon points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26"/></svg>
      </button>
      <button class="mp-top-btn" @click="doDownload" :disabled="downloading">
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
      </button>
      <button class="mp-top-btn" @click="showPlaylistPopup = true">
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
      </button>
    </div>

    <!-- 进度条 -->
    <div class="mp-progress" @click="onSeek">
      <div class="mp-progress-fill" :style="{ width: progress + '%' }">
        <span class="mp-dot"></span>
      </div>
    </div>
    <div class="mp-time">
      <span>{{ fmt(currentTime) }}</span>
      <span>{{ fmt(duration) }}</span>
    </div>

    <!-- 进度条下方控制按钮 -->
    <div class="mp-ctrls">
      <button class="mp-ctrl sm" @click="toggleMode" title="切换模式">
        <svg v-if="playMode === 'sequential'" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><polyline points="17 1 21 5 17 9"/><path d="M3 11V9a4 4 0 0 1 4-4h14"/><polyline points="7 23 3 19 7 15"/><path d="M21 13v2a4 4 0 0 1-4 4H3"/></svg>
        <svg v-else-if="playMode === 'random'" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><polyline points="16 3 21 3 21 8"/><line x1="4" y1="20" x2="21" y2="3"/><polyline points="21 16 21 21 16 21"/><line x1="15" y1="15" x2="21" y2="21"/><line x1="4" y1="4" x2="9" y2="9"/></svg>
        <svg v-else viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><polyline points="17 1 21 5 17 9"/><path d="M3 11V9a4 4 0 0 1 4-4h14"/><polyline points="7 23 3 19 7 15"/><path d="M21 13v2a4 4 0 0 1-4 4H3"/><text x="12" y="17" text-anchor="middle" font-size="9" fill="currentColor" stroke="none">1</text></svg>
      </button>
      <button class="mp-ctrl" @click="prevSong">
        <svg viewBox="0 0 24 24" width="28" height="28" fill="currentColor"><path d="M6 6h2v12H6zm3.5 6l8.5 6V6l-8.5 6z"/></svg>
      </button>
      <button class="mp-ctrl mp-ctrl-play" @click="togglePlay">
        <svg v-if="isPlaying" viewBox="0 0 24 24" width="52" height="52" fill="currentColor"><rect x="6" y="4" width="4" height="16" rx="1"/><rect x="14" y="4" width="4" height="16" rx="1"/></svg>
        <svg v-else viewBox="0 0 24 24" width="52" height="52" fill="currentColor"><polygon points="8,5 19,12 8,19"/></svg>
      </button>
      <button class="mp-ctrl" @click="nextSong">
        <svg viewBox="0 0 24 24" width="28" height="28" fill="currentColor"><path d="M6 18l8.5-6L6 6v12zm8.5-12v12H16V6h-1.5z"/></svg>
      </button>
      <button class="mp-ctrl sm" @click="showQueuePopup = true" title="播放列表">
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/></svg>
      </button>
    </div>
  </div>
  <PlaylistPopup
    v-if="showPlaylistPopup"
    :song="{ sourceId: song.id, name: song.title, artist: song.artist, coverUrl: song.coverUrl }"
    @close="showPlaylistPopup = false"
    @done="showPlaylistPopup = false"
  />
  <MQueuePopup :visible="showQueuePopup" @close="showQueuePopup = false" />
</template>

<style scoped>
.mp {
  height: 100vh; height: 100dvh;
  display: flex; flex-direction: column;
  background: #0a0a0a; color: #e0e0e0;
}
.mp-bar {
  display: flex; align-items: flex-start; padding: 10px 16px 4px; flex-shrink: 0;
}
.mp-back {
  border: none; background: none; color: #ccc; font-size: 36px; line-height: 1;
  cursor: pointer; padding: 0; width: 40px; text-align: left;
}
.mp-bar-center { flex: 1; text-align: center; min-width: 0; padding-top: 4px; }
.mp-name { font-size: 17px; font-weight: 600; color: #e0e0e0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mp-artist { font-size: 13px; color: #888; margin-top: 2px; }
.mp-spacer { width: 40px; }
.mp-cover-section {
  flex: 0 0 auto; display: flex; justify-content: center; align-items: center; padding: 8px 0 12px;
}
.mp-cover {
  width: 200px; height: 200px; border-radius: 50%;
  background: rgba(255,255,255,0.05) center/cover no-repeat;
  box-shadow: 0 8px 24px rgba(0,0,0,0.4);
}
.mp-cover-empty {
  display: flex; align-items: center; justify-content: center;
  height: 100%; font-size: 40px; color: #333;
}
@keyframes spin { from { transform: rotate(0deg) } to { transform: rotate(360deg) } }
.mp-cover.spinning { animation: spin 20s linear infinite; }
.mp-lyric-section {
  flex: 1; display: flex; flex-direction: column; justify-content: center;
  padding: 0 24px; min-height: 100px; overflow: hidden;
}
.mp-lyric-prev, .mp-lyric-next {
  text-align: center; font-size: 13px; color: #555; line-height: 1.6;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; min-height: 26px;
}
.mp-lyric-curr {
  text-align: center; font-size: 17px; font-weight: 500; color: #31c27c;
  line-height: 1.6; padding: 6px 0;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.mp-lyric-placeholder { text-align: center; color: #666; font-size: 14px; }

/* 进度条上方操作 */
.mp-top-actions {
  display: flex; justify-content: space-around; padding: 8px 0 12px; flex-shrink: 0;
}
.mp-top-btn {
  border: none; background: none; cursor: pointer; padding: 6px; color: #888;
  flex: 1; display: flex; justify-content: center;
}
.mp-top-btn.faved { color: #ffc107; }
.mp-top-btn:active { color: #31c27c; }
.mp-top-btn:disabled { opacity: .4; }

.mp-progress {
  height: 4px; margin: 0 28px; background: rgba(255,255,255,.12);
  border-radius: 2px; cursor: pointer; flex-shrink: 0; position: relative;
}
.mp-progress-fill {
  height: 100%; background: #31c27c; border-radius: 2px;
  min-width: 4px; position: relative; transition: width .2s linear;
}
.mp-dot {
  position: absolute; right: -6px; top: 50%; transform: translateY(-50%);
  width: 12px; height: 12px; border-radius: 50%; background: #31c27c;
  opacity: 0; transition: opacity .2s;
}
.mp-progress:hover .mp-dot { opacity: 1; }

.mp-time {
  display: flex; justify-content: space-between; padding: 6px 28px 0;
  font-size: 11px; color: #666; flex-shrink: 0;
}
.mp-ctrls {
  display: flex; align-items: center; justify-content: center; gap: 20px;
  padding: 8px 0 calc(24px + env(safe-area-inset-bottom, 0px)); flex-shrink: 0;
}
.mp-ctrl {
  border: none; background: none; color: #ccc; cursor: pointer; padding: 6px;
  display: flex; align-items: center; justify-content: center;
}
.mp-ctrl.sm { font-size: 18px; padding: 6px; color: #888; }
.mp-ctrl-play { color: #31c27c; }
</style>
