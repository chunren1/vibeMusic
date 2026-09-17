<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { getBanners as apiBanners } from '@/api/song'

const bannerLoading = ref(true)
const slides = ref([
  { name: '发现好音乐', desc: '热门新歌每日更新', coverUrl: '' },
  { name: '每日推荐', desc: '30 首精选好歌', coverUrl: '' },
  { name: '随机推荐', desc: '换一批试试', coverUrl: '' },
])
const activeSlide = ref(0)
const bannerHover = ref(false)
let bannerTimer = null

// 首图 LCP：改为 <img> 预加载更可靠，CSS background 的 preload 关联弱，易报 invalid href / not used
// 保留空实现以兼容旧调用，实际预加载由 <img> 的 fetchpriority 高优先级完成
function preloadFirstBanner(url) {
  if (!url || typeof document === 'undefined') return
  // 校验 URL 合法性，仅用于打点，不再注入 <link rel=preload>（避免 invalid href / not used 告警）
  try {
    const u = new URL(url, window.location.origin)
    if (!u.hostname) return
    // 可选：用 Image 预取代替 link preload，更贴合 CSS background 的实际加载
    const img = new Image()
    img.decoding = 'async'
    img.src = u.href + (u.search ? '&' : '?') + 'param=1600y900'
  } catch {
    return
  }
}

function loadBanners() {
  bannerLoading.value = true
  apiBanners().then(res => {
    if (res.data && res.data.length > 0) {
      slides.value = res.data
      preloadFirstBanner(slides.value[0]?.coverUrl)
    }
  }).catch(e => console.warn('[Banner] 加载失败:', e.message))
  .finally(() => { bannerLoading.value = false })
}

function startBanner() {
  stopBanner()
  bannerTimer = setInterval(() => {
    if (slides.value.length) activeSlide.value = (activeSlide.value + 1) % slides.value.length
  }, 4000)
}

function stopBanner() { if (bannerTimer) { clearInterval(bannerTimer); bannerTimer = null } }
function prevBanner() { stopBanner(); activeSlide.value = activeSlide.value <= 0 ? slides.value.length - 1 : activeSlide.value - 1; if (!bannerHover.value) startBanner() }
function nextBanner() { stopBanner(); activeSlide.value = (activeSlide.value + 1) % slides.value.length; if (!bannerHover.value) startBanner() }
function onEnter() { bannerHover.value = true; stopBanner() }
function onLeave() { bannerHover.value = false; startBanner() }

onMounted(() => { loadBanners(); startBanner() })
onUnmounted(() => stopBanner())
</script>

<template>
  <div class="banner" @mouseenter="onEnter" @mouseleave="onLeave">
    <div v-if="bannerLoading" class="banner-skel skeleton"></div>
    <div v-for="(slide, idx) in slides" :key="idx" class="banner-slide" :class="{ active: idx === activeSlide }"
      :style="idx === activeSlide && slide.coverUrl ? { backgroundImage: 'url(' + slide.coverUrl + '?param=1600y900)' } : {}">
      <div class="slide-text"><h2>{{ slide.name }}</h2><p>{{ slide.desc }}</p></div>
    </div>
    <button class="banner-arrow left" @click.stop="prevBanner" aria-label="上一张"><SvgIcon name="chevron-left" /></button>
    <button class="banner-arrow right" @click.stop="nextBanner" aria-label="下一张"><SvgIcon name="chevron-right" /></button>
    <div class="banner-dots">
      <span v-for="(_, idx) in slides" :key="idx" class="dot" :class="{ active: idx === activeSlide }" @click="activeSlide = idx"></span>
    </div>
  </div>
</template>

<style scoped>
.banner { position: relative; height: min(360px, 30vw); overflow: hidden; margin: 0 32px 32px; border-radius: 14px; }
.banner-skel { position: absolute; inset: 0; border-radius: 14px; background: linear-gradient(90deg, var(--bg-card) 25%, var(--bg-hover) 50%, var(--bg-card) 75%); background-size: 200% 100%; animation: shimmer 1.5s ease-in-out infinite; }
.banner-slide { position: absolute; inset: 0; opacity: 0; transform: scale(0.96); transition: opacity .6s ease, transform .6s ease; cursor: pointer; border-radius: 14px; background-size: cover; background-position: 35% center; }
.banner-slide.active { opacity: 1; transform: scale(1); }
.slide-text { position: absolute; left: 48px; bottom: 32px; }
.slide-text h2 { font-size: 36px; font-weight: 800; color: #fff; margin-bottom: 8px; }
.slide-text p { font-size: 17px; color: rgba(255,255,255,.7); }
.banner-arrow { position: absolute; top: 50%; transform: translateY(-50%); background: rgba(0,0,0,.4); border: none; border-radius: 50%; width: 40px; height: 40px; color: #fff; font-size: 20px; cursor: pointer; display: flex; align-items: center; justify-content: center; opacity: 0; transition: .25s; z-index: 2; }
.banner:hover .banner-arrow { opacity: 1; }
.banner-arrow:hover { background: rgba(0,0,0,.7); }
.banner-arrow.left { left: 12px; }
.banner-arrow.right { right: 12px; }
.banner-dots { position: absolute; bottom: 18px; right: 28px; display: flex; gap: 10px; }
.dot { width: 10px; height: 10px; border-radius: 50%; background: rgba(255,255,255,.3); cursor: pointer; transition: .2s; }
.dot.active { background: #31c27c; width: 24px; border-radius: 5px; }
</style>
