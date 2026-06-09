import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    target: 'es2015',               // 兼容大多数移动浏览器
    cssCodeSplit: true,             // CSS 按需加载
    assetsInlineLimit: 4096,        // 小于 4KB 的资源内联为 base64
    chunkSizeWarningLimit: 600,     // 提高阈值避免警告
    rollupOptions: {
      output: {
        // 分包策略：将 node_modules 中的大库拆成独立 chunk，利用浏览器缓存
        manualChunks(id) {
          if (id.includes('node_modules')) {
            if (id.includes('vue') || id.includes('pinia') || id.includes('vue-router')) return 'vendor-vue'
            if (id.includes('axios')) return 'vendor-axios'
            return 'vendor'
          }
        },
      },
    },
  },
  server: {
    port: 5173,
    host: '0.0.0.0',
    allowedHosts: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
