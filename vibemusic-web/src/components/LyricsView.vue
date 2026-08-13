<script setup>
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { getLyric, downloadSong as apiDownload } from '@/api/song'
import { API_HOST } from '@/api/request'
import { useClickOutside } from '@/composables/useClickOutside'
import { usePlayerStore } from '@/stores/player'
import { useFavoriteStore } from '@/stores/favorite'

const player = usePlayerStore()
const favStore = useFavoriteStore()

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
let timeRafId = null

function startTimeSync() {
  stopTimeSync()
  // rAF 驱动（~16ms）：200ms 的 setInterval 会让逐字高亮"一步一个字"，卡顿不连贯。
  // rAF 由浏览器在绘制前回调，让歌词亮度与动画同步，平滑流动（主流播放器同款）。
  const loop = () => {
    const a = window.vibeAudio
    if (a) { currentTime.value = a.currentTime; volume.value = Math.round(a.volume * 100); isMuted.value = a.muted }
    timeRafId = requestAnimationFrame(loop)
  }
  timeRafId = requestAnimationFrame(loop)
}
function stopTimeSync() { if (timeRafId) { cancelAnimationFrame(timeRafId); timeRafId = null } }



// ===== D2: 封面主色提取（Apple Music 范式）=====
// 模块级缓存：coverUrl → { color, alpha }，同一封面只提取一次，绝不逐帧
const coverColorCache = new Map()
const dominantColor = ref('')
const dominantAlpha = ref(0.55)

// 相对亮度（ITU-R BT.601 加权），用于文字对比度自适应
function colorLuminance(r, g, b) {
  return (0.299 * r + 0.587 * g + 0.114 * b) / 255
}

function extractDominantColor(coverUrl) {
  if (!coverUrl) { dominantColor.value = ''; return }
  const cached = coverColorCache.get(coverUrl)
  if (cached) {
    dominantColor.value = cached.color
    dominantAlpha.value = cached.alpha
    return
  }
  const img = new Image()
  // 先按匿名跨域加载（网易云 CDN 带 CORS 头可成功取色）；
  // 失败则去掉 crossOrigin 重试（可显示但 canvas 被污染 → 取色失败静默回退现有渐变）
  img.crossOrigin = 'anonymous'
  img.onload = () => {
    try {
      // 缩小到 4x4 取平均色 —— 一次提取，性能红线内
      const cvs = document.createElement('canvas')
      cvs.width = 4
      cvs.height = 4
      const ctx = cvs.getContext('2d')
      ctx.drawImage(img, 0, 0, 4, 4)
      const { data } = ctx.getImageData(0, 0, 4, 4)
      let r = 0, g = 0, b = 0
      for (let i = 0; i < data.length; i += 4) {
        r += data[i]; g += data[i + 1]; b += data[i + 2]
      }
      const n = data.length / 4
      r = Math.round(r / n); g = Math.round(g / n); b = Math.round(b / n)
      // 亮度高（浅色封面）→ 降低背景色透明度，保证白色歌词对比度
      const alpha = colorLuminance(r, g, b) > 0.5 ? 0.35 : 0.55
      const entry = { color: `rgb(${r}, ${g}, ${b})`, alpha }
      coverColorCache.set(coverUrl, entry)
      dominantColor.value = entry.color
      dominantAlpha.value = entry.alpha
    } catch {
      // canvas 被污染（跨域无 CORS 头，如 QQ 音乐 CDN）→ 保持现有渐变
    }
  }
  img.onerror = () => {
    if (img.crossOrigin) {
      img.crossOrigin = ''
      img.src = coverUrl + '?param=200y200'
    }
  }
  img.src = coverUrl + '?param=200y200'
}

// 背景色渐变：顶部主色 → 透明，叠加在既有 .bg-grad 之上（透明度随亮度自适应）
const bgTintStyle = computed(() => {
  if (!dominantColor.value) return {}
  return {
    background: `linear-gradient(180deg, ${dominantColor.value} 0%, rgba(0,0,0,0) 55%)`,
    opacity: dominantAlpha.value
  }
})

// 切歌时提取新封面主色（缓存命中则直接复用）
watch(() => props.currentSong.coverUrl, (url) => {
  if (url) extractDominantColor(url)
}, { immediate: true })

// ===== 频谱可视化 =====
// 优先使用全局 AnalyserNode (PlayerBar 创建) 获取真实音频频谱数据
// 不可用时回退到程序化模拟
const spectrumCanvas = ref(null)
let spectrumRafId = null
let canvasReady = false
let specStartTime = 0
let useRealAudio = false
// 柱条渐变只依赖柱高（y = h - bh），按高度分桶预计算复用，避免每帧每柱 createLinearGradient
const GRAD_BUCKETS = 24
let barGrads = []

function initSpectrum() {
  _specInited = false
  specStartTime = performance.now()
}


