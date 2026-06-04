<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { searchSongs, getRandomSongs as apiRandomSongs, playSong as apiPlaySong } from '@/api/song'

const router = useRouter()

// ===== 全局音频播放器（与PlayerBar共享） =====
const audio = window.vibeAudio || new Audio()
window.vibeAudio = audio
const currentPlaySong = ref(null)
const isPlaying = ref(false)

audio.addEventListener('play', () => { isPlaying.value = true })
audio.addEventListener('pause', () => { isPlaying.value = false })
audio.addEventListener('ended', () => { isPlaying.value = false })

function playSong(song) {
  if (!song.sourceId) return

  // 如果同一首歌且正在播放，暂停/继续
  if (currentPlaySong.value?.sourceId === song.sourceId) {
    if (isPlaying.value) {
      audio.pause()
    } else {
      audio.play().catch(() => {})
    }
    return
  }

  // 新 API: playSong(sourceId, name, artist)
  apiPlaySong(song.sourceId, song.name, song.artist).then(res => {
    const url = res.data?.url
    if (!url) return
    audio.src = url
    audio.play().catch(() => {})
    currentPlaySong.value = song

    window.dispatchEvent(new CustomEvent('song-change', {
      detail: {
        title: song.name,
        artist: song.artist,
        id: song.sourceId,
        coverUrl: song.coverUrl,
        duration: song.duration,
        album: song.album,
      }
    }))
  }).catch(() => {})
}

// 下载歌曲（传完整 song 对象）
import { downloadSong as apiDownload } from '@/api/song'
const downloadingIds = ref(new Set())
function handleDownload(song) {
  if (downloadingIds.value.has(song.sourceId)) return
  downloadingIds.value.add(song.sourceId)
  apiDownload(song.sourceId, song).then(() => {
    alert(song.name + ' 下载完成!')
  }).catch(e => {
    alert('下载失败: ' + (e.message || ''))
  }).finally(() => {
    downloadingIds.value.delete(song.sourceId)
  })
}

// ===== 用户信息（模拟） =====
const user = ref({
  name: '音乐爱好者',
  avatar: '',
})

// ===== Banner 轮播 =====
const slides = ref([
  { id: 1, title: '总有一首歌', subtitle: '让你想起最初的自己', bg: 'linear-gradient(135deg, #2b5a3a, #1a3d24)', emoji: '🎵' },
  { id: 2, title: '发现好音乐', subtitle: '从这里开始你的音乐之旅', bg: 'linear-gradient(135deg, #4a2c5a, #2d1a3d)', emoji: '🎸' },
  { id: 3, title: '随机推荐', subtitle: '听听不一样的声音', bg: 'linear-gradient(135deg, #2a4a5a, #1a2d3d)', emoji: '🎹' },
])
const activeSlide = ref(0)

onMounted(() => {
  setInterval(() => {
    activeSlide.value = (activeSlide.value + 1) % slides.value.length
  }, 4000)
})

// ===== 随机推荐歌曲（从后端获取） =====
const randomSongs = ref([])

function shuffleSongs() {
  apiRandomSongs(8).then(res => {
    randomSongs.value = (res.data || []).map(s => ({
      ...s,
      coverColor: randomColor(),
    }))
  }).catch(() => {
    // 后端不可用时用模拟数据
    const pool = [
      { sourceId: '1', name: '晴天', artist: '周杰伦', cover: '' },
      { sourceId: '2', name: '孤勇者', artist: '陈奕迅', cover: '' },
      { sourceId: '3', name: '起风了', artist: '买辣椒也用券', cover: '' },
      { sourceId: '4', name: '错位时空', artist: '艾辰', cover: '' },
      { sourceId: '5', name: '若月亮没来', artist: '黄绮珊', cover: '' },
      { sourceId: '6', name: '罗生门', artist: '张子豪', cover: '' },
      { sourceId: '7', name: '篇章', artist: '张韶涵 / 王赫野', cover: '' },
      { sourceId: '8', name: '我记得', artist: '赵雷', cover: '' },
    ]
    randomSongs.value = pool.map(s => ({ ...s, coverColor: randomColor() }))
  })
}

function randomColor() {
  const colors = ['#31c27c', '#ec4141', '#5b3cc4', '#d44455', '#3c7cc4', '#c48b3c']
  return colors[Math.floor(Math.random() * colors.length)]
}

onMounted(() => shuffleSongs())

