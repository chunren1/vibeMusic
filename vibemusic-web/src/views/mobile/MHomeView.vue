<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { getBanners as apiBanners } from '@/api/song'
import { useAuthStore } from '@/stores/auth'
import { usePlayerStore } from '@/stores/player'
import { useRecommendStore } from '@/stores/recommend'

const router = useRouter()
const authStore = useAuthStore()
const player = usePlayerStore()
const recommendStore = useRecommendStore()

// ===== Play =====
function playSong(song) {
  if (!song.sourceId) return
  player.playSongFromApi(song.sourceId, song.name, song.artist, song.coverUrl || '')
  router.push('/m/player')
}

// ===== Search =====
const searchKeyword = ref('')

function doSearch() {
  const kw = searchKeyword.value.trim()
  if (!kw) return
  router.push({ name: 'm-search', query: { q: kw } })
}

function focusSearch() {
  // 跳转到搜索页，由搜索页的 autofocus 自动聚焦输入框
  router.push('/m/search')
}

// 全屏切换
const isFullscreen = ref(false)
function toggleFullscreen() {
  if (!document.fullscreenElement) {
    document.documentElement.requestFullscreen().then(() => isFullscreen.value = true)
  } else {
    document.exitFullscreen().then(() => isFullscreen.value = false)
  }
}
document.addEventListener('fullscreenchange', () => {
  isFullscreen.value = !!document.fullscreenElement
})

// ===== Banners =====
const slides = ref([{ name: '发现好音乐', desc: '从这里开始', coverUrl: '' }])
const activeSlide = ref(0)
let bannerTimer = null
let touchStartX = 0

function loadBanners() {
  apiBanners().then(res => {
    if (res.data?.length) slides.value = res.data
  }).catch(() => {})
}

function prevBanner() {
  if (!slides.value.length) return
  activeSlide.value = activeSlide.value <= 0 ? slides.value.length - 1 : activeSlide.value - 1
  resetBannerTimer()
}
function nextBanner() {
  if (!slides.value.length) return
  activeSlide.value = (activeSlide.value + 1) % slides.value.length
  resetBannerTimer()
}
function onTouchStart(e) {
  touchStartX = e.touches[0].clientX
}
function onTouchEnd(e) {
  const diff = e.changedTouches[0].clientX - touchStartX
  if (Math.abs(diff) > 50) {
    diff > 0 ? prevBanner() : nextBanner()
  }
}
function resetBannerTimer() {
  clearInterval(bannerTimer)
  bannerTimer = setInterval(nextBanner, 4000)
}

onMounted(() => {
  loadBanners()
  resetBannerTimer()
  recommendStore.fetchRecommend()
  document.addEventListener('visibilitychange', () => {
    document.hidden ? clearInterval(bannerTimer) : resetBannerTimer()
  })
})
onUnmounted(() => {
  clearInterval(bannerTimer)
})

function shuffleSongs() {
  recommendStore.fetchRecommend(true) // refresh=true 跳过缓存
}

</script>

