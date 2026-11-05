import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: fileURLToPath(new URL('../main/resources/META-INF/resources', import.meta.url)),
    emptyOutDir: true
  },
  server: {
    proxy: {
      '/api': {
        target: process.env.METEOR_CONSOLE_API || 'http://127.0.0.1:8080',
        // 保留浏览器访问的 Host，后端据此校验登录与写入请求的 Origin。
        changeOrigin: false
      }
    }
  }
});
