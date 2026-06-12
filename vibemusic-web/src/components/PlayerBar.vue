<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { storeToRefs } from 'pinia'
import { API_HOST } from '@/api/request'
import { downloadSong as apiDownload } from '@/api/song'
import { useAudioBackground } from '@/composables/useAudioBackground'
import { usePlayerStore } from '@/stores/player'
import { useFavoriteStore } from '@/stores/favorite'
import LyricsView from '@/components/LyricsView.vue'

const store = usePlayerStore()
const audio = store.audio

// 从 store 解构（使用 storeToRefs 保持响应式）
const {
  queue, currentIdx, currentSong, isPlaying, isTrialSong,
  quality, qualityLabel,
  progress, currentTime, playMode, volume, isMuted
} = storeToRefs(store)
const modeLabels = store.modeLabels
const totalTime = computed(() => store.fmtSec(store.duration))

const showLyrics = ref(false)

// 首次点击时创建 AudioContext + AnalyserNode
document.addEventListener('click', () => store.setupGlobalAnalyser(), { once: true })

// ===== 监听外部播放（HomeView → PlayerBar） =====
function onSongChange(e) {
  const d = e.detail
  const sourceId = d.id || d.sourceId
  const cover = d.coverUrl || ''
  store.addToQueue({
    sourceId: sourceId,
    name: d.title || d.name,
    artist: d.artist || '',
    coverUrl: cover,
    duration: d.duration || 0,
  })
  const newIdx = queue.value.findIndex(s => s.sourceId === sourceId)
  if (newIdx >= 0) currentIdx.value = newIdx
  if (newIdx >= 0 && cover && !queue.value[newIdx].coverUrl) {
    queue.value[newIdx].coverUrl = cover
  }
  if (currentIdx.value >= 0) {
    const song = queue.value[currentIdx.value]
    currentSong.value = {
      id: song.sourceId, title: song.name, artist: song.artist,
      coverUrl: song.coverUrl || '', duration: song.duration || 0,
    }
  }
  isTrialSong.value = d.isTrial || false
}

// HomeView 播放歌曲时设置 Audio 源
function setAudioSrc(url, sourceId, songName, songArtist, coverUrl) {
  if (!url && !sourceId) return
  store.playBySourceId(sourceId, songName, songArtist, coverUrl, 0)
}
window.vibeAudioSetSrc = setAudioSrc

// 歌词进度跳转
function onLyricsSeek(time) { store.seekToTime(time) }

// 后台播放支持
const audioBg = useAudioBackground()

onMounted(() => {
  window.addEventListener('song-change', onSongChange)
  store.restorePlayback()
  audioBg.startWorkerTimer(() => store.savePlaybackTime(), 5000)
  window.addEventListener('beforeunload', () => store.flushSave())
})
onUnmounted(() => {
  window.removeEventListener('song-change', onSongChange)
  audioBg.stopWorkerTimer()
  store.savePlaybackTime()
})

function togglePlay() { store.togglePlay() }
function toggleMute() { store.toggleMute() }
const progressTrack = ref(null)
const isSeeking = ref(false)
const seekPreviewTime = ref('')

function seekAt(clientX) {
  if (!progressTrack.value) return
  const rect = progressTrack.value.getBoundingClientRect()
  const pct = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width))
  store.seekTo(pct)
  seekPreviewTime.value = store.fmtSec(pct * (store.duration || 0))
}

function onTrackMouseDown(e) {
  isSeeking.value = true
  seekAt(e.clientX)
  document.addEventListener('mousemove', onTrackMouseMove)
  document.addEventListener('mouseup', onTrackMouseUp)
}
function onTrackMouseMove(e) { seekAt(e.clientX) }
function onTrackMouseUp() {
  isSeeking.value = false
  document.removeEventListener('mousemove', onTrackMouseMove)
  document.removeEventListener('mouseup', onTrackMouseUp)
}
function toggleMode() { store.toggleMode() }
function prev() { store.prev() }
function next() { store.next() }

// 收藏 & 下载
const favStore = useFavoriteStore()
const downloadingIds = ref(new Set())

favStore.fetchFavIds()

function toggleFav(song) {
  favStore.toggleFav(song)
}

