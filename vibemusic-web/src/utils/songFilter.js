/**
 * 搜索页筛选维度 —— 只用 SongDTO 里真实存在的字段：
 * - platform（netease / qq / migu / kugou / bilibili）：走后端 /songs/search 的 platform 参数
 * - duration（秒）：客户端过滤
 * 音质、年份、语种等字段 DTO 里没有，不做假筛选器。
 */

/** @typedef {{ value: string, label: string }} FilterOption */

/** 平台筛选项，value 与后端 platform 参数一致；all = 不带参数（全平台） @type {FilterOption[]} */
export const PLATFORM_OPTIONS = [
  { value: 'all', label: '全部平台' },
  { value: 'netease', label: '网易云' },
  { value: 'qq', label: 'QQ 音乐' },
  { value: 'migu', label: '咪咕' },
  { value: 'kugou', label: '酷狗' },
  { value: 'bilibili', label: 'B站' },
]

/** @type {Record<string, string>} */
const PLATFORM_LABELS = {
  netease: '网易云',
  qq: 'QQ',
  migu: '咪咕',
  kugou: '酷狗',
  bilibili: 'B站',
}

/**
 * 歌曲平台角标文案（列表内用的短标签，比筛选 chip 更紧凑）；未知平台原样返回
 * @param {string} [platform]
 * @returns {string}
 */
export function platformLabel(platform) {
  if (!platform) return ''
  return PLATFORM_LABELS[platform] || platform
}

/** @typedef {{ value: string, label: string, min?: number, max?: number }} DurationOption */

/** 时长筛选项，min/max 为闭区间秒数 @type {DurationOption[]} */
export const DURATION_OPTIONS = [
  { value: 'all', label: '全部时长' },
  { value: 'short', label: '3 分钟内', max: 180 },
  { value: 'medium', label: '3-5 分钟', min: 181, max: 300 },
  { value: 'long', label: '5 分钟以上', min: 301 },
]

/**
 * 单曲是否落在时长档内；时长缺失（null / 0 / 非数字）视为不匹配
 * @param {number|undefined} duration 秒
 * @param {string} bucket 档位 value，all 表示不限
 * @returns {boolean}
 */
export function matchesDuration(duration, bucket) {
  if (bucket === 'all') return true
  const opt = DURATION_OPTIONS.find(o => o.value === bucket)
  if (!opt) return true
  if (typeof duration !== 'number' || !isFinite(duration) || duration <= 0) return false
  if (opt.min !== undefined && duration < opt.min) return false
  if (opt.max !== undefined && duration > opt.max) return false
  return true
}

/**
 * 客户端按时长过滤，保持后端原有排序
 * @param {Song[]} songs
 * @param {string} bucket
 * @returns {Song[]}
 */
export function filterByDuration(songs, bucket) {
  if (bucket === 'all') return songs
  return songs.filter(song => matchesDuration(song.duration, bucket))
}

/**
 * 被时长条件筛掉的条数（含时长缺失、无法归类的歌曲）
 * @param {Song[]} songs
 * @param {string} bucket
 * @returns {number}
 */
export function countHiddenByDuration(songs, bucket) {
  if (bucket === 'all') return 0
  return songs.length - filterByDuration(songs, bucket).length
}
