// 图片按需解析（懒加载的公共底座）
// ---------------------------------------------------------------------------
// 为什么需要这个文件：
//
//   存图时，为了不让 localStorage 和 app-data.json 被图片撑爆，
//   dataURL 会被"外置"到独立的 IndexedDB 里，存储里只留 `blob:<id>`
//   这种几十字节的短引用（见 blobStore.ts）。
//
//   以前读出来的时候会把引用**全部**展开成 base64，结果是：
//   打开页面 = 把历史上所有图片一次性塞进内存。图越多越卡，
//   手机上几十张就卡到点不动。
//
//   现在改成：存储层只给引用，**谁来显示谁自己去取**。
//   这个文件就是"去取"的那个统一入口 —— 聊天、图片库、阅读器都用它，
// 免得每个组件各写一遍、各漏一处。
//
// 用法：
//   const url = await resolveImageRef(att.dataUrl);
//   if (!url) → 显示占位符

import { imageBlobs } from './imageBlobDb';

const PENDING = '__PENDING__';

/** 从各种形态里取出 blob 库的键；不是引用就返回 null */
export function blobKeyFromRef(v: unknown): string | null {
  if (typeof v !== 'string') return null;
  if (v.startsWith('blob:')) return v.slice(5);
  if (v.startsWith(PENDING)) return v.slice(PENDING.length);
  return null;
}

/** 这串值是不是可以直接丢给 <img>（已经是 data: 或 http(s):） */
export function isDirectlyUsable(v: unknown): boolean {
  return typeof v === 'string' && (v.startsWith('data:') || /^https?:\/\//i.test(v));
}

/**
 * 把一条"图片地址"解析成 <img> 能用的东西。
 *
 *   - 已经是 data:/http → 原样返回（零成本）
 *   - 是 blob:/__PENDING__ 引用 → 去 IndexedDB 取
 *   - 取不到 → 返回 null，让调用方显示占位符
 */
export async function resolveImageRef(v: unknown): Promise<string | null> {
  if (isDirectlyUsable(v)) return v as string;
  const key = blobKeyFromRef(v);
  if (!key) return null;
  try {
    const data = await imageBlobs.get(key);
    return data && String(data).startsWith('data:') ? data : null;
  } catch {
    return null;
  }
}

/**
 * 小型 LRU：只缓存最近 N 张已解析的图片。
 *
 * 为什么要缓存上限：滚动浏览时同一张图会反复进出视口，
 * 没缓存就反复查 IndexedDB；缓存无上限又会把内存吃回去。
 * N 取 24 是个折中 —— 够覆盖"来回滚几屏"的操作，
 * 最多也就几十 MB 以内。
 */
const CACHE_MAX = 24;
const cache = new Map<string, string>();

export function cachedImage(key: string): string | undefined {
  const v = cache.get(key);
  if (v !== undefined) {
    /* 提到队首（Map 保序） */
    cache.delete(key);
    cache.set(key, v);
  }
  return v;
}

/** 把解析结果塞进缓存（带上限） */
export function rememberImage(key: string, dataUrl: string): void {
  cache.delete(key);
  cache.set(key, dataUrl);
  while (cache.size > CACHE_MAX) {
    const oldest = cache.keys().next().value;
    if (oldest === undefined) break;
    cache.delete(oldest);
  }
}

/**
 * 解析一条附件的图片地址，带缓存。
 * 返回 dataURL 字符串，或 null（该显示占位符了）。
 */
export async function resolveCached(v: unknown): Promise<string | null> {
  if (isDirectlyUsable(v)) return v as string;
  const key = blobKeyFromRef(v);
  if (!key) return null;
  const hit = cachedImage(key);
  if (hit) return hit;
  const data = await resolveImageRef(v);
  if (data) rememberImage(key, data);
  return data;
}

/** 清缓存（用户清理图片库后要调，不然会一直拿到已删除的图） */
export function clearImageCache(): void {
  cache.clear();
}

/** 把一张图片塞进 blob 库，返回 `blob:<id>` 引用 */
export async function storeImageBlob(dataUrl: string): Promise<string | null> {
  try {
    const { contentId, makeRef } = await import('./blobStore');
    const id = contentId(dataUrl);
    await imageBlobs.set(id, dataUrl);
    return makeRef(id);
  } catch {
    return null;
  }
}

/** 判断某个引用在本地图片库还在不在（不读图片本体） */
let keysCache: Set<string> | null = null;
export async function hasBlobKey(key: string): Promise<boolean> {
  if (!keysCache) {
    try {
      keysCache = new Set(await imageBlobs.keys());
    } catch {
      keysCache = new Set();
    }
  }
  return keysCache.has(key);
}