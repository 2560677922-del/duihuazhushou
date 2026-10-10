// 本地文件持久化的客户端。
// ---------------------------------------------------------------------------
// 设计目标：让「文件」成为配置与资产的权威副本，浏览器存储只当缓存。
//
// 工作方式：
//  - 启动时先向开发服务器要一份落盘数据；拿到就用它初始化内存状态。
//  - 每次写入先改内存，再防抖合并写盘（只提交变化的键）。
//  - 定时轮询文件的版本号；若别的窗口/设备改了配置，交给状态条提示。
//  - 开发服务器不可用时（纯静态部署、PWA 离线）自动降级为「只写浏览器存储」，
//    界面会显示当前处于哪种模式，不会静默失败。
//
// 两个曾经踩过的坑，这里都做了防护：
//  1. 按 origin 分开存：localhost:5173 与 192.168.31.45:5173 是两套浏览器存储，
//     共用一个文件时，一边写入默认配置会把另一边配好的密钥覆盖掉。
//  2. 区分「自己写的」与「别人写的」：本机同时跑两个 vite（端口自动 +1）时，
//     共用文件会让双方互相把对方当成外部改动，刷新提示弹个不停。

export type PersistMode = 'file' | 'browser' | 'unknown';

export interface PersistStatus {
  mode: PersistMode;
  /** 落盘文件路径（file 模式才有） */
  file?: string;
  dir?: string;
  bytes?: number;
  mtime?: number;
  backupCount?: number;
  /** 本页对应的访问来源（origin） */
  origin?: string;
  /** 该来源已落盘的键数量 */
  originKeys?: number;
  /** 文件里出现过的所有来源 */
  knownOrigins?: string[];
  /** 最近一次成功写盘时间 */
  lastSavedAt?: number;
  /** 最近一次同步回来的时间 */
  lastSyncedAt?: number;
  /** 检测到**别的**窗口/设备改过数据（不是本实例所写） */
  remoteChanged?: boolean;
  error?: string;
}

const TOKEN_META = 'dsh-persist-token';
const FLUSH_DELAY = 600;

// 本模块在 Node 环境下也允许被导入（跑格式校验脚本时会用到），
// 所以不依赖 import 期的浏览器全局变量。
const hasDom = typeof document !== 'undefined';
const hasLocation = typeof location !== 'undefined';

function readToken(): string {
  if (!hasDom) return '';
  const el = document.querySelector(`meta[name="${TOKEN_META}"]`);
  return el?.getAttribute('content') || '';
}

/**
 * 当前访问来源。必须随每个请求发给服务器：
 * localhost:5173 与 192.168.31.45:5173 是两个不同的浏览器存储，
 * 服务器按这个头把数据分开存，避免一个来源把另一个来源的配置覆盖掉。
 */
function currentOrigin(): string {
  return hasLocation ? location.host || 'unknown' : 'unknown';
}

function persistHeaders(extra: Record<string, string> = {}): Record<string, string> {
  return {
    'X-Persist-Origin': currentOrigin(),
    ...extra,
  };
}

