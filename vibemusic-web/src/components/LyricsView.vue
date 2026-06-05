<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useAuthStore } from '@/stores/auth'

const props = defineProps({
  visible: { type: Boolean, default: false },
  currentSong: { type: Object, default: () => ({}) },
  isPlaying: { type: Boolean, default: false },
  currentTime: { type: Number, default: 0 },
  duration: { type: Number, default: 0 }
})

const emit = defineEmits(['update:visible', 'togglePlay', 'prev', 'next', 'seek'])

const authStore = useAuthStore()

// 模拟歌词数据（实际应从API获取）
const mockLyrics = [
  { time: 0, text: '♪ 纯音乐，请欣赏 ♪' },
  { time: 5, text: '第一段歌词展示区域' },
  { time: 10, text: '歌词会随着播放进度自动高亮' },
  { time: 15, text: '当前播放行会居中显示' },
  { time: 20, text: '支持平滑滚动动画效果' },
  { time: 25, text: '背景使用封面图模糊特效' },
  { time: 30, text: '点击封面可以打开此页面' },
  { time: 35, text: '下拉或点击返回可关闭' },
  { time: 40, text: '底部保留完整播放控制' },
  { time: 45, text: '支持进度条拖拽定位' },
  { time: 50, text: '上一曲下一曲切换' },
  { time: 55, text: '播放暂停状态实时同步' },
  { time: 60, text: '♪ 音乐结束 ♪' }
]

const lyrics = ref(mockLyrics)
const lyricsContainer = ref(null)
const isDragging = ref(false)
const showLyrics = ref(true)

// 当前高亮的歌词索引
const currentLyricIndex = computed(() => {
  const time = props.currentTime
  for (let i = lyrics.value.length - 1; i >= 0; i--) {
    if (time >= lyrics.value[i].time) {
      return i
    }
  }
  return 0
})

// 滚动到当前歌词
function scrollToCurrentLyric() {
  if (!lyricsContainer.value || isDragging.value) return
  const container = lyricsContainer.value
  const items = container.querySelectorAll('.lyric-item')
  const currentItem = items[currentLyricIndex.value]
  if (currentItem) {
    const containerHeight = container.clientHeight
    const itemHeight = currentItem.clientHeight
    const scrollTop = currentItem.offsetTop - containerHeight / 2 + itemHeight / 2
    container.scrollTo({ top: scrollTop, behavior: 'smooth' })
  }
}

// 监听播放时间变化，自动滚动
watch(() => props.currentTime, () => {
  scrollToCurrentLyric()
})

