/**
 * 虚拟滚动 Composable — 仅渲染可视区域内的列表项
 * 适用：收藏列表、播放历史、搜索结果等长列表
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'

export function useVirtualList(itemHeight = 56, overscan = 5) {
  const containerRef = ref(null)
  const scrollTop = ref(0)
  const containerHeight = ref(0)
  const totalItems = ref(0)

  let ticking = false
  let resizeObserver = null

  const visibleRange = computed(() => {
    const start = Math.floor(scrollTop.value / itemHeight)
    const visibleCount = Math.ceil(containerHeight.value / itemHeight)
    const startIdx = Math.max(0, start - overscan)
    const endIdx = Math.min(
      totalItems.value,
      start + visibleCount + overscan
    )
    return { startIdx, endIdx }
  })

  const visibleItems = computed(() => ({
    start: visibleRange.value.startIdx,
    end: visibleRange.value.endIdx,
  }))

  const totalHeight = computed(() => totalItems.value * itemHeight)
  const offsetY = computed(() => visibleRange.value.startIdx * itemHeight)

  function onScroll(e) {
    if (!ticking) {
      requestAnimationFrame(() => {
        scrollTop.value = e.target.scrollTop
        ticking = false
      })
      ticking = true
    }
  }

  onMounted(() => {
    const el = containerRef.value
    if (!el) return

    el.addEventListener('scroll', onScroll, { passive: true })
    el.style.willChange = 'scroll-position'

    resizeObserver = new ResizeObserver(entries => {
      for (const entry of entries) {
        containerHeight.value = entry.contentRect.height
      }
    })
    resizeObserver.observe(el)
    containerHeight.value = el.clientHeight
  })

  onBeforeUnmount(() => {
    const el = containerRef.value
    if (el) el.removeEventListener('scroll', onScroll)
    if (resizeObserver) resizeObserver.disconnect()
  })

  function scrollTo(index) {
    const el = containerRef.value
    if (el) el.scrollTop = index * itemHeight
  }

  return {
    containerRef,
    visibleItems,
    totalItems,
    totalHeight,
    offsetY,
    scrollTo,
  }
}
