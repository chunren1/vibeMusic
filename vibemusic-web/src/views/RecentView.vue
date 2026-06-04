<script setup>
import { ref, onMounted } from 'vue'
import TopBar from '@/components/TopBar.vue'
import { getPlayHistory, playSong as apiPlaySong } from '@/api/song'

const recentSongs = ref([])
const currentPlayId = ref(null)

const audio = window.vibeAudio || new Audio()
window.vibeAudio = audio

function formatTime(dt) {
  if (!dt) return ''
  const d = new Date(dt)
  const now = new Date()
  const diff = now - d
  if (diff < 60000) return '刚刚'
  if (diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前'
  if (diff < 86400000) return Math.floor(diff / 3600000) + ' 小时前'
  return d.toLocaleDateString() + ' ' + d.toLocaleTimeString().substring(0, 5)
}

function play(item) {
  currentPlayId.value = item.sourceId
  apiPlaySong(item.sourceId, item.songName, item.artist).then(res => {
    const url = res.data?.url
    if (!url) return
    audio.src = url
    audio.play().catch(() => {})
  }).catch(() => {})
}

function clearHistory() {
  recentSongs.value = []
}

onMounted(() => {
  getPlayHistory().then(res => {
    recentSongs.value = res.data || []
  }).catch(() => {})
})
</script>

<template>
  <TopBar />
  <div class="recent-page">
    <div class="page-header">
      <h2 class="page-title">🕐 最近播放</h2>
      <button v-if="recentSongs.length" class="clear-btn" @click="clearHistory">清空</button>
    </div>
    <p class="subtitle">{{ recentSongs.length }} 首歌曲</p>

    <div v-if="recentSongs.length > 0" class="song-list">
      <div
        v-for="(item, idx) in recentSongs"
        :key="item.sourceId + '_' + idx"
        class="song-row"
        :class="{ active: currentPlayId === item.sourceId }"
        @click="play(item)"
      >
        <span class="row-index">
          <span v-if="currentPlayId === item.sourceId">▶</span>
          <span v-else>{{ idx + 1 }}</span>
        </span>
        <div class="row-cover">♪</div>
        <div class="row-info">
          <span class="row-title" :class="{ hl: currentPlayId === item.sourceId }">{{ item.songName }}</span>
          <span class="row-meta">{{ item.artist }}</span>
        </div>
        <span class="row-time">{{ formatTime(item.playedAt) }}</span>
      </div>
    </div>

    <div v-else class="empty">
      <p>暂无播放记录</p>
      <p class="hint">去主页听听音乐吧</p>
    </div>
  </div>
</template>

<style scoped>
.recent-page { padding: 28px; }
.page-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 4px; }
.page-title { font-size: 24px; font-weight: 700; color: #333; }
.clear-btn {
  padding: 6px 16px; border: 1px solid #ccc; border-radius: 16px;
  background: transparent; color: #999; font-size: 12px; cursor: pointer;
}
.clear-btn:hover { border-color: #e84c3d; color: #e84c3d; }
.subtitle { font-size: 13px; color: #999; margin-bottom: 24px; }

.song-list { display: flex; flex-direction: column; }
.song-row {
  display: flex; align-items: center; gap: 14px;
  padding: 10px 12px; border-radius: 8px; cursor: pointer; transition: .15s;
}
.song-row:nth-child(odd) { background: rgba(0,0,0,.02); }
.song-row:hover { background: rgba(0,0,0,.05); }
.song-row.active { background: rgba(49,194,124,.1); }

.row-index { width: 28px; text-align: center; font-size: 13px; color: #999; }
.row-cover {
  width: 40px; height: 40px; border-radius: 6px;
  background: #e0e0e0; display: flex; align-items: center; justify-content: center;
  font-size: 16px; color: #999;
}
.row-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.row-title { font-size: 14px; color: #333; }
.row-title.hl { color: #31c27c; }
.row-meta { font-size: 12px; color: #999; }
.row-time { font-size: 12px; color: #999; }

.empty { text-align: center; padding: 80px 0; color: #999; }
.hint { font-size: 13px; margin-top: 8px; }
</style>
