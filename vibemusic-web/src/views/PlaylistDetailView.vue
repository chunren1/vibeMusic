<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import TopBar from '@/components/TopBar.vue'
import request from '@/api/request'
import { usePlayerStore } from '@/stores/player'

const route = useRoute()
const player = usePlayerStore()
const source = computed(() => route.params.source || 'netease')
const playlistId = computed(() => route.params.id)
const info = ref(null)
const songs = ref([])
const loading = ref(true)
const loadError = ref(false)
const currentPlayId = ref(null)

async function load() {
  loading.value = true; loadError.value = false
  try {
    const res = await request.get('/playlists/detail', {
      params: { source: source.value, id: playlistId.value }
    })
    info.value = res.data
    songs.value = res.data.songs || []
  } catch (e) {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

function playAll() {
  if (!songs.value.length) return
  player.playPlaylist(songs.value.map(s => ({
    sourceId: s.id, name: s.name, artist: s.artist || '',
    coverUrl: s.coverUrl || '', duration: s.duration || 0, platform: source.value,
  })))
  currentPlayId.value = songs.value[0]?.id
}

function playSong(song, idx) {
  currentPlayId.value = song.id
  const full = songs.value.map(s => ({
    sourceId: s.id, name: s.name, artist: s.artist || '',
    coverUrl: s.coverUrl || '', duration: s.duration || 0, platform: source.value,
  }))
  player.clearQueue()
  full.forEach(s => player.addToQueue(s))
  player.playIndex(idx)
}

function fmtSec(s) { if (!s) return ''; const m = Math.floor(s / 60); return m + ':' + String(s % 60).padStart(2, '0') }
function fmtCount(n) { if (!n) return ''; return n > 10000 ? Math.floor(n / 10000) + '万' : n }

onMounted(() => load())
</script>

<template>
  <TopBar />
  <div class="root">
    <!-- 骨架屏 -->
    <template v-if="loading">
      <div class="sk-hero"></div>
      <div class="sk-bar"></div>
      <div v-for="i in 8" :key="i" class="sk-row"><span></span><span></span><span></span></div>
    </template>

    <!-- 错误 -->
    <div v-else-if="loadError" class="empty">加载失败，歌单不存在或网络错误</div>

    <!-- 详情 -->
    <template v-else-if="info">
      <!-- 顶部封面 -->
      <div
        class="hero"
        :style="info.coverUrl ? { backgroundImage: 'url(' + info.coverUrl + '?param=500y500)' } : { background: '#2a2a3e' }"
      >
        <div class="hero-mask"></div>
        <span class="source-tag" :class="info.source">{{ info.source === 'qq' ? 'QQ音乐' : '网易云' }}</span>
        <div class="hero-text">
          <h1 class="hero-name">{{ info.name }}</h1>
          <div class="hero-meta">
            <span v-if="info.creator?.name">by {{ info.creator.name }}</span>
            <span v-if="info.playCount">🔄 {{ fmtCount(info.playCount) }} 次</span>
            <span>{{ songs.length }} 首歌</span>
          </div>
        </div>
      </div>

      <!-- 操作栏 -->
      <div class="action-bar">
        <button class="btn-play" @click="playAll" :disabled="!songs.length">▶ 播放全部</button>
        <span class="action-hint" v-if="info.description">{{ info.description.slice(0, 60) }}{{ info.description.length > 60 ? '...' : '' }}</span>
      </div>

      <!-- 歌曲列表（参考搜索列表样式） -->
      <div class="song-list">
        <div class="list-header">
          <span class="h-idx">#</span>
          <span class="h-cover"></span>
          <span class="h-title">歌名</span>
          <span class="h-time">时长</span>
        </div>
        <div
          v-for="(song, idx) in songs" :key="song.id"
          class="song-row"
          :class="{ playing: player.currentSong?.id === song.id }"
        >
          <span class="c-idx">
            <span v-if="player.currentSong?.id === song.id && player.isPlaying" class="eq">▮▮</span>
            <span v-else>{{ idx + 1 }}</span>
          </span>
          <div class="c-cover" @click="playSong(song, idx)">
            <img v-if="song.coverUrl" :src="song.coverUrl + '?param=60y60'" class="cover-img" />
            <span v-else class="cover-icon">♪</span>
            <span class="play-hover">▶</span>
          </div>
          <div class="c-info" @click="playSong(song, idx)">
            <div class="c-name">
              {{ song.name }}
              <span class="c-plat">{{ info.source === 'qq' ? 'QQ' : '网易云' }}</span>
            </div>
            <div class="c-artist">{{ song.artist || '未知歌手' }}<span v-if="song.album"> · {{ song.album }}</span></div>
          </div>
          <span class="c-time">{{ fmtSec(song.duration) }}</span>
        </div>
      </div>
    </template>

    <div v-else class="empty">暂无数据</div>
  </div>
</template>

<style scoped>
.root { width: 100%; max-width: 100%; padding-bottom: 60px; }

/* ---- 骨架 ---- */
.sk-hero { height: 260px; background: #e0e0e0; animation: shim .8s infinite alternate; }
.sk-bar { height: 48px; background: #eee; margin: 12px 32px; border-radius: 8px; animation: shim .8s infinite alternate; }
.sk-row { display: flex; gap: 12px; padding: 10px 40px 10px 48px; }
.sk-row span { height: 14px; background: #eee; border-radius: 4px; animation: shim .8s infinite alternate; }
.sk-row span:nth-child(1) { width: 24px; } .sk-row span:nth-child(2) { flex: 1; } .sk-row span:nth-child(3) { width: 40px; }
@keyframes shim { to { opacity: .6; } }

/* ---- 封面 ---- */
.hero {
  position: relative; height: 280px; background-size: cover; background-position: center;
  display: flex; flex-direction: column; justify-content: flex-end; padding: 32px 40px;
}
.hero-mask { position: absolute; inset: 0; background: linear-gradient(transparent 30%, rgba(0,0,0,.75)); }
.source-tag {
  position: absolute; top: 20px; left: 20px; z-index: 1;
  padding: 4px 12px; border-radius: 4px; font-size: 12px; color: #fff; font-weight: 600;
}
.source-tag.netease { background: #ec4141; }
.source-tag.qq { background: #31c27c; }
.hero-text { position: relative; z-index: 1; color: #fff; }
.hero-name { font-size: 32px; font-weight: 700; margin-bottom: 8px; text-shadow: 0 2px 8px rgba(0,0,0,.4); }
.hero-meta { font-size: 14px; color: rgba(255,255,255,.75); display: flex; gap: 20px; }

/* ---- 操作栏 ---- */
.action-bar { display: flex; align-items: center; gap: 16px; padding: 16px 32px; border-bottom: 1px solid #eee; }
.btn-play {
  padding: 10px 32px; background: #31c27c; color: #fff; border: none;
  border-radius: 24px; font-size: 15px; font-weight: 600; cursor: pointer; white-space: nowrap;
}
.btn-play:hover { background: #28a86b; }
.btn-play:disabled { opacity: .5; cursor: not-allowed; }
.action-hint { font-size: 13px; color: #999; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* ---- 歌曲列表（参考搜索列表） ---- */
.song-list { padding: 0 32px; }
.list-header {
  display: grid; grid-template-columns: 36px 44px 1fr 60px; gap: 12px; align-items: center;
  padding: 10px 0; border-bottom: 1px solid #f0f0f0; color: #aaa; font-size: 12px; position: sticky; top: 0; background: #fff; z-index: 2;
}
.h-idx { text-align: center; }
.song-row {
  display: grid; grid-template-columns: 36px 44px 1fr 60px; gap: 12px; align-items: center;
  padding: 10px 0; border-bottom: 1px solid #f5f5f5; cursor: default; transition: background .12s;
}
.song-row:hover { background: #fafafa; }
.song-row.playing { background: rgba(49,194,124,.06); }
.song-row.playing .c-name { color: #31c27c; }

.c-idx { text-align: center; color: #bbb; font-size: 13px; }
.eq { color: #31c27c; font-weight: bold; animation: pulse .5s infinite alternate; }
@keyframes pulse { to { opacity: .3; } }

.c-cover { position: relative; width: 36px; height: 36px; cursor: pointer; border-radius: 4px; overflow: hidden; background: #f0f0f0; }
.cover-img { width: 100%; height: 100%; object-fit: cover; }
.cover-icon { display: flex; align-items: center; justify-content: center; width: 100%; height: 100%; color: #ccc; font-size: 16px; }
.play-hover { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; background: rgba(0,0,0,.4); color: #fff; font-size: 13px; opacity: 0; transition: .12s; }
.c-cover:hover .play-hover { opacity: 1; }

.c-info { cursor: pointer; min-width: 0; }
.c-name { font-size: 14px; color: #1a1a1a; display: flex; align-items: center; gap: 6px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.c-plat { font-size: 10px; padding: 1px 5px; border-radius: 3px; background: #ec4141; color: #fff; flex-shrink: 0; }
.c-artist { font-size: 12px; color: #999; margin-top: 2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.c-time { font-size: 12px; color: #bbb; text-align: right; }

.empty { text-align: center; padding: 120px 0; color: #aaa; font-size: 15px; }

/* 响应式 */
@media (max-width: 768px) {
  .hero { height: 200px; padding: 20px; }
  .hero-name { font-size: 22px; }
  .action-bar { padding: 12px 16px; }
  .song-list { padding: 0 16px; }
}
</style>
