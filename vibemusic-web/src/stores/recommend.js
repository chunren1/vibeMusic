import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getPersonalizedRecommend, getRandomSongs } from '@/api/song'

const DEVICE_ID_KEY = 'vibe_device_id'

function getDeviceId() {
  try {
    let id = localStorage.getItem(DEVICE_ID_KEY)
    if (!id) {
      id = 'dev_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 8)
      localStorage.setItem(DEVICE_ID_KEY, id)
    }
    return id
  } catch {
    return null
  }
}

/**
 * 首页推荐 Store
 * 封装个性化推荐数据获取、缓存与状态管理
 * 共享给桌面端 HomeView.vue 和移动端 MHomeView.vue
 */
export const useRecommendStore = defineStore('recommend', () => {
  const songs = ref([])
  const greeting = ref('')
  const loading = ref(false)
  const error = ref('')

  const deviceId = getDeviceId()

  /** 是否有数据 */
  const hasData = computed(() => songs.value.length > 0)

  /** 获取推荐数据 */
  async function fetchRecommend() {
    if (loading.value) return
    loading.value = true
    error.value = ''

    try {
      const res = await getPersonalizedRecommend(deviceId)
      const data = res.data
      if (data) {
        songs.value = (data.songs || []).map(s => ({
          ...s,
          coverColor: randomColor(),
        }))
        greeting.value = data.greeting || ''
      }
    } catch {
      // 推荐接口异常 → 降级为随机推荐
      error.value = '推荐服务暂时不可用，已为您展示精选歌曲'
      try {
        const fallback = await getRandomSongs(8)
        songs.value = (fallback.data || []).map(s => ({
          ...s,
          coverColor: randomColor(),
        }))
        greeting.value = '为你精选了一些好歌~'
      } catch {
        songs.value = []
        greeting.value = ''
      }
    } finally {
      loading.value = false
    }
  }

  function randomColor() {
    const colors = ['#31c27c', '#ec4141', '#5b3cc4', '#d44455', '#3c7cc4', '#c48b3c']
    return colors[Math.floor(Math.random() * colors.length)]
  }

  return { songs, greeting, loading, error, hasData, fetchRecommend }
})