// ===== 推荐歌单 =====
const playlists = ref([
  { id: 1, name: '华语热门精选', count: 56, color: '#e84c3d' },
  { id: 2, name: '治愈系纯音乐', count: 38, color: '#3498db' },
  { id: 3, name: '说唱新世代', count: 29, color: '#2ecc71' },
  { id: 4, name: '怀旧金曲', count: 64, color: '#f39c12' },
  { id: 5, name: '民谣在路上', count: 27, color: '#9b59b6' },
  { id: 6, name: '电竞燃曲BGM', count: 33, color: '#1abc9c' },
])

// ===== 页面内搜索（调用后端API） =====
const searchKeyword = ref('')
const searchResults = ref([])
const searchLoading = ref(false)
const hasSearched = ref(false)

async function doSearch() {
  const keyword = searchKeyword.value.trim()
  if (!keyword) {
    searchResults.value = []
    hasSearched.value = false
    return
  }
  searchLoading.value = true
  hasSearched.value = true

  try {
    const res = await searchSongs(keyword)
    searchResults.value = res.data || []
  } catch (e) {
    console.log('搜索失败:', e.message)
    searchResults.value = []
  } finally {
    searchLoading.value = false
  }
}

function clearSearch() {
  searchKeyword.value = ''
  searchResults.value = []
  hasSearched.value = false
}

// 格式化秒数为 mm:ss
function formatDuration(seconds) {
  if (!seconds && seconds !== 0) return ''
  const s = parseInt(seconds)
  const m = Math.floor(s / 60)
  const sec = s % 60
  return m + ':' + String(sec).padStart(2, '0')
}
</script>

