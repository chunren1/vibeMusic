<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import request from '@/api/request'
import { usePlayerStore } from '@/stores/player'
import { useFavoriteStore } from '@/stores/favorite'

const route = useRoute()
const router = useRouter()
const player = usePlayerStore()
const favStore = useFavoriteStore()

const source = computed(() => route.params.source || 'netease')
const playlistId = computed(() => route.params.id)
const info = ref(null)
const songs = ref([])
const loading = ref(true)
const loadError = ref(false)

async function load() {
  loading.value = true; loadError.value = false
  try {
    const res = await request.get('/playlists/detail', {
      params: { source: source.value, id: playlistId.value }
    })
    info.value = res.data
    songs.value = res.data.songs || []
  } catch (e) { loadError.value = true } finally { loading.value = false }
}

function playAll() {
  if (songs.value.length === 0) return
  player.playPlaylist(songs.value.map(s => ({
    sourceId: s.id, name: s.name, artist: s.artist || '',
    coverUrl: s.coverUrl || '', duration: s.duration || 0, platform: source.value,
  })))
  router.push('/m/player')
}

function playSong(song) {
  player.clearQueue()
  songs.value.forEach(s => player.addToQueue({
    sourceId: s.id, name: s.name, artist: s.artist || '',
    coverUrl: s.coverUrl || '', duration: s.duration || 0, platform: source.value,
  }))
  const idx = songs.value.findIndex(s => s.id === song.id)
  player.playIndex(idx >= 0 ? idx : 0)
  router.push('/m/player')
}

function fmtDuration(s) { if (!s) return ''; const m = Math.floor(s / 60); return m + ':' + String(s % 60).padStart(2, '0') }
function fmtCount(n) { if (!n) return ''; if (n > 100000000) return (n / 100000000).toFixed(1) + '亿'; if (n > 10000) return Math.floor(n / 10000) + '万'; return n }

onMounted(() => { load(); favStore.fetchFavIds() })
</script>

<template>
  <div class="m-detail">
    <!-- 顶部导航 -->
    <div class="m-nav">
      <button class="m-back" @click="router.back()">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="15 18 9 12 15 6"/></svg>
      </button>
      <span class="m-title">{{ loading ? '加载中...' : (info?.name || '歌单') }}</span>
      <span class="m-source-tag" v-if="info">{{ info.source === 'qq' ? 'QQ' : '云' }}</span>
    </div>

    <!-- 骨架屏 -->
    <div v-if="loading" class="m-skel">
      <div class="ms-hero"></div>
      <div class="ms-row" v-for="i in 6" :key="i"><span></span><span></span></div>
    </div>

    <div v-else-if="loadError" class="m-err">加载失败，歌单不存在或网络错误</div>

    <template v-else-if="info">
      <!-- 封面区域 -->
      <div class="m-hero">
        <div class="mh-cover">
          <img v-if="info.coverUrl" :src="info.coverUrl + '?param=200y200'" alt="" />
          <span v-else>♪</span>
        </div>
        <div class="mh-info">
          <h2>{{ info.name }}</h2>
          <p v-if="info.creator">by {{ info.creator.nickname || info.creator.name }}</p>
          <p>{{ fmtCount(info.playCount) }} 次播放 · {{ songs.length }} 首歌</p>
        </div>
      </div>

      <!-- 简介 -->
      <p class="m-desc" v-if="info.description">{{ info.description }}</p>

      <!-- 操作栏 -->
      <div class="m-actions">
        <button class="m-btn-play" @click="playAll">▶ 播放全部</button>
      </div>

      <!-- 歌曲列表 -->
      <div class="m-song-list">
        <div
          v-for="(song, idx) in songs" :key="song.id"
          class="m-row"
          @click="playSong(song)"
          :class="{ playing: player.currentSong?.id === song.id }"
        >
          <span class="m-idx">{{ idx + 1 }}</span>
          <div class="mi-cover" v-if="song.coverUrl">
            <img :src="song.coverUrl + '?param=60y60'" />
          </div>
          <div class="mi-info">
            <span class="mi-name">{{ song.name }}</span>
            <span class="mi-artist">{{ song.artist || '未知歌手' }}<span v-if="song.album"> · {{ song.album }}</span></span>
          </div>
          <button
            class="mi-fav"
            :class="{ faved: favStore.isFav(song.id) }"
            @click.stop="favStore.toggleFav(song)"
          >★</button>
          <span class="mi-time">{{ fmtDuration(song.duration) }}</span>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.m-detail { background: #0a0a0a; min-height: 100vh; color: #eee; padding-bottom: 60px; }

/* ======== 导航 ======== */
.m-nav {
  display: flex; align-items: center; gap: 10px;
  padding: 12px 16px;
  position: sticky; top: 0; z-index: 10;
  background: rgba(10,10,10,0.92);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid rgba(255,255,255,0.04);
}
.m-back {
  background: none; border: none; color: #ccc;
  font-size: 18px; cursor: pointer; padding: 4px;
}
.m-title {
  font-size: 16px; font-weight: 600;
  flex: 1;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.m-source-tag {
  font-size: 10px; padding: 2px 8px; border-radius: 8px;
  background: rgba(49,194,124,0.15); color: #31c27c;
  font-weight: 500;
}

/* ======== 封面 ======== */
.m-hero {
  display: flex; align-items: center; gap: 16px;
  padding: 24px 16px;
  background: linear-gradient(180deg, rgba(49,194,124,0.05) 0%, transparent 100%);
}
.mh-cover {
  width: 110px; height: 110px;
  border-radius: 10px; overflow: hidden;
  flex-shrink: 0;
  background: #1a1a2e;
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 4px 20px rgba(0,0,0,0.4);
}
.mh-cover img { width: 100%; height: 100%; object-fit: cover; }
.mh-cover span { font-size: 32px; color: #444; }
.mh-info { flex: 1; min-width: 0; }
.mh-info h2 { font-size: 20px; margin-bottom: 6px; line-height: 1.3; }
.mh-info p { font-size: 13px; color: #888; }

/* ======== 简介 ======== */
.m-desc {
  padding: 0 16px 12px;
  font-size: 13px; color: #666;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* ======== 操作 ======== */
.m-actions { padding: 8px 16px 16px; }
.m-btn-play {
  width: 100%; padding: 12px;
  background: #31c27c; color: #fff;
  border: none; border-radius: 20px;
  font-size: 16px; font-weight: 600; cursor: pointer;
}
.m-btn-play:active { background: #28a86b; }

/* ======== 歌曲列表 ======== */
.m-song-list { padding: 0 16px; }
.m-row {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 4px;
  border-bottom: 1px solid rgba(255,255,255,0.03);
  cursor: pointer;
  transition: background .12s;
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
  color: #444; font-size: 16px; cursor: pointer;
  flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
}
.mi-fav.faved { color: #ec4141; }
.mi-time { font-size: 12px; color: #555; flex-shrink: 0; }

/* ======== 骨架 ======== */
.m-skel { padding: 16px; }
.ms-hero { height: 140px; background: #111; border-radius: 10px; margin-bottom: 16px; animation: shim .8s infinite alternate; }
.ms-row { display: flex; gap: 12px; padding: 10px 0; }
.ms-row span { height: 14px; background: #111; border-radius: 4px; animation: shim .8s infinite alternate; }
.ms-row span:nth-child(1) { width: 24px; } .ms-row span:nth-child(2) { flex: 1; }
@keyframes shim { to { opacity: .5; } }
.m-err { text-align: center; padding: 100px 0; color: #555; font-size: 15px; }
</style>
