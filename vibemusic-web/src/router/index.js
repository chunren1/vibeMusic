import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  { path: '/', name: 'home', component: () => import('@/views/HomeView.vue') },
  { path: '/search', name: 'search', component: () => import('@/views/SearchView.vue') },
  { path: '/playlists', name: 'playlists', meta: { requiresAuth: true }, component: () => import('@/views/PlaylistsView.vue') },
  { path: '/playlist/:id', name: 'playlist', component: () => import('@/views/PlaylistView.vue') },
  { path: '/likes', name: 'likes', meta: { requiresAuth: true }, component: () => import('@/views/LikesView.vue') },
  { path: '/recent', name: 'recent', meta: { requiresAuth: true }, component: () => import('@/views/RecentView.vue') },
  { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue') },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})

router.beforeEach((to, from, next) => {
  const authStore = useAuthStore()
  if (to.meta.requiresAuth && !authStore.isLoggedIn) {
    // 弹出登录弹窗，不跳转页面
    authStore.openLogin()
    return next(false)
  }
  next()
})

export default router
