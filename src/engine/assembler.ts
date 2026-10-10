import type { AssemblyOptions, AssemblyResult, ChatMessage } from './types';
import { applyMacrosToMessages, type MacroContext } from './macro';
import { runRegex } from './regexEngine';
import { triggerWorldbook } from './worldbook';
import { countTokens } from './tokenizer';
import { stripImageMarkers } from './imageMarker';

// 三阶段提示组装引擎：参考 GengBi 的 prompt_assembler.py 用 TS 重写。
//
// 阶段 1 排序：按 ST 预设的 prompt_order[characterId] 拼出有序提示块。
//   chatHistory 作为分界线（presets 中常含 main/greeting/examples 等锚点）。
//
// 阶段 2 深度注入：触发世界书条目，按 position / depth 注入到消息数组。
//   worldInfoBefore / worldInfoAfter / AT_DEPTH / OUTLET 等位置语义与 ST 一致。
//
// 阶段 3 Token 裁剪 + 宏替换 + 正则处理：
//   - 宏替换 {{char}} {{user}} {{persona}} {{description}} {{scenario}}
//     {{original}} 等
//   - 正则处理（USER_INPUT / AI_OUTPUT / WORLD_INFO）
//   - Token 裁剪：保留非历史消息，从最旧的历史消息开始丢，直到预算内
//
// 所有中间阶段结果保留在 stages 供调试面板查看。

// SillyTavern WorldBookEntryPosition 枚举
const BEFORE_CHAR = 0;
const AFTER_CHAR = 1;
const BEFORE_AUTHOR_NOTE = 2;
const AFTER_AUTHOR_NOTE = 3;
const AT_DEPTH = 4;
const BEFORE_EXAMPLES = 5;
const AFTER_EXAMPLES = 6;
const OUTLET = 7;

