/**
 * 全局 Toast 通知（单例模式）
 *
 * 使用模块级 ref 共享同一状态，确保全站只有一个 Toast 实例。
 * 多次调用 toast() 会替换当前消息而非堆叠。
 *
 * 用法:
 *   import { useToast } from '@/composables/useToast'
 *   const { toast } = useToast()
 *   toast('操作成功', 'success')
 */
import { ref } from 'vue'

const message = ref('')
const type = ref('info')
const show = ref(false)
let timer = null

export function useToast() {
  function toast(msg, t = 'info', duration = 3000) {
    clearTimeout(timer)
    message.value = msg
    type.value = t
    show.value = false
    // 强制重新触发 Transition
    requestAnimationFrame(() => {
      show.value = true
    })
    timer = setTimeout(() => { show.value = false }, duration)
  }
  return { message, type, show, toast }
}
