import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  // ===== 桌面端路由 =====
  { path: '/', name: 'home', component: () => import('@/views/HomeView.vue') },
  { path: '/search', name: 'search', component: () => import('@/views/SearchView.vue') },
  { path: '/playlists', name: 'playlists', meta: { requiresAuth: true }, component: () => import('@/views/PlaylistsView.vue') },
  { path: '/playlist/:id', name: 'playlist', component: () => import('@/views/PlaylistView.vue') },
  { path: '/likes', name: 'likes', meta: { requiresAuth: true }, component: () => import('@/views/LikesView.vue') },
  { path: '/recent', name: 'recent', meta: { requiresAuth: true }, component: () => import('@/views/RecentView.vue') },
  { path: '/profile', name: 'profile', component: () => import('@/views/ProfileView.vue') },
  { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue') },

  // ===== 移动端路由 (/m) =====
  {
    path: '/m',
    component: () => import('@/views/mobile/MobileShell.vue'),
    children: [
      { path: '', name: 'm-home', component: () => import('@/views/mobile/MHomeView.vue') },
      { path: 'search', name: 'm-search', component: () => import('@/views/mobile/MSearchView.vue') },
      { path: 'likes', name: 'm-likes', meta: { requiresAuth: true }, component: () => import('@/views/mobile/MLikesView.vue') },
      { path: 'recent', name: 'm-recent', meta: { requiresAuth: true }, component: () => import('@/views/mobile/MRecentView.vue') },
      { path: 'playlists', name: 'm-playlists', meta: { requiresAuth: true }, component: () => import('@/views/mobile/MPlaylistsView.vue') },
      { path: 'profile', name: 'm-profile', component: () => import('@/views/mobile/MProfileView.vue') },
      { path: 'player', name: 'm-player', component: () => import('@/views/mobile/MPlayerView.vue') },
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

router.beforeEach((to, from, next) => {
  const authStore = useAuthStore()
  if (to.meta.requiresAuth && !authStore.isLoggedIn) {
    authStore.openLoginWithRedirect(to.fullPath)
    return next(false)
  }

  // 移动端自动跳转：桌面路由路径在移动设备上 → 映射到 /m 路由
  if (!to.path.startsWith('/m') && isMobileDevice()) {
    const map = {
      '/': '/m',
      '/search': '/m/search',
      '/likes': '/m/likes',
      '/recent': '/m/recent',
    }
    if (map[to.path]) return next(map[to.path])
  }

  // 桌面端误入移动路由 → 重定向回桌面首页
  if (to.path.startsWith('/m') && !isMobileDevice()) {
    return next('/')
  }

  next()
})

export default router
