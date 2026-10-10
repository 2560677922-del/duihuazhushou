import localforage from 'localforage';
import { persist } from './persist';
import { externalize, dropRefs, collectRefs } from './blobStore';
import { hasBlobKey, storeImageBlob } from './lazyImage';
import { kvBlobs, imageBlobs } from './imageBlobDb';

/** 把一个值里所有 blob 引用的 id 收进集合（gcOrphanBlobs 用） */
async function collectRefsInto(value: unknown, out: Set<string>): Promise<void> {
  const ids = await collectRefs(value);
  for (const id of ids) out.add(id);
}

/**
 * 空闲时回收孤儿图片。
 *
 * 用 requestIdleCallback（不支持就退回 setTimeout）避免和首屏抢资源；
 * 整个过程静默，失败也不打扰用户。
 */
function scheduleOrphanGc(): void {
  const run = () => {
    void store.gcOrphanBlobs().catch(() => {
      /* 清理失败无所谓 */
    });
  };
  const w = window as unknown as { requestIdleCallback?: (cb: () => void, o?: object) => number };
  if (typeof w.requestIdleCallback === 'function') {
    w.requestIdleCallback(run, { timeout: 5000 });
  } else {
    setTimeout(run, 3000);
  }
}

// 本地存储封装。
// ---------------------------------------------------------------------------
// 两层：
//   1. 内存/浏览器存储（IndexedDB，失败回退 localStorage）—— 始终可用，当缓存；
//   2. 本地落盘文件（由 vite-plugins/local-persist.ts 提供）—— 权威副本，
//      启动时优先读取，之后每次写入都会镜像过去。
//
// 这么做的原因：浏览器存储会因为清缓存、换访问地址（localhost 与局域网 IP
// 是两套完全独立的存储）而丢失，而配置里有 API Key，重建成本很高。

let useLocalStorage = false;
const localStoragePrefix = 'duihuazhushou_';

/** 落盘数据是否已经灌进内存，避免被浏览器里的旧值覆盖 */
let hydratedFromFile = false;

function getLocalStorage(key: string): unknown {
  const raw = localStorage.getItem(localStoragePrefix + key);
  return raw ? JSON.parse(raw) : null;
}

function setLocalStorage(key: string, value: unknown): void {
  localStorage.setItem(localStoragePrefix + key, JSON.stringify(value));
}

function clearLocalStorage(): void {
  Object.keys(localStorage)
    .filter((k) => k.startsWith(localStoragePrefix))
    .forEach((k) => localStorage.removeItem(k));
}

const db = localforage.createInstance({
  name: 'duihuazhushou',
  storeName: 'kv',
  description: 'AI 写作 + 冒险平台本地存储',
});

/**
 * 这些键**只**进 IndexedDB 的 blob 库，不进 localStorage、不进落盘文件。
 * 判定依据：值里可能含大量 base64 图片。
 */
const BLOB_ONLY_KEYS = new Set(['generatedImages']);

function isBlobOnly(key: string): boolean {
  return BLOB_ONLY_KEYS.has(key);
}

/** 写入浏览器存储（不触发落盘镜像） */
async function writeBrowser(key: string, value: unknown): Promise<void> {
  // 含图片的键只进 IndexedDB：不碰 localStorage（会超配额），
  // 也不镜像到落盘文件（会把 app-data.json 撑到几十 MB）
  if (isBlobOnly(key)) {
    try {
      await kvBlobs.set(key, value);
    } catch (e) {
      console.warn('[存储] 图片库写入失败：', e);
    }
    return;
  }

  persist.setSuppressed(true);
  try {
    if (useLocalStorage) {
      setLocalStorage(key, value);
      return;
    }
    try {
      await db.setItem(key, value);
    } catch (e) {
      // 只在**配额不足**时降级到 localStorage ——
      // 其它错误（比如被隐私模式禁用）降级过去也一样失败，
      // 反而会把真正的错误原因掩盖掉。
      if (isQuotaError(e)) {
        useLocalStorage = true;
        setLocalStorage(key, value);
      } else {
        throw e;
      }
    }
  } finally {
    persist.setSuppressed(false);
  }
}

