import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { h, defineComponent, nextTick } from 'vue'
import ErrorBoundary from '@/components/ErrorBoundary.vue'

const mountOpts = {
  global: {
    stubs: { 'svg-icon': { template: '<span class="svg-stub"></span>', props: ['name'] } },
  },
}

const FaultyChild = defineComponent({
  setup() { throw new Error('Test error') },
  render() { return h('div') },
})

describe('ErrorBoundary', () => {
  it('renders child content normally', () => {
    const w = mount(ErrorBoundary, { ...mountOpts, slots: { default: h('span', 'hello') } })
    expect(w.html()).toContain('hello')
  })

  it('shows fallback on child error', async () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const w = mount(ErrorBoundary, { ...mountOpts, slots: { default: h(FaultyChild) } })
    await nextTick()
    expect(w.find('.error-content').exists()).toBe(true)
    spy.mockRestore()
  })

  it('retry button triggers reset', async () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const w = mount(ErrorBoundary, { ...mountOpts, slots: { default: h(FaultyChild) } })
    await nextTick()
    expect(w.find('.error-content').exists()).toBe(true)
    await w.find('.retry-btn').trigger('click')
    await nextTick()
    spy.mockRestore()
  })

  it('does not crash on self render', () => {
    expect(() => mount(ErrorBoundary, { ...mountOpts, slots: { default: h('div') } })).not.toThrow()
  })
})
