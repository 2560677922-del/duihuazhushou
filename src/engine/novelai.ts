// NovelAI 图像生成：官方请求格式定义 + 常量表
// ---------------------------------------------------------------------------
// 关键约束：本文件的请求体结构与 NovelAI 官方 API（POST /ai/generate-image）
// 完全一致，只有请求地址（baseUrl）可以替换为第三方中转。
// 也就是说：中转站不需要做任何格式转换，它只要原样转发 body 即可。
//
// 官方 body 形状：
// {
//   "input": "<正向提示词>",
//   "model": "nai-diffusion-4-5-full",
//   "action": "generate",              // generate | img2img | infill
//   "parameters": {
//     "params_version": 3,
//     "width": 832, "height": 1216,
//     "scale": 6, "sampler": "k_euler_ancestral", "steps": 28,
//     "seed": 1234567890, "n_samples": 1,
//     "ucPreset": 0, "qualityToggle": true,
//     "dynamic_thresholding": false, "controlnet_strength": 1,
//     "legacy": false, "add_original_image": true,
//     "cfg_rescale": 0, "noise_schedule": "karras",
//     "legacy_v3_extend": false,
//     "negative_prompt": "...",
//     "sm": false, "sm_dyn": false,
//     "skip_cfg_above_sigma": null,
//     "use_coords": false, "autoSmea": false,
//     "normalize_reference_strength_multiple": true,
//     "inpaintImg2ImgStrength": 0.7,
//     "image": "<base64>",             // 仅 img2img / infill
//     "strength": 0.7,                 // 仅 img2img
//     "extra_noise_seed": 0,           // 仅 img2img
//     "characterPrompts": [], "v4_prompt": {...}, "v4_negative_prompt": {...}
//   }
// }

export type NovelAIAction = 'generate' | 'img2img' | 'infill';

/** NovelAI 采样器（官方全部可用值，含 _gpu / _gpu_rtx 变体） */
export const NOVELAI_SAMPLERS = [
  'k_euler',
  'k_euler_ancestral',
  'k_dpmpp_2s_ancestral',
  'k_dpmpp_2m',
  'k_dpmpp_2m_sde',
  'k_dpmpp_sde',
  'k_dpmpp_2m_sde_gpu',
  'k_dpmpp_2s_ancestral_gpu',
  'k_dpmpp_sde_gpu',
  'ddim',
  'plms',
  'k_dpmpp_2m_gpu',
  'k_euler_gpu',
  'k_euler_ancestral_gpu',
] as const;

/** 采样器中文说明，供 UI 下拉显示 */
export const SAMPLER_HINTS: Record<string, string> = {
  k_euler: 'Euler · 稳定，速度快',
  k_euler_ancestral: 'Euler Ancestral · 更有变化，官方 V4/V4.5 推荐',
  k_dpmpp_2s_ancestral: 'DPM++ 2S Ancestral · 细节丰富',
  k_dpmpp_2m: 'DPM++ 2M · 平滑稳定',
  k_dpmpp_2m_sde: 'DPM++ 2M SDE · 细节与稳定平衡',
  k_dpmpp_sde: 'DPM++ SDE · 强细节',
  'k_dpmpp_2m_sde_gpu': 'DPM++ 2M SDE (GPU) · V3 常用',
  'k_dpmpp_2s_ancestral_gpu': 'DPM++ 2S Ancestral (GPU)',
  'k_dpmpp_sde_gpu': 'DPM++ SDE (GPU)',
  ddim: 'DDIM · 老式，兼容旧图',
  plms: 'PLMS · 最老式',
  'k_dpmpp_2m_gpu': 'DPM++ 2M (GPU)',
  'k_euler_gpu': 'Euler (GPU)',
  'k_euler_ancestral_gpu': 'Euler Ancestral (GPU)',
};

/** 噪声调度 */
export const NOVELAI_SCHEDULES = ['native', 'karras', 'exponential', 'polyexponential'] as const;

/** 采样器上下文：V4/V4.5 用 GPU 变体效果更好，这里给出模型推荐默认值 */
export function defaultSamplerFor(model: string): string {
  if (model.startsWith('nai-diffusion-4')) return 'k_euler_ancestral';
  if (model.startsWith('nai-diffusion-3')) return 'k_dpmpp_2m_sde';
  return 'k_dpmpp_2m_sde';
}

