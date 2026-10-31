// 图片资产库 —— 三个模块里"图片"这一件事的唯一存放处
// ---------------------------------------------------------------------------
// 为什么要有这个文件
// ---------------------------------------------------------------------------
// 改造前，图片散在三个地方：
//   1. 会话数据（adventureSessions / novelChapters）里内联一份 dataURL
//   2. 图片库（generatedImages）里又存一份
//   3. 标签页直出的结果再塞进图片库
//
// 后果：
//   · 同一张图存了两遍
//   · 会话数据会同步、会落盘、会进 GitHub 备份 —— 里面塞图片，
//     体积随图量一路涨，读取变慢
//   · 聊天页要加载图片库、标签页要加载会话，互相拖累
//
// 现在的分工：
//   · **图片本体只存在这里**（图片资产库）
//   · 会话里只留一个 `assetId` 引用，界面上照样显示、照样能点开
//   · 三个模块（主程序 / 图片库 / 生图工具）互不依赖，
//     需要图的时候拿 id 来这里取
//
// 存储位置：
//   · APK（Capacitor）→ 安卓原生文件
//   · 浏览器 → IndexedDB
//   两条路对上层是同一套接口，见 imageStorage.ts。

import { computed, ref } from 'vue';
import { imageFileStore } from './imageStorage';

/** 图片资产的一条记录（注意：**不含图片本体**，本体在 imageFileStore 里） */
export interface ImageAsset {
  /** 唯一 id。会话里存的就是它 */
  id: string;
  /** 生成这张图用的完整提示词（重新生图要原样再发一次，不能截断） */
  prompt: string;
  negativePrompt: string;
  provider: string;
  model: string;
  seed?: number;
  /** 画面描述 */
  description: string;
  /** 生成时间 */
  timestamp: number;
  /** 来源：手动生图 / 大模型自动配图 / 标签页直出 */
  origin: 'manual' | 'auto' | 'tagtool' | 'unknown';
  /** 属于哪本书/哪场冒险（用于图片库筛选） */
  scene: 'novel' | 'adventure';
  entityId: string;
}

