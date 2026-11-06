import type { ChatMessage, WorldbookHit } from './types';

// 世界书触发逻辑：参考 GengBi 的注入逻辑 + parsecard 的 WorldBook/WorldBookEntry。
//
// 算法：
//   1. 收集搜索文本 = 最近 N 条消息内容 + 当前用户输入（默认 N=10）
//   2. 遍历所有世界书的所有条目：
//      - 常驻条目（constant）→ 直接触发
//      - 否则按主关键词命中（caseSensitive / wholeWord 由条目决定）
//   3. selective_logic：AND_ANY / NOT_ALL / NOT_ANY / AND_ALL（次关键词）
//   4. 概率触发（probability 0-100）
//   5. 按 (position, depth) 排序，交由 assembler 注入到消息数组
//
// parsecard 会把独立世界书与内嵌 character_book 统一为同一格式，
// 扩展字段（case_sensitive/whole_word/probability/position/depth/role）
// 在统一格式里直接挂在条目上。这里对两种字段命名都做兜底，
// 兼容 parsecard 输出与原始 ST JSON。

export interface WorldbookTriggerOptions {
  /** 最近 N 条消息进入扫描（默认 10） */
  scanDepth?: number;
  /** 是否把 currentUserInput 纳入扫描文本（默认 true） */
  includeUserInput?: boolean;
  /** 注入随机数生成器，便于测试可重现 */
  rng?: () => number;
}

// SillyTavern SelectiveLogic
const SEL_AND_ANY = 0;
const SEL_NOT_ALL = 1;
const SEL_NOT_ANY = 2;
const SEL_AND_ALL = 3;

/** 关键词统一成字符串数组（兼容 keys 字符串/数组、key 字符串/对象数组） */
export function normalizeEntryKeys(entry: any): string[] {
  const raw = entry?.keys ?? entry?.key ?? [];
  return (Array.isArray(raw) ? raw : [raw])
    .map((k: any) => (typeof k === 'string' ? k : (k?.key ?? k?.keys ?? k?.value ?? '')))
    .map((k: any) => String(k).trim())
    .filter(Boolean);
}

/** ST 的 entries 可能是数组，也可能是以 uid 为键的对象；统一成数组 */
export function normalizeEntryList(entries: any): any[] {
  if (Array.isArray(entries)) return entries;
  if (entries && typeof entries === 'object') {
    return Object.entries(entries).map(([uid, e]: [string, any]) => ({ uid, ...e }));
  }
  return [];
}

/**
 * 条目是否被关掉。
 *
 * 踩过的坑（用户报的）：UI 上那个「小对号」取消勾选后写的是 `disabled`，
 * 而这里以前只认 `disable` 和 `enabled` —— 于是**取消勾选完全不起作用**，
 * 条目照样按关键词注入。用户只能把关键词改成不相关的词来绕开。
 *
 * 现在三种写法都认，并且容忍 0/1 和字符串（不同来源的 JSON 写法不一样）：
 *   disabled: true / 1 / 'true'   → 关（本应用 UI 写的）
 *   disable:  true / 1 / 'true'   → 关（SillyTavern 某些版本）
 *   enabled:  false / 0 / 'false' → 关（SillyTavern 标准）
 */
export function isEntryDisabled(entry: any): boolean {
  if (!entry) return false;
  const truthy = (v: any) => v === true || v === 1 || v === 'true' || v === '1';
  const falsy = (v: any) => v === false || v === 0 || v === 'false' || v === '0';
  if (truthy(entry.disabled) || truthy(entry.disable)) return true;
  // enabled 只在**明确**为假时才算关；缺省视为开
  if (falsy(entry.enabled)) return true;
  return false;
}