function initCanvasSize() {
  if (canvasReady || !spectrumCanvas.value) return
  const cvs = spectrumCanvas.value
  const w = cvs.clientWidth
  const h = cvs.clientHeight
  if (w === 0 || h === 0) return
  cvs.width = w * devicePixelRatio
  cvs.height = h * devicePixelRatio
  buildBarGradients(cvs)
  canvasReady = true
}

// 渐变按柱高量化分桶预计算（resize 时重建），绘制时用 globalAlpha 缩放透明度
function buildBarGradients(cvs) {
  const ctx = cvs.getContext('2d')
  const h = cvs.height / devicePixelRatio
  const maxH = h - 4
  barGrads = []
  for (let b = 0; b < GRAD_BUCKETS; b++) {
    const bh = ((b + 0.5) / GRAD_BUCKETS) * maxH
    const y = h - bh
    const g = ctx.createLinearGradient(0, y, 0, h)
    g.addColorStop(0, 'rgba(100,220,150,1)')
    g.addColorStop(0.4, 'rgba(49,194,124,0.5)')
    g.addColorStop(1, 'rgba(49,194,124,0)')
    barGrads.push(g)
  }
}

// iOS Safari <16.4 无 ctx.roundRect，手动绘制圆角矩形路径（arcTo 半径 0 即直角）
function roundRectPath(ctx, x, y, w, h, radii) {
  const [tl, tr, br, bl] = radii
  ctx.moveTo(x + tl, y)
  ctx.arcTo(x + w, y, x + w, y + h, tr)
  ctx.arcTo(x + w, y + h, x, y + h, br)
  ctx.arcTo(x, y + h, x, y, bl)
  ctx.arcTo(x, y, x + w, y, tl)
  ctx.closePath()
}

// ==== 真实音频数据 ====
let realData = null
function getRealData() {
  const a = window._vibeAnalyser
  if (!a) { useRealAudio = false; return null }
  if (!realData || realData.length !== a.frequencyBinCount) {
    realData = new Uint8Array(a.frequencyBinCount)
  }
  a.getByteFrequencyData(realData)
  // 检测 CORS 零数据
  const sum = realData.reduce((s, v) => s + v, 0)
  if (sum === 0) { useRealAudio = false; return null }
  useRealAudio = true
  return realData
}

// ==== 程序化回退数据 ====
const fakeData = new Float64Array(64)
const seeds = new Float64Array(64)
const walkPhase = new Float64Array(64)
const walkSpeed = new Float64Array(64)
const walkAmp = new Float64Array(64)
const barEnergy = new Float64Array(64)
let _specInited = false

function initSpecState() {
  for (let i = 0; i < 64; i++) {
    seeds[i] = Math.random() * Math.PI * 2
    walkPhase[i] = Math.random() * Math.PI * 2
    walkSpeed[i] = 0.3 + Math.random() * 0.9
    walkAmp[i] = 0.3 + Math.random() * 0.5
    barEnergy[i] = 0
  }
  _specInited = true
}

function getFakeData() {
  if (!_specInited) initSpecState()
  const t = (performance.now() - specStartTime) / 1000
  const playing = window.vibeAudio && !window.vibeAudio.paused
  const bps = 2 // 120 BPM
  const beat = (t * bps) % 1
  const beatDecay = Math.exp(-beat * 4)
  const macroEnv = 0.6 + 0.4 * Math.abs(Math.sin(t * 0.13 + Math.sin(t * 0.07) * 1.5))
  for (let i = 0; i < 64; i++) {
    const f = (i + 1) / 64
    let v = 0
    v += Math.sin(t * (2.1 + f * 6) + seeds[i]) * 0.35
    v += Math.sin(t * (3.7 + f * 4.5) + seeds[i] * 1.7) * 0.28
    v += Math.cos(t * (5.2 + f * 8.3) + seeds[i] * 0.6) * 0.22
    walkPhase[i] += walkSpeed[i] * 0.03
    v += Math.sin(walkPhase[i]) * walkAmp[i]
    const beatBoost = beatDecay * (f < 0.4 ? 0.9 : 0.3)
    v += beatBoost * (0.3 + Math.random() * 0.4)
    const target = Math.abs(v)
    if (target > barEnergy[i]) barEnergy[i] += (target - barEnergy[i]) * 0.65
    else barEnergy[i] += (target - barEnergy[i]) * 0.15
    v = barEnergy[i] * macroEnv
    if (!playing) v *= 0.08
    fakeData[i] = Math.max(0, Math.min(1, v))
  }
  return fakeData
}

