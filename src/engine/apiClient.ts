import type { ChatMessage } from './types';

// OpenAI 兼容 API 调用：fetch + ReadableStream 流式输出。
// 用户填写 base_url / api_key / model / temperature / max_tokens。

export interface ApiConfig {
  baseUrl: string; // 例 https://api.openai.com/v1
  apiKey: string;
  model: string;
  temperature?: number;
  topP?: number;
  maxTokens?: number;
  frequencyPenalty?: number;
  presencePenalty?: number;
  extraBody?: Record<string, unknown>;
}

export interface UsageInfo {
  prompt_tokens?: number;
  completion_tokens?: number;
  total_tokens?: number;
}

/** 一次工具调用增量（OpenAI Chat Completions 流式格式）。 */
export interface ToolCallAccum {
  index: number;
  id?: string;
  type?: string;
  name?: string;
  arguments: string;
}

/** SPreset / function calling 的请求扩展。不传时行为与旧版完全一致。 */
export interface ToolRequestOptions {
  tools?: unknown[];
  toolChoice?: unknown;
  /**
   * 预设脚本（FinalRequestProcessing）改写后的**完整消息对象数组**。
   * 它会重写整段 messages 成 few-shot 结构，里面带 tool_calls / tool_call_id /
   * role:'tool' / thoughtSignature 等标准或渠道专有字段 —— 这些必须原样发给 API，
   * 不能用 {role, content} 重新拍扁，否则工具调用示例失效、模型不会照着调工具。
   * 不传时仍按旧的 {role, content} 映射，普通预设零影响。
   */
  rawMessages?: any[];
  /** 流式 tool_calls 增量回调（字段已按 index 合并成完整对象）。 */
  onToolCalls?: (calls: ToolCallAccum[]) => void;
}

export interface StreamCallbacks {
  onToken?: (delta: string) => void;
  /**
   * 思维链增量。
   *
   * 推理模型会把思考过程放在 delta.reasoning_content（或 reasoning）里，
   * 和正文分开。默认不显示给用户 —— 用户要的是故事，不是模型的草稿纸。
   * 界面可以拿它做"正在思考…"之类的提示。
   */
  onReasoning?: (delta: string) => void;
  onError?: (err: Error) => void;
  onDone?: (full: string, usage?: UsageInfo, toolCalls?: ToolCallAccum[]) => void;
}

/**
 * 归一化请求地址：用户可能把完整端点粘进来，
 * 这里剥掉 /chat/completions 之类的结尾，避免拼成 /v1/chat/completions/chat/completions。
 */
export function resolveChatUrl(baseUrl: string): string {
  const base =
    (baseUrl || '')
      .trim()
      .replace(/\/+$/, '')
      .replace(/\/chat\/completions$/i, '')
      .replace(/\/+$/, '') || 'https://api.openai.com/v1';
  return `${base}/chat/completions`;
}

/** 归一化后用于拉取模型列表的地址 */
export function resolveModelsUrl(baseUrl: string): string {
  const base =
    (baseUrl || '')
      .trim()
      .replace(/\/+$/, '')
      .replace(/\/(chat\/completions|models)$/i, '')
      .replace(/\/+$/, '') || 'https://api.openai.com/v1';
  return `${base}/models`;
}

