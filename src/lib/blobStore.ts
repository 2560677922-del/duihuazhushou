// 大块数据外置（图片 dataURL）
// ---------------------------------------------------------------------------
// 问题：消息、生图记录里都内嵌着 base64 dataURL。一张图几百 KB，
// 几十张就会：
//   1. 撑爆 localStorage 配额
//      （报错：Setting the value of '...generatedImages' exceeded the quota）
//   2. 把 app-data.json 变成几十 MB，读写都变慢
//
// 做法：写入存储前把 dataURL 抽出来，换成 `blob:<id>` 这样的短引用，
// 原图存进 IndexedDB 的 blob 库；读出来时再换回 dataURL。
// 上层组件完全感知不到 —— 它们拿到的还是完整的 dataURL。
//
// 这样：
//   - localStorage 里只有短引用
//   - 落盘文件也很小（配置和正文才是要紧的东西）
//   - 图片本身在 IndexedDB 里，容量按 GB 计

import { imageBlobs } from './imageBlobDb';

const PREFIX = 'blob:';

/** 什么算"大块数据"：足够长的 data: URL */
const DATA_URL_RE = /^data:[\w/+.-]+;base64,/;

export function isDataUrl(v: unknown): v is string {
  return typeof v === 'string' && DATA_URL_RE.test(v);
}

export function isBlobRef(v: unknown): v is string {
  return typeof v === 'string' && v.startsWith(PREFIX);
}

export function blobIdOf(ref: string): string {
  return ref.slice(PREFIX.length);
}

/**
 * 内容寻址的 id：**同一张图永远得到同一个 id**。
 *
 * 这是修「图片越用越占空间」的关键。
 *
 * 以前 id 是随机生成的（时间戳+自增+随机），而 externalize 每次保存都会把
 * 内存里的 dataURL 重新外置一遍。于是每保存**一次**就多写一份图，旧的
 * 那份再也没人引用、也永远不会被删。而流式生成时草稿每秒落盘一次 ——
 * 一次长对话就能堆出几百 MB。用户看到的"才 4 张图却占了 300MB"就是这么来的。
 *
 * 改成按内容算 id 之后：重复保存只是覆盖同一个键，不再产生垃圾；
 * 只有图片真的被删掉时才会留下孤儿，而那交给 gcOrphanBlobs 回收。
 */
const HASH_CACHE_MAX = 128;
const hashCache = new Map<string, string>();

/** FNV-1a 变体：纯 JS，比 crypto.subtle 慢一点但**不要求安全上下文**
 *  （局域网 http://192.168.x.x 访问时 crypto.subtle 是 undefined，
 *   用它会导致整个存图逻辑挂在非 https 环境下） */
function fnv1a(s: string, seed: number): number {
  let h = seed >>> 0;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = (h + ((h << 1) + (h << 4) + (h << 7) + (h << 8) + (h << 24))) >>> 0;
  }
  return h >>> 0;
}

export function contentId(dataUrl: string): string {
  const hit = hashCache.get(dataUrl);
  if (hit) return hit;
  // 两轮不同种子的哈希拼成 64 位，碰撞概率对几万张图也可忽略
  const a = fnv1a(dataUrl, 0x811c9dc5);
  const b = fnv1a(dataUrl, 0x1000193);
  const id = `${dataUrl.length.toString(36)}_${a.toString(36)}_${b.toString(36)}`;
  if (hashCache.size >= HASH_CACHE_MAX) hashCache.clear();
  hashCache.set(dataUrl, id);
  return id;
}

/** 生成一个引用 */
export function makeRef(id: string): string {
  return PREFIX + id;
}

/**
 * 把对象里所有内嵌的 dataURL 抽到 blob 库，原地换成引用。
 *
 * 会递归处理数组和普通对象；只认 dataURL 字符串，别的值原样保留。
 */
export async function externalize(value: unknown): Promise<unknown> {
  if (isDataUrl(value)) {
    const id = contentId(value);
    // 同一张图重复保存：先看看在不在，省掉一次写入
    try {
      const exist = await imageBlobs.get(id);
      if (exist) return makeRef(id);
      await imageBlobs.set(id, value);
      return makeRef(id);
    } catch (e) {
      // 抽不出来就保留原值 —— 至少功能还能用，不因为存不下就丢图
      console.warn('[图片外置] 写入失败，保留内嵌：', e);
      return value;
    }
  }
  if (Array.isArray(value)) {
    const out: unknown[] = [];
    for (const v of value) out.push(await externalize(v));
    return out;
  }
  if (value && typeof value === 'object') {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      out[k] = await externalize(v);
    }
    return out;
  }
  return value;
}

