<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { getLyric, downloadSong as apiDownload } from '@/api/song'
import { getPlaylists, addToPlaylist } from '@/api/song'
import { API_HOST } from '@/api/request'
import { usePlayerStore } from '@/stores/player'
import { useFavoriteStore } from '@/stores/favorite'
import { useAudioBackground } from '@/composables/useAudioBackground'
import MQueuePopup from '@/components/mobile/MQueuePopup.vue'

const router = useRouter()
const store = usePlayerStore()
const favStore = useFavoriteStore()
const audio = store.audio

// 歌词
const lyrics = ref([])
const loadingLyric = ref(false)
const currentLyricIdx = ref(-1)
const lastSongId = ref('')

// 收藏
const isFaved = ref(false)
favStore.fetchFavIds()

function toggleFav() {
  const id = store.currentSong.id
  if (!id) return
  const was = favStore.isFav(id)
  isFaved.value = !was
  favStore.toggleFav({ sourceId: id, name: store.currentSong.title, artist: store.currentSong.artist, coverUrl: store.currentSong.coverUrl })
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

// 加入歌单
const showPlaylistPicker = ref(false)
const userPlaylists = ref([])
const addingToPlaylist = ref(null) // 正在添加的歌单ID

function openPlaylistPicker() {
  getPlaylists().then(r => { userPlaylists.value = r.data || [] }).catch(() => {})
  showPlaylistPicker.value = true
}

function doAddToPlaylist(pl) {
  if (addingToPlaylist.value || !store.currentSong.id) return
  addingToPlaylist.value = pl.id
  const song = {
    sourceId: store.currentSong.id,
    name: store.currentSong.title,
    artist: store.currentSong.artist,
    coverUrl: store.currentSong.coverUrl || '',
    duration: store.duration || 0,
  }
  addToPlaylist(pl.id, song).then((res) => {
    if (res.data === false) {
      window.toast?.('歌曲已在歌单中', 'warning')
    } else {
      showPlaylistPicker.value = false
      window.toast?.('已加入歌单', 'success')
    }
  }).catch(() => {
    window.toast?.('加入失败', 'error')
  }).finally(() => { addingToPlaylist.value = null })
}

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
  isFaved.value = favStore.isFav(id)
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

const progressBar = ref(null)
const isSeeking = ref(false)       // 拖拽动画状态
const seekPreview = ref('')        // 拖拽时的时间预览

function seekAt(clientX) {
  if (!progressBar.value) return
  const rect = progressBar.value.getBoundingClientRect()
  const pct = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width))
  store.seekTo(pct)
  seekPreview.value = fmt(pct * (store.duration || 0))
}

function onSeek(e) {
  isSeeking.value = true
  seekAt(e.clientX)
  isSeeking.value = false
}

// 触摸拖拽进度条
function onTouchStart(e) {
  isSeeking.value = true
  e.preventDefault()
  seekAt(e.touches[0].clientX)
}
function onTouchMove(e) {
  seekAt(e.touches[0].clientX)
}
function onTouchEnd() {
  isSeeking.value = false
}

