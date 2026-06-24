<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import request, { API_HOST } from '@/api/request'
import { useRouter } from 'vue-router'
import { searchSongs, getBanners as apiBanners, downloadSong as apiDownload } from '@/api/song'
import { useAuthStore } from '@/stores/auth'
import { usePlayerStore } from '@/stores/player'
import { useRecommendStore } from '@/stores/recommend'
import PlaylistPopup from '@/components/PlaylistPopup.vue'
const router = useRouter()
const authStore = useAuthStore()
const playerStore = usePlayerStore()
const recommendStore = useRecommendStore()

// ===== 播放状态（从 player store 同步） =====
const currentPlaySong = computed(() => {
  const s = playerStore.currentSong
  return s.id ? { sourceId: s.id, name: s.title, artist: s.artist, coverUrl: s.coverUrl, duration: s.duration } : null
})
const isPlaying = computed(() => playerStore.isPlaying)

function playSong(song) {
  if (!song.sourceId) return
  playerStore.playSongFromApi(song.sourceId, song.name, song.artist, song.coverUrl || '', song.platform)
}

// 收藏歌曲（调用后端API）
import { useFavoriteStore } from '@/stores/favorite'
const favStore = useFavoriteStore()

function toggleFav(song) {
  favStore.toggleFav(song)
}

// 全屏切换
function toggleFullscreen() {
  if (!document.fullscreenElement) {
    document.documentElement.requestFullscreen().catch(() => {})
  } else {
    document.exitFullscreen().catch(() => {})
  }
}

// ===== 歌单弹窗 =====
const showPlaylistPopup = ref(false)
const playlistTargetSong = ref(null)
function openPlaylistPopup(song) { playlistTargetSong.value = song; showPlaylistPopup.value = true }

// 下载歌曲（走后端 API 存 RustFS + 浏览器下载）
const downloadingIds = ref(new Set())
function handleDownload(song) {
  if (downloadingIds.value.has(song.sourceId)) return
  downloadingIds.value.add(song.sourceId)
  downloadViaBackend(song)
}

function downloadViaBackend(song) {
  apiDownload(song.sourceId, song).then(res => {
    // 后端返回 { fileUrl } → 触发浏览器下载
    const fileUrl = res.data?.fileUrl || `${API_HOST}/api/download/file/${song.sourceId}`
    const a = document.createElement('a')
    a.href = fileUrl
    a.download = `${song.name || song.sourceId}.mp3`
    a.click()
  }).catch(() => {
    // 即便报错也尝试直接下载 RustFS 文件
    const a = document.createElement('a')
    a.href = `${API_HOST}/api/download/file/${song.sourceId}`
    a.download = `${song.name || song.sourceId}.mp3`
    a.click()
  }).finally(() => {
    downloadingIds.value.delete(song.sourceId)
  })
}

// ===== 用户信息 =====
const username = computed(() => authStore.user?.nickname || authStore.user?.username || '未登录')

// ===== Banner 轮播（从网易云获取推荐歌单） =====
const bannerLoading = ref(true)
const slides = ref([
  { name: '总有一首歌', desc: '让你想起最初的自己', coverUrl: '' },
  { name: '发现好音乐', desc: '从这里开始你的音乐之旅', coverUrl: '' },
  { name: '随机推荐', desc: '听听不一样的声音', coverUrl: '' },
])
const activeSlide = ref(0)
const bannerHover = ref(false)
let bannerTimer = null

function loadBanners() {
  bannerLoading.value = true
  apiBanners().then(res => {
    if (res.data && res.data.length > 0) {
      slides.value = res.data
    }
  }).catch(e => console.warn('[HomeView] Banner 加载失败:', e.message))
  .finally(() => { bannerLoading.value = false })
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
  favStore.fetchFavIds()
  loadBanners()
  startBanner()
})
onUnmounted(() => stopBanner())

// ===== 推荐歌曲（从 Store 获取） =====
function shuffleSongs() {
  recommendStore.fetchRecommend(true) // refresh=true 跳过缓存
}

onMounted(() => recommendStore.fetchRecommend())

