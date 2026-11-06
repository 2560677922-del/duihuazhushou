// 变量存储：给下一个接手的说明
// ---------------------------------------------------------------------------
// 两张**平级**的扁平表，不多一层作用域链，也不做继承：
//   · globalVariables  → 全应用一份（对应 ST 的 settings.json 全局变量）
//   · chatVariables    → 按会话 id 分包（对应 ST 的 chat metadata 局部变量）
//
// 为什么会话变量不直接挂在 AdventureSession 上：
//   那样每次改变量都要重写整份会话数据（会触发会话列表、摘要、草稿的
//   连锁重渲染），而变量是高频读写的。单独一个键，读写都只碰自己。
// 代价：删会话时这张表里会留下一个空壳键 —— 数据量极小，且 load() 时
// 会按现有会话清理一次，不会无限膨胀。
import { defineStore } from 'pinia';
import { ref } from 'vue';
import { store } from '../lib/storage';
import { useAdventureStore } from './adventure';
import {
  applyVariableWrites,
  variableVersion,
  type VariableTables,
  type VariableScope,
} from '../engine/variables';

const K_GLOBAL = 'globalVariables';
const K_SESSION = 'chatVariables';

type StringMap = Record<string, string>;

function asMap(v: unknown): StringMap {
  if (!v || typeof v !== 'object' || Array.isArray(v)) return {};
  const out: StringMap = {};
  for (const [k, val] of Object.entries(v as Record<string, unknown>)) {
    if (typeof k === 'string' && k && (typeof val === 'string' || typeof val === 'number')) {
      out[k] = String(val);
    }
  }
  return out;
}

export interface VariablesBackup {
  format: 'duihuazhushou-variables/1';
  global: StringMap;
  sessions: Record<string, StringMap>;
}

export const useVariablesStore = defineStore('variables', () => {
  const globals = ref<StringMap>({});
  const sessions = ref<Record<string, StringMap>>({});
  /** 每次写 +1。显示缓存的版本串依赖它，改了变量界面才会刷新 */
  const revision = ref(0);

  async function load(): Promise<void> {
    const [g, s, sessionIds] = await Promise.all([
      store.getItem<unknown>(K_GLOBAL),
      store.getItem<unknown>(K_SESSION),
      store.getItem<{ id?: string }[]>('adventureSessions'),
    ]);
    globals.value = asMap(g);
    const rawSessions = s && typeof s === 'object' && !Array.isArray(s)
      ? (s as Record<string, unknown>)
      : {};
    const cleaned: Record<string, StringMap> = {};
    for (const [id, val] of Object.entries(rawSessions)) cleaned[id] = asMap(val);
    // 顺手清掉已经删掉的会话残留（只清自己这张表，不碰会话数据）
    if (Array.isArray(sessionIds)) {
      const alive = new Set(sessionIds.map((x) => x?.id).filter(Boolean));
      for (const id of Object.keys(cleaned)) {
        if (!alive.has(id)) delete cleaned[id];
      }
    }
    sessions.value = cleaned;
    revision.value++;
  }

  async function save(): Promise<void> {
    await Promise.all([
      store.setItem(K_GLOBAL, globals.value),
      store.setItem(K_SESSION, sessions.value),
    ]);
  }

  /**
   * 取一份快照给引擎（纯数据，不带响应式代理）。
   * 不传 id 时自动取当前会话 —— 导出 / 显示这类拿不到 id 的调用方
   * 也能拿到正确的会话变量，不用各自再传一遍。
   */
  function snapshot(sessionId?: string): VariableTables {
    const id = sessionId ?? useAdventureStore().currentSessionId;
    return {
      session: id ? { ...(sessions.value[id] ?? {}) } : {},
      global: { ...globals.value },
    };
  }

  /** 缓存版本串：变量内容一变就变 */
  function versionFor(sessionId?: string): string {
    return `${revision.value}:${variableVersion(snapshot(sessionId))}`;
  }

  /**
   * 在**一次性入口**执行写宏并返回替换后的文本。
   * 只有真的改了表才存盘、才让缓存失效。
   */
  async function runWrites(text: string, sessionId?: string): Promise<string> {
    const r = applyVariableWrites(text, snapshot(sessionId));
    if (!r.changed) return r.text;
    globals.value = { ...r.tables.global };
    if (sessionId) {
      sessions.value = { ...sessions.value, [sessionId]: r.tables.session };
    }
    revision.value++;
    await save();
    return r.text;
  }

  function list(scope: VariableScope, sessionId?: string): [string, string][] {
    const t = scope === 'global'
      ? globals.value
      : (sessionId ? sessions.value[sessionId] ?? {} : {});
    return Object.entries(t).sort((a, b) => a[0].localeCompare(b[0]));
  }

  async function setVar(scope: VariableScope, sessionId: string | undefined, name: string, value: string) {
    const key = name.trim();
    if (!key) return;
    if (scope === 'global') {
      globals.value = { ...globals.value, [key]: value };
    } else {
      if (!sessionId) return;
      const cur = { ...(sessions.value[sessionId] ?? {}) };
      cur[key] = value;
      sessions.value = { ...sessions.value, [sessionId]: cur };
    }
    revision.value++;
    await save();
  }

  async function deleteVar(scope: VariableScope, sessionId: string | undefined, name: string) {
    if (scope === 'global') {
      const next = { ...globals.value };
      delete next[name];
      globals.value = next;
    } else {
      if (!sessionId) return;
      const next = { ...(sessions.value[sessionId] ?? {}) };
      delete next[name];
      sessions.value = { ...sessions.value, [sessionId]: next };
    }
    revision.value++;
    await save();
  }

  function exportBackup(): VariablesBackup {
    return {
      format: 'duihuazhushou-variables/1',
      global: { ...globals.value },
      sessions: Object.fromEntries(
        Object.entries(sessions.value).map(([id, m]) => [id, { ...m }]),
      ),
    };
  }

  /**
   * 导入：既认本项目的备份格式，也认一个「扁平 名称→值」对象
   * （从别处复制变量时最常拿到这种）。后者并入当前作用域。
   */
  async function importBackup(raw: string, scope: VariableScope, sessionId?: string): Promise<number> {
    const data: unknown = JSON.parse(raw);
    let n = 0;
    if (data && typeof data === 'object' && !Array.isArray(data) &&
        (data as VariablesBackup).format === 'duihuazhushou-variables/1') {
      const b = data as VariablesBackup;
      globals.value = { ...globals.value, ...asMap(b.global) };
      n += Object.keys(asMap(b.global)).length;
      if (b.sessions && typeof b.sessions === 'object') {
        const merged = { ...sessions.value };
        for (const [id, m] of Object.entries(b.sessions)) {
          merged[id] = { ...(merged[id] ?? {}), ...asMap(m) };
          n += Object.keys(asMap(m)).length;
        }
        sessions.value = merged;
      }
    } else {
      const flat = asMap(data);
      if (scope === 'global') {
        globals.value = { ...globals.value, ...flat };
      } else if (sessionId) {
        sessions.value = {
          ...sessions.value,
          [sessionId]: { ...(sessions.value[sessionId] ?? {}), ...flat },
        };
      }
      n = Object.keys(flat).length;
    }
    if (n) {
      revision.value++;
      await save();
    }
    return n;
  }

  return {
    globals, sessions, revision,
    load, save, snapshot, versionFor, runWrites,
    list, setVar, deleteVar, exportBackup, importBackup,
  };
});
