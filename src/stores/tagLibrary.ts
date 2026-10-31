import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { store } from '../lib/storage';
import type { TagEntry, ImportedTagLibrary } from '../engine/tagLibrary';
import { parseTagLibrary } from '../engine/tagLibrary';

// 标签库：完整 Danbooru / NovelAI 标签表
//
// 数据来源：社区标签库（chami_tavern-scene-plugin 的 tags SQL）经
// scripts/parse-tag-sql.mjs 转成紧凑 JSON，随应用一起发布在 public/tags/。
// 共 3600+ 个标签，带中文释义、一级/二级分类，约 330KB。
//
// 存放策略：首次启动时把 JSON 写进本地存储（会随落盘文件一起备份），
// 之后直接读本地；这样离线也能用，且用户导入的标签库可以叠加进来。

interface FullTag {
  tag: string;
  zh: string;
  group: string;
  sub: string;
  /** 下划线原写法（源数据是 Danbooru 风格，NovelAI 用空格，两种都留着） */
  alt?: string[];
  /**
   * 同一条标签的**其它释义与分类**。
   *
   * 一个标签常被归到多个分类下，各处译法不同（`maid` = 女仆 / 女仆装）。
   * 早期的解析脚本对重名"只留第一条"，等于扔掉一半信息；
   * 现在全部收在这里，检索时任意一个都能命中。
   */
  also?: string[];
  /** 预先拼好的可搜索文本（含 tag/zh/alt/also），避免每次搜索现拼 */
  hay?: string;
}

const BUNDLED_URL = '/tags/novelai-tags.json';
const STORE_KEY = 'tagLibraryFull';