/** 官方模型列表 */
export const NOVELAI_MODELS = [
  { value: 'nai-diffusion-4-5-full', label: 'V4.5 Full' },
  { value: 'nai-diffusion-4-5-curated', label: 'V4.5 Curated（动漫向）' },
  { value: 'nai-diffusion-5-full', label: 'V5 Full（需订阅档位支持）' },
  { value: 'nai-diffusion-5-curated', label: 'V5 Curated（需订阅档位支持）' },
  { value: 'nai-diffusion-4-full', label: 'V4 Full' },
  { value: 'nai-diffusion-4-curated-preview', label: 'V4 Curated' },
  { value: 'nai-diffusion-3', label: 'V3（Anime V3）' },
  { value: 'nai-diffusion-furry-3', label: 'V3 Furry（兽人向）' },
] as const;

/**
 * 中转站的端点候选路径。
 * 同一家中转站可能挂在 /v1 下，也可能挂在别的前缀下，
 * 这里按“最可能成功”的顺序排列，供自动探测使用。
 */
export function candidateImageEndpoints(baseUrl: string): string[] {
  const raw = (baseUrl || '').trim();
  if (!raw) return [];

  // 用户直接写全了端点：只认它
  if (/\/images\/generations$/i.test(raw)) return [raw.replace(/\/+$/, '')];

  let origin: string;
  try {
    origin = new URL(raw).origin;
  } catch {
    return [];
  }

  const path = raw.replace(/\/+$/, '');
  const stripped = path.replace(/\/(v\d+|api)(\/.*)?$/i, '');
  const base = stripped || origin;

  const cands = [
    // 实测经验：多数 NovelAI 中转站挂在 /v1 下，优先试它
    `${origin}/v1/images/generations`,
    `${origin}/images/generations`,
    `${base}/v1/images/generations`,
    `${path}/v1/images/generations`,
    `${path}/images/generations`,
  ];

  // 去重并保持顺序
  return [...new Set(cands)];
}

/**
 * 可让用户自选/自测的端点后缀。
 * 中转站的路径五花八门，与其猜，不如把候选列出来让用户点。
 */
export const IMAGE_ENDPOINT_SUFFIXES = [
  { suffix: '/v1/images/generations', label: '/v1/images/generations', hint: 'OpenAI 风格，最常见' },
  { suffix: '/images/generations', label: '/images/generations', hint: '不带 v1 的 OpenAI 风格' },
  { suffix: '/v1/images/edits', label: '/v1/images/edits', hint: '少数站点的别名' },
  { suffix: '/api/generate', label: '/api/generate', hint: '自建网关常见' },
  { suffix: '/api/v1/images/generations', label: '/api/v1/images/generations', hint: '前缀带 api' },
  { suffix: '/v1/generate', label: '/v1/generate', hint: '简化别名' },
  { suffix: '/ai/generate-image', label: '/ai/generate-image', hint: 'NovelAI 官方路径（官方格式）' },
] as const;

/** 输入框里的地址 + 用户选的后缀 → 最终端点 */
export function buildEndpointFromSuffix(urlInput: string, suffix: string): string {
  let raw = (urlInput || '').trim().replace(/\/+$/, '');
  if (!raw) return '';
  // 如果地址里已经带了某个已知后缀，先剥掉再拼新的，避免 /v1/v1 这种重复
  for (const s of IMAGE_ENDPOINT_SUFFIXES) {
    if (raw.endsWith(s.suffix)) {
      raw = raw.slice(0, -s.suffix.length);
      break;
    }
  }
  raw = raw.replace(/\/+$/, '');
  const suf = suffix.startsWith('/') ? suffix : `/${suffix}`;
  return `${raw}${suf}`;
}

/** 从完整端点反推用户选中的后缀 */
export function suffixFromEndpoint(url: string): string {
  const u = (url || '').trim();
  for (const s of IMAGE_ENDPOINT_SUFFIXES) {
    if (u.endsWith(s.suffix)) return s.suffix;
  }
  return '';
}

/**
 * 拉取模型列表的候选路径
 */
