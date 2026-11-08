/**
 * SPreset / Tavo 预设脚本运行时（通用层）。
 * ===========================================================================
 * 为什么需要它
 * ---------------------------------------------------------------------------
 * Meowssiah 这类 Tavo 预设的「底部可折叠摘要 / 行动选项」不是靠显示正则凭空
 * 生成的：显示正则（如「前端综合面板」）只认 `<options>` / `<summary>` /
 * `<comment>` 这些标签。标签有两个来源：
 *
 *   ① 主路径（默认）：预设提示词本身写着「正文用 <game> 包上」「用 <summary>
 *      包住」「在 <comment> 里放留言」「<options><option>…</option></options>」，
 *      **模型直接吐标签** → 显示正则 → 整页 HTML 卡片 → WebView / iframe。
 *   ② 可选路径：模型用 tool_calls 返回结构化字段，由
 *      OutputPreprocessing.toolCallFormatter 包成同样的标签 → 同一条渲染链路。
 *
 * 2026-11-09 修订（重要）：第一版误判「非得靠 ② 才有标签」，于是执行
 * FinalRequestProcessing 把 35 条 system 提示词整段替换成 8 条 few-shot，并
 * 强制 tool_choice=required。结果角色设定只剩 context 工具描述里一份，模型
 * 拿不到上下文，直接按 Gemini 默认身份拒答（用户实测：「我是 Gemini…无法
 * 继续参与这个设定…也没有名为 soliumbra_reply 的工具可以调用」）。
 *
 * 现在默认走 ①：保留全部提示词，只用 stripPrivateDirectives 剥掉 Tavo 私有
 * 指令宏（<<<|catgodml|…>>> / <run_tool_replacement> / <|sep|> / <|merge|>），
 * 不注入 tools、不强制 tool_choice、不改写 messages。这些宏是给预设脚本用的，
 * 原样发给模型会污染提示词 —— 这才是当初「正则开了却没生效」的真正原因。
 * applyRequest / formatToolCalls 保留为可选能力，默认不介入请求。
 *
 * 为什么是「通用运行时」而不是针对性补丁
 * ---------------------------------------------------------------------------
 * 这两段脚本都是**纯函数**，零宿主 API 依赖（实测不引用 SillyTavern / window /
 * document / jQuery）。所以不需要把 starowo/SPreset 那套 111KB 的 SillyTavern
 * 扩展搬进安卓工程 —— 只要把预设自带的脚本源码取出来，在 WebView 的 JS 引擎里
 * `new Function` 执行即可。
 *
 * 本模块**不认识任何具体预设**：不写死 `soliumbra_reply`、`registerPart`、
 * `summary` / `options` 这些名字。它只做三件事：
 *   1. 通用地执行 `ToolBindings[*].code`，把结果转成 OpenAI `tools` 定义；
 *   2. 通用地执行 `FinalRequestProcessing.script({request})`，取回改写后的请求；
 *   3. 通用地执行 `OutputPreprocessing.toolCallFormatter.script({calls,…})`，
 *      把响应里的 tool_calls 转成文本。
 * 换一个同样带 `extensions.SPreset` 的预设，只要脚本接口一致，直接就能用。
 *
 * 降级策略
 * ---------------------------------------------------------------------------
 *  · 预设没有 `extensions.SPreset` → 返回 no-op runtime，生成链路与旧版**逐字节一致**；
 *  · 脚本缺失 / 编译失败 / 执行抛错 → 该环节降级（原请求原样发、原文原样回显），
 *    绝不让一段预设脚本把聊天搞崩；
 *  · 脚本执行返回的结果不合预期 → 同上。
 *
 * 只执行预设自带的纯函数脚本，不加载任何外部扩展引擎、不访问网络、不碰宿主桥。
 */

import type { ChatMessage } from './types';
import type { ToolCallAccum } from './apiClient';

/** `FinalRequestProcessing` 改写后的请求片段。 */
export interface SpRequestPatch {
  /** 脚本重写后的完整消息对象数组（含 tool_calls / role:'tool' 等，须原样透传） */
  messages: any[];
  tools: unknown[];
  toolChoice?: unknown;
  /** 诊断用：实际注入的工具名 */
  toolNames: string[];
}

