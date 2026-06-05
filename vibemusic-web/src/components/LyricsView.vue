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

// ===== 播放模式 =====
const playMode = ref('sequential') // sequential | random | single
const modeLabel = computed(() => ({ sequential: '顺序播放', random: '随机播放', single: '单曲循环' }[playMode.value]))
function toggleMode() {
  const modes = ['sequential', 'random', 'single']
  const i = modes.indexOf(playMode.value)
  playMode.value = modes[(i + 1) % 3]
  // 同步到 PlayerBar 的 playMode
  if (window.vibePlayMode) window.vibePlayMode(playMode.value)
}

// ===== 播放列表 =====
const showPlaylist = ref(false)
const queue = computed(() => window.vibeQueue?.value || [])
const currentIdx = computed(() => parseInt(localStorage.getItem('vibe_queue_idx') || '-1'))
function playFromQueue(idx) {
  if (window.vibePlayQueue) window.vibePlayQueue(idx)
}
function removeFromQueue(idx, e) {
  e.stopPropagation()
  const q = window.vibeQueue?.value
  if (q) q.splice(idx, 1)
}

// ===== 下载 =====
const downloading = ref(false)
async function handleDownload() {
  if (!props.currentSong.id || downloading.value) return
  downloading.value = true
  try {
    // 获取播放URL
    const audioUrl = window.vibeAudio?.src
    if (audioUrl && audioUrl.startsWith('http')) {
      const resp = await fetch(audioUrl)
      const blob = await resp.blob()
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `${props.currentSong.title} - ${props.currentSong.artist}.mp3`
      document.body.appendChild(a); a.click(); document.body.removeChild(a)
      URL.revokeObjectURL(url)
    }
  } catch (e) { console.error('下载失败:', e) }
  finally { downloading.value = false }
}

