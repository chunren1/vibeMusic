// 全局 window 扩展声明：播放器单例与跨端桥接挂载点（仅类型补齐，零运行时影响）。
// 由 src/api/request.js 经 /// <reference path> 引入，确保 vue-tsc checkJs 全局生效。
interface Window {
  _openQueuePopup: Function
  _vibeAnalyser: any
  _vibeAudioCtx: AudioContext
  toast: (msg: string, t?: string, duration?: number) => void
  vibeAddToQueue: Function
  vibeAudio: HTMLAudioElement
  vibeAudioSetSrc: Function
  vibeNext: Function
  vibePlay: Function
  vibePlayBySourceId: Function
  vibePlayMode: Function
  vibePlayQueue: Function
  vibePrev: Function
  vibeQueue: any
  vibeToggleMode: Function
  webkitAudioContext: typeof AudioContext
}