// ===== 推荐歌单（网易云真实推荐 + 默认卡片兜底） =====
const playlistColors = ['#e84c3d', '#3498db', '#2ecc71', '#f39c12', '#9b59b6', '#1abc9c']
const playlists = ref([])
const loadingRecommend = ref(false)
async function fetchPlaylists() {
  try {
    const res = await request.get('/playlists/recommend')
    const data = res.data || []
    if (data.length > 0) {
      playlists.value = data.map((p, i) => ({
        id: p.id, name: p.name, desc: p.desc,
        coverUrl: p.coverUrl, count: p.count,
        color: playlistColors[i % playlistColors.length],
      }))
      return
    }
  } catch (e) { /* fallback */ }
  // 兜底默认卡片（无真实数据时，点击刷新）
  playlists.value = [
    { name: '华语热门精选', count: 0, color: '#e84c3d', _fallback: true },
    { name: '治愈系纯音乐', count: 0, color: '#3498db', _fallback: true },
    { name: '说唱新世代', count: 0, color: '#2ecc71', _fallback: true },
    { name: '怀旧金曲', count: 0, color: '#f39c12', _fallback: true },
    { name: '民谣在路上', count: 0, color: '#9b59b6', _fallback: true },
    { name: '电竞燃曲BGM', count: 0, color: '#1abc9c', _fallback: true },
  ]
}
async function refreshRecommend() {
  if (loadingRecommend.value) return
  loadingRecommend.value = true
  await fetchPlaylists()
  loadingRecommend.value = false
}
onMounted(() => fetchPlaylists())

// ===== 搜索下拉框 + 结果页 =====
const searchKeyword = ref('')
const searchResults = ref([])
const searchLoading = ref(false)
const searchFocused = ref(false)
const showDropdown = ref(false)
const showResultPage = ref(false)
const sourceFilter = ref('all')
const searchPage = ref(1)
const hasMoreResults = ref(false)
const SEARCH_PAGE_SIZE = 20

async function doSearch(reset = true) {
  const keyword = searchKeyword.value.trim()
  if (!keyword) {
    searchResults.value = []; showDropdown.value = false; showResultPage.value = false; return
  }
  searchLoading.value = true
  showDropdown.value = false
  showResultPage.value = true
  if (reset) { searchPage.value = 1 }

  try {
    const platform = sourceFilter.value === 'all' ? null : sourceFilter.value
    const res = await searchSongs(keyword, searchPage.value, SEARCH_PAGE_SIZE, platform)
    const data = res.data || []
    if (reset) {
      searchResults.value = data
    } else {
      searchResults.value = [...searchResults.value, ...data]
    }
    hasMoreResults.value = data.length >= SEARCH_PAGE_SIZE
  } catch (e) {
    if (reset) searchResults.value = []
  } finally {
    searchLoading.value = false
  }
}

function loadMore() {
  searchPage.value++
  doSearch(false)
}

function onSourceChange() {
  searchPage.value = 1
  doSearch(true)
}

function onInput() {
  if (searchKeyword.value.trim() === '') {
    searchResults.value = []; showDropdown.value = false; showResultPage.value = false; return
  }
  showDropdown.value = true
  showResultPage.value = false
  doSearchSuggest()
}