export const useTagLibraryStore = defineStore('tagLibrary', () => {
  const tags = ref<FullTag[]>([]);
  /** 搜索文本是否已经构建过（延迟到第一次搜索） */
  const hayBuilt = ref(false);
  const groups = ref<string[]>([]);
  const subGroups = ref<string[]>([]);
  const loaded = ref(false);
  const loading = ref(false);
  const error = ref('');
  const source = ref<'bundled' | 'imported' | 'none'>('none');

  /** 用户额外导入的标签库（叠加，不覆盖） */
  const extraTags = ref<TagEntry[]>([]);

  const count = computed(() => tags.value.length);

  /** 按一级分类统计数量 */
  const groupStats = computed(() => {
    const m = new Map<string, number>();
    for (const t of tags.value) {
      const key = t.group || '未分类';
      m.set(key, (m.get(key) || 0) + 1);
    }
    return [...m.entries()]
      .map(([name, n]) => ({ name, count: n }))
      .sort((a, b) => b.count - a.count);
  });

  /** 某个一级分类下的二级分类 */
  function subGroupsOf(group: string): string[] {
    const s = new Set<string>();
    for (const t of tags.value) if (t.group === group && t.sub) s.add(t.sub);
    return [...s];
  }

  async function load() {
    if (loaded.value || loading.value) return;
    loading.value = true;
    error.value = '';
    try {
      /*
       * 内置标签库的版本号。
       *
       * 为什么必须有：标签库第一次用会缓存到本地存储，之后直接读缓存。
       * 只改 public/tags/novelai-tags.json 的话，**老用户永远读到旧缓存** ——
       * 表现为"标签库怎么不全"、"明明补了还是搜不到"。
       *
       * 改了那个 JSON 就把这个数字 +1，缓存会自动重建。
       *
       * 6：重建标签库（scripts/rebuild-tags.mjs）—— 合并下划线/空格两种写法的
       *    重复标签、统一二级分类、把常用标签排到前面、头发拆成发型+发色。
       */
      const BUNDLED_VERSION = 6;

      // 1. 先看本地有没有存过，且版本一致
      const cached = await store.getItem<FullTag[]>(STORE_KEY);
      const cachedVer = await store.getItem<number>(STORE_KEY + 'Version');
      if (cached && cached.length > 0 && cachedVer === BUNDLED_VERSION) {
        apply(cached);
        source.value = 'bundled';
        loaded.value = true;
        return;
      }

      // 2. 没有（或版本旧了）就取随应用发布的 JSON
      const r = await fetch(BUNDLED_URL);
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      const json = (await r.json()) as FullTag[];
      if (!Array.isArray(json) || json.length === 0) throw new Error('标签数据为空');
      apply(json);
      /*
       * 只记版本号，**不把 JSON 再写回存储**。
       *
       * 以前这里 `setItem(STORE_KEY, json)`，两个问题：
       *   1. 标签库里**一张图片都没有**，但 setItem 会先跑一遍 externalize()，
       *      把 22616 × 5 = 11 万个字段全检查一遍找 dataURL —— 纯浪费。
       *   2. 写完还要把 1.5MB 塞进 IndexedDB。
       * 这两步加起来就是进标签页那十几秒的主要来源。
       *
       * 现在只写一个小小的版本号；下次进来版本对得上就直接用内存里的数据。
       * 万一缓存丢了，从随应用发布的 JSON 重新取也只要十几毫秒。
       */
      await store.setItem(STORE_KEY + 'Version', BUNDLED_VERSION);
      source.value = 'bundled';
      loaded.value = true;
    } catch (e) {
      error.value = `标签库加载失败：${e instanceof Error ? e.message : String(e)}`;
    } finally {
      loading.value = false;
    }
  }

  function apply(list: FullTag[]) {
    tags.value = list.map((t) => {
      const alt = Array.isArray(t.alt) ? t.alt.map(String) : [];
      const also = Array.isArray(t.also) ? t.also.map(String) : [];
      return {
        tag: String(t.tag || ''),
        zh: String(t.zh || ''),
        group: String(t.group || ''),
        sub: String(t.sub || ''),
        alt,
        also,
        /*
         * 搜索文本**延迟构建**（见 ensureHay）。
         *
         * 以前是加载时就给 22616 条各拼一个字符串。实测这一下就是
         * 进标签页卡十几秒的元凶之一 —— 而绝大多数人打开标签页
         * 只是浏览分类、根本不会搜索，白拼一次。
         * 现在留空，第一次真正搜索时才补。
         */
        hay: '',
      };
    });
    hayBuilt.value = false;
    const g = new Set<string>();
    const s = new Set<string>();
    for (const t of tags.value) {
      if (t.group) g.add(t.group);
      if (t.sub) s.add(t.sub);
    }
    groups.value = [...g];
    subGroups.value = [...s];
  }

  /**
   * 第一次真正搜索时才把 22616 条的搜索文本拼出来。
   *
   * 只做一次，之后一直复用。拼一次约几十毫秒 —— 放在"用户敲下第一个字"
   * 那个瞬间是可以接受的，放在"打开页面"就不是了。
   */
  function ensureHay() {
    if (hayBuilt.value) return;
    const list = tags.value;
    for (let i = 0; i < list.length; i++) {
      const t = list[i];
      t.hay = [t.tag, t.zh, ...(t.alt || []), ...(t.also || []), t.group, t.sub]
        .filter(Boolean)
        .join(' ')
        .toLowerCase();
    }
    hayBuilt.value = true;
  }

  /**
   * 检索标签。中英文都能搜：
   *  - 英文按子串匹配 tag（含下划线原写法）
   *  - 中文匹配释义（同一条标签的多个释义都能命中）
   *  - 分类名也参与匹配（搜"汉服"能出汉服分类下的标签）
   * 排序：完全匹配 > 前缀匹配 > 子串匹配，再按标签长度（短的更通用）
   */
  function search(query: string, limit = 60, groupFilter?: string): FullTag[] {
    const q = query.trim().toLowerCase();
    if (!q) return [];
    ensureHay();
    const raw = query.trim();
    const pool = tags.value.filter((t) => !groupFilter || t.group === groupFilter);
    const scored: { t: FullTag; score: number }[] = [];

    for (const t of pool) {
      const tag = t.tag.toLowerCase();
      const zh = t.zh || '';
      let score = -1;
      if (tag === q || zh === raw) score = 100;
      else if (tag.startsWith(q)) score = 80;
      else if (zh.startsWith(raw)) score = 70;
      else if (tag.includes(q)) score = 50;
      else if (zh.includes(raw)) score = 40;
      // 备用写法 / 其它释义 / 分类名 命中，分低一些但不该漏
      else if ((t.hay || '').includes(q)) score = 25;
      if (score < 0) continue;
      // 越短越通用，稍微加权
      score -= Math.min(20, tag.length / 4);
      scored.push({ t, score });
    }

    scored.sort((a, b) => b.score - a.score || a.t.tag.localeCompare(b.t.tag));
    return scored.slice(0, limit).map((s) => s.t);
  }

  /** 浏览某个分类下的标签 */
  function browse(group: string, sub?: string, limit = 200): FullTag[] {
    return tags.value
      .filter((t) => t.group === group && (!sub || t.sub === sub))
      .slice(0, limit);
  }

  /** 用户额外导入一个标签库文件 */
  async function importExtra(raw: unknown, name: string) {
    const lib: ImportedTagLibrary = parseTagLibrary(raw, name);
    extraTags.value = [...extraTags.value, ...lib.tags];
    await store.setItem('importedTagLibraries', extraTags.value);
    return lib;
  }

  async function loadExtra() {
    const saved = await store.getItem<TagEntry[]>('importedTagLibraries');
    if (saved) extraTags.value = saved;
  }

  async function clearExtra() {
    extraTags.value = [];
    await store.setItem('importedTagLibraries', []);
  }

  return {
    tags,
    groups,
    subGroups,
    groupStats,
    loaded,
    loading,
    error,
    source,
    extraTags,
    count,
    subGroupsOf,
    load,
    loadExtra,
    search,
    browse,
    importExtra,
    clearExtra,
  };
});