function drawSpectrum() {
  // 页面不可见时停止 rAF 循环（visibilitychange 也会触发 stopSpectrum）
  if (document.hidden) { stopSpectrum(); return }
  // 窗口失焦时暂停动画（节省资源）
  if (!document.hasFocus()) {
    spectrumRafId = requestAnimationFrame(drawSpectrum)
    return
  }

  initCanvasSize()
  if (!spectrumCanvas.value || !canvasReady) {
    spectrumRafId = requestAnimationFrame(drawSpectrum)
    return
  }
  const cvs = spectrumCanvas.value
  const ctx = cvs.getContext('2d')
  const dpr = devicePixelRatio
  const w = cvs.width / dpr   // 全宽 ~1400px
  const h = cvs.height / dpr  // 64px

  // 获取数据: 真实 > 回退
  let bars = null
  const rd = getRealData()
  if (rd) {
    bars = rd
  } else {
    bars = getFakeData()
  }

  ctx.clearRect(0, 0, cvs.width, cvs.height)

  // 96 根柱条, 每根约 4px 宽
  const barNum = 96
  const gap = 2
  const bw = Math.max(4, (w - gap * (barNum + 1)) / barNum)

  for (let i = 0; i < barNum; i++) {
    const srcIdx = Math.floor(i * bars.length / barNum)
    let v
    if (useRealAudio) {
      v = bars[srcIdx] / 255
    } else {
      v = bars[srcIdx] // already 0..1
    }

    // 幂次曲线: 中间高、两侧低，更平滑自然
    const center = (barNum - 1) / 2
    const dist = Math.abs(i - center) / center
    const curveWeight = Math.pow(Math.cos(dist * Math.PI * 0.5), 1.5)
    const weightedV = v * curveWeight

    const maxH = h - 4
    const bh = weightedV * maxH  // 零值柱子不显示，消除底部虚线
    if (bh < 0.5) continue       // 值太低直接跳过，不画
    const x = gap + i * (bw + gap)
    const y = h - bh

    // 统一使用 #31c27c 品牌绿，偏浅色调
    const alpha = 0.7 + weightedV * 0.3

    // 渐变按高度分桶预计算复用；透明度用 globalAlpha 缩放（等效原逐柱渐变）
    const bucket = Math.min(GRAD_BUCKETS - 1, Math.floor((bh / maxH) * GRAD_BUCKETS))
    ctx.globalAlpha = alpha
    ctx.fillStyle = barGrads[bucket]

    // 顶部圆角 rx = 宽度的一半
    const rx = bw / 2
    ctx.beginPath()
    if (typeof ctx.roundRect === 'function') {
      ctx.roundRect(x, y, bw, bh, [rx, rx, 0, 0])
    } else {
      // iOS Safari <16.4 无 roundRect，手动绘制圆角路径
      roundRectPath(ctx, x, y, bw, bh, [rx, rx, 0, 0])
    }
    ctx.fill()
    ctx.globalAlpha = 1
  }
  spectrumRafId = requestAnimationFrame(drawSpectrum)
}

function stopSpectrum() {
  if (spectrumRafId) { cancelAnimationFrame(spectrumRafId); spectrumRafId = null }
  canvasReady = false
}

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
const modeLabel = computed(() => player.modeLabels[player.playMode] || '列表循环')

// ===== 播放列表 =====
const showPlaylist = ref(false)
const lyricsPlaylistPanelRef = ref(null)
const lyricsPlaylistToggleRef = ref(null)
useClickOutside(lyricsPlaylistPanelRef, () => { showPlaylist.value = false }, { exclude: [lyricsPlaylistToggleRef] })

// ===== 收藏 =====
const isFav = computed(() => favStore.isFav(props.currentSong.id))
async function handleFav() {
  if (!props.currentSong.id) return
  await favStore.toggleFav({
    sourceId: props.currentSong.id,
    name: props.currentSong.title,
    artist: props.currentSong.artist,
    coverUrl: props.currentSong.coverUrl
  })
}

// ===== 下载 =====
const downloading = ref(false)
async function handleDownload() {
  if (!props.currentSong.id || downloading.value) return
  downloading.value = true
  try {
    // 走后端 API 下载到 MinIO + 浏览器下载
    await apiDownload(props.currentSong.id, {
      name: props.currentSong.title,
      artist: props.currentSong.artist,
      coverUrl: props.currentSong.coverUrl || '',
    })
    // 后端已存 MinIO，触发浏览器下载
    const a = document.createElement('a')
    a.href = `${API_HOST}/api/download/file/${props.currentSong.id}`
    a.download = `${props.currentSong.title || props.currentSong.id}.mp3`
    document.body.appendChild(a); a.click(); document.body.removeChild(a)
  } catch (e) { console.error('下载失败:', e) }
  finally { downloading.value = false }
}

// ===== 歌词 =====
const lyrics = ref([])
const loadedForId = ref('')
async function fetchLyric(sourceId) {
  if (!sourceId) { lyrics.value = []; loadedForId.value = ''; return }
  // 切歌时保留旧歌词直到新歌词就绪，避免"加载中"闪烁（主流播放器同策略）
  loadingLyric.value = true
  try {
    const res = await getLyric(sourceId)
    lyrics.value = res.data || []
    loadedForId.value = sourceId
    currentLyricIndex.value = 0
    setTimeout(() => scrollToCurrent(), 200)
  } catch {
    lyrics.value = []
  }
  finally { loadingLyric.value = false }
}

