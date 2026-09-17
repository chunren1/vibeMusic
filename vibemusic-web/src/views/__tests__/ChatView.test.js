import { describe, it, expect, beforeEach, vi } from 'vitest'
import { shallowMount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'

import ChatView from '@/views/ChatView.vue'

// 模拟请求层：doSend 依赖 /assistant/chat；保留 getToken 供 auth store 使用
vi.mock('@/api/request', () => ({
  default: { post: vi.fn().mockResolvedValue({ data: { reply: 'ok', songs: [] } }) },
  getToken: vi.fn().mockReturnValue(null),
  setToken: vi.fn(),
}))

describe('ChatView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('初始化时渲染快捷提示', () => {
    const wrapper = shallowMount(ChatView)
    expect(wrapper.text()).toContain('周杰伦')
  })

  it('消息超过上限时丢弃最旧消息', async () => {
    const wrapper = shallowMount(ChatView)
    const input = wrapper.find('input')
    // 1 条问候 + 101 轮 × 2 条 = 203 条 → 截断为 200
    for (let i = 0; i < 101; i++) {
      await input.setValue('测试消息')
      await input.trigger('keyup.enter')
      await flushPromises()
    }
    expect(wrapper.vm.messages.length).toBe(200)
    // 最旧的问候与首轮消息已被丢弃，剩余首条为用户消息
    expect(wrapper.vm.messages[0].role).toBe('user')
    // 101 轮渲染最多 200 条消息，jsdom 下耗时约 4s；全量并行时 CPU 争用会超过默认 5s 上限
  }, 15000)
})