// 格式化时间
function formatTime(seconds) {
  if (!seconds) return '00:00'
  const m = Math.floor(seconds / 60)
  const s = Math.floor(seconds % 60)
  return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`
}

// 进度条相关
const progressPercent = computed(() => {
  if (!props.duration) return 0
  return (props.currentTime / props.duration) * 100
})

function onProgressClick(e) {
  const rect = e.currentTarget.getBoundingClientRect()
  const percent = (e.clientX - rect.left) / rect.width
  emit('seek', percent * props.duration)
}

// 关闭页面
function close() {
  emit('update:visible', false)
}

// 触摸滑动关闭
let touchStartY = 0
function onTouchStart(e) {
  touchStartY = e.touches[0].clientY
}

function onTouchMove(e) {
  const diff = e.touches[0].clientY - touchStartY
  if (diff > 100) {
    close()
  }
}
</script>

<template>
  <Teleport to="body">
    <Transition name="lyrics-slide">
      <div v-if="visible" class="lyrics-view" @touchstart="onTouchStart" @touchmove="onTouchMove">
        <!-- 背景模糊 -->
        <div class="lyrics-bg" :style="{ backgroundImage: `url(${currentSong.coverUrl})` }"></div>
        
        <!-- 顶部返回按钮 -->
        <div class="lyrics-header">
          <button class="back-btn" @click="close">
            <svg viewBox="0 0 24 24" width="24" height="24">
              <path fill="currentColor" d="M7.41 8.59L12 13.17l4.59-4.58L18 10l-6 6-6-6 1.41-1.41z"/>
            </svg>
          </button>
          <div class="header-info">
            <span class="header-title">{{ currentSong.title || '未播放' }}</span>
            <span class="header-artist">{{ currentSong.artist || '-' }}</span>
          </div>
          <button class="more-btn">
            <svg viewBox="0 0 24 24" width="24" height="24">
              <path fill="currentColor" d="M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"/>
            </svg>
          </button>
        </div>

        <!-- 主内容区 -->
        <div class="lyrics-content">
          <!-- 左侧封面/右侧歌词 切换 -->
          <div class="content-wrapper">
            <!-- 封面大图 -->
            <div class="cover-section" v-show="!showLyrics">
              <div class="cover-disc" :class="{ playing: isPlaying }">
                <img :src="currentSong.coverUrl || '/default-cover.png'" alt="cover">
              </div>
            </div>

            <!-- 歌词区域 -->
            <div class="lyrics-section" v-show="showLyrics">
              <div ref="lyricsContainer" class="lyrics-list">
                <div 
                  v-for="(line, index) in lyrics" 
                  :key="index"
                  class="lyric-item"
                  :class="{ active: index === currentLyricIndex }"
                  @click="$emit('seek', line.time)"
                >
                  {{ line.text }}
                </div>
              </div>
            </div>
          </div>

          <!-- 切换按钮 -->
          <button class="toggle-view-btn" @click="showLyrics = !showLyrics">
            {{ showLyrics ? '词' : '封' }}
          </button>
        </div>

        <!-- 底部控制栏 -->
        <div class="lyrics-controls">
          <!-- 歌曲信息 -->
          <div class="song-info">
            <span class="song-name">{{ currentSong.title || '未播放' }}</span>
            <span class="song-artist">{{ currentSong.artist || '-' }}</span>
          </div>

          <!-- 进度条 -->
          <div class="progress-section">
            <span class="time">{{ formatTime(currentTime) }}</span>
            <div class="progress-bar" @click="onProgressClick">
              <div class="progress-track">
                <div class="progress-fill" :style="{ width: progressPercent + '%' }"></div>
                <div class="progress-thumb" :style="{ left: progressPercent + '%' }"></div>
              </div>
            </div>
            <span class="time">{{ formatTime(duration) }}</span>
          </div>

          <!-- 控制按钮 -->
          <div class="control-buttons">
            <button class="ctrl-btn mode-btn" title="播放模式">↻</button>
            <button class="ctrl-btn" @click="$emit('prev')" title="上一首">⏮</button>
            <button class="ctrl-btn play-btn" @click="$emit('togglePlay')" title="播放/暂停">
              {{ isPlaying ? '⏸' : '▶' }}
            </button>
            <button class="ctrl-btn" @click="$emit('next')" title="下一首">⏭</button>
            <button class="ctrl-btn list-btn" title="播放列表">☰</button>
          </div>
        </div>

        <!-- 下拉提示 -->
        <div class="swipe-hint">
          <span>↓ 下拉关闭</span>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.lyrics-view {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 200;
  display: flex;
  flex-direction: column;
  color: #fff;
  overflow: hidden;
}

/* 背景模糊 */
.lyrics-bg {
  position: absolute;
  top: -20px;
  left: -20px;
  right: -20px;
  bottom: -20px;
  background-size: cover;
  background-position: center;
  filter: blur(60px) brightness(0.4);
  z-index: -1;
}

/* 头部 */
.lyrics-header {
  display: flex;
  align-items: center;
  padding: 16px 20px;
  z-index: 10;
}

.back-btn, .more-btn {
  width: 40px;
  height: 40px;
  border: none;
  background: rgba(255,255,255,0.1);
  border-radius: 50%;
  color: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s;
}

.back-btn:hover, .more-btn:hover {
  background: rgba(255,255,255,0.2);
}

.header-info {
  flex: 1;
  text-align: center;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.header-title {
  font-size: 16px;
  font-weight: 500;
}

.header-artist {
  font-size: 12px;
  opacity: 0.7;
}

/* 内容区 */
.lyrics-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  position: relative;
  overflow: hidden;
}

.content-wrapper {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

/* 封面 */
.cover-section {
  width: 100%;
  max-width: 300px;
}

.cover-disc {
  width: 100%;
  aspect-ratio: 1;
  border-radius: 50%;
  overflow: hidden;
  box-shadow: 0 20px 60px rgba(0,0,0,0.5);
  animation: rotate 20s linear infinite;
  animation-play-state: paused;
}

.cover-disc.playing {
  animation-play-state: running;
}

.cover-disc img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

@keyframes rotate {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* 歌词 */
.lyrics-section {
  width: 100%;
  height: 100%;
  max-width: 600px;
}

.lyrics-list {
  height: 100%;
  overflow-y: auto;
  padding: 40% 20px;
  text-align: center;
  scroll-behavior: smooth;
}

.lyrics-list::-webkit-scrollbar {
  display: none;
}

.lyric-item {
  padding: 16px 0;
  font-size: 16px;
  line-height: 1.6;
  opacity: 0.5;
  transition: all 0.3s;
  cursor: pointer;
}

.lyric-item:hover {
  opacity: 0.8;
}

.lyric-item.active {
  font-size: 18px;
  font-weight: 500;
  opacity: 1;
  color: #31c27c;
}

/* 切换按钮 */
.toggle-view-btn {
  position: absolute;
  right: 20px;
  top: 50%;
  transform: translateY(-50%);
  width: 40px;
  height: 40px;
  border: none;
  background: rgba(255,255,255,0.1);
  border-radius: 50%;
  color: #fff;
  font-size: 14px;
  cursor: pointer;
  transition: background 0.2s;
}

.toggle-view-btn:hover {
  background: rgba(255,255,255,0.2);
}

/* 底部控制 */
.lyrics-controls {
  padding: 20px 24px 40px;
  background: linear-gradient(to top, rgba(0,0,0,0.8), transparent);
}

.song-info {
  text-align: center;
  margin-bottom: 20px;
}

.song-name {
  display: block;
  font-size: 18px;
  font-weight: 500;
  margin-bottom: 4px;
}

.song-artist {
  font-size: 14px;
  opacity: 0.7;
}

/* 进度条 */
.progress-section {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
}

.time {
  font-size: 12px;
  opacity: 0.7;
  min-width: 40px;
  text-align: center;
}

.progress-bar {
  flex: 1;
  height: 20px;
  display: flex;
  align-items: center;
  cursor: pointer;
}

.progress-track {
  width: 100%;
  height: 4px;
  background: rgba(255,255,255,0.2);
  border-radius: 2px;
  position: relative;
}

.progress-fill {
  height: 100%;
  background: #31c27c;
  border-radius: 2px;
}

.progress-thumb {
  position: absolute;
  top: 50%;
  transform: translate(-50%, -50%);
  width: 12px;
  height: 12px;
  background: #fff;
  border-radius: 50%;
  box-shadow: 0 2px 8px rgba(0,0,0,0.3);
}

/* 控制按钮 */
.control-buttons {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 32px;
}

.ctrl-btn {
  border: none;
  background: none;
  color: #fff;
  font-size: 24px;
  cursor: pointer;
  opacity: 0.8;
  transition: opacity 0.2s;
}

.ctrl-btn:hover {
  opacity: 1;
}

.play-btn {
  width: 64px;
  height: 64px;
  background: #31c27c;
  border-radius: 50%;
  font-size: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.play-btn:hover {
  background: #28a86b;
}

.mode-btn, .list-btn {
  font-size: 20px;
}

/* 下拉提示 */
.swipe-hint {
  position: absolute;
  top: 60px;
  left: 50%;
  transform: translateX(-50%);
  font-size: 12px;
  opacity: 0.5;
  animation: bounce 2s infinite;
}

@keyframes bounce {
  0%, 100% { transform: translateX(-50%) translateY(0); }
  50% { transform: translateX(-50%) translateY(5px); }
}

/* 上滑动画 */
.lyrics-slide-enter-active,
.lyrics-slide-leave-active {
  transition: transform 0.4s cubic-bezier(0.32, 0.72, 0, 1);
}

.lyrics-slide-enter-from,
.lyrics-slide-leave-to {
  transform: translateY(100%);
}
</style>
