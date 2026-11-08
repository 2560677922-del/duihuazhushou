import { defineConfig } from 'vite';
import { fileURLToPath, URL } from 'node:url';
import { persistPlugin } from './vite-plugins/local-persist';

/**
 * 只构建一个产物：APK 用的**引擎包**（`native.html`）。
 *
 * 网页界面（Vue + router + views）已经移除 —— 项目早就全面转原生，
 * 那套界面留在仓库里只会造成「网页改了、App 没改」的漂移。
 * 引擎（store / engine / bridge）仍然是网页技术栈，因为它承担
 * SillyTavern 的角色卡、世界书、预设、正则兼容，这部分没法用 Kotlin 重写。
 *
 * 需要改界面 → 改 `dbpg/app/src/main` 的原生代码；
 * 需要调试引擎 → `npm run dev`，浏览器打开 `/native.html`（无界面，只有 console）。
 */
export default defineConfig({
  plugins: [persistPlugin()],

  build: {
    outDir: 'dist',
    emptyOutDir: true,
    target: 'es2020',
    rollupOptions: {
      input: { native: fileURLToPath(new URL('./native.html', import.meta.url)) },
    },
  },

  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      // nekoai-js 在浏览器里也 import 'canvas'（Node 才有的模块），
      // 用空实现顶掉，否则 Rollup 直接构建失败。
      canvas: fileURLToPath(new URL('./src/shims/canvas.ts', import.meta.url)),
    },
  },

  server: {
    port: 5173,
    host: true,
    /*
     * strictPort：端口被占用时**直接报错**，不要自动换到 5174。
     *
     * 这个应用按「访问地址」分开存数据，5173 和 5174 是两套完全独立的存储。
     * 静默换端口会让人看到空数据 —— 表现就是"我的预设又丢了"。
     */
    strictPort: true,
    open: '/native.html',
    /*
     * 别监视 scripts/：那里是测试脚本，频繁创建/删除，Vite 的 watcher
     * 会撞上临时文件抛 EBUSY 把开发服务器搞崩。
     */
    watch: {
      ignored: ['**/scripts/**', '**/data/**', '**/dbpg/**'],
    },
  },
});
