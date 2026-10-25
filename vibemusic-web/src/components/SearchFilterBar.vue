<script setup>
import { computed } from 'vue'
import { DURATION_OPTIONS, PLATFORM_OPTIONS } from '@/utils/songFilter'

/**
 * 搜索结果筛选条（桌面 / 移动共用）。
 * 平台切换交给父级重新请求（后端过滤），时长切换是父级客户端过滤。
 */
const props = defineProps({
  /** 当前平台，'all' = 全平台 */
  platform: { type: String, default: 'all' },
  /** 当前时长档位，'all' = 不限 */
  duration: { type: String, default: 'all' },
  /** desktop = 可换行；mobile = 单行横向滚动 */
  variant: { type: String, default: 'desktop' },
})

const emit = defineEmits(['update:platform', 'update:duration', 'clear'])

const isFiltered = computed(() => props.platform !== 'all' || props.duration !== 'all')
</script>

<template>
  <div class="filter-bar" :class="`is-${variant}`" role="group" aria-label="筛选搜索结果">
    <div class="chip-group" role="group" aria-label="按平台筛选">
      <button
        v-for="opt in PLATFORM_OPTIONS"
        :key="opt.value"
        type="button"
        class="filter-chip"
        :class="{ active: platform === opt.value }"
        :aria-pressed="platform === opt.value"
        @click="emit('update:platform', opt.value)"
      >{{ opt.label }}</button>
    </div>

    <span class="group-sep" aria-hidden="true"></span>

    <div class="chip-group" role="group" aria-label="按时长筛选">
      <button
        v-for="opt in DURATION_OPTIONS"
        :key="opt.value"
        type="button"
        class="filter-chip"
        :class="{ active: duration === opt.value }"
        :aria-pressed="duration === opt.value"
        @click="emit('update:duration', opt.value)"
      >{{ opt.label }}</button>
    </div>

    <button v-if="isFiltered" type="button" class="filter-clear" @click="emit('clear')">
      清除筛选
    </button>
  </div>
</template>

<style scoped>
/* ===== 桌面：轻量 pill 行，视觉权重低于搜索框 ===== */
.filter-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.chip-group {
  display: flex;
  align-items: center;
  gap: 6px;
}

.filter-chip {
  height: 28px;
  padding: 0 12px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: var(--radius-full);
  background: transparent;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 500;
  line-height: 1;
  white-space: nowrap;
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease-out),
    border-color var(--dur-fast) var(--ease-out),
    color var(--dur-fast) var(--ease-out);
}

.filter-chip:hover {
  color: var(--text-primary);
  border-color: rgba(255, 255, 255, 0.2);
}

.filter-chip.active {
  background: rgba(49, 194, 124, 0.12);
  border-color: rgba(49, 194, 124, 0.45);
  color: var(--primary);
}

.filter-chip.active:hover {
  border-color: rgba(49, 194, 124, 0.65);
}

.group-sep {
  width: 1px;
  height: 14px;
  flex-shrink: 0;
  background: rgba(255, 255, 255, 0.1);
}

.filter-clear {
  margin-left: auto;
  padding: 0 2px;
  border: none;
  background: none;
  color: var(--text-tertiary);
  font-size: 12px;
  cursor: pointer;
  transition: color var(--dur-fast) var(--ease-out);
}

.filter-clear:hover {
  color: var(--primary);
}

/* ===== 移动端：单行横向滚动，绝不挤压上方搜索框 ===== */
.filter-bar.is-mobile {
  flex-wrap: nowrap;
  margin-bottom: 8px;
  padding-bottom: 2px;
  overflow-x: auto;
  overflow-y: hidden;
  -webkit-overflow-scrolling: touch;
  scrollbar-width: none;
}

.filter-bar.is-mobile::-webkit-scrollbar {
  display: none;
}

.filter-bar.is-mobile .chip-group {
  flex-wrap: nowrap;
}

.filter-bar.is-mobile .filter-chip {
  height: 34px;
  padding: 0 14px;
  background: var(--m-bg-card);
  border-color: rgba(255, 255, 255, 0.06);
  color: var(--m-text-secondary);
}

.filter-bar.is-mobile .filter-chip.active {
  background: rgba(49, 194, 124, 0.12);
  border-color: rgba(49, 194, 124, 0.45);
  color: var(--primary);
}

.filter-bar.is-mobile .filter-clear {
  flex-shrink: 0;
  margin-left: 4px;
  color: var(--m-text-tertiary);
}
</style>
