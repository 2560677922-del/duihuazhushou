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
// 预设 1.8.8 起也进这里：它和世界书一样是"改一次全量、平时只读"的数据，
// 语义本身不用原生理解（prompt_order、settings 原样搬），只是换个落盘位置。
// 角色卡仍不在此列（图片库本来就是权威，网页那份是开聊时派生的缓存）。
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
      // version 2 = 多了 presets。读取端不按版本分支，只看字段在不在，
      // 所以 version 1 的旧快照照样能读（缺的字段不覆盖内存）。
      version: 2,
      worldBooks: a.worldBooks,
      currentWorldBookIds: a.currentWorldBookIds,
      regexGroups: a.regexGroups,
      currentRegexGroupIds: a.currentRegexGroupIds,
      presets: a.presets,
      currentPresetId: a.currentPresetId,
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
  const hasAny =
    Array.isArray(obj.worldBooks) ||
    Array.isArray(obj.regexGroups) ||
    Array.isArray(obj.presets);
  if (!hasAny) return false;

  /*
   * 只覆盖**快照里真的有**的字段。
   *
   * 1.8.7 存的快照没有 presets，而升级后 store 里还躺着老预设；
   * 要是这里无脑 `a.presets = obj.presets`，就变成 undefined，
   * 紧接着 purge 又把 app-data.json 清掉 —— 预设直接蒸发。
   */
  const a = useAssetsStore();
  if (Array.isArray(obj.worldBooks)) a.worldBooks = obj.worldBooks;
  if (Array.isArray(obj.regexGroups)) a.regexGroups = obj.regexGroups;
  if (Array.isArray(obj.currentWorldBookIds)) a.currentWorldBookIds = obj.currentWorldBookIds;
  if (Array.isArray(obj.currentRegexGroupIds)) a.currentRegexGroupIds = obj.currentRegexGroupIds;
  if (Array.isArray(obj.presets)) a.presets = obj.presets;
  if (typeof obj.currentPresetId === 'string') a.currentPresetId = obj.currentPresetId;

  managed = true;
  lastSent = raw;

  // 老快照没有预设 → 把内存里那份补写进原生，之后才好清旧副本
  if (!Array.isArray(obj.presets)) notifyNativeAssets();

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
      store.setItem('presets', []),
      store.setItem('currentPresetId', undefined),
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
