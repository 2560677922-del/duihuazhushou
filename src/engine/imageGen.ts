// 生图服务：LLM 优化提示词 → 发送给生图 API → 返回 base64 图片
// ---------------------------------------------------------------------------
// 支持的提供方：
//  - novelai         NovelAI 官方（nekoai-js，需要持久化 Token）
//  - novelai-relay   NovelAI 中转（请求体与官方完全一致，只换地址）
//  - novelai-openai  NovelAI 中转（OpenAI /images/generations 风格）
//  - dall-e          OpenAI DALL·E / gpt-image
//  - custom          任意自定义生图端点
//
// 兼容旧的 novelai-openai 配置：它会按“OpenAI 风格中转”处理。

import { NovelAI } from 'nekoai-js';
import { chatCompletion } from './apiClient';
import type { ApiConfig } from './apiClient';
import {
  buildNovelAIRequest,
  extractImageFromResponse,
  describeHttpError,
  defaultSamplerFor,
  randomSeed,
  NOVELAI_HEAVY_UC,
  candidateImageEndpoints,
  candidateModelsEndpoints,
  candidateNovelAIEndpoints,
} from './novelai';
import type { NovelAIAction } from './novelai';
import { httpRequest } from '../lib/http';

export type ImageProvider =
  | 'novelai'
  | 'novelai-relay'
  | 'novelai-openai'
  | 'dall-e'
  | 'custom'
  /*
   * 本地/局域网自己部署的出图服务。
   *
   * 和 custom 的区别（为什么不干脆复用 custom）：
   *   custom 是"用户自己拼请求体"的兜底，字段名按通用约定瞎猜；
   *   而本地部署这类服务（ComfyUI 包装、Z-Image、StableSwarm 之类）
   *   实际说的是 **OpenAI 的图像协议**：POST /v1/images/generations，
   *   返回 { data: [{ b64_json }] }。用 custom 去打它一般会 422。
   *
   *   ⚠ 更要命的一点：本地服务多半是 **http://**，而 APK 的页面来自
   *   https://dbpg.local —— 浏览器会把这种请求当"混合内容"直接拦掉，
   *   而 localhost / 127.0.0.1 属于"可信来源"不受限。这正好解释了
   *   "本地的能用、热点里那台连不上、第三方 https 平台也正常"。
   *   端上已在 MainActivity 里放开 mixedContentMode，这里再对
   *   局域网地址做一次明确支持。
   */
  | 'local';

/**
 * 默认的 LLM → 生图提示词模板。
 *
 * 设计原则（按用户要求反复收过几轮）：
 *   · **短**。这是给模型看的指令，不是说明书，手机上也不用读一屏。
 *   · 前三条讲"输出什么"（标签形式、描述什么、别瞎编）—— 保持不变。
 *   · **动作与互动要具体**。以前只写"动作、互动"，模型就给个
 *     `standing`/`looking at viewer` 了事，画面很空。
 *     现在明确要求：谁在做什么、和谁/什么互动、手在哪、视线朝哪。
 *   · **别提画师串和负面提示词**。用户说得对：不提，模型压根不会给。
 *     以前写了"不要输出 artist:xxx / masterpiece"，反而等于把那些词
 *     塞进了上下文里。现在一个字都不提，只在程序侧剔除（stripStyleTags）。
 */
export const DEFAULT_PROMPT_TEMPLATE = `把下面的{{contextLabel}}转成英文 Danbooru 画面标签。

规则：
1. 英文标签，逗号分隔，重要的放前面。
2. 描述角色（人数、发色发型、瞳色、服装、表情）与环境（背景、时间、天气、光线、镜头）。
3. 只写场景里真实存在或能自然推断的内容，不要臆造。
4. **动作与互动要写具体**：谁在做什么、和谁或什么东西互动、
   手在接触什么、身体朝向、视线看向哪里。用 2~4 个短语说清，
   不要只写 standing / sitting 这种笼统词。
5. 只输出 JSON，不要解释。

输出格式：
{"prompt": "标签，英文逗号分隔", "tags": ["主要标签"]}`;

export const DEFAULT_NEGATIVE_PROMPT =
  'lowres, bad anatomy, bad hands, text, error, missing fingers, extra digit, ' +
  'fewer digits, cropped, worst quality, low quality, normal quality, jpeg artifacts, ' +
  'signature, watermark, username, blurry, artist name';

export interface ImageGenConfig {
  provider: ImageProvider;
  // NovelAI 官方
  novelaiToken?: string;
  novelaiModel?: string;
  // 中转 / OpenAI 兼容 / 自定义
  baseUrl?: string;
  apiKey?: string;
  model?: string;
  /** NovelAI 官方端点主机（中转时一般不需要改） */
  host?: string;
  /**
   * 显式指定的生图端点。
   * 留空则自动在候选路径间探测（/v1/images/generations 等）；
   * 探测成功后可在设置页一键回存到这里，之后就不再试错。
   */
  endpoint?: string;
  // 请求体与官方完全一致的额外参数
  ucPreset?: number;
  qualityToggle?: boolean;
  cfgRescale?: number;
  noiseSchedule?: string;
  sm?: boolean;
  smDyn?: boolean;
  autoSmea?: boolean;
  dynamicThresholding?: boolean;
  // 生图参数
  sampler?: string;
  steps?: number;
  width?: number;
  height?: number;
  cfgScale?: number;
  seed?: number;
  denoisingStrength?: number;
  /** 固定画师串，永远排在最前面 */
  artistTags?: string;
  qualityTags?: string;
  negativePrompt?: string;
  /** LLM 优化提示词用的模板，支持 {{contextText}} / {{contextLabel}} */
  promptTemplate?: string;
  // 图生图
  referenceImage?: string;
  // LLM 提示词优化
  llmConfigId?: string;
  useLlmOptimization?: boolean;
}

export interface ImageGenResult {
  dataUrl: string;
  prompt: string;
  negativePrompt: string;
  provider: string;
  model: string;
  seed?: number;
  timestamp: number;
}

export interface PromptOptimizationResult {
  prompt: string;
  negativePrompt: string;
  tags: string[];
}

