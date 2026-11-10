import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import compression from 'vite-plugin-compression'
import { VitePWA } from 'vite-plugin-pwa'
import { execSync } from 'node:child_process'


// 构建版本号：优先 CI 的 GITHUB_SHA（短 sha），其次本地 git short sha，取不到兜底 'dev'
// 注入 import.meta.env.VITE_APP_RELEASE，随客户端错误上报一起上送，便于按版本定位问题
function resolveRelease() {
  const sha = process.env.GITHUB_SHA
  if (sha) return sha.slice(0, 7)
  try {
    const out = execSync('git rev-parse --short HEAD', { stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim()
    return out || 'dev'
  } catch {
    return 'dev'
  }
}

// vite-plugin-compression 单实例限制（共享 mtimeCache，双实例时后者全部跳过）

export default defineConfig({
  // 客户端错误上报的版本号（第一方上报，替代 Sentry）
  define: {
    'import.meta.env.VITE_APP_RELEASE': JSON.stringify(resolveRelease()),
  },
  plugins: [
    vue(),
    // 生产构建预压缩：只出 .gz（nginx gzip_static 直出）。Brotli 不做：
    // 源站 nginx 无 brotli 模块（Ubuntu 源无现成包），.br 生成了也伺服不了，
    // 用户侧的 br 由 Cloudflare 边缘对 HTML 动态转压承担。
    compression({
      algorithm: 'gzip',
      threshold: 1024,
    }),
    // PWA：以 public/sw.js 为模板，workbox 构建期注入 hash 资源 precache 清单（自动缓存版本管理）
    VitePWA({
      strategies: 'injectManifest',
      srcDir: 'public',
      filename: 'sw.js',
      manifest: false,        // 已有 public/manifest.json，不自动生成
      injectRegister: false,  // 由 src/main.js 手动注册 /sw.js（配合严格 CSP，避免内联脚本）
      registerType: 'autoUpdate',
      injectManifest: {
        globPatterns: ['**/*.{js,css,html,ico,png,svg,webmanifest}'],
        globIgnores: ['**/*.map'],
        maximumFileSizeToCacheInBytes: 3 * 1024 * 1024,
      },
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    target: 'es2020',
    cssCodeSplit: true,
    assetsInlineLimit: 8192,
    // 500KB 警告线（原先误写在 rollupOptions.output 下，Vite 不识别，等于没设）
    chunkSizeWarningLimit: 500,
    minify: 'terser',
    terserOptions: {
      compress: {
        drop_console: ['log', 'info', 'debug'],  // 仅保留 warn/error，生产环境防信息泄漏
        drop_debugger: true,
        passes: 2,
      },
      mangle: { safari10: true },
      format: { comments: false },
    },
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return
          if (id.includes('vue') || id.includes('@vue')) return 'vue-core'
          if (id.includes('pinia')) return 'pinia'
          if (id.includes('axios')) return 'axios'
          return 'vendor'
        },
      },
    },
    reportCompressedSize: true,
    // 生产不生成 sourcemap。
    //
    // 2026-10-26 审计更正：此前是 sourcemap: 'hidden'，注释写「防源码泄露」，但那个判断是错的。
    // 'hidden' 只做一件事——不往 .js 末尾写 //# sourceMappingURL= 注释；
    // 它**不会**阻止 .map 文件被 emit 到 dist/assets/，而 dist/assets/ 是整目录部署的，
    // 于是 25 个 .map（1.6 MB，占 dist 57%）在公网可直接访问，且 sourcesContent 里是 .vue 原文：
    //   curl https://vibe.cyk666.top/assets/ChatView-C5KrxvEu.js.map -> 200 / 18,592 B
    // 本项目生产环境明确不引入第三方监控（src/main.js:19 注释），sourcemap **没有任何消费方**，
    // 属于纯负债。故改为 false。
    // 若将来接入 Sentry 等需要还原堆栈，不要改回 'hidden'，而应：保留 'hidden' + nginx 拦 `\.map$` 返回 404。
    sourcemap: false,
  },
  css: {
    devSourcemap: false,
  },
  server: {
    port: 5173,
    host: '0.0.0.0',
    allowedHosts: true,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/uploads': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
})
