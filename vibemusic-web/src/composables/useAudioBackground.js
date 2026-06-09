/**
 * useAudioBackground — 移动端后台播放全套方案
 *
 * 解决的问题：
 * 1. 切后台/锁屏时音频暂停 → Media Session API + 可见性监控
 * 2. 定时器降频卡顿 → Worker 心跳 + RAF 降级
 * 3. AudioContext 挂起 → resume() 自动恢复
 * 4. 重回前台状态丢失 → localStorage 快照 + 恢复
 *
 * 用法：在 PlayerBar / MPlayerView 的 setup 中调用一次
 */
import { ref, readonly, onMounted, onUnmounted } from 'vue'

// ──────────────────────────────────────────────
// Web Worker 内联脚本（心跳时钟，不会被主线程节流降频）
// ──────────────────────────────────────────────
const WORKER_CODE = `
let interval = 250
let timer = null
let running = false

onmessage = (e) => {
  if (e.data === 'start') {
    if (running) return
    running = true
    tick()
  } else if (e.data === 'stop') {
    running = false
    clearTimeout(timer)
  } else if (typeof e.data === 'number') {
    interval = e.data
  }
}

function tick() {
  if (!running) return
  postMessage('tick')
  timer = setTimeout(tick, interval)
}
`

let workerInstance = null
let workerRefs = 0

function getWorker() {
  if (!workerInstance) {
    const blob = new Blob([WORKER_CODE], { type: 'application/javascript' })
    workerInstance = new Worker(URL.createObjectURL(blob))
  }
  workerRefs++
  return workerInstance
}

function releaseWorker() {
  workerRefs = Math.max(0, workerRefs - 1)
  if (workerRefs === 0 && workerInstance) {
    workerInstance.postMessage('stop')
    workerInstance.terminate()
    workerInstance = null
  }
}

