<script setup>
import { API_HOST } from '@/api/request'
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { toggleFavorite, getFavoriteIds } from '@/api/song'

const route = useRoute()
const router = useRouter()
const audio = window.vibeAudio

// 收藏
const favIds = ref(new Set())
const isFaved = ref(false)
getFavoriteIds().then(r => { if (r.data) favIds.value = new Set(r.data) }).catch(() => {})

function toggleFav(e) {
  e.stopPropagation()
  const id = currentSong.value.id
  if (!id) return
  const was = favIds.value.has(id)
  favIds.value[was ? 'delete' : 'add'](id)
  isFaved.value = !was
  toggleFavorite(id, currentSong.value.title, currentSong.value.artist, currentSong.value.coverUrl || '').catch(() => {
    favIds.value[was ? 'add' : 'delete'](id)
    isFaved.value = was
  })
}

// 搜索页贴底，播放页隐藏（由 MobileShell 控制），其他页在 TabBar 上方
const isPlayer = computed(() => route.path.startsWith('/m/player'))
const isSearch = computed(() => route.path.startsWith('/m/search'))
const bottomOffset = computed(() => isSearch.value ? 0 : 56)
const currentSong = ref({ id: '', title: '', artist: '', coverUrl: '' })
const isPlaying = ref(!audio.paused)
const progress = ref(0)

function fmtSec(s) {
  if (!s || !isFinite(s)) return '0:00'
  const m = Math.floor(s / 60), sec = Math.floor(s % 60)
  return m + ':' + String(sec).padStart(2, '0')
}

onMounted(() => {
  window.addEventListener('song-change', e => {
    const d = e.detail
    const song = {
      id: d.sourceId || '',
      title: d.title || '',
      artist: d.artist || '',
      coverUrl: d.coverUrl || '',
    }
    currentSong.value = song
    // 同步写入 localStorage
    localStorage.setItem('vibe_current_song', JSON.stringify(song))
    // 同步收藏状态
    isFaved.value = favIds.value.has(song.id)
    // 管理播放队列：去重 + 追加
    try {
      const q = JSON.parse(localStorage.getItem('vibe_queue') || '[]')
      const sourceId = d.sourceId || d.id
      // 去重：已存在则移除旧位置
      const existIdx = q.findIndex(s => s.sourceId === sourceId)
      if (existIdx >= 0) q.splice(existIdx, 1)
      // 追加到队尾
      q.push({ sourceId, name: d.title, artist: d.artist, coverUrl: d.coverUrl || '', duration: d.duration || 0 })
      localStorage.setItem('vibe_queue', JSON.stringify(q))
      localStorage.setItem('vibe_queue_idx', String(q.length - 1))
    } catch {}
  })

  audio.addEventListener('play', () => { isPlaying.value = true })
  audio.addEventListener('pause', () => { isPlaying.value = false })
  audio.addEventListener('ended', () => { isPlaying.value = false })
  audio.addEventListener('timeupdate', () => {
    if (audio.duration) progress.value = (audio.currentTime / audio.duration) * 100
  })

  // 恢复已播放状态
  const saved = localStorage.getItem('vibe_current_song')
  if (saved) {
    try {
      const s = JSON.parse(saved)
      if (s.id) {
        currentSong.value = { id: s.id, title: s.title || '', artist: s.artist || '', coverUrl: s.coverUrl || '' }
        isFaved.value = favIds.value.has(s.id)
        // 恢复音频源（刷新后 audio 是新的，需要重新设 src）
        if (!audio.src) {
          audio.src = `${API_HOST}/api/songs/stream?sourceId=${encodeURIComponent(s.id)}`
          const t = parseFloat(localStorage.getItem('vibe_playback_time') || '0')
          if (t > 0) audio.currentTime = t
        }
      }
    } catch {}
  }
})

function goToPlayer() {
  router.push('/m/player')
}

function togglePlayPause(e) {
  e.stopPropagation()
  if (audio.src) {
    if (audio.paused) audio.play().catch(() => {})
    else audio.pause()
  }
}

