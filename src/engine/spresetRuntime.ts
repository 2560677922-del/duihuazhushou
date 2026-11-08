/**
 * SPreset / Tavo 预设脚本运行时（通用层）。
 * ===========================================================================
 * 为什么需要它
 * ---------------------------------------------------------------------------
 * Meowssiah 这类 Tavo 预设的「底部可折叠摘要 / 行动选项」不是靠显示正则凭空
 * 生成的：显示正则（如「前端综合面板」）只认 `<options>` / `<summary>` /
 * `<comment>` 这些标签。而标签是**模型用工具调用返回的结构化字段**，再由预设
 * 自带的脚本包成标签的：
 *
 *   模型输出(tool_calls)
 *     └─ OutputPreprocessing.toolCallFormatter  → `<summary>…</summary>` 等标签
 *         └─ 显示正则「前端综合面板」           → 整页 HTML 卡片
 *             └─ WebView / iframe                → 美化折叠面板
 *
 * 同时，请求也不是直接把组装好的 messages 发出去：预设的
 * `FinalRequestProcessing` 会在请求前扫描消息里的 `<<<|catgodml|…>>>` 指令、
 * 剥离宏、动态拼出工具 schema、注入 `tools` / `tool_choice`，甚至把整段 messages
 * 重写成 few-shot 结构。少了这一层，模型根本不会返回 tool_calls，底部面板自然
 * 永远不出现 —— 这就是之前「正则开了却没生效」的真正原因。
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
 * 从一份预设原始对象里装载 SPreset 运行时。
 *
 * 预设可能有扁平 `{ extensions }` 与嵌套 `{ data: { extensions } }` 两种形态
 * （parsecard 的 fromJSON/toJSON 会保留顶层 extensions，这里两种都认）。
 */
export function loadSpresetRuntime(presetRaw: any): SpresetRuntime {
  const noop = noopRuntime();
  if (!presetRaw || typeof presetRaw !== 'object') return noop;

  const sp =
    presetRaw?.extensions?.SPreset ??
    presetRaw?.data?.extensions?.SPreset ??
    null;
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
