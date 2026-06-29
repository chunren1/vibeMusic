<script setup>
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TopBar from '@/components/TopBar.vue'
import PlaylistPopup from '@/components/PlaylistPopup.vue'
import { searchSongs } from '@/api/song'
import { usePlayerStore } from '@/stores/player'
import { useFavoriteStore } from '@/stores/favorite'

const route = useRoute()
const router = useRouter()
const player = usePlayerStore()
const favStore = useFavoriteStore()

const keyword = ref(route.query.keyword || '')
const results = ref([])
const loading = ref(false)
const page = ref(1)
const totalPageSize = 40

const showPlaylistPopup = ref(false)
const playlistTargetSong = ref(null)

// 输入自动搜索（300ms 防抖）
let debounceTimer = null
watch(keyword, () => {
  clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => {
    if (!keyword.value.trim()) { results.value = []; return }
    onSearch()
  }, 300)
})

function fmtSec(s) {
  if (!s) return ''
  const m = Math.floor(s / 60)
  return m + ':' + String(s % 60).padStart(2, '0')
}

async function onSearch() {
  if (!keyword.value.trim()) return
  loading.value = true
  page.value = 1
  try {
    const res = await searchSongs(keyword.value.trim(), 1, totalPageSize)
    results.value = res.data || []
  } catch (e) {
    console.error('搜索失败:', e)
    results.value = []
  } finally {
    loading.value = false
  }
}

function playSong(song) {
  player.playSongFromApi(song.sourceId, song.name, song.artist, song.coverUrl || '', song.platform || '')
}

function addToQueueFn(song) {
  player.addToQueue({
    sourceId: song.sourceId,
    name: song.name,
    artist: song.artist || '',
    coverUrl: song.coverUrl || '',
    duration: song.duration || 0,
    platform: song.platform || '',
  })
}

function openPlaylistPopup(song) {
  playlistTargetSong.value = { ...song, songName: song.name, name: song.name }
  showPlaylistPopup.value = true
}

favStore.fetchFavIds()

const routeKeyword = route.query.keyword
watch(() => route.query.keyword, (val) => {
  if (val === routeKeyword) return // 忽略首次立即执行
  keyword.value = val || ''
  if (val) onSearch()
}, { immediate: true })
</script>

<template>
  <TopBar />
  <div class="search-page">
    <div class="search-box">
      <input
        v-model="keyword"
        @keyup.enter="onSearch"
        placeholder="搜索歌曲..."
        class="search-input"
      />
      <button class="search-btn" @click="onSearch">搜索</button>
    </div>

    <div v-if="loading" class="loading">搜索中...</div>

    <div v-else-if="results.length > 0" class="song-table">
      <div class="table-header">
        <span class="th-index">#</span>
        <span class="th-cover"></span>
        <span class="th-title">歌名</span>
        <span class="th-time">时长</span>
        <span class="th-actions"></span>
      </div>
      <div
        v-for="(song, idx) in results" :key="song.sourceId"
        class="table-row"
        :class="{ playing: player.currentSong.id === song.sourceId }"
      >
        <span class="td-index">
          <span v-if="player.currentSong.id === song.sourceId && player.isPlaying" class="playing-eq">▮▮</span>
          <span v-else>{{ idx + 1 }}</span>
        </span>
        <div class="td-cover" @click="playSong(song)">
          <img v-if="song.coverUrl" :src="song.coverUrl" class="cover-img" />
          <span v-else class="cover-icon">♪</span>
          <span class="play-hover">▶</span>
        </div>
        <div class="td-info" @click="playSong(song)">
          <div class="song-name">
            {{ song.name }}
            <span v-if="song.platform" class="platform-tag">{{ song.platform === 'qq' ? 'QQ' : '网易云' }}</span>
          </div>
          <div class="song-artist">{{ song.artist }}</div>
        </div>
        <span class="td-time">{{ fmtSec(song.duration) }}</span>
        <div class="td-actions">
          <button
            :class="{ faved: favStore.isFav(song.sourceId) }"
            @click.stop="favStore.toggleFav(song)"
            title="收藏"
          >★</button>
          <button @click.stop="addToQueueFn(song)" title="加入队列">+</button>
          <button @click.stop="openPlaylistPopup(song)" title="加入歌单">📋</button>
        </div>
      </div>
    </div>

    <div v-else class="empty">
      <p v-if="keyword">未找到结果</p>
      <p v-else>输入关键词搜索歌曲</p>
    </div>

    <PlaylistPopup
      v-if="showPlaylistPopup && playlistTargetSong"
      :song="playlistTargetSong"
      @close="showPlaylistPopup = false"
    />
  </div>
</template>

<style scoped>
.search-page { padding: 24px 32px; max-width: 960px; margin: 0 auto; }

.search-box {
  display: flex; gap: 12px; margin-bottom: 24px;
}

.search-input {
  flex: 1; padding: 10px 16px;
  border: 1px solid #444; border-radius: 8px;
  background: #1a1a2e; color: #eee; font-size: 15px; outline: none;
}
.search-input:focus { border-color: #ec4141; }

.search-btn {
  padding: 10px 24px;
  background: #ec4141; color: #fff; border: none; border-radius: 8px;
  cursor: pointer; font-size: 14px; font-weight: 600;
}
.search-btn:hover { background: #d43737; }

.loading, .empty {
  text-align: center; padding: 60px 0; color: #666;
}

.song-table { width: 100%; }

.table-header {
  display: grid;
  grid-template-columns: 40px 48px 1fr 60px 100px;
  gap: 12px; align-items: center;
  padding: 8px 0; border-bottom: 1px solid #222;
  color: #888; font-size: 12px;
}
.table-row {
  display: grid;
  grid-template-columns: 40px 48px 1fr 60px 100px;
  gap: 12px; align-items: center;
  padding: 8px 0; border-bottom: 1px solid #1a1a2e;
  cursor: default; transition: background .15s;
}
.table-row:hover { background: #1a1a2e; }
.table-row.playing { background: rgba(236,65,65,.08); }

.td-index { color: #888; font-size: 13px; text-align: center; }
.playing-eq { color: #ec4141; font-weight: bold; animation: pulse .6s infinite alternate; }
@keyframes pulse { to { opacity: .4; } }

.td-cover { position: relative; width: 40px; height: 40px; cursor: pointer; border-radius: 4px; overflow: hidden; }
.cover-img { width: 100%; height: 100%; object-fit: cover; }
.cover-icon { display: flex; align-items: center; justify-content: center; width: 100%; height: 100%; background: #2a2a3e; color: #666; font-size: 18px; }
.play-hover {
  position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
  background: rgba(0,0,0,.5); color: #fff; font-size: 16px; opacity: 0; transition: opacity .15s;
}
.td-cover:hover .play-hover { opacity: 1; }

.td-info { cursor: pointer; }
.song-name { font-size: 14px; color: #eee; display: flex; align-items: center; gap: 6px; }
.platform-tag { font-size: 10px; padding: 2px 6px; border-radius: 4px; background: #ec4141; color: #fff; }
.song-artist { font-size: 12px; color: #888; margin-top: 2px; }

.td-time { font-size: 12px; color: #666; }

.td-actions { display: flex; gap: 8px; }
.td-actions button {
  width: 30px; height: 30px; border-radius: 6px; border: 1px solid #333;
  background: transparent; color: #888; cursor: pointer; font-size: 14px;
  display: flex; align-items: center; justify-content: center;
  transition: all .15s;
}
.td-actions button:hover { border-color: #ec4141; color: #ec4141; }
.td-actions button.faved { color: #ec4141; border-color: #ec4141; }
</style>
