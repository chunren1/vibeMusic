<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { searchSongs, getRandomSongs as apiRandomSongs, playSong as apiPlaySong, getBanners as apiBanners } from '@/api/song'

import { useAuthStore } from '@/stores/auth'
const router = useRouter()
const authStore = useAuthStore()

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
  apiPlaySong(song.sourceId, song.name, song.artist, song.coverUrl || '').then(res => {
    const url = res.data?.url
    if (!url) return
    // 通过 PlayerBar 设置音频源（它管理播放队列）
    if (window.vibeAudioSetSrc) window.vibeAudioSetSrc(url)
    else { audio.src = url; audio.play().catch(() => {}) }
    currentPlaySong.value = song

    window.dispatchEvent(new CustomEvent('song-change', {
      detail: {
        title: song.name,
        artist: song.artist,
        sourceId: song.sourceId,
        coverUrl: song.coverUrl,
        duration: song.duration,
      }
    }))
  }).catch(() => {})
}

// 收藏歌曲（调用后端API）
import { toggleFavorite, getFavoriteIds } from '@/api/song'
const favIds = ref(new Set())

// 初始化时加载收藏列表
getFavoriteIds().then(res => {
  if (res.data) favIds.value = new Set(res.data)
}).catch(() => {})

function toggleFav(song) {
  const isFav = favIds.value.has(song.sourceId)
  if (isFav) {
    favIds.value.delete(song.sourceId)
  } else {
    favIds.value.add(song.sourceId)
  }
  toggleFavorite(song.sourceId, song.name, song.artist, song.coverUrl || '').then(res => {
    if (res.data === true) {
      favIds.value.add(song.sourceId)
    } else {
      favIds.value.delete(song.sourceId)
    }
  }).catch(err => {
    console.error('收藏操作失败:', err)
    if (isFav) {
      favIds.value.add(song.sourceId)
    } else {
      favIds.value.delete(song.sourceId)
    }
  })
}

// 下载歌曲（传完整 song 对象）
import { downloadSong as apiDownload } from '@/api/song'
import PlaylistPopup from '@/components/PlaylistPopup.vue'
const downloadingIds = ref(new Set())
const showPlaylistPopup = ref(false)
const playlistTargetSong = ref(null)
function openPlaylistPopup(song) { playlistTargetSong.value = song; showPlaylistPopup.value = true }
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

// ===== 用户信息 =====
const username = computed(() => authStore.user?.nickname || authStore.user?.username || '未登录')

// ===== Banner 轮播（从网易云获取推荐歌单） =====
const slides = ref([
  { name: '总有一首歌', desc: '让你想起最初的自己', coverUrl: '' },
  { name: '发现好音乐', desc: '从这里开始你的音乐之旅', coverUrl: '' },
  { name: '随机推荐', desc: '听听不一样的声音', coverUrl: '' },
])
const activeSlide = ref(0)
const bannerHover = ref(false)
let bannerTimer = null

function loadBanners() {
  apiBanners().then(res => {
    if (res.data && res.data.length > 0) {
      slides.value = res.data
    }
  }).catch(() => {})
}

function startBanner() {
  stopBanner()
  bannerTimer = setInterval(() => {
    if (slides.value.length) activeSlide.value = (activeSlide.value + 1) % slides.value.length
  }, 4000)
}

function stopBanner() {
  if (bannerTimer) { clearInterval(bannerTimer); bannerTimer = null }
}

function prevBanner() {
  stopBanner()
  activeSlide.value = activeSlide.value <= 0 ? slides.value.length - 1 : activeSlide.value - 1
  if (!bannerHover.value) startBanner()
}

function nextBanner() {
  stopBanner()
  activeSlide.value = (activeSlide.value + 1) % slides.value.length
  if (!bannerHover.value) startBanner()
}

function onBannerEnter() { bannerHover.value = true; stopBanner() }
function onBannerLeave() { bannerHover.value = false; startBanner() }

