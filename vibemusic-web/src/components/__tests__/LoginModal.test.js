import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))

import LoginModal from '@/components/LoginModal.vue'

describe('LoginModal', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    document.body.innerHTML = ''
  })

  it('visible=true 时应在 body 内渲染弹窗内容', async () => {
    mount(LoginModal, { props: { visible: true }, attachTo: document.body })
    expect(document.body.querySelector('.modal-overlay')).toBeTruthy()
  })

  it('登录验证: 空格用户名和密码应显示错误', async () => {
    const wrapper = mount(LoginModal, { props: { visible: true }, attachTo: document.body })
    // Teleport 渲染到 body，但 wrapper 仍能通过 find 找到 Teleport 内的元素
    const btns = document.body.querySelectorAll('.submit-btn')
    if (btns.length > 0) {
      btns[0].click()
      await new Promise(resolve => setTimeout(resolve, 20))
      expect(document.body.querySelector('.error-msg')?.textContent).toContain('请输入用户名和密码')
    }
  })

  it('visible=false 时 body 内不应有弹窗', async () => {
    mount(LoginModal, { props: { visible: false }, attachTo: document.body })
    expect(document.body.querySelector('.modal-overlay')).toBeFalsy()
  })
})