// ===== 歌词 =====
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
            <!-- 向左箭头 -->
            <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 12H5m0 0l7-7m-7 7l7 7"/></svg>
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
          <!-- 进度条 -->
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
            <!-- 左侧：歌曲信息 + 收藏 -->
            <div class="left-info">
              <div class="mini-song">
                <span class="mini-name">{{ currentSong.title }}</span>
                <span class="mini-artist"> - {{ currentSong.artist }}</span>
              </div>
              <button class="func-btn" title="收藏">
                <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>
              </button>
            </div>

            <!-- 中间：播放控制 -->
            <div class="center-ctrl">
              <button class="ctrl-btn mode-btn" :class="{ active: playMode !== 'sequential' }" @click="toggleMode" :title="modeLabel">
                <!-- 顺序: 循环箭头 | 随机: 交叉箭头 | 单曲: 循环+1 -->
                <svg v-if="playMode === 'sequential'" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 1l4 4-4 4"/><path d="M3 11V9a4 4 0 0 1 4-4h14"/><path d="M7 23l-4-4 4-4"/><path d="M21 13v2a4 4 0 0 1-4 4H3"/></svg>
                <svg v-else-if="playMode === 'random'" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 1l4 4-4 4"/><path d="M3 11V9a4 4 0 0 1 4-4h14"/><path d="M7 23l-4-4 4-4"/><path d="M21 13v2a4 4 0 0 1-4 4H3"/></svg>
                <svg v-else viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 1l4 4-4 4"/><path d="M3 11V9a4 4 0 0 1 4-4h14"/><rect x="7" y="13" width="10" height="10" rx="2"/><text x="12" y="21" text-anchor="middle" font-size="8" fill="currentColor" stroke="none">1</text></svg>
              </button>
              <button class="ctrl-btn skip" @click="$emit('prev')" title="上一首">
                <svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><path d="M6 6h2v12H6zm3.5 6 8.5 6V6z"/></svg>
              </button>
              <button class="ctrl-btn main" @click="$emit('togglePlay')" title="播放/暂停">
                <svg v-if="isPlaying" viewBox="0 0 24 24" width="26" height="26" fill="currentColor"><rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/></svg>
                <svg v-else viewBox="0 0 24 24" width="26" height="26" fill="currentColor"><polygon points="8,5 19,12 8,19"/></svg>
              </button>
              <button class="ctrl-btn skip" @click="$emit('next')" title="下一首">
                <svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/></svg>
              </button>
            </div>

            <!-- 右侧：下载 + 音量 + 列表 -->
            <div class="right-actions">
              <button class="act-icon" :class="{ downloading }" @click="handleDownload" title="下载">
                <svg v-if="!downloading" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
                <svg v-else viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" class="spin-icon"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>
              </button>
              <div class="vol-group">
                <button class="act-icon" @click="toggleMute" title="音量">
                  <svg v-if="isMuted || volume === 0" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><line x1="23" y1="9" x2="17" y2="15"/><line x1="17" y1="9" x2="23" y2="15"/></svg>
                  <svg v-else viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><path d="M15.54 8.46a5 5 0 0 1 0 7.07"/></svg>
                </button>
                <div class="vol-bar" @click="onVolumeClick">
                  <div class="vol-fill" :style="{ width: (isMuted ? 0 : volume) + '%' }"></div>
                </div>
              </div>
              <button class="act-icon" :class="{ active: showPlaylist }" @click="showPlaylist = !showPlaylist" title="播放列表">
                <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/></svg>
              </button>
            </div>
          </div>
        </footer>

        <!-- 播放列表面板 -->
        <Transition name="panel-slide">
          <div v-if="showPlaylist" class="playlist-panel">
            <div class="panel-hd">
              <span>播放队列 ({{ queue.length }})</span>
              <button class="panel-close" @click="showPlaylist = false">
                <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
              </button>
            </div>
            <div class="panel-body">
              <div v-if="queue.length === 0" class="panel-empty">队列为空</div>
              <div v-for="(song, idx) in queue" :key="song.sourceId"
                class="panel-item" :class="{ now: idx === currentIdx }"
                @click="playFromQueue(idx)">
                <span class="panel-idx">{{ idx === currentIdx ? '▶' : idx + 1 }}</span>
                <div class="panel-info">
                  <span class="panel-title">{{ song.name }}</span>
                  <span class="panel-art">{{ song.artist }}</span>
                </div>
                <button class="panel-del" @click="removeFromQueue(idx, $event)">✕</button>
              </div>
            </div>
          </div>
        </Transition>
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
.top { display: flex; align-items: center; justify-content: space-between; padding: 18px 20px 10px; }
.btn-icon { width: 38px; height: 38px; border: none; background: rgba(255,255,255,0.06); border-radius: 50%; color: rgba(255,255,255,0.7); cursor: pointer; display: flex; align-items: center; justify-content: center; transition: .2s; }
.btn-icon:hover { background: rgba(255,255,255,0.12); color: #fff; }
.top-center { text-align: center; }
.song-name { font-size: 19px; font-weight: 600; color: #fff; letter-spacing: 0.5px; }
.artist-name { font-size: 14px; color: rgba(255,255,255,0.5); margin-top: 3px; }

/* 主体 — 左碟片右歌词，均靠上 */
.stage { flex: 1; display: flex; align-items: flex-start; padding: 20px 48px 0; gap: 48px; overflow: hidden; }

/* 碟片 */
.left { width: 42%; display: flex; align-items: flex-start; justify-content: center; padding-top: 10px; }
.disc-box { position: relative; width: 100%; max-width: 320px; aspect-ratio: 1; }
/* 修复：旋转轴心设为碟片中心 */
.disc { position: absolute; inset: 0; transform-origin: center center; animation: spin 18s linear infinite; animation-play-state: paused; }
.disc.spin { animation-play-state: running; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

.disc-outer { position: absolute; inset: 0; border-radius: 50%; background: linear-gradient(145deg, #1a1a1a, #0d0d0d); box-shadow: 0 0 0 1px rgba(255,255,255,0.06), 0 20px 60px rgba(0,0,0,0.6), inset 0 0 40px rgba(0,0,0,0.4); }
.disc-inner { position: absolute; inset: 20%; border-radius: 50%; overflow: hidden; box-shadow: 0 0 0 2px rgba(255,255,255,0.04); }
.disc-inner img { width: 100%; height: 100%; object-fit: cover; }
.disc-shine { position: absolute; inset: 0; border-radius: 50%; background: linear-gradient(135deg, rgba(255,255,255,0.06) 0%, transparent 40%, transparent 60%, rgba(255,255,255,0.02) 100%); pointer-events: none; }

/* 唱臂 — 轴心在 disc-box 右上角 */
.arm { position: absolute; top: -10%; right: 3%; z-index: 5; transform-origin: 82% 16%; transform: rotate(22deg); transition: transform 0.6s cubic-bezier(0.25, 0.1, 0.25, 1); }
.arm.down { transform: rotate(8deg); }
.arm-base { width: 24px; height: 24px; background: radial-gradient(circle at 30% 30%, #666, #1a1a1a); border-radius: 50%; box-shadow: 0 2px 8px rgba(0,0,0,0.5); }
.arm-stick { position: absolute; top: 10px; right: 12px; width: 70px; height: 3px; background: linear-gradient(180deg, #bbb, #777); border-radius: 2px; transform-origin: right; transform: rotate(-12deg); box-shadow: 0 1px 3px rgba(0,0,0,0.4); }
.arm-head { position: absolute; top: 0; left: -6px; width: 18px; height: 9px; background: linear-gradient(180deg, #999, #555); border-radius: 2px; box-shadow: 0 1px 2px rgba(0,0,0,0.5); }

/* 歌词 — 增大宽度高度 */
.right { flex: 1; height: 100%; padding-top: 20px; overflow: hidden; }
.lyric-box { height: 100%; overflow-y: auto; padding: 140px 24px 240px; scroll-behavior: smooth; }
.lyric-box::-webkit-scrollbar { display: none; }
.lyric-line { padding: 18px 0; font-size: 17px; line-height: 1.8; color: rgba(255,255,255,0.32); text-align: left; transition: all .4s; cursor: pointer; letter-spacing: .5px; }
.lyric-line:hover { color: rgba(255,255,255,0.5); }
.lyric-line.current { font-size: 22px; font-weight: 700; color: #2ecc71; text-shadow: 0 0 24px rgba(46,204,113,0.45); }
.empty { text-align: center; padding-top: 40%; color: rgba(255,255,255,0.3); font-size: 16px; }

/* 底部栏 — 增高 */
.bar { padding: 10px 28px 28px; }
.progress-wrap { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.time { font-size: 13px; color: rgba(255,255,255,0.45); min-width: 40px; font-variant-numeric: tabular-nums; }
.progress-track { flex: 1; height: 4px; background: rgba(255,255,255,0.1); border-radius: 2px; cursor: pointer; position: relative; }
.progress-track:hover { height: 6px; }
.progress-fill { height: 100%; background: #2ecc71; border-radius: 2px; position: relative; }
.thumb { position: absolute; right: -5px; top: 50%; transform: translateY(-50%); width: 10px; height: 10px; background: #2ecc71; border-radius: 50%; opacity: 0; transition: opacity .2s; }
.progress-track:hover .thumb { opacity: 1; }

.ctrl-wrap { display: flex; align-items: center; justify-content: space-between; }

/* 左侧 */
.left-info { display: flex; align-items: center; gap: 14px; min-width: 180px; }
.mini-song { color: rgba(255,255,255,0.6); font-size: 14px; }
.mini-name { color: #fff; font-weight: 500; }
.mini-artist { color: rgba(255,255,255,0.45); }
.func-btn { width: 36px; height: 36px; border: none; background: rgba(255,255,255,0.06); border-radius: 50%; color: rgba(255,255,255,0.6); cursor: pointer; display: flex; align-items: center; justify-content: center; transition: .2s; }
.func-btn:hover { background: rgba(255,255,255,0.12); color: #ec4141; }

/* 中间播放 */
.center-ctrl { display: flex; align-items: center; gap: 28px; position: absolute; left: 50%; transform: translateX(-50%); }
.ctrl-btn { border: none; background: none; color: rgba(255,255,255,0.75); cursor: pointer; display: flex; align-items: center; justify-content: center; transition: .2s; }
.ctrl-btn:hover { color: #fff; }
.ctrl-btn.mode-btn { width: 34px; height: 34px; border-radius: 50%; background: rgba(255,255,255,0.05); }
.ctrl-btn.mode-btn.active { color: #2ecc71; background: rgba(46,204,113,0.15); }
.ctrl-btn.mode-btn:hover { background: rgba(255,255,255,0.1); }
.ctrl-btn.skip { opacity: 0.75; padding: 4px; }
.ctrl-btn.skip:hover { opacity: 1; }
.ctrl-btn.main { width: 56px; height: 56px; border-radius: 50%; background: #2ecc71; color: #fff; box-shadow: 0 4px 24px rgba(46,204,113,0.35); }
.ctrl-btn.main:hover { transform: scale(1.06); box-shadow: 0 6px 32px rgba(46,204,113,0.5); }

/* 右侧操作 */
.right-actions { display: flex; align-items: center; gap: 12px; min-width: 180px; justify-content: flex-end; }
.act-icon { width: 34px; height: 34px; border: none; background: none; color: rgba(255,255,255,0.5); cursor: pointer; display: flex; align-items: center; justify-content: center; transition: .2s; }
.act-icon:hover { color: #fff; }
.act-icon.active { color: #2ecc71; }
.act-icon.downloading { color: #2ecc71; }
.spin-icon { animation: spin 1.2s linear infinite; }
.vol-group { display: flex; align-items: center; gap: 6px; }
.vol-bar { width: 72px; height: 3px; background: rgba(255,255,255,0.1); border-radius: 2px; cursor: pointer; }
.vol-bar:hover { height: 4px; }
.vol-fill { height: 100%; background: rgba(255,255,255,0.5); border-radius: 2px; }
.vol-bar:hover .vol-fill { background: #2ecc71; }

/* ===== 播放列表面板 ===== */
.playlist-panel {
  position: fixed; right: 0; top: 0; bottom: 0; width: 340px;
  background: rgba(8,18,12,0.97); backdrop-filter: blur(20px);
  border-left: 1px solid rgba(255,255,255,0.06);
  z-index: 210; display: flex; flex-direction: column;
}
.panel-hd {
  display: flex; align-items: center; justify-content: space-between;
  padding: 20px 20px 14px; color: #fff; font-size: 16px; font-weight: 600;
  border-bottom: 1px solid rgba(255,255,255,0.06);
}
.panel-close { background: none; border: none; color: rgba(255,255,255,0.5); cursor: pointer; }
.panel-close:hover { color: #fff; }
.panel-body { flex: 1; overflow-y: auto; padding: 8px 0; }
.panel-empty { text-align: center; color: rgba(255,255,255,0.3); padding: 80px 0; }
.panel-item { display: flex; align-items: center; gap: 12px; padding: 12px 20px; cursor: pointer; transition: .15s; }
.panel-item:hover { background: rgba(255,255,255,0.04); }
.panel-item.now { background: rgba(46,204,113,0.1); }
.panel-item.now .panel-title { color: #2ecc71; }
.panel-idx { width: 24px; color: rgba(255,255,255,0.3); font-size: 13px; text-align: center; flex-shrink: 0; }
.panel-item.now .panel-idx { color: #2ecc71; }
.panel-info { flex: 1; min-width: 0; }
.panel-title { font-size: 14px; color: rgba(255,255,255,0.85); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.panel-art { font-size: 12px; color: rgba(255,255,255,0.4); margin-top: 2px; }
.panel-del { background: none; border: none; color: rgba(255,255,255,0.25); cursor: pointer; font-size: 14px; }
.panel-del:hover { color: #ec4141; }

/* Transitions */
.slide-enter-active, .slide-leave-active { transition: transform .4s cubic-bezier(.32,.72,0,1); }
.slide-enter-from, .slide-leave-to { transform: translateY(100%); }
.panel-slide-enter-active, .panel-slide-leave-active { transition: transform .3s ease; }
.panel-slide-enter-from, .panel-slide-leave-to { transform: translateX(100%); }
</style>