export interface SpresetRuntime {
  /** 是否至少有一环脚本可用 */
  active: boolean;
  /** 预设名（诊断日志用） */
  name: string;
  hasFinalRequest: boolean;
  hasFormatter: boolean;
  /** 运行期诊断信息，供控制台输出 / 文档说明 */
  notes: string[];
  /**
   * 请求前：把组装好的 messages 交给预设脚本，取回 tools / tool_choice / 重写后的 messages。
   * 返回 null 表示「本次不启用工具调用」（脚本未触发或降级），调用方按原链路发送。
   */
  applyRequest(config: Record<string, unknown>, messages: ChatMessage[]): SpRequestPatch | null;
  /** 响应后：把流式合并好的 tool_calls 交给预设脚本，产出最终文本。 */
  formatToolCalls(calls: ToolCallAccum[], defaultText: string): string;
}

// ---------------------------------------------------------------------------
// 脚本源码提取：预设里同一份脚本有 3 种落盘形态
//   · 字符串：直接就是函数源码
//   · 对象：{ enabled, script }
//   · JSON 字符串：'{ "enabled": true, "script": "…" }'（被二次序列化过）
// ---------------------------------------------------------------------------
function asScriptSource(v: unknown): string | null {
  if (typeof v === 'string') {
    const s = v.trim();
    if (!s) return null;
    if (s.startsWith('{')) {
      try {
        const parsed = JSON.parse(s);
        if (parsed && typeof parsed.script === 'string') return parsed.script;
      } catch {
        /* 不是 JSON，就当它是函数源码 */
      }
    }
    return s;
  }
  if (v && typeof v === 'object') {
    const s = (v as any).script;
    if (typeof s === 'string' && s.trim()) return s;
  }
  return null;
}

/** 没写 enabled 视为启用；显式 false 才停用。 */
function isEnabled(v: unknown): boolean {
  if (v && typeof v === 'object' && 'enabled' in (v as any)) {
    return (v as any).enabled !== false;
  }
  return true;
}

/**
 * 编译「函数表达式」源码：`({ request }) => {...}` / `function (x) {...}`。
 * 用 `new Function` 而非 eval：脚本拿到的是一个独立函数，不注入任何宿主全局。
 *
 * 预设脚本末尾**带不带分号都有**：Meowssiah 的 FinalRequestProcessing 末尾是
 * `}`（纯表达式），toolCallFormatter 末尾却是 `};`（表达式语句）。若一律用
 * `return (…)` 包裹，后者会变成 `return (…;)` 直接语法错。所以先试「直接 return」，
 * 失败再退回括号包裹，两种书写习惯都吃下。
 */
function compileFn(src: string): ((...args: any[]) => any) | null {
  try {
    const fn = new Function(`return ${src}\n`)();
    if (typeof fn === 'function') return fn;
  } catch {
    /* 试下一种 */
  }
  try {
    const fn = new Function(`return (${src}\n);`)();
    if (typeof fn === 'function') return fn;
  } catch {
    /* 两种都不行 */
  }
  return null;
}

/**
 * 编译「函数体」源码：`return { name, description, parameters, action }`。
 * ToolBindings.code 是语句体（开头就是 return），不是表达式，得单独处理。
 */
function compileBody(src: string): (() => any) | null {
  try {
    return new Function(src) as () => any;
  } catch {
    return null;
  }
}

/**
 * 通用解析 `SPreset.ToolBindings`。
 *
 * 每个绑定是一段 `return {…}` 代码，执行后得到工具定义对象。我们只取
 * `{name, description, parameters}` 转成 OpenAI function tool —— `action`
 * 是给 SPreset 引擎「执行工具」用的异步函数，本链路不需要执行任何工具
 * （模型只是借工具返回结构化字段，属于单轮结构化输出，不是智能体循环）。
 */
