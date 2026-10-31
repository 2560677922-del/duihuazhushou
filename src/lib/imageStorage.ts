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

/**
 * @capacitor/filesystem 的调用签名。
 *
 * ⚠ 参数是**一个 options 对象**（`{ path, data, directory }`），不是位置参数！
 *   Capacitor 官方插件、以及打包时注入的 capacitors-compat.js 垫片，
 *   两者都只接受这一个对象。
 *
 *   以前这里写成了位置参数 `writeFile(path, data, opts)`：
 *     · 浏览器里走 IndexedDB，看不出问题；
 *     · 真机上垫片把**第一个参数**（一个字符串）原样交给原生，
 *       原生用 `new JSONObject(那个字符串)` 解析 → 直接抛
 *       「Value ... of type java.lang.String cannot be converted to JSONObject」。
 *   表现就是：**标签页生图在手机上永远存不进图片库**，
 *   而且因为写在 try/catch 里，只留一句"没能存进图片库"，图还在但库是空的。
 *
 *   persistFile.ts（设置/世界书那套落盘）一直用的是正确的对象写法，
 *   所以"设置存得住、图片存不住"—— 差别就在这儿。
 */
type NativeFsOpts = { path: string; data?: string; directory?: string; recursive?: boolean; encoding?: string };

type NativeFs = {
  readFile: (o: NativeFsOpts) => Promise<{ data: string | Blob }>;
  writeFile: (o: NativeFsOpts) => Promise<void>;
  deleteFile: (o: NativeFsOpts) => Promise<void>;
  readdir: (o: NativeFsOpts) => Promise<{ files: { name: string; type: string }[] }>;
  mkdir: (o: NativeFsOpts) => Promise<void>;
  stat: (o: NativeFsOpts) => Promise<{ size: number }>;
};

/**
 * 垫片的 `Directory.dataDir` 在这个实现里是 **undefined**（垫片只导出了
 * Directory.Data 这些枚举值，没有 dataDir 字段），拼出来的路径会是
 * `file:///images/xxx.txt` —— 所以 directUrl 在真机上拿不到可用地址。
 * 这里保留它作为"将来 Capacitor 官方插件回了真值"的快速路径，
 * 但**不依赖它**：真正的取图走 readFile（见 get()）。
 */


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
    await Filesystem.mkdir({ path: DIR, directory: 'DATA', recursive: true });
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

/** 存一张图到原生文件 / IndexedDB */
async function put(id: string, dataUrl: string): Promise<void> {
  const fs = await getNativeFs();
  if (fs) {
    // ★ 对象写法：{ path, data, directory }。位置写法在真机上必崩，见文件顶部说明。
    await fs.writeFile({ path: `${DIR}/${id}.txt`, data: dataUrl, directory: 'DATA', recursive: true });
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
      const r = await fs.readFile({ path: `${DIR}/${id}.txt`, directory: 'DATA' });
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
      await fs.deleteFile({ path: `${DIR}/${id}.txt`, directory: 'DATA' });
    } catch {
      /* 没有就算了 */
    }
    return;
  }
  const { imageBlobs } = await import('./imageBlobDb');
  await imageBlobs.remove(id);
}

/**
 * 原生侧能不能**直接给 `<img>` 一个 URL**。
 *
 * ⚠ 这里是条"有更好、没有也行"的快速路径，**不能返回半成品地址**：
 *   垫片里的 Directory 只有枚举值（Data: 'DATA' …），**没有 dataDir**，
 *   所以 `nativePath()` 拿到的是空串，拼出来会是 `file:///images/x.txt` ——
 *   这是个**指向根目录的无效地址**。<img> 加载它会直接裂图。
 *
 *   所以：拿不到真实目录就老实返回 null，让上层走 readFile 那条路
 *   （readFile 回的是 dataURL，一样能显示，只是多一次 JS 堆里的拷贝）。
 *   宁可慢一点，也不能给一个坏地址。
 */
async function directUrl(id: string): Promise<string | null> {
  const fs = await getNativeFs();
  if (!fs) return null;
  const mod = await loadFsModule();
  if (!mod?.Capacitor?.convertFileSrc) return null;
  const base = await nativePath();
  if (!base) return null; // 拿不到真实目录 → 不硬拼，交给 readFile
  return mod.Capacitor.convertFileSrc(`file://${base}/${DIR}/${id}.txt`);
}

/**
 * App 私有目录的绝对路径。
 * 只有垫片/官方插件真的提供了才返回，否则返回空串（调用方据此放弃快速路径）。
 */
async function nativePath(): Promise<string> {
  const mod = await loadFsModule();
  const d = mod?.Directory;
  // 官方插件的字段名历史上变过：dataDir / Data 两种都试一下
  const v = d?.dataDir || d?.Data;
  return typeof v === 'string' && v.startsWith('/') ? v : '';
}

/* ---- 资产表（元数据） ---- */

async function getIndex<T>(): Promise<T | null> {
  const fs = await getNativeFs();
  if (fs) {
    try {
      const r = await fs.readFile({ path: `${DIR}/index.json`, directory: 'DATA' });
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
    await fs.writeFile({ path: `${DIR}/index.json`, data: txt, directory: 'DATA', recursive: true });
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
      const r = await fs.readdir({ path: DIR, directory: 'DATA' });
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
        const r = await fs.stat({ path: `${DIR}/${id}.txt`, directory: 'DATA' });
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