<script setup>
import { ref, watch } from 'vue'

const props = defineProps({
  message: { type: String, default: '' },
  type: { type: String, default: 'info' }, // info | success | error
  duration: { type: Number, default: 3000 },
  show: { type: Boolean, default: false },
})
const emit = defineEmits(['close'])

const visible = ref(false)
let timer = null

watch(() => props.show, (val) => {
  if (val) {
    visible.value = true
    clearTimeout(timer)
    timer = setTimeout(() => { visible.value = false; emit('close') }, props.duration)
  }
})

function dismiss() {
  clearTimeout(timer)
  visible.value = false
  emit('close')
}
</script>

<template>
  <Teleport to="body">
    <Transition name="toast">
      <div v-if="visible" class="toast" :class="type" @click="dismiss">
        <span>{{ message }}</span>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.toast {
  position: fixed; top: 24px; left: 50%; transform: translateX(-50%);
  z-index: 10000;
  padding: 12px 28px; border-radius: 10px;
  font-size: 15px; font-weight: 500; cursor: pointer;
  box-shadow: 0 8px 32px rgba(0,0,0,0.15);
  white-space: nowrap; max-width: 90vw;
  text-align: center;
}
.toast.info { background: #333; color: #fff; }
.toast.success { background: #31c27c; color: #fff; }
.toast.error { background: #e84c3d; color: #fff; }

.toast-enter-active { transition: all .25s ease; }
.toast-leave-active { transition: all .2s ease; }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateX(-50%) translateY(-12px); }
</style>