/** 判断是不是"存储配额不足" */
function isQuotaError(e: unknown): boolean {
  if (!e) return false;
  const name = (e as { name?: string }).name ?? '';
  const msg = (e as { message?: string }).message ?? String(e);
  return (
    name === 'QuotaExceededError' ||
    name === 'NS_ERROR_DOM_QUOTA_REACHED' ||
    /quota|exceeded|storage is full/i.test(msg)
  );
}

/** 读取浏览器存储 */
async function readBrowser<T>(key: string): Promise<T | null | undefined> {
  if (isBlobOnly(key)) {
    try {
      return kvBlobs.get<T>(key);
    } catch {
      return null;
    }
  }
  if (useLocalStorage) return getLocalStorage(key) as T;
  try {
    return await db.getItem<T>(key);
  } catch {
    useLocalStorage = true;
    return getLocalStorage(key) as T;
  }
}

/**
 * 资产类数据的判重键。
 *
 * 用错键会把不同条目当成同一个丢掉 —— 会话和章节用的是 `id`，
 * 其它资产用的是 `_uid`，这个区别踩过一次坑。
 */
const MERGE_KEY_OF: Record<string, string> = {
  presets: '_uid',
  worldBooks: '_uid',
  regexGroups: '_uid',
  characters: '_uid',
  characterDnaList: '_uid',
  adventureSessions: 'id',
  novelChapters: 'id',
  novelProjects: 'id',
  apiConfigs: '_uid',
  imageConfigs: '_uid',
  githubStorageConfigs: '_uid',
  generatedImages: 'id',
};

/** 合并两个数组，按键去重；没有键的条目一律保留（宁多勿少） */
function mergeArrays(a: unknown, b: unknown, key: string): unknown[] {
  const out = Array.isArray(a) ? [...a] : [];
  const seen = new Set(out.map((x) => (x as Record<string, unknown>)?.[key]).filter(Boolean));
  for (const x of Array.isArray(b) ? b : []) {
    if (!x) continue;
    const k = (x as Record<string, unknown>)[key];
    if (k && seen.has(k)) continue;
    out.push(x);
    if (k) seen.add(k);
  }
  return out;
}

/** 一份数据里"用户资产"有多少条 —— 用来判断哪份更值得保留 */
function assetWeight(d: Record<string, unknown>): number {
  let n = 0;
  for (const [k, key] of Object.entries(MERGE_KEY_OF)) {
    const v = d[k];
    if (!Array.isArray(v)) continue;
    // 示例数据不算数：全是示例的时候不该被当成"更丰富"
    const real = v.filter((x) => !isSampleItem(x, k));
    void key;
    n += real.length;
  }
  return n;
}

/** 判断某个条目是不是自带的示例数据 */
function isSampleItem(x: unknown, kind: string): boolean {
  if (!x || typeof x !== 'object') return false;
  const o = x as Record<string, any>;
  const name = String(o.name || o.preset_name || o.scriptName || o.title || o.data?.name || '');
  if (/(^示例)|(图书馆之夜)/.test(name)) return true;
  // 冒险示例会话的标题
  if (kind === 'adventureSessions' && name === '冒险开始') return true;
  return false;
}

/**
 * 当前来源有数据、但明显比别处少时，自动把别处的资产并进来。
 *
 * 只有在"别处确实更多"时才动手，避免每次启动都做无谓的合并。
 */
