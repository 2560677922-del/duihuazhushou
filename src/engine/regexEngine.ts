import type { ChatMessage, RegexHit } from './types';

// 正则引擎：参考 GengBi 的 RegexScript placement 设计用 TS 重写。
//
// SillyTavern RegexPlacement 枚举：
//   USER_INPUT=1   用户输入
//   AI_OUTPUT=2    AI 输出
//   SLASH_COMMAND=3
//   WORLD_INFO=5   世界信息（注入条目内容）
//   REASONING=6
//
// 执行顺序：每个脚本独立处理。Phase 1 实现 USER_INPUT / AI_OUTPUT /
// WORLD_INFO 三类 placement。脚本内部按 enabled → id 排序。
//
// dryRun=true 时返回命中结果但不修改消息（用于正则测试器与调试面板）。

export const REGEX_PLACEMENT = {
  USER_INPUT: 1,
  AI_OUTPUT: 2,
  SLASH_COMMAND: 3,
  WORLD_INFO: 5,
  REASONING: 6,
} as const;

export interface RegexPlacementFilter {
  user?: boolean;
  ai?: boolean;
  worldInfo?: boolean;
  /**
   * 推理过程（思维链）。
   *
   * 界面上一直有这个选项，但引擎之前**根本没实现** ——
   * 选了「推理过程」的规则等于完全没跑，用户会觉得"有的正则没生效"。
   * 现在按 _origin === 'reasoning' 识别。
   */
  reasoning?: boolean;
  /**
   * SLASH 命令。
   *
   * 同样之前没实现。本应用目前不解析斜杠命令，
   * 所以这个开关只在调用方显式传入且消息带 _origin === 'slash' 时生效，
   * 保留接口以便以后接命令系统。
   */
  slash?: boolean;
}

export interface RegexEngineOptions {
  dryRun?: boolean;
  placement?: RegexPlacementFilter;
  /**
   * 这次跑的是「界面显示」而不是「发给大模型」。
   *
   * SillyTavern 的两个时机标记是一对互斥语义：
   *   markdownOnly = 只改显示，不动发给模型的文本
   *   promptOnly   = 只改发给模型的文本，不动显示
   * 之前实现里 markdownOnly 的脚本在非 dryRun 时直接 continue，
   * 结果是「只改显示」的规则**在显示时也没跑** —— 用户看到的就是
   * 带  thinking、<details>、<SUOT> 这些原始标签的生肉。
   *
   * 传 forDisplay=true 时按显示语义筛选：跑通用的和 markdownOnly 的，
   * 跳过 promptOnly 的。
   */
  forDisplay?: boolean;
}

interface RawScript {
  id?: string;
  scriptName?: string;
  name?: string;
  find_regex?: string;
  findRegex?: string;
  replace_string?: string;
  replaceString?: string;
  placement?: number[];
  disabled?: boolean;
  /** 替换前先从匹配内容里移除的片段（ST 的 Trim Out），每项一行 */
  trimStrings?: string[];
  trim_strings?: string[];
  /** 执行时机：markdownOnly=只改显示，promptOnly=只改提示词 */
  markdownOnly?: boolean;
  markdown_only?: boolean;
  promptOnly?: boolean;
  prompt_only?: boolean;
  /** 0=不替换 1=替换（宏生效） 2=替换（原始） */
  substituteRegex?: number;
  substitute_regex?: number;
  /** 生效的消息深度范围（从最新一条往回数），null/undefined = 不限 */
  minDepth?: number | null;
  min_depth?: number | null;
  maxDepth?: number | null;
  max_depth?: number | null;
}

/**
 * 编译结果缓存。
 *
 * `new RegExp` 不便宜，而这个函数在每次 runRegex 里会被每条规则调用，
 * 界面上又是"每条消息渲染一次"。同样的 pattern 反复编译纯属浪费。
 * 键就是原始字符串（含 flags），同一个规则永远得到同一个 RegExp 实例。
 *
 * 注意：带 `g` 标志的 RegExp 有 lastIndex 状态，**不能跨次复用同一个对象**
 * （会从上一次的位置继续匹配）。所以这里只缓存"源码 → 编译好的模板"，
 * 真正使用时用 `new RegExp(cached.source, cached.flags)` 复制一份。
 */
