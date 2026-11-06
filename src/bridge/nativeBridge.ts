// 原生聊天桥接（增量式、非侵入）。
//
// 设计原则：只在"运行在 App 的 WebView 里"时才激活（window.AndroidNative 存在）。
// 普通浏览器 / 网页版里完全不出现，不影响 SillyTavern 兼容与网页体验。
//
// 职责：
//   1. window.__dhpNative.getSnapshot() —— 把当前会话（含配图）与"显示转换"
//      所需的全部配置（正则脚本 / 变量表 / 宏上下文 / 思维链开关）序列化成
//      一个对象。原生端用 :stcore 复刻网页的 displayText 来渲染，两边一致。
//   2. 注入一个浮动入口按钮，点一下把快照交给原生端打开原生聊天界面。
//   3. 原生端要回传消息时，直接拿 MainActivity 里的 WebView 调
//      window.__dhpApp.sendUserMessage(text)（在 Adventure.vue 里挂），
//      复用页面既有 sendAction，生成 / 草稿 / 落库 / 自动配图都走同一条路。
import { useAdventureStore } from '../stores/adventure';
import { useVariablesStore } from '../stores/variables';
import { useAssetsStore } from '../stores/assets';
import { useStorageSettingsStore } from '../stores/storageSettings';
import { buildMacroContext } from '../composables/macroContext';
import type { ImageAttachment } from '../engine/imageMarker';

/** 配图附件 → 轻量 DTO（dataUrl 直接带过去，原生端解码显示）。 */
function snapshotImages(images?: ImageAttachment[]): any[] {
  if (!images || !images.length) return [];
  return images.map((im) => ({
    id: im.id,
    description: im.description || '',
    markdownIndex: im.markdownIndex ?? 0,
    dataUrl: im.dataUrl || '',
  }));
}

/**
 * 当前会话 + 显示配置的快照（对象，不是字符串）。
 * 原生端既可 JSON.stringify 后通过 AndroidNative.openNativeChat 带过去，
 * 也能被 evaluateJavascript 直接当返回值序列化。
 */
export function getSnapshot(): any {
  const adventure = useAdventureStore();
  const variables = useVariablesStore();
  const assets = useAssetsStore();
  const storage = useStorageSettingsStore();

  const session = adventure.currentSession;
  const messages: any[] = [];
  if (session) {
    for (const act of session.actions) {
      if (act.user && act.user.trim()) {
        messages.push({ role: 'user', content: act.user, images: snapshotImages(act.images) });
      }
      if (act.ai && act.ai.trim()) {
        messages.push({ role: 'assistant', content: act.ai, images: snapshotImages(act.images) });
      }
    }
    // 正在生成中的草稿：作为一条"流式"AI 消息追加到末尾，原生端显示打字效果
    if (session.draft && session.draft.ai && session.draft.ai.trim()) {
      messages.push({
        role: 'assistant',
        content: session.draft.ai,
        images: [],
        streaming: true,
        user: session.draft.user,
      });
    }
  }

  const config = {
    hideReasoning: !!storage.hideReasoning,
    reasoningCutMarker: storage.reasoningCutMarker || '',
    scripts: assets.getCurrentRegexScripts ? assets.getCurrentRegexScripts() : [],
    variables: variables.snapshot ? variables.snapshot() : { session: {}, global: {} },
    macroContext: buildMacroContext(),
    title: session?.title || '',
    sceneDescription: session?.sceneDescription || '',
  };

  return { messages, config };
}

function isNativeAvailable(): boolean {
  return typeof (window as any).AndroidNative !== 'undefined';
}

/**
 * 会话列表（返回 **字符串**）。
 *
 * 为什么不是对象：Android 的 evaluateJavascript 只能把返回值序列化成 JSON，
 * 直接返回对象在原生端会变成 "[object Object]"。这里统一 stringify，
 * 原生端用 JSONTokener 解一层就是干净的 JSON。
 */
function getSessions(): string {
  const adventure = useAdventureStore();
  try {
    return JSON.stringify(
      adventure.sessions.map((s) => ({
        id: s.id,
        title: s.title || '',
        count: s.actions?.length || 0,
        updatedAt: s.updatedAt || s.createdAt || 0,
        current: s.id === adventure.currentSessionId,
      })),
    );
  } catch (e) {
    console.warn('[原生桥接] 读取会话列表失败', e);
    return '[]';
  }
}

/**
 * 切到指定会话，并让网页拉起原生聊天。
 *
 * 只做「切换 + 打开」，不新建、不删除 —— 会话的增删仍归网页管，
 * 原生列表是只读视图，避免两边各写一份导致状态打架。
 */
async function openSession(id: string): Promise<void> {
  const adventure = useAdventureStore();
  if (!id || !adventure.sessions.some((s) => s.id === id)) return;
  await adventure.switchSession(id);
  try {
    (window as any).AndroidNative?.openNativeChat('');
  } catch (e) {
    console.warn('[原生桥接] 打开原生聊天失败', e);
  }
}

let buttonInjected = false;
function injectEntryButton(): void {
  if (buttonInjected || !isNativeAvailable()) return;
  buttonInjected = true;
  const btn = document.createElement('button');
  btn.id = 'dhp-native-chat-entry';
  btn.textContent = '原生聊天';
  Object.assign(btn.style, {
    position: 'fixed',
    right: '14px',
    bottom: 'calc(14px + env(safe-area-inset-bottom, 0px))',
    zIndex: '2147483646',
    padding: '10px 16px',
    borderRadius: '22px',
    border: 'none',
    background: 'rgba(110,168,254,0.92)',
    color: '#0d1018',
    fontSize: '14px',
    fontWeight: '700',
    boxShadow: '0 6px 18px rgba(0,0,0,.35)',
    cursor: 'pointer',
  } as Partial<CSSStyleDeclaration>);
  btn.onclick = () => {
    try {
      (window as any).AndroidNative.openNativeChat(JSON.stringify(getSnapshot()));
    } catch (e) {
      console.warn('[原生桥接] 打开原生聊天失败', e);
    }
  };
  document.body.appendChild(btn);
}

/** 在 app 启动后调用（见 main.ts）。 */
export function installNativeBridge(): void {
  (window as any).__dhpNative = { getSnapshot, getSessions, openSession, isNativeAvailable };
  // WebView 注入 AndroidNative 略晚于页面脚本执行，轮询一小会儿确保按钮出现
  let tries = 0;
  const timer = setInterval(() => {
    tries++;
    if (isNativeAvailable()) {
      injectEntryButton();
      clearInterval(timer);
    } else if (tries > 40) {
      clearInterval(timer);
    }
  }, 200);
}