async function adoptRicherOrigin(
  current: Record<string, unknown>,
): Promise<string | undefined> {
  if (!persist.isFileMode()) return undefined;
  try {
    const others = await persist.listOrigins();
    const mine = assetWeight(current);
    let best: { origin: string; weight: number } | null = null;
    for (const o of others) {
      if (o.origin === currentOriginHost()) continue;
      const d = await persist.peekOrigin(o.origin);
      if (!d) continue;
      const w = assetWeight(d);
      if (w > mine && (!best || w > best.weight)) best = { origin: o.origin, weight: w };
    }
    if (!best) return undefined;

    const src = await persist.peekOrigin(best.origin);
    if (!src) return undefined;

    const merged: Record<string, unknown> = { ...current };
    for (const [k, key] of Object.entries(MERGE_KEY_OF)) {
      if (Array.isArray(src[k]) || Array.isArray(merged[k])) {
        merged[k] = mergeArrays(merged[k], src[k], key);
      }
    }
    for (const k of ['currentPresetId', 'currentCharacterId']) {
      if (!merged[k] && src[k]) merged[k] = src[k];
    }
    for (const k of ['currentWorldBookIds', 'currentRegexGroupIds']) {
      merged[k] = [
        ...new Set([
          ...(Array.isArray(merged[k]) ? (merged[k] as string[]) : []),
          ...(Array.isArray(src[k]) ? (src[k] as string[]) : []),
        ]),
      ];
    }

    // 标量配置：当前没有的才补，不覆盖用户已经设好的
    for (const [k, v] of Object.entries(src)) {
      if (Array.isArray(v)) continue;
      if (merged[k] === undefined || merged[k] === null || merged[k] === '') merged[k] = v;
    }

    for (const [k, v] of Object.entries(merged)) await writeBrowser(k, v);
    await persist.replaceAll(merged);
    await persist.flush();
    console.log(`[本地持久化] 已从「${best.origin}」自动并入更完整的数据`);
    return best.origin;
  } catch (e) {
    console.warn('[本地持久化] 自动合并失败（不影响使用）：', e);
    return undefined;
  }
}

/**
 * 当前来源是空的：去别的来源找一份最完整的搬过来。
 *
 * 这是"换端口/换地址访问导致数据看起来丢了"的兜底 ——
 * 用户不需要知道来源是什么，打开就该看到自己的东西。
 */
async function rescueFromOtherOrigin(): Promise<{ origin: string; keys: number } | undefined> {
  if (!persist.isFileMode()) return undefined;
  try {
    const others = await persist.listOrigins();
    let best: { origin: string; weight: number; data: Record<string, unknown> } | null = null;
    for (const o of others) {
      if (o.origin === currentOriginHost()) continue;
      const d = await persist.peekOrigin(o.origin);
      if (!d || Object.keys(d).length === 0) continue;
      const w = assetWeight(d);
      if (w <= 0) continue; // 只有示例数据的来源不值得搬
      if (!best || w > best.weight) best = { origin: o.origin, weight: w, data: d };
    }
    if (!best) return undefined;

    for (const [k, v] of Object.entries(best.data)) await writeBrowser(k, v);
    await persist.replaceAll(best.data);
    await persist.flush();
    console.log(
      `[本地持久化] 当前地址没有数据，已从「${best.origin}」搬入 ${Object.keys(best.data).length} 项`,
    );
    return { origin: best.origin, keys: Object.keys(best.data).length };
  } catch (e) {
    console.warn('[本地持久化] 跨来源恢复失败（不影响使用）：', e);
    return undefined;
  }
}

/**
 * 自动修复历史遗留的图片引用。
 *
 * 背景：附件的 dataUrl 有时不是 dataURL，而是
 *   - `blob:xxx`       —— 指向本地图片存储区的引用
 *   - `__PENDING__xxx` —— 迁移过程中的临时占位
 *   - 一个远端链接      —— 旧版本"只存链接不留本地副本"留下的
 *
 * 前两种如果对应的图不在本地了，图片就会永远显示成占位符。
 * 这里在启动时扫一遍，能从 GitHub 补回来的就补，
 * **用户不需要做任何操作**（之前让人手动跑恢复页是不对的）。
 */
