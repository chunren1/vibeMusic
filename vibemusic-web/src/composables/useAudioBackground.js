/**
 * useAudioBackground — 移动端后台播放全套方案
 *
 * 解决的问题：
 * 1. 切后台/锁屏时音频暂停 → Media Session API + 可见性监控
 * 2. 定时器降频卡顿 → Worker 心跳 + RAF 降级
 * 3. AudioContext 挂起 → resume() 自动恢复
 * 4. 重回前台状态丢失 → localStorage 快照 + 恢复
 *
 * 用法：App.vue 根组件调用一次（常驻，保证后台能力始终在线）；
 *      PlayerBar / MPlayerView 可以再调用，拿到的是同一个单例，不会重复注册。
 */
import { ref, readonly, onMounted, onUnmounted, getCurrentInstance } from 'vue'

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
      tickHandlers.forEach(fn => { try { fn() } catch { /* ignore */ } })
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
//
// 模块级单例：后台播放能力（MediaSession / Worker 心跳 / 结束检测 / 快照）
// 与「当前在哪个页面」无关，App.vue 常驻持有，PlayerBar / MPlayerView 复用同一实例。
// 修复前每个调用点各自初始化，移动端首页/搜索页播放时切后台没有 MediaSession 控件、
// 没有 Worker 心跳、没有结束检测 → 后台播放断链。
// ──────────────────────────────────────────────
let sharedInstance = null

/**
 * 获取（必要时创建）后台播放单例。
 * 只在第一次调用时创建状态与监听；后续调用返回同一个 api 对象，不重复注册。
 * 用引用计数管理销毁：每个调用方登记一次 onUnmounted 递减，归零才 teardown。
 */
export function useAudioBackground(audioRef) {
  if (!sharedInstance) {
    const created = createAudioBackground(audioRef)
    sharedInstance = { api: created.api, teardown: created.teardown, refs: 0, destroyed: false }
  }
  const inst = sharedInstance
  inst.refs++
  // 只有组件 setup 内调用才需要登记卸载回调（App/PlayerBar/MPlayerView 均满足）
  if (getCurrentInstance()) {
    onUnmounted(() => releaseInstance(inst))
  }
  return inst.api
}

/** 引用计数递减；最后一个持有者卸载时才真正清理监听/Worker/WakeLock */
function releaseInstance(inst) {
  inst.refs = Math.max(0, inst.refs - 1)
  if (inst.refs > 0 || inst.destroyed) return
  inst.destroyed = true
  inst.teardown()
  if (sharedInstance === inst) sharedInstance = null
}

