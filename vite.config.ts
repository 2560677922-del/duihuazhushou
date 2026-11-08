import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { VitePWA } from 'vite-plugin-pwa';
import { fileURLToPath, URL } from 'node:url';
import fs from 'node:fs';
import { persistPlugin } from './vite-plugins/local-persist';

/**
 * 引擎构建只带「原生真的会读」的静态资源。
 *
 * public/ 约 3.2MB：tags 是原生标签库（TagLibrary.kt 直接读 assets/www/tags），
 * 必须留；bg/ 与 splash 只有网页版界面用，原生背景走自己的 res，带了白占安装包。
 */
function copyNativeStatic() {
  return {
    name: 'copy-native-static',
    apply: 'build' as const,
    closeBundle() {
      const from = fileURLToPath(new URL('./public/tags', import.meta.url));
      const to = fileURLToPath(new URL('./dist-native/tags', import.meta.url));
      fs.cpSync(from, to, { recursive: true });
    },
  };
}

export default defineConfig(({ mode }) => {
  /*
   * 两套构建，共用同一份 src/：
   *
   *   npm run build          → dist/         网页版（index.html，完整 UI）
   *   npm run build:native   → dist-native/  APK 引擎（native.html，无 UI）
   *
   * APK 只需要引擎，不需要 Vue 应用。把界面排除在构建之外（而不是运行时
   * 判断），低端机冷启动就不用解析/执行整套 views —— 这是「裁 web UI」的落点。
   */
  const nativeBuild = mode === 'native';

  return {
    plugins: nativeBuild
      ? [copyNativeStatic()]
      : [
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

    /*
     * APK 构建只出 native.html 一个入口。
     * 单入口 + 无路由 → 产物就是「入口 js + 引擎依赖」，没有界面分包。
     */
    build: nativeBuild
      ? {
          outDir: 'dist-native',
          emptyOutDir: true,
          target: 'es2020',
          rollupOptions: {
            input: { native: fileURLToPath(new URL('./native.html', import.meta.url)) },
          },
        }
      : {},

    /*
     * 引擎构建关掉 public 自动拷贝：那 3.2MB 里 1.1MB 是网页背景图、128KB 是 PWA 图标，
     * 原生一个都不读。只由 copyNativeStatic 补回 tags/。
     */
    publicDir: nativeBuild ? false : 'public',

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
  };
});
