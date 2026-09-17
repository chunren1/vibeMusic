import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import compression from 'vite-plugin-compression'
import { VitePWA } from 'vite-plugin-pwa'
import { brotliCompress } from 'node:zlib'
import { promisify } from 'node:util'
import { readdir, readFile, stat, writeFile } from 'node:fs/promises'
import { join } from 'node:path'

const brotli = promisify(brotliCompress)
const COMPRESS_RE = /\.(js|mjs|css|html)$/i

async function collectFiles(dir) {
  const files = []
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name)
    if (entry.isDirectory()) files.push(...await collectFiles(p))
    else if (entry.isFile() && COMPRESS_RE.test(entry.name)) files.push(p)
  }
  return files
}

// vite-plugin-compression 单实例限制（共享 mtimeCache，双实例时后者全部跳过），
// brotli 预压缩用内联插件补齐，与 gzip 走同一份文件清单
function brotliCompressionPlugin() {
  let outputPath
  return {
    name: 'vite:brotli-compression',
    apply: 'build',
    enforce: 'post',
    configResolved(config) {
      outputPath = config.build.outDir
    },
    async closeBundle() {
      await Promise.all((await collectFiles(outputPath)).map(async file => {
        if ((await stat(file)).size < 1024) return
        const compressed = await brotli(await readFile(file))
        await writeFile(`${file}.br`, compressed)
      }))
    },
  }
}

export default defineConfig({
  plugins: [
    vue(),
    // 生产构建预压缩：gzip（插件）+ brotli（内联插件，见 brotliCompressionPlugin），
    // nginx 直接伺服 .gz/.br 免服务端压缩开销
    compression({
      algorithm: 'gzip',
      threshold: 1024,
    }),
    brotliCompressionPlugin(),
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
        chunkSizeWarningLimit: 500,
        manualChunks(id) {
          if (!id.includes('node_modules')) return
          if (id.includes('@sentry')) return 'sentry'
          if (id.includes('vue') || id.includes('@vue')) return 'vue-core'
          if (id.includes('pinia')) return 'pinia'
          if (id.includes('axios')) return 'axios'
          return 'vendor'
        },
      },
    },
    reportCompressedSize: true,
    sourcemap: 'hidden',  // 生成 sourcemap 供 Sentry 上传，但产物不含 //# sourceMappingURL 防泄露
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