function createAudioBackground(audioRef) {
  // 后台状态
  const isBackground = ref(false)
  const wasPausedByBg = ref(false)
  /** @type {import('vue').Ref<number|null>} */
  const bgSince = ref(null)

  let rafId = null
  let unsubscribes = []

  // 本实例从共享 Worker 获取的引用次数（teardown 时等量归还，保证最后一个持有者能真正 terminate）
  let workerAcquires = 0
  function acquireWorker() {
    const w = getWorker()
    workerAcquires++
    return w
  }

  /**
   * 1. Page Visibility API — 前后台切换
   *    关键改动：进后台不暂停，继续播放；回前台只恢复"进后台时本来在播"的那次
   *    （用户主动暂停过的不许被自动续播——此前只要 paused 就 resume，
   *    wasPausedByBg 是只写不读的死状态，round6 W2）
   */
  function handleVisibilityChange() {
    const hidden = document.hidden || document.visibilityState === 'hidden'
    const audio = audioRef?.value || window.vibeAudio

    if (hidden) {
      isBackground.value = true
      bgSince.value = Date.now()
      // IMPORTANT: Don't pause! Keep playing in background
      // 只记录"当时是否在播"，供回前台时判断是否需要恢复
      wasPausedByBg.value = !!(audio && !audio.paused)
    } else {
      isBackground.value = false
      bgSince.value = null
      const shouldResume = wasPausedByBg.value
      wasPausedByBg.value = false
      // 回到前台：仅当进后台时在播、且现在确实被动暂停了，才尝试恢复
      if (shouldResume && audio?.src && audio.paused && audio.readyState >= 2) {
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
    audio.play().catch(() => {
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
      const w = acquireWorker()
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
      try { navigator.mediaSession.setActionHandler(/** @type {MediaSessionAction} */ (action), handler) } catch { /* ignore */ }
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
      } catch { /* ignore */ }
    }

    window.addEventListener('song-change', onSongChange)
    unsubscribes.push(() => window.removeEventListener('song-change', onSongChange))

    // 更新播放状态：play/pause 是不冒泡的媒体事件，必须挂在 audio 元素上（挂 window 永不触发）
    const updateState = () => {
      const a = audioRef?.value || window.vibeAudio
      try {
        navigator.mediaSession.playbackState = a?.paused ? 'paused' : 'playing'
      } catch { /* ignore */ }
    }
    listenAudioEvent('play', updateState)
    listenAudioEvent('pause', updateState)
  }

  // play/pause 不冒泡到 window：优先挂 audio 元素，audio 尚未创建时才回退 window（保持清理结构一致）
  function listenAudioEvent(event, handler) {
    const audio = audioRef?.value || window.vibeAudio
    if (audio?.addEventListener) {
      audio.addEventListener(event, handler)
      unsubscribes.push(() => audio.removeEventListener(event, handler))
    } else {
      window.addEventListener(event, handler)
      unsubscribes.push(() => window.removeEventListener(event, handler))
    }
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
   *
   *    修复前是单个 onTick 槽位：PlayerBar（存进度）与 MPlayerView（进度条/歌词）
   *    同时存在时后注册的会顶掉前一个。改成按调用方登记的回调集合，互不影响。
   */
  const workerCallbacks = new Set()

  function startWorkerTimer(callback, ms = 250) {
    if (typeof callback !== 'function') return
    const isNew = !workerCallbacks.has(callback)
    workerCallbacks.add(callback)
    try {
      // 同一回调重复 start 不重复占用 Worker 引用；已启动时只更新节奏
      const w = isNew || !workerInstance ? acquireWorker() : workerInstance
      if (isNew) tickHandlers.add(callback)
      w.postMessage(ms)
      // 'start' 在 Worker 侧幂等（running 标志），重复调用安全
      w.postMessage('start')
    } catch {
      console.warn('[AudioBG] Worker 不可用，回退到 RAF')
      fallbackRaf(callback)
    }
  }

  function stopWorkerTimer() {
    // 无参语义：清掉本实例登记的全部回调（调用方卸载时无需自己保管引用）
    for (const cb of workerCallbacks) tickHandlers.delete(cb)
    workerCallbacks.clear()
    if (rafId) {
      cancelAnimationFrame(rafId)
      rafId = null
    }
    stopWorkerIfIdle()
  }

  /** RAF 降级方案（在 Worker 不可用时使用，但后台会被节流） */
  function fallbackRaf(callback) {
    function step() {
      try { callback() } catch { /* ignore */ }
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
    } catch { /* ignore */ }
  }

  async function releaseWakeLock() {
    if (wakeLock) {
      try { await wakeLock.release() } catch { /* ignore */ }
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
    if (destroyed) return
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
    listenAudioEvent('pause', onAutoPause)
    unsubscribes.push(
      () => document.removeEventListener('visibilitychange', onHidden),
    )
    const onPlay = () => {
      try { navigator.mediaSession.playbackState = 'playing' } catch { /* ignore */ }
      requestWakeLock()
    }
    const onPause = () => {
      try { navigator.mediaSession.playbackState = 'paused' } catch { /* ignore */ }
      releaseWakeLock()
    }
    listenAudioEvent('play', onPlay)
    listenAudioEvent('pause', onPause)
  })

  // 销毁标记：teardown 只执行一次（引用计数归零时由 releaseInstance 调用）
  let destroyed = false

  function teardown() {
    if (destroyed) return
    destroyed = true
    document.removeEventListener('visibilitychange', handleVisibilityChange)
    stopWorkerTimer()
    stopBgEndedCheck()
    // 本实例每次 acquireWorker 都占了一个全局引用，必须等量归还，
    // 否则 workerRefs 永远 > 0，最后一个持有者卸载时 Worker 也不会 terminate
    while (workerAcquires > 0) {
      workerAcquires--
      releaseWorker()
    }
    releaseWakeLock()
    unsubscribes.forEach(fn => { try { fn() } catch { /* ignore */ } })
    unsubscribes = []
  }

  const api = {
    isBackground: readonly(isBackground),
    startWorkerTimer,
    stopWorkerTimer,
    requestWakeLock,
    releaseWakeLock,
  }

  // refs / destroyed 由外层包装对象承载，releaseInstance 负责递减与置位
  return { api, teardown }
}
