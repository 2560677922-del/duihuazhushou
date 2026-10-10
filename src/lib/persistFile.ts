// 本地文件持久化：**手机端（Capacitor）实现**
// ---------------------------------------------------------------------------
// 这个文件是纯新增的，不影响电脑上现在跑的任何逻辑。
//
// 背景：电脑上的落盘能力来自 vite-plugins/local-persist.ts —— 那是 Vite
// 开发服务器提供的接口。打包成 APK 之后**没有开发服务器**，所以那套会失效，
// 应用会退回纯浏览器存储（可能被系统清理）。
//
// 这里提供同一套接口的手机实现：把配置写成 App 私有目录里的真实文件。
//   - 关机重启都在
//   - 清缓存不影响（私有目录不受"清理缓存"影响）
//   - 卸载时随 App 一起消失，不留残留
//
// 接口与电脑版**完全一致**（同样的 11 个方法），所以上层 storage.ts、
// main.ts、Settings.vue 一行都不用改。
//
// 什么时候会用到：只有真的打包成 App 时。浏览器里运行时这个文件不会被加载
// （persist.ts 会先检测环境，不是 Capacitor 就继续用原来的实现）。

import type { PersistStatus } from './persist';

const DATA_DIR = 'Data';
const DATA_FILE = 'app-data.json';
const BACKUP_DIR = 'Data/backups';
const MAX_BACKUPS = 20;

interface StoredShape {
  version: number;
  updatedAt: number;
  instanceId: string;
  data: Record<string, unknown>;
}

/** 本实例标识：手机上只有一个 App 实例，但仍保留字段以兼容电脑版格式 */
const INSTANCE_ID = `mobile-${Date.now().toString(36)}`;

type Listener = (s: PersistStatus) => void;

/**
 * 创建手机版持久化实现。
 * 懒加载 Capacitor 插件，避免浏览器构建时把原生代码打进包。
 */
