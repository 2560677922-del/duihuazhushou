import type { ChatMessage, RegexHit } from './types';
import type { MacroContext } from './macro';

// 正则引擎：与 SillyTavern（release 分支 public/scripts/extensions/regex/engine.js）
// 的 runRegexScript 对齐。
//
// SillyTavern RegexPlacement 枚举：
//   USER_INPUT=1   用户输入
//   AI_OUTPUT=2    AI 输出
//   SLASH_COMMAND=3
//   WORLD_INFO=5   世界信息（注入条目内容）
//   REASONING=6
//
// ★ 这里以前和 ST 有五处语义不一致，表现都是"有的正则就是不生效 / 替换成乱码"：
//
//   1. $0 与 {{match}} 不支持
//      ST 的替换串里 `$0` 和 `{{match}}` 都表示"整个匹配"。
//      JS 原生 String.replace 里 `$0` **不是**组引用，会原样留下 "$0" ——
//      所以 `<$0>` 这种写法替换出来就是字面的 "<$0>"。
//
//   2. trimStrings（Trim Out）用法错了
//      ST 是**对每个被引用的分组内容**去字；我们之前是拿整个匹配去字、
//      再把去字结果塞给所有 $1/$2，等于把分组引用彻底破坏了。
//
//   3. 替换串里的宏没有展开
//      ST 最后会跑一遍 substituteParams，所以替换串里写 {{user}} / {{char}}
//      会变成实际名字（这是美化类正则的常用手法）。我们之前原样输出。
//
//   4. substituteRegex（findRegex 里的宏）完全没实现
//      这个字段界面上有、也存盘了，但引擎从来没读过。
//
//   5. 脚本执行顺序被按 id 重排
//      ST 严格按"全局 → 角色 → 预设"加上各自数组里的顺序执行，
//      链式脚本（上一条造 HTML、下一条再加工）依赖这个顺序。
//      我们按 id 排序，顺序一乱，后一条就匹配不到前一条的产物。

export const REGEX_PLACEMENT = {
  USER_INPUT: 1,
  AI_OUTPUT: 2,
  SLASH_COMMAND: 3,
  WORLD_INFO: 5,
  REASONING: 6,
} as const;

