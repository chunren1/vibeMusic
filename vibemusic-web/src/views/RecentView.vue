<script setup>
import { ref } from 'vue'

const recentSongs = ref([
  { id: 1, title: '晴天', artist: '周杰伦', time: '今天 18:30', color: '#e84c3d' },
  { id: 2, title: '孤勇者', artist: '陈奕迅', time: '今天 15:20', color: '#3498db' },
  { id: 3, title: '起风了', artist: '买辣椒也用券', time: '昨天 22:15', color: '#2ecc71' },
  { id: 4, title: '错位时空', artist: '艾辰', time: '昨天 18:00', color: '#f39c12' },
  { id: 5, title: '若月亮没来', artist: '黄绮珊', time: '前天 14:30', color: '#9b59b6' },
])

function clearHistory() {
  recentSongs.value = []
}

function playSong(song) {
  console.log('播放:', song.title)
}
</script>

<template>
  <div class="recent-page">
    <div class="header">
      <h2 class="page-title">🕐 最近播放</h2>
      <button v-if="recentSongs.length" class="clear-btn" @click="clearHistory">清空</button>
    </div>
    <p class="subtitle">{{ recentSongs.length }} 首歌曲</p>

    <div class="song-list">
      <div
        v-for="(song, idx) in recentSongs"
        :key="song.id"
        class="song-row"
        @dblclick="playSong(song)"
      >
        <span class="row-index">{{ idx + 1 }}</span>
        <div class="row-cover">
          <div class="cover-dot" :style="{ background: song.color }">♪</div>
        </div>
        <div class="row-info">
          <span class="row-title">{{ song.title }}</span>
          <span class="row-artist">{{ song.artist }}</span>
        </div>
        <span class="row-time">{{ song.time }}</span>
      </div>
    </div>

    <div v-if="recentSongs.length === 0" class="empty">
      <p>暂无播放记录</p>
      <p class="hint">去推荐页听听歌吧</p>
    </div>
  </div>
</template>

<style scoped>
.recent-page { padding: 28px; }
.header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 4px; }
.page-title { font-size: 24px; font-weight: 700; color: #fff; }
.clear-btn {
  padding: 6px 16px; border: 1px solid #444; border-radius: 16px;
  background: transparent; color: #888; font-size: 12px; cursor: pointer;
}
.clear-btn:hover { border-color: #ec4141; color: #ec4141; }
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
.row-artist {
  font-size: 12px; color: #666;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.row-time { font-size: 12px; color: #555; white-space: nowrap; }

.empty { text-align: center; padding: 80px 0; color: #666; }
.hint { font-size: 13px; margin-top: 8px; }
</style>
