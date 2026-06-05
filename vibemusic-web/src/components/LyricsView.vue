<script setup>
import { ref, computed, watch, onUnmounted } from 'vue'
import { getLyric } from '@/api/song'

const props = defineProps({
  visible: { type: Boolean, default: false },
  currentSong: { type: Object, default: () => ({}) },
  isPlaying: { type: Boolean, default: false },
  duration: { type: Number, default: 0 }
})

const emit = defineEmits(['update:visible', 'togglePlay', 'prev', 'next', 'seek'])

const lyricsContainer = ref(null)
const lyricsViewEl = ref(null)
const isDragging = ref(false)
const isFullscreen = ref(false)
const loadingLyric = ref(false)

const currentTime = ref(0)
let timeInterval = null

function startTimeSync() {
  stopTimeSync()
  timeInterval = setInterval(() => {
    const a = window.vibeAudio
    if (a) currentTime.value = a.currentTime
  }, 200)
}
function stopTimeSync() {
  if (timeInterval) { clearInterval(timeInterval); timeInterval = null }
}

// 歌词数据
const lyrics = ref([])

async function fetchLyric(sourceId) {
  if (!sourceId) { lyrics.value = []; return }
  loadingLyric.value = true
  try {
    const res = await getLyric(sourceId)
    lyrics.value = res.data || []
    currentLyricIndex.value = 0
    setTimeout(() => scrollToCurrent(), 200)
  } catch {
    lyrics.value = []
  } finally {
    loadingLyric.value = false
  }
}

watch(() => props.visible, (val) => {
  if (val) {
    currentTime.value = window.vibeAudio?.currentTime || 0
    startTimeSync()
    if (props.currentSong.id) {
      fetchLyric(props.currentSong.id)
    }
  } else {
    stopTimeSync()
    exitFullscreen()
  }
})

onUnmounted(() => { stopTimeSync(); exitFullscreen() })

// 当前歌词行
const currentLyricIndex = ref(0)
watch(() => currentTime.value, () => {
  const t = currentTime.value
  let idx = 0
  for (let i = lyrics.value.length - 1; i >= 0; i--) {
    if (t >= parseFloat(lyrics.value[i].time || 0)) { idx = i; break }
  }
  currentLyricIndex.value = idx
})

function scrollToCurrent() {
  if (!lyricsContainer.value || isDragging.value) return
  const items = lyricsContainer.value.querySelectorAll('.lyric-item')
  const el = items[currentLyricIndex.value]
  if (el) {
    const ch = lyricsContainer.value.clientHeight
    lyricsContainer.value.scrollTo({ top: el.offsetTop - ch / 2 + el.offsetHeight / 2, behavior: 'smooth' })
  }
}
watch(() => currentLyricIndex.value, () => scrollToCurrent())

// 全屏
async function toggleFullscreen() {
  if (!isFullscreen.value) {
    try { await lyricsViewEl.value?.requestFullscreen(); isFullscreen.value = true } catch {}
  } else { exitFullscreen() }
}
function exitFullscreen() {
  if (document.fullscreenElement) { document.exitFullscreen().catch(() => {}) }
  isFullscreen.value = false
}
document.addEventListener('fullscreenchange', () => {
  if (!document.fullscreenElement) isFullscreen.value = false
})

function formatTime(s) {
  if (!s || isNaN(s)) return '00:00'
  const m = Math.floor(s / 60), sec = Math.floor(s % 60)
  return m.toString().padStart(2, '0') + ':' + sec.toString().padStart(2, '0')
}

const progressPercent = computed(() => props.duration ? (currentTime.value / props.duration) * 100 : 0)

function onProgressClick(e) {
  const ratio = (e.clientX - e.currentTarget.getBoundingClientRect().left) / e.currentTarget.clientWidth
  const t = ratio * props.duration
  if (window.vibeAudio) { window.vibeAudio.currentTime = t; currentTime.value = t }
}

