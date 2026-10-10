// 图片文件存储 —— 按环境分流，对上层暴露同一套接口
// ---------------------------------------------------------------------------
// APK（Capacitor）→ 安卓原生文件（app 私有目录 images/）
// 浏览器          → IndexedDB
//
// 为什么要换掉 IndexedDB：
//   图片是大块二进制。存 IndexedDB 要走 base64（体积 +33%），
//   取出来还要在 WebView 的 JS 堆里解成字符串 —— 图一多就卡。
//   存原生文件是原图直接落盘，<img> 用 convertFileSrc 加载，
//   完全不进 JS 堆 —— 这才是手机上该有的做法。
//
// 关键：**两条路对外完全一样**，业务代码不需要知道自己在哪。

/** 资产表（只有元数据，没有图片本体）的存储键 */
const INDEX_KEY = 'imageAssetIndex_v1';

/* ==========================================================================
   后端：安卓原生
   ========================================================================== */

type NativeFs = {
  readFile: (p: string) => Promise<{ data: string | Blob }>;
  writeFile: (p: string, d: string, o?: unknown) => Promise<void>;
  deleteFile: (p: string) => Promise<void>;
  readdir: (p: string) => Promise<{ files: { name: string; type: string }[] }>;
  mkdir: (p: string) => Promise<void>;
  stat: (p: string) => Promise<{ size: number }>;
};

const DIR = 'images';

/**
 * 动态加载 @capacitor/filesystem。
 *
 * 必须用「变量拼包名 + @vite-ignore」，不能写静态 import：
 * 这个包只在打包 APK 时才装上，浏览器构建 / 普通开发时 node_modules 里没有，
 * 静态 import 会让 rollup 直接构建失败。
 * （persistFile.ts 里就是同样的写法，这里保持一致。）
 */
async function loadFsModule(): Promise<any | null> {
  try {
    const pkg = '@capacitor/' + 'filesystem';
    const mod: any = await import(/* @vite-ignore */ pkg);
    return mod ?? null;
  } catch {
    return null;
  }
}

async function getNativeFs(): Promise<NativeFs | null> {
  const cap: any = (globalThis as any).Capacitor;
  if (!cap?.isNativePlatform?.()) return null;
  const mod = await loadFsModule();
  const Filesystem = mod?.Filesystem;
  if (!Filesystem) return null;
  try {
    await Filesystem.mkdir({ path: DIR, directory: 'DATA' });
  } catch {
    /* 已存在 */
  }
  return Filesystem;
}

/* ==========================================================================
   对外接口
   ========================================================================== */

async function isNative(): Promise<boolean> {
  return (await getNativeFs()) !== null;
}

/** 存一张图，返回实际路径（原生）或 null（浏览器） */
async function put(id: string, dataUrl: string): Promise<void> {
  const fs = await getNativeFs();
  if (fs) {
    await fs.writeFile(`${DIR}/${id}.txt`, dataUrl);
    return;
  }
  const { imageBlobs } = await import('./imageBlobDb');
  await imageBlobs.set(id, dataUrl);
}

/** 取一张图 */
async function get(id: string): Promise<string | null> {
  const fs = await getNativeFs();
  if (fs) {
    try {
      const r = await fs.readFile(`${DIR}/${id}.txt`);
      return typeof r.data === 'string' ? r.data : null;
    } catch {
      return null;
    }
  }
  const { imageBlobs } = await import('./imageBlobDb');
  return imageBlobs.get(id);
}

async function remove(id: string): Promise<void> {
  const fs = await getNativeFs();
  if (fs) {
    try {
      await fs.deleteFile(`${DIR}/${id}.txt`);
    } catch {
      /* 没有就算了 */
    }
    return;
  }
  const { imageBlobs } = await import('./imageBlobDb');
  await imageBlobs.remove(id);
}

/** 原生侧能不能直接给 <img> 用一个 URL —— 能，返回 convertFileSrc 后的地址 */
async function directUrl(id: string): Promise<string | null> {
  const fs = await getNativeFs();
  if (fs) {
    const mod = await loadFsModule();
    if (!mod?.Capacitor?.convertFileSrc) return null;
    return mod.Capacitor.convertFileSrc(`file://${await nativePath()}/${DIR}/${id}.txt`);
  }
  return null;
}

async function nativePath(): Promise<string> {
  const mod = await loadFsModule();
  return mod?.Directory?.dataDir || '';
}

/* ---- 资产表（元数据） ---- */

async function getIndex<T>(): Promise<T | null> {
  const fs = await getNativeFs();
  if (fs) {
    try {
      const r = await fs.readFile(`${DIR}/index.json`);
      const txt = typeof r.data === 'string' ? r.data : await (r.data as Blob).text();
      return JSON.parse(txt) as T;
    } catch {
      return null;
    }
  }
  const { store } = await import('./storage');
  return (await store.getItem<T>(INDEX_KEY)) ?? null;
}

async function setIndex(value: unknown): Promise<void> {
  const txt = JSON.stringify(value);
  const fs = await getNativeFs();
  if (fs) {
    await fs.writeFile(`${DIR}/index.json`, txt);
    return;
  }
  const { store } = await import('./storage');
  await store.setItem(INDEX_KEY, value);
}

/**
 * 列举所有图片 id（清理残留用）。
 * 原生侧靠目录列表，浏览器侧靠 IndexedDB 的键。
 */
async function list(): Promise<string[]> {
  const fs = await getNativeFs();
  if (fs) {
    try {
      const r = await fs.readdir(DIR);
      return r.files.map((f) => f.name.replace(/\.txt$/, ''));
    } catch {
      return [];
    }
  }
  const { imageBlobs } = await import('./imageBlobDb');
  return imageBlobs.keys();
}

/** 占用空间 */
async function usage(): Promise<number> {
  const fs = await getNativeFs();
  if (fs) {
    let total = 0;
    for (const id of await list()) {
      if (id === 'index') continue;
      try {
        const r = await fs.stat(`${DIR}/${id}.txt`);
        total += r.size;
      } catch {
        /* 跳过 */
      }
    }
    return total;
  }
  const { imageBlobs } = await import('./imageBlobDb');
  return imageBlobs.usage();
}

export const imageFileStore = {
  put,
  get,
  remove,
  list,
  usage,
  getIndex,
  setIndex,
  directUrl,
  isNative,
};