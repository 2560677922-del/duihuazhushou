import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import router from './router';
import './styles/main.css';
/* Material 3 设计令牌：新界面用 --md-*，不改动原有样式，可随时关掉 */
import './styles/material.css';

/* 按键音效：WebAudio 现场合成，不打包音频文件。挂一次全局代理，新按钮自动有音效 */
import { installSoundFeedback } from './lib/sound';
import { registerSW } from 'virtual:pwa-register';
import { store } from './lib/storage';
import { persist } from './lib/persist';
import { useAppearanceStore } from './stores/appearance';
import { useDnaStore } from './stores/characterDna';

const app = createApp(App);
app.use(createPinia());
app.use(router);

async function boot() {
  /*
   * 申请"持久存储"配额。
   *
   * 手机上这一步很关键：默认情况下浏览器/WebView 认为这些数据是"可回收的
   * 缓存"，一旦设备存储紧张就会**悄悄清掉** —— 表现就是"打开 App 数据没了"。
   * 调 persist() 之后，系统会把它当成用户数据而不是缓存，不再随便回收。
   *
   * 必须在写入前调用（越早越好）；失败也不影响使用，只是保护弱一些。
   */
  /*
   * ★ 配额申请改成后台，不再 await。
   *
   * navigator.storage.persist() / estimate() 在部分 Android WebView 上
   * 会等很久，而它只决定"数据会不会被系统回收"，跟界面能不能画出来
   * 毫无关系。它原本排在 app.mount() 之前，是首屏白屏的一段白等。
   */
  void (async () => {
    try {
      const s = navigator.storage as StorageManager | undefined;
      if (s?.persist) {
        const already = s.persisted ? await s.persisted() : false;
        if (!already) {
          const ok = await s.persist();
          console.log(ok ? '[存储] 已获得持久存储授权，数据不会被自动清理' : '[存储] 未获得持久存储授权');
        }
        if (s.estimate) {
          const est = await s.estimate();
          if (est.quota) {
            console.log(
              `[存储] 已用 ${((est.usage ?? 0) / 1024 / 1024).toFixed(1)} MB / ` +
                `可用 ${(est.quota / 1024 / 1024).toFixed(0)} MB`,
            );
          }
        }
      }
    } catch (e) {
      console.warn('[存储] 申请持久存储失败（不影响使用）：', e);
    }
  })();

  // 挂载前先把本地落盘数据读回浏览器缓存。
  // 必须早于任何 store.load()，否则会拿到浏览器里的旧值。
  // 读不到就按浏览器存储继续跑，不阻塞启动。
  try {
    const r = await store.hydrate();
    if (r.adoptedFrom) {
      // 换了端口/地址访问时，数据会自动从旧来源搬过来，这里留个记录
      console.log(
        `[本地持久化] 当前访问地址没有数据，已自动从「${r.adoptedFrom}」搬入 ${r.keys} 项`,
      );
    } else if (r.fromFile) {
      console.log(`[本地持久化] 已从本地文件恢复 ${r.keys} 项数据`);
    } else if (r.adoptedBrowser) {
      console.log(
        `[本地持久化] 该访问地址还没有落盘记录，已把浏览器里现有的 ${r.keys} 项配置写入文件`,
      );
    }
  } catch (e) {
    console.warn('[本地持久化] 读取本地文件失败，改用浏览器存储：', e);
  }

  app.mount('#app');

  /* 按键音效：全局代理，一次挂上，以后新加的按钮自动有声音 */
  installSoundFeedback();

  // 原生聊天桥接：只在 App 的 WebView 里激活（window.AndroidNative 存在时
  // 才会出现"原生聊天"入口按钮），网页版 / 普通浏览器里完全无感。
  // 放进 .then 之后、等 pinia 真正就绪，桥里读取 store 才不会拿到空值。
  try {
    import('./bridge/nativeBridge').then((m) => m.installNativeBridge()).catch((e) => {
      console.warn('[原生桥接] 加载失败（不影响网页使用）：', e);
    });
  } catch (e) {
    console.warn('[原生桥接] 初始化失败（不影响网页使用）：', e);
  }

  // 外观（背景/主色）单独加载并立即应用，避免先闪一下默认配色
  try {
    await useAppearanceStore().load();
  } catch (e) {
    console.warn('[外观] 加载失败，使用默认配色：', e);
  }

  // 角色 DNA 提前加载：写作页的快捷条与自动配图都要用
  try {
    await useDnaStore().load();
  } catch (e) {
    console.warn('[角色DNA] 加载失败：', e);
  }

  // 定时检查落盘文件是否被别的窗口/设备改动过（例如手机上改了配置）。
  // 只在窗口可见时检查，并交给状态条提示，不再用阻塞式 confirm。
  setInterval(
    () => {
      if (document.visibilityState !== 'visible') return;
      void persist.poll();
    },
    8000,
  );

  // 关闭/切走页面前把待写内容落盘，避免刚改完就关掉丢配置
  window.addEventListener('beforeunload', () => {
    void persist.flush();
  });
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') void persist.flush();
  });
}

void boot();

/*
 * 注册 PWA Service Worker（自动更新模式）。
 *
 * 为什么这里要额外做几件事：手机上界面是**被 Service Worker 缓存之后**渲染的，
 * 于是很容易出现"代码改了、手机打开还是老的"——用户会以为功能没做/没修好。
 * （实际踩过：手机上点"重新生图"仍然弹旧面板，其实是吃了缓存的旧 JS。）
 *
 * 对策：
 *   1. 每次回到前台都查一次有没有新版本
 *   2. 暴露一个手动入口（设置页的「检查更新」），让用户能强制刷
 */
const updateSW = registerSW({
  immediate: true,
  onOfflineReady() {
    console.log('[PWA] 应用已可离线使用');
  },
  onNeedRefresh() {
    // autoUpdate 模式下一般轮不到这里；留着兜底
    if (confirm('有新版本，是否立刻刷新？')) void updateSW();
  },
  onOfflineError() {
    console.warn('[PWA] 离线缓存注册失败');
  },
});

/** 手动检查更新（设置页按钮调用）。带防抖，避免连点。 */
let checking = false;
export async function checkForUpdate(): Promise<'updated' | 'latest' | 'unsupported'> {
  if (checking) return 'latest';
  checking = true;
  try {
    if (!('serviceWorker' in navigator)) return 'unsupported';
    const reg = await navigator.serviceWorker.getRegistration();
    if (!reg) return 'unsupported';
    await reg.update();
    // 有新 SW 在等待 → 让它接管并刷新
    if (reg.waiting) {
      reg.waiting.postMessage({ type: 'SKIP_WAITING' });
      void updateSW();
      return 'updated';
    }
    return 'latest';
  } catch {
    return 'unsupported';
  } finally {
    setTimeout(() => (checking = false), 3000);
  }
}

/** 回到前台时顺手查一次更新（手机切回来就能拿到新版） */
if (typeof document !== 'undefined') {
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState !== 'visible') return;
    // 上一次检查太近就跳过，避免频繁打网络
    const now = Date.now();
    const last = Number(sessionStorage.getItem('sw-last-check') || '0');
    if (now - last < 10 * 60 * 1000) return;
    sessionStorage.setItem('sw-last-check', String(now));
    void navigator.serviceWorker?.getRegistration().then((r) => r?.update()).catch(() => {});
  });
}