export interface ImageGenOptions {
  /** 上下文消息列表（对话历史或章节内容） */
  contextMessages: { role: string; content: string }[];
  /** 上下文窗口大小：取最近 N 条（默认 5） */
  contextWindow?: number;
  /** 跳过 LLM 优化 */
  skipOptimization?: boolean;
  /** 手动提示词（跳过优化时生效） */
  manualPrompt?: string;
  /**
   * **整段最终提示词**：原样发给生图接口，不再拼画师串/质量标签，也不走 LLM。
   *
   * 用于"重新生图"——提示词是现成的成品，只换种子重出。
   * 和 manualPrompt 的区别：manualPrompt 还会被 provider 追加 artistTags/qualityTags，
   * 而这个是完全直发。
   */
  rawFullPrompt?: string;
  /** 手动负向提示词 */
  manualNegativePrompt?: string;
  /** 额外上下文说明（如"章节场景"、"当前行动"等） */
  contextLabel?: string;
  /**
   * 追加到提示词优化 system 里的标签参考块。
   * 由调用方按当前选中的分类组装，避免引擎层依赖 UI 设置。
   */
  tagReference?: string;
  /**
   * 角色 DNA 块：锚定角色固定外观。
   * 放在标签参考之前，因为角色一致性比标签写法优先级更高。
   */
  dnaBlock?: string;
  /** 手动提示词模式下直接拼进正向提示词的 DNA 标签 */
  dnaTags?: string;
  /**
   * 预设内容块（可选）。
   *
   * 把当前预设里启用的提示词交给大模型参考，用于让生图更贴合
   * 你惯用的写作/描写基调。默认不带 —— 见 AutoImageSettings.usePreset 的说明。
   */
  presetBlock?: string;
}

/**
 * 把正文里**不属于画面**的内容剥掉，只留下可以用来想象画面的那部分。
 *
 * 为什么需要（用户报的"点历史消息的配图就会乱配"）：
 *   真实会话里那条消息有 4288 字，开头是
 *     <Interleaving> <thinking> 回到 Dramatron，继续。我应该无视所有…
 *   —— 模型把**思维链**也写进了正文。这类内容对"画什么"毫无用处，
 *   却占掉了上下文的一大半，还会把生图模型的注意力带偏
 *   （它会把推理过程里的词当成画面元素），出来的图自然对不上剧情。
 *
 *   最新那条往往刚生成、或者用户凭印象觉得"它就该是这段"，
 *   所以问题不明显；翻回去点历史消息时，长消息+思维链就见真章了。
 *
 * 剥掉的东西：
 *   · <thinking>…</thinking>、<Interleaving> 这类标签及其内容
 *   · markdown 代码块、行内代码（里面常是 JSON/日志）
 *   · [[IMG: …]] 图片标记（本来就不该给模型看）
 *   · "好的/让我想想/根据要求"这类元话语行
 * 保留：所有叙述与对话正文。
 */
