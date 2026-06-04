<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue'

const currentSong = ref({
  id: '',
  title: '未播放',
  artist: '',
  coverUrl: '',
  duration: 0,
})

const isPlaying = ref(false)
const progress = ref(0)
const currentTime = ref('0:00')
const totalTime = ref('0:00')
const volume = ref(70)
const isMuted = ref(false)
const isLiked = ref(false)

// 共享全局 Audio
const audio = window.vibeAudio || new Audio()
window.vibeAudio = audio

function formatDuration(s) {
  if (!s) return ''
  const m = Math.floor(s / 60)
  const sec = s % 60
  return m + ':' + String(sec).padStart(2, '0')
}

// 监听歌曲变化
function onSongChange(e) {
  currentSong.value = {
    id: e.detail.id || '',
    title: e.detail.title || '未播放',
    artist: e.detail.artist || '',
    coverUrl: e.detail.coverUrl || '',
    duration: e.detail.duration || 0,
  }
  totalTime.value = formatDuration(e.detail.duration || 0)
}

// Audio 事件
audio.addEventListener('timeupdate', () => {
  if (audio.duration) {
    progress.value = (audio.currentTime / audio.duration) * 100
    currentTime.value = formatDuration(Math.floor(audio.currentTime))
  }
})
audio.addEventListener('play', () => { isPlaying.value = true })
audio.addEventListener('pause', () => { isPlaying.value = false })
audio.addEventListener('ended', () => { isPlaying.value = false })

onMounted(() => {
  window.addEventListener('song-change', onSongChange)
})
onUnmounted(() => {
  window.removeEventListener('song-change', onSongChange)
})

function togglePlay() {
  if (isPlaying.value) {
    audio.pause()
  } else {
    audio.play().catch(() => {})
  }
}
function prev() { audio.currentTime = Math.max(0, audio.currentTime - 10) }
function next() { audio.currentTime = audio.currentTime + 10 }
function toggleMute() {
  isMuted.value = !isMuted.value
  audio.muted = isMuted.value
}
function toggleLike() { isLiked.value = !isLiked.value }

// 音量
watch(volume, v => { audio.volume = v / 100 })

// 播放列表
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
        <button class="ctrl-btn" @click="prev" title="后退10s">⏮</button>
        <button class="ctrl-btn play-btn" @click="togglePlay">
          {{ isPlaying ? '⏸' : '▶' }}
        </button>
        <button class="ctrl-btn" @click="next" title="前进10s">⏭</button>
      </div>
      <div class="progress-area">
        <span class="time">{{ currentTime }}</span>
        <div class="progress-bar" @click="e => audio.currentTime = (e.offsetX / e.target.offsetWidth) * audio.duration">
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
      <button class="like-btn" :class="{ liked: isLiked }" @click="toggleLike">
        {{ isLiked ? '❤️' : '🤍' }}
      </button>
      <button class="panel-btn" :class="{ active: showPlaylist }" @click="togglePlaylist" title="播放列表">
        📋
      </button>
    </div>
  </div>
</template>

<style scoped>
.player-bar {
  position: fixed; bottom: 0; left: 0; right: 0; height: 80px;
  background: #191b1f; border-top: 1px solid #2a2a2a;
  display: flex; align-items: center; gap: 20px;
  padding: 0 24px; z-index: 100;
}
.song-info {
  display: flex; align-items: center; gap: 14px; width: 240px; flex-shrink: 0;
}
.mini-cover {
  width: 54px; height: 54px; border-radius: 8px;
  background: linear-gradient(135deg, #2a2a3a, #1e2024);
  display: flex; align-items: center; justify-content: center;
  font-size: 22px; color: #31c27c; flex-shrink: 0;
  background-size: cover; background-position: center;
}
.info-text { flex: 1; min-width: 0; }
.song-title { font-size: 15px; color: #ddd; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.song-artist { font-size: 13px; color: #666; margin-top: 3px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.player-controls { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 8px; }
.control-btns { display: flex; align-items: center; gap: 24px; }
.ctrl-btn { background: none; border: none; color: #ccc; font-size: 22px; cursor: pointer; }
.ctrl-btn:hover { color: #fff; }
.play-btn {
  width: 44px; height: 44px; border-radius: 50%;
  background: #31c27c; color: #fff; font-size: 16px;
  display: flex; align-items: center; justify-content: center;
}
.play-btn:hover { background: #28a86b; }
.progress-area { display: flex; align-items: center; gap: 12px; width: 100%; max-width: 520px; }
.time { font-size: 13px; color: #555; width: 40px; text-align: center; }
.progress-bar {
  flex: 1; height: 5px; background: #333; border-radius: 3px;
  position: relative; cursor: pointer;
}
.progress-fill { height: 100%; background: #31c27c; border-radius: 3px; transition: width .2s; }

.right-area { display: flex; align-items: center; gap: 14px; flex-shrink: 0; }
.like-btn { background: none; border: none; font-size: 22px; cursor: pointer; padding: 6px; border-radius: 6px; }
.like-btn:hover { transform: scale(1.15); }
.like-btn.liked { animation: likeBeat .3s ease; }
@keyframes likeBeat { 0% { transform: scale(1); } 50% { transform: scale(1.3); } 100% { transform: scale(1); } }

.panel-btn {
  position: relative; background: none; border: none;
  color: #999; font-size: 22px; cursor: pointer; padding: 6px; border-radius: 6px;
}
.panel-btn:hover { color: #fff; background: rgba(255,255,255,.06); }
.panel-btn.active { color: #31c27c; }

.volume-area { display: flex; align-items: center; gap: 10px; }
.mute-btn { background: none; border: none; font-size: 20px; cursor: pointer; color: #ccc; }
.volume-bar { width: 100px; height: 5px; background: #333; border-radius: 3px; cursor: pointer; }
.volume-fill { height: 100%; background: #ccc; border-radius: 3px; }
</style>