function close() { emit('update:visible', false) }
let touchStartY = 0
function onTS(e) { touchStartY = e.touches[0].clientY }
function onTM(e) { if (e.touches[0].clientY - touchStartY > 100) close() }
</script>

<template>
  <Teleport to="body">
    <Transition name="lyrics-slide">
      <div v-if="visible" ref="lyricsViewEl" class="lyrics-view" :class="{ fullscreen: isFullscreen }"
        @touchstart="onTS" @touchmove="onTM">

        <!-- 多层背景：封面图模糊 + 深绿渐变叠加 + 纹理 -->
        <div class="bg-layer">
          <div class="bg-cover" :style="{ backgroundImage: `url(${currentSong.coverUrl}?param=600y600)` }"></div>
          <div class="bg-gradient"></div>
          <div class="bg-texture"></div>
          <div class="bg-vignette"></div>
        </div>

        <!-- 顶部导航 -->
        <div class="lyrics-header">
          <button class="nav-btn" @click="close" aria-label="返回">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>
          </button>
          <div class="header-meta">
            <div class="meta-title">{{ currentSong.title || '未播放' }}</div>
            <div class="meta-sub">
              {{ currentSong.artist || '-' }}
              <span v-if="currentSong.artist" class="meta-dot">·</span>
              专辑名
            </div>
          </div>
          <button class="nav-btn" @click="toggleFullscreen" aria-label="全屏">
            <svg v-if="!isFullscreen" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M8 3H5a2 2 0 0 0-2 2v3m18 0V5a2 2 0 0 0-2-2h-3m0 18h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3"/></svg>
            <svg v-else viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M8 3v3a2 2 0 0 1-2 2H3m18 0h-3a2 2 0 0 1-2-2V3m0 18v-3a2 2 0 0 1 2-2h3M3 16h3a2 2 0 0 1 2 2v3"/></svg>
          </button>
        </div>

        <!-- 封面 + 歌词同屏主体 -->
        <div class="lyrics-body">
          <!-- 左侧：黑胶唱片 -->
          <div class="disc-area">
            <div class="disc-player">
              <!-- 唱臂 -->
              <div class="tonearm" :class="{ dropped: isPlaying }">
                <div class="tonearm-base"></div>
                <div class="tonearm-bar"></div>
                <div class="tonearm-head"></div>
              </div>
              <!-- 唱片 -->
              <div class="disc-wrapper" :class="{ playing: isPlaying }">
                <div class="disc-grooves"></div>
                <div class="disc-body">
                  <img :src="currentSong.coverUrl ? currentSong.coverUrl + '?param=400y400' : ''" class="disc-cover" alt="" />
                  <div class="disc-label">
                    <span class="label-text">{{ currentSong.title?.charAt(0) || '♪' }}</span>
                  </div>
                </div>
                <div class="disc-highlight"></div>
              </div>
            </div>
          </div>

          <!-- 右侧：歌词 -->
          <div class="lyrics-area">
            <div ref="lyricsContainer" class="lyrics-list">
              <div v-if="loadingLyric" class="lyric-placeholder">
                <span class="loading-dots">加载歌词中<span class="dots-anim">...</span></span>
              </div>
              <div v-else-if="lyrics.length === 0" class="lyric-placeholder">
                <svg viewBox="0 0 24 24" width="32" height="32" fill="none" stroke="currentColor" stroke-width="1.5" opacity="0.3"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>
                <p>暂无歌词</p>
                <span class="placeholder-hint">请欣赏音乐</span>
              </div>
              <div v-for="(line, i) in lyrics" :key="i"
                class="lyric-item" :class="{ active: i === currentLyricIndex }"
                @click="window.vibeAudio && (window.vibeAudio.currentTime = parseFloat(line.time))">
                <span class="lyric-text">{{ line.text }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 底部播放控制 QQ音乐风格 -->
        <div class="lyrics-footer">
          <div class="ft-controls">
            <button class="ft-btn" @click="$emit('prev')">
              <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M6 6h2v12H6zm3.5 6 8.5 6V6z"/></svg>
            </button>
            <button class="ft-btn ft-play" @click="$emit('togglePlay')">
              <svg v-if="isPlaying" viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/></svg>
              <svg v-else viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><polygon points="8,5 19,12 8,19"/></svg>
            </button>
            <button class="ft-btn" @click="$emit('next')">
              <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/></svg>
            </button>
          </div>
          <div class="ft-progress">
            <span class="ft-time">{{ formatTime(currentTime) }}</span>
            <div class="ft-bar" @click="onProgressClick">
              <div class="ft-fill" :style="{ width: progressPercent + '%' }"></div>
            </div>
            <span class="ft-time">{{ formatTime(duration) }}</span>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
/* ===============================
   BASE LAYOUT
   =============================== */
.lyrics-view {
  position: fixed; inset: 0; z-index: 200;
  display: flex; flex-direction: column; overflow: hidden;
  -webkit-font-smoothing: antialiased;
}

/* ===============================
   MULTI-LAYER BACKGROUND
   =============================== */
.bg-layer { position: absolute; inset: 0; z-index: -1; }
.bg-cover {
  position: absolute; inset: -60px;
  background-size: cover; background-position: center;
  filter: blur(100px) saturate(0.6);
  transform: scale(1.1);
}
.bg-gradient {
  position: absolute; inset: 0;
  background: linear-gradient(
    170deg,
    rgba(22, 38, 28, 0.92) 0%,
    rgba(16, 28, 20, 0.88) 30%,
    rgba(10, 18, 12, 0.94) 60%,
    rgba(8, 14, 10, 0.97) 100%
  );
}
.bg-texture {
  position: absolute; inset: 0; opacity: 0.04;
  background-image: repeating-linear-gradient(
    0deg, transparent, transparent 2px,
    rgba(255,255,255,0.03) 2px, rgba(255,255,255,0.03) 4px
  );
}
.bg-vignette {
  position: absolute; inset: 0;
  background: radial-gradient(ellipse at center, transparent 40%, rgba(0,0,0,0.45) 100%);
}

/* ===============================
   HEADER
   =============================== */
.lyrics-header {
  display: flex; align-items: center;
  padding: 14px 16px 8px; z-index: 10;
  backdrop-filter: blur(4px);
}
.nav-btn {
  width: 36px; height: 36px; border: none;
  background: rgba(255,255,255,0.08);
  border-radius: 50%; color: rgba(255,255,255,0.85);
  cursor: pointer; display: flex; align-items: center; justify-content: center;
  transition: all 0.2s; flex-shrink: 0;
}
.nav-btn:hover { background: rgba(255,255,255,0.15); color: #fff; }
.nav-btn:active { transform: scale(0.92); }

.header-meta { flex: 1; text-align: center; min-width: 0; padding: 0 12px; }
.meta-title {
  font-size: 17px; font-weight: 600; color: #fff;
  letter-spacing: 0.5px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.meta-sub {
  font-size: 12px; color: rgba(255,255,255,0.5); margin-top: 3px;
  letter-spacing: 0.3px;
}
.meta-dot { margin: 0 6px; opacity: 0.4; }

/* ===============================
   BODY: DISC + LYRICS
   =============================== */
.lyrics-body {
  flex: 1; display: flex; align-items: center;
  padding: 0 24px; gap: 32px; overflow: hidden; min-height: 0;
}

/* ------ DISC AREA ------ */
.disc-area {
  width: 44%; max-width: 340px; display: flex; align-items: center; justify-content: center;
  position: relative; z-index: 1;
}
.disc-player {
  position: relative; width: 100%; aspect-ratio: 1;
  max-width: 280px;
}

/* Tonearm */
.tonearm {
  position: absolute; top: -8%; right: -2%; z-index: 5;
  transform-origin: 85% 12%;
  transform: rotate(18deg);
  transition: transform 0.5s cubic-bezier(0.25, 0.1, 0.25, 1);
}
.tonearm.dropped { transform: rotate(6deg); }
.tonearm-base {
  width: 26px; height: 26px; background: radial-gradient(circle at 30% 30%, #555, #222);
  border-radius: 50%; box-shadow: 0 2px 8px rgba(0,0,0,0.5);
}
.tonearm-bar {
  position: absolute; top: 11px; right: 13px;
  width: 72px; height: 3px;
  background: linear-gradient(180deg, #d4d4d4, #888);
  border-radius: 2px;
  transform-origin: right center;
  transform: rotate(-14deg);
  box-shadow: 0 1px 3px rgba(0,0,0,0.4);
}
.tonearm-head {
  position: absolute; top: 0px; left: -8px;
  width: 20px; height: 10px;
  background: linear-gradient(180deg, #ccc, #777);
  border-radius: 2px 2px 6px 1px;
  box-shadow: 0 1px 2px rgba(0,0,0,0.5);
}

/* Disc */
.disc-wrapper {
  position: relative; width: 100%; height: 100%;
  animation: discSpin 20s linear infinite; animation-play-state: paused;
}
.disc-wrapper.playing { animation-play-state: running; }
@keyframes discSpin { to { transform: rotate(360deg); } }

.disc-grooves {
  position: absolute; inset: -3%;
  border-radius: 50%;
  background: conic-gradient(
    from 0deg,
    #1a1a1a 0deg 15deg, #222 15deg 30deg,
    #1a1a1a 30deg 45deg, #222 45deg 60deg,
    #1a1a1a 60deg 75deg, #222 75deg 90deg,
    #1a1a1a 90deg 105deg, #222 105deg 120deg,
    #1a1a1a 120deg 135deg, #222 135deg 150deg,
    #1a1a1a 150deg 165deg, #222 165deg 180deg,
    #1a1a1a 180deg 195deg, #222 195deg 210deg,
    #1a1a1a 210deg 225deg, #222 225deg 240deg,
    #1a1a1a 240deg 255deg, #222 255deg 270deg,
    #1a1a1a 270deg 285deg, #222 285deg 300deg,
    #1a1a1a 300deg 315deg, #222 315deg 330deg,
    #1a1a1a 330deg 345deg, #222 345deg 360deg
  );
  opacity: 0.5; box-shadow: 0 0 60px rgba(0,0,0,0.6), inset 0 0 30px rgba(0,0,0,0.4);
}

.disc-body {
  position: absolute; inset: 7%;
  border-radius: 50%; overflow: hidden;
  box-shadow: 0 4px 30px rgba(0,0,0,0.5);
}
.disc-cover { width: 100%; height: 100%; object-fit: cover; display: block; }
.disc-label {
  position: absolute; inset: 38%;
  border-radius: 50%;
  background: radial-gradient(circle, #e63946 0%, #c1121f 40%, #780000 100%);
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 2px 10px rgba(0,0,0,0.4), inset 0 1px 3px rgba(255,255,255,0.2);
}
.label-text {
  font-size: 14px; font-weight: 700; color: rgba(255,255,255,0.9);
  text-shadow: 0 1px 2px rgba(0,0,0,0.3);
}

.disc-highlight {
  position: absolute; top: 12%; left: 15%; right: 15%; bottom: 50%;
  border-radius: 50%;
  background: linear-gradient(160deg, rgba(255,255,255,0.07) 0%, transparent 70%);
  pointer-events: none;
}

/* ------ LYRICS AREA ------ */
.lyrics-area {
  flex: 1; height: 100%; min-width: 0; overflow: hidden;
  position: relative;
}
.lyrics-area::before, .lyrics-area::after {
  content: ''; position: absolute; left: 0; right: 8px; z-index: 2;
  height: 48px; pointer-events: none;
}
.lyrics-area::before {
  top: 0;
  background: linear-gradient(to bottom, rgba(16,28,20,0.92) 0%, transparent 100%);
}
.lyrics-area::after {
  bottom: 0;
  background: linear-gradient(to top, rgba(8,14,10,0.92) 0%, transparent 100%);
}

.lyrics-list {
  height: 100%; overflow-y: auto; padding: 35% 0;
  scroll-behavior: smooth;
}
.lyrics-list::-webkit-scrollbar { display: none; }

.lyric-item {
  padding: 14px 20px; cursor: pointer;
  transition: all 0.45s cubic-bezier(0.23, 1, 0.32, 1);
  text-align: center;
}
.lyric-text {
  font-size: 16px; line-height: 1.8; color: rgba(255,255,255,0.35);
  transition: all 0.45s cubic-bezier(0.23, 1, 0.32, 1);
  display: inline-block; letter-spacing: 0.8px;
}

.lyric-item.active .lyric-text {
  font-size: 20px; font-weight: 700; color: #31c27c;
  text-shadow: 0 0 20px rgba(49,194,124,0.4), 0 0 40px rgba(49,194,124,0.15);
  transform: scale(1.02);
}

.lyric-item:not(.active):hover .lyric-text {
  color: rgba(255,255,255,0.6);
}

.lyric-placeholder {
  text-align: center; padding-top: 40%; color: rgba(255,255,255,0.3);
  display: flex; flex-direction: column; align-items: center; gap: 10px;
}
.lyric-placeholder p { font-size: 16px; margin: 0; }
.placeholder-hint { font-size: 12px; opacity: 0.5; }

.dots-anim {
  animation: dots 1.5s infinite;
}
@keyframes dots {
  0%, 20% { opacity: 0; }
  50% { opacity: 1; }
  80%, 100% { opacity: 0; }
}

/* ===============================
   FOOTER — QQ Music Compact Style
   =============================== */
.lyrics-footer {
  padding: 6px 28px;
  padding-bottom: max(20px, env(safe-area-inset-bottom, 20px));
  z-index: 10;
}

/* 播放控制行 */
.ft-controls {
  display: flex; align-items: center; justify-content: center; gap: 36px;
  margin-bottom: 10px;
}
.ft-btn {
  border: none; background: none; cursor: pointer; color: #fff;
  padding: 4px; opacity: 0.65; transition: all 0.2s;
  display: flex; align-items: center; justify-content: center;
}
.ft-btn:hover { opacity: 1; }
.ft-btn:active { transform: scale(0.9); }
.ft-btn.ft-play {
  width: 48px; height: 48px; border-radius: 50%; opacity: 1;
  background: linear-gradient(135deg, #31c27c, #27ae60);
  box-shadow: 0 4px 16px rgba(49,194,124,0.3);
}
.ft-btn.ft-play:hover { transform: scale(1.06); }

/* 进度条行 */
.ft-progress {
  display: flex; align-items: center; gap: 10px;
}
.ft-time {
  font-size: 11px; color: rgba(255,255,255,0.5);
  font-variant-numeric: tabular-nums; min-width: 34px; text-align: center;
}
.ft-bar {
  flex: 1; height: 3px; background: rgba(255,255,255,0.12);
  border-radius: 2px; cursor: pointer; transition: height 0.15s;
}
.ft-bar:hover { height: 5px; }
.ft-fill {
  height: 100%; background: linear-gradient(90deg, #31c27c, #5fdd9d);
  border-radius: 2px; transition: width 0.15s linear;
}

/* ===============================
   TRANSITIONS
   =============================== */
.lyrics-slide-enter-active,
.lyrics-slide-leave-active {
  transition: transform 0.45s cubic-bezier(0.32, 0.72, 0, 1);
}
.lyrics-slide-enter-from,
.lyrics-slide-leave-to {
  transform: translateY(100%);
}

/* Fullscreen tweaks */
.lyrics-view.fullscreen .meta-title { font-size: 20px; }
.lyrics-view.fullscreen .lyrics-body { gap: 48px; }
.lyrics-view.fullscreen .disc-area { max-width: 380px; }
.lyrics-view.fullscreen .lyric-text { font-size: 17px; }
.lyrics-view.fullscreen .lyric-item.active .lyric-text { font-size: 22px; }
</style>
