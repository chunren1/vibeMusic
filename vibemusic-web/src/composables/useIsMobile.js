import { ref, onMounted, watchEffect } from 'vue'

const isMobile = ref(false)

/** 设备检测：screen width < 768 或 userAgent 包含移动端标识 */
function detect() {
  const ua = navigator.userAgent || ''
  return window.innerWidth < 768 ||
    /Android|iPhone|iPad|iPod|webOS|BlackBerry|Windows Phone/i.test(ua)
}

export function useIsMobile() {
  onMounted(() => {
    isMobile.value = detect()
    window.addEventListener('resize', () => { isMobile.value = detect() })
  })
  watchEffect(() => {
    if (isMobile.value) document.documentElement.classList.add('mobile')
    else document.documentElement.classList.remove('mobile')
  })
  return { isMobile }
}

/** 纯函数版，不需要 onMounted（用于路由守卫等同步场景） */
export function checkMobile() {
  return detect()
}