let seq = 0;
function newId(): string {
  seq += 1;
  return `ia-${Date.now().toString(36)}-${seq.toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

/* ==========================================================================
   资产表（只是元数据，很小，可以整份放内存）
   ========================================================================== */

const assets = ref<ImageAsset[]>([]);
/** id → 可直接给 <img> 的地址。**不进全局**，谁要谁取，避免整份常驻 */
const urls = ref<Record<string, string>>({});
const loaded = ref(false);
let loadingPromise: Promise<void> | null = null;

const byId = computed(() => {
  const m = new Map<string, ImageAsset>();
  for (const a of assets.value) m.set(a.id, a);
  return m;
});

/**
 * 登记一张新图。
 *
 * 图片本体立刻写进图片存储，这里只留元数据。
 * 返回 assetId —— **会话里只存这个**，不存 dataUrl。
 */
export async function registerAsset(dataUrl: string, meta: Partial<ImageAsset>): Promise<string> {
  // ★ 先确保旧表已经在内存里。资产表是**整份**写回的（setIndex），
  //   如果调用方没先 load 过（内存里只有一张新图），这里就会用
  //   `[新图]` 覆盖掉用户整个索引 —— 以前"存进去的图下次打开就没了"
  //   就是这么来的。表只有元数据，很小，读一次不吃亏。
  await loadAssets();
  const id = newId();
  try {
    await imageFileStore.put(id, dataUrl);
  } catch (e) {
    console.warn('[图片资产] 写图片失败：', e);
    throw e;
  }
  const asset: ImageAsset = {
    id,
    prompt: meta.prompt || '',
    negativePrompt: meta.negativePrompt || '',
    provider: meta.provider || '',
    model: meta.model || '',
    seed: meta.seed,
    description: meta.description || '',
    timestamp: meta.timestamp ?? Date.now(),
    origin: meta.origin || 'unknown',
    scene: meta.scene || 'adventure',
    entityId: meta.entityId || '',
  };
  assets.value = [asset, ...assets.value];
  urls.value = { ...urls.value, [id]: dataUrl };
  return id;
}

/** 取一张图的地址（内存没有就去存储里取一次） */
export async function assetUrl(id: string): Promise<string | null> {
  if (!id) return null;
  const hit = urls.value[id];
  if (hit) return hit;
  try {
    const data = await imageFileStore.get(id);
    if (data) urls.value = { ...urls.value, [id]: data };
    return data;
  } catch {
    return null;
  }
}

/** 同步版：只在内存里找，不触发 IO。渲染时用它决定"要不要显示加载中" */
export function assetUrlSync(id: string): string | undefined {
  return id ? urls.value[id] : undefined;
}

/** 删除一张图（连本体一起删） */
export async function removeAsset(id: string): Promise<void> {
  assets.value = assets.value.filter((a) => a.id !== id);
  const next = { ...urls.value };
  delete next[id];
  urls.value = next;
  // 必须落盘：不写回索引的话，刷新页面这些"已经没有本体"的记录又会冒出来
  scheduleSave();
  try {
    await imageFileStore.remove(id);
  } catch {
    /* 删不掉就算了，不影响使用 */
  }
}

/** 清空全部（图片库"清空全部"用） */
export async function removeAllAssets(): Promise<void> {
  const ids = assets.value.map((a) => a.id);
  assets.value = [];
  urls.value = {};
  scheduleSave(); // 同上：索引必须写回，否则下次打开又"复活"
  for (const id of ids) {
    try {
      await imageFileStore.remove(id);
    } catch {
      /* 忽略 */
    }
  }
}

/** 加载资产表（元数据，很小） */
export async function loadAssets(): Promise<void> {
  if (loaded.value) return;
  if (loadingPromise) return loadingPromise;
  loadingPromise = (async () => {
    try {
      /* 资产表走 index（只有元数据），不是 list（那是图片 id 列表） */
      const idx = await imageFileStore.getIndex<ImageAsset[]>();
      assets.value = Array.isArray(idx) ? idx : [];
      loaded.value = true;
    } catch (e) {
      console.warn('[图片资产] 读资产表失败：', e);
    } finally {
      loadingPromise = null;
    }
  })();
  return loadingPromise;
}

async function saveAssets(): Promise<void> {
  // 没读到旧表就整份覆盖 = 把用户整个图片库抹掉。读失败时这轮先不存，
  // 下次改动还会再试。读成功（哪怕是空表）loaded 也会是 true。
  if (!loaded.value) {
    await loadAssets();
    if (!loaded.value) return;
  }
  try {
    await imageFileStore.setIndex(assets.value);
  } catch (e) {
    console.warn('[图片资产] 写资产表失败：', e);
  }
}

/* 改了列表就自动落盘（防抖由 imageFileStore 内部处理） */
let saveTimer: ReturnType<typeof setTimeout> | null = null;
function scheduleSave() {
  if (saveTimer) clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    saveTimer = null;
    void saveAssets();
  }, 400);
}

/** 往资产库里塞一条已经生成好的图（Tags 直出等入口用） */
export async function addAsset(dataUrl: string, meta: Partial<ImageAsset>): Promise<string> {
  const id = await registerAsset(dataUrl, meta);
  scheduleSave();
  return id;
}

/** 外部直接改列表后调用，触发落盘 */
export function markAssetsDirty(): void {
  scheduleSave();
}

/* ---- 便捷查询 ---- */

export function assetOf(id: string): ImageAsset | undefined {
  return byId.value.get(id);
}

export function listAssets(filter?: { scene?: 'novel' | 'adventure'; origin?: ImageAsset['origin'] }): ImageAsset[] {
  let arr = assets.value;
  if (filter?.scene) arr = arr.filter((a) => a.scene === filter.scene);
  if (filter?.origin) arr = arr.filter((a) => a.origin === filter.origin);
  return [...arr].sort((a, b) => b.timestamp - a.timestamp);
}

export const assetCount = computed(() => assets.value.length);
export const assetsReady = computed(() => loaded.value);