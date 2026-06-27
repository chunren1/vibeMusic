import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'

// Mock API 请求层
vi.mock('@/api/request', () => ({
  getToken: vi.fn(() => null),
  setToken: vi.fn(),
  API_HOST: 'http://localhost:8080',
}))

vi.mock('@/api/auth', () => ({
  getMe: vi.fn(),
  updateProfile: vi.fn(),
  uploadAvatar: vi.fn(),
  uploadBgImage: vi.fn(),
}))

import { getMe, updateProfile, uploadAvatar, uploadBgImage } from '@/api/auth'
import { getToken, setToken } from '@/api/request'

describe('AuthStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    getToken.mockReturnValue(null)
    localStorage.clear()
  })

  describe('初始状态', () => {
    it('未登录时 isLoggedIn 应为 false', () => {
      const auth = useAuthStore()
      expect(auth.isLoggedIn).toBe(false)
      expect(auth.user).toBeNull()
      expect(auth.sessionChecked).toBe(false)
    })

    it('已有 token 时 isLoggedIn 应为 true', () => {
      getToken.mockReturnValue('existing-token')
      // 需要重新创建 store 实例以读取 mock 后的 token
      setActivePinia(createPinia())
      const auth = useAuthStore()
      expect(auth.isLoggedIn).toBe(true)
    })
  })

  describe('login 登录', () => {
    it('有效 token 应登录成功并设置用户', () => {
      const auth = useAuthStore()
      const result = auth.login('test-token', { username: 'testuser' })

      expect(result).toBe(true)
      expect(auth.token).toBe('test-token')
      expect(auth.user).toEqual({ username: 'testuser' })
      expect(auth.isLoggedIn).toBe(true)
      expect(auth.sessionChecked).toBe(true)
      expect(setToken).toHaveBeenCalledWith('test-token')
    })

    it('空 token 应登录失败返回 false', () => {
      const auth = useAuthStore()
      const result = auth.login(null, {})

      expect(result).toBe(false)
      expect(auth.isLoggedIn).toBe(false)
      expect(setToken).not.toHaveBeenCalled()
    })

    it('空字符串 token 应登录失败', () => {
      const auth = useAuthStore()
      const result = auth.login('', {})

      expect(result).toBe(false)
    })
  })

  describe('logout 登出', () => {
    it('应清除 token、用户、重定向路径和弹窗状态', () => {
      const auth = useAuthStore()
      auth.login('token', { username: 'test' })
      auth.openLoginWithRedirect('/profile')

      auth.logout()

      expect(auth.token).toBeNull()
      expect(auth.user).toBeNull()
      expect(auth.redirectPath).toBeNull()
      expect(auth.showLoginModal).toBe(false)
      expect(auth.sessionChecked).toBe(true)
      expect(setToken).toHaveBeenCalledWith(null)
    })
  })

  describe('登录弹窗控制', () => {
    it('openLogin 应显示弹窗', () => {
      const auth = useAuthStore()
      auth.openLogin()
      expect(auth.showLoginModal).toBe(true)
    })

    it('closeLogin 应隐藏弹窗', () => {
      const auth = useAuthStore()
      auth.openLogin()
      auth.closeLogin()
      expect(auth.showLoginModal).toBe(false)
    })

    it('openLoginWithRedirect 应设置重定向路径并显示弹窗', () => {
      const auth = useAuthStore()
      auth.openLoginWithRedirect('/playlists')
      expect(auth.showLoginModal).toBe(true)
      expect(auth.redirectPath).toBe('/playlists')
    })

    it('consumeRedirect 应返回路径并清除', () => {
      const auth = useAuthStore()
      auth.openLoginWithRedirect('/playlists')

      const path = auth.consumeRedirect()
      expect(path).toBe('/playlists')
      expect(auth.redirectPath).toBeNull()
    })
  })

  describe('avatarSrc / bgImageSrc 计算属性', () => {
    it('无头像时应返回空字符串', () => {
      const auth = useAuthStore()
      expect(auth.avatarSrc).toBe('')
    })

    it('相对路径头像应拼接 API_HOST', () => {
      const auth = useAuthStore()
      auth.login('token', { avatar: '/uploads/avatar.jpg' })
      expect(auth.avatarSrc).toBe('http://localhost:8080/uploads/avatar.jpg')
    })

    it('完整 URL 头像应直接返回', () => {
      const auth = useAuthStore()
      auth.login('token', { avatar: 'https://cdn.example.com/avatar.jpg' })
      expect(auth.avatarSrc).toBe('https://cdn.example.com/avatar.jpg')
    })

    it('背景图同样支持相对路径和完整 URL', () => {
      const auth = useAuthStore()
      auth.login('token', { bgImage: '/uploads/bg.jpg' })
      expect(auth.bgImageSrc).toBe('http://localhost:8080/uploads/bg.jpg')

      auth.user = { bgImage: 'https://cdn.example.com/bg.jpg' }
      expect(auth.bgImageSrc).toBe('https://cdn.example.com/bg.jpg')
    })
  })

  describe('tryRestoreSession 恢复会话', () => {
    it('已有 token 时应跳过恢复', async () => {
      const auth = useAuthStore()
      auth.login('existing', {})

      await auth.tryRestoreSession()

      expect(getMe).not.toHaveBeenCalled()
    })

    it('sessionChecked 为 true 时应跳过恢复', async () => {
      getToken.mockReturnValue(null)
      const auth = useAuthStore()
      auth.sessionChecked = true

      await auth.tryRestoreSession()

      expect(getMe).not.toHaveBeenCalled()
    })

    it('成功恢复会话应设置用户信息', async () => {
      getToken.mockReturnValue(null)
      getMe.mockResolvedValue({
        code: 200,
        data: { userId: 1, username: 'testuser', nickname: '测试', avatar: '/a.jpg', bgImage: null }
      })
      const auth = useAuthStore()

      await auth.tryRestoreSession()

      expect(auth.isLoggedIn).toBe(true)
      expect(auth.user.username).toBe('testuser')
      expect(auth.sessionChecked).toBe(true)
    })

    it('未登录时应静默失败并标记 sessionChecked', async () => {
      getToken.mockReturnValue(null)
      getMe.mockRejectedValue(new Error('401'))
      const auth = useAuthStore()

      await auth.tryRestoreSession()

      expect(auth.isLoggedIn).toBe(false)
      expect(auth.sessionChecked).toBe(true)
    })
  })

  describe('updateUserProfile 更新资料', () => {
    it('成功更新应合并用户信息', async () => {
      const auth = useAuthStore()
      auth.login('token', { username: 'old', nickname: '旧昵称' })
      updateProfile.mockResolvedValue({ code: 200, data: { nickname: '新昵称' } })

      const res = await auth.updateUserProfile({ nickname: '新昵称' })

      expect(res.code).toBe(200)
      expect(auth.user.nickname).toBe('新昵称')
      expect(auth.user.username).toBe('old') // 保留原有字段
    })

    it('接口返回非 200 不应修改用户信息', async () => {
      const auth = useAuthStore()
      auth.login('token', { nickname: '旧昵称' })
      updateProfile.mockResolvedValue({ code: 400, data: null })

      await auth.updateUserProfile({ nickname: '新昵称' })

      expect(auth.user.nickname).toBe('旧昵称')
    })
  })

  describe('uploadUserAvatar 上传头像', () => {
    it('成功上传应更新用户头像', async () => {
      const auth = useAuthStore()
      auth.login('token', { username: 'test' })
      uploadAvatar.mockResolvedValue({ code: 200, data: { avatar: '/uploads/new.jpg' } })

      await auth.uploadUserAvatar(new File([], 'avatar.png'))

      expect(auth.user.avatar).toBe('/uploads/new.jpg')
    })
  })

  describe('uploadUserBgImage 上传背景图', () => {
    it('成功上传应更新用户背景图', async () => {
      const auth = useAuthStore()
      auth.login('token', { username: 'test' })
      uploadBgImage.mockResolvedValue({ code: 200, data: { bgImage: '/uploads/bg.jpg' } })

      await auth.uploadUserBgImage(new File([], 'bg.jpg'))

      expect(auth.user.bgImage).toBe('/uploads/bg.jpg')
    })
  })
})
