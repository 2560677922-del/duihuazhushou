// APK 专用入口：**只跑引擎，不跑界面**。
//
// 与 src/main.ts 的区别：
//   - 不 createApp / 不挂 router / 不 import 任何 .vue 或样式
//   - 不注册 PWA（APK 里 Service Worker 只会缓存旧资源，反而添乱）
//   - 不开 8 秒一次的落盘轮询（原生界面看不到文件变动提示，纯耗电）
//
// 保留的能力：store 读盘、变量/资产/会话加载、三条桥（生成 / 迁移 / 原生能力）。
// 原生所有页面都通过这些桥拿数据、发请求，所以这里少一个装配步骤
// 都会表现为「点开是空的 / 生成没反应」——顺序不能动，注释里都标了原因。

import { createPinia, setActivePinia } from 'pinia';
import { store } from '../lib/storage';
import { persist } from '../lib/persist';
import { useAssetsStore } from '../stores/assets';
import { useAdventureStore } from '../stores/adventure';
import { useVariablesStore } from '../stores/variables';
import { useApiConfigStore } from '../stores/apiConfig';
import { installGenerateService } from '../bridge/generateService';
import { installDataExport } from '../bridge/dataExport';
import { installNativeBridge } from '../bridge/nativeBridge';
import { openCharacterChat } from '../bridge/characterEntry';

/*
 * 没有 Vue app，pinia 不会有人替我们 use()。
 * 必须显式激活，否则任何 useXxxStore() 都会抛
 * "getActivePinia() was called but there was no active Pinia"。
 */
setActivePinia(createPinia());

function setStatus(text: string, cls = '') {
  const el = document.getElementById('engine-status');
  if (!el) return;
  el.textContent = text;
  el.className = cls;
}

async function boot(): Promise<void> {
  /*
   * 1) 先把落盘数据读回内存。
   *
   * ★ 必须早于任何 store 的读取：storage.get 读的是内存缓存，
   *   hydrate 之前读到的全是默认值。这里顺序错了不会报错，
   *   只会表现为「预设列表空的 / 会话不见了」，很难查。
   */
  try {
    await store.hydrate();
  } catch (e) {
    console.warn('[引擎] 读取本地数据失败，按空数据启动：', e);
  }

  /*
   * 2) 加载引擎真正要用的几个 store。
   *
   * assets / adventure / variables 是异步读盘（文件或 IndexedDB），
   * 不 await 的话第一次生成会拿着空预设、「建会话」甚至会把旧会话覆盖掉。
   * apiConfig 是同步读的，但配置桥要用，顺手 load 一次。
   */
  await Promise.all([
    useAssetsStore().load(),
    useAdventureStore().load(),
    useVariablesStore().load(),
    useApiConfigStore().load(),
  ]);

  /*
   * 3) 装桥。顺序：先 __dhpNative（openCharacterChat 依赖它），
   *    再生成通道（__dhpGen），最后迁移通道。
   */
  installNativeBridge({ injectButton: false });
  installGenerateService();
  installDataExport();

  /*
   * 4) 原生角色库「用它开聊」的入口。
   *
   * 只挂这一个业务动作 —— 发消息、生成、落库全部走原生 ChatController，
   * 引擎不再需要 sendUserMessage 这类反向依赖界面的接口。
   */
  (window as any).__dhpApp = {
    useCharacter: (rawJson: string) => openCharacterChat(rawJson),
  };

  /*
   * 5) 关页/切后台前把待写内容落盘。
   *    只保留 flush，不做轮询 —— 原生界面不看外部改动提示。
   */
  window.addEventListener('beforeunload', () => {
    void persist.flush();
  });
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') void persist.flush();
  });

  setStatus('引擎就绪', 'ready');
  console.log('[引擎] 已就绪（无界面模式）');
}

boot().catch((e) => {
  console.error('[引擎] 启动失败', e);
  setStatus(`引擎启动失败：${e instanceof Error ? e.message : String(e)}`, 'error');
});

/*
 * 未捕获异常也显示出来。
 *
 * 引擎模式下页面是隐藏的，原生那边只会看到「点了没反应」；
 * 把错误留在页面上，至少用 chrome://inspect 连上能看到原因。
 */
window.addEventListener('error', (e) => {
  setStatus(`引擎异常：${e.message}`, 'error');
});
window.addEventListener('unhandledrejection', (e) => {
  const r: any = (e as PromiseRejectionEvent).reason;
  setStatus(`引擎异常：${r?.message ?? String(r)}`, 'error');
});