<template>
  <div class="home">
    <!-- 搜索栏 + 用户信息 -->
    <div class="top-bar">
      <div class="search-area">
        <div class="search-box">
          <span class="search-icon">🔍</span>
          <input
            v-model="searchKeyword"
            @keyup.enter="doSearch"
            @input="searchKeyword.trim() === '' ? clearSearch() : null"
            placeholder="搜索歌曲、歌手、专辑"
            class="search-input"
          />
          <button v-if="searchKeyword" class="search-clear" @click="clearSearch">✕</button>
        </div>
      </div>

      <div class="user-info">
        <div class="user-avatar">👤</div>
        <span class="user-name">{{ user.name }}</span>
      </div>
    </div>

    <!-- 搜索结果页面（全屏展示，非下拉） -->
    <div v-if="hasSearched" class="search-page">
      <div class="search-page-header">
        <h2 class="search-title">"{{ searchKeyword }}" 的搜索结果</h2>
        <div class="search-stats">
          <span v-if="searchLoading">搜索中...</span>
          <span v-else-if="searchResults.length === 0">未找到相关歌曲</span>
          <span v-else>找到 {{ searchResults.length }} 首歌曲</span>
        </div>
        <button class="back-btn" @click="clearSearch">返回首页</button>
      </div>
      
      <div v-if="!searchLoading && searchResults.length > 0" class="search-result-page">
        <div class="result-table-header">
          <span class="th-index">#</span>
          <span class="th-cover"></span>
          <span class="th-title">歌名</span>
          <span class="th-album">专辑</span>
          <span class="th-time">时长</span>
          <span class="th-actions"></span>
        </div>
        <div
          v-for="(song, idx) in searchResults"
          :key="song.sourceId"
          class="result-page-item"
          :class="{ playing: currentPlaySong?.sourceId === song.sourceId }"
        >
          <span class="rp-index">
            <span v-if="currentPlaySong?.sourceId === song.sourceId && isPlaying" class="equalizer">▮▮</span>
            <span v-else>{{ idx + 1 }}</span>
          </span>
          <div class="rp-cover-wrap">
            <div
              class="rp-cover"
              :style="song.coverUrl ? { backgroundImage: 'url(' + song.coverUrl + '?param=100y100)' } : {}"
              @click="playSong(song)"
            >
              <span v-if="!song.coverUrl">♪</span>
              <div class="cover-play-btn">▶</div>
            </div>
          </div>
          <div class="rp-text" @click="playSong(song)">
            <span class="rp-title" :class="{ active: currentPlaySong?.sourceId === song.sourceId }">{{ song.name }}</span>
            <span class="rp-artist">{{ song.artist }}</span>
          </div>
          <span class="rp-album" @click="playSong(song)">{{ song.album || '-' }}</span>
          <span class="rp-time" @click="playSong(song)">{{ formatDuration(song.duration) }}</span>
          <div class="rp-actions">
            <button
              class="action-btn download-btn"
              @click.stop="handleDownload(song)"
              :title="downloadingIds.has(song.id) ? '下载中...' : '下载到RustFS'"
            >
              {{ downloadingIds.has(song.id) ? '⏳' : '⬇' }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Banner 轮播 + 下方内容（搜索时隐藏） -->
    <template v-if="!hasSearched">
    <div class="banner">
      <div
        v-for="(slide, idx) in slides"
        :key="slide.id"
        class="banner-slide"
        :class="{ active: idx === activeSlide }"
        @click="activeSlide = idx"
      >
        <div class="slide-bg" :style="{ background: slide.bg }"></div>
        <div class="slide-emoji">{{ slide.emoji }}</div>
        <div class="slide-text">
          <h2>{{ slide.title }}</h2>
          <p>{{ slide.subtitle }}</p>
        </div>
      </div>
      <div class="banner-dots">
        <span
          v-for="(_, idx) in slides"
          :key="idx"
          class="dot" :class="{ active: idx === activeSlide }"
          @click="activeSlide = idx"
        ></span>
      </div>
    </div>

    <!-- 推荐歌曲 -->
    <section class="section">
      <div class="section-header">
        <h3>推荐歌曲</h3>
        <span class="refresh-btn" @click="shuffleSongs">🔄 换一批</span>
      </div>
      <div class="song-scroll">
        <div
          v-for="song in randomSongs"
          :key="song.sourceId"
          class="song-card"
          @click="playSong(song)"
        >
          <div class="card-cover" @click="playSong(song)">
            <div
              class="cover-grad"
              :style="song.coverUrl ? { backgroundImage: 'url(' + song.coverUrl + '?param=200y200)', backgroundSize: 'cover' } : { background: song.coverColor }"
            >
              <span v-if="!song.coverUrl">♪</span>
            </div>
            <div class="play-overlay">▶</div>
          </div>
          <p class="card-title">{{ song.name }}</p>
          <p class="card-artist">{{ song.artist }}</p>
        </div>
      </div>
    </section>

    <!-- 推荐歌单 -->
    <section class="section">
      <div class="section-header">
        <h3>推荐歌单</h3>
        <span class="more">更多 ›</span>
      </div>
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
    </section>
    </template>
  </div>
</template>

<style scoped>
.home { padding-bottom: 20px; }

/* ===== 顶部搜索栏 + 用户信息 ===== */
.top-bar {
  padding: 32px 32px 36px; display: flex; align-items: flex-start; justify-content: space-between; gap: 20px;
}
.search-area { position: relative; flex: 1; max-width: 560px; }

.search-box {
  display: flex; align-items: center;
  width: 100%; padding: 14px 20px;
  background: #1e2024; border-radius: 24px; border: 1px solid transparent;
  transition: .2s;
}
.search-box:focus-within { border-color: #31c27c; background: #222; }
.search-icon { font-size: 16px; margin-right: 10px; opacity: .5; flex-shrink: 0; }
.search-input {
  flex: 1; border: none; background: none; color: #ddd;
  font-size: 15px; outline: none;
}
.search-input::placeholder { color: #555; }
.search-clear {
  background: none; border: none; color: #666; font-size: 14px; cursor: pointer;
  padding: 2px 6px; border-radius: 50%; flex-shrink: 0;
}
.search-clear:hover { color: #fff; background: rgba(255,255,255,.08); }

/* ===== 搜索结果全屏页面 ===== */
.search-page {
  padding: 20px 32px; flex: 1; overflow-y: auto;
}
.search-page-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 24px; padding-bottom: 16px;
  border-bottom: 1px solid #2a2a2a;
}
.search-title { font-size: 22px; font-weight: 700; color: #eee; }
.search-stats { font-size: 13px; color: #666; flex: 1; margin-left: 16px; }
.back-btn {
  padding: 8px 20px; border: 1px solid #444; border-radius: 20px;
  background: transparent; color: #aaa; font-size: 13px; cursor: pointer;
}
.back-btn:hover { border-color: #31c27c; color: #31c27c; }

.result-table-header {
  display: grid;
  grid-template-columns: 36px 56px 2fr 1fr 70px 50px;
  padding: 8px 0 12px; border-bottom: 1px solid #2a2a2a;
  color: #555; font-size: 12px; margin-bottom: 4px;
}
.th-index { text-align: center; }
.th-cover { }
.th-album, .th-time { text-align: left; }
.th-actions { text-align: center; }

.result-page-item {
  display: grid;
  grid-template-columns: 36px 56px 2fr 1fr 70px 50px;
  align-items: center;
  padding: 8px 0; border-radius: 8px; transition: .12s;
}
.result-page-item:hover { background: rgba(255,255,255,.04); }
.result-page-item:nth-child(odd) { background: rgba(255,255,255,.012); }
.result-page-item:nth-child(odd):hover { background: rgba(255,255,255,.04); }
.result-page-item.playing { background: rgba(49, 194, 124, .06); }

.rp-index { text-align: center; font-size: 14px; color: #555; }
.equalizer { color: #31c27c; font-size: 12px; letter-spacing: -2px; }

.rp-cover-wrap { display: flex; align-items: center; justify-content: center; }
.rp-cover {
  width: 44px; height: 44px; border-radius: 6px; flex-shrink: 0; cursor: pointer; position: relative;
  background: #2a2a3a;
  display: flex; align-items: center; justify-content: center;
  font-size: 16px; color: rgba(255,255,255,.25);
  background-size: cover; background-position: center;
}
.cover-play-btn {
  position: absolute; inset: 0; border-radius: 6px;
  background: rgba(0,0,0,.55);
  display: flex; align-items: center; justify-content: center;
  font-size: 18px; color: #31c27c; opacity: 0; transition: .15s;
}
.rp-cover:hover .cover-play-btn { opacity: 1; }

.rp-text { display: flex; flex-direction: column; gap: 3px; min-width: 0; cursor: pointer; padding: 4px 0; }
.rp-title { font-size: 14px; color: #ddd; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rp-title.active { color: #31c27c; }
.rp-artist { font-size: 12px; color: #666; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rp-album {
  font-size: 13px; color: #666; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; cursor: pointer;
}
.rp-time { font-size: 13px; color: #555; cursor: pointer; }

.rp-actions { display: flex; justify-content: center; }
.action-btn {
  background: none; border: none; color: #555; font-size: 16px;
  cursor: pointer; padding: 4px 8px; border-radius: 4px; opacity: 0; transition: .15s;
}
.result-page-item:hover .action-btn { opacity: 1; }
.action-btn:hover { color: #31c27c; background: rgba(255,255,255,.06); }

.user-info {
  display: flex; align-items: center; gap: 10px; cursor: pointer; flex-shrink: 0;
}
.user-avatar {
  width: 40px; height: 40px; border-radius: 50%;
  background: #2a2a2a;
  display: flex; align-items: center; justify-content: center;
  font-size: 18px;
}
.user-avatar:hover { background: #333; }
.user-name { font-size: 15px; color: #ccc; }

/* ===== Banner ===== */
.banner {
  position: relative; height: 300px; overflow: hidden;
  margin: 0 32px 32px; border-radius: 14px;
}
.banner-slide {
  position: absolute; inset: 0; opacity: 0; transform: scale(0.96);
  transition: all .6s ease; cursor: pointer;
}
.banner-slide.active { opacity: 1; transform: scale(1); }
.slide-bg {
  width: 100%; height: 100%; border-radius: 14px;
  display: flex; align-items: center; padding: 0 48px;
}
.slide-emoji {
  position: absolute; right: 56px; top: 50%;
  transform: translateY(-50%); font-size: 90px; opacity: .25;
}
.slide-text { position: absolute; left: 48px; top: 50%; transform: translateY(-50%); }
.slide-text h2 {
  font-size: 36px; font-weight: 800; color: #fff; margin-bottom: 8px;
}
.slide-text p { font-size: 17px; color: rgba(255,255,255,.7); }

.banner-dots {
  position: absolute; bottom: 18px; right: 28px; display: flex; gap: 10px;
}
.dot {
  width: 10px; height: 10px; border-radius: 50%;
  background: rgba(255,255,255,.3); cursor: pointer; transition: .2s;
}
.dot.active { background: #31c27c; width: 24px; border-radius: 5px; }

/* ===== Section 通用 ===== */
.section { padding: 0 32px; margin-bottom: 40px; }
.section-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 20px;
}
.section-header h3 { font-size: 20px; font-weight: 700; color: #eee; }
.refresh-btn, .more {
  font-size: 14px; color: #666; cursor: pointer; transition: .2s;
}
.refresh-btn:hover, .more:hover { color: #31c27c; }

/* ===== 推荐歌曲 ===== */
.song-scroll {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 20px;
}
.song-card { cursor: pointer; text-align: center; }
.song-card:hover .play-overlay { opacity: 1; }
.card-cover {
  position: relative; padding-bottom: 100%;
  border-radius: 10px; overflow: hidden; margin-bottom: 10px;
}
.cover-grad {
  position: absolute; inset: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: 42px; color: rgba(255,255,255,.35);
}
.play-overlay {
  position: absolute; bottom: 10px; right: 10px;
  width: 38px; height: 38px; border-radius: 50%;
  background: rgba(49, 194, 124, .85);
  display: flex; align-items: center; justify-content: center;
  font-size: 14px; color: #fff; opacity: 0; transition: .2s;
}
.card-title {
  font-size: 15px; color: #ddd; margin-bottom: 4px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.card-artist {
  font-size: 13px; color: #666;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}

/* ===== 推荐歌单 ===== */
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
