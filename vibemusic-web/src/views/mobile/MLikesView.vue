<script setup>
import { ref, onMounted } from 'vue'
import { getFavorites } from '@/api/song'
import { usePlayerStore } from '@/stores/player'
import { useFavoriteStore } from '@/stores/favorite'

const player = usePlayerStore()
const favStore = useFavoriteStore()
const songs = ref([])

favStore.fetchFavIds()

function play(song) {
  player.playSongFromApi(song.sourceId, song.songName, song.artist, song.coverUrl || '')
}

onMounted(() => {
  getFavorites().then(r => { songs.value = r.data || [] }).catch(() => {})
})
</script>

<template>
  <div class="m-page">
    <h2 class="m-title">
      <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="#ffc107" stroke-width="2" style="vertical-align:middle;margin-right:6px"><polygon points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26"/></svg>
      我的收藏
    </h2>
    <p class="m-sub">{{ songs.length }} 首</p>

    <div class="m-list">
      <div v-for="(s, i) in songs" :key="s.sourceId"
        class="m-item" :class="{ playing: player.currentSong.id === s.sourceId && player.isPlaying }" @click="play(s)">
        <div class="m-cover" :style="s.coverUrl ? { backgroundImage: `url(${s.coverUrl}?param=80y80)` } : {}">
          <span v-if="player.currentSong.id === s.sourceId && player.isPlaying" class="m-eq"><span></span><span></span><span></span></span>
        </div>
        <div class="m-info">
          <div class="m-name">{{ s.songName }}</div>
          <div class="m-artist">{{ s.artist }}</div>
        </div>
        <button :class="{ faved: favStore.isFav(s.sourceId) }" @click.stop="favStore.toggleFav(s)">
          <svg viewBox="0 0 24 24" width="18" height="18" :fill="favStore.isFav(s.sourceId) ? '#ffc107' : 'none'" stroke="currentColor" stroke-width="2"><polygon points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26"/></svg>
        </button>
      </div>
    </div>

    <div v-if="!songs.length" class="m-empty">还没有收藏歌曲</div>
  </div>
</template>

<style scoped>
.m-page { padding: 12px 16px; }
.m-title { font-size: 20px; font-weight: 700; color: #e0e0e0; }
.m-sub { font-size: 12px; color: #888; margin: 4px 0 16px; }
.m-list { display: flex; flex-direction: column; gap: 2px; }
.m-item {
  display: flex; align-items: center; gap: 10px; padding: 10px 8px; border-radius: 10px;
}
.m-item:active { background: rgba(255,255,255,.03); }
.m-item.playing { background: rgba(49,194,124,.06); }
.m-cover {
  width: 40px; height: 40px; border-radius: 6px; flex-shrink: 0;
  background: rgba(255,255,255,0.06) center/cover no-repeat;
  display: flex; align-items: center; justify-content: center;
}
.m-eq { display: flex; align-items: flex-end; gap: 2px; height: 14px; }
.m-eq span { width: 2px; background: #31c27c; border-radius: 1px; animation: eq .7s ease-in-out infinite alternate; }
.m-eq span:nth-child(1) { height: 7px; }
.m-eq span:nth-child(2) { height: 12px; animation-delay: .15s; }
.m-eq span:nth-child(3) { height: 5px; animation-delay: .3s; }
@keyframes eq { to { height: 3px; } }
.m-info { flex: 1; min-width: 0; }
.m-name { font-size: 14px; color: #e0e0e0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.m-artist { font-size: 12px; color: #888; margin-top: 2px; }
.m-item button {
  border: none; background: none; font-size: 16px; padding: 4px; cursor: pointer; color: #555; flex-shrink: 0;
}
.m-item button.faved { color: #ffc107; }
.m-empty { text-align: center; padding: 60px 0; color: #666; font-size: 14px; }
</style>
