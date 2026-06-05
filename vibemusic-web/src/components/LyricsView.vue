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
const isFullscreen = ref(false)
const loadingLyric = ref(false)

const currentTime = ref(0)
const volume = ref(70)
const isMuted = ref(false)
let timeInterval = null

function startTimeSync() {
  stopTimeSync()
  timeInterval = setInterval(() => {
    const a = window.vibeAudio
    if (a) { currentTime.value = a.currentTime; volume.value = Math.round(a.volume * 100); isMuted.value = a.muted }
  }, 200)
}
function stopTimeSync() { if (timeInterval) { clearInterval(timeInterval); timeInterval = null } }

function toggleMute() {
  isMuted.value = !isMuted.value
  if (window.vibeAudio) { window.vibeAudio.muted = isMuted.value; if (!isMuted.value && volume.value === 0) { volume.value = 70; window.vibeAudio.volume = 0.7 } }
}
function onVolumeClick(e) {
  const v = Math.round((e.offsetX / e.target.offsetWidth) * 100)
  volume.value = Math.max(0, Math.min(100, v))
  if (window.vibeAudio) { window.vibeAudio.volume = volume.value / 100; window.vibeAudio.muted = false; isMuted.value = false }
}

// 歌词
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
    volume.value = Math.round((window.vibeAudio?.volume || 1) * 100)
    isMuted.value = window.vibeAudio?.muted || false
    startTimeSync()
    if (props.currentSong.id) fetchLyric(props.currentSong.id)
  } else {
    stopTimeSync()
    exitFullscreen()
  }
})

onUnmounted(() => { stopTimeSync(); exitFullscreen() })

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
  if (!lyricsContainer.value) return
  const items = lyricsContainer.value.querySelectorAll('.lyric-item')
  const el = items[currentLyricIndex.value]
  if (el) {
    const ch = lyricsContainer.value.clientHeight
    lyricsContainer.value.scrollTo({ top: el.offsetTop - ch / 2 + el.offsetHeight / 2, behavior: 'smooth' })
  }
}
watch(() => currentLyricIndex.value, () => scrollToCurrent())

async function toggleFullscreen() {
  if (!isFullscreen.value) {
    try { await lyricsViewEl.value?.requestFullscreen(); isFullscreen.value = true } catch {}
  } else { exitFullscreen() }
}
function exitFullscreen() {
  if (document.fullscreenElement) { document.exitFullscreen().catch(() => {}) }
  isFullscreen.value = false
}
document.addEventListener('fullscreenchange', () => { if (!document.fullscreenElement) isFullscreen.value = false })

function formatTime(s) {
  if (!s || isNaN(s)) return '00:00'
  const m = Math.floor(s / 60), sec = Math.floor(s % 60)
  return m.toString().padStart(2, '0') + ':' + sec.toString().padStart(2, '0')
}

const progressPercent = computed(() => props.duration ? (currentTime.value / props.duration) * 100 : 0)

