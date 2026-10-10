// 统一的 SillyTavern 资产导入。
// ---------------------------------------------------------------------------
// 为什么需要它：ST 生态里的导出文件格式非常多，同一个 JSON 可能是
// 预设 / 世界书 / 正则 / 角色卡，而且各自还有好几代格式（字段名不一样）。
// 之前每种资产一个导入入口、还要手动选类型，导入一个预设就卡住了。
//
// 这里的做法：
//  1. 结构识别（detectKind）：看几个关键字段就能判断是哪种资产；
//  2. 结构归一（normalize*）：把各代字段名收敛成 parsecard 认识的样子；
//  3. 批量导入（importAnyJson）：一个 JSON 里可以混装多种资产，
//     数组会逐个处理，全部导入后返回逐条结果。

import { CharacterCard, WorldBook, RegexScript, OpenAIPreset } from 'parsecard';

export type AssetKind = 'preset' | 'worldbook' | 'regex' | 'character' | 'unknown';

export interface DetectResult {
  kind: AssetKind;
  /** 判断依据，便于在界面上解释"为什么它被当成世界书" */
  reason: string;
  /** 名称，导入预览时显示 */
  name?: string;
  /** 条目数量（世界书/正则集合） */
  count?: number;
}

export interface ImportedItem {
  kind: AssetKind;
  name: string;
  ok: boolean;
  error?: string;
  /** 归一化过程中做的修补，值得告诉用户 */
  notes?: string[];
}

/** 从各种可能的位置取出"实体数组" */
function asArray(value: unknown): any[] {
  if (Array.isArray(value)) return value;
  if (value && typeof value === 'object') return [value];
  return [];
}

/** 宽松取名 */
function pickName(raw: any, fallback: string): string {
  return (
    raw?.name ||
    raw?.scriptName ||
    raw?.character_name ||
    raw?.char_name ||
    raw?.data?.name ||
    raw?.data?.character_name ||
    raw?.comment ||
    fallback
  );
}

// ---------------------------------------------------------------------------
// 结构识别
// ---------------------------------------------------------------------------

/**
 * 判断一段 JSON 属于哪种资产。
 * 顺序很重要：角色卡里也带 `data`，预设里也带 `prompts`，所以先判特征最强的。
 */