function resolveToolBindings(sp: any): {
  tools: unknown[];
  names: string[];
  notes: string[];
} {
  const tools: unknown[] = [];
  const names: string[] = [];
  const notes: string[] = [];
  const tb = sp?.ToolBindings;
  if (!tb || typeof tb !== 'object') return { tools, names, notes };

  for (const [id, binding] of Object.entries(tb as Record<string, any>)) {
    if (!binding || typeof binding !== 'object') continue;
    if (binding.enabled === false || binding.valid === false) continue;
    const code = typeof binding.code === 'string' ? binding.code : '';
    if (!code.trim()) continue;

    const fn = compileBody(code);
    if (!fn) {
      notes.push(`ToolBinding ${id} 代码编译失败，已跳过`);
      continue;
    }
    let def: any;
    try {
      def = fn();
    } catch (e) {
      notes.push(`ToolBinding ${id} 执行失败：${errMsg(e)}`);
      continue;
    }
    if (!def || typeof def !== 'object' || !def.name) continue;
    tools.push({
      type: 'function',
      function: {
        name: String(def.name),
        description: String(def.description ?? ''),
        parameters: def.parameters ?? { type: 'object', properties: {} },
      },
    });
    names.push(String(def.name));
  }
  if (names.length) notes.push(`已解析 ${names.length} 个工具绑定：${names.join(', ')}`);
  return { tools, names, notes };
}