export function candidateModelsEndpoints(baseUrl: string): string[] {
  const raw = (baseUrl || '').trim();
  if (!raw) return [];
  if (/\/models$/i.test(raw)) return [raw.replace(/\/+$/, '')];

  let origin: string;
  try {
    origin = new URL(raw).origin;
  } catch {
    return [];
  }
  const path = raw.replace(/\/+$/, '');
  const stripped = path.replace(/\/(v\d+|api)(\/.*)?$/i, '');
  const base = stripped || origin;

  return [
    ...new Set([
      `${origin}/v1/models`,
      `${origin}/models`,
      `${base}/v1/models`,
      `${path}/v1/models`,
      `${path}/models`,
    ]),
  ];
}

/**
 * NovelAI 官方生成端点候选。
 * 官方是 https://api.novelai.net/ai/generate-image。
 */
export function candidateNovelAIEndpoints(baseUrl: string): string[] {
  const raw = (baseUrl || '').trim();
  if (!raw) return ['https://api.novelai.net/ai/generate-image'];
  if (/\/ai\/generate-image$/i.test(raw)) return [raw];

  let origin: string;
  try {
    origin = new URL(raw).origin;
  } catch {
    return [`${raw.replace(/\/+$/, '')}/ai/generate-image`];
  }
  return [...new Set([`${origin}/ai/generate-image`, `${raw.replace(/\/+$/, '')}/ai/generate-image`])];
}


/** 分辨率预设（NovelAI 官方推荐档位） */
export const SIZE_PRESETS = [
  { label: '竖图 832×1216（人像）', width: 832, height: 1216 },
  { label: '竖图 1024×1536', width: 1024, height: 1536 },
  { label: '方图 1024×1024', width: 1024, height: 1024 },
  { label: '方图 832×832', width: 832, height: 832 },
  { label: '横图 1216×832（场景）', width: 1216, height: 832 },
  { label: '横图 1536×1024', width: 1536, height: 1024 },
  { label: '小图 512×768（省额度）', width: 512, height: 768 },
] as const;

/** 官方正向/负向质量标签预设（ucPreset 0 = Heavy） */
export const UC_PRESETS = [
  { value: 0, label: 'Heavy（强负面）' },
  { value: 1, label: 'Light' },
  { value: 2, label: 'Furry Focus' },
  { value: 3, label: 'Human Focus' },
  { value: 4, label: 'None（不用内置负面）' },
] as const;

/** NovelAI 官方 Heavy 负面提示词（关闭 ucPreset 时它会被加回来） */
export const NOVELAI_HEAVY_UC =
  'lowres, {bad}, error, fewer, extra, missing, worst quality, jpeg artifacts, bad quality, ' +
  'watermark, unfinished, chromatic aberration, signature, extra digits, artistic error, ' +
  'username, scan, [abstract], @, bad hands, bad anatomy, mutated hands, blurry, ' +
  'low quality, worst quality, bad proportions, extra limbs, extra fingers, ' +
  'missing fingers, fusebad, weird anatomy, ugly, deformed';

/** 常用质量标签 */
export const QUALITY_TAG_PRESETS = [
  {
    label: 'V4/V4.5 推荐',
    value: 'very aesthetic, masterpiece, no text, best quality, absurdres',
  },
  { label: '经典', value: 'masterpiece, best quality, highres' },
  { label: '高细节', value: 'masterpiece, best quality, amazing quality, very aesthetic, absurdres, incredibly detailed' },
];

/** 画师串预设（固定画师串用，可自由编辑） */
export const ARTIST_PRESET_EXAMPLES = [
  { label: '厚涂 / 概念图', value: 'artist:wlop, artist:greg rutkowski, artist:john singer sargent' },
  { label: '日式动画', value: 'artist:ciloranko, artist:sho_(sho_lwlw)' },
  { label: '赛璐璐', value: 'artist:modare, artist:as109' },
  { label: '水彩', value: 'artist:yoneyama mai, artist:fu_mi_kan' },
];

/** 生图参数（中转与官方共用同一份结构） */
export interface NovelAIParams {
  width: number;
  height: number;
  scale: number;
  sampler: string;
  steps: number;
  seed: number;
  n_samples?: number;
  ucPreset?: number;
  qualityToggle?: boolean;
  negative_prompt: string;
  cfg_rescale?: number;
  noise_schedule?: string;
  dynamic_thresholding?: boolean;
  dynamic_thresholding_percentile?: number;
  dynamic_thresholding_mimic_scale?: number;
  sm?: boolean;
  sm_dyn?: boolean;
  autoSmea?: boolean;
  skip_cfg_above_sigma?: number | null;
  legacy?: boolean;
  legacy_v3_extend?: boolean;
  controlnet_strength?: number;
  add_original_image?: boolean;
  use_coords?: boolean;
  normalize_reference_strength_multiple?: boolean;
  params_version?: number;
  // img2img
  image?: string;
  strength?: number;
  noise?: number;
  extra_noise_seed?: number;
  inpaintImg2ImgStrength?: number;
}