export function detectKind(raw: any): DetectResult {
  if (!raw || typeof raw !== 'object') {
    return { kind: 'unknown', reason: '不是对象或数组' };
  }

  // 数组：看元素特征；混装数组由调用方拆分
  if (Array.isArray(raw)) {
    if (raw.length === 0) return { kind: 'unknown', reason: '空数组' };
    const inner = detectKind(raw[0]);
    return { ...inner, count: raw.length, reason: `数组（${raw.length} 项）· ${inner.reason}` };
  }

  // 角色卡 V2：{ spec: 'chara_card_v2', data: { name, description, ... } }
  if (raw.spec && typeof raw.spec === 'string' && raw.spec.includes('chara_card')) {
    return {
      kind: 'character',
      reason: `角色卡 V2（spec=${raw.spec}）`,
      name: pickName(raw, '未命名角色'),
    };
  }
  if (raw.data && typeof raw.data === 'object') {
    const d = raw.data;
    const cardish =
      d.description !== undefined ||
      d.first_mes !== undefined ||
      d.personality !== undefined ||
      d.mes_example !== undefined;
    if (cardish) {
      return {
        kind: 'character',
        reason: '角色卡（含 data.description / first_mes）',
        name: pickName(raw, '未命名角色'),
      };
    }
  }
  // 角色卡 V1：字段直接铺在顶层
  if (
    raw.description !== undefined &&
    (raw.first_mes !== undefined || raw.personality !== undefined || raw.scenario !== undefined)
  ) {
    return {
      kind: 'character',
      reason: '角色卡 V1（顶层 description + first_mes）',
      name: pickName(raw, '未命名角色'),
    };
  }

  // 世界书：entries 是数组或对象；或顶层直接有 key+content 列表
  const hasEntries = raw.entries !== undefined && raw.entries !== null;
  if (hasEntries) {
    const entries = asArray(raw.entries);
    const looksLikeWB =
      !Array.isArray(raw.entries) || // entries 是对象（ST 按 uid 索引）
      entries.some((e: any) => e && (e.content !== undefined || e.key !== undefined));
    if (looksLikeWB) {
      return {
        kind: 'worldbook',
        reason: Array.isArray(raw.entries)
          ? '世界书（entries 数组）'
          : '世界书（entries 为对象索引）',
        name: pickName(raw, '未命名世界书'),
        count: Array.isArray(raw.entries) ? raw.entries.length : Object.keys(raw.entries).length,
      };
    }
  }

  // 预设：prompts / prompt_order / temperature 等
  if (
    raw.prompts !== undefined ||
    raw.prompt_order !== undefined ||
    raw.promptOrder !== undefined ||
    (raw.temperature !== undefined && raw.max_tokens !== undefined)
  ) {
    return {
      kind: 'preset',
      reason: raw.prompts ? '预设（含 prompts）' : '预设（含采样参数）',
      name: pickName(raw, '未命名预设'),
      count: Array.isArray(raw.prompts) ? raw.prompts.length : undefined,
    };
  }

  // 正则：单条 { script: {...} } 或 { find_regex, replace_string }
  if (raw.script && typeof raw.script === 'object') {
    return {
      kind: 'regex',
      reason: '正则脚本（ST 嵌套 script 格式）',
      name: pickName(raw, '未命名正则'),
    };
  }
  if (raw.find_regex !== undefined || raw.findRegex !== undefined) {
    return {
      kind: 'regex',
      reason: '正则脚本（扁平格式）',
      name: pickName(raw, '未命名正则'),
    };
  }
  if (Array.isArray(raw.scripts)) {
    return {
      kind: 'regex',
      reason: `正则脚本集合（scripts 数组）`,
      name: pickName(raw, '正则集合'),
      count: raw.scripts.length,
    };
  }
  // 有些批量导出直接给一个数组，元素是 {script:{...}}
  if (raw.scriptName !== undefined && raw.replaceString !== undefined) {
    return {
      kind: 'regex',
      reason: '正则脚本（parsecard 扁平格式）',
      name: pickName(raw, '未命名正则'),
    };
  }

  return {
    kind: 'unknown',
    reason: `无法识别（顶层字段：${Object.keys(raw).slice(0, 8).join(', ')}）`,
  };
}

/** 把一批原始对象按类型拆开（一个 JSON 里可能混装多种资产） */
export function splitByKind(value: unknown): { kind: AssetKind; items: any[] }[] {
  const items = Array.isArray(value) ? value : [value];
  const buckets = new Map<AssetKind, any[]>();

  const push = (kind: AssetKind, list: any[]) => {
    const arr = buckets.get(kind) || [];
    arr.push(...list);
    buckets.set(kind, arr);
  };

  for (const item of items) {
    // 世界书批量导出：{ entries: {...} } 是单本，不是数组，已在上面处理
    const d = detectKind(item);
    const kind = d.kind;

    // 正则集合：{scripts:[...]}
    if (kind === 'regex' && Array.isArray(item?.scripts)) {
      push('regex', item.scripts);
      continue;
    }

    if (kind === 'unknown') {
      // 尝试更深一层：有些导出是 { worldBooks: [...] } / { presets: [...] }
      const nested = findNestedAssetArray(item);
      if (nested) {
        push(nested.kind, nested.items);
        continue;
      }
    }

    push(kind, [item]);

    // ---- 预设里内嵌的正则 ----
    //
    // SillyTavern 导出一体化预设时，会把正则一起塞进 extensions.regex_scripts。
    // 旧实现只导入预设本体、把这一块整个丢掉，所以用户导入"预设+正则"的 JSON
    // 只能看到预设、正则全没了。这里把它们拆出来单独入库。
    if (kind === 'preset') {
      const embedded = extractEmbeddedRegex(item);
      if (embedded.length > 0) push('regex', embedded);
    }
  }

  return [...buckets.entries()].map(([kind, items]) => ({ kind, items }));
}

/**
 * 从预设里取出内嵌的正则脚本。
 * SillyTavern 放在 extensions.regex_scripts，
 * 格式已经是扁平结构（findRegex/replaceString/placement），无需转换。
 */
