import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  // ===== 桌面端路由 =====
  { path: '/', name: 'home', component: () => import('@/views/HomeView.vue') },
  { path: '/search', name: 'search', component: () => import('@/views/SearchView.vue') },
  { path: '/playlists', name: 'playlists', meta: { requiresAuth: true }, component: () => import('@/views/PlaylistsView.vue') },
  { path: '/playlist/:source/:id', name: 'playlist-detail', component: () => import('@/views/PlaylistDetailView.vue') },
  { path: '/playlist/:id', name: 'playlist', component: () => import('@/views/PlaylistView.vue') },
  { path: '/likes', name: 'likes', meta: { requiresAuth: true }, component: () => import('@/views/LikesView.vue') },
  { path: '/recent', name: 'recent', meta: { requiresAuth: true }, component: () => import('@/views/RecentView.vue') },
  { path: '/profile', name: 'profile', component: () => import('@/views/ProfileView.vue') },
  { path: '/profile/detail', name: 'profile-detail', component: () => import('@/views/ProfileDetailView.vue') },
  { path: '/chat', name: 'chat', component: () => import('@/views/ChatView.vue') },
  { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue') },

  // ===== 移动端路由 (/m) =====
  // Q2c 收敛：仅保留 3 个移动端核心页（首页/搜索/播放器），其余 /m 路由
  // 复用桌面端视图（响应式渲染），路由名/路径/meta 保持不变，无 404。
  // 被替换的 mobile/*.vue 文件保留在原位，仅不再被路由引用，便于回滚。
  {
    path: '/m',
    component: () => import('@/views/mobile/MobileShell.vue'),
    children: [
      { path: '', name: 'm-home', component: () => import('@/views/mobile/MHomeView.vue') },
      { path: 'search', name: 'm-search', component: () => import('@/views/mobile/MSearchView.vue') },
      { path: 'likes', name: 'm-likes', meta: { requiresAuth: true }, component: () => import('@/views/LikesView.vue') },
      { path: 'recent', name: 'm-recent', meta: { requiresAuth: true }, component: () => import('@/views/RecentView.vue') },
      { path: 'playlists', name: 'm-playlists', meta: { requiresAuth: true }, component: () => import('@/views/PlaylistsView.vue') },
      { path: 'profile', name: 'm-profile', component: () => import('@/views/ProfileView.vue') },
      { path: 'profile/detail', name: 'm-profile-detail', component: () => import('@/views/ProfileDetailView.vue') },
      { path: 'playlist/:id', name: 'm-playlist', meta: { requiresAuth: true }, component: () => import('@/views/PlaylistView.vue') },
      { path: 'playlist/:source/:id', name: 'm-playlist-detail', component: () => import('@/views/PlaylistDetailView.vue') },
      { path: 'player', name: 'm-player', component: () => import('@/views/mobile/MPlayerView.vue') },
      { path: 'chat', name: 'm-chat', component: () => import('@/views/ChatView.vue') },
    ]
  },

  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})

function isMobileDevice() {
  const ua = navigator.userAgent || ''
  return window.innerWidth < 768 ||
    /Android|iPhone|iPad|iPod|webOS|BlackBerry|Windows Phone/i.test(ua)
}

router.beforeEach(async (to, from, next) => {
  const authStore = useAuthStore()

  // 仅需登录的路由才等待会话恢复，公开路由不阻塞直接放行
  const needsAuth = to.meta.requiresAuth || to.matched.some(r => r.meta.requiresAuth)
  if (needsAuth && !authStore.sessionChecked) {
    await authStore.tryRestoreSession()
  }

  if (to.meta.requiresAuth && !authStore.isLoggedIn) {
    authStore.openLoginWithRedirect(to.fullPath)
    // 重定向到首页而非 next(false)，避免 RouterView 无内容渲染导致黑屏
    if (isMobileDevice()) return next('/m')
    return next('/')
  }

  // 移动端自动跳转：桌面路由路径在移动设备上 → 映射到 /m 路由
  if (!to.path.startsWith('/m') && isMobileDevice()) {
    const map = {
      '/': '/m',
      '/search': '/m/search',
      '/playlists': '/m/playlists',
      '/likes': '/m/likes',
      '/recent': '/m/recent',
      '/profile': '/m/profile',
      '/profile/detail': '/m/profile/detail',
      '/chat': '/m/chat',
    }
    if (map[to.path]) return next(map[to.path])
    if (to.path.startsWith('/playlist/')) {
      if (to.name === 'playlist') {
        return next('/m/playlist/' + to.params.id)
      }
      return next('/m/playlist/' + to.params.source + '/' + to.params.id)
    }
  }

  // 桌面端误入移动路由 → 重定向回桌面首页
  if (to.path.startsWith('/m') && !isMobileDevice()) {
    return next('/')
  }

  next()
})

export default router
