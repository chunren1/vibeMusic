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
// 模块级 tick 订阅者集合：所有 useAudioBackground 实例共享同一个 Worker，
// 每个实例（PlayerBar / MPlayerView / 结束检测）注册自己的回调，由统一 onmessage 分发。
// 修复前 startWorkerTimer 直接覆盖 worker.onmessage 单槽，多订阅者会互相顶掉。
const tickHandlers = new Set()

function getWorker() {
  if (!workerInstance) {
    const blob = new Blob([WORKER_CODE], { type: 'application/javascript' })
    workerInstance = new Worker(URL.createObjectURL(blob))
    // 统一分发：Worker tick 到达主线程时逐个调用订阅者。
    // Worker 线程的 setTimeout 不受后台标签页定时器节流影响，
    // 消息投递到主线程同样不被节流 —— 这是第三层结束检测的根基。
    workerInstance.onmessage = () => {
      tickHandlers.forEach(fn => { try { fn() } catch {} })
    }
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
    tickHandlers.clear()
  }
}

/** 无订阅者时软停止 Worker 心跳（实例保留，可再次 start） */
function stopWorkerIfIdle() {
  if (tickHandlers.size === 0 && workerInstance) {
    workerInstance.postMessage('stop')
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
  let unsubscribes = []

  /**
   * 1. Page Visibility API — 前后台切换
   *    关键改动：进后台不暂停，继续播放；回前台不自动播放
   */
  function handleVisibilityChange() {
    const hidden = document.hidden || document.visibilityState === 'hidden'
    const audio = audioRef?.value || window.vibeAudio

    if (hidden) {
      isBackground.value = true
      bgSince.value = Date.now()
      // IMPORTANT: Don't pause! Keep playing in background
      wasPausedByBg.value = false
    } else {
      isBackground.value = false
      bgSince.value = null
      // 回到前台如果因某些原因暂停了，尝试恢复
      if (audio?.src && audio.paused && audio.readyState >= 2) {
        resumePlayback(audio)
      }
    }
  }

  /** 恢复播放 */
  function resumePlayback(audio, retries = 2) {
    if (!audio?.src) return
    if (window._vibeAudioCtx?.state === 'suspended') {
      window._vibeAudioCtx.resume().catch(() => {})
    }
    audio.play().catch(err => {
      if (retries > 0) {
        setTimeout(() => resumePlayback(audio, retries - 1), 800)
      }
    })
  }

  /**
   * 2. 后台播放心跳 — 三层保险检测歌曲结束
   *
   *    问题：手机后台 timeupdate 降频（1-3s/次）、ended 事件不触发、
   *    setInterval 严重节流。单靠任何一种都不可靠。
   *
   *    方案：
   *    第1层 timeupdate 抢先切 —— 距离结尾 2 秒时提前切歌（避免等到 timeupdate 中断）
   *    第2层 pause 兜底 —— 直接读 audio.ended 属性（浏览器引擎设的，不依赖JS事件）
   *    第3层 Worker 心跳 —— 由 Worker tick 驱动，每 ~4 秒检查一次 audio.ended
   *    （Worker 线程不受主线程节流影响，消息投递亦不节流）
   */
  let _switchingNext = false

  const onBgTimeUpdate = () => {
    const audio = window.vibeAudio
    if (!audio || audio.loop || _switchingNext) return
    // 第1层：距结束 2 秒内抢先切歌
    // 2秒阈值确保在后台 timeupdate 降频到 1Hz 时也能命中至少一帧
    if (audio.duration > 2 && audio.currentTime >= audio.duration - 2.0) {
      _switchingNext = true
      if (window.vibeNext) window.vibeNext()
      setTimeout(() => { _switchingNext = false }, 4000)
    }
  }

  const onBgPauseForEnd = () => {
    const audio = window.vibeAudio
    if (!audio || audio.loop || _switchingNext) return
    // 第2层：pause 时直接读 ended 属性（不依赖 _nearEnd）
    // audio.ended 由浏览器引擎在播完时自动设为 true，不依赖 JS 事件触发
    if (audio.ended && audio.duration > 0) {
      _switchingNext = true
      if (window.vibeNext) window.vibeNext()
      setTimeout(() => { _switchingNext = false }, 4000)
    }
  }

  // 第3层：Worker 心跳驱动检查（与进度保存共用同一个 Worker）
  // 修复前：这里误用主线程 setInterval —— 后台标签页定时器被浏览器节流
  // （Chrome 强化节流可降至 1 次/分钟），导致第三层兜底失效。
  // 现在改为订阅 Worker tick：Worker 线程的 setTimeout 不受主线程节流影响，
  // 其 postMessage 投递到主线程也不被节流，检测节奏由 Worker 时钟驱动。
  let _bgEndedTickHandler = null
  let _bgEndedFallback = null
  let _lastEndedCheck = 0

  function checkEnded() {
    const audio = window.vibeAudio
    if (!audio || audio.loop || _switchingNext) return
    if (audio.ended && audio.duration > 0) {
      _switchingNext = true
      if (window.vibeNext) window.vibeNext()
      setTimeout(() => { _switchingNext = false }, 4000)
    }
  }

  function startBgEndedCheck() {
    const audio = window.vibeAudio
    if (!audio) return
    audio.addEventListener('timeupdate', onBgTimeUpdate)
    audio.addEventListener('pause', onBgPauseForEnd)

    // Worker tick 驱动：保持 ~4 秒检查节奏（tick 可能 250ms 一次，用时间闸限频）
    const onWorkerTick = () => {
      if (Date.now() - _lastEndedCheck < 4000) return
      _lastEndedCheck = Date.now()
      checkEnded()
    }
    try {
      const w = getWorker()
      _bgEndedTickHandler = onWorkerTick
      tickHandlers.add(onWorkerTick)
      // 若 Worker 尚未被任何调用方启动，以默认 250ms 节奏启动；
      // 若已被 startWorkerTimer 启动，则沿用其节奏，不覆盖 interval
      if (tickHandlers.size === 1) {
        w.postMessage(250)
        w.postMessage('start')
      }
    } catch {
      // Worker 不可用：退化为低频 setInterval 兜底（前台可用；
      // 后台仍会被节流 —— 已知限制，Worker 不可用时无解）
      _bgEndedFallback = setInterval(checkEnded, 4000)
    }
    unsubscribes.push(
      () => audio.removeEventListener('timeupdate', onBgTimeUpdate),
      () => audio.removeEventListener('pause', onBgPauseForEnd),
    )
  }

  function stopBgEndedCheck() {
    if (_bgEndedTickHandler) {
      tickHandlers.delete(_bgEndedTickHandler)
      _bgEndedTickHandler = null
      stopWorkerIfIdle()
    }
    if (_bgEndedFallback) {
      clearInterval(_bgEndedFallback)
      _bgEndedFallback = null
    }
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
      const w = getWorker()
      tickHandlers.add(onTick)
      w.postMessage(ms)
      // 'start' 在 Worker 侧幂等（running 标志），重复调用安全
      w.postMessage('start')
    } catch (e) {
      console.warn('[AudioBG] Worker 不可用，回退到 RAF')
      fallbackRaf()
    }
  }

  function stopWorkerTimer() {
    if (onTick) {
      tickHandlers.delete(onTick)
      onTick = null
    }
    if (rafId) {
      cancelAnimationFrame(rafId)
      rafId = null
    }
    stopWorkerIfIdle()
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

  // 背景暂停自动恢复状态
  let wasPlayingBeforeBg = false
  let bgPauseRetries = 0

  onMounted(() => {
    setupMediaSession()
    setupStatePersistence()
    document.addEventListener('visibilitychange', handleVisibilityChange)
    startBgEndedCheck()

    // 进后台时记录播放状态
    const onHidden = () => {
      if (document.hidden) {
        const audio = window.vibeAudio
        wasPlayingBeforeBg = !!(audio && !audio.paused)
        bgPauseRetries = 0
      } else {
        wasPlayingBeforeBg = false
      }
    }
    document.addEventListener('visibilitychange', onHidden)

    // 检测后台强制暂停 — 自动恢复（多级重试）
    // 最小化后延迟 500ms 再试，给浏览器足够时间完成最小化，避免立即 play 导致窗口弹回
    const onAutoPause = () => {
      const audio = window.vibeAudio
      if (!document.hidden) return
      if (!wasPlayingBeforeBg) return
      if (!audio?.src || audio.readyState < 2) return

      const retry = (delay, maxRetries) => {
        bgPauseRetries++
        if (bgPauseRetries > maxRetries) return
        setTimeout(() => {
          if (!document.hidden || !audio.paused || !audio.src) return
          audio.play().catch(() => retry(delay * 1.5, maxRetries))
        }, delay)
      }
      // 首次重试延迟 500ms，避免与浏览器最小化动画竞争
      setTimeout(() => {
        if (!document.hidden || !audio.paused || !audio.src) return
        audio.play().catch(() => retry(300, 5))
      }, 500)
    }
    window.addEventListener('pause', onAutoPause)
    unsubscribes.push(
      () => document.removeEventListener('visibilitychange', onHidden),
      () => window.removeEventListener('pause', onAutoPause),
    )

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
    stopBgEndedCheck()
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
