import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { VitePWA } from 'vite-plugin-pwa';
import { fileURLToPath, URL } from 'node:url';
import { persistPlugin } from './vite-plugins/local-persist';

export default defineConfig({
  plugins: [
    vue(),
    // 开发服务器本地落盘：配置/资产存到 data/app-data.json，启动自动读回
    persistPlugin(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['pwa-192.png', 'apple-touch-icon.png'],
      manifest: {
        name: '冒险模拟器',
        short_name: '冒险模拟器',
        description: '兼容 SillyTavern 预设生态的 AI 写作与冒险游戏平台',
        theme_color: '#0d1018',
        background_color: '#0d1018',
        display: 'standalone',
        orientation: 'portrait',
        start_url: '/',
        icons: [
          { src: 'pwa-192.png', sizes: '192x192', type: 'image/png' },
          { src: 'pwa-512.png', sizes: '512x512', type: 'image/png' },
        ],
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,ico,png,svg}'],
        navigateFallback: '/index.html',
        // 本地落盘接口必须绕过 Service Worker，否则会被缓存住读到旧数据
        navigateFallbackDenylist: [/^\/__persist\//],
        runtimeCaching: [
          {
            urlPattern: /\/__persist\//,
            handler: 'NetworkOnly',
          },
          {
            urlPattern: /^https:\/\/fonts\.googleapis\.com\/.*/i,
            handler: 'CacheFirst',
            options: { cacheName: 'google-fonts', expiration: { maxEntries: 10 } },
          },
        ],
      },
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      'canvas': fileURLToPath(new URL('./src/shims/canvas.ts', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    host: true,
    /*
     * strictPort：端口被占用时**直接报错**，不要自动换到 5174。
     *
     * 为什么重要：这个应用按「访问地址」分开存数据，
     * 5173 和 5174 是两套完全独立的存储。Vite 默认会在 5173 被占用时
     * 悄悄换成 5174，于是用户在新端口看到的是空数据 ——
     * 表现就是"我的预设又丢了"（其实还在旧的来源里）。
     *
     * 现在宁可启动失败（会明确提示端口被占用），
     * 也不让访问地址静默漂移。加上客户端的自动找回逻辑，
     * 两边一起保证"打开就能看到自己的东西"。
     */
    strictPort: true,
    /*
     * 别监视 scripts/。
     *
     * 那里放的是测试脚本，跑测试时会被频繁创建/删除。
     * 编辑器（以及某些写文件的方式）会先写一个临时文件再改名，
     * Vite 的 watcher 正好撞上那个 tmp 文件还锁着，直接抛
     *   EBUSY: resource busy or locked, watch '...tmpdir\\xxx.mjs.tmp'
     * 整个开发服务器当场崩掉 —— 本会话踩过好几次，每次都要重启。
     *
     * 这些文件本来就不属于应用源码，不监视没有任何损失。
     */
    watch: {
      ignored: ['**/scripts/**', '**/data/**', '**/dbpg/**'],
    },
  },
});