export function cleanSceneText(text: string): string {
  if (!text) return '';
  let out = text;

  // 1. 成对的思维链/系统标签（含内容，跨行）
  out = out
    .replace(/<thinking>[\s\S]*?<\/thinking>/gi, ' ')
    .replace(/<reasoning>[\s\S]*?<\/reasoning>/gi, ' ')
    .replace(/<analysis>[\s\S]*?<\/analysis>/gi, ' ')
    .replace(/<reflection>[\s\S]*?<\/reflection>/gi, ' ')
    .replace(/<scratchpad>[\s\S]*?<\/scratchpad>/gi, ' ');

  // 2. 落单的标签名（没有闭合的残缺输出）
  out = out.replace(/<\/?(thinking|reasoning|analysis|reflection|scratchpad|Interleaving|interleaving)\b[^>]*>/gi, ' ');

  // 3. 代码块与行内代码
  out = out.replace(/```[\s\S]*?```/g, ' ').replace(/`[^`\n]{0,80}`/g, ' ');

  // 4. 图片标记
  out = out.replace(/\[\[\s*IMG\s*[:：][^\]]*\]\]/gi, ' ');

  /*
   * 5. 元话语：模型自述/思考过程。
   *
   * ⚠ 只切掉**开头那句元话语**，不能整行删掉 ——
   *   实测：模型常写成 "好的，让我来写这一段。艾琳低声说：…"，
   *   一句话里前半是元话语、后半是正文。整行删会把这句台词也删掉，
   *   导致"画面主体在、结尾的情绪与台词没了"。
   *   所以这里只在行首匹配那一段、就地切除，保留后面的正文。
   */
  const META_HEAD =
    /^\s*(好的|明白|收到|让我|我来|我们先|首先|接下来|根据(要求|你的|上述)|总结一下|思考[:：]|分析[:：])[^\n。！？!?]{0,30}[。！？!?，,]\s*/;
  out = out
    .split('\n')
    .map((line) => {
      let t = line;
      // 最多剥三层（"好的，让我来看看。"这种套娃）
      for (let k = 0; k < 3; k++) {
        const next = t.replace(META_HEAD, '');
        if (next === t) break;
        t = next;
      }
      return t;
    })
    .join('\n');

  return out.replace(/[ \t]{2,}/g, ' ').replace(/\n{3,}/g, '\n\n').trim();
}

/**
 * 提取上下文文本：从消息列表中取最近 N 条拼接
 */
export function extractContext(
  messages: { role: string; content: string }[],
  windowSize = 5,
): string {
  if (messages.length === 0) return '';
  const recent = messages.slice(-Math.min(windowSize, messages.length));
  return recent
    /*
     * 每条先净化再拼。
     *
     * 净化放在这里（而不是让每个调用方自己处理）：生图上下文只有这一个入口，
     * 集中处理才能保证"手动生图 / 一键配图 / 历史消息配图 / 自动配图"
     * 全都不会把思维链喂给生图模型。
     */
    .map((m, i) => `[${i + 1}] ${m.role}: ${cleanSceneText(m.content)}`)
    .filter((line) => {
      // 净化后只剩 "[1] assistant: " 这种空壳的，直接丢掉
      const body = line.replace(/^\[\d+\]\s*\w+\s*[:：]\s*/, '').trim();
      return body.length > 0;
    })
    .join('\n\n');
}

/**
 * 把 LLM 返回的标签里"不该由它负责"的部分剔掉。
 *
 * 为什么要做这一步（用户的要求，也是解耦的关键）：
 *   画师串、质量词、负向提示词这类东西**格式固定、由配置决定**，
 *   每次让大模型现编一遍既浪费 token 又不可控 ——
 *   模型偶尔会漏几个、写错写法、或者把画风带偏。
 *   正确分工是：**模型只负责"画什么"，程序负责"用谁的画风、什么质量档"**。
 *
 *   模板里已经写明不要输出这些，但模型不总是听话（尤其小模型），
 *   所以这里再兜一层：即使它写了也会被丢掉，最终提示词只由
 *   `composePrompt([artistTags, qualityTags, 模型标签])` 决定。
 *
 * 剔除对象：
 *   · artist:xxx / (artist:xxx:1.2)  —— 画师串
 *   · masterpiece / best quality / absurdres / very aesthetic 等质量词
 *   · negative prompt 相关的键（由调用方用自己的配置）
 */
export function stripStyleTags(tags: string): string {
  if (!tags) return '';
  /** 质量/画风类词根 —— 这些一律交给配置，不让模型决定 */
  const STYLE_WORDS = [
    'masterpiece', 'best quality', 'high quality', 'top quality', 'amazing quality',
    'very aesthetic', 'aesthetic', 'absurdres', 'highres', 'ultra detailed',
    'extremely detailed', 'detailed', 'no text', 'official art', '4k', '8k',
    'best anatomy', 'perfect anatomy',
  ];

  const kept = tags
    .split(',')
    .map((t) => t.trim())
    // 去掉括号/权重后判断，别让 (artist:x:1.2) 逃过检测
    .filter((raw) => {
      if (!raw) return false;
      const bare = raw
        .replace(/^\(+|\)+$/g, '')
        .replace(/:\s*[\d.]+$/, '')
        .trim()
        .toLowerCase();
      if (!bare) return false;
      // 画师标签（NovelAI 的 artist:名字）
      if (/^artist\s*:/.test(bare)) return false;
      // 质量/画风词
      if (STYLE_WORDS.some((w) => bare === w || bare.startsWith(w + ' '))) return false;
      return true;
    });

  return kept.join(', ');
}

/** 把模板里的占位符替换为实际内容 */
export function renderPromptTemplate(
  template: string,
  vars: { contextText: string; contextLabel: string; artistTags?: string },
): { system: string; user: string } {
  const system = (template || DEFAULT_PROMPT_TEMPLATE)
    .replace(/\{\{\s*contextLabel\s*\}\}/g, vars.contextLabel)
    .replace(/\{\{\s*contextText\s*\}\}/g, vars.contextText)
    .replace(/\{\{\s*artistTags\s*\}\}/g, vars.artistTags || '');

  const user = `以下是${vars.contextLabel}内容：

${vars.contextText}

请提炼视觉要素并输出提示词（严格 JSON）。`;

  return { system, user };
}

/**
 * Step 1: 用 LLM 优化场景描述为生图提示词
 */
export async function optimizePrompt(
  contextText: string,
  llmConfig: ApiConfig,
  options: {
    contextLabel?: string;
    template?: string;
    artistTags?: string;
    manualPrompt?: string;
    /** 标签参考块，会追加到 system 末尾 */
    tagReference?: string;
    /** 角色 DNA 块，优先级高于标签参考 */
    dnaBlock?: string;
    /** 预设内容块（可选） */
    presetBlock?: string;
  } = {},
): Promise<PromptOptimizationResult> {
  const contextLabel = options.contextLabel || '场景';
  const { system, user } = renderPromptTemplate(options.template || DEFAULT_PROMPT_TEMPLATE, {
    contextText,
    contextLabel,
    artistTags: options.artistTags,
  });

  /*
   * 拼进 system 的顺序有讲究：
   *   1. 模板本体 —— 定义任务（把文本变成 Danbooru 标签）
   *   2. 预设内容 —— 你惯用的描写倾向，作为"风格参考"
   *   3. 标签参考 —— 可用标签表
   *   4. 角色 DNA —— 优先级最高，放最后（模型对末尾注意力更强，也最靠近输出）
   */
  const systemWithTags = [system, options.presetBlock, options.tagReference, options.dnaBlock]
    .filter(Boolean)
    .join('\n\n');

  const response = await chatCompletion(
    {
      ...llmConfig,
      temperature: llmConfig.temperature ?? 0.7,
      maxTokens: 900,
    },
    [
      { role: 'system', content: systemWithTags },
      { role: 'user', content: user },
    ],
  );

  // 解析 JSON 响应
  try {
    const match = response.match(/\{[\s\S]*\}/);
    if (match) {
      const parsed = JSON.parse(match[0]);
      return {
        prompt: parsed.prompt || '',
        negativePrompt: parsed.negativePrompt || '',
        tags: parsed.tags || [],
      };
    }
  } catch {
    // JSON 解析失败，回退到纯文本
  }

  return {
    prompt: response.replace(/```[a-z]*/g, '').trim(),
    negativePrompt: '',
    tags: [],
  };
}

/** 拼装最终正向提示词：固定画师串 → 质量标签 → 主体提示词（自动去重） */
export function composePrompt(
  parts: (string | undefined)[],
  dedupe = true,
): string {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const part of parts) {
    if (!part) continue;
    for (const raw of part.split(',')) {
      const tag = raw.trim();
      if (!tag) continue;
      const key = tag.toLowerCase();
      if (dedupe && seen.has(key)) continue;
      seen.add(key);
      out.push(tag);
    }
  }
  return out.join(', ');
}

/** 去掉字符串里的重复标签（保留原有顺序与大小写） */
export function dedupeTags(value: string): string {
  return composePrompt([value]);
}

/** 归一化 baseUrl，避免出现 /v1/v1 或重复端点。导出供对话接口复用。 */
export function trimEndpoint(url: string): string {
  return url
    .trim()
    .replace(/\/+$/, '')
    .replace(/\/(chat\/completions|images\/generations|models)$/i, '')
    .replace(/\/+$/, '');
}

// 端点探测结果缓存：把“哪条候选路径真的能用”记下来，避免每次都试错
const endpointCache = new Map<string, string>();

/** 已确认可用的端点（由设置页的「测试连接」写入） */
export function rememberEndpoint(key: string, url: string) {
  endpointCache.set(key, url);
}

export function forgetEndpoint(key: string) {
  endpointCache.delete(key);
}

function resolveNovelAIGenerateUrls(config: ImageGenConfig): string[] {
  if (config.endpoint && config.endpoint.trim()) {
    const exact = config.endpoint.trim();
    return [exact, ...candidateNovelAIEndpoints(config.baseUrl || '').filter((c) => c !== exact)];
  }
  const key = `nai:${config.baseUrl || 'official'}`;
  const cached = endpointCache.get(key);
  const cands = candidateNovelAIEndpoints(config.baseUrl || '');
  return cached ? [cached, ...cands.filter((c) => c !== cached)] : cands;
}

function resolveOpenAIImageUrls(config: ImageGenConfig): string[] {
  // 用户显式指定了端点：优先用它，其余候选作为兜底
  if (config.endpoint && config.endpoint.trim()) {
    const exact = config.endpoint.trim();
    return [exact, ...candidateImageEndpoints(config.baseUrl || '').filter((c) => c !== exact)];
  }
  const key = `openai-img:${config.baseUrl || ''}`;
  const cached = endpointCache.get(key);
  const cands = candidateImageEndpoints(config.baseUrl || '');
  return cached ? [cached, ...cands.filter((c) => c !== cached)] : cands;
}