function errMsg(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

function noopRuntime(): SpresetRuntime {
  return {
    active: false,
    name: '',
    hasFinalRequest: false,
    hasFormatter: false,
    notes: [],
    applyRequest: () => null,
    formatToolCalls: (_calls, defaultText) => defaultText,
  };
}

/**
 * 找出预设里的 SPreset 配置，含「内嵌备份」自愈。
 *
 * 早期导入器按 64KB 上限丢掉过 extensions.SPreset，旧存档因此永远读不到
 * 脚本。但这类预设通常会在 prompts 里塞一条禁用的「SPreset配置」提示词，
 * 内容就是**完整配置 JSON**（Meowssiah 实测与 extensions 那份逐字节相同）。
 * 所以顶层读不到时，扫一遍 prompts 就能自愈，不必逼用户重新导入。
 *
 * 不硬编码提示词名字或下标，只按「内容是可解析的配置对象」识别：
 * 至少带 ToolBindings 或 FinalRequestProcessing 之一。
 */
export function findSpresetConfig(presetRaw: any): any | null {
  const direct =
    presetRaw?.extensions?.SPreset ?? presetRaw?.data?.extensions?.SPreset ?? null;
  if (direct && typeof direct === 'object') return direct;

  const lists: unknown[] = [presetRaw?.prompts, presetRaw?.data?.prompts];
  for (const list of lists) {
    if (!Array.isArray(list)) continue;
    for (const p of list as any[]) {
      const s = typeof p?.content === 'string' ? p.content.trim() : '';
      // 配置至少上千字符；短 prompt 不必尝试 JSON.parse
      if (s.length < 1000 || !s.startsWith('{')) continue;
      try {
        const obj = JSON.parse(s);
        if (
          obj &&
          typeof obj === 'object' &&
          (obj.ToolBindings || obj.FinalRequestProcessing)
        ) {
          return obj;
        }
      } catch {
        /* 不是配置 JSON，跳过 */
      }
    }
  }
  return null;
}

/**
 * 请求前的**通用清洗**：把 Tavo / SPreset 的私有指令标记从消息里剥掉。
 *
 * 这是本轮修复的核心。上一版误判「必须靠工具调用才有 <summary>/<options>
 * 标签」，于是执行 FinalRequestProcessing 把 35 条 system 提示词整段换成
 * 8 条 few-shot 并强制 tool_choice=required —— 角色设定只剩工具描述里一份，
 * 模型拿不到上下文，直接按 Gemini 默认身份拒答。
 *
 * 实际上预设的提示词本身就写着「正文用 <game> 包上」「用 <summary> 包住」
 * 「<comment>」「<options><option>…</option></options>」，全是给模型的自然
 * 语言指令。只要**保留全文、剥掉私有宏**，模型就会直接吐标签，已有的
 * 「前端综合面板」正则就能替换成美化卡片 —— 这也正是 Tavo 的行为
 * （用户对照：Tavo 输出正常，只是正则替换偶尔不完美）。
 *
 * 为什么这些宏必须剥：
 *   · <<<|catgodml|registerPart:…>>> / setLastMessage / deepthink 等是
 *     给预设脚本解析的，不是给模型读的；
 *   · <run_tool_replacement> 是脚本的触发开关；
 *   · <|sep|> / <|merge|> 是 ChatSquash 的分隔符（配置里叫 conditional_tag）。
 * 原样发出去会污染提示词，模型要么困惑、要么照抄 few-shot 里的拒答反例。
 *
 * 通用性：不写死某份预设的标记名 —— 三竖线宏按形态整块删，
 * <|xxx|> 按形态删，触发标记从预设自带脚本里动态提取，再带一组兜底常量。
 * 换个用同类机制的预设同样生效；不含这些标记的预设逐字节不变。
 */
const FALLBACK_MARKERS = ['<run_tool_replacement>'];

export function stripPrivateDirectives(messages: ChatMessage[], presetRaw?: any): ChatMessage[] {
  const sp = findSpresetConfig(presetRaw);
  const markers = new Set<string>(FALLBACK_MARKERS);

  // 从预设脚本里动态取 marker 常量，避免硬编码
  const src = asScriptSource(sp?.FinalRequestProcessing) ?? '';
  for (const m of src.matchAll(/const\s+marker\s*=\s*['"`]([^'"`]+)['"`]/g)) {
    if (m[1]) markers.add(m[1]);
  }
  const ct = sp?.ChatSquash?.conditional_tag;
  if (typeof ct === 'string' && ct) markers.add(ct);

  return messages.map((msg) => {
    const content = (msg as any).content;
    if (typeof content !== 'string') return msg;
    // ① <<<|任意标识|…>>> 整块删（宏指令，不是正文）
    let text = content.replace(/<<<\|[^|]*\|[\s\S]*?>>>/g, '');
    // ② 脚本声明的触发标记 / ChatSquash 分隔符
    for (const mk of markers) if (mk) text = text.split(mk).join('');
    // ③ 兜底：<|xxx|> 形态的私有分隔符
    text = text.replace(/<\|[A-Za-z_]+\|>/g, '');
    // ④ ST 行内注释 {{// … }}：作者声明 / 给用户看的调参说明，不是给模型的
    //    （macro.ts 只处理标准宏，实测这份预设里残留 3 块，会污染提示词）
    text = text.replace(/\{\{\/\/[\s\S]*?\}\}/g, '');
    const trimmed = text.trim();
    return trimmed === content ? msg : ({ ...msg, content: trimmed } as ChatMessage);
  });
}

/**
 * 从一份预设原始对象里装载 SPreset 运行时。
 *
 * 预设可能有扁平 `{ extensions }` 与嵌套 `{ data: { extensions } }` 两种形态
 * （parsecard 的 fromJSON/toJSON 会保留顶层 extensions，这里两种都认）。
 */
export function loadSpresetRuntime(presetRaw: any): SpresetRuntime {
  const noop = noopRuntime();
  if (!presetRaw || typeof presetRaw !== 'object') return noop;

  // 顶层读不到时会自动扫 prompts 里的内嵌配置备份（见 findSpresetConfig）
  const sp = findSpresetConfig(presetRaw);
  if (!sp || typeof sp !== 'object') return noop;

  const notes: string[] = [];
  const name = String(presetRaw?.name || presetRaw?.data?.name || 'SPreset 预设');

  // ── ① 请求前脚本 ──
  let finalReq: ((...a: any[]) => any) | null = null;
  if (isEnabled(sp.FinalRequestProcessing)) {
    const src = asScriptSource(sp.FinalRequestProcessing);
    if (src) finalReq = compileFn(src);
    if (!finalReq) notes.push('FinalRequestProcessing 缺失或编译失败，本次按无脚本降级');
  } else {
    notes.push('FinalRequestProcessing 在预设里被禁用');
  }

  // ── ② 响应后格式化脚本 ──
  let formatter: ((...a: any[]) => any) | null = null;
  const outPre = sp?.OutputPreprocessing;
  const tcf = outPre?.toolCallFormatter;
  if (isEnabled(outPre) && isEnabled(tcf)) {
    const src = asScriptSource(tcf);
    if (src) formatter = compileFn(src);
    if (!formatter) notes.push('toolCallFormatter 缺失或编译失败，工具调用将按原样回显');
  } else {
    notes.push('OutputPreprocessing / toolCallFormatter 在预设里被禁用');
  }

  // ── ③ 工具绑定 → tools 定义 ──
  const { tools: boundTools, notes: tbNotes } = resolveToolBindings(sp);
  notes.push(...tbNotes);

  const active = !!finalReq || !!formatter;
  if (!active) return { ...noop, name, notes };

  return {
    active,
    name,
    hasFinalRequest: !!finalReq,
    hasFormatter: !!formatter,
    notes,

    applyRequest(config, messages) {
      if (!finalReq) return null;

      // 交给脚本的是一份可随意改写的深拷贝 request；
      // 采样参数等一并带上，脚本可能读取（例如按 model 前缀切换行为）。
      const baseMessages = messages.map((m) => ({ role: m.role, content: m.content }));
      const request: Record<string, unknown> = {
        ...config,
        messages: baseMessages,
        ...(boundTools.length ? { tools: boundTools } : {}),
      };
      const snapshot = JSON.stringify(request.messages);

      let out: any;
      try {
        out = finalReq({ request: JSON.parse(JSON.stringify(request)) });
      } catch (e) {
        notes.push(`FinalRequestProcessing 执行失败：${errMsg(e)}；本次按原链路发送`);
        return null;
      }
      if (!out || typeof out !== 'object' || !Array.isArray(out.messages)) return null;

      const tools: unknown[] = Array.isArray(out.tools) ? out.tools : [];
      if (!tools.length) return null; // 没注入工具 = 没启用工具调用

      /*
       * 脚本只在请求里检测到触发标记（如 <run_tool_replacement>）时才改写；
       * 未触发时原样 return request。这里用「tool_choice 被设置」或
       * 「messages 内容与输入不同」判断是否真的触发 —— 不硬编码标记名，
       * 换个预设只要脚本同样改写请求就能识别。
       */
      const rewritten = JSON.stringify(out.messages) !== snapshot;
      const triggered = out.tool_choice !== undefined || rewritten;
      if (!triggered) return null;

      const toolNames = tools
        .map((t: any) => String(t?.function?.name ?? t?.name ?? ''))
        .filter(Boolean);
      notes.push(
        `FinalRequestProcessing 已触发：注入 ${toolNames.length} 个工具 [${toolNames.join(', ')}]，` +
          `tool_choice=${String(out.tool_choice ?? 'auto')}，messages 重写 ${rewritten ? '是' : '否'}`,
      );
      return {
        messages: out.messages,
        tools,
        toolChoice: out.tool_choice,
        toolNames,
      };
    },

    formatToolCalls(calls, defaultText) {
      if (!formatter || !Array.isArray(calls) || calls.length === 0) return defaultText;

      // 同时给出两种形态，兼容只读 `call.arguments` 的脚本和读
      // `call.function.arguments` 的脚本 —— 不假设预设内部实现。
      const shaped = calls.map((c) => ({
        id: c.id,
        type: c.type || 'function',
        function: { name: c.name, arguments: c.arguments },
        name: c.name,
        arguments: c.arguments,
      }));

      try {
        const out = formatter({ calls: shaped, defaultText });
        if (typeof out === 'string' && out) {
          notes.push(`toolCallFormatter 产出 ${out.length} 字符标签文本`);
          return out;
        }
        return defaultText;
      } catch (e) {
        notes.push(`toolCallFormatter 执行失败：${errMsg(e)}；按原文回显`);
        return defaultText;
      }
    },
  };
}