export function extractEmbeddedRegex(preset: any): any[] {
  const raw = preset?.extensions?.regex_scripts;
  if (!Array.isArray(raw)) return [];
  return raw.filter(
    (s) => s && typeof s === 'object' && (s.scriptName || s.findRegex || s.find_regex),
  );
}

/** 容器对象：{ worldBooks: [...] } 这类 */
function findNestedAssetArray(raw: any): { kind: AssetKind; items: any[] } | null {
  const map: [string, AssetKind][] = [
    ['worldBooks', 'worldbook'],
    ['worldbooks', 'worldbook'],
    ['lorebooks', 'worldbook'],
    ['presets', 'preset'],
    ['regexScripts', 'regex'],
    ['regexes', 'regex'],
    ['scripts', 'regex'],
    ['characters', 'character'],
    ['charactersCards', 'character'],
  ];
  for (const [key, kind] of map) {
    if (Array.isArray(raw?.[key])) return { kind, items: raw[key] };
  }
  return null;
}

// ---------------------------------------------------------------------------
// 归一化
// ---------------------------------------------------------------------------

/**
 * 世界书归一。
 * 重点：
 *  - ST 的 entries 是**对象**（以 uid 为键），而本项目的 UI/引擎按数组处理，
 *    这里统一成数组（之前"导入世界书没反应"的最主要原因）。
 *  - keys 与 key 两种写法都补齐成字符串数组，
 *    因为 key 为 [{key:"..."}] 时直接 String() 会得到 "[object Object]"。
 */
export function normalizeWorldBook(raw: any): { value: any; notes: string[] } {
  const notes: string[] = [];
  const out: any = { ...raw };

  if (raw.entries && !Array.isArray(raw.entries)) {
    const list = Object.entries(raw.entries).map(([uid, e]: [string, any]) => ({
      uid,
      ...(e as any),
    }));
    out.entries = list;
    notes.push(`entries 从对象索引转为数组（${list.length} 条）`);
  } else if (Array.isArray(raw.entries)) {
    out.entries = raw.entries.map((e: any, i: number) => ({ uid: e?.uid ?? String(i), ...e }));
  } else {
    out.entries = [];
  }

  // 条目字段补齐：parsecard 需要 key 数组 + keys 字符串
  out.entries = out.entries.map((e: any) => {
    // 优先级：keys（字符串或数组）→ key 数组（元素可能是字符串或对象）
    let keys: string[] = [];
    if (Array.isArray(e.keys)) {
      keys = e.keys
        .map((k: any) => (typeof k === 'string' ? k : (k?.key ?? '')))
        .filter((v: any) => typeof v === 'string' && v.length);
    } else if (typeof e.keys === 'string') {
      keys = e.keys
        .split(',')
        .map((s: string) => s.trim())
        .filter(Boolean);
    }
    if (keys.length === 0 && Array.isArray(e.key)) {
      keys = e.key
        .map((k: any) => (typeof k === 'string' ? k : (k?.key ?? '')))
        .filter((v: any) => typeof v === 'string' && v.length);
    }

    // key 保留原条目里的对象细节（whole_word 等），同时保证 key.key 是字符串
    const keyObjs =
      Array.isArray(e.key) && e.key.length
        ? e.key.map((k: any) =>
            typeof k === 'string'
              ? {
                  key: k,
                  secondary_keys: [],
                  match_case: false,
                  whole_word: false,
                  recursive: false,
                }
              : {
                  key: String(k?.key ?? ''),
                  secondary_keys: k?.secondary_keys ?? [],
                  match_case: k?.match_case ?? false,
                  whole_word: k?.whole_word ?? false,
                  recursive: k?.recursive ?? false,
                },
          )
        : keys.map((k) => ({
            key: k,
            secondary_keys: [],
            match_case: false,
            whole_word: false,
            recursive: false,
          }));

    return {
      ...e,
      keys,
      key: keyObjs,
      keysecondary: e.keysecondary ?? e.secondary_keys ?? [],
      content: e.content ?? '',
      comment: e.comment ?? '',
      constant: e.constant ?? false,
      // 默认 100：ST 里没有 order 的条目通常排在中段
      order: e.order ?? e.insertion_order ?? 100,
      position: e.position ?? 0,
      depth: e.depth ?? 4,
      probability: e.probability ?? e.prob ?? 100,
      disable: e.disable ?? e.disabled ?? false,
      disabled: e.disabled ?? e.disable ?? false,
      selective: e.selective ?? false,
      selectiveLogic: e.selectiveLogic ?? e.selective_logic ?? 0,
      caseSensitive: e.caseSensitive ?? e.case_sensitive ?? false,
      wholeWord: e.wholeWord ?? e.whole_word ?? false,
    };
  });

  if (!out.name && out.comment) {
    out.name = out.comment;
    notes.push('用 comment 作为世界书名称');
  }
  return { value: out, notes };
}