/** 把一次成功探测到的端点记下来，供设置页回存配置 */
export function getResolvedEndpoint(baseUrl: string): string | undefined {
  return (
    endpointCache.get(`openai-img:${baseUrl || ''}`) ||
    endpointCache.get(`nai:${baseUrl || 'official'}`)
  );
}

/**
 * 只测一个地址是否可用（用于设置页的端点自测）。
 * 用一个极小的请求，避免浪费额度。
 */
export async function probeEndpoint(
  url: string,
  config: ImageGenConfig,
): Promise<{ ok: boolean; status?: number; message: string }> {
  try {
    const resp = await fetch(url, {
      method: 'POST',
      headers: authHeaders(config),
      body: JSON.stringify(
        config.provider === 'novelai-relay'
          ? buildNovelAIRequest({
              prompt: 'test',
              negativePrompt: 'lowres',
              model: config.novelaiModel || config.model || 'nai-diffusion-4-5-full',
              width: 832,
              height: 1216,
              steps: 1,
              scale: 6,
              sampler: config.sampler || 'k_euler_ancestral',
              seed: 1,
            })
          : {
              model: config.model || config.novelaiModel || 'nai-diffusion-4-5-full',
              prompt: 'test',
              negative_prompt: 'lowres',
              width: 832,
              height: 1216,
              steps: 1,
              scale: 6,
              sampler: config.sampler || 'k_euler_ancestral',
              seed: 1,
            },
      ),
    });

    if (!resp.ok) {
      const text = await resp.text().catch(() => '');
      return {
        ok: false,
        status: resp.status,
        message: `HTTP ${resp.status} ${text.slice(0, 140)}`,
      };
    }

    try {
      const dataUrl = await extractImageFromResponse(resp);
      // 顺手记下可用端点
      endpointCache.set(`openai-img:${config.baseUrl || ''}`, url);
      return { ok: true, status: resp.status, message: `可用（返回图片 ${dataUrl.length} 字符）` };
    } catch (e) {
      return {
        ok: false,
        status: resp.status,
        message: `HTTP 200 但不是图片接口：${e instanceof Error ? e.message : String(e)}`,
      };
    }
  } catch (e) {
    return { ok: false, message: `连不上：${e instanceof Error ? e.message : String(e)}` };
  }
}


function authHeaders(config: ImageGenConfig): Record<string, string> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'image/png,application/json,*/*',
  };
  const key = config.apiKey || config.novelaiToken;
  if (key) headers.Authorization = `Bearer ${key}`;
  return headers;
}

/**
 * 在候选端点中依次尝试，命中即返回。
 * 关键点：有些站点对未定义的路径会返回 HTTP 200 + {"error":"File not found"}，
 * 所以不能只看状态码——必须能真的解析出图片才算成功。
 */
async function postToCandidates(
  urls: string[],
  cacheKey: string,
  init: RequestInit,
  errorLabel: string,
): Promise<string> {
  const tried: string[] = [];
  let lastErr: Error | undefined;

  for (const url of urls) {
    let resp: Response;
    try {
      resp = await fetch(url, init);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      tried.push(`${url} → 连不上（${msg}）`);
      lastErr = new Error(`无法连接 ${url}：${msg}`);
      continue;
    }

    if (!resp.ok) {
      const text = await resp.text().catch(() => '');
      // 路径不存在 → 试下一条；其余（401/400/403…）说明路径对，直接报真实错误
      if (resp.status === 404 || resp.status === 405) {
        tried.push(`${url} → HTTP ${resp.status}`);
        lastErr = new Error(describeHttpError(errorLabel, resp.status, text));
        continue;
      }
      throw new Error(describeHttpError(`${errorLabel}（${url}）`, resp.status, text));
    }

    // HTTP 200：还要确认真的拿到了图片
    let dataUrl: string;
    try {
      dataUrl = await extractImageFromResponse(resp);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      // 命中“路径不存在”的伪成功响应 → 试下一条
      if (/File not found|未返回图片数据|无法解析/i.test(msg)) {
        tried.push(`${url} → HTTP 200 但不是图片接口（${msg.slice(0, 60)}）`);
        lastErr = new Error(msg);
        continue;
      }
      throw e;
    }

    endpointCache.set(cacheKey, url);
    return dataUrl;
  }

  throw new Error(
    `${errorLabel}：所有候选端点都失败了。\n已尝试：\n  ${tried.join('\n  ')}\n` +
      (lastErr ? `\n最后一个错误：${lastErr.message}` : '') +
      '\n\n💡 请核对「设置 → 生图配置」里的请求地址。' +
      '若站点使用非标准路径，请把完整端点地址（以 /images/generations 结尾）直接填入请求地址。',
  );
}

/**
 * Step 2: NovelAI 官方（nekoai-js）
 */
export async function generateWithNovelAI(
  prompt: string,
  negativePrompt: string,
  config: ImageGenConfig,
): Promise<ImageGenResult> {
  const model = config.novelaiModel || config.model || 'nai-diffusion-4-5-full';
  const client = new NovelAI({
    token: config.novelaiToken!,
    ...(config.host ? { host: config.host } : {}),
  });

  const fullPrompt = composePrompt([config.artistTags, config.qualityTags, prompt]);
  const seed = config.seed && config.seed > 0 ? config.seed : randomSeed();

  const images = await client.generateImage({
    prompt: fullPrompt,
    negative_prompt: negativePrompt || DEFAULT_NEGATIVE_PROMPT,
    model: model as any,
    steps: config.steps ?? 28,
    scale: config.cfgScale ?? 6.0,
    sampler: (config.sampler || defaultSamplerFor(model)) as any,
    width: config.width ?? 832,
    height: config.height ?? 1216,
    seed,
  });

  if (!images || images.length === 0) {
    throw new Error('NovelAI 未返回图片');
  }

  return {
    dataUrl: images[0].toDataURL(),
    prompt: fullPrompt,
    negativePrompt,
    provider: 'novelai',
    model,
    seed,
    timestamp: Date.now(),
  };
}

/**
 * Step 2: NovelAI 中转 —— 请求体与官方 100% 一致，只换地址。
 * 这是本项目的推荐路径：任何兼容 NovelAI 格式的中转都能直接用。
 */