const regexCache = new Map<string, { source: string; flags: string }>();

/** 取出 `{ source, flags }`，避免每次重新解析字符串 */
function parseRegex(raw: string): { source: string; flags: string } {
  const hit = regexCache.get(raw);
  if (hit) return hit;
  let pattern = raw;
  let flags = 'g';
  const m = raw.match(/^\/(.+)\/([a-z]*)$/s);
  if (m) {
    pattern = m[1];
    flags = m[2].includes('g') ? m[2] : m[2] + 'g';
  }
  const out = { source: pattern, flags };
  // 上限保护：正则脚本一般就几十条，给足空间；异常数据也不会撑爆内存
  if (regexCache.size > 500) regexCache.clear();
  regexCache.set(raw, out);
  return out;
}

/**
 * 把 `/pattern/flags` 或裸 pattern 解析成 RegExp。
 * ST 里两种写法都常见，之前只认裸 pattern，
 * 遇到带斜杠的导出会直接编译失败、静默不生效。
 */
function compileRegex(raw: string): RegExp {
  const { source, flags } = parseRegex(raw);
  // 每次都新建实例：`g` 标志会带 lastIndex 状态，复用会导致跳字
  return new RegExp(source, flags);
}

/** 取出 Trim Out 列表（兼容两种字段命名：数组或换行分隔的字符串） */
function trimList(s: RawScript): string[] {
  const raw: unknown = s.trimStrings ?? s.trim_strings;
  if (Array.isArray(raw)) {
    return raw.filter((x): x is string => typeof x === 'string' && x.length > 0);
  }
  if (typeof raw === 'string') {
    return raw
      .split('\n')
      .map((x: string) => x.trim())
      .filter(Boolean);
  }
  return [];
}

/** 该消息是否落在脚本的深度范围内（0 = 最新一条） */
function inDepthRange(s: RawScript, depthFromEnd: number): boolean {
  const min = numOrNull(s.minDepth ?? s.min_depth);
  const max = numOrNull(s.maxDepth ?? s.max_depth);
  if (min !== null && depthFromEnd < min) return false;
  if (max !== null && depthFromEnd > max) return false;
  return true;
}

/** 把可能是 ''/null/undefined/字符串数字 的值归一成 number 或 null */
function numOrNull(v: unknown): number | null {
  if (v === null || v === undefined || v === '') return null;
  const n = Number(v);
  return Number.isNaN(n) ? null : n;
}

