<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { getLyric, toggleFavorite, getFavoriteIds, downloadSong as apiDownload } from '@/api/song'
import { API_HOST } from '@/api/request'
import { usePlayerStore } from '@/stores/player'
import { useAudioBackground } from '@/composables/useAudioBackground'
import MQueuePopup from '@/components/mobile/MQueuePopup.vue'

const router = useRouter()
const store = usePlayerStore()
const audio = store.audio

// 歌词
const lyrics = ref([])
const loadingLyric = ref(false)
const currentLyricIdx = ref(-1)
const lastSongId = ref('')

// 收藏
const favIds = ref(new Set())
const isFaved = ref(false)
getFavoriteIds().then(r => { if (r.data) favIds.value = new Set(r.data) }).catch(() => {})

function toggleFav() {
  const id = store.currentSong.id
  if (!id) return
  const was = favIds.value.has(id)
  favIds.value[was ? 'delete' : 'add'](id)
  isFaved.value = !was
  toggleFavorite(id, store.currentSong.title, store.currentSong.artist, store.currentSong.coverUrl || '').catch(() => {
    favIds.value[was ? 'add' : 'delete'](id)
    isFaved.value = was
  })
}

// 下载
const downloading = ref(false)
function doDownload() {
  if (downloading.value || !store.currentSong.id) return
  downloading.value = true
  apiDownload(store.currentSong.id, { name: store.currentSong.title, artist: store.currentSong.artist, coverUrl: store.currentSong.coverUrl }).then(res => {
    const u = res.data?.fileUrl || `${API_HOST}/api/download/file/${store.currentSong.id}`
    const a = document.createElement('a'); a.href = u; a.download = `${store.currentSong.title}.mp3`; a.click()
  }).catch(() => {
    const a = document.createElement('a'); a.href = `${API_HOST}/api/download/file/${store.currentSong.id}`; a.download = `${store.currentSong.title}.mp3`; a.click()
  }).finally(() => { downloading.value = false })
}

// 弹窗
const showQueuePopup = ref(false)

// 播放模式
const modeLabels = store.modeLabels

// 后台播放支持
const audioBg = useAudioBackground()

// 歌词获取
async function fetchLyric(sourceId) {
  if (!sourceId) { lyrics.value = []; return }
  loadingLyric.value = true
  try {
    const r = await getLyric(sourceId)
    lyrics.value = r.data || []
  } catch { lyrics.value = [] }
  finally { loadingLyric.value = false }
}

// 监听切歌事件
function onSongChange(e) {
  const d = e.detail
  const id = d.sourceId || d.id
  if (!id || id === lastSongId.value) return
  lastSongId.value = id
  isFaved.value = favIds.value.has(id)
  lyrics.value = []
  currentLyricIdx.value = -1
  fetchLyric(id)
}

// 时间同步（更新歌词索引）
function tick() {
  currentLyricIdx.value = activeIdx()
}

function activeIdx() {
  if (!lyrics.value.length) return -1
  const t = audio.currentTime
  for (let i = lyrics.value.length - 1; i >= 0; i--) {
    if (t >= (lyrics.value[i].time || 0)) return i
  }
  return -1
}

function fmt(s) {
  if (!s || !isFinite(s)) return '0:00'
  const m = Math.floor(s / 60), sec = Math.floor(s % 60)
  return m + ':' + String(sec).padStart(2, '0')
}

function togglePlay() { store.togglePlay() }

function onSeek(e) {
  const rect = e.target.getBoundingClientRect()
  const pct = (e.clientX - rect.left) / rect.width
  store.seekTo(pct)
}

onMounted(() => {
  window.addEventListener('song-change', onSongChange)
  // 加载当前歌曲歌词
  const id = store.currentSong.id
  if (id && id !== lastSongId.value) {
    lastSongId.value = id
    isFaved.value = favIds.value.has(id)
    fetchLyric(id)
  }
  audioBg.startWorkerTimer(tick, 250)
})
onUnmounted(() => {
  window.removeEventListener('song-change', onSongChange)
  audioBg.stopWorkerTimer()
})
</script>

