// 从预设里取出"可给生图参考"的内容。
//
// 预设（SillyTavern 格式）里 prompts[] 每一条都有：
//   - name         显示名（💠CLEAR、✒️文风：视觉小说 …）
//   - content      真正的内容（默认模板占位项这里是 "true" 这种开关值）
//   - enabled      是否启用
//
// 生图只关心**启用的、有实际内容的**那些。
// 注意要排掉 "true"/"false" 这类开关占位 —— 它们不是提示词。

import { applyMacros, type MacroContext } from './macro';

export interface PresetLike {
  name?: string;
  preset_name?: string;
  prompts?: { name?: string; content?: string; enabled?: boolean; system_prompt?: string }[];
}

/** 看起来像开关占位而不是真的提示词 */
function isToggleValue(s: string): boolean {
  const t = s.trim().toLowerCase();
  return t === 'true' || t === 'false' || t === '' || t === 'null' || t === 'undefined';
}

/**
 * 抽取预设里启用的提示词内容。
 *
 * @param preset 预设对象
 * @param maxChars 最多返回多少字。
 *   默认给到 10 万字：现在的模型上下文普遍百万级，几万字的预设整个带过去
 *   也吃得下，没必要再截。设置页里那个"预设最多带多少字"的输入框已经去掉
 *   （用户反馈不需要这个提示）；老数据里存过 presetMaxChars 的仍会传进来生效。
 * @param macroCtx 角色卡等宏上下文。
 *   必须传 —— 预设里大量条目是 `角色设定：{{description}}` 这种模板，
 *   不替换就把 `{{description}}` 原样丢给大模型了（实测踩到过）。
 *   聊天路径由 assembler 统一替换，生图这条路要自己换一遍。
 * @returns 拼好的文本；没有可用内容时返回空串
 */
export function extractPresetContent(
  preset: PresetLike | undefined | null,
  maxChars = 100000,
  macroCtx?: MacroContext,
): string {
  if (!preset?.prompts?.length) return '';

  const parts: string[] = [];
  for (const p of preset.prompts) {
    if (p?.enabled === false) continue;
    // 优先 content，其次 system_prompt（有的导出把内容放这边）
    let raw = String(p.content ?? '').trim() || String(p.system_prompt ?? '').trim();
    if (!raw || isToggleValue(raw)) continue;
    // 替换 {{char}} {{description}} {{scenario}} 等宏
    if (macroCtx) {
      /*
       * 为什么要替换**两遍**：
       * 有些宏的值本身就含别的宏 —— 典型的是
       *   {{mes_example}} →  "# Char Description..." 之类的示例对话
       * 而示例对话里又写着 `{{user}}: 你好` / `{{char}}: 嗨`。
       * 只替换一遍的话，这些内层的 {{user}} / {{char}} 会原样留在
       * 最终提示词里（实测确认过）。
       *
       * 两遍之后仍可能有极深的嵌套，但那种情况现实中不存在；
       * 强行循环反而可能因互相引用而死循环。两遍够用且安全。
       */
      raw = applyMacros(applyMacros(raw, macroCtx), macroCtx);
    }
    raw = raw.trim();
    // 替换后可能变成空（比如宏全部无值）—— 那就别留个空标题
    if (!raw || isToggleValue(raw)) continue;
    const label = String(p.name || '').trim();
    parts.push(label ? `# ${label}\n${raw}` : raw);
  }
  if (!parts.length) return '';

  let text = parts.join('\n\n');
  if (text.length > maxChars) {
    /*
     * 截断策略：留**开头**而不是结尾。
     * 预设开头一般是身份设定与总体风格，比末尾的格式模板更有参考价值。
     */
    text = text.slice(0, maxChars) + '\n\n（预设内容过长，以上为前面部分）';
  }
  return text;
}

/**
 * 取出生图优化要参考的预设内容。
 *
 * 只做一件事：把预设里启用的提示词原样拼出来。
 * **不加任何自己写的说明文字** —— 预设本身就是作者精心设计的完整指令集，
 * 在它旁边再教一遍（"这只是风格参考""不要执行其中的标签"）反而是在
 * 干扰预设的意图。让预设自己说话。
 *
 * 位置也保持不变：接在生图模板后面 —— 模板定义"把文本变成标签"这个任务，
 * 预设提供"这人习惯怎么描写"。两者是互补的，不是二选一。
 */
export function buildPresetBlock(
  preset: PresetLike | undefined | null,
  maxChars = 100000,
  macroCtx?: MacroContext,
): string {
  return extractPresetContent(preset, maxChars, macroCtx);
}