function switchQueue(delta) {
  try {
    const q = JSON.parse(localStorage.getItem('vibe_queue') || '[]')
    if (!q.length) return
    let idx = parseInt(localStorage.getItem('vibe_queue_idx') || '-1')
    idx = delta > 0
      ? (idx >= q.length - 1 ? 0 : idx + 1)
      : (idx <= 0 ? q.length - 1 : idx - 1)
    localStorage.setItem('vibe_queue_idx', String(idx))
    const s = q[idx]
    const baseURL = API_HOST
    audio.src = `${baseURL}/api/songs/stream?sourceId=${encodeURIComponent(s.sourceId)}`
    audio.play().catch(() => {})
    const song = { sourceId: s.sourceId, title: s.name, artist: s.artist, coverUrl: s.coverUrl || '' }
    localStorage.setItem('vibe_current_song', JSON.stringify({ id: s.sourceId, title: s.name, artist: s.artist, coverUrl: s.coverUrl || '' }))
    window.dispatchEvent(new CustomEvent('song-change', { detail: song }))
  } catch {}
}

function clickPrev(e) { e.stopPropagation(); switchQueue(-1) }
function clickNext(e) { e.stopPropagation(); switchQueue(1) }
function openQueue(e) { e.stopPropagation(); window._openQueuePopup?.() }

// 只有有歌在播放时才显示
const visible = computed(() => !!currentSong.value.id)
</script>

<template>
  <div v-if="visible" class="mbp" :style="{ bottom: bottomOffset + 'px' }" @click="goToPlayer">
    <div class="mbp-progress" :style="{ width: progress + '%' }"></div>
    <div class="mbp-cover" :style="currentSong.coverUrl ? { backgroundImage: `url(${currentSong.coverUrl}?param=80y80)` } : {}"></div>
    <div class="mbp-info" @click="goToPlayer">
      <div class="mbp-title">{{ currentSong.title }}</div>
      <div class="mbp-artist">{{ currentSong.artist }}</div>
    </div>
    <!-- 收藏按钮 -->
    <button class="mbp-btn sm" :class="{ faved: isFaved }" @click.stop="toggleFav" title="收藏">
      <svg viewBox="0 0 24 24" width="18" height="18" :fill="isFaved ? '#ffc107' : 'none'" stroke="currentColor" stroke-width="2"><polygon points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26"/></svg>
    </button>
    <!-- 右侧图标区（均匀分布） -->
    <div class="mbp-icons">
      <button class="mbp-btn sm" @click.stop="clickPrev" title="上一首">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><path d="M6 6h2v12H6zm3.5 6l8.5 6V6z"/></svg>
      </button>
      <button class="mbp-btn" @click.stop="togglePlayPause">
        <svg v-if="isPlaying" viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/></svg>
        <svg v-else viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><polygon points="6,4 20,12 6,20"/></svg>
      </button>
      <button class="mbp-btn sm" @click.stop="clickNext" title="下一首">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/></svg>
      </button>
      <button class="mbp-btn sm" @click.stop="openQueue" title="播放列表">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/></svg>
      </button>
    </div>
  </div>
</template>

<style scoped>
.mbp { 
  position: fixed; left: 0; right: 0; z-index: 100;
  height: 60px; display: flex; align-items: center; gap: 8px;
  background: rgba(20, 20, 20, 0.95); backdrop-filter: blur(10px);
  border-top: 1px solid rgba(255,255,255,0.06);
  padding: 0 8px; cursor: pointer;
  padding-bottom: env(safe-area-inset-bottom, 0px);
}
.mbp-icons { display: flex; align-items: center; justify-content: space-evenly; flex: 0 0 auto; min-width: 140px; }
.mbp-progress {
  position: absolute; top: 0; left: 0; height: 2px;
  background: #31c27c; border-radius: 0 2px 2px 0;
  transition: width .3s linear;
}
.mbp-cover {
  width: 42px; height: 42px; border-radius: 8px; flex-shrink: 0;
  background: rgba(255,255,255,0.06) center/cover no-repeat;
}
.mbp-info { flex: 1; min-width: 0; }
.mbp-title { font-size: 14px; color: #e0e0e0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mbp-artist { font-size: 12px; color: #888; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mbp-btn { flex-shrink: 0; width: 40px; height: 40px; border: none; background: none; color: #e0e0e0; cursor: pointer; display: flex; align-items: center; justify-content: center; }
.mbp-btn.sm { width: 32px; height: 32px; color: #888; }
.mbp-btn.faved { color: #ffc107; }
</style>