// ===== 逐字高亮（Karaoke 风格，连续流动版 v3）=====
// 主流播放器效果 = "亮度连续流动"，而非每字突变。
// 方案：行内进度用浮点数 charFloat（0~total），每字亮度按它距该字的距离做线性插值，
// 相邻两字之间亮度连续过渡，视觉上像 gradient 平滑滑动，但仍是 span+color 只重绘单字，性能友好
const lyricChars = computed(() => {
  const line = lyrics.value[currentLyricIndex.value]
  return line && typeof line.text === 'string' ? Array.from(line.text) : []
})
// 当前行内进度（浮点，0 ~ total 字符数；无 yrc 逐字数据，行时长按字符均分）
const charFloat = computed(() => {
  const t = currentTime.value
  const lines = lyrics.value
  const idx = currentLyricIndex.value
  if (!lines.length || idx >= lines.length) return 0
  const total = lyricChars.value.length
  if (!total) return 0
  const start = parseFloat(lines[idx].time || 0)
  const end = idx + 1 < lines.length ? parseFloat(lines[idx + 1].time || 0) : start + 5
  if (t <= start) return 0
  if (t >= end) return total
  return ((t - start) / (end - start)) * total
})
// 每个字符的亮度（0~1）：已过的字全亮，当前字按插值渐亮，未到的字暗
function charAlpha(ci) {
  const f = charFloat.value
  if (!Number.isFinite(f)) return 0.45
  if (f <= ci) return 0.45          // 未唱到：暗
  if (f >= ci + 1) return 1         // 已唱完：全亮
  // 正在唱的字符（介于 ci ~ ci+1）：线性插值 0.45 → 1
  return 0.45 + (f - ci) * 0.55
}

// ===== D4: mini 封面 → 全屏封面 FLIP 过渡 =====
// First(记录 PlayerBar mini-cover rect) → Last(视图滑入完成后取 disc rect)
// → Invert(无过渡置位到起点) → Play(过渡到 identity，ease-out 400ms，仅 transform 合成器友好)
const discBoxEl = ref(null)
let flipTimer = null

function runCoverFlip() {
  const mini = document.querySelector('.mini-cover')
  const disc = discBoxEl.value
  if (!mini || !disc) return  // 找不到起点/终点 → 降级：无 FLIP，直接显示
  const first = mini.getBoundingClientRect()
  const view = lyricsViewEl.value
  let done = false
  const doFlip = () => {
    if (done) return
    done = true
    if (!props.visible || !discBoxEl.value) return  // 视图已关闭则跳过
    const last = disc.getBoundingClientRect()
    const dx = first.left - last.left
    const dy = first.top - last.top
    const sx = first.width / last.width
    const sy = first.height / last.height
    // Invert: 无过渡，把封面放到 mini-cover 的位置/尺寸
    disc.style.transition = 'none'
    disc.style.transform = `translate(${dx}px, ${dy}px) scale(${sx}, ${sy})`
    disc.style.transformOrigin = 'center center'
    void disc.offsetWidth  // 强制重排，确保 Invert 生效
    // Play: 过渡到 identity（A1 --ease-out 同款曲线）
    disc.style.transition = 'transform .4s cubic-bezier(.2,0,0,1)'
    disc.style.transform = 'none'
    const onEnd = () => {
      disc.style.transition = ''
      disc.style.transform = ''
      disc.removeEventListener('transitionend', onEnd)
    }
    disc.addEventListener('transitionend', onEnd)
  }
  if (view) {
    // 等 slide 入场动画结束（视图位移稳定）再取 Last，避免终点偏移
    view.addEventListener('transitionend', doFlip, { once: true })
    flipTimer = setTimeout(doFlip, 500)  // 兜底：transitionend 未触发时
  } else {
    doFlip()
  }
}

function cancelCoverFlip() {
  clearTimeout(flipTimer)
  flipTimer = null
  if (discBoxEl.value) {
    discBoxEl.value.style.transition = ''
    discBoxEl.value.style.transform = ''
  }
}

watch(() => props.visible, (val) => {
  if (val) {
    currentTime.value = window.vibeAudio?.currentTime || 0
    volume.value = Math.round((window.vibeAudio?.volume || 1) * 100)
    isMuted.value = window.vibeAudio?.muted || false
    startTimeSync()
    if (props.currentSong.id) fetchLyric(props.currentSong.id)
    // 启动频谱可视化
    canvasReady = false
    initSpectrum()
    if (!spectrumRafId) drawSpectrum()
    // D4: 视图渲染后执行 mini 封面 → 全屏封面 FLIP
    nextTick(() => runCoverFlip())
  } else {
    stopTimeSync()
    stopSpectrum()
    exitFullscreen()
    cancelCoverFlip()
  }
})

// 切歌时自动刷新歌词
watch(() => props.currentSong.id, (newId) => {
  if (props.visible && newId) fetchLyric(newId)
})

// 页面隐藏时取消频谱 rAF，恢复可见时重启
function onVisibilityChange() {
  if (document.hidden) {
    stopSpectrum()
  } else if (props.visible) {
    canvasReady = false
    initSpectrum()
    if (!spectrumRafId) drawSpectrum()
  }
}

