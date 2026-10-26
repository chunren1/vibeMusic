import { onMounted, onUnmounted } from 'vue'

/**
 * 弹层键盘关闭：监听 Esc。
 * 弹层常驻挂载（v-if 在内部）时用 isActive 判断当前是否打开，避免关着的弹层抢 Esc。
 * @param {() => void} onEscape 按下 Esc 时执行
 * @param {() => boolean} [isActive] 返回 false 时不响应
 */
export function useEscapeKey(onEscape, isActive = () => true) {
  /** @param {KeyboardEvent} e */
  function onKeydown(e) {
    if (e.key !== 'Escape' || e.defaultPrevented) return
    if (!isActive()) return
    e.preventDefault()
    onEscape()
  }

  onMounted(() => window.addEventListener('keydown', onKeydown))
  onUnmounted(() => window.removeEventListener('keydown', onKeydown))
}