export function createCapacitorPersist() {
  let ready = false;
  let initError = '';
  const listeners = new Set<Listener>();
  let status: PersistStatus = { mode: 'unknown' };

  let pending = new Map<string, unknown>();
  let flushTimer: ReturnType<typeof setTimeout> | null = null;
  let suppressMirror = false;

  // Capacitor 插件句柄，初始化时填充
  let Filesystem: any = null;
  let Directory: any = null;

  function emit() {
    for (const fn of listeners) fn({ ...status });
  }
  function setStatus(patch: Partial<PersistStatus>) {
    status = { ...status, ...patch };
    emit();
  }

  /**
   * 初始化：动态加载 Capacitor 插件并准备目录。
   * 用动态 import 是刻意的 —— 浏览器环境不会走到这里，
   * 打包时也能避免把原生依赖塞进 Web 版本。
   */
  async function probe(): Promise<boolean> {
    if (ready) return true;
    if (initError) return false;

    try {
      const cap = (globalThis as any).Capacitor;
      if (!cap?.isNativePlatform?.()) {
        initError = '不是原生环境';
        setStatus({ mode: 'browser' });
        return false;
      }

      // 运行时加载插件。
      //
      // 这里刻意用变量拼出包名：写死的字符串会让打包器在构建期就去解析
      // "@capacitor/filesystem"，而这个包在还没到打包那一步时并不存在，
      // 会导致浏览器版本也构建失败。用变量后打包器不再解析，
      // 只有在手机里真的执行到这行时才会去加载。
      const pkg = '@capacitor/' + 'filesystem';
      const fs: any = await import(/* @vite-ignore */ pkg);
      Filesystem = fs?.Filesystem ?? cap?.Plugins?.Filesystem;
      Directory = fs?.Directory ?? {
        Data: 'DATA',
        Documents: 'DOCUMENTS',
        Cache: 'CACHE',
        ExternalStorage: 'EXTERNAL_STORAGE',
      };
      if (!Filesystem) throw new Error('Filesystem 插件不可用');

      // 建目录（已存在会报错，忽略即可）
      for (const dir of ['Data', BACKUP_DIR]) {
        try {
          await Filesystem.mkdir({ path: dir, directory: Directory.Data, recursive: true });
        } catch {
          /* 目录已存在 */
        }
      }

      ready = true;
      setStatus({
        mode: 'file',
        file: `${DATA_DIR}/${DATA_FILE}`,
        dir: DATA_DIR,
        origin: 'app-private',
        error: undefined,
      });
      return true;
    } catch (e) {
      initError = e instanceof Error ? e.message : String(e);
      setStatus({
        mode: 'browser',
        error: `手机端文件存储不可用（${initError}），已退回浏览器存储`,
      });
      return false;
    }
  }

  /** 读文件原文；不存在返回 null */
  async function readFileRaw(): Promise<string | null> {
    try {
      const r = await Filesystem.readFile({
        path: `${DATA_DIR}/${DATA_FILE}`,
        directory: Directory.Data,
        encoding: 'utf8',
      });
      return typeof r.data === 'string' ? r.data : null;
    } catch {
      return null;
    }
  }

  async function writeFileRaw(text: string): Promise<void> {
    await Filesystem.writeFile({
      path: `${DATA_DIR}/${DATA_FILE}`,
      directory: Directory.Data,
      data: text,
      encoding: 'utf8',
    });
  }

  function readStored(raw: string | null): StoredShape {
    if (!raw) return { version: 2, updatedAt: 0, instanceId: INSTANCE_ID, data: {} };
    try {
      const j = JSON.parse(raw);
      return {
        version: j?.version ?? 2,
        updatedAt: j?.updatedAt ?? 0,
        instanceId: j?.instanceId ?? INSTANCE_ID,
        data: j?.data && typeof j.data === 'object' ? j.data : {},
      };
    } catch {
      return { version: 2, updatedAt: 0, instanceId: INSTANCE_ID, data: {} };
    }
  }

  /** 写盘前留一份快照，最多保留 MAX_BACKUPS 份 */
  async function snapshotBackup(raw: string | null) {
    if (!raw) return;
    try {
      const stamp = new Date().toISOString().replace(/[:.]/g, '-');
      await Filesystem.writeFile({
        path: `${BACKUP_DIR}/${stamp}.json`,
        directory: Directory.Data,
        data: raw,
        encoding: 'utf8',
      });
      // 清理超额备份
      const list = await Filesystem.readdir({ path: BACKUP_DIR, directory: Directory.Data });
      const files = (list?.files ?? [])
        .map((f: any) => (typeof f === 'string' ? f : f?.name))
        .filter((n: string) => n?.endsWith('.json'))
        .sort();
      for (const name of files.slice(0, Math.max(0, files.length - MAX_BACKUPS))) {
        await Filesystem.deleteFile({
          path: `${BACKUP_DIR}/${name}`,
          directory: Directory.Data,
        }).catch(() => {});
      }
    } catch {
      /* 备份失败不阻塞主流程 */
    }
  }

  /** 读取全部数据 */
  async function loadAll(): Promise<Record<string, unknown> | null> {
    if (!(await probe())) return null;
    const stored = readStored(await readFileRaw());
    setStatus({
      lastSyncedAt: Date.now(),
      originKeys: Object.keys(stored.data).length,
    });
    return Object.keys(stored.data).length ? stored.data : null;
  }

  function markChanged(key: string, value: unknown) {
    if (suppressMirror) return;
    pending.set(key, value);
    if (flushTimer) clearTimeout(flushTimer);
    flushTimer = setTimeout(() => void flush(), 600);
  }

  /** 合并写盘 */
  async function flush(): Promise<void> {
    if (flushTimer) {
      clearTimeout(flushTimer);
      flushTimer = null;
    }
    if (!ready || pending.size === 0) return;

    const batch = Object.fromEntries(pending);
    pending.clear();

    try {
      const raw = await readFileRaw();
      const stored = readStored(raw);
      const merged = { ...stored.data, ...batch };
      const next: StoredShape = {
        version: 2,
        updatedAt: Date.now(),
        instanceId: INSTANCE_ID,
        data: merged,
      };

      await snapshotBackup(raw);
      await writeFileRaw(JSON.stringify(next));
      setStatus({
        lastSavedAt: Date.now(),
        error: undefined,
        originKeys: Object.keys(merged).length,
      });
    } catch (e) {
      // 失败就把这批放回队列，下次再试
      for (const [k, v] of Object.entries(batch)) pending.set(k, v);
      setStatus({ error: `写入手机存储失败：${e instanceof Error ? e.message : String(e)}` });
    }
  }

  /** 整体替换（导入时用） */
  async function replaceAll(data: Record<string, unknown>) {
    suppressMirror = true;
    try {
      if (!ready) return;
      const raw = await readFileRaw();
      await snapshotBackup(raw);
      await writeFileRaw(
        JSON.stringify({ version: 2, updatedAt: Date.now(), instanceId: INSTANCE_ID, data }),
      );
      setStatus({ lastSavedAt: Date.now(), error: undefined });
    } finally {
      suppressMirror = false;
    }
  }

  /** 手机上只有一个 App 实例，不存在"别的窗口改了文件"的情况 */
  async function poll(): Promise<boolean> {
    return false;
  }

  async function acceptRemote(): Promise<Record<string, unknown> | null> {
    const data = await loadAll();
    setStatus({ remoteChanged: false });
    return data;
  }

  async function listBackups(): Promise<{ name: string; bytes: number; mtime: number }[]> {
    if (!(await probe())) return [];
    try {
      const list = await Filesystem.readdir({ path: BACKUP_DIR, directory: Directory.Data });
      return (list?.files ?? [])
        .map((f: any) => (typeof f === 'string' ? { name: f } : f))
        .filter((f: any) => f?.name?.endsWith('.json'))
        .map((f: any) => ({
          name: f.name,
          bytes: Number(f.size ?? 0),
          mtime: Number(f.mtime ?? 0),
        }));
    } catch {
      return [];
    }
  }

  /** 手机上只有一份数据，没有"按访问地址分片"的概念 */
  async function listOrigins() {
    return [{ origin: 'app-private', keys: status.originKeys ?? 0, updatedAt: Date.now() }];
  }

  return {
    probe,
    loadAll,
    markChanged,
    flush,
    poll,
    acceptRemote,
    replaceAll,
    listBackups,
    listOrigins,
    onChange(fn: Listener) {
      listeners.add(fn);
      fn({ ...status });
      return () => listeners.delete(fn);
    },
    getStatus: () => ({ ...status }),
    isFileMode: () => ready,
    setSuppressed(v: boolean) {
      suppressMirror = v;
    },
  };
}