export interface NovelAIRequestBody {
  input: string;
  model: string;
  action: NovelAIAction;
  parameters: NovelAIParams;
}

export interface BuildRequestOptions {
  prompt: string;
  negativePrompt: string;
  model: string;
  action?: NovelAIAction;
  width: number;
  height: number;
  steps: number;
  scale: number;
  sampler: string;
  seed?: number;
  ucPreset?: number;
  qualityToggle?: boolean;
  cfgRescale?: number;
  noiseSchedule?: string;
  sm?: boolean;
  smDyn?: boolean;
  autoSmea?: boolean;
  dynamicThresholding?: boolean;
  /** img2img：base64（不含 data: 前缀） */
  imageBase64?: string;
  strength?: number;
  /** 额外参数覆盖，用于中转站扩展字段 */
  extraParams?: Record<string, unknown>;
}

/**
 * 按 NovelAI 官方格式构造请求体。
 * 会根据模型版本自动裁剪该版本不支持/多余的字段，避免中转端 422。
 */
export function buildNovelAIRequest(opts: BuildRequestOptions): NovelAIRequestBody {
  // V4 及更新的扩散模型（含 V5）共用同一套参数；V3 单独一套；更早的模型字段最少。
  const isV4 = /^nai-diffusion-[45]/.test(opts.model);
  const isV3 = /^nai-diffusion-(3|furry-3)/.test(opts.model);
  const isOld = !isV4 && !isV3;
  const action: NovelAIAction = opts.imageBase64 ? (opts.action ?? 'img2img') : 'generate';

  const parameters: NovelAIParams = {
    params_version: 3,
    width: Math.round(opts.width) || 832,
    height: Math.round(opts.height) || 1216,
    scale: opts.scale ?? 6,
    sampler: opts.sampler || defaultSamplerFor(opts.model),
    steps: Math.round(opts.steps) || 28,
    seed: opts.seed && opts.seed > 0 ? Math.floor(opts.seed) : randomSeed(),
    n_samples: 1,
    ucPreset: opts.ucPreset ?? 0,
    qualityToggle: opts.qualityToggle ?? true,
    negative_prompt: opts.negativePrompt || '',
    cfg_rescale: opts.cfgRescale ?? 0,
    noise_schedule: opts.noiseSchedule || (isV4 || isV3 ? 'karras' : 'native'),
    legacy: false,
    legacy_v3_extend: false,
    add_original_image: true,
    controlnet_strength: 1,
    normalize_reference_strength_multiple: true,
    skip_cfg_above_sigma: null,
    ...(opts.extraParams ?? {}),
  };

  // V4/V4.5 专属
  if (isV4) {
    parameters.use_coords = false;
    parameters.autoSmea = opts.autoSmea ?? false;
    parameters.dynamic_thresholding = opts.dynamicThresholding ?? false;
    // V4 的负面提示词同时走 v4_negative_prompt，中转端通常会自动补，
    // 这里只带上官方模板所需的占位，避免格式校验失败。
  }

  // V3 专属
  if (isV3) {
    parameters.sm = opts.sm ?? false;
    parameters.sm_dyn = opts.smDyn ?? false;
    parameters.dynamic_thresholding = opts.dynamicThresholding ?? false;
    parameters.skip_cfg_above_sigma = null;
  }

  // 旧模型（V2）不认这些
  if (isOld) {
    delete parameters.noise_schedule;
    delete parameters.skip_cfg_above_sigma;
  }

  // img2img
  if (opts.imageBase64) {
    parameters.image = opts.imageBase64.replace(/^data:image\/\w+;base64,/, '');
    parameters.strength = opts.strength ?? 0.7;
    parameters.noise = 0;
    parameters.extra_noise_seed = parameters.seed;
    parameters.inpaintImg2ImgStrength = opts.strength ?? 0.7;
  }

  return { input: opts.prompt, model: opts.model, action, parameters };
}

