// 角色卡「用它开聊」的唯一实现。
//
// 为什么单独抽出来：这段逻辑原本写在 Adventure.vue 里，原生角色库通过
// `window.__dhpApp.useCharacter(json)` 隔着 WebView 调一个**视图组件内部**
// 的函数 —— 一旦不加载界面（APK 现在只跑引擎），入口就断了。
//
// 它是业务而不是界面：去重导入、设为当前角色、建会话、拉起原生聊天。
// 放在 bridge 层之后，两个入口（网页版 / 引擎版）共用同一份，不会再漂。

import { useAdventureStore } from '../stores/adventure';
import { useAssetsStore } from '../stores/assets';

/** 从角色卡 JSON 里取名字：V2 卡在 data.name，V1 卡在顶层 name。 */
function cardName(raw: any): string {
  return String(raw?.data?.name ?? raw?.name ?? '').trim();
}

/**
 * 把一张角色卡「只导入不进对话」——给原生选卡列表用。
 *
 * 背景：原生角色库（文件）与网页 assets（IndexedDB）是两份存储，平时只在
 * 「用它开聊」时合并一次。用户在卡库里建好卡，选卡列表却还是空的，
 * 根因就是卡没进 assets。这里补一个纯导入入口：
 *
 * - 同名复用，不覆盖（用户改过的卡不能被旧文件顶掉）；
 * - 不设当前角色、不建会话、不跳页面 —— 选哪个由原生写会话绑定；
 * - 同步返回 `{"ok":true,"uid":…,"name":…}`，因为原生要立刻拿 uid。
 *   落盘本身是异步的（`void save()`），不影响这次会话的生成。
 */
export function ensureCharacterToLibrary(rawJson: string): string {
  try {
    const raw = JSON.parse(rawJson || '{}');
    const obj = useAssetsStore().ensureCharacter(raw);
    return JSON.stringify({ ok: true, uid: obj?._uid || '', name: obj?.name || '' });
  } catch (e: any) {
    console.warn('[角色卡] 导入资产库失败', e);
    return JSON.stringify({ ok: false, error: e?.message || String(e) });
  }
}

/**
 * 用一张角色卡开新对话。
 *
 * @param rawJson 标准 V2 JSON 字符串（原生只负责读文件与解析，建档归 web store）
 *
 * 只做「确保角色在库里 + 选中 + 建会话 + 打开原生聊天」，
 * 不碰生成、不碰界面状态 —— 那两件事分别归 generateService 与原生聊天页。
 */
export async function openCharacterChat(rawJson: string): Promise<void> {
  let raw: any;
  try {
    raw = JSON.parse(rawJson || '{}');
  } catch (e) {
    console.warn('[角色开聊] 卡 JSON 解析失败', e);
    return;
  }

  const assets = useAssetsStore();
  const name = cardName(raw);

  /*
   * 按名字去重。
   *
   * 原生角色库每次点「用它开聊」都会把卡发过来；直接 import 会给同一张卡
   * 反复建档，用几次角色库就变成一列同名副本。同名直接复用已有条目
   * （卡本身不合并、不覆盖 —— 用户改过的卡不能被旧文件顶掉）。
   */
  let obj = name ? (assets.characters as any[]).find((c: any) => c?.name === name) : undefined;
  if (!obj) obj = await assets.importCharacter(raw);

  assets.currentCharacterId = obj._uid;
  await assets.save();

  const adventure = useAdventureStore();
  await adventure.addSession(obj.name || '新的冒险', '');

  /*
   * 走统一的“打开原生聊天”入口，不要凭空调 AndroidNative.openNativeChat('')。
   * 以前传空串，原生解析不出 sessionId 就直接 finish() ——
   * 用户看到的就是「点了角色卡，闪一下又回来了」。
   */
  const bridge = (window as any).__dhpNative;
  if (bridge && typeof bridge.openNativeChat === 'function') {
    bridge.openNativeChat();
  }
}
