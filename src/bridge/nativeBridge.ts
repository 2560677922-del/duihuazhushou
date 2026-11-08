// 原生聊天桥接（增量式、非侵入）。
//
// 设计原则：只在"运行在 App 的 WebView 里"时才激活（window.AndroidNative 存在）。
// 普通浏览器 / 网页版里完全不出现，不影响 SillyTavern 兼容与网页体验。
//
// 职责：
//   1. window.__dhpNative.getSnapshot() —— 把当前会话（含配图元信息）序列化成
//      一个对象。**不含**正则/变量/宏上下文，也不含图片 base64，见下方注释。
//   2. 注入一个浮动入口按钮，点一下打开原生聊天界面（带 sessionId）。
//   3. 原生端要回传消息时，直接拿 MainActivity 里的 WebView 调
//      window.__dhpApp.sendUserMessage(text)（在 Adventure.vue 里挂），
//      复用页面既有 sendAction，生成 / 草稿 / 落库 / 自动配图都走同一条路。
import { useAdventureStore } from '../stores/adventure';
import type { ImageAttachment } from '../engine/imageMarker';

/**
 * 配图附件 → 轻量 DTO。
 *
 * ★ 刻意**不带 dataUrl**。
 *
 *   一张图 base64 动辄几 MB，一个有几十张图的会话，快照能到几十 MB。
 *   这个字符串要过三关：网页里 JSON.stringify、跨 JS 桥传输、原生主线程
 *   JSONObject 解析。前两关还好，第三关直接在 onCreate 里把主线程按住，
 *   第一帧画不出来 —— 用户看到的就是"点一下原生聊天就黑屏卡住"。
 *
 *   图片本体本来就已经落在本地文件里（迁移/生图都会写 localPath），
 *   原生按路径加载即可；没有本地路径时先不显示图，绝不让它拖死文字。
 */
function snapshotImages(images?: ImageAttachment[]): any[] {
  if (!images || !images.length) return [];
  return images.map((im) => ({
    id: im.id,
    description: im.description || '',
    markdownIndex: im.markdownIndex ?? 0,
  }));
}

/**
 * 当前会话 + 显示配置的快照（对象，不是字符串）。
 *
 * ★ 只带"会话本身"，不带显示配置（正则/变量/宏上下文）。
 *
 *   那些东西每次打开聊天都会重新算一遍，而且正则表可能几十 KB；
 *   把它们塞进快照，等于每开一次聊天就跨桥搬一次大对象。
 *   原生端自己通过 `__dhpGen.displayConfig(sessionId)` 拉，结果一样，
 *   但传输量小得多，也不会一次性全压在打开的那一瞬间。
 */
export function getSnapshot(): any {
  const adventure = useAdventureStore();

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
    title: session?.title || '',
    sceneDescription: session?.sceneDescription || '',
  };

  return { sessionId: session?.id || '', messages, config };
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
 * 打开原生聊天界面（统一入口）。
 *
 * ★ 为什么单独抽出来、并且单独传 sessionId：
 *
 *   原生端拿到 sessionId 就能直接读本地会话文件，不必解析快照 ——
 *   快照只是"本地还没有这份会话"时的兜底。以前所有入口都只塞一坨
 *   越来越大的 JSON，原生在 onCreate 里解析它，主线程被按住就黑屏。
 *
 *   现在：sessionId 走独立参数（原生零解析），快照只在兜底时用，
 *   而且已经瘦身（无 base64、无正则表）。
 */
function openNativeChatCurrent(): void {
  const adventure = useAdventureStore();
  const id = adventure.currentSessionId || '';
  const native = (window as any).AndroidNative;
  if (!native) return;
  let snap = '';
  try {
    snap = JSON.stringify(getSnapshot());
  } catch (e) {
    console.warn('[原生桥接] 序列化快照失败，改为只传 sessionId', e);
  }
  try {
    if (typeof native.openNativeChatWithId === 'function') {
      native.openNativeChatWithId(id, snap);
    } else {
      // 老版本 APK 没有新方法，退回旧签名（仍可用，只是要自己解析）
      native.openNativeChat(snap);
    }
  } catch (e) {
    console.warn('[原生桥接] 打开原生聊天失败', e);
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
  openNativeChatCurrent();
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
  btn.onclick = () => openNativeChatCurrent();
  document.body.appendChild(btn);
}

/**
 * 在 app 启动后调用（见 src/main.ts 与 src/native/main.ts）。
 *
 * @param opts.injectButton 是否注入「原生聊天」浮动按钮。
 *   网页版需要这个入口；引擎模式（APK）没有界面，传 false，
 *   否则会往一个不存在的 body 上挂按钮、还白跑 8 秒轮询。
 */
export function installNativeBridge(opts: { injectButton?: boolean } = {}): void {
  (window as any).__dhpNative = {
    getSnapshot,
    getSessions,
    openSession,
    openNativeChat: openNativeChatCurrent,
    isNativeAvailable,
  };
  if (opts.injectButton === false) return;
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