export function assemblePrompt(opts: AssemblyOptions): AssemblyResult {
  const warnings: string[] = [];
  const {
    preset,
    worldBooks = [],
    regexScripts = [],
    characterCard,
    chatHistory,
    currentUserInput,
    authorNote,
    authorNoteDepth = 4,
    worldBookScanDepth = 10,
    contextWindowTokens,
    userLookbackTokens,
  } = opts;

  // ---- 阶段 1：按 prompt_order 排序 ----
  // 优先用 parsecard 的 listPromptEntries()（它已处理排序与 enabled），
  // 回退到原始 ST JSON 的 prompt_order[characterId] + prompts 查找。
  const baseMessages: ChatMessage[] = [];
  if (preset) {
    const entries: any[] = preset.listPromptEntries ? preset.listPromptEntries() : [];
    if (entries.length > 0) {
      for (const e of entries) {
        if (e.enabled === false) continue;
        const p = e.prompt ?? e;
        if (!p || p.content == null) continue;
        baseMessages.push({
          role: (p.role as ChatMessage['role']) || 'system',
          content: p.content ?? '',
          _promptId: p.identifier || e.identifier,
          _name: p.name,
          _origin: 'preset',
        });
      }
    } else {
      // 原始 ST 预设 JSON 回退路径
      const charId = String(preset.defaultCharacterId ?? '100001');
      const order: string[] = preset.prompt_order?.[charId] ?? preset.prompt_order?.['100001'] ?? preset.promptOrder?.[charId] ?? [];
      for (const id of order) {
        const p = preset.getPrompt ? preset.getPrompt(id) : findPromptInRaw(preset, id);
        if (!p) continue;
        if (p.enabled === false) continue;
        baseMessages.push({
          role: (p.role as ChatMessage['role']) || 'system',
          content: p.content ?? '',
          _promptId: p.identifier || id,
          _name: p.name,
          _origin: 'preset',
        });
      }
    }
  }

  // 历史消息 + 当前用户输入
  //
  // 隔离保证：正文里的插图标记 [[IMG: ...]] 在这里被剥掉。
  // 模型自己写的标记绝不会被回喂给它自己，也不会占 token、污染上下文。
  // 生成出来的图片以附件形式单独存放，从不进入 content。
  const history: ChatMessage[] = chatHistory.map((m, i) => ({
    ...m,
    content: stripImageMarkers(m.content),
    _origin: 'history',
    _index: i,
  }));
  const userMsgs: ChatMessage[] = currentUserInput
    ? [
        {
          role: 'user',
          content: stripImageMarkers(currentUserInput),
          _origin: 'history',
          _index: history.length,
        },
      ]
    : [];

  const raw: ChatMessage[] = [...baseMessages, ...history, ...userMsgs];

  // ---- 阶段 2：世界书深度注入 ----
  const { hits } = triggerWorldbook(worldBooks, history, currentUserInput, {
    scanDepth: worldBookScanDepth,
  });
  const afterWorldBook = injectWorldbook(raw, hits, authorNote, authorNoteDepth);

  // ---- 阶段 3a：宏替换 ----
  const macroCtx: MacroContext = {
    char: characterCard?.name ?? (preset?.getSetting ? preset.getSetting('name_char') : undefined) ?? '',
    user:
      (preset?.getSetting ? preset.getSetting('name_user') : undefined) ?? '用户',
    description: characterCard?.description ?? '',
    scenario: characterCard?.scenario ?? '',
    persona: characterCard?.persona ?? '',
    original: characterCard?.first_mes ?? characterCard?.firstMes ?? '',
    mes_example: characterCard?.mes_example ?? characterCard?.mesExample ?? '',
    chat: history.map((m) => `${m.role}: ${m.content}`).join('\n'),
    currentMessage: currentUserInput ?? '',
    lastMessage: history[history.length - 1]?.content ?? '',
  };
  const afterMacro = applyMacrosToMessages(afterWorldBook, macroCtx);

  /*
   * ---- 阶段 3a-2：角色卡兜底注入 ----
   *
   * 问题：角色卡原本**只能**通过预设里的宏（{{char}} / {{description}} /
   * {{scenario}} …）进入提示词。但很多预设（包括用户现在用的 kemi预设，
   * 55 条）一条这样的宏都没有 —— 于是"选了角色卡却完全没效果"，
   * 用户只会以为这个功能坏了（实测确认过）。
   *
   * 规则：选了角色卡、且整个提示词里**一处都没替换出角色信息**时，
   * 把角色卡作为一条 system 消息补进去。已经引用了的预设不受影响
   * （避免同样的内容出现两遍，那会浪费 token 也干扰模型）。
   */
  const withCharacter = injectCharacterCardIfMissing(afterMacro, characterCard);

  // ---- 阶段 3b：正则 ----
  //
  // placement 说明：这些开关决定"这类消息要不要被处理"。
  // 推理过程和斜杠命令也打开 —— 否则标了这两种作用的规则会被整条跳过，
  // 用户看到的现象就是"有的正则没生效"。
  const { messages: afterRegex, hits: regexApplied } = runRegex(withCharacter, regexScripts, {
    dryRun: false,
    placement: {
      user: true,
      ai: true,
      worldInfo: true,
      reasoning: true,
      slash: true,
    },
  });

  // ---- 阶段 3c：Token 裁剪 ----
  let finalMessages: ChatMessage[] = afterRegex;
  if (contextWindowTokens && userLookbackTokens) {
    const budget = Math.max(0, contextWindowTokens - userLookbackTokens);
    const nonHistory = afterRegex.filter((m) => m._origin !== 'history');
    const historyMsgs = afterRegex.filter((m) => m._origin === 'history');
    const used = countTokens(nonHistory.map((m) => m.content).join('\n'));
    const kept: ChatMessage[] = [];
    let acc = used;
    for (let i = historyMsgs.length - 1; i >= 0; i--) {
      const t = countTokens(historyMsgs[i].content);
      if (acc + t > budget) break;
      kept.unshift(historyMsgs[i]);
      acc += t;
    }
    if (kept.length < historyMsgs.length) {
      warnings.push(`为契合 token 预算，裁剪了 ${historyMsgs.length - kept.length} 条历史消息`);
    }
    finalMessages = [...nonHistory, ...kept];
  }

  const tokenEstimate = countTokens(finalMessages.map((m) => m.content).join('\n'));

  return {
    finalMessages,
    stages: { raw, afterWorldBook, afterMacro, afterRegex },
    worldBookTriggered: hits,
    regexApplied,
    tokenEstimate,
    warnings,
  };
}

/**
 * 角色卡兜底注入。
 *
 * 背景：角色卡进入提示词的正规途径是**预设里的宏**
 * （{{char}} / {{description}} / {{scenario}} / {{personality}} /
 * {{first_mes}} / {{mes_example}} / {{persona}}）。
 * 但不少预设根本不写这些宏（用户当前的 kemi预设 55 条里一条都没有），
 * 于是"选了角色卡却完全没效果" —— 从用户视角这就是功能坏了。
 *
 * 做法：只有当提示词里**一处都没出现过角色名**时，才补一条 system 消息。
 * 已经引用过的预设不动它 —— 内容重复出现既浪费 token 也会干扰模型。
 *
 * 放在宏替换之后、正则之前：这样补进去的内容同样会被用户的正则规则处理。
 */
