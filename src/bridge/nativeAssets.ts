// 世界书 / 正则组的原生权威存储（2026-11-09）。
//
// 背景：这两类资产以前存在网页 store 里，再由 persist 镜像到 filesDir/Data/app-data.json。
// 结果是「安卓侧看到的」和「真正生效的」隔着一层桥，删除要靠命令回包、未来做群聊
// 之类的原生功能还得反向解析网页存储。
//
// 现在改成：
//   · 原生文件 filesDir/data/assets/assets.json 是**唯一权威**；
//   · 引擎启动时用 AndroidNative.getNativeAssets() 同步拉取，填进 assets store；
//   · 之后引擎里的 store 只是工作副本，任何 save() 都会把快照回传原生
//     （AndroidNative.onAssetsChanged）；
//   · 一旦接管，网页持久化不再写这三个键，app-data.json 里那份会被置空，不留双份。
//
// 为什么不做「原生直接读写解析」：世界书/正则的归一化（entries 对象→数组、key 对象、
// ST 字段补齐）全在 engine/stImport.ts，那里是被真实预设验证过的。原生只做存储，
// 归一化仍由引擎完成，提示词组装看到的数据与改造前**逐字节一致**，风险最小。
//
// 预设**不在此列**（prompt_order 语义最重，继续走 app-data.json）；
// 角色卡也不在此列（文件库本来就是权威，网页那份是开聊时派生的）。
import { store } from '../lib/storage';
import { persist } from '../lib/persist';
import { useAssetsStore } from '../stores/assets';

/** 接管后由 assets.save() 调用：把快照交给原生落盘。 */
let managed = false;
/** 上一份已回传的快照，避免重复写盘。 */
let lastSent = '';
/** 第一次接管时要把旧的浏览器/文件副本清掉，只做一次。 */
let purged = false;

export function nativeManaged(): boolean {
  return managed;
}

/** 当前 store 里由原生托管的字段（世界书 + 正则组 + 各自的启用项）。 */
export function snapshotNativeAssets(): string {
  const a = useAssetsStore();
  try {
    return JSON.stringify({
      version: 1,
      worldBooks: a.worldBooks,
      currentWorldBookIds: a.currentWorldBookIds,
      regexGroups: a.regexGroups,
      currentRegexGroupIds: a.currentRegexGroupIds,
    });
  } catch (e) {
    console.warn('[原生资产] 快照序列化失败', e);
    return '';
  }
}

/** 回传给原生（assets.save() 内部调用，别在别处重复调）。 */
export function notifyNativeAssets(): void {
  if (!managed) return;
  const json = snapshotNativeAssets();
  if (!json || json === lastSent) return;
  lastSent = json;
  try {
    const native = (window as any).AndroidNative;
    if (native && typeof native.onAssetsChanged === 'function') native.onAssetsChanged(json);
  } catch (e) {
    console.warn('[原生资产] 回传失败', e);
  }
}

/**
 * 用原生快照覆盖 store。
 *
 * 只在快照里**至少有一个**托管字段时才接管 —— 空对象 "{}" 说明原生还没有数据
 * （首次升级），这时应该反过来把 store 现有数据交给原生，而不是把 store 清空。
 */
export async function adoptNativeAssets(raw: string): Promise<boolean> {
  let obj: any;
  try {
    obj = JSON.parse(raw || '{}');
  } catch {
    return false;
  }
  if (!obj || typeof obj !== 'object') return false;
  if (!Array.isArray(obj.worldBooks) && !Array.isArray(obj.regexGroups)) return false;

  const a = useAssetsStore();
  if (Array.isArray(obj.worldBooks)) a.worldBooks = obj.worldBooks;
  if (Array.isArray(obj.regexGroups)) a.regexGroups = obj.regexGroups;
  if (Array.isArray(obj.currentWorldBookIds)) a.currentWorldBookIds = obj.currentWorldBookIds;
  if (Array.isArray(obj.currentRegexGroupIds)) a.currentRegexGroupIds = obj.currentRegexGroupIds;

  managed = true;
  lastSent = raw;
  await purgeOldCopies();
  return true;
}

/**
 * 把旧的持久化副本置空。
 *
 * 不删键（persist 的文件合并只增不删），而是写空数组：app-data.json 里原先
 * 几十上百 KB 的世界书内容会被一次写盘缩掉，之后也不会再被写回。
 */
async function purgeOldCopies(): Promise<void> {
  if (purged) return;
  purged = true;
  try {
    await Promise.all([
      store.setItem('worldBooks', []),
      store.setItem('regexGroups', []),
      store.setItem('currentWorldBookIds', []),
      store.setItem('currentRegexGroupIds', []),
    ]);
    await persist.flush();
  } catch (e) {
    console.warn('[原生资产] 清理旧副本失败（不影响使用）', e);
  }
}

/**
 * 启动时装配。必须在 assets.load() 之后调用：
 *   · 原生有数据 → 覆盖 store（接管）；
 *   · 原生是空的 → 把 store 现有数据交出去（首次升级的迁移）。
 */
export function installNativeAssets(): void {
  const native = (window as any).AndroidNative;
  if (!native || typeof native.getNativeAssets !== 'function') {
    // 浏览器里跑（没有原生桥）：保持旧行为，什么都不做
    return;
  }

  let raw = '';
  try {
    raw = String(native.getNativeAssets() || '');
  } catch (e) {
    console.warn('[原生资产] 读取原生快照失败', e);
  }

  if (raw && raw.trim() !== '' && raw.trim() !== '{}') {
    void adoptNativeAssets(raw).then((ok) => {
      if (!ok) handOverToNative();
    });
  } else {
    handOverToNative();
  }

  // 调试入口：控制台里 __dhpAssets.take() 能看当前快照
  (window as any).__dhpAssets = {
    take: snapshotNativeAssets,
    managed: nativeManaged,
  };
}

/** 首次升级：把 store 现有数据交给原生，然后进入接管模式。 */
function handOverToNative(): void {
  const json = snapshotNativeAssets();
  if (!json) return;
  managed = true;
  lastSent = json;
  try {
    const native = (window as any).AndroidNative;
    if (native && typeof native.onAssetsChanged === 'function') native.onAssetsChanged(json);
  } catch (e) {
    console.warn('[原生资产] 首次移交失败', e);
  }
  // 移交成功后清掉旧副本，避免两边各一份
  void purgeOldCopies();
}