export function triggerWorldbook(
  worldBooks: any[],
  chatHistory: ChatMessage[],
  currentUserInput?: string,
  options: WorldbookTriggerOptions = {},
): { hits: WorldbookHit[] } {
  const scanDepth = options.scanDepth ?? 10;
  const rng = options.rng ?? Math.random;
  const hits: WorldbookHit[] = [];

  const recent = chatHistory.slice(-scanDepth);
  const searchText = [
    ...recent.map((m) => m.content),
    ...(options.includeUserInput === false ? [] : [currentUserInput || '']),
  ].join('\n');

  for (const book of worldBooks) {
    const bookName = book?.name || 'WorldBook';
    const entriesRaw = book?.entries;
    const list: any[] = Array.isArray(entriesRaw)
      ? entriesRaw
      : entriesRaw
        ? Object.values(entriesRaw)
        : [];

    for (const entry of list) {
      // 关掉的条目一律跳过（三种字段写法都认，见 isEntryDisabled 的说明）
      if (isEntryDisabled(entry)) continue;
      const ext = entry.extensions ?? {};

      // 关键词有几种写法，必须全部认：
      //   keys: ["魔法"]            ST 内部格式
      //   key:  ["魔法"]            部分导出
      //   key:  [{ key: "魔法" }]    ST 导出 / parsecard 输出
      // 最后一种如果直接 String() 会变成 "[object Object]"，导致永远匹配不上。
      const rawKeys = entry.keys ?? entry.key ?? ext.keys ?? ext.key ?? [];
      const keys: string[] = (Array.isArray(rawKeys) ? rawKeys : [rawKeys])
        .map((k: any) =>
          typeof k === 'string' ? k : (k?.key ?? k?.keys ?? k?.value ?? ''),
        )
        .map((k: any) => String(k).trim())
        .filter(Boolean);
      const isConstant = ext.constant ?? entry.constant ?? false;

      // 没关键词且非常驻 → 跳过（Phase 1 不实现 vectorized）
      if (keys.length === 0 && !isConstant) continue;

      const caseSensitive = ext.case_sensitive ?? entry.case_sensitive ?? false;
      const wholeWord = ext.whole_word ?? entry.whole_word ?? false;
      const position = ext.position ?? entry.position ?? 0;
      const depth = ext.depth ?? entry.depth ?? 0;
      const role = ext.role ?? entry.role ?? 0;
      const probability = ext.probability ?? entry.probability ?? 100;
      const selectiveLogic = ext.selective_logic ?? entry.selective_logic ?? SEL_AND_ANY;
      const secondaryKeys: string[] = (
        ext.secondary_keys ??
        entry.secondary_keys ??
        entry.secondaryKeys ??
        entry.keysecondary ??
        []
      )
        .map((k: any) => String(k))
        .filter(Boolean);

      const content = entry.content || '';
      const comment = entry.comment || `Entry ${entry.uid ?? entry.id ?? '?'}`;
      const uid = String(entry.uid ?? entry.id ?? Math.random().toString(36).slice(2));

      let triggeredKeys: string[] = [];
      let matchText = '';

      if (isConstant) {
        hits.push({
          uid,
          bookName,
          comment,
          keys,
          content,
          position,
          depth,
          role,
          reason: 'constant',
          triggeredKeys: keys,
          matchText: '(constant)',
        });
        continue;
      }

      for (const k of keys) {
        const mk = matchKey(searchText, k, caseSensitive, wholeWord);
        if (mk) {
          triggeredKeys = [k];
          matchText = k;
          break; // AND_ANY：任一主关键词命中即触发
        }
      }
      if (triggeredKeys.length === 0) continue;

      // selective 逻辑（次关键词）
      const selOk = testSelective(selectiveLogic, secondaryKeys, searchText, caseSensitive, wholeWord);
      if (!selOk) continue;

      // 概率触发
      if (rng() * 100 > probability) continue;

      hits.push({
        uid,
        bookName,
        comment,
        keys,
        content,
        position,
        depth,
        role,
        reason: 'keyword',
        triggeredKeys,
        matchText,
      });
    }
  }

  return { hits };
}

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

// 判断是否需要 \b 词边界。CJK 字符没有词边界概念，
// wholeWord 模式下对纯 CJK 关键词退化为子串匹配。
function needsWordBoundary(key: string): boolean {
  return /[A-Za-z0-9_]/.test(key);
}

function matchKey(text: string, key: string, caseSensitive: boolean, wholeWord: boolean): boolean {
  if (!key) return false;
  const search = caseSensitive ? text : text.toLowerCase();
  const needle = caseSensitive ? key : key.toLowerCase();
  // wholeWord 仅在关键词含 ASCII 词字符时加 \b，CJK 退化为子串
  const useBoundary = wholeWord && needsWordBoundary(key);
  const pattern = useBoundary ? `\\b${escapeRegExp(needle)}\\b` : escapeRegExp(needle);
  try {
    const re = new RegExp(pattern, caseSensitive ? 'g' : 'gi');
    return re.test(search);
  } catch {
    return false;
  }
}

function testSelective(
  logic: number,
  selKeys: string[],
  text: string,
  caseSensitive: boolean,
  wholeWord: boolean,
): boolean {
  if (selKeys.length === 0) return true;
  const matched = selKeys.filter((k) => matchKey(text, k, caseSensitive, wholeWord));
  switch (logic) {
    case SEL_AND_ANY:
      return matched.length > 0;
    case SEL_AND_ALL:
      return matched.length === selKeys.length;
    case SEL_NOT_ANY:
      return matched.length === 0;
    case SEL_NOT_ALL:
      return matched.length < selKeys.length;
    default:
      return true;
  }
}
