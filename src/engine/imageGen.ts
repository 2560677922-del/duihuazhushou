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

export type ImageProvider =
  | 'novelai'
  | 'novelai-relay'
  | 'novelai-openai'
  | 'dall-e'
  | 'custom';

/** 默认的 LLM → 生图提示词模板（用户可在 UI 中修改并恢复默认） */
export const DEFAULT_PROMPT_TEMPLATE = `你是一位顶尖的 AI 绘画提示词工程师，擅长把小说/对话场景转换成 NovelAI（Danbooru 标签体系）提示词。

任务：阅读下面的{{contextLabel}}，提炼画面视觉要素，输出英文提示词。

规则：
1. 正向提示词用英文 Danbooru 标签，逗号分隔，按重要性从高到低排列。
2. 优先描述：画面中的角色（人数、性别、发色发型、瞳色、服装、表情、姿势）、动作、互动、镜头视角、构图、环境与背景、时间与天气、光线氛围。
3. 只写画面中真实存在或能自然推断的元素，不要臆造无关角色或道具。
4. 使用画家/画风标签时按 NovelAI 的 \`artist:名字\` 写法。
5. 不要输出解释、序号、Markdown，只输出 JSON。
6. 负向提示词要针对本场景可能出现的问题（如多人时的肢体错误、不想要的风格）。

输出格式（严格 JSON，不要代码块）：
{
  "prompt": "正向提示词",
  "negativePrompt": "负向提示词",
  "tags": ["主要视觉标签1", "标签2"]
}`;

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
 * 提取上下文文本：从消息列表中取最近 N 条拼接
 */
export function extractContext(
  messages: { role: string; content: string }[],
  windowSize = 5,
): string {
  if (messages.length === 0) return '';
  const recent = messages.slice(-Math.min(windowSize, messages.length));
  return recent
    .map((m, i) => `[${i + 1}] ${m.role}: ${m.content}`)
    .join('\n\n');
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

  const dataUrl = await postToCandidates(
    urls,
    cacheKey,
    {
      method: 'POST',
      headers: authHeaders(config),
      body: JSON.stringify({
        model,
        prompt: fullPrompt,
        negative_prompt: negativePrompt || config.negativePrompt || DEFAULT_NEGATIVE_PROMPT,
        n: 1,
        size: `${config.width ?? 832}x${config.height ?? 1216}`,
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
      }),
    },
    'NovelAI 中转（OpenAI 风格）请求失败',
  );

  return {
    dataUrl,
    prompt: fullPrompt,
    negativePrompt,
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
    let resp: Response;
    try {
      resp = await fetch(url, { method: 'GET', headers });
    } catch (e) {
      problems.push(`${url} → 连不上（${e instanceof Error ? e.message : String(e)}）`);
      continue;
    }

    if (!resp.ok) {
      const text = await resp.text().catch(() => '');
      problems.push(`${url} → HTTP ${resp.status} ${text.slice(0, 120)}`);
      continue;
    }

    const json: any = await resp.json().catch(() => null);

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
    prompt = opt.prompt;
    negativePrompt =
      opt.negativePrompt ||
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
    default:
      throw new Error(`未知生图提供方: ${imageConfig.provider}`);
  }
}

export { NOVELAI_HEAVY_UC };