async function healImageRefs(): Promise<number> {
  if (!persist.isFileMode()) return 0;

  // 没有可用的 GitHub 配置就补不了，直接跳过（不报错）
  let gh: any = null;
  try {
    const cfgs = await readBrowser<any[]>('githubStorageConfigs');
    const curId = await readBrowser<string>('currentGithubStorageId');
    const list = Array.isArray(cfgs) ? cfgs : [];
    gh = (curId ? list.find((c) => c?._uid === curId) : null) || list[0] || null;
    if (!gh?.token || !gh?.owner || !gh?.repo) gh = null;
  } catch {
    gh = null;
  }
  if (!gh) return 0;

  let healed = 0;
  const cache = new Map<string, string | null>();

  async function fetchRemote(remotePath: string): Promise<string | null> {
    if (cache.has(remotePath)) return cache.get(remotePath) ?? null;
    let out: string | null = null;
    try {
      const url =
        `https://api.github.com/repos/${gh.owner}/${gh.repo}/contents/` +
        `${remotePath}?ref=${gh.branch || 'main'}`;
      const r = await fetch(url, {
        headers: { Authorization: `Bearer ${gh.token}`, Accept: 'application/vnd.github.v3+json' },
      });
      if (r.ok) {
        const j = await r.json();
        if (j?.content && j.encoding === 'base64') {
          const ext = String(remotePath).split('.').pop()?.toLowerCase();
          const mime =
            ext === 'webp' ? 'image/webp' : ext === 'jpg' || ext === 'jpeg' ? 'image/jpeg' : 'image/png';
          out = `data:${mime};base64,${String(j.content).replace(/\n/g, '')}`;
        }
      }
    } catch {
      out = null;
    }
    cache.set(remotePath, out);
    return out;
  }

  /**
   * 处理一组图片附件，返回修好了几张。
   *
   * 关键：**只判断图在不在，不把图读进内存**。
   *
   * 以前这里是 `imageBlobs.get(key)`，等于启动时把所有会话所有章节里的
   * 每一张图都从 IndexedDB 读回来、base64 展开一遍 —— 手机上开 App
   * 就要卡好几秒。图越多越卡。
   *
   * 现在改成先取一次 `imageBlobs.keys()`（几十字节），
   * 只判断引用还在不在；真缺图才去 GitHub 拉那一张。
   * 修复动作也改成"更新引用"，不再把图片本体塞回存储。
   */
  async function healAttachments(atts: unknown): Promise<number> {
    if (!Array.isArray(atts)) return 0;
    let n = 0;
    for (const im of atts) {
      if (!im || typeof im !== 'object') continue;
      const a = im as Record<string, any>;
      const u = String(a.dataUrl ?? '');
      if (u.startsWith('data:')) continue; // 已经是正常 dataURL（内存里刚生成的）

      const key = u.startsWith('blob:') ? u.slice(5) : u.startsWith('__PENDING__') ? u.slice(11) : null;
      if (key) {
        /* 本地图片存储区里有这个键 = 图还在，不用碰它 */
        if (await hasBlobKey(key)) continue;
        /* 缺了才去远端补，补回来的仍然存成引用，不留在存储里 */
        if (a.remotePath) {
          const got = await fetchRemote(String(a.remotePath));
          if (got) {
            const ref = await storeImageBlob(got);
            if (ref) {
              a.dataUrl = ref;
              n++;
            }
            continue;
          }
        }
        continue;
      }

      // 没有本地引用但有远端链接：直接挂链接，至少能显示
      if (a.remoteUrl) a.dataUrl = String(a.remoteUrl);
    }
    return n;
  }

  try {
    // 冒险：每轮行动的附件 + 场景描述抽出来的附件
    const sessions = await readBrowser<any[]>('adventureSessions');
    if (Array.isArray(sessions)) {
      let n = 0;
      for (const s of sessions) {
        for (const act of Array.isArray(s?.actions) ? s.actions : []) {
          n += await healAttachments(act?.images);
        }
        n += await healAttachments(s?.sceneImages);
      }
      if (n) {
        await writeBrowser('adventureSessions', sessions);
        persist.markChanged('adventureSessions', sessions);
        healed += n;
      }
    }

    // 小说：每轮消息的附件 + 章节级附件
    const chapters = await readBrowser<any[]>('novelChapters');
    if (Array.isArray(chapters)) {
      let n = 0;
      for (const c of chapters) {
        for (const m of Array.isArray(c?.messages) ? c.messages : []) {
          n += await healAttachments(m?.images);
        }
        n += await healAttachments(c?.images);
      }
      if (n) {
        await writeBrowser('novelChapters', chapters);
        persist.markChanged('novelChapters', chapters);
        healed += n;
      }
    }

    if (healed) {
      await persist.flush();
      console.log(`[本地持久化] 已自动补回 ${healed} 张图片（从仓库取回）`);
    }
  } catch (e) {
    console.warn('[本地持久化] 图片引用检查失败（不影响使用）：', e);
  }
  return healed;
}