// ──────────────────────────────────────────────
// 主 composable
// ──────────────────────────────────────────────
export function useAudioBackground(audioRef) {
  // 后台状态
  const isBackground = ref(false)
  const wasPausedByBg = ref(false)
  const bgSince = ref(null)

  let rafId = null
  let worker = null
  let unsubscribes = []

  /**
   * 1. Page Visibility API — 检测前后台切换
   *    进入后台时记录状态，回到前台时恢复播放
   */
  function handleVisibilityChange() {
    const hidden = document.hidden || document.visibilityState === 'hidden'
    const audio = audioRef?.value || window.vibeAudio

    if (hidden) {
      isBackground.value = true
      bgSince.value = Date.now()
      // 如果正在播放，标记为"因后台而暂停"
      wasPausedByBg.value = !audio?.paused
    } else {
      isBackground.value = false
      bgSince.value = null

      // 自动恢复播放
      if (wasPausedByBg.value && audio?.src && audio.paused) {
        resumePlayback(audio)
      }
      wasPausedByBg.value = false
    }
  }

  /** 恢复播放（处理 AudioContext 挂起 + 播放失败重试） */
  function resumePlayback(audio, retries = 3) {
    if (!audio?.src) return

    // 恢复 AudioContext
    if (window._vibeAudioCtx?.state === 'suspended') {
      window._vibeAudioCtx.resume().catch(() => {})
    }

    // 尝试播放（移动端需要用户手势，这里做重试）
    audio.play().then(() => {
      console.log('[AudioBG] 播放已恢复')
    }).catch(err => {
      console.warn('[AudioBG] 自动恢复播放失败:', err.message)
      if (retries > 0) {
        setTimeout(() => resumePlayback(audio, retries - 1), 500)
      }
    })
  }

  /**
   * 2. Media Session API — 锁屏 / 通知栏控制中心
   *    让后台播放时在通知栏显示播放信息和控制按钮
   */
  function setupMediaSession() {
    if (!('mediaSession' in navigator)) return

    const actionHandlers = {
      play: () => {
        const a = window.vibeAudio
        if (a?.src) {
          a.play().catch(() => {})
          navigator.mediaSession.playbackState = 'playing'
        }
      },
      pause: () => {
        const a = window.vibeAudio
        a?.pause()
        navigator.mediaSession.playbackState = 'paused'
      },
      previoustrack: () => window.vibePrev?.(),
      nexttrack: () => window.vibeNext?.(),
    }

    // 注册动作处理器
    for (const [action, handler] of Object.entries(actionHandlers)) {
      try { navigator.mediaSession.setActionHandler(action, handler) } catch {}
    }

    // 监听元数据更新
    const onSongChange = (e) => {
      if (!e?.detail) return
      const d = e.detail
      try {
        navigator.mediaSession.metadata = new MediaMetadata({
          title: d.title || d.name || '未知歌曲',
          artist: d.artist || '未知歌手',
          album: '',
          artwork: d.coverUrl
            ? [{ src: d.coverUrl + '?param=200y200', sizes: '200x200', type: 'image/png' }]
            : [],
        })
      } catch {}
    }

    window.addEventListener('song-change', onSongChange)
    unsubscribes.push(() => window.removeEventListener('song-change', onSongChange))

    // 更新播放状态
    const updateState = () => {
      const a = window.vibeAudio
      try {
        navigator.mediaSession.playbackState = a?.paused ? 'paused' : 'playing'
      } catch {}
    }
    window.addEventListener('play', updateState)
    window.addEventListener('pause', updateState)
    unsubscribes.push(
      () => window.removeEventListener('play', updateState),
      () => window.removeEventListener('pause', updateState),
    )
  }

  /**
   * 3. 状态快照 — 重回前台恢复进度
   *    利用 localStorage 保存当前播放进度，确保切后台再回来时进度不丢失
   */
  function setupStatePersistence() {
    const save = () => {
      const a = window.vibeAudio
      if (!a?.duration) return
      localStorage.setItem('vibe_bg_time', String(a.currentTime))
      localStorage.setItem('vibe_bg_paused', String(a.paused))
      localStorage.setItem('vibe_bg_ts', String(Date.now()))
    }

    const restore = () => {
      const savedTime = parseFloat(localStorage.getItem('vibe_bg_time') || '0')
      const a = window.vibeAudio
      if (savedTime > 0 && a?.src && a.paused) {
        // 粗略补偿流逝时间（最多30秒防止跳太多）
        const elapsed = Math.min((Date.now() - parseInt(localStorage.getItem('vibe_bg_ts') || '0')) / 1000, 30)
        if (elapsed > 3) {
          a.currentTime = Math.min(savedTime + elapsed, a.duration || Infinity)
        }
      }
    }

    window.addEventListener('beforeunload', save)
    window.addEventListener('pagehide', save)

    const onHidden = () => { if (document.hidden) save() }
    const onVisible = () => { if (!document.hidden) restore() }
    document.addEventListener('visibilitychange', onHidden)
    document.addEventListener('visibilitychange', onVisible)

    unsubscribes.push(
      () => window.removeEventListener('beforeunload', save),
      () => window.removeEventListener('pagehide', save),
      () => document.removeEventListener('visibilitychange', onHidden),
      () => document.removeEventListener('visibilitychange', onVisible),
    )
  }

  /**
   * 4. Worker 心跳 — 避免 setInterval 降频
   *    在 Worker 中保持时间精度，用于同步进度条
   */
  let onTick = null
  function startWorkerTimer(callback, ms = 250) {
    onTick = callback
    try {
      worker = getWorker()
      worker.postMessage(ms)
      worker.postMessage('start')
      worker.onmessage = () => { onTick?.() }
    } catch (e) {
      console.warn('[AudioBG] Worker 不可用，回退到 RAF')
      fallbackRaf()
    }
  }

  function stopWorkerTimer() {
    if (worker) {
      worker.onmessage = null
      worker.postMessage('stop')
    }
    if (rafId) {
      cancelAnimationFrame(rafId)
      rafId = null
    }
  }

  /** RAF 降级方案（在 Worker 不可用时使用，但后台会被节流） */
  function fallbackRaf() {
    function step() {
      onTick?.()
      rafId = requestAnimationFrame(step)
    }
    rafId = requestAnimationFrame(step)
  }

  /**
   * 5. Wake Lock — 防锁屏（可选）
   */
  let wakeLock = null
  async function requestWakeLock() {
    if (!('wakeLock' in navigator)) return
    try {
      wakeLock = await navigator.wakeLock.request('screen')
      wakeLock.addEventListener('release', () => { wakeLock = null })
    } catch {}
  }

  async function releaseWakeLock() {
    if (wakeLock) {
      try { await wakeLock.release() } catch {}
      wakeLock = null
    }
  }

  // ──────────────────────────────────────────────
  // 生命周期
  // ──────────────────────────────────────────────
  onMounted(() => {
    setupMediaSession()
    setupStatePersistence()
    document.addEventListener('visibilitychange', handleVisibilityChange)

    // 当播放开始时尝试获取 Wake Lock
    const onPlay = () => {
      try { navigator.mediaSession.playbackState = 'playing' } catch {}
      requestWakeLock()
    }
    const onPause = () => {
      try { navigator.mediaSession.playbackState = 'paused' } catch {}
      releaseWakeLock()
    }
    window.addEventListener('play', onPlay)
    window.addEventListener('pause', onPause)
    unsubscribes.push(
      () => window.removeEventListener('play', onPlay),
      () => window.removeEventListener('pause', onPause),
    )
  })

  onUnmounted(() => {
    document.removeEventListener('visibilitychange', handleVisibilityChange)
    stopWorkerTimer()
    releaseWorker()
    releaseWakeLock()
    unsubscribes.forEach(fn => { try { fn() } catch {} })
  })

  return {
    isBackground: readonly(isBackground),
    startWorkerTimer,
    stopWorkerTimer,
    requestWakeLock,
    releaseWakeLock,
  }
}