/** 正则归一：ST 嵌套 → parsecard 扁平 */
export function normalizeRegex(raw: any): { value: any; notes: string[] } {
  const notes: string[] = [];
  if (raw?.script) {
    const s = raw.script;
    notes.push('ST 嵌套 script 格式 → 扁平');
    return {
      value: {
        id: s.id,
        scriptName: raw.name || s.scriptName || s.name,
        findRegex: s.find_regex ?? s.findRegex ?? '',
        replaceString: s.replace_string ?? s.replaceString ?? '',
        trimStrings: s.trim_strings ?? s.trimStrings ?? [],
        placement: s.placement ?? [],
        disabled: s.disabled ?? s.isDisabled ?? false,
        markdownOnly: s.markdown_only ?? s.markdownOnly ?? false,
        promptOnly: s.prompt_only ?? s.promptOnly ?? false,
        runOnEdit: s.run_on_edit ?? s.runOnEdit ?? false,
        substituteRegex: s.substitute_regex ?? s.substituteRegex ?? 0,
        minDepth: s.min_depth ?? s.minDepth ?? null,
        maxDepth: s.max_depth ?? s.maxDepth ?? null,
      },
      notes,
    };
  }
  // 已经是扁平：只补默认值
  const value = {
    ...raw,
    scriptName: raw.scriptName ?? raw.name ?? '未命名正则',
    findRegex: raw.findRegex ?? raw.find_regex ?? '',
    replaceString: raw.replaceString ?? raw.replace_string ?? '',
    placement: raw.placement ?? [],
    disabled: raw.disabled ?? false,
  };
  return { value, notes };
}

/** 角色卡归一 */
export function normalizeCharacter(raw: any): { value: any; notes: string[] } {
  const notes: string[] = [];
  // V3 / 类脑扩展字段：parsecard 不认识，但留着不丢
  const out = { ...raw };
  if (raw?.spec && !raw.spec.startsWith('chara_card')) {
    notes.push(`spec=${raw.spec} 不是标准值，仍按角色卡尝试导入`);
  }
  return { value: out, notes };
}