/** 当前访问地址（host[:port]） */
function currentOriginHost(): string {
  return typeof location !== 'undefined' ? location.host || 'unknown' : 'unknown';
}

export const store = {
  /**
   * 启动时调用。
   *
   * 两种情况的处理不一样：
   *  - 文件里有数据 → 灌进浏览器缓存，文件是权威副本；
   *  - 文件是空的（第一次启用持久化）→ 保留浏览器里已有的配置，
   *    并把它写进文件，这样以后就不再依赖浏览器了。
   *
   * 之前如果无脑用文件覆盖，第一次启用时会把浏览器里已经填好的密钥清掉。
   */
  async hydrate(): Promise<{
    fromFile: boolean;
    adoptedBrowser: boolean;
    keys: number;
    /** 从别的访问地址自动搬过来的话，这里说明来源 */
    adoptedFrom?: string;
  }> {
    if (hydratedFromFile) return { fromFile: true, adoptedBrowser: false, keys: 0 };

    const data = await persist.loadAll();
    const fileKeys = data ? Object.keys(data) : [];

    if (fileKeys.length > 0) {
      for (const [k, v] of Object.entries(data!)) {
        await writeBrowser(k, v);
      }
      hydratedFromFile = true;
      // 图片引用可能是坏的（blob 引用、只存了链接、迁移占位符），
      // 启动时顺手补回来 —— 用户不该为这种事做任何操作
      await healImageRefs();
      /*
       * 回收孤儿图片，放到空闲时跑：
       * 老版本每次保存都会重写一份图，手机里可能已经堆了几百 MB，
       * 这里在后台清掉，不阻塞首屏。
       */
      scheduleOrphanGc();
      // 内容太少时，看看别的来源有没有更全的，有就自动合并过来
      const adopted = await adoptRicherOrigin(data!);
      return { fromFile: true, adoptedBrowser: false, keys: fileKeys.length, adoptedFrom: adopted };
    }

    // 这个来源是空的 —— 很可能是换了端口/地址访问（每个地址一套独立存储）。
    // 先找找别的来源里有没有东西，有就直接搬过来，
    // 避免用户看到"数据全没了"。
    const rescued = await rescueFromOtherOrigin();
    if (rescued) {
      hydratedFromFile = true;
      return { fromFile: true, adoptedBrowser: false, keys: rescued.keys, adoptedFrom: rescued.origin };
    }

    // 再看浏览器里有没有现成的东西，有就当成初始内容写进文件。
    const browserKeys: Record<string, unknown> = {};
    await store.iterate((value, key) => {
      browserKeys[key] = value;
    });
    const meaningful = Object.keys(browserKeys).filter((k) => !k.startsWith('__'));

    if (meaningful.length > 0 && persist.isFileMode()) {
      await persist.replaceAll(browserKeys);
      await persist.flush();
      hydratedFromFile = true;
      return { fromFile: false, adoptedBrowser: true, keys: meaningful.length };
    }

    return { fromFile: false, adoptedBrowser: false, keys: 0 };
  },

  async getItem<T>(key: string): Promise<T | null | undefined> {
    const v = await readBrowser<T>(key);
    if (v === null || v === undefined) return v;
    /*
     * 这里**故意不再**把 blob: 引用展开成 dataURL。
     *
     * 以前这里调 internalize，把这次读取涉及的每一张图都从 IndexedDB
     * 读回来、变成 base64 字符串。后果：
     *   · 打开会话页 = 把**所有会话的所有图片**一次性塞进内存
     *   · 50 张图 ≈ 70MB 字符串，手机上直接卡到点不动
     *   · 越用越卡，因为图只会越来越多
     *
     * 现在返回的仍是 `blob:<id>` 这样的短引用（几十字节）。
     * 真正要显示图片的地方（聊天里滚到的那张、图片库里点开的那张）
     * 自己按需去 imageBlobs 取 —— 见 lib/lazyImage.ts。
     */
    return v as T;
  },

  async setItem(key: string, value: unknown): Promise<void> {
    // 内嵌的图片 dataURL 先抽到 blob 库，存储里只留短引用。
    // 这样 localStorage 和落盘文件都不会被图片撑爆。
    const slim = await externalize(value);
    await writeBrowser(key, slim);
    // 镜像到本地文件（防抖合并）。图片类键整个不镜像。
    if (!isBlobOnly(key)) persist.markChanged(key, slim);
  },

  /** 删除某一项（用户删了就该真没了，不留残留） */
  async removeItem(key: string): Promise<void> {
    try {
      // 先读出来，把它引用的图一起删掉
      const existing = await readBrowser(key);
      if (existing !== null && existing !== undefined) {
        await dropRefs(existing);
      }
      if (isBlobOnly(key)) await kvBlobs.remove(key);
      else await db.removeItem(key);
    } catch (e) {
      console.warn(`[存储] 删除 ${key} 失败：`, e);
    }
  },

  /**
   * 清空。注意这是破坏性操作。
   * 平时不要调用它——导入请用 importAll 的「增补」模式。
   */
  async clear(): Promise<void> {
    if (useLocalStorage) {
      clearLocalStorage();
      return;
    }
    try {
      await db.clear();
    } catch {
      useLocalStorage = true;
      clearLocalStorage();
    }
  },

  async iterate(cb: (value: unknown, key: string) => void): Promise<void> {
    if (useLocalStorage) {
      Object.keys(localStorage)
        .filter((k) => k.startsWith(localStoragePrefix))
        .forEach((k) => {
          const value = JSON.parse(localStorage.getItem(k) || 'null');
          cb(value, k.slice(localStoragePrefix.length));
        });
      return;
    }
    try {
      // 图片库不参与遍历：导出配置时不该把几百 MB 的 base64 带上，
      // 启动时的"用浏览器数据初始化落盘文件"也不该把它们抄进 json。
      await db.iterate((value, key) => {
        if (isBlobOnly(key)) return;
        cb(value, key);
      });
    } catch {
      useLocalStorage = true;
      Object.keys(localStorage)
        .filter((k) => k.startsWith(localStoragePrefix))
        .forEach((k) => {
          const value = JSON.parse(localStorage.getItem(k) || 'null');
          cb(value, k.slice(localStoragePrefix.length));
        });
    }
  },

  /**
   * 回收孤儿图片（标记-清除）。
   *
   * 为什么需要：图片以 `blob:<id>` 的短引用存在配置里，真身在 IndexedDB 的
   * blob 库。删除消息 / 换配图 / 清空会话时，引用没了，但真身不会自动消失。
   * 历史版本还有个更严重的问题（id 随机、每次保存都重写一遍），
   * 老用户手机里可能已经堆了几百 MB 的垃圾 —— 这个函数就是把那些清出来。
   *
   * 做法：
   *   1. 把所有存起来的配置值读一遍，收集仍然被引用的 id
   *   2. blob 库里既不是"配置键"、也没被引用的条目 = 孤儿，删掉
   *
   * 只在启动后空闲时跑一次，不会卡住界面。
   */
  async gcOrphanBlobs(): Promise<{ removed: number; freedBytes: number }> {
    const referenced = new Set<string>();
    // 配置键（图片库里的数组，如 generatedImages）
    const configKeys = new Set<string>();
    try {
      await db.iterate((value, key) => {
        void collectRefsInto(value, referenced);
        configKeys.add(key);
      });
    } catch {
      /* 读不到就算了，宁可不清也不能误删 */
    }
    // 图片库里的"配置"条目（generatedImages 这种），它们不是图片，别误删
    for (const key of BLOB_ONLY_KEYS) {
      configKeys.add(key);
      try {
        const v = await kvBlobs.get<unknown>(key);
        if (v != null) await collectRefsInto(v, referenced);
      } catch {
        /* 忽略 */
      }
    }

    let removed = 0;
    let freedBytes = 0;
    try {
      const keys = await imageBlobs.keys();
      for (const k of keys) {
        if (configKeys.has(k)) continue; // 这是配置条目，不是图片
        if (referenced.has(k)) continue; // 还有人用
        try {
          const v = await imageBlobs.get(k);
          if (typeof v === 'string') freedBytes += v.length;
          await imageBlobs.remove(k);
          removed++;
        } catch {
          /* 单条删不掉不影响其余 */
        }
      }
    } catch (e) {
      console.warn('[存储] 回收孤儿图片失败：', e);
    }
    if (removed) {
      console.log(
        `[存储] 已回收 ${removed} 张无人引用的图片，释放约 ${(freedBytes / 1024 / 1024).toFixed(1)} MB`,
      );
    }
    return { removed, freedBytes };
  },

  async ready(): Promise<void> {
    try {
      await db.ready();
    } catch {
      useLocalStorage = true;
    }
  },

  /** 是否已从落盘文件恢复过 */
  isHydratedFromFile: () => hydratedFromFile,
  usesLocalStorageFallback: () => useLocalStorage,
};