export async function generateWithNovelAIRelay(
  prompt: string,
  negativePrompt: string,
  config: ImageGenConfig,
): Promise<ImageGenResult> {
  const model = config.novelaiModel || config.model || 'nai-diffusion-4-5-full';
  const urls = resolveNovelAIGenerateUrls(config);
  const cacheKey = `nai:${config.baseUrl || 'official'}`;
  const fullPrompt = composePrompt([config.artistTags, config.qualityTags, prompt]);
  const seed = config.seed && config.seed > 0 ? config.seed : randomSeed();
  const action: NovelAIAction = config.referenceImage ? 'img2img' : 'generate';

  const body = buildNovelAIRequest({
    prompt: fullPrompt,
    negativePrompt: negativePrompt || config.negativePrompt || DEFAULT_NEGATIVE_PROMPT,
    model,
    action,
    width: config.width ?? 832,
    height: config.height ?? 1216,
    steps: config.steps ?? 28,
    scale: config.cfgScale ?? 6.0,
    sampler: config.sampler || defaultSamplerFor(model),
    seed,
    ucPreset: config.ucPreset ?? 0,
    qualityToggle: config.qualityToggle ?? true,
    cfgRescale: config.cfgRescale ?? 0,
    noiseSchedule: config.noiseSchedule || 'karras',
    sm: config.sm,
    smDyn: config.smDyn,
    autoSmea: config.autoSmea,
    dynamicThresholding: config.dynamicThresholding,
    imageBase64: config.referenceImage,
    strength: config.denoisingStrength ?? 0.7,
  });

  const dataUrl = await postToCandidates(
    urls,
    cacheKey,
    {
      method: 'POST',
      headers: authHeaders(config),
      body: JSON.stringify(body),
    },
    'NovelAI 中转请求失败',
  );

  return {
    dataUrl,
    prompt: fullPrompt,
    negativePrompt: body.parameters.negative_prompt,
    provider: 'novelai-relay',
    model,
    seed: body.parameters.seed,
    timestamp: Date.now(),
  };
}

/**
 * Step 2: OpenAI 风格中转（POST {base}/images/generations）
 * 多数国内 NovelAI 中转站用这个形状，字段与官方同名。
 */
export async function generateWithNovelAICompatible(
  prompt: string,
  negativePrompt: string,
  config: ImageGenConfig,
): Promise<ImageGenResult> {
  const urls = resolveOpenAIImageUrls(config);
  const cacheKey = `openai-img:${config.baseUrl || ''}`;
  const model = config.model || config.novelaiModel || 'nai-diffusion-4-5-full';
  const fullPrompt = composePrompt([config.artistTags, config.qualityTags, prompt]);
  const seed = config.seed && config.seed > 0 ? config.seed : randomSeed();

  /*
   * ★ 两套请求体，从"最全"降级到"最保守"。
   *
   * 用户反馈「第三方接口最近总报 400，有时又正常」。查下来是这里的问题：
   * 这个 called "OpenAI 风格" 的请求体里**混了 NovelAI 的专有字段** ——
   *   ucPreset / qualityToggle / noise_schedule / sampler / scale / steps
   * 宽松的中转会忽略不认识的键，所以能出图；
   * 严格校验的中转（或它上游换了实现）会直接 400，
   * 表现就是"有时候行、有时候报 400"。
   *
   * 现在先发全字段（对真正的 NovelAI 中转最优），
   * 一旦 400 就自动用**纯 OpenAI 字段**再试一次。
   * 两种实现都能用，用户不用自己猜该填哪套。
   */
  const sizeStr = `${config.width ?? 832}x${config.height ?? 1216}`;

  const fullBody = {
    model,
    prompt: fullPrompt,
    negative_prompt: negativePrompt || config.negativePrompt || DEFAULT_NEGATIVE_PROMPT,
    n: 1,
    size: sizeStr,
    width: config.width ?? 832,
    height: config.height ?? 1216,
    steps: config.steps ?? 28,
    scale: config.cfgScale ?? 6.0,
    cfg_scale: config.cfgScale ?? 6.0,
    sampler: config.sampler || defaultSamplerFor(model),
    noise_schedule: config.noiseSchedule || 'karras',
    ucPreset: config.ucPreset ?? 0,
    qualityToggle: config.qualityToggle ?? true,
    seed,
  };

  /** 纯 OpenAI 形状：只留官方认得的键，严格网关也能过 */
  const slimBody = {
    model,
    prompt: fullPrompt,
    n: 1,
    size: sizeStr,
    response_format: 'b64_json',
  };

  const neg = negativePrompt || config.negativePrompt || DEFAULT_NEGATIVE_PROMPT;

  try {
    const dataUrl = await postToCandidates(urls, cacheKey, {
      method: 'POST',
      headers: authHeaders(config),
      body: JSON.stringify(fullBody),
    }, 'NovelAI 中转（OpenAI 风格）请求失败');
    return { dataUrl, prompt: fullPrompt, negativePrompt: neg, provider: 'novelai-openai', model, seed, timestamp: Date.now() };
  } catch (e) {
    const lastErr = e instanceof Error ? e : new Error(String(e));
    /*
     * 只有"字段不被接受"这类错误才降级重试。
     *
     * ★ 判断必须**只看 HTTP 状态码**，不要拿错误文本去猜。
     *   踩过：401 的响应体是 {"error":{"message":"invalid api key"}}，
     *   里面带 "invalid"，于是被 /invalid/ 命中、误判成"字段问题"，
     *   白白多发一次请求 —— 鉴权失败换什么字段都没用。
     *
     *   降级只对 400 / 422 有意义（语义就是"请求体不合法"）；
     *   401/403（鉴权）、429（限流）、5xx（服务端）、网络错误都不该重试。
     */
    const statusMatch = lastErr.message.match(/\bHTTP\s+(\d{3})\b/);
    const status = statusMatch ? Number(statusMatch[1]) : 0;
    const isSchemaReject = status === 400 || status === 422;
    if (!isSchemaReject) throw lastErr;
    console.warn('[生图] 中转拒绝了扩展字段，改用纯 OpenAI 格式重试：', lastErr.message.slice(0, 160));
  }

  const dataUrl = await postToCandidates(urls, cacheKey, {
    method: 'POST',
    headers: authHeaders(config),
    body: JSON.stringify(slimBody),
  }, 'NovelAI 中转（纯 OpenAI 格式）请求失败');

  return {
    dataUrl,
    prompt: fullPrompt,
    negativePrompt: neg,
    provider: 'novelai-openai',
    model,
    seed,
    timestamp: Date.now(),
  };
}

/**
 * Step 2: OpenAI DALL·E / gpt-image
 */
