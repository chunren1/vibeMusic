<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import TopBar from '@/components/TopBar.vue'
import { getPlaylists } from '@/api/song'

const router = useRouter()
const playlists = ref([])

onMounted(() => {
  getPlaylists().then(res => {
    playlists.value = res.data || []
  }).catch(() => {})
})
</script>

<template>
  <TopBar />
  <div class="playlists-page">
    <h2 class="page-title">📂 我的歌单</h2>
    <p class="subtitle">{{ playlists.length }} 个歌单</p>

    <div class="playlist-grid">
      <div
        v-for="pl in playlists" :key="pl.id"
        class="playlist-card"
        @click="router.push({ name: 'playlist', params: { id: pl.id } })"
      >
        <div class="pl-cover">
          <div class="cover-inner" :style="{ background: '#31c27c' }">♪</div>
          <span class="pl-count">{{ pl.songCount }}首</span>
        </div>
        <p class="pl-name">{{ pl.name }}</p>
        <p v-if="pl.description" class="pl-desc">{{ pl.description }}</p>
      </div>
    </div>

    <div v-if="playlists.length === 0" class="empty">
      <p>还没有歌单</p>
      <p class="hint">在歌曲列表中点击"加入歌单"来创建</p>
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
  font-size: 15px; color: #1a1a1a;
  display: -webkit-box; -webkit-line-clamp: 1; -webkit-box-orient: vertical;
  overflow: hidden;
}
.pl-desc {
  font-size: 12px; color: #999; margin-top: 4px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}

.empty { text-align: center; padding: 80px 0; color: #999; }
.hint { font-size: 13px; margin-top: 8px; }
</style>