function handleDownload(song) {
  if (downloadingIds.value.has(song.sourceId)) return
  downloadingIds.value.add(song.sourceId)
  apiDownload(song.sourceId, song).then(() => {
    downloadingIds.value.delete(song.sourceId)
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
        <span class="time">{{ store.fmtSec(currentTime) }}</span>
        <div ref="progressTrack" class="progress-track" :class="{ seeking: isSeeking }" @mousedown="onTrackMouseDown">
          <!-- 拖拽时间气泡 -->
          <div v-if="isSeeking" class="seek-bubble">{{ seekPreviewTime }}</div>
          <div class="progress-fill" :style="{ width: progress + '%' }">
            <div class="thumb"></div>
          </div>
        </div>
        <span class="time" :class="{ seeking: isSeeking }">{{ isSeeking ? seekPreviewTime : (totalTime || '0:00') }}</span>
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
            <span v-if="isTrialSong" class="tag-trial">试听</span>
            <span class="tag-quality">{{ qualityLabel }}</span>
            <span class="mini-artist"> - {{ currentSong.artist }}</span>
          </div>
          <button class="func-btn" :class="{ fav: favStore.isFav(currentSong.id) }" @click="toggleFav(currentSong)" title="收藏">
            <svg viewBox="0 0 24 24" width="22" height="22" :fill="favStore.isFav(currentSong.id) ? '#ec4141' : 'none'" stroke="currentColor" stroke-width="2"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>
          </button>
        </div>

        <div class="center-ctrl">
          <button class="ctrl-btn mode-btn" :class="{ active: playMode !== 'list-loop' }" @click="toggleMode" :title="modeLabels[playMode]">
            <!-- 顺序播放 -->
            <svg v-if="playMode === 'list-loop'" viewBox="0 0 1280 1024" width="22" height="22" fill="currentColor"><path d="M1121.8 243.7A373.4 373.4 0 0 1 1231.9 509.5c0 34.2-4.6 68.2-13.7 100.8a42.4 42.4 0 0 1-81.7-22.6 291.9 291.9 0 0 0 10.6-78.2c0-160.5-130.6-291.1-291.1-291.1H461.5v75.1c0 24.1-16.8 33.5-37.3 20.8L243.5 202.2c-20.5-12.7-20.7-33.8-.4-46.9L424.7 38.1c20.2-13.1 36.8-4 36.8 20.1v75.4h394.5c100.4 0 194.8 39.1 265.8 110.1zm-70 573.1c20.5 12.7 20.7 33.8.4 46.8l-181.6 117.3c-20.2 13.1-36.8 4.1-36.8-20V885.4H407.9c-100.4 0-194.8-39.1-265.8-110.1A373.4 373.4 0 0 1 32 509.5c0-72.6 20.7-143.1 60-203.9a42.4 42.4 0 1 1 71.2 46 290 290 0 0 0-46.4 157.8c0 160.6 130.6 291.2 291.1 291.2h425.9v-75.1c0-24.1 16.8-33.5 37.2-20.7l180.8 111.9z"/></svg>
            <!-- 单曲循环 -->
            <svg v-else-if="playMode === 'single'" viewBox="0 0 1024 1024" width="22" height="22" fill="currentColor"><path d="M928 476.8c-19.2 0-32 12.8-32 32v86.4c0 108.8-86.4 198.4-198.4 198.4H201.6l41.6-38.4c6.4-6.4 12.8-16 12.8-25.6 0-19.2-16-35.2-35.2-35.2-9.6 0-22.4 3.2-28.8 9.6l-108.8 99.2c-16 12.8-12.8 35.2 0 48l108.8 96c6.4 6.4 19.2 12.8 28.8 12.8 19.2 0 35.2-12.8 38.4-32 0-12.8-6.4-22.4-16-28.8l-48-44.8h499.2c147.2 0 265.6-118.4 265.6-259.2v-86.4c0-19.2-12.8-32-32-32zM96 556.8c19.2 0 32-12.8 32-32v-89.6c0-112 89.6-201.6 198.4-204.8h496l-41.6 38.4c-6.4 6.4-12.8 16-12.8 25.6 0 19.2 16 35.2 35.2 35.2 9.6 0 22.4-3.2 28.8-9.6l105.6-99.2c16-12.8 12.8-35.2 0-48l-108.8-96c-6.4-6.4-19.2-12.8-28.8-12.8-19.2 0-35.2 12.8-38.4 32 0 12.8 6.4 22.4 16 28.8l48 44.8H329.6C182.4 169.6 64 288 64 438.4v86.4c0 19.2 12.8 32 32 32z"/><path d="M544 672V352h-48L416 409.6l16 41.6 60.8-41.6V672z"/></svg>
            <!-- 随机播放 -->
            <svg v-else-if="playMode === 'shuffle'" viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><polyline points="16 3 21 3 21 8"/><line x1="4" y1="20" x2="21" y2="3"/><polyline points="21 16 21 21 16 21"/><line x1="15" y1="15" x2="21" y2="21"/><line x1="4" y1="4" x2="9" y2="9"/></svg>
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
            <div class="vol-bar" @click="e => store.volume = Math.round((e.offsetX / e.target.offsetWidth) * 100)">
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
            <button v-if="queue.length" class="panel-clear-btn" @click="store.clearQueue()">清空</button>
            <button class="panel-close" @click="showPlaylist = false">✕</button>
          </div>
        </div>
        <div class="panel-list">
          <div
            v-for="(song, idx) in queue"
            :key="song.sourceId + '_' + idx"
            class="panel-item"
            :class="{ current: idx === currentIdx }"
            @click="store.playIndex(idx)"
          >
            <div class="pi-cover" :style="song.coverUrl ? { backgroundImage: 'url(' + song.coverUrl + '?param=60y60)' } : {}">
              <span v-if="!song.coverUrl">♪</span>
            </div>
            <div class="pi-info">
              <span class="pi-name" :class="{ hl: idx === currentIdx }">{{ song.name }}</span>
              <span class="pi-artist">{{ song.artist }}</span>
            </div>
            <button class="pi-fav" :class="{ faved: favStore.isFav(song.sourceId) }" @click.stop="toggleFav(song)" :title="favStore.isFav(song.sourceId) ? '取消收藏' : '收藏'">⭐</button>
            <button class="pi-dl" @click.stop="handleDownload(song)" :title="downloadingIds.has(song.sourceId) ? '下载中' : '下载'">{{ downloadingIds.has(song.sourceId) ? '⏳' : '⬇' }}</button>
            <button class="pi-remove" @click.stop="store.removeFromQueue(idx)" title="移除">✕</button>
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
.progress-track { flex: 1; height: 4px; background: #e8e8e8; border-radius: 2px; cursor: pointer; position: relative; transition: height .15s; }
.progress-track:hover { height: 6px; }
.progress-track.seeking { height: 6px; }

/* 拖拽时间气泡 */
.seek-bubble {
  position: absolute; top: -26px; left: 50%; transform: translateX(-50%);
  background: #31c27c; color: #fff; font-size: 11px; font-weight: 600;
  padding: 2px 8px; border-radius: 10px; white-space: nowrap;
  pointer-events: none; z-index: 2;
  animation: bubbleIn .15s ease-out;
}
@keyframes bubbleIn { from { opacity: 0; transform: translateX(-50%) translateY(4px); } }

.progress-fill { height: 100%; background: #31c27c; border-radius: 2px; position: relative; transition: filter .15s; }
.progress-track.seeking .progress-fill { filter: brightness(1.2); }

.thumb { position: absolute; right: -6px; top: 50%; transform: translateY(-50%); width: 12px; height: 12px; background: #31c27c; border-radius: 50%; opacity: 0; transition: all .2s; }
.progress-track:hover .thumb { opacity: 1; }
.progress-track.seeking .thumb {
  opacity: 1;
  width: 14px; height: 14px; right: -7px;
  box-shadow: 0 0 6px rgba(49,194,124,.4);
}

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
.tag-trial { display: inline-block; font-size: 9px; padding: 0 4px; border-radius: 3px; margin-left: 4px; background: #fff1f0; color: #cf1322; border: 1px solid #ffa39e; vertical-align: middle; line-height: 16px; }
.tag-quality { display: inline-block; font-size: 9px; padding: 0 5px; border-radius: 3px; margin-left: 4px; background: rgba(49,194,124,0.12); color: #31c27c; font-weight: 500; vertical-align: middle; line-height: 16px; }
.func-btn { width: 40px; height: 40px; border: none; background: none; border-radius: 50%; color: #999; cursor: pointer; display: flex; align-items: center; justify-content: center; transition: .15s; }
.func-btn:hover { background: rgba(0,0,0,.05); color: #ec4141; }
.func-btn.fav { color: #ec4141; }

.center-ctrl { display: flex; align-items: center; gap: 12px; }
.ctrl-btn { border: none; background: none; color: #555; cursor: pointer; display: flex; align-items: center; justify-content: center; transition: .15s; }
.ctrl-btn:hover { color: #1a1a1a; }
.ctrl-btn.mode-btn { width: 40px; height: 40px; border-radius: 50%; color: #999; }
.ctrl-btn.mode-btn:hover { color: #31c27c; background: rgba(49,194,124,0.12); }
.ctrl-btn.mode-btn.active { color: #31c27c; }
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
