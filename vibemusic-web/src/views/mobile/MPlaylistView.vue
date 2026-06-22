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
const info = ref(null)
const loading = ref(true)
const loadError = ref(false)

function formatDuration(s) {
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
    if (pl) info.value = {
      id: pl.id,
      name: pl.name || '我的歌单',
      description: pl.description || '',
      coverUrl: pl.coverUrl || '',
      songCount: pl.songCount || 0,
      createdAt: pl.createdAt,
    }
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
  <div class="mp-detail">
    <!-- 返回按钮（浮动在 hero 上方） -->
    <button class="mp-back" @click="router.back()">
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.3"><polyline points="15 18 9 12 15 6"/></svg>
    </button>

    <!-- 加载 -->
    <div v-if="loading" class="mp-flex">
      <div class="mp-skel-hero">
        <div class="mp-skel-cover"></div>
        <div class="mp-skel-name"></div>
        <div class="mp-skel-sub"></div>
        <div class="mp-skel-btn"></div>
      </div>
    </div>

    <!-- 错误 -->
    <div v-else-if="loadError" class="mp-msg">加载失败，请返回重试</div>

    <!-- Hero 区 -->
    <template v-else-if="info">
      <div class="mp-hero">
        <div class="mph-cover">
          <img v-if="info.coverUrl" :src="info.coverUrl + '?param=300y300'" alt="" />
          <svg v-else viewBox="0 0 24 24" width="36" height="36" fill="currentColor" opacity="0.15"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>
        </div>
        <div class="mph-info">
          <h1 class="mph-name">{{ info.name }}</h1>
          <p class="mph-desc" v-if="info.description">{{ info.description }}</p>
          <div class="mph-meta">
            <span>{{ songs.length }} 首歌曲</span>
          </div>
          <button class="mph-play" @click="playAll" :disabled="!songs.length">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>
            播放全部
          </button>
        </div>
      </div>

      <!-- 空歌单 -->
      <div v-if="songs.length === 0" class="mp-msg">
        <p>歌单里还没有歌曲</p>
        <p class="mp-hint">去搜索页添加吧~</p>
      </div>

      <!-- 歌曲列表 -->
      <div v-else class="mp-list">
        <div
          v-for="(song, idx) in songs" :key="song.sourceId"
          class="mp-row" @click="play(song)"
          :class="{ playing: player.currentSong?.id === song.sourceId }"
        >
          <span class="mpr-idx">
            <span v-if="player.currentSong?.id === song.sourceId && player.isPlaying" class="mpr-eq">▮▮</span>
            <span v-else>{{ idx + 1 }}</span>
          </span>
          <div class="mpr-cover">
            <img v-if="song.coverUrl" :src="song.coverUrl + '?param=60y60'" loading="lazy" />
            <svg v-else viewBox="0 0 24 24" width="14" height="14" fill="currentColor" opacity="0.2"><path d="M9 18V5l12-2v13"/></svg>
          </div>
          <div class="mpr-info">
            <span class="mpr-name" :class="{ active: player.currentSong?.id === song.sourceId }">{{ song.name }}</span>
            <span class="mpr-artist">{{ song.artist || '-' }}</span>
          </div>
          <button
            class="mpr-fav" :class="{ faved: favStore.isFav(song.sourceId) }"
            @click.stop="favStore.toggleFav(song)"
          >
            <svg viewBox="0 0 24 24" width="16" height="16" :fill="favStore.isFav(song.sourceId) ? 'currentColor' : 'none'" stroke="currentColor" stroke-width="2"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>
          </button>
          <button class="mpr-del" @click.stop="removeSong(song)">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
          </button>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.mp-detail {
  background: #0a0a0a; min-height: 100dvh; padding-bottom: 60px;
  overflow-x: hidden;
  position: relative;
}

/* 返回按钮 */
.mp-back {
  position: absolute; top: 14px; left: 14px; z-index: 20;
  width: 36px; height: 36px; border-radius: 50%; border: none;
  background: rgba(0,0,0,.45); backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  color: #fff; display: flex; align-items: center; justify-content: center;
  cursor: pointer;
}

/* 加载骨架屏 */
.mp-flex { padding: 0 20px; }
.mp-skel-hero {
  display: flex; flex-direction: column; align-items: center;
  padding: 60px 0 40px; gap: 14px;
}
.mp-skel-cover {
  width: 160px; height: 160px; border-radius: 14px;
  background: rgba(255,255,255,.04); animation: mp-shim 1s infinite alternate;
}
.mp-skel-name {
  width: 60%; height: 22px; border-radius: 6px;
  background: rgba(255,255,255,.04); animation: mp-shim 1s infinite alternate;
}
.mp-skel-sub {
  width: 40%; height: 14px; border-radius: 6px;
  background: rgba(255,255,255,.03); animation: mp-shim 1s infinite alternate;
}
.mp-skel-btn {
  width: 140px; height: 40px; border-radius: 20px;
  background: rgba(255,255,255,.04); animation: mp-shim 1s infinite alternate;
}
@keyframes mp-shim { to { opacity: .2; } }

/* Hero 区 */
.mp-hero {
  display: flex; flex-direction: column; align-items: center;
  padding: 50px 24px 24px;
  background: linear-gradient(180deg, rgba(49,194,124,.06) 0%, transparent 60%);
  border-bottom: 1px solid rgba(255,255,255,.04);
}
.mph-cover {
  width: 160px; height: 160px; border-radius: 14px; overflow: hidden;
  margin-bottom: 18px; flex-shrink: 0;
  background: #1a1a2e; box-shadow: 0 8px 32px rgba(0,0,0,.3);
  display: flex; align-items: center; justify-content: center;
}
.mph-cover img { width: 100%; height: 100%; object-fit: cover; }
.mph-info { text-align: center; width: 100%; }
.mph-name {
  font-size: 18px; font-weight: 700; color: #eee;
  word-break: break-word; line-height: 1.3; margin: 0;
}
.mph-desc {
  font-size: 12px; color: #777; line-height: 1.5; margin: 8px 0 0;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
  overflow: hidden;
}
.mph-meta { font-size: 12px; color: #666; margin-top: 8px; }
.mph-play {
  display: inline-flex; align-items: center; gap: 8px;
  margin-top: 16px; padding: 10px 32px;
  background: #31c27c; color: #fff;
  border: none; border-radius: 24px;
  font-size: 15px; font-weight: 600; cursor: pointer;
  transition: .15s;
}
.mph-play:active { background: #28a86b; }
.mph-play:disabled { opacity: .4; }

/* 空状态 */
.mp-msg { text-align: center; padding: 80px 20px; color: #555; font-size: 15px; }
.mp-hint { font-size: 12px; color: #444; margin-top: 6px; }

/* 歌曲列表 */
.mp-list { padding: 8px 0; }
.mp-row {
  display: flex; align-items: center; gap: 10px;
  padding: 11px 16px;
  border-bottom: 1px solid rgba(255,255,255,.03);
  cursor: pointer; transition: .1s;
}
.mp-row:active { background: rgba(255,255,255,.025); }
.mp-row.playing .mpr-name { color: #31c27c; }
.mpr-idx { width: 24px; color: #555; font-size: 13px; text-align: center; flex-shrink: 0; }
.mpr-eq { color: #31c27c; font-weight: bold; animation: mp-pulse .5s infinite alternate; }
@keyframes mp-pulse { to { opacity: .3; } }
.mpr-cover {
  width: 38px; height: 38px; border-radius: 5px; overflow: hidden;
  flex-shrink: 0; background: #1a1a2e;
  display: flex; align-items: center; justify-content: center;
}
.mpr-cover img { width: 100%; height: 100%; object-fit: cover; }
.mpr-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.mpr-name {
  font-size: 14px; color: #d0d0d0;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.mpr-name.active { color: #31c27c; }
.mpr-artist {
  font-size: 11px; color: #666;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.mpr-fav, .mpr-del {
  width: 32px; height: 32px; border-radius: 6px;
  border: none; background: transparent; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0; transition: .15s;
}
.mpr-fav { color: #444; }
.mpr-fav.faved { color: #ec4141; }
.mpr-del { color: #444; }
.mpr-del:active { color: #e84c3d; }
</style>