export function randomSeed(): number {
  return Math.floor(Math.random() * 4294967295);
}

/**
 * 解析生图响应。中转站返回格式五花八门，这里全部兜住：
 *  - NovelAI 官方 / 中转：二进制图片流（zip 或 png）
 *  - { image: "base64" } / { data: "base64" } / { images: [...] }
 *  - OpenAI 风格：{ data: [{ b64_json }] } / { data: [{ url }] }
 *  - 纯 base64 文本 / data URL 文本
 */
export async function extractImageFromResponse(resp: Response): Promise<string> {
  const contentType = (resp.headers.get('content-type') || '').toLowerCase();

  // 二进制流（官方与大多数中转的默认返回）
  if (
    contentType.includes('image/') ||
    contentType.includes('application/octet-stream') ||
    contentType.includes('application/zip') ||
    (!contentType.includes('json') && !contentType.includes('text'))
  ) {
    const blob = await resp.blob();
    if (blob.size === 0) throw new Error('生图 API 返回了空数据');
    // zip 包：NovelAI 官方在 n_samples>1 时返回 zip，前端不便解压，
    // 这里直接给出明确提示而不是静默失败。
    if (contentType.includes('zip') || (await isZip(blob))) {
      throw new Error('返回的是 zip 压缩包（多张图）。请把 n_samples 设为 1 后重试。');
    }
    return blobToDataUrl(blob);
  }

  const text = await resp.text();
  const trimmed = text.trim();

  // 纯 data URL / 纯 base64
  if (trimmed.startsWith('data:image/')) return trimmed;
  if (/^[A-Za-z0-9+/=\s]{256,}$/.test(trimmed)) {
    return `data:image/png;base64,${trimmed.replace(/\s+/g, '')}`;
  }

  let json: any;
  try {
    json = JSON.parse(trimmed);
  } catch {
    throw new Error(`生图 API 返回了无法解析的内容: ${trimmed.slice(0, 200)}`);
  }

  const candidate =
    json.data?.[0]?.b64_json ||
    json.data?.[0]?.url ||
    json.data?.[0]?.image ||
    (Array.isArray(json.data) && typeof json.data[0] === 'string' ? json.data[0] : undefined) ||
    (typeof json.data === 'string' ? json.data : undefined) ||
    json.images?.[0] ||
    json.image ||
    json.b64_json ||
    json.url ||
    json.output?.[0];

  if (!candidate) {
    throw new Error(
      `生图 API 未返回图片数据（响应字段：${Object.keys(json).join(', ') || '空'}）`,
    );
  }

  if (typeof candidate === 'string' && candidate.startsWith('http')) {
    const imgResp = await fetch(candidate);
    if (!imgResp.ok) throw new Error(`下载图片失败: HTTP ${imgResp.status}`);
    return blobToDataUrl(await imgResp.blob());
  }

  return typeof candidate === 'string' && candidate.startsWith('data:')
    ? candidate
    : `data:image/png;base64,${String(candidate).replace(/^data:image\/\w+;base64,/, '')}`;
}

/** 把错误响应体转成可读信息 */
export function describeHttpError(prefix: string, status: number, body: string): string {
  const detail = body.trim().slice(0, 300);
  const hints: Record<number, string> = {
    401: '认证失败：请检查 Token / API Key（NovelAI 官方用的是持久化 Token，不是邮箱密码）',
    402: '额度不足或订阅无效',
    403: '被拒绝：可能是 Anlas（额度）不足或内容被拦截',
    422: '参数不被接受：请确认模型与本页参数是否匹配该中转站',
    429: '触发限流，请稍后重试',
  };
  const hint = hints[status] ? `\n💡 ${hints[status]}` : '';
  return `${prefix}: HTTP ${status}${detail ? ` - ${detail}` : ''}${hint}`;
}

function blobToDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onloadend = () => resolve(reader.result as string);
    reader.onerror = () => reject(new Error('图片数据读取失败'));
    reader.readAsDataURL(blob);
  });
}

async function isZip(blob: Blob): Promise<boolean> {
  if (blob.size < 4) return false;
  const head = new Uint8Array(await blob.slice(0, 4).arrayBuffer());
  return head[0] === 0x50 && head[1] === 0x4b; // "PK"
}
