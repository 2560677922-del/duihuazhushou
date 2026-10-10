import { defineStore } from 'pinia';
import { store, genId } from '../lib/storage';
import { CharacterCard, OpenAIPreset } from 'parsecard';
import { detectKind, splitByKind, parseItem } from '../engine/stImport';
import type { AssetKind, ImportedItem } from '../engine/stImport';
import { createRegexGroup, toScriptItem, flattenForEngine } from '../engine/regexGroups';

// 资产 Pinia store：管理角色卡 / 预设 / 世界书 / 正则，以及当前选中的资产。
// 导入时用 parsecard 解析为统一格式后存 IndexedDB，使用时返回 parsecard
// 实例或 toJSON 输出供引擎消费。

export interface AssetsState {
  characters: any[];
  worldBooks: any[];
  /**
   * 正则**分组**。
   * ST 导出的正则本身就是一个组（里面装着很多条脚本），
   * 所以这里存的是组，脚本在 group.scripts 里。旧的扁平脚本会在 load 时自动包成组。
   */
  regexGroups: any[];
  presets: any[];
  currentCharacterId?: string;
  currentWorldBookIds: string[];
  /** 启用的正则组 id */
  currentRegexGroupIds: string[];
  currentPresetId?: string;
}

export type { AssetKind, ImportedItem };

function mark(raw: any, kind: string): any {
  const obj = raw as any;
  if (!obj._uid) obj._uid = genId(kind);
  if (!obj._kind) obj._kind = kind;
  if (!obj._importedAt) obj._importedAt = Date.now();
  return obj;
}