export function runRegex(
  messages: ChatMessage[],
  scripts: any[],
  options: RegexEngineOptions = {},
): { messages: ChatMessage[]; hits: RegexHit[] } {
  const dry = options.dryRun ?? false;
  const forDisplay = options.forDisplay ?? false;
  const placement = options.placement ?? { user: true, ai: true, worldInfo: true };
  const hits: RegexHit[] = [];

  const sorted = [...scripts].sort((a, b) => {
    const ai = a.disabled ? 1 : 0;
    const bi = b.disabled ? 1 : 0;
    if (ai !== bi) return ai - bi;
    return String(a.id ?? '').localeCompare(String(b.id ?? ''));
  });

  // dryRun 模式下复制消息，避免污染调用方数据
  const work: ChatMessage[] = dry ? messages.map((m) => ({ ...m })) : messages;

  for (const s of sorted as RawScript[]) {
    if (s.disabled) continue;
    const placements: number[] = Array.isArray(s.placement) ? s.placement : [];
    const applyToUser = placement.user && placements.includes(REGEX_PLACEMENT.USER_INPUT);
    const applyToAI = placement.ai && placements.includes(REGEX_PLACEMENT.AI_OUTPUT);
    const applyToWI = placement.worldInfo && placements.includes(REGEX_PLACEMENT.WORLD_INFO);
    const applyToReasoning = placement.reasoning && placements.includes(REGEX_PLACEMENT.REASONING);
    const applyToSlash = placement.slash && placements.includes(REGEX_PLACEMENT.SLASH_COMMAND);
    if (!applyToUser && !applyToAI && !applyToWI && !applyToReasoning && !applyToSlash) continue;

    // 执行时机筛选。
    //
    // markdownOnly / promptOnly 是一对互斥语义，按调用方的目的取一个方向：
    //   发给大模型（默认）：跑通用的 + promptOnly，跳过 markdownOnly
    //   界面显示（forDisplay）：跑通用的 + markdownOnly，跳过 promptOnly
    const markdownOnly = s.markdownOnly ?? s.markdown_only ?? false;
    const promptOnly = s.promptOnly ?? s.prompt_only ?? false;
    if (forDisplay) {
      if (promptOnly) continue;
    } else if (!dry) {
      if (markdownOnly) continue;
    }

    const find = s.find_regex ?? s.findRegex ?? '';
    const replace = s.replace_string ?? s.replaceString ?? '';
    const name = s.name || s.scriptName || '(未命名)';
    const trims = trimList(s);

    let regex: RegExp;
    try {
      regex = compileRegex(find);
    } catch {
      hits.push({
        id: String(s.id ?? name),
        scriptName: name,
        placement: placements,
        findRegex: find,
        replaceString: replace,
        matchCount: 0,
        sample: null,
      });
      continue;
    }

    let matchCount = 0;
    let sample: RegexHit['sample'] = null;

    for (let mi = 0; mi < work.length; mi++) {
      const m = work[mi];
      const isUser = m.role === 'user';
      const isAI = m.role === 'assistant';
      const isWI = m._origin === 'worldbook' || m._origin === 'author_note';
      // 推理过程/斜杠命令靠 _origin 区分：它们也是 assistant/user 角色，
      // 不区分的话「只对推理过程」会把正文也一起改掉。
      const isReasoning = m._origin === 'reasoning';
      const isSlash = m._origin === 'slash';
      const touch =
        (applyToUser && isUser && !isSlash) ||
        (applyToAI && isAI && !isReasoning) ||
        (applyToWI && isWI) ||
        (applyToReasoning && isReasoning) ||
        (applyToSlash && isSlash);
      if (!touch) continue;

      // 深度范围：从最后一条往回数，最后一条是 0
      const depthFromEnd = work.length - 1 - mi;
      if (!inDepthRange(s, depthFromEnd)) continue;

      regex.lastIndex = 0;
      const firstMatch = regex.exec(m.content);
      if (!firstMatch) continue;
      matchCount++;

      if (!sample) {
        // 用首个匹配点构造 before/after 片段
        const idx = firstMatch.index;
        const beforeCtx = m.content.slice(Math.max(0, idx - 24), idx);
        const matchText = firstMatch[0];
        const afterCtx = m.content.slice(idx + matchText.length, idx + matchText.length + 24);
        // 把 $1/$2 等反向引用代入预览
        const preview = replace.replace(/\$(\d)/g, (_full, n: string) => firstMatch[Number(n)] ?? '');
        sample = {
          before: `${beforeCtx}⟪${matchText}⟫${afterCtx}`,
          after: `${beforeCtx}⟪${preview}⟫${afterCtx}`,
        };
      }

      if (!dry) {
        let content = m.content;
        // Trim Out：替换前先从匹配内容里去字。ST 的做法是对匹配到的片段
        // 先删掉这些子串，再整体做替换，这里同样处理。
        if (trims.length) {
          regex.lastIndex = 0;
          content = content.replace(regex, (whole, ...rest) => {
            void rest;
            let w = whole;
            for (const t of trims) w = w.split(t).join('');
            // 把修剪后的内容当成"匹配结果"再套用替换模板
            return replace.replace(/\$(\d)/g, () => w);
          });
        } else {
          regex.lastIndex = 0;
          content = content.replace(regex, replace);
        }
        m.content = content;
      }
    }

    hits.push({
      id: String(s.id ?? name),
      scriptName: name,
      placement: placements,
      findRegex: find,
      replaceString: replace,
      matchCount,
      sample,
    });
  }

  return { messages: work, hits };
}