onMounted(() => {
  document.addEventListener('fullscreenchange', onFullscreenChange)
  document.addEventListener('visibilitychange', onVisibilityChange)
})
onUnmounted(() => {
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  stopTimeSync(); stopSpectrum(); exitFullscreen()
})

const currentLyricIndex = ref(0)
watch(() => currentTime.value, () => {
  const t = currentTime.value
  const lines = lyrics.value
  if (!lines.length) { currentLyricIndex.value = 0; return }
  // 时间单调前进时从当前行向后扫（均摊 O(1)），回退（拖进度条）时从末尾反扫
  let idx = Math.min(currentLyricIndex.value, lines.length - 1)
  if (t >= parseFloat(lines[idx].time || 0)) {
    while (idx + 1 < lines.length && t >= parseFloat(lines[idx + 1].time || 0)) idx++
  } else {
    idx = 0
    for (let i = lines.length - 1; i >= 0; i--) {
      if (t >= parseFloat(lines[i].time || 0)) { idx = i; break }
    }
  }
  currentLyricIndex.value = idx
})

function scrollToCurrent() {
  if (!lyricsContainer.value) return
  const items = lyricsContainer.value.querySelectorAll('.lyric-line')
  const el = items[currentLyricIndex.value]
  if (el) {
    const ch = lyricsContainer.value.clientHeight
    // behavior: 'auto' 避免逐帧平滑滚动重排；滚动仅由歌词 index 变化触发
    lyricsContainer.value.scrollTo({ top: el.offsetTop - ch / 2 + el.offsetHeight / 2, behavior: 'auto' })
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
function onFullscreenChange() { if (!document.fullscreenElement) isFullscreen.value = false }

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
          <!-- D2: 封面主色渐变叠加层（canvas 提取，失败时保持透明回退） -->
          <div class="bg-tint" :style="bgTintStyle"></div>
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
            <div class="disc-box" ref="discBoxEl">
              <div class="disc" :class="{ spin: isPlaying }">
                <div class="disc-outer"></div>
                <div class="disc-inner">
                  <img :src="currentSong.coverUrl ? currentSong.coverUrl + '?param=400y400' : ''" alt="" />
                </div>
                <div class="disc-shine"></div>
              </div>
            </div>
          </div>

          <!-- 右侧：歌词 -->
          <div class="right">
            <div ref="lyricsContainer" class="lyric-box">
              <div v-if="!loadedForId" class="empty">{{ loadingLyric ? '加载中...' : '暂无歌词' }}</div>
              <div v-for="(line, i) in lyrics" :key="i"
                class="lyric-line" :class="{ current: i === currentLyricIndex }"
                @click="window.vibeAudio && (window.vibeAudio.currentTime = parseFloat(line.time))">
                <!-- 逐字高亮（连续流动）：每字一个 span，亮度 = charAlpha(ci) 线性插值，相邻字之间平滑过渡 -->
                <span v-for="(c, ci) in (i === currentLyricIndex ? lyricChars : line.text.split(''))" :key="ci"
                  class="ch"
                  :class="{ current: i === currentLyricIndex }"
                  :style="i === currentLyricIndex ? { color: `rgba(255,255,255,${charAlpha(ci).toFixed(3)})` } : null">{{ c }}</span>
              </div>
            </div>
          </div>
        </main>

        <!-- 频谱 — 底部栏上方，水平全宽 -->
        <div class="spectrum-row">
          <canvas ref="spectrumCanvas" class="spec-canvas"></canvas>
        </div>

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
              <button class="func-btn" :class="{ fav: isFav }" @click="handleFav" title="收藏">
                <svg viewBox="0 0 24 24" width="26" height="26" :fill="isFav ? '#ec4141' : 'none'" stroke="currentColor" stroke-width="2"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>
              </button>
            </div>

            <!-- 中间：播放控制 -->
            <div class="center-ctrl">
              <button class="ctrl-btn mode-btn" :class="{ active: player.playMode !== 'list-loop' }" @click="player.toggleMode()" :title="modeLabel">
                <!-- 顺序播放 -->
                <svg v-if="player.playMode === 'list-loop'" viewBox="0 0 1280 1024" width="24" height="24" fill="currentColor"><path d="M1121.8 243.7A373.4 373.4 0 0 1 1231.9 509.5c0 34.2-4.6 68.2-13.7 100.8a42.4 42.4 0 0 1-81.7-22.6 291.9 291.9 0 0 0 10.6-78.2c0-160.5-130.6-291.1-291.1-291.1H461.5v75.1c0 24.1-16.8 33.5-37.3 20.8L243.5 202.2c-20.5-12.7-20.7-33.8-.4-46.9L424.7 38.1c20.2-13.1 36.8-4 36.8 20.1v75.4h394.5c100.4 0 194.8 39.1 265.8 110.1zm-70 573.1c20.5 12.7 20.7 33.8.4 46.8l-181.6 117.3c-20.2 13.1-36.8 4.1-36.8-20V885.4H407.9c-100.4 0-194.8-39.1-265.8-110.1A373.4 373.4 0 0 1 32 509.5c0-72.6 20.7-143.1 60-203.9a42.4 42.4 0 1 1 71.2 46 290 290 0 0 0-46.4 157.8c0 160.6 130.6 291.2 291.1 291.2h425.9v-75.1c0-24.1 16.8-33.5 37.2-20.7l180.8 111.9z"/></svg>
                <!-- 单曲循环 -->
                <svg v-else-if="player.playMode === 'single'" viewBox="0 0 1024 1024" width="24" height="24" fill="currentColor"><path d="M928 476.8c-19.2 0-32 12.8-32 32v86.4c0 108.8-86.4 198.4-198.4 198.4H201.6l41.6-38.4c6.4-6.4 12.8-16 12.8-25.6 0-19.2-16-35.2-35.2-35.2-9.6 0-22.4 3.2-28.8 9.6l-108.8 99.2c-16 12.8-12.8 35.2 0 48l108.8 96c6.4 6.4 19.2 12.8 28.8 12.8 19.2 0 35.2-12.8 38.4-32 0-12.8-6.4-22.4-16-28.8l-48-44.8h499.2c147.2 0 265.6-118.4 265.6-259.2v-86.4c0-19.2-12.8-32-32-32zM96 556.8c19.2 0 32-12.8 32-32v-89.6c0-112 89.6-201.6 198.4-204.8h496l-41.6 38.4c-6.4 6.4-12.8 16-12.8 25.6 0 19.2 16 35.2 35.2 35.2 9.6 0 22.4-3.2 28.8-9.6l105.6-99.2c16-12.8 12.8-35.2 0-48l-108.8-96c-6.4-6.4-19.2-12.8-28.8-12.8-19.2 0-35.2 12.8-38.4 32 0 12.8 6.4 22.4 16 28.8l48 44.8H329.6C182.4 169.6 64 288 64 438.4v86.4c0 19.2 12.8 32 32 32z"/><path d="M544 672V352h-48L416 409.6l16 41.6 60.8-41.6V672z"/></svg>
                <!-- 随机播放 -->
                <svg v-else-if="player.playMode === 'shuffle'" viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="16 3 21 3 21 8"/><line x1="4" y1="20" x2="21" y2="3"/><polyline points="21 16 21 21 16 21"/><line x1="15" y1="15" x2="21" y2="21"/><line x1="4" y1="4" x2="9" y2="9"/></svg>
              </button>
              <button class="ctrl-btn skip" @click="$emit('prev')" title="上一首">
                <svg viewBox="0 0 24 24" width="32" height="32" fill="currentColor"><path d="M6 6h2v12H6zm3.5 6 8.5 6V6z"/></svg>
              </button>
              <button class="ctrl-btn main" @click="$emit('togglePlay')" title="播放/暂停">
                <svg v-if="isPlaying" viewBox="0 0 24 24" width="28" height="28" fill="currentColor"><rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/></svg>
                <svg v-else viewBox="0 0 24 24" width="28" height="28" fill="currentColor"><polygon points="8,5 19,12 8,19"/></svg>
              </button>
              <button class="ctrl-btn skip" @click="$emit('next')" title="下一首">
                <svg viewBox="0 0 24 24" width="32" height="32" fill="currentColor"><path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/></svg>
              </button>
            </div>

            <!-- 右侧：下载 + 音量 + 列表 -->
            <div class="right-actions">
              <button class="act-icon" :class="{ downloading }" @click="handleDownload" title="下载">
                <svg v-if="!downloading" viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
                <svg v-else viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="2" class="spin-icon"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>
              </button>
              <div class="vol-group">
                <button class="act-icon" @click="toggleMute" title="音量">
                  <svg v-if="isMuted || volume === 0" viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><line x1="23" y1="9" x2="17" y2="15"/><line x1="17" y1="9" x2="23" y2="15"/></svg>
                  <svg v-else viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><path d="M15.54 8.46a5 5 0 0 1 0 7.07"/></svg>
                </button>
                <div class="vol-bar" @click="onVolumeClick">
                  <div class="vol-fill" :style="{ width: (isMuted ? 0 : volume) + '%' }"></div>
                </div>
              </div>
              <button ref="lyricsPlaylistToggleRef" class="act-icon" :class="{ active: showPlaylist }" @click="showPlaylist = !showPlaylist" title="播放列表">
                <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="2"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/></svg>
              </button>
            </div>
          </div>
        </footer>

        <!-- 播放列表面板 -->
        <Transition name="panel-slide">
          <div v-if="showPlaylist" ref="lyricsPlaylistPanelRef" class="playlist-panel">
            <div class="panel-hd">
              <span>播放队列 ({{ player.queue.length }})</span>
              <button class="panel-close" @click="showPlaylist = false">
                <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
              </button>
            </div>
            <div class="panel-body">
              <div v-if="player.queue.length === 0" class="panel-empty">队列为空</div>
              <div v-for="(song, idx) in player.queue" :key="song.sourceId"
                class="panel-item" :class="{ now: idx === player.currentIdx }"
                @click="player.playIndex(idx)">
                <span class="panel-idx">
                  <SvgIcon v-if="idx === player.currentIdx" name="play" size="13" />
                  <template v-else>{{ idx + 1 }}</template>
                </span>
                <div class="panel-info">
                  <span class="panel-title">{{ song.name }}</span>
                  <span class="panel-art">{{ song.artist }}</span>
                </div>
                <button class="panel-del" @click="player.removeFromQueue(idx)"><SvgIcon name="close" /></button>
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
/* 封面图（高斯模糊放大铺满 + 暗化压背景静音） */
.bg-img { position: absolute; inset: -100px; background-size: cover; background-position: center; filter: blur(60px) saturate(0.45) brightness(0.35); transform: scale(1.1); }
/* 暗色渐变（顶/底压暗，保证顶部歌名和底部控制栏的可读性；中间更透出让封面色 tint 透出来） */
.bg-grad { position: absolute; inset: 0; background: linear-gradient(180deg, rgba(0,0,0,0.55) 0%, rgba(0,0,0,0.15) 35%, rgba(0,0,0,0.15) 65%, rgba(0,0,0,0.65) 100%); }
/* D2: 封面主色叠加层（内联 gradient + opacity 由取色结果驱动，淡入过渡） */
.bg-tint { position: absolute; inset: 0; opacity: 0; transition: opacity .6s ease; }

/* 顶部 */
.top { display: flex; align-items: center; justify-content: space-between; padding: 18px 20px 10px; }
.btn-icon { width: 38px; height: 38px; border: none; background: rgba(255,255,255,0.06); border-radius: 50%; color: rgba(255,255,255,0.7); cursor: pointer; display: flex; align-items: center; justify-content: center; transition: .2s; }
.btn-icon:hover { background: rgba(255,255,255,0.12); color: #fff; }
.top-center { text-align: center; }
.song-name { font-size: 19px; font-weight: 600; color: #fff; letter-spacing: 0.5px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 60vw; }
.artist-name { font-size: 14px; color: rgba(255,255,255,0.5); margin-top: 3px; }

/* 主体 — 左右居中 */
.stage { flex: 1; display: flex; align-items: center; padding: 0 40px; gap: 40px; overflow: hidden; }

/* 碟片 */
.left { width: 38%; display: flex; align-items: center; justify-content: center; }
.disc-box { position: relative; width: 100%; max-width: 390px; aspect-ratio: 1; flex-shrink: 0; }
.disc { position: absolute; inset: 0; transform-origin: center center; animation: spin 18s linear infinite; animation-play-state: paused; }
.disc.spin { animation-play-state: running; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

.disc-outer { position: absolute; inset: 0; border-radius: 50%; background: linear-gradient(145deg, #1a1a1a, #0d0d0d); box-shadow: 0 20px 60px rgba(0,0,0,0.6); }
.disc-inner { position: absolute; inset: 20%; border-radius: 50%; overflow: hidden; }
.disc-inner img { width: 100%; height: 100%; object-fit: cover; }
.disc-shine { position: absolute; inset: 0; border-radius: 50%; background: linear-gradient(135deg, rgba(255,255,255,0.06) 0%, transparent 40%, transparent 60%, rgba(255,255,255,0.02) 100%); pointer-events: none; }

/* 频谱 — 底部栏上方，水平全宽（之前 300px 留白在小屏对齐但在 1440+ 宽屏显得"被挖了个坑"） */
.spectrum-row { padding: 0 max(40px, 8vw); flex-shrink: 0; margin-bottom: 8px; }
.spec-canvas { width: 100%; height: 125px; display: block; border-radius: 0; }

/* 歌词 — 紧邻碟片右侧，撑满 */
.right { flex: 1; height: 75%; overflow: hidden; padding-left: 70px; }
.lyric-box { height: 100%; overflow-y: auto; padding: 40px 20px 100px; }
.lyric-box::-webkit-scrollbar { display: none; }
/* D1 歌词景深（Apple Music 范式）：当前行放大加亮，非当前行弱化
   transform:scale + opacity 过渡（合成器友好，无逐行 blur/重排） */
.lyric-line {
  padding: 13px 18px; font-size: 25px; line-height: 1.7;
  color: rgba(255,255,255,0.4);
  opacity: 0.5;
  transform: scale(1);
  transform-origin: left center;
  position: relative;
  text-align: left; cursor: pointer; letter-spacing: .5px;
  border-radius: 12px;
  transition: color .35s, opacity .35s, transform .35s, background .35s;
}
.lyric-line:hover { color: rgba(255,255,255,0.7); opacity: 0.85; background: rgba(255,255,255,0.04); }
.lyric-line.current {
  font-weight: 700; color: #fff; opacity: 1;
  transform: scale(1.08);
  background: linear-gradient(90deg, rgba(255,255,255,0.08) 0%, rgba(255,255,255,0.02) 100%);
  box-shadow: inset 3px 0 0 var(--primary);  /* 配合 ::before 双重保险；inset 阴影不占布局 */
}
/* 逐字高亮（v3 连续流动）：非当前行继承行级颜色；当前行每个字的颜色由内联 style 控制
   （charAlpha 线性插值），color 更新只触发单字重绘，性能好且视觉连续 */
.lyric-line:not(.current) .ch { color: inherit; }
.lyric-line.current .ch {
  color: rgba(255,255,255,0.45); /* 兜底（未开始行时 charAlpha=0.45） */
  transition: none; /* 内联 color 直接插值，禁 transition 避免累加延迟 */
}
/* 品牌绿点缀：当前行左侧竖条（B6 禁 glow，用几何元素而非光晕） */
.lyric-line.current::before {
  content: ''; position: absolute; left: -16px; top: 50%;
  transform: translateY(-50%);
  width: 3px; height: 26px; border-radius: 2px;
  background: var(--primary);
}
.empty { text-align: center; padding-top: 30%; color: rgba(255,255,255,0.3); font-size: 16px; }

/* 底部栏 */
.bar { padding: 16px 36px 38px; }
.progress-wrap { display: flex; align-items: center; gap: 16px; margin-bottom: 22px; }
.time { font-size: 15px; color: rgba(255,255,255,0.45); min-width: 48px; font-variant-numeric: tabular-nums; }
.progress-track { flex: 1; height: 6px; background: rgba(255,255,255,0.1); border-radius: 3px; cursor: pointer; position: relative; }
.progress-track:hover { height: 8px; }
.progress-fill { height: 100%; background: #2ecc71; border-radius: 3px; position: relative; }
.thumb { position: absolute; right: -7px; top: 50%; transform: translateY(-50%); width: 14px; height: 14px; background: #2ecc71; border-radius: 50%; opacity: 0; transition: opacity .2s; }
.progress-track:hover .thumb { opacity: 1; }

.ctrl-wrap { display: flex; align-items: center; justify-content: space-between; }

/* 左侧 — x2 */
.left-info { display: flex; align-items: center; gap: 20px; min-width: 200px; }
.mini-song { color: rgba(255,255,255,0.6); font-size: 16px; max-width: 160px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mini-name { color: #fff; font-weight: 500; }
.mini-artist { color: rgba(255,255,255,0.45); }
.func-btn { width: 56px; height: 56px; border: none; background: rgba(255,255,255,0.06); border-radius: 50%; color: rgba(255,255,255,0.6); cursor: pointer; display: flex; align-items: center; justify-content: center; transition: .2s; }
.func-btn:hover { background: rgba(255,255,255,0.12); color: #ec4141; }
.func-btn.fav { color: #ec4141; background: rgba(236,65,65,0.12); }

/* 中间播放 — x2 */
.center-ctrl { display: flex; align-items: center; gap: 16px; position: absolute; left: 48%; transform: translateX(-50%); }
.ctrl-btn { border: none; background: none; color: rgba(255,255,255,0.75); cursor: pointer; display: flex; align-items: center; justify-content: center; transition: .2s; }
.ctrl-btn:hover { color: #fff; }
.ctrl-btn.mode-btn { width: 56px; height: 56px; border-radius: 50%; background: rgba(255,255,255,0.04); }
.ctrl-btn.mode-btn:hover { color: #2ecc71; background: rgba(46,204,113,0.12); }
.ctrl-btn.mode-btn.active { color: #2ecc71; }
.ctrl-btn.skip { opacity: 0.8; padding: 6px; }
.ctrl-btn.skip:hover { opacity: 1; }
.ctrl-btn.main { width: 65px; height: 32px; border-radius: 8px; background: #2ecc71; color: #fff; box-shadow: 0 4px 18px rgba(46,204,113,0.35); font-size: 16px; }
.ctrl-btn.main:hover { transform: scale(1.05); box-shadow: 0 6px 24px rgba(46,204,113,0.45); }

/* 右侧操作 — x2 */
.right-actions { display: flex; align-items: center; gap: 20px; min-width: 220px; justify-content: flex-end; }
.act-icon { width: 50px; height: 50px; border: none; background: rgba(255,255,255,0.04); border-radius: 50%; color: rgba(255,255,255,0.5); cursor: pointer; display: flex; align-items: center; justify-content: center; transition: .2s; }
.act-icon:hover { color: #fff; background: rgba(255,255,255,0.08); }
.act-icon.active { color: #2ecc71; background: rgba(46,204,113,0.1); }
.act-icon.downloading { color: #2ecc71; }
.spin-icon { animation: spin 1.2s linear infinite; }
.vol-group { display: flex; align-items: center; gap: 10px; }
.vol-bar { width: 100px; height: 5px; background: rgba(255,255,255,0.1); border-radius: 3px; cursor: pointer; }
.vol-bar:hover { height: 7px; }
.vol-fill { height: 100%; background: rgba(255,255,255,0.5); border-radius: 3px; }
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
