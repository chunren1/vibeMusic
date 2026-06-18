<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import request from '@/api/request'
import { usePlayerStore } from '@/stores/player'

const route = useRoute()
const router = useRouter()
const player = usePlayerStore()
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
    const d = res.data
    info.value = d; songs.value = d.songs || []
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
function fmtCount(n) { if (!n) return ''; return n > 10000 ? Math.floor(n / 10000) + '万' : n }

onMounted(() => load())
</script>

<template>
  <div class="m-detail">
    <!-- 顶部导航 -->
    <div class="m-nav">
      <button class="m-back" @click="router.back()">←</button>
      <span class="m-title">{{ info?.name || '歌单' }}</span>
    </div>

    <!-- 骨架屏 -->
    <div v-if="loading" class="m-skel">
      <div class="ms-hero"></div>
      <div class="ms-row" v-for="i in 6" :key="i"><span></span><span></span></div>
    </div>

    <div v-else-if="loadError" class="m-err">加载失败</div>

    <template v-else-if="info">
      <!-- 封面 -->
      <div class="m-hero" :style="info.coverUrl ? { backgroundImage: 'url(' + info.coverUrl + '?param=300y300)' } : { background: '#2a2a3e' }">
        <div class="mh-cover">
          <img v-if="info.coverUrl" :src="info.coverUrl + '?param=200y200'" />
          <span v-else>♪</span>
        </div>
        <div class="mh-info">
          <h2>{{ info.name }}</h2>
          <p>{{ fmtCount(info.playCount) }} 次播放 · {{ songs.length }} 首歌</p>
        </div>
      </div>

      <!-- 操作栏 -->
      <div class="m-actions">
        <button class="m-btn-play" @click="playAll">▶ 播放全部</button>
      </div>

      <!-- 歌曲列表 -->
      <div class="m-song-list">
        <div
          v-for="(song, idx) in songs" :key="song.id"
          class="m-row" @click="playSong(song)"
          :class="{ playing: player.currentSong?.id === song.id }"
        >
          <span class="m-idx">{{ idx + 1 }}</span>
          <div class="mi-info">
            <span class="mi-name">{{ song.name }}</span>
            <span class="mi-artist">{{ song.artist }}</span>
          </div>
          <span class="mi-time">{{ fmtDuration(song.duration) }}</span>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.m-detail { background: #0a0a0a; min-height: 100vh; color: #eee; padding-bottom: 40px; }
.m-nav {
  display: flex; align-items: center; gap: 12px; padding: 12px 16px;
  position: sticky; top: 0; z-index: 10; background: rgba(10,10,10,.9);
}
.m-back { background: none; border: none; color: #ccc; font-size: 18px; cursor: pointer; }
.m-title { font-size: 16px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.m-hero { display: flex; align-items: center; gap: 16px; padding: 20px 16px; background-size: cover; background-position: center; }
.mh-cover { width: 100px; height: 100px; border-radius: 10px; overflow: hidden; flex-shrink: 0; background: #2a2a3e; display: flex; align-items: center; justify-content: center; }
.mh-cover img { width: 100%; height: 100%; object-fit: cover; }
.mh-cover span { font-size: 36px; color: #555; }
.mh-info h2 { font-size: 20px; margin-bottom: 4px; }
.mh-info p { font-size: 13px; color: #888; }

.m-actions { padding: 12px 16px; }
.m-btn-play {
  width: 100%; padding: 12px; background: #31c27c; color: #fff; border: none;
  border-radius: 20px; font-size: 16px; font-weight: 600; cursor: pointer;
}
.m-btn-play:active { background: #28a86b; }

.m-song-list { padding: 0 16px; }
.m-row {
  display: flex; align-items: center; gap: 10px; padding: 10px 8px;
  border-bottom: 1px solid rgba(255,255,255,.04); cursor: pointer;
}
.m-row.playing .mi-name { color: #31c27c; }
.m-idx { width: 24px; color: #666; font-size: 13px; text-align: center; }
.mi-info { flex: 1; min-width: 0; }
.mi-name { display: block; font-size: 14px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mi-artist { font-size: 12px; color: #888; }
.mi-time { font-size: 12px; color: #666; }

.m-skel { padding: 16px; }
.ms-hero { height: 140px; background: #1a1a2e; border-radius: 10px; margin-bottom: 16px; animation: shim .8s infinite alternate; }
.ms-row { display: flex; gap: 12px; padding: 10px 0; }
.ms-row span { height: 14px; background: #1a1a2e; border-radius: 4px; animation: shim .8s infinite alternate; }
.ms-row span:nth-child(1) { width: 24px; } .ms-row span:nth-child(2) { flex: 1; }
@keyframes shim { to { opacity: .6; } }
.m-err { text-align: center; padding: 100px 0; color: #666; }
</style>
