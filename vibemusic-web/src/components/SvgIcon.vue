<template>
  <svg
    aria-hidden="true"
    :class="['svg-icon', { 'is-spin': spin }, className]"
    :style="svgStyle"
  >
    <use :xlink:href="symbolId" :href="symbolId" />
  </svg>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  name: { type: String, required: true },
  prefix: { type: String, default: 'icon' },
  className: { type: String, default: '' },
  color: { type: String, default: 'currentColor' },
  size: { type: [String, Number], default: '1em' },
  spin: { type: Boolean, default: false }
})

const symbolId = computed(() => `#${props.prefix}-${props.name}`)

const svgStyle = computed(() => ({
  width: typeof props.size === 'number' ? `${props.size}px` : props.size,
  height: typeof props.size === 'number' ? `${props.size}px` : props.size,
  color: props.color
}))
</script>

<style scoped>
.svg-icon {
  width: 1em;
  height: 1em;
  vertical-align: middle;
  fill: currentColor;
  overflow: hidden;
  display: inline-block;
  flex-shrink: 0;
}

.is-spin {
  animation: spin 1.5s linear infinite;
}

@keyframes spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}
</style>