export const persist = (() => {
  let token = '';
  let available = false;
  let probed = false;
  let status: PersistStatus = { mode: 'unknown' };

  /**
   * 手机端（Capacitor）实现。
   *
   * 只有真的打包成 App 时才会被启用；浏览器里它始终是 null，
   * 所有调用照旧走下面的 HTTP 实现 —— 所以加这一段不改变任何现有行为。
   * 启用方式见文件末尾的 enableNativePersist()。
   */
  let native: ReturnType<typeof import('./persistFile').createCapacitorPersist> | null = null;
  /** 是否已经检查过是不是手机环境（只查一次） */
  let nativeChecked = false;
  /** 手机端某个方法在初始化完成前就改过数据，需要补写 */
  let nativeDirty = new Map<string, unknown>();

  /** 把手机端的状态同步到本对象的监听器上 */
  function bridgeStatus(s: PersistStatus) {
    setStatus({ ...s });
  }

  /** 待写盘的键值对 */
  const pending = new Map<string, unknown>();
  let flushTimer: ReturnType<typeof setTimeout> | null = null;
  let flushPromise: Promise<void> | null = null;
  let suppressMirror = false;

  /** 本来源的数据版本号，用于判断别处是否改过 */
  let remoteUpdatedAt = 0;
  /** 本页对应的服务器实例 id，用于区分「自己写的」与「别人写的」 */
  let serverInstanceId = '';

  const listeners = new Set<(s: PersistStatus) => void>();

  function emit() {
    for (const fn of listeners) fn({ ...status });
  }

  function setStatus(patch: Partial<PersistStatus>) {
    status = { ...status, ...patch };
    emit();
  }

  /** 探测开发服务器的持久化接口是否可用（HTTP 实现） */
  async function probeHttp(): Promise<boolean> {
    if (probed) return available;
    probed = true;
    token = readToken();
    try {
      const r = await fetch('/__persist/status', {
        headers: persistHeaders({ Accept: 'application/json' }),
      });
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      const j = await r.json();
      available = !!j.enabled;
      serverInstanceId = j.instanceId || '';
      setStatus({
        mode: available ? 'file' : 'browser',
        file: j.file,
        dir: j.dir,
        bytes: j.bytes,
        mtime: j.mtime,
        backupCount: j.backupCount,
        origin: j.origin,
        originKeys: j.originKeys,
        knownOrigins: j.knownOrigins,
      });
    } catch (e) {
      available = false;
      setStatus({
        mode: 'browser',
        error: `未检测到本地落盘接口（${e instanceof Error ? e.message : String(e)}），已降级为浏览器存储`,
      });
    }
    return available;
  }

  /** 读取本来源的落盘数据；没有则返回 null（HTTP 实现） */
  async function loadAllHttp(): Promise<Record<string, unknown> | null> {
    if (!(await probeHttp())) return null;
    try {
      const r = await fetch('/__persist/data', {
        headers: persistHeaders({ Accept: 'application/json' }),
      });
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      const j = await r.json();
      remoteUpdatedAt = j.updatedAt || 0;
      serverInstanceId = j.instanceId || serverInstanceId;
      const data = j.data;
      if (!data || typeof data !== 'object') return null;
      setStatus({
        lastSyncedAt: Date.now(),
        error: undefined,
        origin: j.origin,
        originKeys: Object.keys(data).length,
      });
      return Object.keys(data).length ? (data as Record<string, unknown>) : null;
    } catch (e) {
      setStatus({ error: `读取落盘数据失败：${e instanceof Error ? e.message : String(e)}` });
      return null;
    }
  }

  /** 记录一次变更（内存已改完再调用），稍后合并写盘（HTTP 实现） */
  function markChangedHttp(key: string, value: unknown) {
    if (suppressMirror) return;
    if (!available) return; // 降级模式无需排队
    pending.set(key, value);
    if (flushTimer) clearTimeout(flushTimer);
    flushTimer = setTimeout(() => {
      void flush();
    }, FLUSH_DELAY);
  }

  /** 立即把待写内容合并写盘（HTTP 实现） */
  async function flushHttp(): Promise<void> {
    if (flushTimer) {
      clearTimeout(flushTimer);
      flushTimer = null;
    }
    if (!available || pending.size === 0) {
      if (flushPromise) return flushPromise;
      return;
    }
    if (flushPromise) {
      // 已有写盘在飞：等它结束再写这一批
      await flushPromise;
      if (pending.size === 0) return;
    }

    const batch = Object.fromEntries(pending);
    pending.clear();

    flushPromise = (async () => {
      try {
        const r = await fetch('/__persist/data', {
          method: 'POST',
          headers: persistHeaders({
            'Content-Type': 'application/json',
            'X-Persist-Token': token,
          }),
          body: JSON.stringify({ data: batch }),
        });
        const j = await r.json().catch(() => null);
        if (!r.ok) throw new Error(j?.error || `HTTP ${r.status}`);
        remoteUpdatedAt = j?.updatedAt || Date.now();
        serverInstanceId = j?.instanceId || serverInstanceId;
        setStatus({
          lastSavedAt: Date.now(),
          error: undefined,
          lastSyncedAt: Date.now(),
          originKeys: j?.totalKeys,
        });
      } catch (e) {
        // 写盘失败：把这一批放回队列，下次再试，同时明确报错
        for (const [k, v] of Object.entries(batch)) pending.set(k, v);
        setStatus({ error: `写入本地文件失败：${e instanceof Error ? e.message : String(e)}` });
      } finally {
        flushPromise = null;
      }
    })();

    return flushPromise;
  }

  /**
   * 轮询：判断本来源的数据是否被**别的浏览器/设备**改过。
   *
   * 关键点：必须忽略本实例自己写的改动。
   * 之前只看 mtime/updatedAt，而本机同时跑两个 vite（5173 + 5174）时，
   * 两边共用同一个文件，互相把对方的写入当成「外部改动」，
   * 导致刷新提示弹个不停（实测就是死循环）。现在比对 instanceId。
   *
   * 返回 true 表示确实检测到外部变更。
   */
  async function pollHttp(): Promise<boolean> {
    if (!available) return false;
    try {
      const r = await fetch('/__persist/status', {
        headers: persistHeaders({ Accept: 'application/json' }),
      });
      if (!r.ok) return false;
      const j = await r.json();
      setStatus({
        bytes: j.bytes,
        mtime: j.mtime,
        backupCount: j.backupCount,
        originKeys: j.originKeys,
        knownOrigins: j.knownOrigins,
      });

      const fileUpdatedAt = j.updatedAt || 0;
      if (fileUpdatedAt === 0) return false;

      // 这份数据是本页对应的服务器实例写的 → 不是外部改动
      if (j.instanceId && j.instanceId === serverInstanceId) {
        remoteUpdatedAt = fileUpdatedAt;
        if (status.remoteChanged) setStatus({ remoteChanged: false });
        return false;
      }

      const changed = fileUpdatedAt > remoteUpdatedAt && remoteUpdatedAt > 0;
      if (changed) setStatus({ remoteChanged: true });
      return changed;
    } catch {
      return false;
    }
  }

  /** 用户确认载入外部改动后调用，重新读取数据 */
  async function acceptRemoteHttp(): Promise<Record<string, unknown> | null> {
    const data = await loadAllHttp();
    setStatus({ remoteChanged: false });
    return data;
  }

  /** 整体替换（导入时用）。会一次性写盘。 */
  async function replaceAllHttp(data: Record<string, unknown>) {
    suppressMirror = true;
    try {
      if (!available) return;
      const r = await fetch('/__persist/data', {
        method: 'POST',
        headers: persistHeaders({
          'Content-Type': 'application/json',
          'X-Persist-Token': token,
        }),
        body: JSON.stringify({ data }),
      });
      const j = await r.json().catch(() => null);
      if (!r.ok) throw new Error(j?.error || `HTTP ${r.status}`);
      remoteUpdatedAt = j?.updatedAt || Date.now();
      setStatus({ lastSavedAt: Date.now(), error: undefined });
    } finally {
      suppressMirror = false;
    }
  }

  async function listBackupsHttp(): Promise<{ name: string; bytes: number; mtime: number }[]> {
    if (!(await probeHttp())) return [];
    try {
      const r = await fetch('/__persist/backups', {
        headers: persistHeaders({ Accept: 'application/json' }),
      });
      if (!r.ok) return [];
      const j = await r.json();
      return j.backups || [];
    } catch {
      return [];
    }
  }

  /** 列出文件里记录过的所有来源，便于排查"配置没回来"是不是访问地址变了 */
  async function listOriginsHttp(): Promise<
    { origin: string; keys: number; updatedAt: number }[]
  > {
    if (!(await probeHttp())) return [];
    try {
      const r = await fetch('/__persist/origins', {
        headers: persistHeaders({ Accept: 'application/json' }),
      });
      if (!r.ok) return [];
      const j = await r.json();
      return j.origins || [];
    } catch {
      return [];
    }
  }

  /**
   * 读取**指定来源**的数据（只读）。
   * 用来把散落在别的端口 / 地址下的数据找回来。
   */
  async function peekOriginHttp(target: string): Promise<Record<string, unknown> | null> {
    if (!(await probeHttp())) return null;
    try {
      const r = await fetch(`/__persist/data?origin=${encodeURIComponent(target)}`, {
        headers: persistHeaders({ Accept: 'application/json' }),
      });
      if (!r.ok) return null;
      const j = await r.json();
      const data = j?.data;
      return data && typeof data === 'object' ? data : null;
    } catch {
      return null;
    }
  }

  /** 删除某个来源（合并之后清理残片用） */
  async function deleteOriginHttp(target: string): Promise<boolean> {
    if (!(await probeHttp())) return false;
    try {
      const r = await fetch(`/__persist/origin?origin=${encodeURIComponent(target)}`, {
        method: 'DELETE',
        headers: persistHeaders(),
      });
      return r.ok;
    } catch {
      return false;
    }
  }

  /**
   * 检查是不是手机（Capacitor）环境，是就启用文件存储。
   *
   * 只在这里做环境判断，判断通过才动态加载 persistFile.ts，
   * 浏览器里这个 import 永远不会执行 —— 所以 Web 版本的体积和行为都不受影响。
   */
  async function ensureNative(): Promise<boolean> {
    if (nativeChecked) return !!native;
    nativeChecked = true;
    try {
      // 先看全局有没有 Capacitor。
      // Capacitor 会把运行时挂到 window.Capacitor 上，这样判断
      // 不需要 import 任何包 —— 浏览器版本因此完全不受影响，
      // 构建时也不会因为"找不到 @capacitor/core"而失败。
      const cap = (globalThis as any).Capacitor;
      if (!cap?.isNativePlatform?.()) return false;

      // 只有确认是手机环境，才真正去加载手机版实现。
      // 这里的 import 是运行时的，用 ​/* @vite-ignore */ 让打包器跳过解析。
      const mod = await import(/* @vite-ignore */ './persistFile');
      const impl = mod.createCapacitorPersist();
      const ok = await impl.probe();
      if (!ok) return false;

      impl.onChange((s) => bridgeStatus(s));
      native = impl;
      // 初始化期间如果已经改过数据，补写一次
      if (nativeDirty.size > 0) {
        for (const [k, v] of nativeDirty) impl.markChanged(k, v);
        nativeDirty.clear();
        void impl.flush();
      }
      return true;
    } catch (e) {
      // 手机版实现加载失败（依赖没装齐等），退回现有实现
      console.debug('[持久化] 未启用手机端存储，继续使用现有实现：', e);
      return false;
    }
  }

  // ---------------- 对外接口（每个方法都先看手机端是否接管） ----------------

  async function probe(): Promise<boolean> {
    return (await ensureNative()) ? true : probeHttp();
  }

  async function loadAll(): Promise<Record<string, unknown> | null> {
    if (await ensureNative()) return native!.loadAll();
    return probeHttp().then((ok) => (ok ? loadAllHttp() : null));
  }

  function markChanged(key: string, value: unknown) {
    if (native) {
      native.markChanged(key, value);
      return;
    }
    // 手机端还没初始化完就先攒着，避免启动早期的改动丢掉
    if (!nativeChecked) nativeDirty.set(key, value);
    markChangedHttp(key, value);
  }

  async function flush(): Promise<void> {
    if (native) return native.flush();
    return flushHttp();
  }

  async function poll(): Promise<boolean> {
    if (native) return native.poll();
    return pollHttp();
  }

  async function acceptRemote(): Promise<Record<string, unknown> | null> {
    if (native) return native.acceptRemote();
    return acceptRemoteHttp();
  }

  async function replaceAll(data: Record<string, unknown>) {
    if (native) return native.replaceAll(data);
    return replaceAllHttp(data);
  }

  async function listBackups(): Promise<{ name: string; bytes: number; mtime: number }[]> {
    if (native) return native.listBackups();
    return listBackupsHttp();
  }

  async function listOrigins(): Promise<{ origin: string; keys: number; updatedAt: number }[]> {
    if (native) return native.listOrigins();
    return listOriginsHttp();
  }

  function onChange(fn: (s: PersistStatus) => void) {
    listeners.add(fn);
    fn({ ...status });
    return () => listeners.delete(fn);
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
    /** 读取指定来源的数据（只读），用于跨地址找回数据 */
    peekOrigin: (target: string) => peekOriginHttp(target),
    /** 删除某个来源 */
    deleteOrigin: (target: string) => deleteOriginHttp(target),
    onChange,
    getStatus: () => ({ ...status }),
    isFileMode: () => (native ? native.isFileMode() : available),
    /** 供持久化写入自身使用，避免自我触发 */
    setSuppressed(v: boolean) {
      suppressMirror = v;
      native?.setSuppressed(v);
    },
    /**
     * 显式启用手机端实现。
     * 打包成 App 时在启动流程里调用一次即可；不调用也不影响任何行为。
     */
    enableNative: ensureNative,
  };
})();