onMounted(() => {
  window.addEventListener('song-change', onSongChange)

  // 刷新恢复：如果 audio 无源但有 currentSong 数据，从 localStorage 恢复
  if ((!audio.src || audio.src === window.location.href) && store.currentSong.id) {
    store.restoreFromCurrentSong()
  }

  // 加载当前歌曲歌词
  const id = store.currentSong.id
  if (id && id !== lastSongId.value) {
    lastSongId.value = id
    isFaved.value = favStore.isFav(id)
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
        <div class="mp-name">{{ store.currentSong.title || '未在播放' }}<span class="mp-quality">{{ store.qualityLabel }}</span></div>
        <div class="mp-artist">{{ store.currentSong.artist }}</div>
      </div>
      <div class="mp-spacer"></div>
    </div>

    <!-- 模糊背景 -->
    <div class="mp-blur-bg" :style="store.currentSong.coverUrl ? { backgroundImage: `url(${store.currentSong.coverUrl}?param=400y400)` } : {}"></div>

    <!-- 封面 -->
    <div class="mp-cover-section">
      <div
        class="mp-cover"
        :class="{ spinning: store.isPlaying }"
        :style="store.currentSong.coverUrl ? { backgroundImage: `url(${store.currentSong.coverUrl}?param=400y400)` } : {}"
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
      <button class="mp-top-btn" @click="openPlaylistPicker" title="加入歌单">
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
      </button>
    </div>

    <!-- 进度条 -->
    <div ref="progressBar" class="mp-progress" :class="{ seeking: isSeeking }"
      @click="onSeek"
      @touchstart="onTouchStart"
      @touchmove="onTouchMove"
      @touchend="onTouchEnd">
      <!-- 拖拽时的时间气泡 -->
      <div v-if="isSeeking" class="mp-seek-bubble">{{ seekPreview }}</div>
      <div class="mp-progress-fill" :style="{ transform: `scaleX(${store.progress / 100})` }">
        <span class="mp-dot"></span>
      </div>
    </div>
    <div class="mp-time" :class="{ seeking: isSeeking }">
      <span>{{ isSeeking ? seekPreview : fmt(store.currentTime) }}</span>
      <span>{{ fmt(store.duration) }}</span>
    </div>

    <!-- 进度条下方控制按钮 -->
    <div class="mp-ctrls">
      <button class="mp-ctrl sm" :class="{ 'mode-active': store.playMode !== 'list-loop' }" @click="store.toggleMode()" :title="modeLabels[store.playMode]">
        <svg v-if="store.playMode === 'list-loop'" viewBox="0 0 1280 1024" width="20" height="20" fill="currentColor"><path d="M1121.8 243.7A373.4 373.4 0 0 1 1231.9 509.5c0 34.2-4.6 68.2-13.7 100.8a42.4 42.4 0 0 1-81.7-22.6 291.9 291.9 0 0 0 10.6-78.2c0-160.5-130.6-291.1-291.1-291.1H461.5v75.1c0 24.1-16.8 33.5-37.3 20.8L243.5 202.2c-20.5-12.7-20.7-33.8-.4-46.9L424.7 38.1c20.2-13.1 36.8-4 36.8 20.1v75.4h394.5c100.4 0 194.8 39.1 265.8 110.1zm-70 573.1c20.5 12.7 20.7 33.8.4 46.8l-181.6 117.3c-20.2 13.1-36.8 4.1-36.8-20V885.4H407.9c-100.4 0-194.8-39.1-265.8-110.1A373.4 373.4 0 0 1 32 509.5c0-72.6 20.7-143.1 60-203.9a42.4 42.4 0 1 1 71.2 46 290 290 0 0 0-46.4 157.8c0 160.6 130.6 291.2 291.1 291.2h425.9v-75.1c0-24.1 16.8-33.5 37.2-20.7l180.8 111.9z"/></svg>
        <svg v-else-if="store.playMode === 'single'" viewBox="0 0 1024 1024" width="20" height="20" fill="currentColor"><path d="M928 476.8c-19.2 0-32 12.8-32 32v86.4c0 108.8-86.4 198.4-198.4 198.4H201.6l41.6-38.4c6.4-6.4 12.8-16 12.8-25.6 0-19.2-16-35.2-35.2-35.2-9.6 0-22.4 3.2-28.8 9.6l-108.8 99.2c-16 12.8-12.8 35.2 0 48l108.8 96c6.4 6.4 19.2 12.8 28.8 12.8 19.2 0 35.2-12.8 38.4-32 0-12.8-6.4-22.4-16-28.8l-48-44.8h499.2c147.2 0 265.6-118.4 265.6-259.2v-86.4c0-19.2-12.8-32-32-32zM96 556.8c19.2 0 32-12.8 32-32v-89.6c0-112 89.6-201.6 198.4-204.8h496l-41.6 38.4c-6.4 6.4-12.8 16-12.8 25.6 0 19.2 16 35.2 35.2 35.2 9.6 0 22.4-3.2 28.8-9.6l105.6-99.2c16-12.8 12.8-35.2 0-48l-108.8-96c-6.4-6.4-19.2-12.8-28.8-12.8-19.2 0-35.2 12.8-38.4 32 0 12.8 6.4 22.4 16 28.8l48 44.8H329.6C182.4 169.6 64 288 64 438.4v86.4c0 19.2 12.8 32 32 32z"/><path d="M544 672V352h-48L416 409.6l16 41.6 60.8-41.6V672z"/></svg>
        <svg v-else-if="store.playMode === 'shuffle'" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><polyline points="16 3 21 3 21 8"/><line x1="4" y1="20" x2="21" y2="3"/><polyline points="21 16 21 21 16 21"/><line x1="15" y1="15" x2="21" y2="21"/><line x1="4" y1="4" x2="9" y2="9"/></svg>
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

  <!-- 加入歌单弹窗 -->
  <Teleport to="body">
    <div v-if="showPlaylistPicker" class="mp-overlay" @click.self="showPlaylistPicker = false">
      <div class="mp-picker">
        <div class="mp-picker-hd">
          <span>加入歌单</span>
          <button @click="showPlaylistPicker = false">✕</button>
        </div>
        <div v-if="!userPlaylists.length" class="mp-picker-empty">还没有歌单，去创建一个吧</div>
        <div v-else class="mp-picker-list">
          <div
            v-for="pl in userPlaylists" :key="pl.id"
            class="mp-picker-item"
            :class="{ adding: addingToPlaylist === pl.id }"
            @click="doAddToPlaylist(pl)"
          >
            <span class="mp-picker-name">{{ pl.name }}</span>
            <span class="mp-picker-count">{{ pl.songCount || 0 }} 首</span>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.mp {
  height: 100vh; height: 100dvh;
  display: flex; flex-direction: column;
  background: var(--m-bg-base); color: var(--m-text-primary);
  position: relative; overflow: hidden;
}
.mp-blur-bg {
  position: absolute; inset: 0; z-index: 0;
  background: var(--m-bg-base) center/cover no-repeat;
  filter: blur(50px) brightness(0.25) saturate(1.4);
  opacity: 0.7; transform: scale(1.15);
  pointer-events: none;
}
.mp-bar {
  display: flex; align-items: flex-start; padding: 10px 16px 4px; flex-shrink: 0;
  position: relative; z-index: 1;
}
.mp-back {
  border: none; background: none; color: var(--m-text-secondary); font-size: 36px; line-height: 1;
  cursor: pointer; padding: 0; width: 40px; text-align: left;
}
.mp-bar-center { flex: 1; text-align: center; min-width: 0; padding-top: 4px; }
.mp-name { font-size: 17px; font-weight: 700; color: var(--m-text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; letter-spacing: 0.3px; }
.mp-quality { font-size: 10px; padding: 2px 6px; border-radius: 4px; margin-left: 6px; background: rgba(46,229,154,0.12); color: var(--m-primary); font-weight: 600; vertical-align: middle; }
.mp-artist { font-size: 13px; color: var(--m-text-secondary); margin-top: 2px; }
.mp-spacer { width: 40px; }
.mp-cover-section {
  flex: 0 0 auto; display: flex; justify-content: center; align-items: center; padding: 10px 0 16px;
  position: relative; z-index: 1;
}
.mp-cover {
  width: 210px; height: 210px; border-radius: 50%;
  background: rgba(255,255,255,0.04) center/cover no-repeat;
  box-shadow: 0 0 60px var(--m-primary-glow), 0 0 120px rgba(46,229,154,0.1), 0 12px 32px rgba(0,0,0,0.5);
  border: 1px solid rgba(255,255,255,0.04);
}
.mp-cover-empty {
  display: flex; align-items: center; justify-content: center;
  height: 100%; font-size: 44px; color: var(--m-text-tertiary);
}
.mp-cover.spinning { animation: cover-spin 24s linear infinite; will-change: transform; }
.mp-lyric-section {
  flex: 1; display: flex; flex-direction: column; justify-content: center;
  padding: 0 28px; min-height: 100px; overflow: hidden;
}
.mp-lyric-prev, .mp-lyric-next {
  text-align: center; font-size: 13px; color: var(--m-text-tertiary); line-height: 1.7;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; min-height: 28px;
  opacity: 0.5;
}
.mp-lyric-curr {
  text-align: center; font-size: 18px; font-weight: 600; color: var(--m-primary);
  line-height: 1.7; padding: 8px 0;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  filter: drop-shadow(0 0 8px var(--m-primary-glow));
}
.mp-lyric-placeholder { text-align: center; color: var(--m-text-secondary); font-size: 14px; }

/* 进度条上方操作 */
.mp-top-actions {
  display: flex; justify-content: space-around; padding: 10px 0 14px; flex-shrink: 0;
}
.mp-top-btn {
  border: none; background: none; cursor: pointer; padding: 8px; color: var(--m-text-secondary);
  flex: 1; display: flex; justify-content: center; transition: color 0.2s, transform 0.2s var(--m-ease-spring);
}
.mp-top-btn:active { color: var(--m-primary); transform: scale(0.9); }
.mp-top-btn.faved { color: var(--m-gold); filter: drop-shadow(0 0 4px var(--m-gold-glow)); }
.mp-top-btn:disabled { opacity: .3; }

.mp-progress {
  height: 4px; margin: 0 28px; background: rgba(255,255,255,.08);
  border-radius: 2px; cursor: pointer; flex-shrink: 0; position: relative;
  transition: height .15s;
}
.mp-progress.seeking { height: 6px; }

.mp-seek-bubble {
  position: absolute; top: -28px; left: 50%; transform: translateX(-50%);
  background: var(--m-gradient-brand); color: #fff; font-size: 11px; font-weight: 600;
  padding: 3px 10px; border-radius: 10px; white-space: nowrap;
  pointer-events: none; z-index: 2;
  animation: bubbleIn .15s ease-out;
  box-shadow: 0 2px 8px rgba(0,0,0,0.4);
}
@keyframes bubbleIn { from { opacity: 0; transform: translateX(-50%) translateY(4px); } }

.mp-progress-fill {
  height: 100%; width: 100%; background: var(--m-gradient-brand); border-radius: 2px;
  position: relative; transform-origin: left;
  will-change: transform; transition: filter .15s;
  box-shadow: 0 0 6px var(--m-primary-glow);
}
.mp-progress.seeking .mp-progress-fill { filter: brightness(1.4); }

.mp-dot {
  position: absolute; right: -6px; top: 50%; transform: translateY(-50%);
  width: 12px; height: 12px; border-radius: 50%;
  background: var(--m-primary); opacity: 0; transition: all .2s;
  box-shadow: 0 0 10px var(--m-primary-glow);
}
.mp-progress:hover .mp-dot { opacity: 1; }
.mp-progress.seeking .mp-dot {
  opacity: 1; width: 18px; height: 18px; right: -9px;
  box-shadow: 0 0 14px var(--m-primary-glow);
}

.mp-time.seeking { color: var(--m-primary); }

.mp-time {
  display: flex; justify-content: space-between; padding: 6px 28px 0;
  font-size: 11px; color: var(--m-text-tertiary); flex-shrink: 0;
}
.mp-ctrls {
  display: flex; align-items: center; justify-content: center; gap: 24px;
  padding: 10px 0 calc(28px + env(safe-area-inset-bottom, 0px)); flex-shrink: 0;
}
.mp-ctrl {
  border: none; background: none; color: var(--m-text-primary); cursor: pointer;
  padding: 8px; display: flex; align-items: center; justify-content: center;
  transition: color 0.2s, transform 0.2s var(--m-ease-spring);
}
.mp-ctrl:active { transform: scale(0.88); }
.mp-ctrl.sm { padding: 8px; color: var(--m-text-secondary); }
.mp-ctrl.sm.mode-active { color: var(--m-primary); }
.mp-ctrl-play {
  color: var(--m-primary); width: 56px; height: 56px;
  background: var(--m-gradient-brand); border-radius: 50%;
  box-shadow: 0 0 20px var(--m-primary-glow);
}
.mp-ctrl-play:active { box-shadow: 0 0 30px var(--m-primary-glow); }

/* 加入歌单弹窗 */
.mp-overlay {
  position: fixed; inset: 0; z-index: 1000;
  background: rgba(0,0,0,.75);
  display: flex; align-items: flex-end; justify-content: center;
}
.mp-picker {
  width: 100%; max-width: 420px; max-height: 65vh;
  background: var(--m-bg-elevated); border-radius: var(--m-radius-lg) var(--m-radius-lg) 0 0;
  padding: 20px 16px calc(20px + env(safe-area-inset-bottom, 0px));
  display: flex; flex-direction: column;
}
.mp-picker-hd {
  display: flex; justify-content: space-between; align-items: center;
  margin-bottom: 14px; font-size: 17px; font-weight: 700; color: var(--m-text-primary);
}
.mp-picker-hd button {
  border: none; background: none; color: var(--m-text-secondary); font-size: 18px; cursor: pointer;
}
.mp-picker-empty { text-align: center; color: var(--m-text-secondary); padding: 32px 0; font-size: 14px; }
.mp-picker-list { overflow-y: auto; }
.mp-picker-item {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 12px; border-radius: var(--m-radius-md);
  background: var(--m-bg-card); margin-bottom: 6px; cursor: pointer;
  transition: background 0.15s;
}
.mp-picker-item:active { background: rgba(46,229,154,0.1); }
.mp-picker-item.adding { opacity: .5; pointer-events: none; }
.mp-picker-name { font-size: 14px; color: var(--m-text-primary); font-weight: 500; }
.mp-picker-count { font-size: 12px; color: var(--m-text-secondary); }
</style>
