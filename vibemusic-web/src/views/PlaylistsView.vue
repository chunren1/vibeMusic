<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import TopBar from '@/components/TopBar.vue'

const router = useRouter()

const playlists = ref([
  { id: 1, name: '华语热门精选', count: 56, color: '#e84c3d' },
  { id: 2, name: '治愈系纯音乐', count: 38, color: '#3498db' },
  { id: 3, name: '说唱新世代', count: 29, color: '#2ecc71' },
  { id: 4, name: '怀旧金曲', count: 64, color: '#f39c12' },
  { id: 5, name: '民谣在路上', count: 27, color: '#9b59b6' },
  { id: 6, name: '电竞燃曲BGM', count: 33, color: '#1abc9c' },
  { id: 7, name: '周杰伦全集', count: 88, color: '#e74c3c' },
  { id: 8, name: '欧美精选', count: 42, color: '#2c3e50' },
  { id: 9, name: '抖音神曲2026', count: 51, color: '#e67e22' },
  { id: 10, name: '经典钢琴曲', count: 35, color: '#8e44ad' },
])
</script>

<template>
  <TopBar />
  <div class="playlists-page">
    <h2 class="page-title">📂 我的歌单</h2>
    <p class="subtitle">共 {{ playlists.length }} 个歌单</p>

    <div class="playlist-grid">
      <div
        v-for="pl in playlists"
        :key="pl.id"
        class="playlist-card"
        @click="router.push({ name: 'playlist', params: { id: pl.id } })"
      >
        <div class="pl-cover">
          <div class="cover-inner" :style="{ background: pl.color }">♪</div>
          <span class="pl-count">{{ pl.count }}首</span>
        </div>
        <p class="pl-name">{{ pl.name }}</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.playlists-page { padding: 32px; }
.page-title { font-size: 24px; font-weight: 700; color: #1a1a1a; margin-bottom: 4px; }
.subtitle { font-size: 13px; color: #999; margin-bottom: 28px; }

.playlist-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 20px;
}
.playlist-card { cursor: pointer; }
.pl-cover {
  position: relative; padding-bottom: 100%;
  border-radius: 10px; overflow: hidden; margin-bottom: 10px;
}
.cover-inner {
  position: absolute; inset: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: 42px; color: rgba(255,255,255,.3);
  transition: transform .3s;
}
.playlist-card:hover .cover-inner { transform: scale(1.05); }
.pl-count {
  position: absolute; top: 10px; right: 10px;
  padding: 3px 10px; border-radius: 4px;
  background: rgba(0,0,0,.55); font-size: 13px; color: #bbb;
}
.pl-name {
  font-size: 15px; color: #ccc;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
  overflow: hidden; line-height: 1.4;
}
</style>
