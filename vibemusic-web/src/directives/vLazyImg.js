/**
 * v-lazy-img 指令 — IntersectionObserver 懒加载图片
 * 用法: <img v-lazy-img="url" />
 *       <div v-lazy-img:bg="url" />           (背景图模式)
 *       <div v-lazy-img:bg="url" data-blur="20" />
 */
const observerMap = new WeakMap()

function getObserver() {
  if (observerMap.has(window)) return observerMap.get(window)
  const obs = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue
        const el = entry.target
        const url = el._lazyUrl
        if (!url) continue

        if (el._lazyMode === 'bg') {
          el.style.backgroundImage = `url(${url})`
          const blur = el.dataset.blur
          if (blur) {
            el.style.filter = 'blur(0px)'
            el.style.transition = 'filter 0.3s ease'
          }
        } else {
          if (el.tagName === 'IMG') {
            el.src = url
          } else {
            el.style.backgroundImage = `url(${url})`
          }
        }
        obs.unobserve(el)
        delete el._lazyUrl
        delete el._lazyMode
      }
    },
    {
      rootMargin: '200px 0px',
      threshold: 0,
    }
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

    if (el._lazyMode === 'bg' && el.dataset.blur) {
      el.style.filter = `blur(${el.dataset.blur}px)`
    }

    // 最近6项直接加载（首屏可见），其余懒加载
    const idx = Array.from(el.parentNode?.children || []).indexOf(el)
    if (idx < 6) {
      if (el._lazyMode === 'bg') {
        el.style.backgroundImage = `url(${url})`
      } else if (el.tagName === 'IMG') {
        el.src = url
      }
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