<template>
  <div class="m-home">
    <!-- 顶部：搜索框 + 用户 -->
    <div class="m-top">
      <div class="m-search-bar" @click="focusSearch">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        <span class="m-search-placeholder">搜索歌曲...</span>
      </div>
      <div class="m-user" @click="authStore.isLoggedIn ? null : authStore.openLogin()">
        <span class="m-user-avatar">{{ authStore.user?.nickname?.[0] || authStore.user?.username?.[0] || '?' }}</span>
      </div>
      <button class="m-fs-btn" @click.stop="toggleFullscreen" :title="isFullscreen ? '退出全屏' : '全屏'">
        <svg v-if="!isFullscreen" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/></svg>
        <svg v-else viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="4 8 4 3 9 3"/><polyline points="20 16 20 21 15 21"/><line x1="4" y1="3" x2="11" y2="10"/><line x1="20" y1="21" x2="13" y2="14"/></svg>
      </button>
    </div>

    <!-- Banner -->
    <div v-if="slides.length" class="m-banner" @touchstart="onTouchStart" @touchend="onTouchEnd" @click="router.push('/m/search')">
      <div
        v-for="(s, i) in slides" :key="i"
        class="m-banner-slide" :class="{ active: i === activeSlide }"
        :style="s.coverUrl ? { backgroundImage: `url(${s.coverUrl}?param=800y400)` } : {}"
      >
        <div class="m-banner-mask">
          <div class="m-banner-title">{{ s.name }}</div>
          <div class="m-banner-sub">{{ s.desc }}</div>
        </div>
      </div>
      <!-- 左右箭头 -->
      <button class="m-banner-arrow left" @click.stop="prevBanner">‹</button>
      <button class="m-banner-arrow right" @click.stop="nextBanner">›</button>
      <!-- 指示点 -->
      <div class="m-banner-dots">
        <span v-for="(s, i) in slides" :key="i" class="m-dot" :class="{ on: i === activeSlide }"></span>
      </div>
    </div>

    <!-- 推荐歌曲 -->
    <div class="m-section">
      <div class="m-section-header">
        <h3 class="m-section-title">为你推荐</h3>
        <button class="m-shuffle-btn" @click="shuffleSongs">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><polyline points="16 3 21 3 21 8"/><line x1="4" y1="20" x2="21" y2="3"/><polyline points="21 16 21 21 16 21"/><line x1="15" y1="15" x2="21" y2="21"/><line x1="4" y1="4" x2="9" y2="9"/></svg>
          换一批
        </button>
      </div>
      <div v-if="recommendStore.greeting" class="m-section-greeting">{{ recommendStore.greeting }}</div>
      <!-- 骨架屏 -->
      <div v-if="recommendStore.loading" class="m-skeleton-list">
        <div v-for="i in 5" :key="i" class="m-skel-item">
          <div class="skeleton skeleton-circle" style="width:44px;height:44px"></div>
          <div class="m-skel-text">
            <div class="skeleton" style="width:60%;height:14px;margin-bottom:6px"></div>
            <div class="skeleton" style="width:40%;height:12px"></div>
          </div>
        </div>
      </div>
      <div v-else class="m-song-list">
        <div v-for="(song, idx) in recommendStore.songs" :key="song.sourceId || idx"
          class="m-song-item tap-scale"
          :class="{ playing: player.currentSong.id === song.sourceId && player.isPlaying }"
          @click="playSong(song)">
          <div class="m-song-cover"
            v-lazy-img:bg="song.coverUrl ? `${song.coverUrl}?param=80y80` : null"
            :style="!song.coverUrl ? { background: song.coverColor } : {}">
            <span v-if="player.currentSong.id === song.sourceId && player.isPlaying" class="m-eq">
              <span class="eq-bar"></span><span class="eq-bar"></span><span class="eq-bar"></span>
            </span>
          </div>
          <div class="m-song-info">
            <div class="m-song-name">{{ song.name }}</div>
            <div class="m-song-artist">{{ song.artist }}</div>
          </div>
          <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor" class="m-play-icon"><polygon points="6,4 20,12 6,20"/></svg>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.m-home { padding: 0 16px 12px; }

