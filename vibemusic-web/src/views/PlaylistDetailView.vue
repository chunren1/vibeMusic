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
    const d = res.data
    info.value = d
    songs.value = d.songs || []
  } catch (e) {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

function playAll() {
  if (songs.value.length === 0) return
  const formatted = songs.value.map(s => ({
    sourceId: s.id,
    name: s.name,
    artist: s.artist || '',
    coverUrl: s.coverUrl || '',
    duration: s.duration || 0,
    platform: source.value,
  }))
  player.playPlaylist(formatted)
  currentPlayId.value = songs.value[0]?.id
}

function playSong(song, idx) {
  currentPlayId.value = song.id
  // 替换整个队列为歌单全部歌曲，从点击的那首开始播
  const full = songs.value.map(s => ({
    sourceId: s.id, name: s.name, artist: s.artist || '',
    coverUrl: s.coverUrl || '', duration: s.duration || 0, platform: source.value,
  }))
  player.clearQueue()
  full.forEach(s => player.addToQueue(s))
  player.playIndex(idx)
}

function fmtDuration(s) {
  if (!s) return ''
  const m = Math.floor(s / 60)
  return m + ':' + String(s % 60).padStart(2, '0')
}

function fmtCount(n) {
  if (!n) return ''
  return n > 10000 ? Math.floor(n / 10000) + '万' : n
}

onMounted(() => load())
</script>

<template>
  <TopBar />
  <div class="detail-page" v-if="!loading && !loadError && info">
    <!-- 顶部封面 -->
    <div class="hero" :style="info.coverUrl ? { backgroundImage: 'url(' + info.coverUrl + '?param=400y400)' } : { background: '#2a2a3e' }">
      <div class="hero-overlay"></div>
      <span class="platform-tag" :class="info.source">{{ info.source === 'qq' ? 'QQ音乐' : '网易云' }}</span>
      <div class="hero-info">
        <h1 class="hero-name">{{ info.name }}</h1>
        <div class="hero-meta">
          <span v-if="info.creator.name">by {{ info.creator.name }}</span>
          <span v-if="info.playCount">{{ fmtCount(info.playCount) }} 次播放</span>
          <span>{{ songs.length }} 首歌</span>
        </div>
      </div>
    </div>

    <!-- 操作栏 -->
    <div class="action-bar">
      <button class="btn-play-all" @click="playAll" :disabled="songs.length === 0">▶ 播放全部</button>
    </div>

    <!-- 歌曲列表 -->
    <div class="song-list">
      <div
        v-for="(song, idx) in songs" :key="song.id"
        class="song-row"
        :class="{ playing: currentPlayId === song.id }"
        @click="playSong(song, idx)"
      >
        <span class="rd-idx">{{ idx + 1 }}</span>
        <div class="rd-info">
          <span class="rd-name">{{ song.name }}</span>
          <span class="rd-artist">{{ song.artist }}</span>
        </div>
        <span class="rd-time">{{ fmtDuration(song.duration) }}</span>
      </div>
    </div>

    <div v-if="info.description" class="desc-section">
      <h3>歌单简介</h3>
      <p>{{ info.description }}</p>
    </div>
  </div>

  <!-- 加载态 -->
  <div v-if="loading" class="skeleton">
    <TopBar />
    <div class="sk-hero"></div>
    <div class="sk-bar"></div>
    <div class="sk-row" v-for="i in 8" :key="i"><span></span><span></span><span></span></div>
  </div>

  <div v-if="loadError" class="error">加载失败，歌单不存在或网络错误</div>
</template>

<style scoped>
.detail-page { max-width: 960px; margin: 0 auto; padding-bottom: 60px; }

.hero {
  position: relative; height: 280px; background-size: cover; background-position: center;
  display: flex; flex-direction: column; justify-content: flex-end; padding: 30px;
}
.hero-overlay { position: absolute; inset: 0; background: linear-gradient(transparent 40%, rgba(0,0,0,.7)); }
.platform-tag {
  position: absolute; top: 16px; left: 16px; z-index: 1;
  padding: 4px 10px; border-radius: 4px; font-size: 12px; color: #fff;
}
.platform-tag.netease { background: #ec4141; }
.platform-tag.qq { background: #31c27c; }
.hero-info { position: relative; z-index: 1; color: #fff; }
.hero-name { font-size: 28px; font-weight: 700; margin-bottom: 8px; }
.hero-meta { font-size: 13px; color: rgba(255,255,255,.7); display: flex; gap: 16px; }

.action-bar { padding: 16px 30px; display: flex; gap: 12px; }
.btn-play-all {
  padding: 10px 32px; background: #31c27c; color: #fff; border: none;
  border-radius: 20px; font-size: 15px; font-weight: 600; cursor: pointer;
}
.btn-play-all:hover { background: #28a86b; }
.btn-play-all:disabled { opacity: .5; cursor: not-allowed; }

.song-list { padding: 0 30px; }
.song-row {
  display: flex; align-items: center; gap: 12px; padding: 10px 8px; border-radius: 8px;
  cursor: pointer; transition: background .12s;
}
.song-row:hover { background: rgba(0,0,0,.03); }
.song-row.playing { background: rgba(49,194,124,.08); }
.song-row.playing .rd-name { color: #31c27c; }
.rd-idx { width: 28px; text-align: center; color: #999; font-size: 13px; flex-shrink: 0; }
.rd-info { flex: 1; min-width: 0; }
.rd-name { display: block; font-size: 14px; color: #1a1a1a; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rd-artist { font-size: 12px; color: #888; margin-top: 2px; }
.rd-time { font-size: 12px; color: #999; flex-shrink: 0; }

.desc-section { padding: 24px 30px; }
.desc-section h3 { font-size: 16px; margin-bottom: 8px; }
.desc-section p { font-size: 14px; color: #666; line-height: 1.8; }

.skeleton { max-width: 960px; margin: 0 auto; }
.sk-hero { height: 280px; background: #e0e0e0; margin-bottom: 12px; animation: shim .8s infinite alternate; }
.sk-bar { height: 40px; background: #eee; margin: 0 30px 12px; border-radius: 8px; animation: shim .8s infinite alternate; }
.sk-row { display: flex; gap: 12px; padding: 10px 38px; }
.sk-row span { height: 14px; background: #eee; border-radius: 4px; animation: shim .8s infinite alternate; }
.sk-row span:nth-child(1) { width: 24px; } .sk-row span:nth-child(2) { flex: 1; } .sk-row span:nth-child(3) { width: 40px; }
@keyframes shim { to { opacity: .6; } }

.error { text-align: center; padding: 100px 0; color: #999; }
</style>