function onProgressClick(e) {
  const ratio = (e.offsetX || (e.clientX - e.currentTarget.getBoundingClientRect().left)) / e.currentTarget.clientWidth
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
    <Transition name="slide-up">
      <div v-if="visible" ref="lyricsViewEl" class="player" :class="{ fs: isFullscreen }"
        @touchstart="onTS" @touchmove="onTM">

        <!-- 背景层 -->
        <div class="bg-stack">
          <div class="bg-img" :style="{ backgroundImage: `url(${currentSong.coverUrl}?param=600y600)` }"></div>
          <div class="bg-grad"></div>
          <div class="bg-noise"></div>
        </div>

        <!-- 头部 -->
        <header class="top-bar">
          <button class="icon-btn" @click="close">
            <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><polyline points="15 18 9 12 15 6"/></svg>
          </button>
          <div class="top-meta">
            <h1 class="top-title">{{ currentSong.title || '未播放' }}</h1>
            <p class="top-sub">{{ currentSong.artist || '-' }}</p>
          </div>
          <button class="icon-btn" @click="toggleFullscreen">
            <svg v-if="!isFullscreen" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><path d="M8 3H5a2 2 0 0 0-2 2v3m18 0V5a2 2 0 0 0-2-2h-3m0 18h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3"/></svg>
            <svg v-else viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><path d="M8 3v3a2 2 0 0 1-2 2H3m18 0h-3a2 2 0 0 1-2-2V3m0 18v-3a2 2 0 0 1 2-2h3M3 16h3a2 2 0 0 1 2 2v3"/></svg>
          </button>
        </header>

        <!-- 主体：碟片 + 歌词 -->
        <main class="main-area">
          <!-- 左侧：透明中心碟片 -->
          <div class="disc-panel">
            <div class="turntable">
              <div class="tonearm-arm" :class="{ on: isPlaying }">
                <div class="t-base"></div>
                <div class="t-bar"></div>
                <div class="t-head"></div>
              </div>

              <div class="vinyl" :class="{ spinning: isPlaying }">
                <!-- 外层唱片环 -->
                <div class="vinyl-ring"></div>
                <!-- 透明中心 — 封面直接可见 -->
                <div class="vinyl-glass">
                  <img :src="currentSong.coverUrl ? currentSong.coverUrl + '?param=400y400' : ''" class="vinyl-img" alt="" />
                </div>
                <!-- 光泽扫过 -->
                <div class="vinyl-sheen"></div>
              </div>
            </div>
            <!-- 封面下方发光点缀 -->
            <div class="disc-glow"></div>
          </div>

          <!-- 右侧：歌词 -->
          <div class="lyric-panel">
            <div ref="lyricsContainer" class="lyric-scroll">
              <div v-if="loadingLyric" class="lyric-hint">加载中…</div>
              <div v-else-if="lyrics.length === 0" class="lyric-hint">
                <p>暂无歌词</p>
                <span>请欣赏音乐</span>
              </div>
              <div v-for="(line, i) in lyrics" :key="i"
                class="lyric-line" :class="{ on: i === currentLyricIndex }"
                @click="window.vibeAudio && (window.vibeAudio.currentTime = parseFloat(line.time))">
                {{ line.text }}
              </div>
            </div>
          </div>
        </main>

        <!-- 底部栏 -->
        <footer class="ctrl-bar">
          <!-- 进度条 -->
          <div class="seek-row">
            <span class="t">{{ formatTime(currentTime) }}</span>
            <div class="seek-track" @click="onProgressClick">
              <div class="seek-fill" :style="{ width: progressPercent + '%' }">
                <div class="seek-knob"></div>
              </div>
            </div>
            <span class="t">{{ formatTime(duration) }}</span>
          </div>

          <!-- 控制 + 音量 -->
          <div class="action-row">
            <div class="actions-left">
              <button class="act-btn" @click="$emit('prev')">
                <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M6 6h2v12H6zm3.5 6 8.5 6V6z"/></svg>
              </button>
              <button class="act-btn play" @click="$emit('togglePlay')">
                <svg v-if="isPlaying" viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/></svg>
                <svg v-else viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><polygon points="8,5 19,12 8,19"/></svg>
              </button>
              <button class="act-btn" @click="$emit('next')">
                <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/></svg>
              </button>
            </div>

            <!-- 音量 -->
            <div class="vol-group">
              <button class="act-btn mute" @click="toggleMute">
                <svg v-if="isMuted || volume === 0" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><line x1="23" y1="9" x2="17" y2="15"/><line x1="17" y1="9" x2="23" y2="15"/></svg>
                <svg v-else viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><path d="M15.54 8.46a5 5 0 0 1 0 7.07"/></svg>
              </button>
              <div class="vol-track" @click="onVolumeClick">
                <div class="vol-fill" :style="{ width: (isMuted ? 0 : volume) + '%' }"></div>
              </div>
            </div>
          </div>
        </footer>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
/* ============================
   BASE
   ============================ */
.player {
  position: fixed; inset: 0; z-index: 200;
  display: flex; flex-direction: column; overflow: hidden;
  -webkit-font-smoothing: antialiased; user-select: none;
}

/* ============================
   BACKGROUND — Deep Green Gradient
   ============================ */
.bg-stack { position: absolute; inset: 0; z-index: -1; }
.bg-img {
  position: absolute; inset: -80px;
  background-size: cover; background-position: center;
  filter: blur(120px) saturate(0.5) brightness(0.35);
  transform: scale(1.15);
}
.bg-grad {
  position: absolute; inset: 0;
  background: linear-gradient(175deg,
    #12261a 0%, #0e2016 25%, #0a1810 50%, #08120c 75%, #060e0a 100%);
}
.bg-noise {
  position: absolute; inset: 0; opacity: 0.03;
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)' opacity='1'/%3E%3C/svg%3E");
}

/* ============================
   TOP BAR
   ============================ */
.top-bar {
  display: flex; align-items: center;
  padding: 12px 14px 4px; z-index: 10;
}
.icon-btn {
  width: 38px; height: 38px; border: none;
  background: rgba(255,255,255,0.06); border-radius: 50%;
  color: rgba(255,255,255,0.8); cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  transition: all .2s; flex-shrink: 0;
}
.icon-btn:hover { background: rgba(255,255,255,0.12); color: #fff; }
.icon-btn:active { transform: scale(0.9); }
.top-meta { flex: 1; text-align: center; min-width: 0; padding: 0 10px; }
.top-title {
  font-size: 16px; font-weight: 600; color: #fff;
  letter-spacing: .4px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.top-sub {
  font-size: 11px; color: rgba(255,255,255,0.45); margin-top: 2px;
}

/* ============================
   MAIN: DISC + LYRICS
   ============================ */
.main-area {
  flex: 1; display: flex; align-items: center; overflow: hidden;
  padding: 0 20px; gap: 24px;
}

/* ------ DISC ------ */
.disc-panel {
  width: 44%; max-width: 340px; display: flex;
  flex-direction: column; align-items: center; justify-content: center;
  position: relative;
}
.turntable {
  position: relative; width: 100%; aspect-ratio: 1; max-width: 260px;
}
/* 唱臂 */
.tonearm-arm {
  position: absolute; top: -6%; right: -4%; z-index: 5;
  transform-origin: 80% 14%; transform: rotate(20deg);
  transition: transform .5s cubic-bezier(.25,.1,.25,1);
}
.tonearm-arm.on { transform: rotate(7deg); }
.t-base {
  width: 22px; height: 22px; background: radial-gradient(circle at 35% 35%, #666, #1a1a1a);
  border-radius: 50%; box-shadow: 0 2px 6px rgba(0,0,0,.6);
}
.t-bar {
  position: absolute; top: 8px; right: 11px; width: 64px; height: 3px;
  background: linear-gradient(180deg, #c0c0c0, #777); border-radius: 2px;
  transform-origin: right; transform: rotate(-15deg);
  box-shadow: 0 1px 2px rgba(0,0,0,.5);
}
.t-head {
  position: absolute; top: 0; left: -12px; width: 18px; height: 9px;
  background: linear-gradient(180deg, #aaa, #666); border-radius: 1px 1px 5px 1px;
  box-shadow: 0 1px 2px rgba(0,0,0,.6);
}

/* 透明中心唱片 */
.vinyl {
  position: relative; width: 100%; height: 100%;
  animation: spin 20s linear infinite; animation-play-state: paused;
}
.vinyl.spinning { animation-play-state: running; }
@keyframes spin { to { transform: rotate(360deg); } }

/* 外层细环 */
.vinyl-ring {
  position: absolute; inset: -2%;
  border-radius: 50%;
  border: 3px solid rgba(255,255,255,0.08);
  box-shadow: 0 0 80px rgba(0,0,0,.5), inset 0 0 40px rgba(0,0,0,.3);
  background: radial-gradient(circle,
    transparent 40%,
    rgba(0,0,0,0.15) 60%,
    rgba(0,0,0,0.3) 80%,
    rgba(0,0,0,0.5) 100%
  );
}
/* 透明中心 — 封面完全可见 */
.vinyl-glass {
  position: absolute; inset: 12%;
  border-radius: 50%; overflow: hidden;
  box-shadow: 0 0 30px rgba(49,194,124,0.1), 0 0 0 2px rgba(255,255,255,0.06);
}
.vinyl-img { width: 100%; height: 100%; object-fit: cover; display: block; }

/* 光泽扫过 */
.vinyl-sheen {
  position: absolute; top: 6%; left: 10%; right: 10%; bottom: 55%;
  border-radius: 50%;
  background: linear-gradient(165deg, rgba(255,255,255,0.1) 0%, transparent 60%);
  pointer-events: none;
}

/* 封面下方绿色光晕 */
.disc-glow {
  width: 60%; height: 6px; margin-top: 16px;
  background: radial-gradient(ellipse, rgba(49,194,124,0.25), transparent 80%);
  border-radius: 50%; filter: blur(4px);
}

/* ------ LYRICS ------ */
.lyric-panel {
  flex: 1; height: 100%; min-width: 0; overflow: hidden; position: relative;
}
/* 上下渐变遮罩 */
.lyric-panel::before, .lyric-panel::after {
  content: ''; position: absolute; left: 0; right: 0; z-index: 2;
  height: 44px; pointer-events: none;
}
.lyric-panel::before {
  top: 0;
  background: linear-gradient(to bottom, rgba(14,32,22,0.92), transparent);
}
.lyric-panel::after {
  bottom: 0;
  background: linear-gradient(to top, rgba(8,14,10,0.92), transparent);
}

.lyric-scroll {
  height: 100%; overflow-y: auto; padding: 38% 0;
  scroll-behavior: smooth;
}
.lyric-scroll::-webkit-scrollbar { display: none; }

.lyric-line {
  padding: 13px 24px; cursor: pointer; text-align: center;
  font-size: 15px; line-height: 1.8; letter-spacing: .6px;
  color: rgba(255,255,255,0.28);
  transition: all .45s cubic-bezier(.23,1,.32,1);
}
.lyric-line:hover { color: rgba(255,255,255,0.5); }
.lyric-line.on {
  color: #31c27c; font-size: 19px; font-weight: 700;
  text-shadow: 0 0 24px rgba(49,194,124,0.45), 0 0 48px rgba(49,194,124,0.15);
  transform: scale(1.02);
}

.lyric-hint {
  text-align: center; padding-top: 42%;
  color: rgba(255,255,255,0.25); font-size: 15px;
  display: flex; flex-direction: column; align-items: center; gap: 8px;
}
.lyric-hint p { margin: 0; font-size: 17px; }
.lyric-hint span { font-size: 12px; opacity: .5; }

/* ============================
   FOOTER CONTROL BAR
   ============================ */
.ctrl-bar {
  padding: 8px 24px;
  padding-bottom: max(22px, env(safe-area-inset-bottom, 22px));
  z-index: 10;
}

/* 进度条 */
.seek-row {
  display: flex; align-items: center; gap: 8px; margin-bottom: 12px;
}
.seek-row .t {
  font-size: 11px; color: rgba(255,255,255,0.45);
  font-variant-numeric: tabular-nums; min-width: 32px; text-align: center;
}
.seek-track {
  flex: 1; height: 4px; background: rgba(255,255,255,0.08);
  border-radius: 2px; cursor: pointer; position: relative;
  transition: height .15s;
}
.seek-track:hover { height: 6px; }
.seek-fill {
  height: 100%; background: linear-gradient(90deg, #31c27c, #4cd98b);
  border-radius: 2px; position: relative; transition: width .15s linear;
}
.seek-knob {
  position: absolute; right: -5px; top: 50%; transform: translateY(-50%);
  width: 10px; height: 10px; background: #31c27c; border-radius: 50%;
  box-shadow: 0 0 6px rgba(49,194,124,.6); opacity: 0;
  transition: opacity .15s;
}
.seek-track:hover .seek-knob { opacity: 1; }

/* 操作行 */
.action-row {
  display: flex; align-items: center; justify-content: space-between;
}
.actions-left {
  display: flex; align-items: center; gap: 32px;
}
.act-btn {
  border: none; background: none; cursor: pointer;
  color: rgba(255,255,255,0.75); padding: 6px;
  display: flex; align-items: center; justify-content: center;
  transition: all .18s;
}
.act-btn:hover { color: #fff; }
.act-btn:active { transform: scale(.9); }
.act-btn.play {
  width: 46px; height: 46px; border-radius: 50%; color: #fff;
  background: linear-gradient(135deg, #31c27c, #22b05a);
  box-shadow: 0 4px 18px rgba(49,194,124,.3);
}
.act-btn.play:hover { transform: scale(1.06); box-shadow: 0 6px 24px rgba(49,194,124,.45); }

/* 音量组 */
.vol-group {
  display: flex; align-items: center; gap: 8px;
}
.act-btn.mute { color: rgba(255,255,255,0.5); padding: 4px; }
.act-btn.mute:hover { color: #31c27c; }
.vol-track {
  width: 80px; height: 3px; background: rgba(255,255,255,0.1);
  border-radius: 2px; cursor: pointer; transition: height .15s;
}
.vol-track:hover { height: 5px; }
.vol-fill {
  height: 100%; background: rgba(255,255,255,0.5); border-radius: 2px;
  transition: width .15s;
}
.vol-track:hover .vol-fill { background: #31c27c; }

/* ============================
   TRANSITIONS
   ============================ */
.slide-up-enter-active, .slide-up-leave-active {
  transition: transform .45s cubic-bezier(.32,.72,0,1);
}
.slide-up-enter-from, .slide-up-leave-to { transform: translateY(100%); }

/* Fullscreen */
.player.fs .top-title { font-size: 20px; }
.player.fs .main-area { gap: 40px; }
.player.fs .disc-panel { max-width: 400px; }
.player.fs .turntable { max-width: 320px; }
.player.fs .lyric-line { font-size: 16px; }
.player.fs .lyric-line.on { font-size: 21px; }
</style>
