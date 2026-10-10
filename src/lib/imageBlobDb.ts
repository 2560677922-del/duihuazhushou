// 图片等大块数据的专用 IndexedDB 库
// ---------------------------------------------------------------------------
// 单独一个模块是为了**避免循环引用**：
//   storage.ts  →  blobStore.ts（外置/还原）
//   blobStore.ts →  本模块（存图）
// 如果直接把库放在 storage.ts 里，blobStore 就得反过来 import storage，
// 两边互相引用，打包和初始化顺序都会出问题。
//
// 这里用独立的 localforage 实例（storeName: 'blobs'），和配置用的 'kv' 分开。
// 好处是：配置区继续小、快；图片区按 GB 计，互不影响；
// 以后要清图片缓存也只清这一个 store。

import localforage from 'localforage';

const blobDb = localforage.createInstance({
  name: 'duihuazhushou',
  storeName: 'blobs',
  description: '图片等大块数据',
});

export const imageBlobs = {
  /** 存一张图（dataURL 字符串） */
  async set(key: string, dataUrl: string): Promise<void> {
    await blobDb.setItem(key, dataUrl);
  },
  async get(key: string): Promise<string | null> {
    return (await blobDb.getItem<string>(key)) ?? null;
  },
  async remove(key: string): Promise<void> {
    await blobDb.removeItem(key);
  },
  async keys(): Promise<string[]> {
    return blobDb.keys();
  },
  async clear(): Promise<void> {
    await blobDb.clear();
  },
  /** 占用了多少字节（用于界面上提示） */
  async usage(): Promise<number> {
    let total = 0;
    await blobDb.iterate<string, void>((v) => {
      if (typeof v === 'string') total += v.length;
    });
    return total;
  },
};

/**
 * 整个"只进 IndexedDB"的键（比如生图记录数组）。
 * 和单张图共用同一个 store，但存的是任意值。
 */
export const kvBlobs = {
  async set(key: string, value: unknown): Promise<void> {
    await blobDb.setItem(key, value);
  },
  async get<T>(key: string): Promise<T | null> {
    return (await blobDb.getItem<T>(key)) ?? null;
  },
  async remove(key: string): Promise<void> {
    await blobDb.removeItem(key);
  },
};