onMounted(() => {
  loadBanners()
  startBanner()
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

// ===== 搜索下拉框 + 结果页 =====
const searchKeyword = ref('')
const searchResults = ref([])
const searchLoading = ref(false)
const searchFocused = ref(false)
const showDropdown = ref(false)
const showResultPage = ref(false)  // 控制显示全屏结果页

async function doSearch() {
  const keyword = searchKeyword.value.trim()
  if (!keyword) {
    searchResults.value = []
    showDropdown.value = false
    showResultPage.value = false
    return
  }
  searchLoading.value = true
  showDropdown.value = false  // 隐藏下拉
  showResultPage.value = true  // 显示结果页

  try {
    const res = await searchSongs(keyword)
    searchResults.value = res.data || []
  } catch (e) {
    searchResults.value = []
  } finally {
    searchLoading.value = false
  }
}

function onInput() {
  if (searchKeyword.value.trim() === '') {
    searchResults.value = []
    showDropdown.value = false
    showResultPage.value = false
    return
  }
  // 输入时只显示下拉建议，不跳转结果页
  showDropdown.value = true
  showResultPage.value = false
  doSearchSuggest()
}

async function doSearchSuggest() {
  const keyword = searchKeyword.value.trim()
  if (!keyword) return
  searchLoading.value = true
  try {
    const res = await searchSongs(keyword)
    searchResults.value = res.data || []
  } catch (e) {
    searchResults.value = []
  } finally {
    searchLoading.value = false
  }
}

function onBlur() {
  searchFocused.value = false
  setTimeout(() => { showDropdown.value = false }, 200)
}

function onFocus() {
  searchFocused.value = true
  if (searchKeyword.value.trim() && !showResultPage.value) {
    showDropdown.value = true
  }
}

function clearSearch() {
  searchKeyword.value = ''
  searchResults.value = []
  showDropdown.value = false
  showResultPage.value = false
}

function goResultPage() {
  showDropdown.value = false
  showResultPage.value = true
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
        <div class="search-box" :class="{ focused: searchFocused }">
          <span class="search-icon">{{ searchFocused ? '🎵' : '🔍' }}</span>
          <input
            v-model="searchKeyword"
            @focus="onFocus"
            @blur="onBlur"
            @keyup.enter="doSearch"
            @input="onInput"
            placeholder="搜索歌曲"
            class="search-input"
          />
          <button v-if="searchKeyword" class="search-clear" @click.stop="clearSearch">✕</button>
        </div>

        <!-- 搜索结果下拉 -->
        <Transition name="dropdown">
          <div v-if="showDropdown" class="search-dropdown">
            <div v-if="searchLoading" class="drop-loading">搜索中...</div>
            <div v-else-if="searchResults.length === 0" class="drop-empty">未找到相关歌曲</div>
            <div v-else class="drop-list">
              <div
                v-for="(song, idx) in searchResults.slice(0, 5)"
                :key="song.sourceId"
                class="drop-item"
                :class="{ active: currentPlaySong?.sourceId === song.sourceId }"
                @mousedown.prevent="playSong(song); goResultPage()"
              >
                <div
                  class="drop-cover"
                  :style="song.coverUrl ? { backgroundImage: 'url(' + song.coverUrl + '?param=60y60)' } : {}"
                >
                  <span v-if="!song.coverUrl">♪</span>
                  <div class="drop-play-icon">▶</div>
                </div>
                <div class="drop-info">
                  <span class="drop-name" :class="{ hl: currentPlaySong?.sourceId === song.sourceId }">
                    {{ song.name }}
                  </span>
                  <span class="drop-meta">{{ song.artist }}{{ song.album ? ' · ' + song.album : '' }} | {{ formatDuration(song.duration) }}</span>
                </div>
              </div>
              <div class="drop-footer" @mousedown.prevent="goResultPage">
                查看全部结果 →
              </div>
            </div>
          </div>
        </Transition>
      </div>

      <div class="user-info">
        <template v-if="authStore.isLoggedIn">
          <div class="user-avatar">👤</div>
          <span class="user-name">{{ username }}</span>
          <button class="logout-btn" @click="authStore.logout(); $router.push('/')">退出</button>
        </template>
        <button v-else class="login-btn" @click="$router.push('/login')">登录</button>
      </div>
    </div>

    <!-- Banner 轮播 + 下方内容（搜索时隐藏） -->
    <template v-if="!showResultPage">
    <div class="banner" @mouseenter="onBannerEnter" @mouseleave="onBannerLeave">
      <div
        v-for="(slide, idx) in slides"
        :key="idx"
        class="banner-slide"
        :class="{ active: idx === activeSlide }"
        :style="slide.coverUrl ? { backgroundImage: 'linear-gradient(rgba(0,0,0,.3), rgba(0,0,0,.7)), url(' + slide.coverUrl + '?param=800y340)' } : {}"
      >
        <div class="slide-text">
          <h2>{{ slide.name }}</h2>
          <p>{{ slide.desc }}</p>
        </div>
      </div>
      <button class="banner-arrow left" @click.stop="prevBanner">◂</button>
      <button class="banner-arrow right" @click.stop="nextBanner">▸</button>
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

    <!-- 搜索结果全屏页 -->
    <div v-if="showResultPage" class="search-page">
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
              class="action-btn fav-btn"
              :class="{ faved: favIds.has(song.sourceId) }"
              @click.stop="toggleFav(song)"
              :title="favIds.has(song.sourceId) ? '取消收藏' : '收藏'"
            >
              {{ favIds.has(song.sourceId) ? '⭐' : '☆' }}
            </button>
            <button
              class="action-btn download-btn"
              @click.stop="handleDownload(song)"
              :title="downloadingIds.has(song.sourceId) ? '下载中...' : '下载到RustFS'"
            >
              {{ downloadingIds.has(song.sourceId) ? '⏳' : '⬇' }}
            </button>
            <button
              class="action-btn playlist-btn"
              @click.stop="openPlaylistPopup(song)"
              title="加入歌单"
            >
              ➕
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
  <PlaylistPopup
    v-if="showPlaylistPopup"
    :song="playlistTargetSong"
    @close="showPlaylistPopup = false"
    @done="showPlaylistPopup = false"
  />
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
  background: #fff; border-radius: 24px; border: 1px solid #e0e0e0;
  transition: .2s;
}
.search-box:focus-within { border-color: #31c27c; background: #fff; }
.search-icon { font-size: 16px; margin-right: 10px; opacity: .5; flex-shrink: 0; }
.search-input {
  flex: 1; border: none; background: none; color: #333;
  font-size: 15px; outline: none;
}
.search-input::placeholder { color: #bbb; }
.search-clear {
  background: none; border: none; color: #999; font-size: 14px; cursor: pointer;
  padding: 2px 6px; border-radius: 50%; flex-shrink: 0;
}
.search-clear:hover { color: #333; background: rgba(0,0,0,.06); }

.search-box.focused { border-color: #31c27c; background: #fff; }

/* ===== 搜索下拉框 ===== */
.search-dropdown {
  position: absolute; top: 62px; left: 0; right: 0;
  background: #fff; border: 1px solid #e0e0e0;
  border-radius: 12px; overflow: hidden; z-index: 50;
  max-height: 420px; overflow-y: auto;
  box-shadow: 0 8px 32px rgba(0,0,0,.1);
}
.drop-loading, .drop-empty {
  padding: 32px; text-align: center; color: #999; font-size: 14px;
}
.drop-item {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 16px; cursor: pointer; transition: .12s;
}
.drop-item:hover { background: #f0f0f0; }
.drop-item.active { background: rgba(49,194,124,.1); }

.drop-cover {
  width: 42px; height: 42px; border-radius: 6px; flex-shrink: 0; position: relative;
  background: #e0e0e0;
  display: flex; align-items: center; justify-content: center;
  font-size: 14px; color: #999;
  background-size: cover; background-position: center;
}
.drop-play-icon {
  position: absolute; inset: 0; border-radius: 6px;
  background: rgba(0,0,0,.5);
  display: flex; align-items: center; justify-content: center;
  font-size: 14px; color: #31c27c; opacity: 0; transition: .15s;
}
.drop-item:hover .drop-play-icon { opacity: 1; }

.drop-info {
  flex: 1; min-width: 0;
  display: flex; flex-direction: column; gap: 2px;
}
.drop-name { font-size: 14px; color: #333; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.drop-name.hl { color: #31c27c; }
.drop-meta { font-size: 12px; color: #999; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

/* 下拉动画 */
.dropdown-enter-active, .dropdown-leave-active { transition: all .2s ease; }
.dropdown-enter-from, .dropdown-leave-to { opacity: 0; transform: translateY(-8px); }

.drop-footer {
  padding: 12px 16px; text-align: center; color: #31c27c;
  font-size: 13px; cursor: pointer; border-top: 1px solid #eee;
}
.drop-footer:hover { background: rgba(49,194,124,.06); }

/* ===== 搜索结果全屏页 ===== */
.search-page {
  padding: 20px 32px; flex: 1; overflow-y: auto;
}
.search-page-header {
  display: flex; align-items: center; margin-bottom: 20px; padding-bottom: 14px;
  border-bottom: 1px solid #eee;
}
.search-title { font-size: 20px; font-weight: 700; color: #1a1a1a; }
.search-stats { font-size: 13px; color: #777; flex: 1; margin-left: 14px; }
.back-btn {
  padding: 6px 16px; border: 1px solid #ccc; border-radius: 16px;
  background: transparent; color: #777; font-size: 12px; cursor: pointer;
}
.back-btn:hover { border-color: #31c27c; color: #31c27c; }

.result-table-header {
  display: grid;
  grid-template-columns: 36px 56px 2fr 1fr 70px 80px;
  padding: 8px 0 12px; border-bottom: 1px solid #ddd;
  color: #999; font-size: 12px;
}
.th-index { text-align: center; }
.th-actions { text-align: center; }

.result-page-item {
  display: grid;
  grid-template-columns: 36px 56px 2fr 1fr 70px 80px;
  align-items: center; padding: 8px 0; border-radius: 8px; transition: .12s;
}
.result-page-item:hover { background: #f0f0f0; }
.result-page-item:nth-child(odd) { background: #f9f9f9; }
.result-page-item.playing { background: rgba(49,194,124,.08); }
.rp-index { text-align: center; font-size: 14px; color: #999; }
.equalizer { color: #31c27c; font-size: 12px; letter-spacing: -2px; }
.rp-cover-wrap { display: flex; align-items: center; justify-content: center; }
.rp-cover {
  width: 44px; height: 44px; border-radius: 6px; cursor: pointer; position: relative;
  background: #e0e0e0; display: flex; align-items: center; justify-content: center;
  font-size: 16px; color: #999;
  background-size: cover; background-position: center;
}
.cover-play-btn {
  position: absolute; inset: 0; border-radius: 6px;
  background: rgba(0,0,0,.55); display: flex; align-items: center; justify-content: center;
  font-size: 18px; color: #31c27c; opacity: 0; transition: .15s;
}
.rp-cover:hover .cover-play-btn { opacity: 1; }
.rp-text { display: flex; flex-direction: column; gap: 3px; min-width: 0; cursor: pointer; }
.rp-title { font-size: 14px; color: #1a1a1a; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rp-title.active { color: #31c27c; }
.rp-artist { font-size: 12px; color: #777; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rp-album { font-size: 13px; color: #777; cursor: pointer; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rp-time { font-size: 13px; color: #888; cursor: pointer; }
.rp-actions { display: flex; justify-content: center; gap: 2px; }
.action-btn {
  background: none; border: none; color: #555; font-size: 15px;
  cursor: pointer; padding: 4px 6px; border-radius: 4px; opacity: 0; transition: .15s;
}
.result-page-item:hover .action-btn { opacity: 1; }
.action-btn:hover { color: #31c27c; background: rgba(255,255,255,.06); }
.action-btn.faved { color: #f0c040; opacity: 1; }
.action-btn.playlist-btn { color: #31c27c; opacity: 1; }

.user-info {
  display: flex; align-items: center; gap: 10px; cursor: pointer; flex-shrink: 0;
}
.user-avatar {
  width: 40px; height: 40px; border-radius: 50%;
  background: #e8e8e8;
  display: flex; align-items: center; justify-content: center;
  font-size: 18px;
}
.user-avatar:hover { background: #ddd; }
.user-name { font-size: 15px; color: #444; }
.login-btn, .logout-btn {
  padding: 8px 20px; border-radius: 20px; border: 1px solid #31c27c;
  background: transparent; color: #31c27c; font-size: 14px; cursor: pointer; transition: .2s;
  margin-left: 12px;
}
.login-btn:hover { background: #31c27c; color: #fff; }
.logout-btn { border-color: #e0e0e0; color: #999; margin-left: 8px; }
.logout-btn:hover { border-color: #ec4141; color: #ec4141; }

/* ===== Banner ===== */
.banner {
  position: relative; height: 300px; overflow: hidden;
  margin: 0 32px 32px; border-radius: 14px;
}
.banner-slide {
  position: absolute; inset: 0; opacity: 0; transform: scale(0.96);
  transition: all .6s ease; cursor: pointer;
  border-radius: 14px;
  background-size: cover; background-position: center;
}
.banner-slide.active { opacity: 1; transform: scale(1); }
.slide-text { position: absolute; left: 48px; bottom: 32px; }
.slide-text h2 {
  font-size: 36px; font-weight: 800; color: #fff; margin-bottom: 8px;
}
.slide-text p { font-size: 17px; color: rgba(255,255,255,.7); }

.banner-arrow {
  position: absolute; top: 50%; transform: translateY(-50%);
  background: rgba(0,0,0,.4); border: none; border-radius: 50%;
  width: 40px; height: 40px;
  color: #fff; font-size: 20px; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  opacity: 0; transition: .25s; z-index: 2;
}
.banner:hover .banner-arrow { opacity: 1; }
.banner-arrow:hover { background: rgba(0,0,0,.7); }
.banner-arrow.left { left: 12px; }
.banner-arrow.right { right: 12px; }

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
.section-header h3 { font-size: 20px; font-weight: 700; color: #1a1a1a; }
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
  font-size: 15px; color: #1a1a1a; margin-bottom: 4px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.card-artist {
  font-size: 13px; color: #888;
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
  font-size: 15px; color: #1a1a1a;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
  overflow: hidden; line-height: 1.4;
}
</style>
