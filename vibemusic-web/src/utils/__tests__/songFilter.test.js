import { describe, it, expect } from 'vitest'
import {
  PLATFORM_OPTIONS,
  DURATION_OPTIONS,
  platformLabel,
  matchesDuration,
  filterByDuration,
  countHiddenByDuration,
} from '@/utils/songFilter'

/** @param {Partial<Song>} patch */
const song = (patch) => ({ sourceId: '1', name: 'x', ...patch })

describe('songFilter 平台选项', () => {
  it('平台选项覆盖后端支持的全部 5 个来源 + 全部', () => {
    expect(PLATFORM_OPTIONS.map(o => o.value)).toEqual(
      ['all', 'netease', 'qq', 'migu', 'kugou', 'bilibili'],
    )
  })

  it('平台角标文案按来源区分，未知来源原样返回', () => {
    expect(platformLabel('netease')).toBe('网易云')
    expect(platformLabel('qq')).toBe('QQ')
    expect(platformLabel('migu')).toBe('咪咕')
    expect(platformLabel('kugou')).toBe('酷狗')
    expect(platformLabel('bilibili')).toBe('B站')
    expect(platformLabel('somewhere')).toBe('somewhere')
    expect(platformLabel(undefined)).toBe('')
  })
})

describe('songFilter 时长匹配', () => {
  it('全部时长不过滤任何歌曲', () => {
    expect(matchesDuration(undefined, 'all')).toBe(true)
    expect(matchesDuration(30, 'all')).toBe(true)
  })

  it('档位边界按闭区间归类', () => {
    expect(matchesDuration(180, 'short')).toBe(true)
    expect(matchesDuration(181, 'short')).toBe(false)
    expect(matchesDuration(181, 'medium')).toBe(true)
    expect(matchesDuration(300, 'medium')).toBe(true)
    expect(matchesDuration(301, 'medium')).toBe(false)
    expect(matchesDuration(301, 'long')).toBe(true)
  })

  it('时长缺失（undefined / 0 / NaN）不归入任何档位', () => {
    expect(matchesDuration(undefined, 'short')).toBe(false)
    expect(matchesDuration(0, 'short')).toBe(false)
    expect(matchesDuration(NaN, 'long')).toBe(false)
  })

  it('未知档位视为不过滤，避免写错 value 时整页空白', () => {
    expect(matchesDuration(100, 'no-such-bucket')).toBe(true)
  })
})

describe('songFilter 列表过滤', () => {
  const list = [
    song({ sourceId: 'a', duration: 120 }),
    song({ sourceId: 'b', duration: 240 }),
    song({ sourceId: 'c', duration: 400 }),
    song({ sourceId: 'd' }),
  ]

  it('按时长过滤并保持后端顺序', () => {
    expect(filterByDuration(list, 'short').map(s => s.sourceId)).toEqual(['a'])
    expect(filterByDuration(list, 'medium').map(s => s.sourceId)).toEqual(['b'])
    expect(filterByDuration(list, 'long').map(s => s.sourceId)).toEqual(['c'])
  })

  it('全部时长返回原列表', () => {
    expect(filterByDuration(list, 'all')).toHaveLength(4)
  })

  it('隐藏条数包含时长缺失的歌曲，供 UI 如实提示', () => {
    expect(countHiddenByDuration(list, 'short')).toBe(3)
    expect(countHiddenByDuration(list, 'all')).toBe(0)
  })

  it('空列表不产生负数', () => {
    expect(countHiddenByDuration([], 'short')).toBe(0)
  })
})

describe('songFilter 时长选项', () => {
  it('时长选项为全部 + 3 档', () => {
    expect(DURATION_OPTIONS.map(o => o.value)).toEqual(['all', 'short', 'medium', 'long'])
    expect(DURATION_OPTIONS[0].label).toBe('全部时长')
  })
})
