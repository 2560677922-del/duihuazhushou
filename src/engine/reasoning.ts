// 思维链（推理过程）的处理
// ---------------------------------------------------------------------------
// 为什么需要单独一个模块：
//
// 推理模型（DeepSeek-R1 系、各家中转的 thinking 模型）会把"草稿纸"和正文
// 一起返回，用户看到的就是一大段"分析前段的不足之处…情景回顾…"贴在正文前面。
//
// 这件事有三条路径，必须分别处理：
//
//   1. 接口把推理放在**独立字段**（delta.reasoning_content / delta.reasoning）
//      → 最理想。apiClient 已经把它分离出来，这里直接丢掉即可，
//        正文本来就不含它。
//
//   2. 接口把推理**混在 content 里**，但带标记
//      （<thinking>…</thinking>、<|reasoning|>、[思考]…[/思考] 等）
//      → 这里按标记剥掉。
//
//   3. 接口把推理**混在 content 里且没有任何标记**
//      （截图里就是这种：直接一段中文分析，然后是正文）
//      → 无法可靠区分，只能让用户指定一个"分界标记"，
//        或者让模型按提示词自查。这里提供可配置的分界规则。
//
// 预设里那些 SillyTavern 正则（"思维链折叠"等）是针对路径 2 写的，
// 对路径 3 无能为力 —— 这就是"正则明明开着却没生效"的原因。

/** 常见的推理标记：会被整段剥掉 */
const TAG_PAIRS: [RegExp, RegExp][] = [
  [/<thinking\b[^>]*>/gi, /<\/thinking>/gi],
  [/<think\b[^>]*>/gi, /<\/think>/gi],
  [/<reasoning\b[^>]*>/gi, /<\/reasoning>/gi],
  [/<thought\b[^>]*>/gi, /<\/thought>/gi],
  [/<\|reasoning\|>/gi, /<\|\/reasoning\|>/gi],
  [/<\|thinking\|>/gi, /<\|\/thinking\|>/gi],
  [/\[thinking\]/gi, /\[\/thinking\]/gi],
  [/\[思考\]/g, /\[\/思考\]/g],
  [/\[推理\]/g, /\[\/推理\]/g],
];

/** 分界标记：这些行之后的内容才算正文 */
const BOUNDARY_PATTERNS: RegExp[] = [
  /^\s*(?:<\/think(?:ing)?>|<\/reasoning>)\s*$/gim,
  /^\s*(?:思考|推理|思维链)\s*(?:完毕|结束|完成)\s*[:：]?\s*$/gim,
  /^\s*-{3,}\s*(?:正文|回复|以下是正文|以下是回复)\s*-{0,}\s*$/gim,
  /^\s*(?:正文|回复)\s*[:：]\s*$/gim,
];

export interface ReasoningStripResult {
  /** 处理后的显示文本 */
  text: string;
  /** 是否真的去掉了东西 */
  stripped: boolean;
  /** 去掉的那部分（用于"查看思维链"） */
  reasoning: string;
}

/**
 * 按标记剥掉推理内容。
 *
 * @param text 原始输出
 * @param cutAfter 用户自定义的分界标记（可选）。一旦命中，它之前的内容全部算推理。
 */
export function stripReasoning(text: string, cutAfter?: string): ReasoningStripResult {
  if (!text) return { text: '', stripped: false, reasoning: '' };

  let out = text;
  let reasoning = '';

  // 1) 成对标记：整段（含标记）搬到 reasoning
  for (const [open, close] of TAG_PAIRS) {
    const re = new RegExp(`(${open.source})[\\s\\S]*?(${close.source})`, 'gi');
    out = out.replace(re, (whole) => {
      reasoning += whole + '\n';
      return '';
    });
  }

  // 2) 未闭合的开标记：从它开始到结尾都算推理
  for (const [open] of TAG_PAIRS) {
    const re = new RegExp(`(${open.source})[\\s\\S]*$`, 'i');
    out = out.replace(re, (whole) => {
      reasoning += whole;
      return '';
    });
  }

  // 3) 用户自定义分界：命中点之前全部算推理
  const cut = cutAfter?.trim();
  if (cut) {
    const idx = out.lastIndexOf(cut);
    if (idx >= 0) {
      reasoning += out.slice(0, idx + cut.length);
      out = out.slice(idx + cut.length);
    }
  }

  // 4) 通用分界标记
  if (!reasoning) {
    for (const p of BOUNDARY_PATTERNS) {
      p.lastIndex = 0;
      const m = p.exec(out);
      if (m && m.index >= 0) {
        reasoning += out.slice(0, m.index + m[0].length);
        out = out.slice(m.index + m[0].length);
        break;
      }
    }
  }

  out = out.replace(/^\s+/, '');
  const stripped = out !== text;
  return { text: out, stripped, reasoning: reasoning.trim() };
}

/**
 * 只做剥离，返回文本。用于保存/显示的主路径。
 */
export function cleanOutput(text: string, cutAfter?: string): string {
  return stripReasoning(text, cutAfter).text;
}