function injectCharacterCardIfMissing(
  messages: ChatMessage[],
  card: any,
): ChatMessage[] {
  if (!card) return messages;
  const name = String(card.name || '').trim();
  if (!name) return messages;

  // 已经被预设引用过 → 不再重复注入
  const alreadyUsed = messages.some((m) => String(m.content || '').includes(name));
  if (alreadyUsed) return messages;

  // 按 ST 的角色卡字段拼一段描述。缺哪项就跳过哪项，不写空标题。
  const parts: string[] = [`角色：${name}`];
  const add = (label: string, v: unknown) => {
    const s = String(v || '').trim();
    if (s) parts.push(`${label}：${s}`);
  };
  add('设定', card.description);
  add('性格', card.personality);
  add('场景', card.scenario);

  const example = String(card.mes_example || card.mesExample || '').trim();
  if (example) parts.push(`对话示例：\n${example}`);

  const opening = String(card.first_mes || card.firstMes || '').trim();
  if (opening) parts.push(`开场：\n${opening}`);

  const block =
    '以下是本次对话的角色设定，扮演时请遵循（但不要在正文中复述这份设定）：\n\n' +
    parts.join('\n\n');

  /*
   * 插入位置：**预设块之后、历史之前**。
   *
   * 参照 SillyTavern 的 Prompt Manager —— 角色卡相关的内容
   * （Character Description / Character Personality / Scenario）
   * 在那里是**固定的默认提示条目**，位置在
   *   Main Prompt → World Info(前) → Persona → 角色描述/性格/场景 → … → Chat History
   * 也就是说：靠后、紧挨着聊天记录，但仍在历史之前。
   *
   * 放在最前面会削弱预设里的规则（那些通常优先级更高）；
   * 放在历史中间会打断对话连贯性。
   */
  const out = [...messages];
  // 从后往前找第一条"历史"消息，插在它前面
  let insertAt = out.length;
  for (let i = out.length - 1; i >= 0; i--) {
    if (out[i]._origin === 'history') insertAt = i;
  }
  out.splice(insertAt, 0, {
    role: 'system',
    content: block,
    _origin: 'preset',
    _name: '角色卡（自动注入）',
  } as ChatMessage);
  return out;
}

// 从原始 ST 预设 JSON 中按 identifier 找 prompt（不依赖 parsecard 实例方法时用）
function findPromptInRaw(preset: any, id: string): any | null {
  const list: any[] = preset?.prompts ?? [];
  const p = list.find((x) => x.identifier === id);
  return p ?? null;
}

function injectWorldbook(
  base: ChatMessage[],
  hits: any[],
  authorNote?: string,
  authorNoteDepth = 4,
): ChatMessage[] {
  const result: ChatMessage[] = [...base];

  // 作者注释：作为 user 消息按 depth 回溯插入
  if (authorNote) {
    const idx = Math.max(0, result.length - authorNoteDepth);
    result.splice(idx, 0, {
      role: 'user',
      content: authorNote,
      _origin: 'author_note',
      _promptId: 'author_note',
      _name: "Author's Note",
    });
  }

  // 命中条目按 position asc、depth desc 排序后注入
  const sorted = [...hits].sort((a, b) => {
    if (a.position !== b.position) return a.position - b.position;
    return (b.depth || 0) - (a.depth || 0);
  });

  for (const hit of sorted) {
    const content = hit.content || '';
    if (!content) continue;
    const idx = computeInjectionIndex(result, hit.position, hit.depth || 0);
    const role: ChatMessage['role'] =
      hit.role === 1 ? 'user' : hit.role === 2 ? 'assistant' : 'system';
    result.splice(idx, 0, {
      role,
      content,
      _origin: 'worldbook',
      _promptId: `wb:${hit.uid}`,
      _name: hit.comment,
    });
  }

  return result;
}

function computeInjectionIndex(messages: ChatMessage[], position: number, depth: number): number {
  switch (position) {
    case BEFORE_CHAR:
      return findAnchor(messages, 'enhanceDefinitions') ?? 0;
    case AFTER_CHAR: {
      const i = findAnchor(messages, 'enhanceDefinitions');
      return i !== null ? i + 1 : messages.length;
    }
    case BEFORE_AUTHOR_NOTE:
      return findAnchor(messages, 'author_note') ?? messages.length;
    case AFTER_AUTHOR_NOTE: {
      const i = findAnchor(messages, 'author_note');
      return i !== null ? i + 1 : messages.length;
    }
    case AT_DEPTH:
      return Math.max(0, messages.length - Math.max(1, depth));
    case BEFORE_EXAMPLES:
      return findAnchor(messages, 'examples') ?? messages.length;
    case AFTER_EXAMPLES: {
      const i = findAnchor(messages, 'examples');
      return i !== null ? i + 1 : messages.length;
    }
    case OUTLET:
      return findAnchor(messages, 'world_info_outlet') ?? messages.length;
    default:
      return messages.length;
  }
}

function findAnchor(messages: ChatMessage[], id: string): number | null {
  for (let i = 0; i < messages.length; i++) {
    if (messages[i]._promptId === id) return i;
  }
  return null;
}
