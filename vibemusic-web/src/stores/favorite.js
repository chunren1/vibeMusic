import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getFavoriteIds, toggleFavorite } from '@/api/song'

/**
 * 全局收藏状态管理
 * 所有页面/组件的收藏状态统一从此 store 读取和操作，
 * 实现跨页面收藏状态同步：在搜索页收藏的歌曲，歌词页/播放页立即同步显示已收藏样式。
 */
export const useFavoriteStore = defineStore('favorite', () => {
  const favIds = ref(new Set())
  const loaded = ref(false)
  const loading = ref(false)

  /** 从后端加载收藏 ID 集合 */
  async function fetchFavIds() {
    if (loading.value) return
    loading.value = true
    try {
      const res = await getFavoriteIds()
      if (res.data) {
        favIds.value = new Set(res.data)
        // 保持 window 全局兼容（LyricsView 等旧代码可能还在读）
        window.vibeFavIds = favIds.value
      }
      loaded.value = true
    } catch {
      // 静默失败，不影响页面正常使用
    } finally {
      loading.value = false
    }
  }

  /** 判断 sourceId 是否已收藏 */
  function isFav(sourceId) {
    if (!sourceId) return false
    return favIds.value.has(sourceId)
  }

  /** 切换收藏状态（乐观更新 + 回滚） */
  async function toggleFav(song) {
    const sid = song.sourceId || song.id || song.songId
    if (!sid) return

    const was = favIds.value.has(sid)
    // 乐观更新
    if (was) favIds.value.delete(sid)
    else favIds.value.add(sid)
    // 触发 Vue 响应式（Set 变异不自动触发）
    favIds.value = new Set(favIds.value)
    window.vibeFavIds = favIds.value

    try {
      const artifact = song.name || song.songName || song.title || ''
      const artist = song.artist || ''
      const cover = song.coverUrl || ''
      const res = await toggleFavorite(sid, artifact, artist, cover)
      // 以后端返回为准
      if (res.data === true) {
        favIds.value.add(sid)
      } else {
        favIds.value.delete(sid)
      }
      favIds.value = new Set(favIds.value)
      window.vibeFavIds = favIds.value
    } catch {
      // 网络异常等，回滚
      if (was) favIds.value.add(sid)
      else favIds.value.delete(sid)
      favIds.value = new Set(favIds.value)
      window.vibeFavIds = favIds.value
    }
  }

  return { favIds, loaded, loading, fetchFavIds, isFav, toggleFav }
})