/**
 * 生成唯一 ID。
 *
 * 注意：不能直接用 crypto.randomUUID —— 它只在**安全上下文**里存在，
 * 也就是 https 或 localhost。用户通过局域网地址访问时
 * （http://192.168.31.45:5173）属于不安全上下文，crypto.randomUUID 是 undefined，
 * 于是"新建世界书 / 新建正则"之类的操作会直接抛 TypeError。
 * 这正是「界面出错了：crypto.randomUUID is not a function」的原因。
 */
export function genId(prefix = 'id'): string {
  const rnd =
    typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
  return `${prefix}-${rnd}`;
}

/** 只想要一个唯一 ID 时的简写 */
export function uid(): string {
  return genId('id');
}

/**
 * 整库导出为单个 JSON 对象（供项目导出 / 备份）。
 *
 * 关于 undefined：
 *   底层是 localforage（IndexedDB），而 IndexedDB **不能存 undefined**，
 *   写入时会被静默丢弃（不抛错）。所以导出过程中可能根本读不到那把键。
 *   实测有过：appearanceThemeId 值是 undefined，导出文件里就没这一项，
 *   换台设备导入后该键不存在，代码读到 undefined —— 表现是"设置好像没迁过来"。
 *
 *   JSON 本身也表达不了 undefined（序列化时会整个键消失），
 *   所以统一转成 null：**键还在，值是 null**，语义上就是"这里没设过"。
 *   比键凭空消失好得多 —— 至少导入方能确定"这个设置是空的"，
 *   而不是"这个设置是不是迁移漏了"。
 */