/* 顶部栏 */
.m-top {
  display: flex; align-items: center; gap: 12px; padding: 12px 0 8px;
  position: sticky; top: 0; z-index: 10; background: #0a0a0a;
}
.m-search-bar {
  flex: 1; display: flex; align-items: center; gap: 8px;
  background: rgba(255,255,255,0.08); border-radius: 20px; padding: 8px 14px;
  color: #888;
}
.m-search-input {
  flex: 1; border: none; background: none; color: #ccc; font-size: 14px; outline: none;
}
.m-search-placeholder {
  flex: 1; color: #666; font-size: 14px; pointer-events: none;
}
.m-user-avatar {
  width: 32px; height: 32px; border-radius: 50%;
  background: rgba(255,255,255,0.1); display: flex; align-items: center; justify-content: center;
  font-size: 14px; color: #ccc; cursor: pointer;
}
.m-fs-btn {
  width: 30px; height: 30px; border: none; border-radius: 50%;
  background: rgba(255,255,255,0.08); color: #888;
  display: flex; align-items: center; justify-content: center;
  cursor: pointer; flex-shrink: 0;
}
.m-fs-btn:active { background: rgba(255,255,255,0.15); color: #ccc; }

/* Banner */
.m-banner {
  position: relative; height: 140px; border-radius: 12px; overflow: hidden; margin-bottom: 16px;
}
.m-banner-slide {
  position: absolute; inset: 0;
  background-color: #1a1a2e;
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  opacity: 0; transition: opacity .5s;
  will-change: opacity; transform: translateZ(0);
  backface-visibility: hidden;
}
.m-banner-slide.active { opacity: 1; }
.m-banner-mask {
  position: absolute; inset: 0; background: linear-gradient(0deg, rgba(0,0,0,.6), transparent);
  display: flex; flex-direction: column; justify-content: flex-end; padding: 16px;
}
.m-banner-title { font-size: 18px; font-weight: 600; color: #fff; }
.m-banner-sub { font-size: 12px; color: rgba(255,255,255,.6); margin-top: 4px; }
.m-banner-dots {
  position: absolute; bottom: 10px; right: 12px; display: flex; gap: 5px;
}
.m-dot { width: 6px; height: 6px; border-radius: 50%; background: rgba(255,255,255,.3); }
.m-dot.on { background: #31c27c; width: 16px; border-radius: 3px; }
.m-banner-arrow {
  position: absolute; top: 50%; transform: translateY(-50%);
  border: none; background: rgba(0,0,0,0.3); color: #fff; font-size: 28px;
  width: 32px; height: 32px; border-radius: 50%; cursor: pointer;
  display: flex; align-items: center; justify-content: center; z-index: 2;
  opacity: 0; transition: opacity .2s;
}
.m-banner:hover .m-banner-arrow { opacity: 1; }
.m-banner-arrow.left { left: 8px; }
.m-banner-arrow.right { right: 8px; }

/* 推荐 */
.m-section { margin-bottom: 16px; }
.m-section-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.m-section-title { font-size: 16px; font-weight: 600; color: #e0e0e0; }
.m-shuffle-btn {
  border: 1px solid rgba(255,255,255,0.12); border-radius: 14px;
  background: transparent; color: #888; font-size: 12px; padding: 4px 10px;
  cursor: pointer; display: flex; align-items: center; gap: 4px;
}
.m-shuffle-btn:active { background: rgba(255,255,255,0.04); }
.m-section-greeting { font-size: 12px; color: #31c27c; margin-bottom: 10px; }
.m-section-loading { text-align: center; color: #666; font-size: 13px; padding: 24px 0; }
.m-skeleton-list { display: flex; flex-direction: column; gap: 2px; }
.m-skel-item {
  display: flex; align-items: center; gap: 10px; padding: 10px 8px;
}
.m-skel-text { flex: 1; }
.m-song-list { display: flex; flex-direction: column; gap: 2px; }
.m-song-item {
  display: flex; align-items: center; gap: 12px; padding: 10px 8px;
  border-radius: 10px; transition: background .15s;
}
.m-song-item:active { background: rgba(255,255,255,.04); }
.m-song-item.playing { background: rgba(49,194,124,.08); }
.m-song-cover {
  width: 44px; height: 44px; border-radius: var(--m-radius-sm); flex-shrink: 0;
  display: flex; align-items: center; justify-content: center; color: #fff; font-size: 13px;
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  background-color: #1a1a2e;
}
.m-eq { display: flex; align-items: flex-end; gap: 2px; height: 16px; }
.m-eq .eq-bar { width: 3px; }
.m-eq .eq-bar:nth-child(1) { height: 8px; }
.m-eq .eq-bar:nth-child(2) { height: 14px; }
.m-eq .eq-bar:nth-child(3) { height: 6px; }
.m-song-info { flex: 1; min-width: 0; }
.m-song-name { font-size: 14px; color: #e0e0e0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.m-song-artist { font-size: 12px; color: #888; margin-top: 2px; }
.m-play-icon { color: #888; flex-shrink: 0; opacity: 0.6; }
.m-song-item:active .m-play-icon { color: #31c27c; opacity: 1; }
.m-song-item.playing .m-play-icon { color: #31c27c; opacity: 1; }
</style>