/** 预设归一 */
export function normalizePreset(raw: any): { value: any; notes: string[] } {
  const notes: string[] = [];
  const out = { ...raw };

  // 有些预设把 prompts 放在 preset.prompts，有些在顶层
  if (!out.prompts && raw?.preset?.prompts) {
    out.prompts = raw.preset.prompts;
    notes.push('prompts 从 preset 子对象中提取');
  }

  // prompt_order 各代命名不一
  if (!out.prompt_order && out.promptOrder) {
    out.prompt_order = out.promptOrder;
    notes.push('promptOrder → prompt_order');
  }
  if (!out.prompt_order && Array.isArray(out.prompts)) {
    // 老预设没有 order：按数组顺序生成一份，保证能按序组装
    const ids = out.prompts.map((p: any, i: number) => ({
      identifier: p?.identifier ?? p?.id ?? `p${i}`,
      enabled: p?.enabled ?? true,
    }));
    out.prompt_order = [{ character_id: 100001, order: ids }];
    notes.push('预设缺少 prompt_order，已按 prompts 顺序自动生成');
  }

  // prompts 需要 identifier
  if (Array.isArray(out.prompts)) {
    let fixed = 0;
    out.prompts = out.prompts.map((p: any, i: number) => {
      const identifier = p?.identifier ?? p?.id ?? `p${i}`;
      if (!p?.identifier) fixed++;
      return { ...p, identifier };
    });
    if (fixed) notes.push(`${fixed} 条 prompt 缺少 identifier，已自动补齐`);
  }

  if (!out.name && out.preset_name) {
    out.name = out.preset_name;
    notes.push('preset_name → name');
  }

  // ---- 清理 extensions 里的重货 ----
  //
  // ST 的预设会把一堆东西塞进 extensions：
  //   regex_scripts  已经单独入库了，留在预设里会重复
  //   tavern_helper  可能内嵌几十万字符的脚本（这份档案里有一个 304KB 的）
  // 留着既占空间又没意义（前端不执行 STscript），所以剥掉。
  // 顺带把任何超过 64KB 的扩展值也丢掉，避免预设文件把存储撑爆。
  if (out.extensions && typeof out.extensions === 'object') {
    const ext: Record<string, unknown> = { ...out.extensions };
    const hadRegex = Array.isArray(ext.regex_scripts) ? ext.regex_scripts.length : 0;
    delete ext.regex_scripts;
    delete ext.tavern_helper;

    const kept: Record<string, unknown> = {};
    let dropped = 0;
    for (const [k, v] of Object.entries(ext)) {
      let size = 0;
      try {
        size = JSON.stringify(v)?.length ?? 0;
      } catch {
        size = 0;
      }
      if (size > 65536) {
        dropped++;
        continue;
      }
      kept[k] = v;
    }
    out.extensions = kept;
    if (hadRegex) notes.push(`预设内嵌的 ${hadRegex} 条正则已一并导入`);
    if (dropped) notes.push(`另有 ${dropped} 个超大扩展数据已跳过（多为内嵌脚本）`);
  }

  return { value: out, notes };
}

/** 按类型分发归一化 */
export function normalizeByKind(kind: AssetKind, raw: any): { value: any; notes: string[] } {
  switch (kind) {
    case 'worldbook':
      return normalizeWorldBook(raw);
    case 'regex':
      return normalizeRegex(raw);
    case 'character':
      return normalizeCharacter(raw);
    case 'preset':
      return normalizePreset(raw);
    default:
      return { value: raw, notes: [] };
  }
}

// ---------------------------------------------------------------------------
// 解析入口：把 parsecard 解析也包进来，统一错误信息
// ---------------------------------------------------------------------------

export interface ParseOutcome {
  kind: AssetKind;
  name: string;
  /** 归一化 + parsecard 解析后的对象，导入失败时为 null */
  parsed: any;
  error?: string;
  notes: string[];
}

export function parseItem(kind: AssetKind, raw: any, index: number): ParseOutcome {
  const { value, notes } = normalizeByKind(kind, raw);
  const fallbackName = `${kind === 'preset' ? '预设' : kind === 'worldbook' ? '世界书' : kind === 'regex' ? '正则' : '角色'} ${index + 1}`;
  const name = pickName(value, fallbackName);

  try {
    switch (kind) {
      case 'character':
        return { kind, name, parsed: CharacterCard.fromJSON(value).toJSON(), notes };
      case 'worldbook': {
        // 关键：不要用 parsecard 的 toStandaloneJSON() 作为最终产物。
        // 实测它会把 entries 重建为**对象**，并且丢掉 order/position/depth/
        // probability/constant 等编辑字段。这里只用它验证一下能否解析，
        // 真正入库的是我们已经归一好的结构（数组 + 完整字段）。
        WorldBook.fromJSON(value);
        return { kind, name, parsed: value, notes };
      }
      case 'regex': {
        // 同理：parsecard 的 toJSON() 会重排字段，这里保留归一化结果，
        // 只做一次解析验证。
        RegexScript.fromJSON(value);
        return { kind, name, parsed: value, notes };
      }
      case 'preset': {
        // 预设必须走 parsecard：它要负责 prompt_order 与 prompts 的合并，
        // 而这正是 parsecard 的强项，重排也不会丢字段。
        const p = OpenAIPreset.fromJSON(value);
        return { kind, name, parsed: p.toJSON(), notes };
      }
      default:
        return { kind, name, parsed: null, error: '无法识别的资产类型', notes };
    }
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    return { kind, name, parsed: null, error: `解析失败：${msg}`, notes };
  }
}
