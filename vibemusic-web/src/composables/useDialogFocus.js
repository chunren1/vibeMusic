import { nextTick, watch } from 'vue'

/** 可聚焦元素（与浏览器 Tab 序列大致一致，不含 tabindex="-1"） */
const FOCUSABLE = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(', ')

/**
 * 弹层焦点管理：打开时记住触发元素、把焦点移进弹层；关闭时把焦点还回去。
 * 只做「初始聚焦 + 回填」，不做 focus trap（Tab 到最后一个元素后会走到浏览器地址栏，属预期行为）。
 * @param {import('vue').Ref<boolean> | (() => boolean)} isOpen 弹层是否打开
 * @param {import('vue').Ref<HTMLElement|null>} panelRef 弹层容器（兜底聚焦时需带 tabindex="-1"）
 * @param {{ initialSelector?: string }} [options] initialSelector 优先聚焦的元素
 */
export function useDialogFocus(isOpen, panelRef, options = {}) {
  /** @type {HTMLElement|null} */
  let lastFocused = null

  /** 打开：记录触发元素 + 焦点移入 */
  async function capture() {
    lastFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null
    await nextTick()
    const panel = panelRef.value
    if (!panel) return
    const preferred = options.initialSelector ? panel.querySelector(options.initialSelector) : null
    const target = preferred || panel.querySelector(FOCUSABLE) || panel
    if (target instanceof HTMLElement) target.focus({ preventScroll: true })
  }

  /** 关闭：焦点回填到触发元素（元素已不在文档里就放弃） */
  function restore() {
    if (lastFocused && document.contains(lastFocused)) {
      lastFocused.focus({ preventScroll: true })
    }
    lastFocused = null
  }

  watch(isOpen, (open) => {
    if (open) capture()
    else restore()
  }, { immediate: true })
}