export async function exportAll(): Promise<Record<string, unknown>> {
  const out: Record<string, unknown> = {};

  /** 把 undefined / NaN / Infinity 这类 JSON 表达不了的值规整掉 */
  const normalize = (v: unknown): unknown => {
    if (v === undefined) return null;
    if (typeof v === 'number' && !Number.isFinite(v)) return null;
    return v;
  };

  await store.iterate((value, key) => {
    out[key] = normalize(value);
  });

  return out;
}

/**
 * 导入数据。
 * 关键改动：默认是「增补」而不是「清空后导入」。
 * 旧实现先 store.clear() 再写入，一旦误选旧导出文件，
 * 所有配置和资产会被整体抹掉——这是真实发生过的数据丢失原因。
 *
 * @param mode 'merge' 只覆盖文件里出现的键（默认、安全）
 *             'replace' 先清空再导入（危险，调用方必须显式确认）
 */
export async function importAll(
  data: Record<string, unknown>,
  mode: 'merge' | 'replace' = 'merge',
): Promise<{ imported: number; skipped: number }> {
  if (!data || typeof data !== 'object') {
    throw new Error('导入内容不是合法的 JSON 对象');
  }
  const keys = Object.keys(data).filter((k) => k.startsWith(localStoragePrefix) || !k.startsWith('__'));
  if (keys.length === 0) {
    throw new Error('导入内容里没有任何可识别的数据键，请确认选对了导出文件');
  }

  if (mode === 'replace') {
    await store.clear();
  }

  let imported = 0;
  for (const k of keys) {
    // 兼容导出文件里的前缀键与裸键
    const bare = k.startsWith(localStoragePrefix) ? k.slice(localStoragePrefix.length) : k;
    await store.setItem(bare, data[k]);
    imported++;
  }
  await persist.flush();
  return { imported, skipped: Object.keys(data).length - keys.length };
}