async function doSearchSuggest() {
  const keyword = searchKeyword.value.trim()
  if (!keyword) return
  searchLoading.value = true
  try {
    const res = await searchSongs(keyword, 1, 8)
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

function addToQueueDesktop(song) {
  if (window.vibeAddToQueue) {
    window.vibeAddToQueue({ sourceId: song.sourceId, name: song.name, artist: song.artist, coverUrl: song.coverUrl, duration: song.duration })
  }
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
          <svg class="search-icon" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
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
                    <span v-if="song.platform" class="tag-platform" :class="song.platform">{{ song.platform === 'qq' ? 'QQ' : '网易云' }}</span>
                  </span>
                  <span class="drop-meta">{{ song.artist }}{{ song.album ? ' · ' + song.album : '' }} | {{ formatDuration(song.duration) }}</span>
                </div>
                <button class="drop-queue-btn" @mousedown.stop="addToQueueDesktop(song)" title="加入队列">+</button>
              </div>
              <div class="drop-footer" @mousedown.prevent="goResultPage">
                查看全部结果 →
              </div>
            </div>
          </div>
        </Transition>
      </div>

      <div class="user-info">
        <button class="topbar-fs" @click="toggleFullscreen" title="全屏">
          <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/></svg>
        </button>
        <template v-if="authStore.isLoggedIn">
          <div class="user-avatar">
            <img v-if="authStore.avatarSrc" :src="authStore.avatarSrc" class="avatar-img" />
            <span v-else>👤</span>
          </div>
          <span class="user-name">{{ username }}</span>
          <button class="logout-btn" @click="authStore.logout(); router.push('/')">退出</button>
        </template>
        <button v-else class="login-btn" @click="authStore.openLogin()">登录</button>
      </div>
    </div>

    <template v-if="!showResultPage">
    <div class="banner" @mouseenter="onBannerEnter" @mouseleave="onBannerLeave">
      <div v-if="bannerLoading" class="banner-skel skeleton"></div>
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

    <section class="section">
      <div class="section-header">
        <h3>推荐歌曲</h3>
        <span class="refresh-btn" @click="shuffleSongs">🔄 换一批</span>
      </div>
      <div v-if="recommendStore.greeting" class="recommend-greeting">{{ recommendStore.greeting }}</div>
      <div v-if="recommendStore.loading" class="recommend-loading">推荐加载中...</div>
      <div v-else class="song-scroll">
        <div
          v-for="song in recommendStore.songs"
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

    <section class="section">
      <div class="section-header">
        <h3>推荐歌单</h3>
        <span class="more" @click="refreshRecommend">{{ loadingRecommend ? '加载中...' : '更多 ›' }}</span>
      </div>
      <div class="playlist-grid">
        <div
          v-for="(pl, idx) in playlists"
          :key="pl.id || idx"
          class="playlist-card"
          @click="pl._fallback ? refreshRecommend() : router.push({ name: 'playlist-detail', params: { source: pl.source || 'netease', id: String(pl.id) } })"
        >
          <div class="pl-cover">
            <img v-if="pl.coverUrl" :src="pl.coverUrl + '?param=200y200'" class="pl-img" />
            <div v-else class="cover-inner" :style="{ background: pl.color }">♪</div>
            <span class="pl-count" v-if="pl.count">{{ pl.count > 10000 ? Math.floor(pl.count/10000)+'万' : pl.count }}</span>
          </div>
          <p class="pl-name">{{ pl.name }}</p>
        </div>
      </div>
    </section>
    </template>

    <div v-if="showResultPage" class="search-page">
      <div class="search-page-header">
        <h2 class="search-title">"{{ searchKeyword }}" 的搜索结果</h2>
        <div class="search-stats">
          <span v-if="searchLoading">搜索中...</span>
          <span v-else-if="searchResults.length === 0">未找到相关歌曲</span>
          <span v-else>共 {{ searchResults.length }} 首</span>
        </div>
        <select v-model="sourceFilter" class="source-select" @change="onSourceChange">
          <option value="all">全部来源</option>
          <option value="netease">网易云</option>
          <option value="qq">QQ音乐</option>
        </select>
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
            <span class="rp-title" :class="{ active: currentPlaySong?.sourceId === song.sourceId }">
              {{ song.name }}
              <span v-if="song.platform" class="tag-platform" :class="song.platform">{{ song.platform === 'qq' ? 'QQ' : '网易云' }}</span>
            </span>
            <span class="rp-artist">{{ song.artist }}</span>
          </div>
          <span class="rp-album" @click="playSong(song)">{{ song.album || '-' }}</span>
          <span class="rp-time" @click="playSong(song)">{{ formatDuration(song.duration) }}</span>
          <div class="rp-actions">
            <button class="action-btn queue-btn" @click.stop="addToQueueDesktop(song)" title="加入队列">+</button>
            <button
              class="action-btn fav-btn"
              :class="{ faved: favStore.isFav(song.sourceId) }"
              @click.stop="toggleFav(song)"
              :title="favStore.isFav(song.sourceId) ? '取消收藏' : '收藏'"
            >
              <svg viewBox="0 0 24 24" width="16" height="16" :fill="favStore.isFav(song.sourceId) ? 'currentColor' : 'none'" stroke="currentColor" stroke-width="2"><polygon points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26"/></svg>
            </button>
            <button
              class="action-btn download-btn"
              @click.stop="handleDownload(song)"
              :title="downloadingIds.has(song.sourceId) ? '下载中...' : '下载到RustFS'"
            >
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
            </button>
            <button
              class="action-btn playlist-btn"
              @click.stop="openPlaylistPopup(song)"
              title="加入歌单"
            >
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
            </button>
          </div>
        </div>
        <div v-if="hasMoreResults" class="load-more-wrap">
          <button class="load-more-btn" @click="loadMore" :disabled="searchLoading">
            {{ searchLoading ? '加载中...' : '加载更多' }}
          </button>
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
.search-icon { margin-right: 10px; opacity: .9; flex-shrink: 0; color: #999; }
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

.dropdown-enter-active, .dropdown-leave-active { transition: all .2s ease; }
.dropdown-enter-from, .dropdown-leave-to { opacity: 0; transform: translateY(-8px); }

.drop-footer {
  padding: 12px 16px; text-align: center; color: #31c27c;
  font-size: 13px; cursor: pointer; border-top: 1px solid #eee;
}
.drop-footer:hover { background: rgba(49,194,124,.06); }

.search-page {
  padding: 20px 32px; flex: 1; overflow-y: auto;
}
.search-page-header {
  display: flex; align-items: center; margin-bottom: 20px; padding-bottom: 14px;
  border-bottom: 1px solid #eee;
}
.search-title { font-size: 20px; font-weight: 700; color: #1a1a1a; }
.search-stats { font-size: 13px; color: #777; flex: 1; margin-left: 14px; }
.source-select {
  padding: 5px 10px; border: 1px solid #d9d9d9; border-radius: 6px;
  font-size: 13px; color: #555; background: #fff; cursor: pointer; outline: none;
}
.source-select:hover { border-color: #31c27c; }
.back-btn {
  padding: 6px 16px; border: 1px solid #ccc; border-radius: 16px;
  background: transparent; color: #777; font-size: 12px; cursor: pointer;
}
.back-btn:hover { border-color: #31c27c; color: #31c27c; }

.load-more-wrap { text-align: center; padding: 20px 0 10px; }
.load-more-btn {
  padding: 8px 36px; border: 1px solid #31c27c; border-radius: 20px;
  background: transparent; color: #31c27c; font-size: 14px; cursor: pointer; outline: none;
}
.load-more-btn:hover { background: #31c27c; color: #fff; }
.load-more-btn:disabled { opacity: 0.5; cursor: default; }

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

/* 平台标签 */
.tag-platform {
  display: inline-block; font-size: 10px; padding: 1px 5px; border-radius: 3px;
  margin-left: 6px; vertical-align: middle; font-weight: 500;
}
.tag-platform.qq { background: #e6f7ff; color: #1890ff; border: 1px solid #91d5ff; }
.tag-platform.netease { background: #fff7e6; color: #fa541c; border: 1px solid #ffd591; }
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
.topbar-fs {
  width: 32px; height: 32px; border: 1px solid #ddd; border-radius: 50%;
  background: #fff; color: #999; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  transition: .15s;
}
.topbar-fs:hover { color: #31c27c; border-color: #31c27c; }
.user-avatar {
  width: 40px; height: 40px; border-radius: 50%;
  background: #e8e8e8; overflow: hidden;
  display: flex; align-items: center; justify-content: center;
  font-size: 18px; flex-shrink: 0;
}
.avatar-img { width: 100%; height: 100%; object-fit: cover; }
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

.banner {
  position: relative; height: 300px; overflow: hidden;
  margin: 0 32px 32px; border-radius: 14px;
}
.banner-skel {
  position: absolute; inset: 0; border-radius: 14px;
  background: linear-gradient(90deg, #eee 25%, #f0f0f0 50%, #eee 75%);
  background-size: 200% 100%;
  animation: shimmer 1.5s ease-in-out infinite;
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

.section { padding: 0 32px; margin-bottom: 40px; }
.section-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 20px;
}
.section-header h3 { font-size: 20px; font-weight: 700; color: #1a1a1a; }
.recommend-greeting { font-size: 13px; color: #31c27c; margin-bottom: 12px; }
.recommend-loading { text-align: center; color: #999; font-size: 14px; padding: 32px 0; }
.refresh-btn, .more {
  font-size: 14px; color: #666; cursor: pointer; transition: .2s;
}
.refresh-btn:hover, .more:hover { color: #31c27c; }

.song-scroll {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 20px;
}
.song-card {
  cursor: pointer; text-align: center;
  border-radius: 16px; padding: 12px;
  transition: transform .25s cubic-bezier(0.34, 1.56, 0.64, 1), box-shadow .25s ease;
}
.song-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 8px 24px rgba(0,0,0,.15);
}
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

.playlist-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 20px;
}
.playlist-card {
  cursor: pointer; border-radius: 16px; padding: 12px;
  transition: transform .25s cubic-bezier(0.34, 1.56, 0.64, 1), box-shadow .25s ease;
}
.playlist-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 8px 24px rgba(0,0,0,.15);
}
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
.pl-img {
  position: absolute; inset: 0;
  width: 100%; height: 100%; object-fit: cover;
  border-radius: 10px;
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
