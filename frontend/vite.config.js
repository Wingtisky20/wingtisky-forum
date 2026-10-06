import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],

  server: {
    port: 5173,

    // 开发时把 /api 转发到后端，**前后端在浏览器看来是同源的**。
    //
    // 为什么不直接在后端开 CORS：开了之后开发期是一种跨域配置、
    // 上线后通常又变成同源部署——**两套东西**，而真正出问题（凭证不生效、
    // 预检请求被挡）往往只在其中一种环境下复现。
    // 用代理，开发期就复现了"同源"，这个问题从一开始就不存在。
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
