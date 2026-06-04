<script setup>
import { ref, onMounted } from 'vue'
import TopBar from '@/components/TopBar.vue'
import { getFavorites, playSong as apiPlaySong } from '@/api/song'

const favorites = ref([])
const currentPlayId = ref(null)

const audio = window.vibeAudio || new Audio()
window.vibeAudio = audio

function formatDuration(s) {
  if (!s) return ''
  const m = Math.floor(s / 60)
  return m + ':' + String(s % 60).padStart(2, '0')
}

function play(fav) {
  currentPlayId.value = fav.sourceId
  apiPlaySong(fav.sourceId, fav.songName, fav.artist).then(res => {
    const url = res.data?.url
    if (!url) return
    audio.src = url
    audio.play().catch(() => {})
  }).catch(() => {})
}

onMounted(() => {
  getFavorites().then(res => {
    favorites.value = res.data || []
  }).catch(() => {})
})
</script>

<template>
  <TopBar />
  <div class="likes-page">
    <h2 class="page-title">⭐ 我的收藏</h2>
    <p class="subtitle">{{ favorites.length }} 首歌曲</p>

    <div v-if="favorites.length > 0" class="song-list">
      <div
        v-for="(fav, idx) in favorites"
        :key="fav.sourceId"
        class="song-row"
        :class="{ active: currentPlayId === fav.sourceId }"
        @click="play(fav)"
      >
        <span class="row-index">
          <span v-if="currentPlayId === fav.sourceId">▶</span>
          <span v-else>{{ idx + 1 }}</span>
        </span>
        <div class="row-cover">♪</div>
        <div class="row-info">
          <span class="row-title" :class="{ hl: currentPlayId === fav.sourceId }">{{ fav.songName }}</span>
          <span class="row-meta">{{ fav.artist }}</span>
        </div>
      </div>
    </div>

    <div v-else class="empty">
      <p>还没有收藏歌曲</p>
      <p class="hint">去主页搜索喜欢的音乐吧</p>
    </div>
  </div>
</template>

<style scoped>
.likes-page { padding: 28px; }
.page-title { font-size: 24px; font-weight: 700; color: #333; margin-bottom: 4px; }
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

.empty { text-align: center; padding: 80px 0; color: #999; }
.hint { font-size: 13px; margin-top: 8px; }
</style>
