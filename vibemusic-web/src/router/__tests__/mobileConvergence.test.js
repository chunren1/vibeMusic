import { describe, it, expect } from 'vitest'
import router from '@/router/index.js'

// Q2c 收敛：/m 路由全部保留（无 404），8 条复用桌面视图，3 条保留移动端核心页
const DESKTOP_REUSE = {
  '/m/likes': { file: 'src/views/LikesView.vue', name: 'm-likes', auth: true },
  '/m/recent': { file: 'src/views/RecentView.vue', name: 'm-recent', auth: true },
  '/m/playlists': { file: 'src/views/PlaylistsView.vue', name: 'm-playlists', auth: true },
  '/m/profile': { file: 'src/views/ProfileView.vue', name: 'm-profile', auth: false },
  '/m/profile/detail': { file: 'src/views/ProfileDetailView.vue', name: 'm-profile-detail', auth: false },
  '/m/playlist/123': { file: 'src/views/PlaylistView.vue', name: 'm-playlist', auth: true },
  '/m/playlist/netease/456': { file: 'src/views/PlaylistDetailView.vue', name: 'm-playlist-detail', auth: false },
  '/m/chat': { file: 'src/views/ChatView.vue', name: 'm-chat', auth: false },
}

const MOBILE_KEPT = {
  '/m': { file: 'src/views/mobile/MHomeView.vue', name: 'm-home' },
  '/m/search': { file: 'src/views/mobile/MSearchView.vue', name: 'm-search' },
  '/m/player': { file: 'src/views/mobile/MPlayerView.vue', name: 'm-player' },
}

async function resolvedFile(path) {
  const resolved = router.resolve(path)
  expect(resolved.matched.length > 0, `${path} 应命中路由`).toBe(true)
  const leaf = resolved.matched[resolved.matched.length - 1]
  const mod = await leaf.components.default()
  return mod.default.__file || ''
}

describe('Q2c 移动端路由收敛', () => {
  it('8 条 /m 路由复用桌面视图（路由名/meta 不变）', async () => {
    for (const [path, want] of Object.entries(DESKTOP_REUSE)) {
      const resolved = router.resolve(path)
      expect(resolved.name, `${path} 路由名`).toBe(want.name)
      const needsAuth = resolved.matched.some((r) => r.meta.requiresAuth)
      expect(needsAuth, `${path} 登录态`).toBe(want.auth)
      expect(await resolvedFile(path), `${path} 视图`).toContain(want.file)
    }
  })

  it('3 个移动端核心页保留（首页/搜索/播放）', async () => {
    for (const [path, want] of Object.entries(MOBILE_KEPT)) {
      expect(router.resolve(path).name, `${path} 路由名`).toBe(want.name)
      expect(await resolvedFile(path), `${path} 视图`).toContain(want.file)
    }
  })

  it('全部 11 条 /m 子路由挂在 MobileShell 下（无 404/野路由）', () => {
    const paths = [...Object.keys(DESKTOP_REUSE), ...Object.keys(MOBILE_KEPT)]
    expect(paths).toHaveLength(11)
    for (const path of paths) {
      const resolved = router.resolve(path)
      expect(resolved.matched[0].path, `${path} 父路由`).toBe('/m')
    }
  })
})