/**
 * 反向：把引用换回真正的 dataURL。
 *
 * 引用指向的图找不到时（比如清了 IndexedDB）就把该字段删掉，
 * 而不是留一个 `blob:xxx` 字符串让 <img> 显示成裂图。
 */
export async function internalize(value: unknown): Promise<unknown> {
  if (isBlobRef(value)) {
    const data = await imageBlobs.get(blobIdOf(value));
    return data ?? undefined;
  }
  if (Array.isArray(value)) {
    const out: unknown[] = [];
    for (const v of value) out.push(await internalize(v));
    return out;
  }
  if (value && typeof value === 'object') {
    const src = value as Record<string, unknown>;
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(src)) {
      const r = await internalize(v);
      // 引用失效时直接跳过这个字段，别留空壳
      if (r === undefined && isBlobRef(v)) continue;
      out[k] = r;
    }
    return out;
  }
  return value;
}

/**
 * 用户删掉某条记录后，把它引用的图也删掉。
 *
 * 否则会出现"界面上删了，可存储里还占着"的残留 ——
 * 这正是"删了应该有啥读啥"的另一半：删要真删。
 */
export async function collectRefs(value: unknown, out: Set<string> = new Set()): Promise<Set<string>> {
  if (isBlobRef(value)) {
    out.add(blobIdOf(value));
    return out;
  }
  if (Array.isArray(value)) {
    for (const v of value) await collectRefs(v, out);
    return out;
  }
  if (value && typeof value === 'object') {
    for (const v of Object.values(value as Record<string, unknown>)) await collectRefs(v, out);
  }
  return out;
}

/** 删除一批引用指向的图 */
export async function dropRefs(value: unknown): Promise<number> {
  const ids = await collectRefs(value);
  let n = 0;
  for (const id of ids) {
    try {
      await imageBlobs.remove(id);
      n++;
    } catch {
      /* 删不掉就跳过 */
    }
  }
  return n;
}

/**
 * 删除一段**内存中**的数据所引用的图。
 *
 * 和 dropRefs 的区别：内存里的 dataUrl 是真正的 dataURL（internalize 换回来的），
 * 不是 `blob:` 引用，所以要用 contentId 反推出键再删。
 * store 里删消息/删附件时用这个，否则图会变成永远回收不到的孤儿。
 */
export async function dropInlineImages(value: unknown): Promise<number> {
  const ids = new Set<string>();
  const walk = (v: unknown) => {
    if (isDataUrl(v)) {
      ids.add(contentId(v));
      return;
    }
    if (isBlobRef(v)) {
      ids.add(blobIdOf(v));
      return;
    }
    if (Array.isArray(v)) {
      for (const x of v) walk(x);
      return;
    }
    if (v && typeof v === 'object') {
      for (const x of Object.values(v as Record<string, unknown>)) walk(x);
    }
  };
  walk(value);
  let n = 0;
  for (const id of ids) {
    try {
      await imageBlobs.remove(id);
      n++;
    } catch {
      /* 忽略 */
    }
  }
  return n;
}

/** 把一卷数据里残留的内嵌 dataURL 全部外置（老数据迁移用） */
export async function migrateInline(value: unknown): Promise<{ value: unknown; moved: number }> {
  let moved = 0;
  const walk = async (v: unknown): Promise<unknown> => {
    if (isDataUrl(v)) {
      moved++;
      const id = contentId(v);
      try {
        await imageBlobs.set(id, v);
        return makeRef(id);
      } catch {
        moved--;
        return v;
      }
    }
    if (Array.isArray(v)) {
      const out: unknown[] = [];
      for (const x of v) out.push(await walk(x));
      return out;
    }
    if (v && typeof v === 'object') {
      const out: Record<string, unknown> = {};
      for (const [k, x] of Object.entries(v as Record<string, unknown>)) out[k] = await walk(x);
      return out;
    }
    return v;
  };
  return { value: await walk(value), moved };
}
