<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { storeToRefs } from 'pinia'
import { API_HOST } from '@/api/request'
import { downloadSong as apiDownload } from '@/api/song'
import { useAudioBackground } from '@/composables/useAudioBackground'
import { useClickOutside } from '@/composables/useClickOutside'
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
const playlistPanelRef = ref(null)
const playlistToggleRef = ref(null)
useClickOutside(playlistPanelRef, () => { showPlaylist.value = false }, { exclude: [playlistToggleRef] })
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
            <div class="mini-name-wrap">
              <span class="mini-name">{{ currentSong.title }}</span>
              <span v-if="isTrialSong" class="tag-trial">试听</span>
              <span class="tag-quality">
                <SvgIcon name="quality" size="12" color="#31c27c" />
                {{ qualityLabel }}
              </span>
            </div>
            <div class="mini-artist">{{ currentSong.artist }}</div>
          </div>
          <button class="func-btn" :class="{ fav: favStore.isFav(currentSong.id) }" @click="toggleFav(currentSong)" title="收藏">
            <SvgIcon :name="favStore.isFav(currentSong.id) ? 'heart-fill' : 'heart'" :color="favStore.isFav(currentSong.id) ? '#ff4757' : 'currentColor'" size="22" />
          </button>
        </div>

        <div class="center-ctrl">
          <button class="ctrl-btn mode-btn" :class="{ active: playMode !== 'list-loop' }" @click="toggleMode" :title="modeLabels[playMode]">
            <SvgIcon :name="playMode" size="22" />
          </button>
          <button class="ctrl-btn skip" @click="prev" title="上一首">
            <SvgIcon name="previous" size="24" />
          </button>
          <button class="ctrl-btn main" @click="togglePlay" title="播放/暂停">
            <SvgIcon :name="isPlaying ? 'pause' : 'play'" size="24" color="#fff" />
          </button>
          <button class="ctrl-btn skip" @click="next" title="下一首">
            <SvgIcon name="next" size="24" />
          </button>
        </div>

        <div class="right-actions">
          <button class="act-icon" :class="{ downloading: downloadingIds.has(currentSong.id) }" @click="handleDownload(currentSong)" title="下载">
            <SvgIcon name="download" size="20" />
          </button>
          <div class="vol-group">
            <button class="act-icon" @click="toggleMute" title="音量">
              <SvgIcon :name="isMuted || volume === 0 ? 'volume-mute' : 'volume'" size="20" />
            </button>
            <div class="vol-bar" @click="e => store.volume = Math.round((e.offsetX / e.target.offsetWidth) * 100)">
              <div class="vol-fill" :style="{ width: (isMuted ? 0 : volume) + '%' }"></div>
            </div>
          </div>
          <button ref="playlistToggleRef" class="act-icon" :class="{ active: showPlaylist }" @click="togglePlaylist" title="播放列表">
            <SvgIcon name="playlist" size="20" />
          </button>
        </div>
      </div>
    </footer>

    <!-- 播放列表面板 -->
    <Transition name="panel">
      <div v-if="showPlaylist" ref="playlistPanelRef" class="playlist-panel">
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
            <button class="pi-fav" :class="{ faved: favStore.isFav(song.sourceId) }" @click.stop="toggleFav(song)" :title="favStore.isFav(song.sourceId) ? '取消收藏' : '收藏'">
              <SvgIcon :name="favStore.isFav(song.sourceId) ? 'heart-fill' : 'heart'" :color="favStore.isFav(song.sourceId) ? '#ff4757' : 'currentColor'" size="14" />
            </button>
            <button class="pi-dl" @click.stop="handleDownload(song)" :title="downloadingIds.has(song.sourceId) ? '下载中' : '下载'">
              <SvgIcon name="download" size="12" />
            </button>
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

.left-info { display: flex; align-items: center; gap: 14px; min-width: 240px; }
.mini-cover {
  width: 44px; height: 44px; border-radius: 6px; flex-shrink: 0;
  background: #eee; display: flex; align-items: center; justify-content: center;
  font-size: 18px; color: #31c27c; cursor: pointer;
  background-size: cover; background-position: center;
  transition: transform .15s;
}
.mini-cover:hover { transform: scale(1.08); }
.mini-cover.active { box-shadow: 0 0 0 2px rgba(49,194,124,0.5); }
.mini-song { display: flex; flex-direction: column; gap: 2px; min-width: 0; flex: 1; max-width: 160px; }
.mini-name-wrap { display: flex; align-items: center; gap: 6px; min-width: 0; }
.mini-name { color: #1a1a1a; font-weight: 500; font-size: 14px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; flex-shrink: 1; min-width: 0; }
.mini-artist { color: #999; font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.tag-trial { display: inline-block; font-size: 9px; padding: 0 4px; border-radius: 3px; background: #fff1f0; color: #cf1322; border: 1px solid #ffa39e; vertical-align: middle; line-height: 16px; white-space: nowrap; flex-shrink: 0; }
.tag-quality { display: inline-block; font-size: 9px; padding: 0 5px; border-radius: 3px; background: rgba(49,194,124,0.12); color: #31c27c; font-weight: 500; vertical-align: middle; line-height: 16px; white-space: nowrap; flex-shrink: 0; }
.func-btn { width: 40px; height: 40px; border: none; background: none; border-radius: 50%; color: #999; cursor: pointer; display: flex; align-items: center; justify-content: center; transition: .15s; }
.func-btn:hover { background: rgba(0,0,0,.05); color: #ec4141; }
.func-btn.fav { color: #ec4141; }

.center-ctrl { display: flex; align-items: center; gap: 16px; }
.ctrl-btn { border: none; background: none; color: #555; cursor: pointer; display: flex; align-items: center; justify-content: center; transition: .15s; }
.ctrl-btn:hover { color: #1a1a1a; }
.ctrl-btn.mode-btn { width: 40px; height: 40px; border-radius: 50%; color: #999; }
.ctrl-btn.mode-btn:hover { color: #31c27c; background: rgba(49,194,124,0.12); }
.ctrl-btn.mode-btn.active { color: #31c27c; }
.ctrl-btn.skip { opacity: 0.75; }
.ctrl-btn.skip:hover { opacity: 1; }
.ctrl-btn.main { width: 64px; height: 36px; border-radius: 10px; background: #31c27c; color: #fff; }
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
