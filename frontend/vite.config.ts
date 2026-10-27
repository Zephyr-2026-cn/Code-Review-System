import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发代理说明：
//   前端统一请求 /api/**，由 Vite 转发到后端，浏览器视角同源 —— 这样
//   HttpOnly Cookie（刷新令牌）无需处理跨域凭证问题，生产环境由 Nginx 做同样的事。
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: process.env.VITE_PROXY_TARGET ?? 'http://localhost:8080',
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
        // 手动分包：Element Plus 单独成块，业务代码变更时不影响其缓存
        manualChunks: {
          'element-plus': ['element-plus'],
          vue: ['vue', 'vue-router', 'pinia']
        }
      }
    }
  }
})
