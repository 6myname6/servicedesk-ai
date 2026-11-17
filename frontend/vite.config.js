import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * Vite 构建配置。
 *
 * <p>开发期通过代理把 /api 转发到本地后端 8080，保持与生产环境（nginx 反向代理）
 * 完全一致的请求路径，前端代码里不出现后端地址。</p>
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      // 后端 context-path 就是 /api，代理时保留前缀
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 1200,
    rollupOptions: {
      output: {
        // 把体积最大的两个依赖拆出来，nginx 可长期缓存，业务代码升级不影响它们
        manualChunks: {
          'vendor-element': ['element-plus', '@element-plus/icons-vue'],
          'vendor-vue': ['vue', 'vue-router', 'pinia', 'axios']
        }
      }
    }
  }
})