/** substituteRegex 的取值（对应 ST 的 substitute_find_regex） */
export const SUBSTITUTE_REGEX = {
  NONE: 0,
  RAW: 1,
  ESCAPED: 2,
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
  /**
   * 宏上下文。
   *
   * 用于两处（和 ST 一致）：findRegex 的 substituteRegex、替换串末尾的宏展开。
   * 不传就完全跳过宏处理，行为与旧版一致。
   */
  macroContext?: MacroContext;
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
    /*
     * 写了 /…/flags 就**照用户写的来**，不再强行补 g。
     *
     * ST 的 regexFromString 也不补：`/abc/` 只替换第一处。
     * 以前我们强行加 g，把"只换一次"的脚本变成了全换。
     */
    flags = m[2];
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

/* ---------------- 宏 ---------------- */

/**
 * 只替换**已知**的宏，未知的原样保留。
 *
 * 不能用 applyMacros：那个函数把不认识的宏替换成空串，
 * 用在正则替换串上会把用户好不容易写进去的 {{random}} 之类吃光。
 */
function substituteKnownMacros(text: string, ctx?: MacroContext): string {
  if (!ctx || !text.includes('{{')) return text;
  const map = new Map<string, string>();
  for (const [k, v] of Object.entries(ctx)) {
    if (v !== undefined && v !== null) map.set(k.toLowerCase(), String(v));
  }
  if (map.size === 0) return text;
  return text.replace(/\{\{\s*([\w.]+)\s*\}\}/g, (full, name: string) => map.get(name.toLowerCase()) ?? full);
}

/**
 * 转义正则元字符（对应 ST 的 sanitizeRegexMacro）。
 *
 * substituteRegex = ESCAPED 时用：宏的值当成**字面量**塞进正则，
 * 这样角色名叫 "A.B" 也不会被当成通配符。
 */
export function sanitizeRegexMacro(s: string): string {
  return s.replace(/[\n\r\t\v\f\0.^$*+?{}[\]\\/|()]/g, (ch) => {
    switch (ch) {
      case '\n': return '\\n';
      case '\r': return '\\r';
      case '\t': return '\\t';
      case '\v': return '\\v';
      case '\f': return '\\f';
      case '\0': return '\\0';
      default: return '\\' + ch;
    }
  });
}

/** 按 substituteRegex 处理 findRegex（对应 ST 的 getRegexString） */
function resolveFindPattern(raw: string, mode: number, ctx?: MacroContext): string {
  if (mode === SUBSTITUTE_REGEX.RAW) return substituteKnownMacros(raw, ctx);
  if (mode === SUBSTITUTE_REGEX.ESCAPED) {
    if (!ctx) return raw;
    // 先替换，再对"替换进来的值"转义 —— 做法和 ST 一致：
    // 宏的值整体转义，未命中的部分保持原样
    const map = new Map<string, string>();
    for (const [k, v] of Object.entries(ctx)) {
      if (v !== undefined && v !== null) map.set(k.toLowerCase(), sanitizeRegexMacro(String(v)));
    }
    if (map.size === 0) return raw;
    return raw.replace(/\{\{\s*([\w.]+)\s*\}\}/g, (full, name: string) => map.get(name.toLowerCase()) ?? full);
  }
  return raw;
}

/* ---------------- 替换 ---------------- */

/** 一次匹配里，替换模板能用到的素材 */
interface MatchParts {
  /** 整个匹配 */
  match: string;
  /** $1..$n */
  groups: (string | undefined)[];
  /** 命名分组 $<name> */
  named?: Record<string, string>;
}

/**
 * 从 String.replace 回调的多余参数里拆出分组。
 *
 * 回调参数形式是：
 *   (match, p1…pn, offset, string)              没有命名分组
 *   (match, p1…pn, offset, string, groups)      有命名分组
 * 所以分组个数 = 长度 − 2（或 − 3）。
 */
function splitCallbackArgs(rest: any[]): { groups: (string | undefined)[]; named?: Record<string, string> } {
  const tail = rest[rest.length - 1];
  const hasNamed = !!tail && typeof tail === 'object';
  const count = rest.length - (hasNamed ? 3 : 2);
  return {
    groups: rest.slice(0, Math.max(0, count)) as (string | undefined)[],
    named: hasNamed ? (tail as Record<string, string>) : undefined,
  };
}

/** 按 ST 的规则把替换模板套用到一次匹配上 */
function buildReplacement(
  template: string,
  parts: MatchParts,
  trims: string[],
  ctx?: MacroContext,
): string {
  // ST: 替换串里的 {{match}} 等价于 $0（整个匹配）
  const tpl = template.replace(/\{\{match\}\}/gi, '$0');

  const withGroups = tpl.replace(/\$(\d+)|\$<([^>]+)>/g, (_all, num?: string, name?: string) => {
    let raw: string | undefined;
    if (name) {
      raw = parts.named?.[name];
    } else if (num !== undefined) {
      raw = Number(num) === 0 ? parts.match : parts.groups[Number(num) - 1];
    }
    // 分组没参与匹配（比如 (a)? 没命中）→ 空串，和 ST 一致
    if (raw === undefined || raw === null) return '';
    // Trim Out 是**按被引用的那段内容**去字，不是整个匹配
    let v = raw;
    for (const t of trims) {
      if (t) v = v.split(t).join('');
    }
    return v;
  });

  // ST 最后整体跑一遍宏替换
  return substituteKnownMacros(withGroups, ctx);
}

/** 数一下能匹配多少处（用于测试面板的统计） */
function countMatches(regex: RegExp, text: string): number {
  let n = 0;
  const copy = new RegExp(regex.source, regex.flags.includes('g') ? regex.flags : regex.flags + 'g');
  let m: RegExpExecArray | null;
  while ((m = copy.exec(text)) !== null) {
    n++;
    if (n >= 999) break;
    if (m[0] === '') copy.lastIndex++; // 零宽匹配防死循环
  }
  return n;
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
  const ctx = options.macroContext;
  const placement = options.placement ?? { user: true, ai: true, worldInfo: true };
  const hits: RegexHit[] = [];

  /*
   * ★ 不再按 id 排序。
   *
   * ST 的执行顺序是「全局 → 角色 → 预设」，组内按数组顺序（用户拖出来的顺序）。
   * 链式脚本严重依赖这个顺序：先造 HTML、后加工颜色。
   * 我们之前按 id 字典序排，顺序一乱后面那条就匹配不到前面造出来的东西。
   * 传进来的数组已经是 assets.getCurrentRegexScripts() 压平后的正确顺序，
   * 直接照用即可。
   */
  const ordered = scripts as RawScript[];

  // dryRun 模式下复制消息，避免污染调用方数据
  const work: ChatMessage[] = dry ? messages.map((m) => ({ ...m })) : messages;

  for (const s of ordered) {
    if (s.disabled) continue;

    /*
     * placement 空数组 / 缺失时按 AI_OUTPUT 处理。
     *
     * ST 的 UI 建脚本时一定会写 placement；但从别处导入的规则经常没有这个字段，
     * 以前算出来就是"哪都不生效"，用户完全看不出原因。
     */
    const declared: number[] = Array.isArray(s.placement) ? s.placement : [];
    const placements = declared.length ? declared : [REGEX_PLACEMENT.AI_OUTPUT];

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

    const rawFind = s.find_regex ?? s.findRegex ?? '';
    const replaceTemplate = s.replace_string ?? s.replaceString ?? '';
    const name = s.name || s.scriptName || '(未命名)';
    const trims = trimList(s);

    const subMode = Number(s.substituteRegex ?? s.substitute_regex ?? SUBSTITUTE_REGEX.NONE) || 0;
    const find = resolveFindPattern(rawFind, subMode, ctx);

    let regex: RegExp;
    try {
      regex = compileRegex(find);
    } catch {
      hits.push({
        id: String(s.id ?? name),
        scriptName: name,
        placement: placements,
        findRegex: find,
        replaceString: replaceTemplate,
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

      /*
       * 命中数：正常路径在下面 replace 的回调里顺带数，零额外扫描。
       *
       * 以前这里无条件调用 countMatches —— 对每条消息 × 每个脚本都再全文
       * 跑一遍带 g 的 exec（最多 999 次）。显示路径在流式生成时每秒重渲染
       * 数次，长会话 + 多脚本会把主线程压住。这是"卡住一会儿"的放大器。
       * 只有 dryRun（正则测试面板要精确统计）才需要单独数。
       */
      if (dry) matchCount += countMatches(regex, m.content);

      if (!sample) {
        // 用首个匹配点构造 before/after 片段
        const idx = firstMatch.index;
        const beforeCtx = m.content.slice(Math.max(0, idx - 24), idx);
        const matchText = firstMatch[0];
        const afterCtx = m.content.slice(idx + matchText.length, idx + matchText.length + 24);
        const preview = buildReplacement(
          replaceTemplate,
          { match: matchText, groups: firstMatch.slice(1), named: firstMatch.groups },
          trims,
          ctx,
        );
        sample = {
          before: `${beforeCtx}⟪${matchText}⟫${afterCtx}`,
          after: `${beforeCtx}⟪${preview}⟫${afterCtx}`,
        };
      }

      if (!dry) {
        regex.lastIndex = 0;
        m.content = m.content.replace(regex, (whole: string, ...rest: any[]) => {
          matchCount++;
          const parts = splitCallbackArgs(rest);
          return buildReplacement(
            replaceTemplate,
            { match: whole, groups: parts.groups, named: parts.named },
            trims,
            ctx,
          );
        });
      }
    }

    hits.push({
      id: String(s.id ?? name),
      scriptName: name,
      placement: placements,
      findRegex: find,
      replaceString: replaceTemplate,
      matchCount,
      sample,
    });
  }

  return { messages: work, hits };
}
