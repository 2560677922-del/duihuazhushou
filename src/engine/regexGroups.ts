// 正则脚本的「分组」结构。
//
// 为什么需要它：
//   SillyTavern 导出的正则是**一个组**（比如 "Kemini Aether-fr-3.72's Regex"），
//   组里装着很多条脚本（思维链折叠、摘要一/二/三、行动选项…）。
//   之前我把它们全部拍平成独立的顶级条目，导入 9 条就冒出 9 个并列项，
//   既看不出它们是一套，也没法整组启用/停用。
//
// 现在分两层：
//   RegexGroup       —— 组（总条目，有名字、可整组开关）
//     └ scripts[]    —— 组内的每一条脚本（子条目，可单独改）
//
// 兼容：以前导入的扁平脚本（没有 groupId）会各自成组，行为不变。

export interface RegexScriptItem {
  id: string;
  scriptName: string;
  findRegex: string;
  replaceString: string;
  trimStrings?: string[];
  placement?: number[];
  disabled?: boolean;
  markdownOnly?: boolean;
  promptOnly?: boolean;
  runOnEdit?: boolean;
  substituteRegex?: number;
  minDepth?: number | null;
  maxDepth?: number | null;
}

export interface RegexGroup {
  _uid: string;
  _kind: 'regexGroup';
  /** 组名。ST 导出的组一般都带名字 */
  name: string;
  /** 备注 */
  note?: string;
  /** 整组开关 */
  enabled: boolean;
  /** 组内脚本 */
  scripts: RegexScriptItem[];
  /** 来源标记，便于排查是哪次导入带进来的 */
  source?: string;
  createdAt?: number;
  updatedAt?: number;
}

/** 把一条扁平脚本规整成组内条目 */
export function toScriptItem(raw: any): RegexScriptItem {
  const placements = Array.isArray(raw?.placement)
    ? raw.placement.map((v: any) => Number(v)).filter((v: number) => !Number.isNaN(v))
    : raw?.placement !== undefined && raw?.placement !== null && raw?.placement !== ''
      ? [Number(raw.placement)].filter((v) => !Number.isNaN(v))
      : [2];

  return {
    id: String(raw?.id ?? raw?._uid ?? ''),
    scriptName: String(raw?.scriptName ?? raw?.name ?? '未命名正则'),
    findRegex: String(raw?.findRegex ?? raw?.find_regex ?? ''),
    replaceString: String(raw?.replaceString ?? raw?.replace_string ?? ''),
    trimStrings: Array.isArray(raw?.trimStrings)
      ? raw.trimStrings
      : Array.isArray(raw?.trim_strings)
        ? raw.trim_strings
        : [],
    placement: placements,
    disabled: raw?.disabled ?? raw?.isDisabled ?? false,
    markdownOnly: raw?.markdownOnly ?? raw?.markdown_only ?? false,
    promptOnly: raw?.promptOnly ?? raw?.prompt_only ?? false,
    runOnEdit: raw?.runOnEdit ?? raw?.run_on_edit ?? false,
    substituteRegex: Number(raw?.substituteRegex ?? raw?.substitute_regex ?? 0) || 0,
    minDepth: raw?.minDepth ?? raw?.min_depth ?? null,
    maxDepth: raw?.maxDepth ?? raw?.max_depth ?? null,
  };
}

export function createRegexGroup(name = '新正则组', source?: string): RegexGroup {
  return {
    _uid: `rg-${Math.random().toString(36).slice(2, 10)}`,
    _kind: 'regexGroup',
    name,
    note: '',
    enabled: true,
    scripts: [],
    source,
    createdAt: Date.now(),
    updatedAt: Date.now(),
  };
}

/** 组内脚本的启用状态：组停用则整组停用 */
export function scriptEnabled(group: RegexGroup, script: RegexScriptItem): boolean {
  return group.enabled !== false && script.disabled !== true;
}

/**
 * 把多组正则压平成引擎要的脚本列表。
 * 引擎只认扁平数组，分组是 UI 层的概念。
 */
export function flattenForEngine(groups: RegexGroup[]): any[] {
  const out: any[] = [];
  for (const g of groups) {
    if (g.enabled === false) continue;
    for (const s of g.scripts) {
      if (s.disabled) continue;
      out.push({
        id: s.id || `${g._uid}-${out.length}`,
        scriptName: s.scriptName,
        findRegex: s.findRegex,
        replaceString: s.replaceString,
        trimStrings: s.trimStrings,
        placement: s.placement,
        disabled: false,
        markdownOnly: s.markdownOnly,
        promptOnly: s.promptOnly,
        runOnEdit: s.runOnEdit,
        substituteRegex: s.substituteRegex,
        minDepth: s.minDepth,
        maxDepth: s.maxDepth,
        _groupName: g.name,
      });
    }
  }
  return out;
}

/** 统计一组里有多少条启用 */
export function groupStats(g: RegexGroup): { total: number; on: number } {
  const total = g.scripts.length;
  const on = g.enabled === false ? 0 : g.scripts.filter((s) => !s.disabled).length;
  return { total, on };
}