export async function generateWithDalle(
  prompt: string,
  config: ImageGenConfig,
): Promise<ImageGenResult> {
  const urls = resolveOpenAIImageUrls(config);
  const cacheKey = `openai-img:${config.baseUrl || ''}`;
  const model = config.model || 'dall-e-3';
  const fullPrompt = composePrompt([config.qualityTags, prompt], false);
  const aspect = (config.width ?? 1) / (config.height ?? 1);
  const finalSize =
    aspect > 1.2 ? '1792x1024' : aspect < 0.83 ? '1024x1792' : '1024x1024';

  const dataUrl = await postToCandidates(
    urls,
    cacheKey,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(config.apiKey ? { Authorization: `Bearer ${config.apiKey}` } : {}),
      },
      body: JSON.stringify({
        model,
        prompt: fullPrompt,
        n: 1,
        size: finalSize,
        quality: 'standard',
      }),
    },
    'DALL·E 请求失败',
  );

  return {
    dataUrl,
    prompt: fullPrompt,
    negativePrompt: config.negativePrompt || '',
    provider: 'dall-e',
    model,
    timestamp: Date.now(),
  };
}

/**
 * 本地 / 局域网自建出图服务（OpenAI 图像协议）。
 *
 * 实测过的接口（AI 出图工作台，ComfyUI 包装，端口 8899）：
 *   GET  /v1/models              → { data:[{ id, name, type, default_steps,
 *                                    default_cfg, default_sampler, default_size,
 *                                    negative_prompt, prompt_lang }] }
 *   POST /v1/images/generations  → { data:[{ b64_json, revised_prompt }] }
 *   GET  /v1/status              → 显存/可用尺寸/采样器清单
 *
 * 关键取舍：
 *   · 尺寸**必须**传 size（"512x768" 这种），而不是 width/height ——
 *     它只认 OpenAI 那套字段；
 *   · 采样器和步数按服务端 /v1/models 给的默认值走，用户没填就不硬塞，
 *     否则容易踩到"这个采样器不支持"的 4xx；
 *   · 负向提示词它也叫 negative_prompt（OpenAI 官方没有这个字段，
 *     但这个服务额外支持），一并带上。
 *
 * 和 NovelAI / 第三方中转完全独立的一条路，互不影响。
 */
export async function generateWithLocal(
  prompt: string,
  negativePrompt: string,
  config: ImageGenConfig,
): Promise<ImageGenResult> {
  const base = (config.baseUrl || '').trim().replace(/\/+$/, '');
  if (!base) throw new Error('本地部署需要填写地址，例如 http://192.168.137.1:8899');

  /*
   * 端点可以用 config.endpoint 覆盖（设置页能存下来）。
   * 默认按 OpenAI 惯例拼 /v1/images/generations；
   * 也把不带 /v1 的那种列进来，方便不同包装实现。
   */
  const urls = config.endpoint
    ? [`${base}${config.endpoint.startsWith('/') ? '' : '/'}${config.endpoint}`]
    : [`${base}/v1/images/generations`, `${base}/images/generations`];

  const fullPrompt = composePrompt([config.artistTags, config.qualityTags, prompt]);
  const seed = config.seed && config.seed > 0 ? config.seed : randomSeed();

  /*
   * 尺寸：本地服务只接受它自己支持的预设，超出会被拒或自动降级。
   * 用用户设的宽高拼成 size；没设就留空，让服务端用它自己的默认。
   */
  const w = config.width ?? 0;
  const h = config.height ?? 0;
  const size = w > 0 && h > 0 ? `${w}x${h}` : '';

  const body: Record<string, unknown> = {
    prompt: fullPrompt,
    n: 1,
    /*
     * 一定要 b64_json：本地服务不一定会把图存在自己能对外提供的路径上，
     * 要 url 的话经常直接返回空。
     */
    response_format: 'b64_json',
    seed,
  };
  if (config.model) body.model = config.model;
  if (size) body.size = size;
  if (config.steps && config.steps > 0) body.steps = config.steps;
  if (config.cfgScale && config.cfgScale > 0) body.cfg_scale = config.cfgScale;
  if (config.sampler) body.sampler = config.sampler;
  const neg = negativePrompt || config.negativePrompt || '';
  if (neg) body.negative_prompt = neg;

  const dataUrl = await postLocalCandidates(urls, {
    method: 'POST',
    headers: authHeaders(config),
    body: JSON.stringify(body),
  });

  return {
    dataUrl,
    prompt: fullPrompt,
    negativePrompt: neg,
    provider: 'local',
    model: config.model || 'local',
    seed,
    timestamp: Date.now(),
  };
}

/**
 * 本地部署专用：发请求 + 从 JSON 里取图。
 *
 * 为什么不复用 postToCandidates：
 *   那个函数是围绕 `Response` 对象写的（extractImageFromResponse 要吃
 *   Response，还要按 content-type 分 binary / json 两条路）。
 *   而走出原生桥时拿到的是 `{status, body}` 纯文本，没有 Response。
 *   与其为了兼容两种形状把公共函数改得面目全非，
 *   不如让本地这条路自己走一遍 —— 它只认一种响应：
 *   OpenAI 风格的 { data:[{ b64_json | url }] }，逻辑很短。
 *
 *   这样 NovelAI / 第三方中转那几条**完全不受影响**，
 *   符合"别动原先没问题的连接"的要求。
 */
async function postLocalCandidates(
  urls: string[],
  init: { method: string; headers: Record<string, string>; body: string },
): Promise<string> {
  const tried: string[] = [];
  let lastErr: Error | undefined;

  for (const url of urls) {
    let res;
    try {
      res = await httpRequest(url, { ...init, timeoutMs: 300000 });
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      tried.push(`${url} → 连不上（${msg.slice(0, 120)}）`);
      lastErr = new Error(msg);
      continue;
    }

    if (!res.ok) {
      /* 404/405 说明这个候选路径不对，换下一条试 */
      if (res.status === 404 || res.status === 405) {
        tried.push(`${url} → HTTP ${res.status}`);
        lastErr = new Error(describeHttpError('本地出图服务', res.status, res.body));
        continue;
      }
      throw new Error(describeHttpError(`本地出图服务（${url}）`, res.status, res.body));
    }

    /* 能解析出图才算成功 —— 有些服务对未知路径也回 200 + 一段 JSON */
    try {
      return extractImageFromJsonText(res.body);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      tried.push(`${url} → HTTP 200 但没图（${msg.slice(0, 60)}）`);
      lastErr = new Error(msg);
      continue;
    }
  }

  throw new Error(
    `本地出图服务：所有候选端点都失败了。\n已尝试：\n  ${tried.join('\n  ')}\n` +
      (lastErr ? `\n最后一个错误：${lastErr.message}` : '') +
      '\n\n💡 请确认：地址填到端口（别带 /v1）、手机和那台电脑在同一网络、' +
      '服务已启动。',
  );
}

