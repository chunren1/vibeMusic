<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  currentSong: { type: Object, default: () => ({}) },
  isPlaying: { type: Boolean, default: false },
  duration: { type: Number, default: 0 }
})

const emit = defineEmits(['update:visible', 'togglePlay', 'prev', 'next', 'seek'])

const lyricsContainer = ref(null)
const isDragging = ref(false)
const showLyrics = ref(true)

// 使用真实播放时间（同步自 PlayerBar）
const currentTime = ref(0)

// 定时器同步播放进度
let timeInterval = null

function startTimeSync() {
  stopTimeSync()
  timeInterval = setInterval(() => {
    const audio = window.vibeAudio
    if (audio && audio.currentTime !== undefined) {
      currentTime.value = audio.currentTime
    }
  }, 200)
}

function stopTimeSync() {
  if (timeInterval) { clearInterval(timeInterval); timeInterval = null }
}

watch(() => props.visible, (val) => {
  if (val) {
    currentTime.value = window.vibeAudio?.currentTime || 0
    startTimeSync()
  } else {
    stopTimeSync()
  }
})

onUnmounted(() => stopTimeSync())

// 模拟歌词（无API时展示）
const fallbackLyrics = [
  { time: 0, text: '♪ 音乐即将开始 ♪' },
  { time: 5, text: '♪ 当前暂无歌词数据 ♪' },
  { time: 10, text: '请欣赏音乐' },
  { time: 15, text: '后续可接入歌词API' },
  { time: 20, text: '实现真实歌词展示' },
  { time: 25, text: '♪' }
]

const lyrics = ref(fallbackLyrics)

// 当前高亮的歌词索引
const currentLyricIndex = computed(() => {
  const time = currentTime.value
  for (let i = lyrics.value.length - 1; i >= 0; i--) {
    if (time >= lyrics.value[i].time) return i
  }
  return 0
})

function scrollToCurrentLyric() {
  if (!lyricsContainer.value || isDragging.value) return
  const container = lyricsContainer.value
  const items = container.querySelectorAll('.lyric-item')
  const currentItem = items[currentLyricIndex.value]
  if (currentItem) {
    const ch = container.clientHeight
    const ih = currentItem.clientHeight
    container.scrollTo({ top: currentItem.offsetTop - ch / 2 + ih / 2, behavior: 'smooth' })
  }
}

watch(() => currentTime.value, () => scrollToCurrentLyric())

function formatTime(s) {
  if (!s || isNaN(s)) return '00:00'
  const m = Math.floor(s / 60)
  const sec = Math.floor(s % 60)
  return m.toString().padStart(2, '0') + ':' + sec.toString().padStart(2, '0')
}

const progressPercent = computed(() => {
  if (!props.duration) return 0
  return (currentTime.value / props.duration) * 100
})

function onProgressClick(e) {
  const rect = e.currentTarget.getBoundingClientRect()
  const ratio = (e.clientX - rect.left) / rect.width
  const seekTime = ratio * props.duration
  if (window.vibeAudio) window.vibeAudio.currentTime = seekTime
  currentTime.value = seekTime
}

function close() {
  emit('update:visible', false)
}

let touchStartY = 0
function onTouchStart(e) { touchStartY = e.touches[0].clientY }
function onTouchMove(e) {
  if (e.touches[0].clientY - touchStartY > 100) close()
}
</script>

