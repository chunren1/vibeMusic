/**
 * v-lazy-img 指令 — IntersectionObserver 懒加载图片 + 兜底色
 *
 * 用法:
 *   <img v-lazy-img="url" />
 *   <div v-lazy-img:bg="coverUrl" />               <!-- 图片背景 -->
 *   <div v-lazy-img:bg="coverUrl || '#1a1a2e'" />  <!-- 无图时兜底色 -->
 *
 * 重要: 不要在同元素上同时使用 :style 和 v-lazy-img。
 */
const observerMap = new WeakMap()

function applyImage(el) {
  const url = el._lazyUrl
  if (!url) return

  if (el._lazyMode === 'bg') {
    if (url.startsWith('http') || url.startsWith('//')) {
      // !important 确保覆盖 CSS background 简写的 background-image: none
      el.style.setProperty('background-image', `url(${url})`, 'important')
    } else {
      // 纯色兜底
      el.style.setProperty('background-image', 'none', 'important')
      el.style.backgroundColor = url
    }
  } else if (el.tagName === 'IMG') {
    el.src = url
  }
  delete el._lazyUrl
  delete el._lazyMode
}

function getObserver() {
  if (observerMap.has(window)) return observerMap.get(window)
  const obs = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue
        applyImage(entry.target)
        obs.unobserve(entry.target)
      }
    },
    { rootMargin: '200px 0px', threshold: 0 }
  )
  observerMap.set(window, obs)
  return obs
}

export default {
  mounted(el, binding) {
    const url = binding.value
    if (!url) return
    el._lazyUrl = url
    el._lazyMode = binding.arg === 'bg' ? 'bg' : 'img'

    const siblings = el.parentNode?.children || []
    const idx = Array.from(siblings).indexOf(el)
    if (idx < 6) {
      applyImage(el)
      return
    }
    getObserver().observe(el)
  },

  unmounted(el) {
    getObserver().unobserve(el)
    delete el._lazyUrl
    delete el._lazyMode
  },
}
