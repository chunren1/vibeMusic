import { describe, it, expect, beforeEach, vi } from 'vitest'
import { shallowMount } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'

import ChatView from '@/views/ChatView.vue'

describe('ChatView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('初始化时渲染快捷提示', () => {
    const wrapper = shallowMount(ChatView)
    expect(wrapper.text()).toContain('周杰伦')
  })
})
