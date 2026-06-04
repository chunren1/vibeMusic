<script setup>
// 喜欢的歌曲列表（后续接入 API）
import { ref } from 'vue'

const likedSongs = ref([
  { id: 1, title: '晴天', artist: '周杰伦', album: '叶惠美', duration: '4:29', color: '#e84c3d' },
  { id: 2, title: '孤勇者', artist: '陈奕迅', album: '孤勇者', duration: '4:16', color: '#3498db' },
  { id: 3, title: '起风了', artist: '买辣椒也用券', album: '起风了', duration: '5:08', color: '#2ecc71' },
])
</script>

<template>
  <div class="likes-page">
    <h2 class="page-title">❤ 我喜欢</h2>
    <p class="subtitle">{{ likedSongs.length }} 首歌曲</p>

    <div class="song-list">
      <div v-for="(song, idx) in likedSongs" :key="song.id" class="song-row">
        <span class="row-index">{{ idx + 1 }}</span>
        <div class="row-cover">
          <div class="cover-dot" :style="{ background: song.color }">♪</div>
        </div>
        <div class="row-info">
          <span class="row-title">{{ song.title }}</span>
          <span class="row-meta">{{ song.artist }} · {{ song.album }}</span>
        </div>
        <span class="row-time">{{ song.duration }}</span>
      </div>
    </div>

    <div v-if="likedSongs.length === 0" class="empty">
      <p>还没有喜欢的歌曲</p>
      <p class="hint">去推荐页发现好音乐吧</p>
    </div>
  </div>
</template>

<style scoped>
.likes-page { padding: 28px; }
.page-title { font-size: 24px; font-weight: 700; color: #fff; margin-bottom: 4px; }
.subtitle { font-size: 13px; color: #666; margin-bottom: 24px; }

.song-list { display: flex; flex-direction: column; }
.song-row {
  display: flex; align-items: center; gap: 14px;
  padding: 10px 12px; border-radius: 8px; cursor: pointer; transition: .15s;
}
.song-row:nth-child(odd) { background: rgba(255,255,255,.015); }
.song-row:hover { background: rgba(255,255,255,.05); }

.row-index { width: 28px; text-align: center; font-size: 13px; color: #555; }
.row-cover { width: 40px; height: 40px; border-radius: 6px; overflow: hidden; }
.cover-dot {
  width: 100%; height: 100%;
  display: flex; align-items: center; justify-content: center;
  font-size: 16px; color: rgba(255,255,255,.5);
}
.row-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.row-title { font-size: 14px; color: #ddd; }
.row-meta {
  font-size: 12px; color: #666;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.row-time { font-size: 12px; color: #555; }

.empty { text-align: center; padding: 80px 0; color: #666; }
.hint { font-size: 13px; margin-top: 8px; }
</style>
