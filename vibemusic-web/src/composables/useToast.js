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
