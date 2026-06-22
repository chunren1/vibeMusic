import { onMounted, onUnmounted } from 'vue'

/**
 * 检测点击目标是否在指定元素外部，常用于弹窗/面板的点击外部收起。
 *
 * @param {import('vue').Ref<HTMLElement|null>} panelRef  面板/弹窗的模板 ref
 * @param {() => void}                        onOutside  点击外部时的回调
 * @param {object}                            [options]
 * @param {import('vue').Ref<HTMLElement|null>[]} [options.exclude=[]]  排除的元素 ref 列表（如触发按钮）
 *
 * @example
 * const panelRef = ref(null)
 * const btnRef = ref(null)
 * const show = ref(false)
 * useClickOutside(panelRef, () => { show.value = false }, { exclude: [btnRef] })
 */
export function useClickOutside(panelRef, onOutside, { exclude = [] } = {}) {
  function handleClick(e) {
    const el = panelRef.value
    if (!el) return

    // 点击在面板内部 → 不触发
    if (el.contains(e.target)) return

    // 点击在排除元素上（如触发按钮） → 不触发
    for (const ref of exclude) {
      if (ref.value && ref.value.contains(e.target)) return
    }

    onOutside()
  }

  onMounted(() => {
    document.addEventListener('click', handleClick)
  })

  onUnmounted(() => {
    document.removeEventListener('click', handleClick)
  })
}
