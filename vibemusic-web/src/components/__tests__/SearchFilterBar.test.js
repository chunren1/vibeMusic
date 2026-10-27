import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import SearchFilterBar from '@/components/SearchFilterBar.vue'

/** @param {Record<string, unknown>} [props] */
function mountBar(props = {}) {
  return mount(SearchFilterBar, { props: { platform: 'all', duration: 'all', ...props } })
}

/** @param {import('@vue/test-utils').VueWrapper} wrapper @param {string} label */
function chip(wrapper, label) {
  const found = wrapper.findAll('.filter-chip').find(b => b.text() === label)
  if (!found) throw new Error(`chip not found: ${label}`)
  return found
}

describe('SearchFilterBar', () => {
  it('渲染 5 个平台项 + 4 个时长项，全部是原生 button（键盘可达）', () => {
    const wrapper = mountBar()
    const chips = wrapper.findAll('.filter-chip')

    // QQ 平台项 2026-10-27 起隐藏（源停用），故 5 个平台项而非 6 个。
    expect(chips).toHaveLength(9)
    expect(chips.every(c => c.element.tagName === 'BUTTON')).toBe(true)
    expect(chips.every(c => c.attributes('type') === 'button')).toBe(true)
    expect(chips.map(c => c.text())).toEqual([
      '全部平台', '网易云', '咪咕', '酷狗', 'B站',
      '全部时长', '3 分钟内', '3-5 分钟', '5 分钟以上',
    ])
  })

  it('选中态用 aria-pressed 表达，默认选中两个「全部」', () => {
    const wrapper = mountBar()
    expect(chip(wrapper, '全部平台').attributes('aria-pressed')).toBe('true')
    expect(chip(wrapper, '全部时长').attributes('aria-pressed')).toBe('true')
    expect(chip(wrapper, '咪咕').attributes('aria-pressed')).toBe('false')
  })

  it('点击平台项 emit update:platform', async () => {
    const wrapper = mountBar({ platform: 'migu' })
    await chip(wrapper, '酷狗').trigger('click')
    expect(wrapper.emitted('update:platform')).toEqual([['kugou']])
  })

  it('点击时长项 emit update:duration', async () => {
    const wrapper = mountBar()
    await chip(wrapper, '3 分钟内').trigger('click')
    expect(wrapper.emitted('update:duration')).toEqual([['short']])
  })

  it('无筛选时不显示清除按钮；有筛选时显示并 emit clear', async () => {
    const plain = mountBar()
    expect(plain.find('.filter-clear').exists()).toBe(false)

    const filtered = mountBar({ platform: 'migu' })
    await filtered.find('.filter-clear').trigger('click')
    expect(filtered.emitted('clear')).toHaveLength(1)
  })

  it('移动端变体用横向滚动类，不挤压搜索框', () => {
    const wrapper = mountBar({ variant: 'mobile' })
    expect(wrapper.find('.filter-bar').classes()).toContain('is-mobile')
  })

  it('分组用 role=group 提供语义', () => {
    const wrapper = mountBar()
    const groups = wrapper.findAll('[role="group"]')
    expect(groups.length).toBeGreaterThanOrEqual(3)
    expect(groups[0].attributes('aria-label')).toBe('筛选搜索结果')
  })
})