<template>
  <Teleport to="body">
    <Transition name="lyrics-slide">
      <div v-if="visible" class="lyrics-view" @touchstart="onTouchStart" @touchmove="onTouchMove">
        <!-- 背景模糊 -->
        <div
          class="lyrics-bg"
          :style="{ backgroundImage: `url(${currentSong.coverUrl}?param=500y500)` }"
        ></div>

        <!-- 头部 -->
        <div class="lyrics-header">
          <button class="back-btn" @click="close">
            <svg viewBox="0 0 24 24" width="22" height="22"><path fill="currentColor" d="M7.41 15.41L12 10.83l4.59 4.58L18 14l-6-6-6 6z"/></svg>
          </button>
          <div class="header-info">
            <span class="header-title">{{ currentSong.title || '未播放' }}</span>
            <span class="header-artist">{{ currentSong.artist || '-' }}</span>
          </div>
          <button class="share-btn" @click="close">✕</button>
        </div>

        <!-- 主内容 -->
        <div class="lyrics-content">
          <!-- 唱片封面区 -->
          <div class="disc-area" v-show="!showLyrics">
            <div class="disc-container">
              <div class="disc-arm" :class="{ playing: isPlaying }">
                <div class="arm-pivot"></div>
                <div class="arm-stick"></div>
                <div class="arm-head"></div>
              </div>
              <div class="disc-wrapper" :class="{ playing: isPlaying }">
                <div class="disc-outer">
                  <div class="disc-inner">
                    <img
                      :src="currentSong.coverUrl ? currentSong.coverUrl + '?param=300y300' : ''"
                      class="disc-cover"
                    />
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- 歌词区 -->
          <div class="lyrics-section" v-show="showLyrics">
            <div ref="lyricsContainer" class="lyrics-list">
              <div
                v-for="(line, i) in lyrics"
                :key="i"
                class="lyric-item"
                :class="{ active: i === currentLyricIndex }"
                @click="window.vibeAudio && (window.vibeAudio.currentTime = line.time)"
              >{{ line.text }}</div>
            </div>
          </div>

          <!-- 切换 -->
          <button class="toggle-btn" @click="showLyrics = !showLyrics">
            {{ showLyrics ? '封' : '词' }}
          </button>
        </div>

        <!-- 底部控制 -->
        <div class="lyrics-controls">
          <div class="progress-section">
            <span class="time">{{ formatTime(currentTime) }}</span>
            <div class="progress-bar" @click="onProgressClick">
              <div class="progress-track">
                <div class="progress-fill" :style="{ width: progressPercent + '%' }"></div>
              </div>
            </div>
            <span class="time">{{ formatTime(duration) }}</span>
          </div>
          <div class="control-btns">
            <button class="ctrl-btn" @click="$emit('prev')">⏮</button>
            <button class="ctrl-btn play-btn" @click="$emit('togglePlay')">
              {{ isPlaying ? '⏸' : '▶' }}
            </button>
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

/* 背景 */
.lyrics-bg {
  position: absolute; top: -30px; left: -30px; right: -30px; bottom: -30px;
  background-size: cover; background-position: center;
  filter: blur(80px) brightness(0.35); z-index: -1;
}

/* 头部 */
.lyrics-header {
  display: flex; align-items: center; padding: 16px 16px 0; z-index: 10;
}
.back-btn, .share-btn {
  width: 36px; height: 36px; border: none;
  background: rgba(255,255,255,0.1); border-radius: 50%;
  color: #fff; cursor: pointer; display: flex; align-items: center; justify-content: center;
  font-size: 18px; transition: background 0.2s;
}
.back-btn:hover, .share-btn:hover { background: rgba(255,255,255,0.2); }
.header-info { flex: 1; text-align: center; }
.header-title { font-size: 16px; font-weight: 500; display: block; }
.header-artist { font-size: 12px; opacity: 0.6; display: block; margin-top: 2px; }

/* 内容 */
.lyrics-content {
  flex: 1; display: flex; flex-direction: column; position: relative; overflow: hidden;
}

