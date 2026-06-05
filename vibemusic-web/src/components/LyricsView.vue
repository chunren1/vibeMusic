<script setup>
import { ref, computed, watch, onUnmounted } from 'vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  currentSong: { type: Object, default: () => ({}) },
  isPlaying: { type: Boolean, default: false },
  duration: { type: Number, default: 0 }
})

const emit = defineEmits(['update:visible', 'togglePlay', 'prev', 'next', 'seek'])

const lyricsContainer = ref(null)
const isDragging = ref(false)
const isFullscreen = ref(false)
const lyricsViewEl = ref(null)

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

watch(() => props.visible, (val) => {
  if (val) { currentTime.value = window.vibeAudio?.currentTime || 0; startTimeSync() }
  else { stopTimeSync(); exitFullscreen() }
})

onUnmounted(() => { stopTimeSync(); exitFullscreen() })

// 歌词
const fallbackLyrics = [
  { time: 0, text: '♪ 音乐即将开始 ♪' },
  { time: 5, text: '♪ 当前暂无歌词数据 ♪' },
  { time: 10, text: '请欣赏音乐' },
  { time: 15, text: '后续可接入歌词API' },
  { time: 20, text: '♪' }
]
const lyrics = ref(fallbackLyrics)

const currentLyricIndex = computed(() => {
  const t = currentTime.value
  for (let i = lyrics.value.length - 1; i >= 0; i--) {
    if (t >= lyrics.value[i].time) return i
  }
  return 0
})

function scrollToCurrent() {
  if (!lyricsContainer.value || isDragging.value) return
  const items = lyricsContainer.value.querySelectorAll('.lyric-item')
  const el = items[currentLyricIndex.value]
  if (el) {
    const ch = lyricsContainer.value.clientHeight
    const ih = el.clientHeight
    lyricsContainer.value.scrollTo({ top: el.offsetTop - ch / 2 + ih / 2, behavior: 'smooth' })
  }
}
watch(() => currentTime.value, () => scrollToCurrent())

// 全屏
async function toggleFullscreen() {
  if (!isFullscreen.value) {
    try {
      if (lyricsViewEl.value?.requestFullscreen) {
        await lyricsViewEl.value.requestFullscreen()
        isFullscreen.value = true
      }
    } catch(e) {}
  } else {
    exitFullscreen()
  }
}
function exitFullscreen() {
  if (document.fullscreenElement) { document.exitFullscreen().catch(()=>{}) }
  isFullscreen.value = false
}

// 监听 ESC 退出全屏
document.addEventListener('fullscreenchange', () => {
  if (!document.fullscreenElement) isFullscreen.value = false
})