/**
 * 从响应文本里抠出图片，统一成 dataURL。
 *
 * 支持这几种常见形状：
 *   { data: [ { b64_json } ] }      OpenAI 图像协议（本机实测就是这种）
 *   { data: [ { url } ] }           有的实现返回外链
 *   { images: ["<base64>", ...] }   SD WebUI 风格
 *   { image: "<base64>" }           单图简写
 *   纯 base64 / dataURL 文本         最朴素的实现
 */
function extractImageFromJsonText(text: string): string {
  const raw = (text || '').trim();
  if (!raw) throw new Error('响应是空的');

  // 直接就是 dataURL 或裸 base64
  if (raw.startsWith('data:image')) return raw;
  if (/^[A-Za-z0-9+/=\s]+$/.test(raw) && raw.length > 200) {
    return `data:image/png;base64,${raw.replace(/\s+/g, '')}`;
  }

  let j: any;
  try {
    j = JSON.parse(raw);
  } catch {
    throw new Error('响应不是 JSON');
  }

  const toDataUrl = (v: unknown): string | null => {
    if (typeof v !== 'string' || !v) return null;
    if (v.startsWith('data:image')) return v;
    if (/^https?:\/\//i.test(v)) return v;   // 外链，交给调用方加载
    if (v.length < 200) return null;         // 太短不可能是图
    return `data:image/png;base64,${v.replace(/\s+/g, '')}`;
  };

  const cand =
    toDataUrl(j?.data?.[0]?.b64_json) ||
    toDataUrl(j?.data?.[0]?.url) ||
    toDataUrl(j?.images?.[0]) ||
    toDataUrl(j?.image) ||
    toDataUrl(j?.artifacts?.[0]?.base64) ||
    toDataUrl(j?.result?.image);
  if (cand) return cand;

  /* 服务自己报的错，原样带出来，比"没拿到图片"有用得多 */
  const msg = j?.error?.message || j?.error || j?.detail || j?.message;
  throw new Error(typeof msg === 'string' ? msg : '响应里没有图片字段');
}

/**
 * Step 2: 自定义端点（用户完全掌控 body 形状由我们自己定，这里用通用字段）
 */
export async function generateWithCustom(
  prompt: string,
  negativePrompt: string,
  config: ImageGenConfig,
): Promise<ImageGenResult> {
  // 自定义接口：用户填写的地址就是最终地址，不做任何补全
  const url = (config.baseUrl || '').trim();
  if (!url) throw new Error('自定义接口需要填写完整请求地址');
  const fullPrompt = composePrompt([config.artistTags, config.qualityTags, prompt]);
  const seed = config.seed && config.seed > 0 ? config.seed : randomSeed();

  const resp = await fetch(url, {
    method: 'POST',
    headers: authHeaders(config),
    body: JSON.stringify({
      prompt: fullPrompt,
      negative_prompt: negativePrompt || config.negativePrompt || '',
      model: config.model || '',
      width: config.width ?? 832,
      height: config.height ?? 1216,
      steps: config.steps ?? 28,
      scale: config.cfgScale ?? 6,
      cfg_scale: config.cfgScale ?? 6,
      sampler: config.sampler || defaultSamplerFor(config.model || ''),
      seed,
    }),
  });

  if (!resp.ok) {
    const text = await resp.text().catch(() => '');
    throw new Error(describeHttpError(`自定义生图 API 请求失败（${url}）`, resp.status, text));
  }

  const dataUrl = await extractImageFromResponse(resp);

  return {
    dataUrl,
    prompt: fullPrompt,
    negativePrompt,
    provider: 'custom',
    model: config.model || 'custom',
    seed,
    timestamp: Date.now(),
  };
}

/**
 * 依次请求候选地址，返回第一个成功解析出模型列表的结果。
 * 与生图端点一样，兼容“/v1/models”“/models”以及各种前缀。
 */
export async function fetchModels(config: {
  baseUrl: string;
  apiKey?: string;
}): Promise<string[]> {
  if (!config.baseUrl || !config.baseUrl.trim()) {
    throw new Error('请先填写请求地址（Base URL）');
  }

  const cands = candidateModelsEndpoints(config.baseUrl);
  if (cands.length === 0) {
    throw new Error(`无法解析请求地址：${config.baseUrl}`);
  }

  const headers: Record<string, string> = { Accept: 'application/json' };
  if (config.apiKey) headers.Authorization = `Bearer ${config.apiKey}`;

  const problems: string[] = [];

  for (const url of cands) {
    /*
     * 走 httpRequest 而不是裸 fetch：
     * 在 APK 里本地服务没有 CORS 头，裸 fetch 一定失败，
     * 表现是"拉不到模型列表"（但直接出图又能成功，很迷惑）。
     * httpRequest 在端上会自动改走原生请求。
     */
    let res;
    try {
      res = await httpRequest(url, { method: 'GET', headers, timeoutMs: 20000 });
    } catch (e) {
      problems.push(`${url} → 连不上（${e instanceof Error ? e.message : String(e)}）`);
      continue;
    }

    if (!res.ok) {
      problems.push(`${url} → HTTP ${res.status} ${res.body.slice(0, 120)}`);
      continue;
    }

    let json: any = null;
    try {
      json = JSON.parse(res.body);
    } catch {
      json = null;
    }

    // 有些站点对未定义路径返回 HTTP 200 + {"error":"File not found"}，不能当成成功
    if (json && typeof json === 'object' && json.error && !json.data && !json.models) {
      problems.push(`${url} → HTTP 200 但不是模型接口：${JSON.stringify(json).slice(0, 100)}`);
      continue;
    }

    const raw =
      (Array.isArray(json) && json) ||
      json?.data ||
      json?.models ||
      json?.result ||
      json?.list ||
      [];

    const ids = (Array.isArray(raw) ? raw : [])
      .map((m: any) => (typeof m === 'string' ? m : m?.id || m?.name || m?.model))
      .filter((v: any): v is string => typeof v === 'string' && v.length > 0)
      .sort((a: string, b: string) => a.localeCompare(b));

    if (ids.length > 0) return ids;
    problems.push(`${url} → HTTP 200 但不是模型接口：${JSON.stringify(json).slice(0, 100)}`);
  }

  throw new Error(
    `拉取模型列表失败，已尝试 ${cands.length} 个地址：\n  ${problems.join('\n  ')}\n\n` +
      '💡 部分服务不提供 /models 端点，可以手动填写模型名。\n' +
      '   如果提示“连不上”，通常是地址不对或该站点不允许浏览器跨域（CORS）访问。',
  );
}

/**
 * 连通性测试：真的发一次极小的生成请求，确认地址 / 密钥 / 模型三者都能用。
 * 供设置页「测试连接」按钮调用。
 */
export async function testImageConnection(config: ImageGenConfig): Promise<{
  ok: boolean;
  endpoint?: string;
  message: string;
}> {
  const tiny = {
    ...config,
    width: 832,
    height: 1216,
    steps: 1,
    seed: 1,
    qualityTags: '',
    artistTags: '',
    referenceImage: undefined,
    promptTemplate: undefined,
  };

  try {
    const result = await generateImage(
      null,
      tiny,
      { contextMessages: [], skipOptimization: true, manualPrompt: 'test, 1girl' },
    );
    return {
      ok: true,
      endpoint: endpointCache.get(`openai-img:${config.baseUrl || ''}`) ||
        endpointCache.get(`nai:${config.baseUrl || 'official'}`),
      message: `连接成功，已生成 1 张测试图（${result.provider} / ${result.model}）`,
    };
  } catch (e) {
    return { ok: false, message: e instanceof Error ? e.message : String(e) };
  }
}


/**
 * 主入口：完整生图流程（提取上下文 → LLM 优化 → 生成 → 返回）
 */
export async function generateImage(
  llmConfig: ApiConfig | null,
  imageConfig: ImageGenConfig,
  options: ImageGenOptions,
): Promise<ImageGenResult> {
  const contextWindow = options.contextWindow ?? 5;
  const contextText = extractContext(options.contextMessages, contextWindow);

  const manual = options.manualPrompt?.trim();
  const useOptimization =
    !options.skipOptimization && !manual && !!llmConfig?.apiKey && !!llmConfig?.baseUrl;

  /*
   * 「整段直发」模式：提示词已经是**最终成品**（含画师串、质量标签），
   * 只换一个种子重新出图。
   *
   * 为什么单独走一条路：provider 层会做 `composePrompt([artistTags, qualityTags, prompt])`。
   * 如果把一条**已经拼好**的完整提示词当 manualPrompt 传进去，画师串会被再拼一遍，
   * 画风就变了 —— 这正是"重新生图出来的图不对"的原因。
   * 所以这里直接把整段当 prompt，并清掉 artistTags/qualityTags，让 provider 不再追加。
   */
  if (options.rawFullPrompt) {
    const cfg: ImageGenConfig = { ...imageConfig, artistTags: '', qualityTags: '' };
    const negative =
      options.manualNegativePrompt?.trim() ||
      imageConfig.negativePrompt ||
      DEFAULT_NEGATIVE_PROMPT;
    switch (cfg.provider) {
      case 'novelai':
        return generateWithNovelAI(options.rawFullPrompt, negative, cfg);
      case 'novelai-relay':
        return generateWithNovelAIRelay(options.rawFullPrompt, negative, cfg);
      case 'novelai-openai':
        return generateWithNovelAICompatible(options.rawFullPrompt, negative, cfg);
      case 'dall-e':
        return generateWithDalle(options.rawFullPrompt, cfg);
      case 'custom':
        return generateWithCustom(options.rawFullPrompt, negative, cfg);
      default:
        throw new Error(`未知生图提供方: ${cfg.provider}`);
    }
  }

  if (!contextText && !manual && !useOptimization) {
    throw new Error('没有可用的上下文内容，请填写手动提示词或配置 LLM');
  }

  let prompt: string;
  let negativePrompt: string;

  if (useOptimization) {
    const opt = await optimizePrompt(contextText, llmConfig!, {
      contextLabel: options.contextLabel,
      template: imageConfig.promptTemplate,
      artistTags: imageConfig.artistTags,
      manualPrompt: manual,
      tagReference: options.tagReference,
      dnaBlock: options.dnaBlock,
      presetBlock: options.presetBlock,
    });
    /*
     * ★ 职责分离（用户明确要求）：
     *
     *   模型只产出**画面内容标签**；画师串和质量词由配置拼、负向提示词用配置的。
     *
     *   为什么不信模型给的：画师串/质量档是"固定格式 + 用户偏好"，
     *   每次让模型现编会出现漏写、写法不一、偶尔把画风带偏；
     *   而且换一张图换一套画师串，同一段剧情出的图风格都不统一。
     *
     *   所以这里做两件事：
     *     1. 把模型返回里混进来的画师/质量词剔掉（stripStyleTags，兜住不听话的模型）
     *     2. 用 composePrompt 按 **画师串 → 质量标签 → 模型标签** 的顺序重新拼
     *
     *   negativePrompt **一律用配置**，不采用模型瞎编的那份 ——
     *   反向提示词是针对"这个模型/这个采样器"调好的，属于配置，不该逐图漂移。
     */
    const tagsOnly = stripStyleTags(opt.prompt || '');
    prompt = composePrompt([
      imageConfig.artistTags,
      imageConfig.qualityTags,
      tagsOnly,
    ]);
    /* 模型什么都没吐出来（被剔空了）→ 退回原文，至少不让提示词变空 */
    if (!tagsOnly.trim()) prompt = composePrompt([imageConfig.artistTags, imageConfig.qualityTags, contextText]);

    negativePrompt =
      options.manualNegativePrompt?.trim() ||
      imageConfig.negativePrompt ||
      DEFAULT_NEGATIVE_PROMPT;
  } else {
    // 手动模式：DNA 直接拼进正向提示词，保证角色特征不丢
    const dnaTags = options.dnaTags?.trim();
    const base = manual || contextText;
    prompt = dnaTags ? `${dnaTags}, ${base}` : base;
    negativePrompt =
      options.manualNegativePrompt?.trim() ||
      imageConfig.negativePrompt ||
      DEFAULT_NEGATIVE_PROMPT;
  }

  switch (imageConfig.provider) {
    case 'novelai':
      return generateWithNovelAI(prompt, negativePrompt, imageConfig);
    case 'novelai-relay':
      return generateWithNovelAIRelay(prompt, negativePrompt, imageConfig);
    case 'novelai-openai':
      return generateWithNovelAICompatible(prompt, negativePrompt, imageConfig);
    case 'dall-e':
      return generateWithDalle(prompt, imageConfig);
    case 'custom':
      return generateWithCustom(prompt, negativePrompt, imageConfig);
    case 'local':
      return generateWithLocal(prompt, negativePrompt, imageConfig);
    default:
      throw new Error(`未知生图提供方: ${imageConfig.provider}`);
  }
}

export { NOVELAI_HEAVY_UC };
