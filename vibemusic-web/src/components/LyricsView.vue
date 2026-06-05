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

const lyrics = ref([])

async function fetchLyric(sourceId) {
  if (!sourceId) { lyrics.value = []; return }
  loadingLyric.value = true
  try {
    const res = await getLyric(sourceId)
    lyrics.value = res.data || []
    currentLyricIndex.value = 0
    setTimeout(() => scrollToCurrent(), 200)
  } catch { lyrics.value = [] }
  finally { loadingLyric.value = false }
}

watch(() => props.visible, (val) => {
  if (val) {
    currentTime.value = window.vibeAudio?.currentTime || 0
    volume.value = Math.round((window.vibeAudio?.volume || 1) * 100)
    isMuted.value = window.vibeAudio?.muted || false
    startTimeSync()
    if (props.currentSong.id) fetchLyric(props.currentSong.id)
  } else { stopTimeSync(); exitFullscreen() }
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
  const items = lyricsContainer.value.querySelectorAll('.lyric-line')
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
  const rect = e.currentTarget.getBoundingClientRect()
  const ratio = (e.clientX - rect.left) / rect.width
  const t = ratio * props.duration
  if (window.vibeAudio) { window.vibeAudio.currentTime = t; currentTime.value = t }
}

function close() { emit('update:visible', false) }
</script>

<template>
  <Teleport to="body">
    <Transition name="slide">
      <div v-if="visible" ref="lyricsViewEl" class="view" :class="{ fs: isFullscreen }">
        <!-- 背景 -->
        <div class="bg">
          <div class="bg-img" :style="{ backgroundImage: `url(${currentSong.coverUrl}?param=800y800)` }"></div>
          <div class="bg-grad"></div>
        </div>

        <!-- 顶部 -->
        <header class="top">
          <button class="btn-icon" @click="close">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2.2"><polyline points="15 18 9 12 15 6"/></svg>
          </button>
          <div class="top-center">
            <div class="song-name">{{ currentSong.title || '未播放' }}</div>
            <div class="artist-name">{{ currentSong.artist || '-' }}</div>
          </div>
          <button class="btn-icon" @click="toggleFullscreen">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><path d="M8 3H5a2 2 0 0 0-2 2v3m18 0V5a2 2 0 0 0-2-2h-3m0 18h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3"/></svg>
          </button>
        </header>

        <!-- 主体：碟片 + 歌词 -->
        <main class="stage">
          <!-- 左侧：大碟片 -->
          <div class="left">
            <div class="disc-box">
              <div class="disc" :class="{ spin: isPlaying }">
                <div class="disc-outer"></div>
                <div class="disc-inner">
                  <img :src="currentSong.coverUrl ? currentSong.coverUrl + '?param=400y400' : ''" alt="" />
                </div>
                <div class="disc-shine"></div>
              </div>
              <!-- 唱臂 -->
              <div class="arm" :class="{ down: isPlaying }">
                <div class="arm-base"></div>
                <div class="arm-stick"></div>
                <div class="arm-head"></div>
              </div>
            </div>
          </div>

          <!-- 右侧：歌词 -->
          <div class="right">
            <div ref="lyricsContainer" class="lyric-box">
              <div v-if="loadingLyric" class="empty">加载中...</div>
              <div v-else-if="lyrics.length === 0" class="empty">暂无歌词</div>
              <div v-for="(line, i) in lyrics" :key="i"
                class="lyric-line" :class="{ current: i === currentLyricIndex }"
                @click="window.vibeAudio && (window.vibeAudio.currentTime = parseFloat(line.time))">
                {{ line.text }}
              </div>
            </div>
          </div>
        </main>

        <!-- 底部控制栏 -->
        <footer class="bar">
          <!-- 进度条在上 -->
          <div class="progress-wrap">
            <span class="time">{{ formatTime(currentTime) }}</span>
            <div class="progress-track" @click="onProgressClick">
              <div class="progress-fill" :style="{ width: progressPercent + '%' }">
                <div class="thumb"></div>
              </div>
            </div>
            <span class="time">{{ formatTime(duration) }}</span>
          </div>

          <!-- 按钮行 -->
          <div class="ctrl-wrap">
            <!-- 左侧：歌曲信息 + 功能按钮 -->
            <div class="left-info">
              <div class="mini-song">
                <span class="mini-name">{{ currentSong.title }}</span>
                <span class="mini-artist"> - {{ currentSong.artist }}</span>
              </div>
              <div class="func-btns">
                <button class="func-btn" title="喜欢">
                  <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>
                </button>
                <button class="func-btn" title="评论">
                  <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/></svg>
                </button>
              </div>
            </div>

            <!-- 中间：播放控制 -->
            <div class="center-ctrl">
              <button class="play-btn mode" title="顺序播放">
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M16 3h3v3h-3zM8 3h3v3H8zM5 8h14v12a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V8zM3 8h18"/></svg>
              </button>
              <button class="play-btn skip" @click="$emit('prev')">
                <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M6 6h2v12H6zm3.5 6 8.5 6V6z"/></svg>
              </button>
              <button class="play-btn main" @click="$emit('togglePlay')">
                <svg v-if="isPlaying" viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/></svg>
                <svg v-else viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><polygon points="8,5 19,12 8,19"/></svg>
              </button>
              <button class="play-btn skip" @click="$emit('next')">
                <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/></svg>
              </button>
            </div>

            <!-- 右侧：音量 + 列表 -->
            <div class="right-vol">
              <button class="vol-btn" @click="toggleMute">
                <svg v-if="isMuted || volume === 0" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><line x1="23" y1="9" x2="17" y2="15"/><line x1="17" y1="9" x2="23" y2="15"/></svg>
                <svg v-else viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><path d="M15.54 8.46a5 5 0 0 1 0 7.07"/></svg>
              </button>
              <div class="vol-bar" @click="onVolumeClick">
                <div class="vol-fill" :style="{ width: (isMuted ? 0 : volume) + '%' }"></div>
              </div>
              <button class="vol-btn list" title="列表">
                <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/></svg>
              </button>
            </div>
          </div>
        </footer>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.view { position: fixed; inset: 0; z-index: 200; display: flex; flex-direction: column; overflow: hidden; }

/* 背景 */
.bg { position: absolute; inset: 0; z-index: -1; }
.bg-img { position: absolute; inset: -100px; background-size: cover; background-position: center; filter: blur(120px) saturate(0.45) brightness(0.3); transform: scale(1.1); }
.bg-grad { position: absolute; inset: 0; background: linear-gradient(180deg, #0d1f14 0%, #091812 30%, #05120d 70%, #020c08 100%); }

/* 顶部 */
.top { display: flex; align-items: center; justify-content: space-between; padding: 16px 20px 8px; }
.btn-icon { width: 36px; height: 36px; border: none; background: rgba(255,255,255,0.06); border-radius: 50%; color: rgba(255,255,255,0.7); cursor: pointer; display: flex; align-items: center; justify-content: center; transition: .2s; }
.btn-icon:hover { background: rgba(255,255,255,0.12); color: #fff; }
.top-center { text-align: center; }
.song-name { font-size: 18px; font-weight: 600; color: #fff; letter-spacing: 0.5px; }
.artist-name { font-size: 13px; color: rgba(255,255,255,0.5); margin-top: 2px; }

/* 主体 */
.stage { flex: 1; display: flex; align-items: flex-start; padding: 20px 40px 0; gap: 60px; overflow: hidden; }

/* 左侧碟片 */
.left { width: 45%; display: flex; align-items: flex-start; justify-content: center; padding-top: 20px; }
.disc-box { position: relative; width: 100%; max-width: 340px; aspect-ratio: 1; }
.disc { position: relative; width: 100%; height: 100%; animation: spin 18s linear infinite; animation-play-state: paused; }
.disc.spin { animation-play-state: running; }
@keyframes spin { to { transform: rotate(360deg); } }

.disc-outer { position: absolute; inset: 0; border-radius: 50%; background: linear-gradient(145deg, #1a1a1a, #0d0d0d); box-shadow: 0 0 0 1px rgba(255,255,255,0.08), 0 20px 60px rgba(0,0,0,0.6), inset 0 0 40px rgba(0,0,0,0.4); }
.disc-inner { position: absolute; inset: 18%; border-radius: 50%; overflow: hidden; box-shadow: 0 0 0 2px rgba(255,255,255,0.05); }
.disc-inner img { width: 100%; height: 100%; object-fit: cover; }
.disc-shine { position: absolute; inset: 0; border-radius: 50%; background: linear-gradient(135deg, rgba(255,255,255,0.08) 0%, transparent 40%, transparent 60%, rgba(255,255,255,0.03) 100%); pointer-events: none; }

/* 唱臂 */
.arm { position: absolute; top: -8%; right: 5%; transform-origin: 80% 15%; transform: rotate(22deg); transition: transform 0.6s cubic-bezier(0.25, 0.1, 0.25, 1); }
.arm.down { transform: rotate(8deg); }
.arm-base { width: 24px; height: 24px; background: radial-gradient(circle at 30% 30%, #666, #1a1a1a); border-radius: 50%; box-shadow: 0 2px 8px rgba(0,0,0,0.5); }
.arm-stick { position: absolute; top: 10px; right: 12px; width: 70px; height: 3px; background: linear-gradient(180deg, #bbb, #777); border-radius: 2px; transform-origin: right; transform: rotate(-12deg); box-shadow: 0 1px 3px rgba(0,0,0,0.4); }
.arm-head { position: absolute; top: 0; left: -6px; width: 18px; height: 9px; background: linear-gradient(180deg, #999, #555); border-radius: 2px; box-shadow: 0 1px 2px rgba(0,0,0,0.5); }

/* 右侧歌词 */
.right { flex: 1; height: 100%; padding-top: 40px; overflow: hidden; }
.lyric-box { height: 100%; overflow-y: auto; padding: 120px 20px 200px; scroll-behavior: smooth; }
.lyric-box::-webkit-scrollbar { display: none; }
.lyric-line { padding: 16px 0; font-size: 16px; line-height: 1.7; color: rgba(255,255,255,0.35); text-align: left; transition: all .4s; cursor: pointer; }
.lyric-line:hover { color: rgba(255,255,255,0.5); }
.lyric-line.current { font-size: 20px; font-weight: 700; color: #2ecc71; text-shadow: 0 0 20px rgba(46,204,113,0.4); }
.empty { text-align: center; padding-top: 40%; color: rgba(255,255,255,0.3); }

/* 底部控制栏 */
.bar { padding: 0 24px 20px; }

/* 进度条 */
.progress-wrap { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.time { font-size: 11px; color: rgba(255,255,255,0.4); min-width: 36px; font-variant-numeric: tabular-nums; }
.progress-track { flex: 1; height: 3px; background: rgba(255,255,255,0.1); border-radius: 2px; cursor: pointer; position: relative; }
.progress-track:hover { height: 4px; }
.progress-fill { height: 100%; background: #2ecc71; border-radius: 2px; position: relative; }
.thumb { position: absolute; right: -4px; top: 50%; transform: translateY(-50%); width: 8px; height: 8px; background: #2ecc71; border-radius: 50%; opacity: 0; transition: opacity .2s; }
.progress-track:hover .thumb { opacity: 1; }

/* 按钮行 */
.ctrl-wrap { display: flex; align-items: center; justify-content: space-between; }

/* 左侧信息 */
.left-info { display: flex; align-items: center; gap: 16px; min-width: 200px; }
.mini-song { color: rgba(255,255,255,0.6); font-size: 13px; }
.mini-name { color: #fff; font-weight: 500; }
.mini-artist { color: rgba(255,255,255,0.5); }
.func-btns { display: flex; gap: 8px; }
.func-btn { width: 32px; height: 32px; border: none; background: rgba(255,255,255,0.06); border-radius: 50%; color: rgba(255,255,255,0.6); cursor: pointer; display: flex; align-items: center; justify-content: center; transition: .2s; }
.func-btn:hover { background: rgba(255,255,255,0.12); color: #fff; }

/* 中间播放 */
.center-ctrl { display: flex; align-items: center; gap: 24px; position: absolute; left: 50%; transform: translateX(-50%); }
.play-btn { border: none; background: none; color: rgba(255,255,255,0.7); cursor: pointer; display: flex; align-items: center; justify-content: center; transition: .2s; }
.play-btn:hover { color: #fff; }
.play-btn.mode { width: 32px; height: 32px; border-radius: 50%; background: rgba(255,255,255,0.06); }
.play-btn.skip { opacity: 0.7; }
.play-btn.skip:hover { opacity: 1; }
.play-btn.main { width: 52px; height: 52px; border-radius: 50%; background: #2ecc71; color: #fff; box-shadow: 0 4px 20px rgba(46,204,113,0.35); }
.play-btn.main:hover { transform: scale(1.05); box-shadow: 0 6px 28px rgba(46,204,113,0.5); }

/* 右侧音量 */
.right-vol { display: flex; align-items: center; gap: 10px; min-width: 200px; justify-content: flex-end; }
.vol-btn { width: 32px; height: 32px; border: none; background: none; color: rgba(255,255,255,0.5); cursor: pointer; display: flex; align-items: center; justify-content: center; }
.vol-btn:hover { color: #fff; }
.vol-bar { width: 80px; height: 3px; background: rgba(255,255,255,0.1); border-radius: 2px; cursor: pointer; }
.vol-bar:hover { height: 4px; }
.vol-fill { height: 100%; background: rgba(255,255,255,0.5); border-radius: 2px; }
.vol-bar:hover .vol-fill { background: #2ecc71; }

.slide-enter-active, .slide-leave-active { transition: transform .4s cubic-bezier(.32,.72,0,1); }
.slide-enter-from, .slide-leave-to { transform: translateY(100%); }
</style>
