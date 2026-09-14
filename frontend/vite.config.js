import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Vite 构建配置
export default defineConfig({
  plugins: [vue()],
  server: {
    // 开发服务器端口
    port: 5173,
    proxy: {
      // 将 /api 请求代理到本地后端服务
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    // 构建产物输出到后端 Spring Boot 静态资源目录
    outDir: '../src/main/resources/static',
    // 构建前清空输出目录
    emptyOutDir: true
  }
})