export const useAssetsStore = defineStore('assets', {
  state: (): AssetsState => ({
    characters: [],
    worldBooks: [],
    regexGroups: [],
    presets: [],
    currentCharacterId: undefined,
    currentWorldBookIds: [],
    currentRegexGroupIds: [],
    currentPresetId: undefined,
  }),
  getters: {
    /** 启用中的正则组压平成引擎要的脚本列表 */
    enabledRegexScripts(state): any[] {
      const groups = state.regexGroups.filter((g: any) =>
        state.currentRegexGroupIds.includes(g._uid),
      );
      return flattenForEngine(groups);
    },
    /** 正则脚本总条数（跨组统计，给界面显示用） */
    regexScriptCount(state): number {
      return state.regexGroups.reduce((n: number, g: any) => n + (g.scripts?.length ?? 0), 0);
    },
  },
  actions: {
    async load() {
      const [c, w, groups, flatLegacy, p, cid, wids, gids, pid] = await Promise.all([
        store.getItem<any[]>('characters'),
        store.getItem<any[]>('worldBooks'),
        store.getItem<any[]>('regexGroups'),
        // 旧版本的扁平脚本，迁移用
        store.getItem<any[]>('regexScripts'),
        store.getItem<any[]>('presets'),
        store.getItem<string | undefined>('currentCharacterId'),
        store.getItem<string[]>('currentWorldBookIds'),
        store.getItem<string[]>('currentRegexGroupIds'),
        store.getItem<string | undefined>('currentPresetId'),
      ]);
      this.characters = c ?? [];
      this.worldBooks = w ?? [];
      this.presets = p ?? [];
      this.currentCharacterId = cid ?? undefined;
      this.currentWorldBookIds = wids ?? [];
      this.currentPresetId = pid ?? undefined;

      // ---- 正则：优先读新结构，否则把旧的扁平脚本迁移成组 ----
      let migrated = false;
      if (Array.isArray(groups) && groups.length > 0) {
        this.regexGroups = groups;
        this.currentRegexGroupIds = gids ?? groups.map((g: any) => g._uid);
      } else if (Array.isArray(flatLegacy) && flatLegacy.length > 0) {
        // 迁移：每条旧脚本各自包成一个组，保持原样可用
        this.regexGroups = flatLegacy.map((s: any) => {
          const g = createRegexGroup(s.scriptName || s.name || '正则组', 'migrated');
          g.scripts = [toScriptItem(s)];
          return g;
        });
        this.currentRegexGroupIds = this.regexGroups.map((g: any) => g._uid);
        migrated = true;
      } else {
        this.regexGroups = [];
        this.currentRegexGroupIds = [];
      }
      // 清掉旧键，避免下次又把已经迁移过的读回来
      if (migrated) {
        await store.setItem('regexScripts', []);
        await store.setItem('currentRegexScriptIds', []);
      }

      // 修正悬空的启用项
      this.currentRegexGroupIds = this.currentRegexGroupIds.filter((id: string) =>
        this.regexGroups.some((g: any) => g._uid === id),
      );
    },
    async save() {
      await Promise.all([
        store.setItem('characters', this.characters),
        store.setItem('worldBooks', this.worldBooks),
        store.setItem('regexGroups', this.regexGroups),
        store.setItem('presets', this.presets),
        store.setItem('currentCharacterId', this.currentCharacterId),
        store.setItem('currentWorldBookIds', this.currentWorldBookIds),
        store.setItem('currentRegexGroupIds', this.currentRegexGroupIds),
        store.setItem('currentPresetId', this.currentPresetId),
      ]);
    },

    // --- 导入：parsecard 解析 → 标记 _uid → 存库 ---
    async importCharacter(raw: any): Promise<any> {
      const card = CharacterCard.fromJSON(raw);
      const obj = mark(card.toJSON(), 'character');
      this.characters.push(obj);
      if (!this.currentCharacterId) this.currentCharacterId = obj._uid;
      await this.save();
      return obj;
    },
    async importWorldBook(raw: any): Promise<any> {
      // 走统一归一化：entries 对象索引 → 数组，keys/key 统一，
      // 并保住 order/position/depth/probability/constant 等编辑字段。
      const outcome = parseItem('worldbook', raw, this.worldBooks.length);
      if (!outcome.parsed) throw new Error(outcome.error || '世界书解析失败');
      const obj = mark(outcome.parsed, 'worldbook');
      this.worldBooks.push(obj);
      if (!this.currentWorldBookIds.includes(obj._uid)) this.currentWorldBookIds.push(obj._uid);
      await this.save();
      return obj;
    },
    async importRegexScript(raw: any): Promise<any> {
      // 单条正则导入：包成一个同名组，保持结构统一。
      const outcome = parseItem('regex', raw, this.regexScriptCount);
      if (!outcome.parsed) throw new Error(outcome.error || '正则解析失败');
      const g = createRegexGroup(outcome.parsed.scriptName || '正则组', 'single');
      g.scripts = [toScriptItem(outcome.parsed)];
      g._uid = genId('rg');
      this.regexGroups.push(g);
      if (!this.currentRegexGroupIds.includes(g._uid)) this.currentRegexGroupIds.push(g._uid);
      await this.save();
      return g;
    },
    async importPreset(raw: any): Promise<any> {
      // 有的预设把 prompts 塞在 preset 子对象里，先拉平再交给 parsecard
      const src = !raw?.prompts && raw?.preset?.prompts ? { ...raw, prompts: raw.preset.prompts } : raw;
      const p = OpenAIPreset.fromJSON(src);
      const obj = mark(p.toJSON(), 'preset');
      this.presets.push(obj);
      if (!this.currentPresetId) this.currentPresetId = obj._uid;
      await this.save();
      return obj;
    },

    /**
     * 统一导入：随便丢一个 SillyTavern 的 JSON 进来。
     *
     * 自动识别是预设 / 世界书 / 正则 / 角色卡，也支持：
     *  - 一个数组里混装多种资产
     *  - { worldBooks: [...] } 这类容器对象
     *  - ST 世界书 entries 为对象索引的格式（之前导入没反应的根因）
     *  - ST 正则的嵌套 script 格式
     */
    async importAnyJson(raw: unknown): Promise<ImportedItem[]> {
      const buckets = splitByKind(raw);
      const results: ImportedItem[] = [];

      // 同一次导入里的正则合成一个组。
      // 名字优先用这次导入的预设名（"XXX 的正则"），没有就叫「导入的正则」。
      const regexItems: { parsed: any; name: string }[] = [];
      let presetNameForGroup = '';

      for (const bucket of buckets) {
        if (bucket.kind === 'unknown') {
          for (const [i] of bucket.items.entries()) {
            const d = detectKind(bucket.items[i]);
            results.push({
              kind: 'unknown',
              name: d.name || `第 ${i + 1} 项`,
              ok: false,
              error: `无法识别这个 JSON。${d.reason}`,
            });
          }
          continue;
        }

        for (const [i, item] of bucket.items.entries()) {
          const outcome = parseItem(bucket.kind, item, i);
          if (!outcome.parsed) {
            results.push({
              kind: bucket.kind,
              name: outcome.name,
              ok: false,
              error: outcome.error,
              notes: outcome.notes,
            });
            continue;
          }

          if (bucket.kind === 'regex') {
            // 先攒着，等知道预设名之后再统一建组
            regexItems.push({ parsed: outcome.parsed, name: outcome.name });
            continue;
          }

          const obj = mark(outcome.parsed, bucket.kind);
          if (bucket.kind === 'character') {
            this.characters.push(obj);
            if (!this.currentCharacterId) this.currentCharacterId = obj._uid;
          } else if (bucket.kind === 'worldbook') {
            this.worldBooks.push(obj);
            if (!this.currentWorldBookIds.includes(obj._uid))
              this.currentWorldBookIds.push(obj._uid);
          } else if (bucket.kind === 'preset') {
            this.presets.push(obj);
            if (!this.currentPresetId) this.currentPresetId = obj._uid;
            if (!presetNameForGroup) {
              presetNameForGroup = outcome.parsed.name || outcome.parsed.preset_name || '';
            }
          }
          results.push({
            kind: bucket.kind,
            name: outcome.name,
            ok: true,
            notes: outcome.notes,
          });
        }
      }

      // ---- 正则统一入组 ----
      if (regexItems.length > 0) {
        const label = presetNameForGroup
          ? `${presetNameForGroup} 的正则`
          : regexItems.length === 1
            ? regexItems[0].name
            : `导入的正则（${regexItems.length} 条）`;
        const g = createRegexGroup(label, 'import');
        g._uid = genId('rg');
        g.scripts = regexItems.map((r) => toScriptItem(r.parsed));
        this.regexGroups.push(g);
        if (!this.currentRegexGroupIds.includes(g._uid)) this.currentRegexGroupIds.push(g._uid);

        results.push({
          kind: 'regex',
          name: `${label}（${g.scripts.length} 条脚本）`,
          ok: true,
          notes: [`${g.scripts.length} 条正则已合并为一个组`],
        });
      }

      await this.save();
      return results;
    },

    /** 导入前的预览：只识别不落库，让用户确认后再决定 */
    previewAnyJson(raw: unknown): { kind: AssetKind; count: number }[] {
      return splitByKind(raw).map((b) => ({ kind: b.kind, count: b.items.length }));
    },

    // --- 选择当前资产 ---
    async setCurrentCharacter(uid?: string) {
      this.currentCharacterId = uid;
      await this.save();
    },
    async setCurrentPreset(uid?: string) {
      this.currentPresetId = uid;
      await this.save();
    },
    async toggleWorldBook(uid: string, on: boolean) {
      if (on && !this.currentWorldBookIds.includes(uid)) this.currentWorldBookIds.push(uid);
      if (!on) this.currentWorldBookIds = this.currentWorldBookIds.filter((x) => x !== uid);
      await this.save();
    },
    async toggleRegexScript(uid: string, on: boolean) {
      await this.toggleRegexGroup(uid, on);
    },

    // --- 正则分组操作 ---
    async toggleRegexGroup(uid: string, on: boolean) {
      if (on && !this.currentRegexGroupIds.includes(uid)) this.currentRegexGroupIds.push(uid);
      if (!on) this.currentRegexGroupIds = this.currentRegexGroupIds.filter((x) => x !== uid);
      await this.save();
    },
    async removeRegexGroup(uid: string) {
      this.regexGroups = this.regexGroups.filter((g: any) => g._uid !== uid);
      this.currentRegexGroupIds = this.currentRegexGroupIds.filter((x: string) => x !== uid);
      await this.save();
    },
    /** 组内某条脚本的开关 */
    async toggleRegexScriptInGroup(groupUid: string, scriptId: string, on: boolean) {
      const g = this.regexGroups.find((x: any) => x._uid === groupUid);
      if (!g) return;
      const s = (g.scripts || []).find((x: any) => x.id === scriptId);
      if (s) s.disabled = !on;
      g.updatedAt = Date.now();
      await this.save();
    },

    // --- 获取当前选中的资产（给引擎用） ---
    getCurrentCharacter(): any | undefined {
      return this.characters.find((c) => c._uid === this.currentCharacterId);
    },
    getCurrentWorldBooks(): any[] {
      return this.worldBooks.filter((w) => this.currentWorldBookIds.includes(w._uid));
    },
    /**
     * 给引擎用的正则是**压平后的脚本列表**。
     * 引擎只认扁平数组，分组是 UI 层的组织方式。
     */
    getCurrentRegexScripts(): any[] {
      const groups = this.regexGroups.filter((g: any) =>
        this.currentRegexGroupIds.includes(g._uid),
      );
      return flattenForEngine(groups);
    },
    getCurrentPreset(): any | undefined {
      return this.presets.find((p) => p._uid === this.currentPresetId);
    },

    // --- 删除资产 ---
    async removeCharacter(uid: string) {
      this.characters = this.characters.filter((c) => c._uid !== uid);
      if (this.currentCharacterId === uid) this.currentCharacterId = undefined;
      await this.save();
    },
    async removeWorldBook(uid: string) {
      this.worldBooks = this.worldBooks.filter((w) => w._uid !== uid);
      this.currentWorldBookIds = this.currentWorldBookIds.filter((x) => x !== uid);
      await this.save();
    },
    async removeRegexScript(uid: string) {
      // 兼容旧调用：现在传进来的是组 id
      await this.removeRegexGroup(uid);
    },
    async removePreset(uid: string) {
      this.presets = this.presets.filter((p) => p._uid !== uid);
      if (this.currentPresetId === uid) this.currentPresetId = undefined;
      await this.save();
    },
  },
});