<template>
  <div class="mp">
    <!-- 顶栏 -->
    <div class="mp-bar">
      <button class="mp-back" @click="router.push('/m')">‹</button>
      <div class="mp-bar-center">
        <div class="mp-name">{{ store.currentSong.title || '未在播放' }}</div>
        <div class="mp-artist">{{ store.currentSong.artist }}</div>
      </div>
      <div class="mp-spacer"></div>
    </div>

    <!-- 封面 -->
    <div class="mp-cover-section">
      <div
        class="mp-cover"
        :class="{ spinning: store.isPlaying }"
        :style="store.currentSong.coverUrl ? { backgroundImage: `url(${store.currentSong.coverUrl}?param=200y200)` } : {}"
      >
        <div v-if="!store.currentSong.coverUrl" class="mp-cover-empty">♪</div>
      </div>
    </div>

    <!-- 歌词 -->
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
      <button class="mp-top-btn" @click="showQueuePopup = true" title="播放列表">
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
      </button>
    </div>

    <!-- 进度条 -->
    <div class="mp-progress" @click="onSeek">
      <div class="mp-progress-fill" :style="{ width: store.progress + '%' }">
        <span class="mp-dot"></span>
      </div>
    </div>
    <div class="mp-time">
      <span>{{ fmt(store.currentTime) }}</span>
      <span>{{ fmt(store.duration) }}</span>
    </div>

    <!-- 进度条下方控制按钮 -->
    <div class="mp-ctrls">
      <button class="mp-ctrl sm" :class="{ 'mode-active': store.playMode !== 'list-loop' }" @click="store.toggleMode()" :title="modeLabels[store.playMode]">
        <svg v-if="store.playMode === 'list-loop'" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><polyline points="17 1 21 5 17 9"/><path d="M3 11V9a4 4 0 0 1 4-4h14"/><polyline points="7 23 3 19 7 15"/><path d="M21 13v2a4 4 0 0 1-4 4H3"/></svg>
        <svg v-else-if="store.playMode === 'single'" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><polyline points="17 1 21 5 17 9"/><path d="M3 11V9a4 4 0 0 1 4-4h14"/><polyline points="7 23 3 19 7 15"/><path d="M21 13v2a4 4 0 0 1-4 4H3"/><text x="12" y="16.5" text-anchor="middle" dominant-baseline="central" font-size="9" font-weight="700" fill="currentColor" stroke="none">1</text></svg>
        <svg v-else-if="store.playMode === 'shuffle'" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><polyline points="16 3 21 3 21 8"/><line x1="4" y1="20" x2="21" y2="3"/><polyline points="21 16 21 21 16 21"/><line x1="15" y1="15" x2="21" y2="21"/><line x1="4" y1="4" x2="9" y2="9"/></svg>
        <svg v-else viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><polyline points="17 1 21 5 17 9"/><path d="M3 11V9a4 4 0 0 1 4-4h14"/><path d="M3 13v2a4 4 0 0 0 4 4h7"/></svg>
      </button>
      <button class="mp-ctrl" @click="store.prev()">
        <svg viewBox="0 0 24 24" width="28" height="28" fill="currentColor"><path d="M6 6h2v12H6zm3.5 6l8.5 6V6l-8.5 6z"/></svg>
      </button>
      <button class="mp-ctrl mp-ctrl-play" @click="togglePlay">
        <svg v-if="store.isPlaying" viewBox="0 0 24 24" width="52" height="52" fill="currentColor"><rect x="6" y="4" width="4" height="16" rx="1"/><rect x="14" y="4" width="4" height="16" rx="1"/></svg>
        <svg v-else viewBox="0 0 24 24" width="52" height="52" fill="currentColor"><polygon points="8,5 19,12 8,19"/></svg>
      </button>
      <button class="mp-ctrl" @click="store.next()">
        <svg viewBox="0 0 24 24" width="28" height="28" fill="currentColor"><path d="M6 18l8.5-6L6 6v12zm8.5-12v12H16V6h-1.5z"/></svg>
      </button>
      <button class="mp-ctrl sm" @click="showQueuePopup = true" title="播放列表">
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/></svg>
      </button>
    </div>
  </div>
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
.mp-ctrl.sm.mode-active { color: #31c27c; }
.mp-ctrl-play { color: #31c27c; }
</style>