export async function streamChatCompletion(
  config: ApiConfig,
  messages: ChatMessage[],
  callbacks: StreamCallbacks = {},
  signal?: AbortSignal,
  toolOptions?: ToolRequestOptions,
): Promise<string> {
  const url = resolveChatUrl(config.baseUrl);
  const body: Record<string, unknown> = {
    model: config.model,
    messages: toolOptions?.rawMessages ?? messages.map((m) => ({ role: m.role, content: m.content })),
    stream: true,
    ...(config.extraBody ?? {}),
  };
  // SPreset 的 FinalRequestProcessing 会往请求里塞 tools / tool_choice：
  // 模型被强制用工具返回结构化字段（think_nya / game_content / summary …），
  // 再由 toolCallFormatter 包成标签交给显示正则。不传就完全不发这两个字段。
  if (toolOptions?.tools?.length) body.tools = toolOptions.tools;
  if (toolOptions?.toolChoice !== undefined) body.tool_choice = toolOptions.toolChoice;
  if (config.temperature !== undefined) body.temperature = config.temperature;
  if (config.topP !== undefined) body.top_p = config.topP;
  if (config.maxTokens !== undefined) body.max_tokens = config.maxTokens;
  if (config.frequencyPenalty !== undefined) body.frequency_penalty = config.frequencyPenalty;
  if (config.presencePenalty !== undefined) body.presence_penalty = config.presencePenalty;

  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (config.apiKey) headers.Authorization = `Bearer ${config.apiKey}`;

  let full = '';
  let reasoning = '';
  let usage: UsageInfo | undefined;
  /**
   * 流式 tool_calls 是**分片**的：同一 index 的多个 delta 里，
   * id / name 只出现在第一片，arguments 则一段段拼起来。
   * 按 index 归并，最后交给 onDone（调用方再交给 toolCallFormatter）。
   */
  const toolAcc = new Map<number, ToolCallAccum>();
  const toolCallsList = (): ToolCallAccum[] =>
    [...toolAcc.values()].sort((a, b) => a.index - b.index);
  const mergeToolCalls = (calls: unknown): void => {
    if (!Array.isArray(calls)) return;
    for (const c of calls) {
      if (!c || typeof c !== 'object') continue;
      const raw = c as Record<string, any>;
      // 没有 index 的中转按到达顺序补一个，避免多工具全挤到 0 号
      const idx = typeof raw.index === 'number' ? raw.index : toolAcc.size;
      let acc = toolAcc.get(idx);
      if (!acc) {
        acc = { index: idx, arguments: '' };
        toolAcc.set(idx, acc);
      }
      if (raw.id) acc.id = String(raw.id);
      if (raw.type) acc.type = String(raw.type);
      const fn = raw.function;
      if (fn && typeof fn === 'object') {
        if (fn.name) acc.name = String(fn.name);
        if (typeof fn.arguments === 'string') acc.arguments += fn.arguments;
      }
      // 少数中转把字段直接摊在调用对象上，而不是塞进 function
      if (!fn && typeof raw.name === 'string') acc.name = raw.name;
      if (!fn && typeof raw.arguments === 'string') acc.arguments += raw.arguments;
    }
  };
  /**
   * finish 只能触发一次，并且**必须等 onDone 处理完**。
   *
   * 这里踩过两个坑：
   *
   * 1）重复触发：读到 [DONE] 时调用一次，但那个 break 只跳出内层 for，
   *    外层 while 继续读到流结束、末尾又调一次 —— 同一段回复存了两遍。
   *    用 finished 标志挡住。
   *
   * 2）**没 await onDone**（更隐蔽的那个）：onDone 是 async 的，里面要
   *    把消息落库（commitDraft / addMessage 都是异步写盘）。不 await 的话
   *    `await streamChatCompletion(...)` 会先返回，调用方的 finally 立刻
   *    把 streaming 置回 false —— 而此刻消息还没提交完。
   *    结果：已提交的消息和"流式块"同时出现在界面上，看起来就是
   *    **同一条回复显示了两遍**。现在 await 它，finally 一定在提交之后才跑。
   */
  let finished = false;
  const finish = async (text: string) => {
    if (finished) return;
    finished = true;
    const calls = toolCallsList();
    if (calls.length && toolOptions?.onToolCalls) {
      try {
        toolOptions.onToolCalls(calls);
      } catch {
        /* 脚本回调异常不能影响主链路落盘 */
      }
    }
    await callbacks.onDone?.(text, usage, calls);
  };

  try {
    const resp = await fetch(url, {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
      signal,
    });
    if (!resp.ok || !resp.body) {
      const text = await resp.text().catch(() => '');
      throw new Error(`HTTP ${resp.status} ${resp.statusText}: ${text.slice(0, 500)}`);
    }

    const reader = resp.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';

    outer: while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      // SSE 按 \n\n 分块
      const parts = buffer.split('\n\n');
      buffer = parts.pop() || '';
      for (const part of parts) {
        for (const line of part.split('\n')) {
          const t = line.trim();
          if (!t.startsWith('data:')) continue;
          const data = t.slice(5).trim();
          if (data === '[DONE]') {
            await finish(full);
            break outer;
          }
          try {
            const json = JSON.parse(data);
            if (json.usage) usage = json.usage;
            const choice = json.choices?.[0] ?? {};
            const d = choice.delta ?? {};

            // 工具调用增量：流式在 delta.tool_calls，非流式/一次性在 message.tool_calls
            mergeToolCalls(d.tool_calls);
            mergeToolCalls(choice.message?.tool_calls);

            /*
             * 思维链走**独立字段**，不会混在 content 里。
             *
             * 多数推理模型（DeepSeek-R1 系、各家中转的 thinking 模型）会把
             * 推理过程放在 delta.reasoning_content / delta.reasoning，
             * 正文只放在 delta.content。
             * 有些中转则把两者**拼在同一个 content 字段**里，
             * 那就只能靠调用方按标记切分（见 engine/reasoning.ts）。
             *
             * 这里两件事都做：
             *   1. 独立字段的推理单独回调，调用方可以不显示它
             *   2. 正文照旧累积
             */
            const reasoningDelta: string =
              d.reasoning_content ?? d.reasoning ?? choice.message?.reasoning_content ?? '';
            if (reasoningDelta) {
              reasoning += reasoningDelta;
              callbacks.onReasoning?.(reasoningDelta);
            }

            const delta: string = d.content || choice.message?.content || '';
            if (delta) {
              full += delta;
              callbacks.onToken?.(delta);
            }
          } catch {
            // 跳过格式异常的 chunk
          }
        }
      }
    }
    // 流正常结束但没有 [DONE]（有些中转不发）—— 这里补一次；
    // finish() 内部保证不会重复触发
    await finish(full);
    return full;
  } catch (err) {
    const e = err instanceof Error ? err : new Error(String(err));
    callbacks.onError?.(e);
    throw e;
  }
}

// 非流式版本（用于 LLM 优化生图提示词等需要完整结果的场景）
export async function chatCompletion(
  config: ApiConfig,
  messages: { role: 'system' | 'user' | 'assistant'; content: string }[],
  signal?: AbortSignal,
): Promise<string> {
  const url = resolveChatUrl(config.baseUrl);
  const body: Record<string, unknown> = {
    model: config.model,
    messages,
    stream: false,
    ...(config.extraBody ?? {}),
  };
  if (config.temperature !== undefined) body.temperature = config.temperature;
  if (config.topP !== undefined) body.top_p = config.topP;
  if (config.maxTokens !== undefined) body.max_tokens = config.maxTokens;

  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (config.apiKey) headers.Authorization = `Bearer ${config.apiKey}`;

  const resp = await fetch(url, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
    signal,
  });
  if (!resp.ok) {
    const text = await resp.text().catch(() => '');
    throw new Error(`HTTP ${resp.status} ${resp.statusText}: ${text.slice(0, 500)}`);
  }
  const json = await resp.json();
  return json.choices?.[0]?.message?.content ?? '';
}