function formatTime(s) {
  if (!s || isNaN(s)) return '00:00'
  const m = Math.floor(s / 60), sec = Math.floor(s % 60)
  return m.toString().padStart(2,'0') + ':' + sec.toString().padStart(2,'0')
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
        <!-- 背景 -->
        <div class="lyrics-bg"
          :style="{ backgroundImage: `url(${currentSong.coverUrl}?param=500y500)` }"></div>

        <!-- 头部 -->
        <div class="lyrics-header">
          <button class="back-btn" @click="close">
            <svg viewBox="0 0 24 24" width="20" height="20"><path fill="currentColor" d="M7.41 15.41L12 10.83l4.59 4.58L18 14l-6-6-6 6z"/></svg>
          </button>
          <span class="header-title">{{ currentSong.title || '未播放' }}</span>
          <button class="fullscreen-btn" @click="toggleFullscreen" :title="isFullscreen ? '退出全屏' : '全屏'">
            <svg v-if="!isFullscreen" viewBox="0 0 24 24" width="20" height="20"><path fill="currentColor" d="M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z"/></svg>
            <svg v-else viewBox="0 0 24 24" width="20" height="20"><path fill="currentColor" d="M5 16h3v3h2v-5H5v2zm3-8H5v2h5V5H8v3zm6 11h2v-3h3v-2h-5v5zm2-11V5h-2v5h5V8h-3z"/></svg>
          </button>
        </div>

        <!-- 主内容：封面 + 歌词同屏 -->
        <div class="lyrics-content">
          <!-- 封面区 -->
          <div class="disc-area">
            <div class="disc-wrapper" :class="{ playing: isPlaying }">
              <div class="disc-outer">
                <img :src="currentSong.coverUrl ? currentSong.coverUrl + '?param=200y200' : ''" class="disc-cover" />
              </div>
              <div class="disc-shine"></div>
            </div>
            <p class="disc-artist">{{ currentSong.artist || '-' }}</p>
          </div>

          <!-- 歌词区 -->
          <div class="lyrics-area">
            <div ref="lyricsContainer" class="lyrics-list">
              <div v-for="(line, i) in lyrics" :key="i"
                class="lyric-item" :class="{ active: i === currentLyricIndex }"
                @click="window.vibeAudio && (window.vibeAudio.currentTime = line.time)">
                {{ line.text }}
              </div>
            </div>
          </div>
        </div>

        <!-- 底部 -->
        <div class="lyrics-controls">
          <div class="progress-section">
            <span class="time">{{ formatTime(currentTime) }}</span>
            <div class="progress-bar" @click="onProgressClick">
              <div class="progress-fill" :style="{ width: progressPercent + '%' }"></div>
            </div>
            <span class="time">{{ formatTime(duration) }}</span>
          </div>
          <div class="control-btns">
            <button class="ctrl-btn" @click="$emit('prev')">⏮</button>
            <button class="ctrl-btn play-btn" @click="$emit('togglePlay')">{{ isPlaying ? '⏸' : '▶' }}</button>
            <button class="ctrl-btn" @click="$emit('next')">⏭</button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.lyrics-view {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  z-index: 200; display: flex; flex-direction: column; color: #fff; overflow: hidden;
}
.lyrics-view.fullscreen { z-index: 300; }

.lyrics-bg {
  position: absolute; top: -30px; left: -30px; right: -30px; bottom: -30px;
  background-size: cover; background-position: center;
  filter: blur(80px) brightness(0.35); z-index: -1;
}

.lyrics-header {
  display: flex; align-items: center; padding: 12px 16px 0; z-index: 10;
}
.back-btn, .fullscreen-btn {
  width: 34px; height: 34px; border: none; background: rgba(255,255,255,0.1);
  border-radius: 50%; color: #fff; cursor: pointer; display: flex;
  align-items: center; justify-content: center; transition: background 0.2s;
}
.back-btn:hover, .fullscreen-btn:hover { background: rgba(255,255,255,0.2); }
.header-title {
  flex: 1; text-align: center; font-size: 15px; font-weight: 500;
}

/* 内容区：封面 + 歌词 */
.lyrics-content {
  flex: 1; display: flex; align-items: center; gap: 12px; padding: 10px 16px;
  overflow: hidden;
}

/* 封面 */
.disc-area {
  width: 44%; display: flex; flex-direction: column; align-items: center; gap: 14px;
}
.disc-wrapper {
  position: relative; width: 100%; max-width: 180px; aspect-ratio: 1;
  animation: discRotate 20s linear infinite; animation-play-state: paused;
}
.disc-wrapper.playing { animation-play-state: running; }
.disc-outer {
  width: 100%; height: 100%; border-radius: 50%; overflow: hidden;
  border: 6px solid rgba(255,255,255,0.15); box-shadow: 0 0 40px rgba(0,0,0,0.5);
}
.disc-cover { width: 100%; height: 100%; object-fit: cover; display: block; }
.disc-shine {
  position: absolute; top: 10%; left: 20%; right: 20%; bottom: 30%;
  background: linear-gradient(135deg, rgba(255,255,255,0.15) 0%, transparent 60%);
  border-radius: 50%; pointer-events: none;
}
.disc-artist { font-size: 13px; opacity: 0.6; }
@keyframes discRotate { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

/* 歌词 */
.lyrics-area { flex: 1; min-width: 0; height: 100%; overflow: hidden; }
.lyrics-list {
  height: 100%; overflow-y: auto; padding: 20% 8px 20% 4px;
}
.lyrics-list::-webkit-scrollbar { display: none; }
.lyric-item {
  padding: 12px 0; font-size: 14px; line-height: 1.6; opacity: 0.35;
  transition: all 0.3s; cursor: pointer;
}
.lyric-item.active { font-size: 16px; font-weight: 600; opacity: 1; color: #31c27c; }

/* 底部 */
.lyrics-controls {
  padding: 10px 24px 32px;
  background: linear-gradient(to top, rgba(0,0,0,0.8), transparent);
}
.progress-section { display: flex; align-items: center; gap: 10px; margin-bottom: 16px; }
.time { font-size: 12px; opacity: 0.6; min-width: 36px; text-align: center; }
.progress-bar {
  flex: 1; height: 6px; background: rgba(255,255,255,0.15); border-radius: 3px;
  cursor: pointer; overflow: hidden;
}
.progress-fill { height: 100%; background: #31c27c; border-radius: 3px; transition: width 0.15s; }
.control-btns { display: flex; align-items: center; justify-content: center; gap: 36px; }
.ctrl-btn { border: none; background: none; color: #fff; font-size: 22px; cursor: pointer; opacity: 0.85; }
.ctrl-btn:hover { opacity: 1; }
.play-btn {
  width: 52px; height: 52px; background: #31c27c; border-radius: 50%;
  font-size: 22px; display: flex; align-items: center; justify-content: center;
}

.lyrics-slide-enter-active, .lyrics-slide-leave-active { transition: transform 0.4s cubic-bezier(0.32,0.72,0,1); }
.lyrics-slide-enter-from, .lyrics-slide-leave-to { transform: translateY(100%); }
</style>
