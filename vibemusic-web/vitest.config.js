import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    include: ['src/**/*.{test,spec}.{js,ts}'],
    setupFiles: ['./src/test-setup.js'],
    css: false,
    mockReset: true,
    coverage: {
      provider: 'v8',
      include: ['src/stores/**', 'src/components/**', 'src/views/**'],
      // 2026-10-23 实测（含 views）：28.59 / 18.43 / 17.17 / 32.42。
      // 门禁先钉在实测基线，只允许往上；目标 40/35 需先给 views 补测试再抬。
      thresholds: {
        statements: 28,
        branches: 18,
        functions: 17,
        lines: 32,
      },
    },
  },
})
