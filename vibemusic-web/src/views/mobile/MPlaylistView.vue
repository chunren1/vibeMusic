<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getPlaylists, getPlaylistSongs, removeFromPlaylist } from '@/api/song'
import { usePlayerStore } from '@/stores/player'
import { useFavoriteStore } from '@/stores/favorite'

const route = useRoute()
const router = useRouter()
const player = usePlayerStore()
const favStore = useFavoriteStore()
const playlistId = ref(Number(route.params.id) || 0)
const songs = ref([])
const playlistName = ref('歌单详情')
const loading = ref(true)
const loadError = ref(false)

function fmtDuration(s) {
  if (!s) return ''
  const m = Math.floor(s / 60)
  return m + ':' + String(s % 60).padStart(2, '0')
}

async function loadSongs() {
  if (!playlistId.value) return
  loading.value = true; loadError.value = false
  try {
    const [plRes, songsRes] = await Promise.all([
      getPlaylists(), getPlaylistSongs(playlistId.value)
    ])
    const pl = (plRes.data || []).find(p => p.id === playlistId.value)
    if (pl) playlistName.value = pl.name
    songs.value = (songsRes.data || []).map(s => ({
      sourceId: s.sourceId,
      name: s.songName,
      artist: s.artist || '',
      coverUrl: s.coverUrl || '',
      duration: s.duration || 0,
    }))
  } catch (e) {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

function play(song) {
  player.clearQueue()
  songs.value.forEach(s => player.addToQueue({
    sourceId: s.sourceId, name: s.name, artist: s.artist,
    coverUrl: s.coverUrl, duration: s.duration,
  }))
  const idx = songs.value.findIndex(s => s.sourceId === song.sourceId)
  player.playIndex(idx >= 0 ? idx : 0)
  router.push('/m/player')
}

function playAll() {
  if (songs.value.length === 0) return
  player.playPlaylist(songs.value)
  router.push('/m/player')
}

async function removeSong(song) {
  try {
    await removeFromPlaylist(playlistId.value, song.sourceId)
    songs.value = songs.value.filter(s => s.sourceId !== song.sourceId)
  } catch (e) {
    console.error('移除失败:', e)
  }
}

favStore.fetchFavIds()
onMounted(() => loadSongs())
</script>

<template>
  <div class="m-detail">
    <!-- 顶部导航 -->
    <div class="m-nav">
      <button class="m-back" @click="router.back()">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="15 18 9 12 15 6"/></svg>
      </button>
      <span class="m-title">{{ playlistName }}</span>
    </div>

    <!-- 加载/错误 -->
    <div v-if="loading" class="m-msg">加载中...</div>
    <div v-else-if="loadError" class="m-msg">加载失败，请检查是否登录</div>
    <div v-else-if="songs.length === 0" class="m-msg">
      <p>歌单里还没有歌曲</p>
      <p class="m-hint">去搜索页添加吧~</p>
    </div>

    <!-- 歌曲列表 -->
    <template v-else>
      <div class="m-actions">
        <button class="m-btn-play" @click="playAll">▶ 播放全部</button>
      </div>

      <div class="m-song-list">
        <div
          v-for="(song, idx) in songs" :key="song.sourceId"
          class="m-row" @click="play(song)"
          :class="{ playing: player.currentSong?.id === song.sourceId }"
        >
          <span class="m-idx">{{ idx + 1 }}</span>
          <div class="mi-cover" v-if="song.coverUrl">
            <img :src="song.coverUrl + '?param=60y60'" />
          </div>
          <div class="mi-info">
            <span class="mi-name">{{ song.name }}</span>
            <span class="mi-artist">{{ song.artist || '-' }}</span>
          </div>
          <button
            class="mi-fav"
            :class="{ faved: favStore.isFav(song.sourceId) }"
            @click.stop="favStore.toggleFav(song)"
          >★</button>
          <button
            class="mi-del"
            @click.stop="removeSong(song)"
          >✕</button>
          <span class="mi-time">{{ fmtDuration(song.duration) }}</span>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.m-detail { background: #0a0a0a; min-height: 100vh; color: #eee; padding-bottom: 60px; }

.m-nav {
  display: flex; align-items: center; gap: 10px;
  padding: 12px 16px;
  position: sticky; top: 0; z-index: 10;
  background: rgba(10,10,10,0.92);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid rgba(255,255,255,0.04);
}
.m-back { background: none; border: none; color: #ccc; font-size: 18px; cursor: pointer; padding: 4px; }
.m-title { font-size: 16px; font-weight: 600; flex: 1; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.m-msg { text-align: center; padding: 100px 0; color: #555; font-size: 15px; }
.m-hint { font-size: 12px; color: #444; margin-top: 6px; }

.m-actions { padding: 12px 16px; }
.m-btn-play {
  width: 100%; padding: 12px; background: #31c27c; color: #fff;
  border: none; border-radius: 20px; font-size: 16px; font-weight: 600; cursor: pointer;
}
.m-btn-play:active { background: #28a86b; }

.m-song-list { padding: 0 16px; }
.m-row {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 4px;
  border-bottom: 1px solid rgba(255,255,255,0.03);
  cursor: pointer;
}
.m-row:active { background: rgba(255,255,255,0.03); }
.m-row.playing .mi-name { color: #31c27c; }
.m-idx { width: 24px; color: #555; font-size: 13px; text-align: center; flex-shrink: 0; }
.mi-cover {
  width: 36px; height: 36px; border-radius: 4px; overflow: hidden;
  flex-shrink: 0; background: #1a1a2e;
}
.mi-cover img { width: 100%; height: 100%; object-fit: cover; }
.mi-info { flex: 1; min-width: 0; }
.mi-name {
  display: block; font-size: 14px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.mi-artist {
  font-size: 12px; color: #666;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  display: block; margin-top: 2px;
}
.mi-fav {
  width: 32px; height: 32px; border-radius: 6px;
  border: none; background: transparent;
  color: #444; font-size: 16px; cursor: pointer; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
}
.mi-fav.faved { color: #ec4141; }
.mi-del {
  width: 32px; height: 32px; border-radius: 6px;
  border: none; background: transparent;
  color: #444; font-size: 14px; cursor: pointer; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
}
.mi-del:active { color: #e84c3d; }
.mi-time { font-size: 12px; color: #555; flex-shrink: 0; }
</style>
