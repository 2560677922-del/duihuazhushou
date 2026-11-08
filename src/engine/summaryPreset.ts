import { applyMacros, type MacroContext } from './macro';

/**
 * 预设条目（parsecard `listPromptEntries()` 的元素，或原始 ST JSON）的宽松形状。
 *
 * 之所以不写成严格类型：ST 预设的历史版本字段不统一（`prompt` 包一层 /
 * 直接平铺、`enabled` 在 order 里 / 在 prompt 里），这里只取真正用到的几个。
 */
export interface PresetEntryLike {
  enabled?: boolean;
  prompt?: { role?: string; content?: string; marker?: boolean };
  role?: string;
  content?: string;
  marker?: boolean;
}

/**
 * 角色卡类宏：这些宏在摘要里会被清空。
 *
 * 单独列出来是为了做"空壳行"清理 —— 展开后只剩 `角色设定：` 这半句的，
 * 整行删掉，否则模型会看到一堆没有内容的标签。
 */
const CARD_MACRO_RE =
  /\{\{\s*(description|personality|scenario|persona|mes_example|first_mes|original|char_persona)\s*\}\}/i;

/** 只有标签没有内容的一行（`角色设定：` / `Scenario:`）。 */
const EMPTY_LABEL_RE = /^[^：:\n]{1,24}[：:]\s*$/;

/** 摘要请求里的一条预设提示词。 */
export interface PresetMessage {
  role: string;
  content: string;
}

/**
 * 把预设条目整理成「摘要可用的提示词」。
 *
 * ★ 这是自动摘要「带预设、不带角色卡/世界书」的**唯一**落点，
 *   所以单独抽成纯函数，方便脱离 Pinia / 浏览器跑测试。
 *
 * 规则：
 *   1. 关掉的条目不要；marker 条目（content 为空）不要；
 *   2. 宏按传入的 [macros] 展开 —— 调用方只填 `char` / `user` 两个名字，
 *      `description` / `personality` / `scenario` / `persona` / `mes_example` /
 *      `first_mes` 全部为空，于是预设在引用角色卡的位置变成空白，
 *      **但条目本身保留**（整条删掉会把"回复规则"一起删了，预设就失效了）；
 *   3. 变量宏可选展开（`{{getvar::x}}`）。写宏在摘要里不执行，会变成空串；
 *   4. 不截断：预设砍一半等于失效，这是用户明确要求。
 */
export function collectPresetMessages(
  entries: PresetEntryLike[],
  macros: MacroContext,
  readVars?: (text: string) => string,
): PresetMessage[] {
  const out: PresetMessage[] = [];
  for (const e of entries) {
    if (!e || e.enabled === false) continue;
    const p = e.prompt ?? e;
    if (!p || typeof p.content !== 'string' || !p.content.trim()) continue;
    if (p.marker === true) continue;

    const before = p.content;
    let content = applyMacros(before, macros);
    if (readVars && content.includes('{{')) content = readVars(content);
    content = dropEmptyLabelLines(before, content).trim();
    if (!content) continue;

    out.push({ role: String(p.role || 'system'), content });
  }
  return out;
}

/**
 * 删掉「角色卡宏被清空后只剩冒号标签」的整行。
 *
 * 只在**展开前后行数一致**时动手：宏里如果带换行，行号对不上，
 * 精细匹配会误删，这时宁可留着空标签也不动。
 */
function dropEmptyLabelLines(before: string, after: string): string {
  const b = before.split('\n');
  const a = after.split('\n');
  if (b.length !== a.length) return after;
  const out: string[] = [];
  for (let i = 0; i < a.length; i++) {
    if (CARD_MACRO_RE.test(b[i]) && EMPTY_LABEL_RE.test(a[i].trim())) continue;
    out.push(a[i]);
  }
  return out.join('\n');
}