/* ====== 唱片封面 ====== */
.disc-area {
  flex: 1; display: flex; align-items: center; justify-content: center;
}
.disc-container {
  position: relative; width: 280px; height: 340px;
}
/* 唱臂 */
.disc-arm {
  position: absolute; top: -10px; right: 20px; width: 120px; height: 160px;
  z-index: 3; transform-origin: 0 0; transform: rotate(-30deg);
  transition: transform 0.6s ease;
}
.disc-arm.playing { transform: rotate(-18deg); }
.arm-pivot {
  position: absolute; top: 0; left: 0; width: 24px; height: 24px;
  background: #ddd; border-radius: 50%; box-shadow: 0 2px 8px rgba(0,0,0,0.3);
}
.arm-stick {
  position: absolute; top: 10px; left: -4px;
  width: 6px; height: 100px; background: linear-gradient(to bottom, #ccc, #999);
  border-radius: 3px; transform: rotate(5deg);
}
.arm-head {
  position: absolute; bottom: 45px; left: -4px;
  width: 20px; height: 16px; background: #888; border-radius: 3px;
}
/* 唱片 */
.disc-wrapper {
  position: absolute; top: 40px; left: 10px; z-index: 2;
}
.disc-outer {
  width: 260px; height: 260px;
  background: radial-gradient(circle at center,
    #222 0%, #222 18%, #333 18%, #333 20%,
    #1a1a1a 20%, #1a1a1a 28%, #2a2a2a 28%, #2a2a2a 30%,
    #111 30%, #111 100%
  );
  border-radius: 50%; display: flex; align-items: center; justify-content: center;
  box-shadow: 0 0 60px rgba(0,0,0,0.6), inset 0 0 40px rgba(0,0,0,0.3);
  animation: discRotate 20s linear infinite; animation-play-state: paused;
}
.disc-wrapper.playing { animation-play-state: running; }
.disc-inner {
  width: 140px; height: 140px; border-radius: 50%; overflow: hidden;
  border: 4px solid #333;
}
.disc-cover { width: 100%; height: 100%; object-fit: cover; }
@keyframes discRotate { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

/* ====== 歌词 ====== */
.lyrics-section { flex: 1; overflow: hidden; }
.lyrics-list { height: 100%; overflow-y: auto; padding: 30% 24px; text-align: center; }
.lyrics-list::-webkit-scrollbar { display: none; }
.lyric-item {
  padding: 14px 0; font-size: 15px; line-height: 1.7; opacity: 0.35;
  transition: all 0.35s ease; cursor: pointer;
}
.lyric-item.active {
  font-size: 17px; font-weight: 600; opacity: 1; color: #31c27c;
}

/* 切换按钮 */
.toggle-btn {
  position: absolute; right: 16px; top: 50%; transform: translateY(-50%);
  width: 36px; height: 36px; border: none;
  background: rgba(255,255,255,0.1); border-radius: 50%;
  color: #fff; font-size: 13px; cursor: pointer;
}

/* 底部控制 */
.lyrics-controls {
  padding: 12px 24px 36px;
  background: linear-gradient(to top, rgba(0,0,0,0.8), transparent);
}
.progress-section {
  display: flex; align-items: center; gap: 10px; margin-bottom: 20px;
}
.time { font-size: 12px; opacity: 0.6; min-width: 36px; text-align: center; }
.progress-bar { flex: 1; height: 8px; cursor: pointer; display: flex; align-items: center; }
.progress-track {
  width: 100%; height: 3px; background: rgba(255,255,255,0.2); border-radius: 2px;
}
.progress-fill { height: 100%; background: #31c27c; border-radius: 2px; transition: width 0.2s; }
.control-btns {
  display: flex; align-items: center; justify-content: center; gap: 40px;
}
.ctrl-btn { border: none; background: none; color: #fff; font-size: 24px; cursor: pointer; opacity: 0.85; }
.ctrl-btn:hover { opacity: 1; }
.play-btn {
  width: 56px; height: 56px; background: #31c27c; border-radius: 50%;
  font-size: 24px; display: flex; align-items: center; justify-content: center;
}

/* 上滑动画 */
.lyrics-slide-enter-active, .lyrics-slide-leave-active { transition: transform 0.4s cubic-bezier(0.32,0.72,0,1); }
.lyrics-slide-enter-from, .lyrics-slide-leave-to { transform: translateY(100%); }
</style>
