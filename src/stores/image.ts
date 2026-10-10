import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { store, genId } from '../lib/storage';
import type { ImageProvider } from '../engine/imageGen';
import { DEFAULT_PROMPT_TEMPLATE, DEFAULT_NEGATIVE_PROMPT } from '../engine/imageGen';

// 生图配置 store
//
// ---------------------------------------------------------------------------
// 为什么拆成两份配置
//
// 原来一份 ImageConfigEntry 里既有"怎么连上去"（baseUrl / apiKey / model /
// token / 端点），又有"画成什么样"（画师串 / 反向提示词 / 采样器 / 尺寸…）。
// 结果是：换一个画风就得把 API Key 重填一遍，同一套 API 想存两种风格也没法共用。
//
// 现在拆成两份独立、可复用的配置：
//   ImageApiEntry   —— 只管连接（换个中转/换个模型只改这里）
//   ImageStyleEntry —— 只管出图风格（画师串、反向提示词、采样器、参数…）
// 生成时把两者合并成引擎要的那一份（见 currentConfig），所以引擎不用改。
// ---------------------------------------------------------------------------

/** 生图 API：只管"怎么连上去"，不含任何出图风格 */
export interface ImageApiEntry {
  _uid: string;
  name: string;
  provider: ImageProvider;

  // ---- NovelAI 官方 ----
  novelaiToken?: string;
  /** 官方/中转 图像端点主机，中转一般不需要改 */
  host?: string;

  // ---- 中转 / OpenAI 兼容 / 自定义 ----
  baseUrl?: string;
  apiKey?: string;
  model?: string;
  novelaiModel?: string;
  /**
   * 显式指定的生图端点。留空则自动在候选后缀间探测，
   * 探测成功后可在设置页一键存到这里，之后不再试错。
   */
  endpoint?: string;

  /*
   * LLM 提示词优化放在"API"这一侧，因为它同样是"调用哪个服务"的问题
   * （用哪个对话模型把正文转成标签），跟画风无关 ——
   * 换画风不该把优化用的模型也一起换掉。
   */
  llmConfigId?: string;
  useLlmOptimization?: boolean;
}

/** 风格：只管"画成什么样"，可以跨不同 API 反复复用 */
export interface ImageStyleEntry {
  _uid: string;
  name: string;

  /**
   * 风格参考图（dataURL）—— **纯展示，绝不参与出图**。
   *
   * 用途只有一个：风格列表做成一排方块卡片，有张图就能直观看出一套风格
   * 画出来大概什么感觉（清新 / 暗黑 / 赛璐璐…），不用逐个点开试。
   *
   * ⚠ 别和"出图用的参考图"混在一起，那是**两件不同的活**：
   *   - 这里（风格参考图）：回答"这套风格长什么样"，是给人看的；
   *   - `quickReferenceImage`（对话界面那张）：回答"这一笔要照着谁画"，
   *     通常是角色形象 / 立绘，它会真的驱动 img2img（引擎拿它当底图）。
   * 所以这个字段**不会**被 `merged()` 带进引擎配置 ——
   * 给风格放张缩略图，不该让出图悄悄变成图生图。
   */
  thumb?: string;

  // ---- 提示词 ----
  /** 固定画师串（永远排在提示词最前面） */
  artistTags?: string;
  qualityTags?: string;
  negativePrompt?: string;
  /** 给 LLM 的提示词转换模板，支持 {{contextText}} / {{contextLabel}} */
  promptTemplate?: string;

  // ---- 出图参数 ----
  sampler?: string;
  steps?: number;
  width?: number;
  height?: number;
  cfgScale?: number;
  seed?: number;
  denoisingStrength?: number;

  // ---- NovelAI 高级参数（与官方请求体同名） ----
  ucPreset?: number;
  qualityToggle?: boolean;
  cfgRescale?: number;
  noiseSchedule?: string;
  sm?: boolean;
  smDyn?: boolean;
  autoSmea?: boolean;
  dynamicThresholding?: boolean;

  /** 是否出现在快捷条里 */
  isQuick?: boolean;
}

/**
 * 引擎实际使用的那一份（API + 风格合并后的结果）。
 *
 * 保留这个名字是为了**不改引擎和一大票调用方** ——
 * 它们都只读 `currentConfig`，拆分配置对它们完全透明。
 */
export interface ImageConfigEntry extends ImageApiEntry, ImageStyleEntry {
  /**
   * 出图用的参考图（图生图底图，base64）。
   *
   * **它不属于任何配置** —— 这是"这一次要照着谁画"的临时输入，
   * 由调用方在出图时传进来（界面上那张图存在
   * `quickReferenceImage` 里，通常是角色形象 / 立绘）。
   *
   * 为什么单独放在这一层而不是风格里：
   *   风格的图回答"这套画风长什么样"（给人看的）；
   *   这张图回答"这一笔画的是谁"（给引擎用的）。
   *   两件事，必须分开存 —— 早先把它们混在一个字段里，
   *   结果给风格放张缩略图就会让出图变成图生图。
   */
  referenceImage?: string;
}

// 生成的图片
export interface GeneratedImage {
  id: string;
  dataUrl: string;
  prompt: string;
  negativePrompt: string;
  provider: string;
  model: string;
  seed?: number;
  timestamp: number;
  context: string;
  scene: 'novel' | 'adventure';
  entityId: string;
}

/** 引擎用的合并默认值（API + 风格各取默认后合并） */
export const DEFAULT_CONFIG: ImageConfigEntry = {
  ...{
    _uid: '',
    name: '默认配置',
    provider: 'novelai-relay' as const,
    novelaiToken: '',
    host: '',
    baseUrl: '',
    apiKey: '',
    model: 'nai-diffusion-4-5-full',
    novelaiModel: 'nai-diffusion-4-5-full',
    endpoint: '',
    llmConfigId: undefined,
    useLlmOptimization: true,
  },
  ...{
    _uid: '',
    name: '默认配置',
    sampler: 'k_euler_ancestral',
    steps: 28,
    width: 832,
    height: 1216,
    cfgScale: 6,
    seed: 0,
    denoisingStrength: 0.7,
    ucPreset: 0,
    qualityToggle: true,
    cfgRescale: 0,
    noiseSchedule: 'karras',
    sm: false,
    smDyn: false,
    autoSmea: false,
    dynamicThresholding: false,
    artistTags: '',
    qualityTags: 'very aesthetic, masterpiece, no text, best quality, absurdres',
    negativePrompt: DEFAULT_NEGATIVE_PROMPT,
    promptTemplate: DEFAULT_PROMPT_TEMPLATE,
    thumb: undefined,
    isQuick: true,
  },
};

/** 生图 API 的默认连接值 */
export const DEFAULT_API: Omit<ImageApiEntry, '_uid' | 'name'> = {
  provider: 'novelai-relay',
  novelaiToken: '',
  host: '',
  baseUrl: '',
  apiKey: '',
  model: 'nai-diffusion-4-5-full',
  novelaiModel: 'nai-diffusion-4-5-full',
  endpoint: '',
  llmConfigId: undefined,
  useLlmOptimization: true,
};

/** 风格的默认值 */
export const DEFAULT_STYLE: Omit<ImageStyleEntry, '_uid' | 'name'> = {
  sampler: 'k_euler_ancestral',
  steps: 28,
  width: 832,
  height: 1216,
  cfgScale: 6,
  seed: 0,
  denoisingStrength: 0.7,
  ucPreset: 0,
  qualityToggle: true,
  cfgRescale: 0,
  noiseSchedule: 'karras',
  sm: false,
  smDyn: false,
  autoSmea: false,
  dynamicThresholding: false,
  artistTags: '',
  qualityTags: 'very aesthetic, masterpiece, no text, best quality, absurdres',
  negativePrompt: DEFAULT_NEGATIVE_PROMPT,
  promptTemplate: DEFAULT_PROMPT_TEMPLATE,
  thumb: undefined,
  isQuick: true,
};

/** 兼容旧版本已保存的配置：补齐新增字段、迁移 provider 命名 */
export function migrateConfig(raw: Partial<ImageConfigEntry>): ImageConfigEntry {
  const cfg: ImageConfigEntry = { ...DEFAULT_CONFIG, ...raw } as ImageConfigEntry;
  // 旧配置若用的是 novelai 但没填 token 而填了 baseUrl，说明其实是中转
  if (cfg.provider === 'novelai' && !cfg.novelaiToken && cfg.baseUrl) {
    cfg.provider = 'novelai-relay';
  }
  if (!cfg.promptTemplate) cfg.promptTemplate = DEFAULT_PROMPT_TEMPLATE;
  if (!cfg.negativePrompt) cfg.negativePrompt = DEFAULT_NEGATIVE_PROMPT;
  if (!cfg.novelaiModel && cfg.model) cfg.novelaiModel = cfg.model;
  if (!cfg.model && cfg.novelaiModel) cfg.model = cfg.novelaiModel;
  return cfg;
}

/** 补齐一份 API 配置的新增字段 */
export function migrateApi(raw: Partial<ImageApiEntry>): ImageApiEntry {
  const cfg: ImageApiEntry = { ...DEFAULT_API, ...raw } as ImageApiEntry;
  if (cfg.provider === 'novelai' && !cfg.novelaiToken && cfg.baseUrl) {
    cfg.provider = 'novelai-relay';
  }
  if (!cfg.novelaiModel && cfg.model) cfg.novelaiModel = cfg.model;
  if (!cfg.model && cfg.novelaiModel) cfg.model = cfg.novelaiModel;
  return cfg;
}

/** 补齐一份风格配置的新增字段 */
export function migrateStyle(raw: Partial<ImageStyleEntry>): ImageStyleEntry {
  const cfg: ImageStyleEntry = { ...DEFAULT_STYLE, ...raw } as ImageStyleEntry;
  if (!cfg.promptTemplate) cfg.promptTemplate = DEFAULT_PROMPT_TEMPLATE;
  if (!cfg.negativePrompt) cfg.negativePrompt = DEFAULT_NEGATIVE_PROMPT;
  /*
   * 老数据迁移：风格上曾经存过 referenceImage（那时被当成"这套配置的底图"）。
   * 现在的规则是"风格只存看效果的缩略图，出图底图属于单次生成"，
   * 所以把它一并搬到 thumb —— 图不丢，也不会再让出图变成图生图。
   *
   * 迁移后把原字段删掉，否则它会一直躺在 IndexedDB 里，
   * 将来有人写 `...(style)` 又会把老问题带回来。
   */
  const legacyRef = (raw as { referenceImage?: string }).referenceImage;
  if (legacyRef && !cfg.thumb) cfg.thumb = legacyRef;
  delete (cfg as { referenceImage?: string }).referenceImage;
  return cfg;
}

/**
 * 把一份旧格式（API + 风格混在一起）拆成两份。
 *
 * 迁移只做一次（靠 imageConfigSplitMigrated 标志），否则用户改了新配置之后
 * 再刷新，又会被旧数据重新覆盖一遍。
 */
export function splitLegacyConfig(legacy: Partial<ImageConfigEntry>): {
  api: ImageApiEntry;
  style: ImageStyleEntry;
} {
  const uid = String(legacy._uid ?? genId('img'));
  const name = String(legacy.name ?? '默认配置');
  // 用旧 uid 派生新 uid：这样"当前选中的是哪一份"能一一对应地迁移过去
  const api: ImageApiEntry = migrateApi({
    provider: legacy.provider,
    novelaiToken: legacy.novelaiToken,
    host: legacy.host,
    baseUrl: legacy.baseUrl,
    apiKey: legacy.apiKey,
    model: legacy.model,
    novelaiModel: legacy.novelaiModel,
    endpoint: legacy.endpoint,
    llmConfigId: legacy.llmConfigId,
    useLlmOptimization: legacy.useLlmOptimization,
  });
  api._uid = `api-${uid}`;
  api.name = name;

  const style: ImageStyleEntry = migrateStyle({
    sampler: legacy.sampler,
    steps: legacy.steps,
    width: legacy.width,
    height: legacy.height,
    cfgScale: legacy.cfgScale,
    seed: legacy.seed,
    denoisingStrength: legacy.denoisingStrength,
    ucPreset: legacy.ucPreset,
    qualityToggle: legacy.qualityToggle,
    cfgRescale: legacy.cfgRescale,
    noiseSchedule: legacy.noiseSchedule,
    sm: legacy.sm,
    smDyn: legacy.smDyn,
    autoSmea: legacy.autoSmea,
    dynamicThresholding: legacy.dynamicThresholding,
    artistTags: legacy.artistTags,
    qualityTags: legacy.qualityTags,
    negativePrompt: legacy.negativePrompt,
    promptTemplate: legacy.promptTemplate,
    /*
     * 旧数据里的参考图搬到 thumb（风格参考图，只做展示）。
     * 早先它被当成"这套配置的出图底图"，但按现在的规则，
     * 出图的底图属于**单次生成**、不归配置 —— 所以这里把它降级成缩略图，
     * 图不会丢，同时也不会再影响出图方式。
     */
    thumb: legacy.referenceImage,
    isQuick: legacy.isQuick,
  });
  style._uid = `sty-${uid}`;
  style.name = name;

  return { api, style };
}

/** 自动配图设置 */
export interface AutoImageSettings {
  /** 是否允许大模型用 [[IMG: ...]] 标记自主配图 */
  enabled: boolean;
  /** 一次回复最多生成几张，防止刷额度 */
  maxPerReply: number;
  /** 跳过 LLM 提示词优化，直接用标记里的描述（更省、更快） */
  skipOptimization: boolean;
  /**
   * 优化生图提示词时，是否把当前预设里启用的提示词也一起交给大模型参考。
   *
   * 为什么做成可选项：预设里装的多数是"怎么写小说"的指令
   * （文风、人称、防截断、思考格式…），对"画成什么样"帮助有限，
   * 反而占上下文、还可能把模型带偏。
   * 但预设里确实有少量跟画面相关的内容（性场景描写基调之类），
   * 所以留个开关让你自己试效果，**默认关闭**。
   */
  usePreset: boolean;
  /** 预设内容最多带多少字（预设动辄几万字，必须截断） */
  presetMaxChars: number;
}

const DEFAULT_AUTO_IMAGE: AutoImageSettings = {
  enabled: false,
  maxPerReply: 2,
  skipOptimization: true,
  usePreset: false,
  presetMaxChars: 4000,
};

export const useImageStore = defineStore('image', () => {
  /** 生图 API 配置（只管连接） */
  const apiConfigs = ref<ImageApiEntry[]>([]);
  /** 风格配置（只管出图风格） */
  const styleConfigs = ref<ImageStyleEntry[]>([]);
  const currentApiId = ref<string | undefined>(undefined);
  const currentStyleId = ref<string | undefined>(undefined);
  /** 旧格式是否已经拆分过（只拆一次，避免旧数据反复覆盖新配置） */
  const splitMigrated = ref(false);

  const images = ref<GeneratedImage[]>([]);
  const autoImage = ref<AutoImageSettings>({ ...DEFAULT_AUTO_IMAGE });

  /**
   * 页面上常驻的图生图参考图（dataURL）。
   * 空字符串 = 未启用图生图。写作时临时换素材用，不塞进单个配置里。
   */
  const quickReferenceImage = ref('');
  /** 图生图重绘强度 */
  const quickReferenceStrength = ref(0.6);

  const currentApi = computed(() =>
    apiConfigs.value.find((c) => c._uid === currentApiId.value),
  );
  const currentStyle = computed(() =>
    styleConfigs.value.find((c) => c._uid === currentStyleId.value),
  );

  /**
   * 引擎实际用的那一份：当前 API + 当前风格合并。
   *
   * 拆分配置对引擎和一大票调用方是完全透明的 —— 它们照旧只读这个。
   */
  const currentConfig = computed<ImageConfigEntry | undefined>(() =>
    merged(currentApiId.value, currentStyleId.value),
  );

  /**
   * 兼容旧代码的"配置列表"：按**风格**展开，每项带上当前 API 的连接信息。
   *
   * `_uid` 取风格的 uid（唯一），这样 `v-for :key` 不会撞、
   * `setCurrent(uid)` 也就是"换风格"，语义和界面上的下拉框一致。
   */
  const configs = computed<ImageConfigEntry[]>(() =>
    styleConfigs.value
      .map((s) => merged(currentApiId.value, s._uid))
      .filter(Boolean) as ImageConfigEntry[],
  );
  const currentConfigId = computed(() => currentStyleId.value);

  /** 把一份 API 和一份风格合成引擎要的形状 */
  function merged(apiId?: string, styleId?: string): ImageConfigEntry | undefined {
    const a = apiConfigs.value.find((c) => c._uid === apiId);
    const s = styleConfigs.value.find((c) => c._uid === styleId);
    if (!a && !s) return undefined;
    const out = {
      ...DEFAULT_CONFIG,
      ...(a ?? {}),
      ...(s ?? {}),
      // _uid/name 用风格的：它才是"这一套出图设置"的身份
      _uid: s?._uid ?? a?._uid ?? '',
      name: s?.name ?? a?.name ?? '未命名',
    } as ImageConfigEntry;
    /*
     * 显式清掉风格带来的参考图。
     *
     * 老数据里风格曾经存过 referenceImage（那时被当成"这套配置的参考图"），
     * 展开 `...(s ?? {})` 会把它带进来 —— 于是一张给风格当缩略图的图片，
     * 会让每一次出图都悄悄走成 img2img。这里统一清掉：
     * 出图的参考图**只能**由调用方按"这一次要照着谁画"显式传进来。
     */
    delete (out as { referenceImage?: string }).referenceImage;
    return out;
  }

  /** 按 (apiId, styleId) 取合并结果 —— 给"临时换个接口出图"这类场景用 */
  function currentConfigOf(apiId?: string, styleId?: string): ImageConfigEntry | undefined {
    return merged(apiId, styleId);
  }

  function imagesForEntity(entityId: string) {
    return images.value.filter((img: GeneratedImage) => img.entityId === entityId);
  }

  async function load() {
    const [
      storedApis,
      storedStyles,
      storedApiId,
      storedStyleId,
      storedMigrated,
      legacyConfigs,
      legacyCurrentId,
      storedImages,
      storedAuto,
      quickRef,
      quickStrength,
    ] = await Promise.all([
      store.getItem<Partial<ImageApiEntry>[]>('imageApiConfigs'),
      store.getItem<Partial<ImageStyleEntry>[]>('imageStyleConfigs'),
      store.getItem<string | undefined>('imageCurrentApiId'),
      store.getItem<string | undefined>('imageCurrentStyleId'),
      store.getItem<boolean>('imageConfigSplitMigrated'),
      // 旧键只在还没迁移时读
      store.getItem<Partial<ImageConfigEntry>[]>('imageConfigs'),
      store.getItem<string | undefined>('imageCurrentConfigId'),
      store.getItem<GeneratedImage[]>('generatedImages'),
      store.getItem<Partial<AutoImageSettings>>('autoImageSettings'),
      store.getItem<string>('quickReferenceImage'),
      store.getItem<number>('quickReferenceStrength'),
    ]);

    let apis = (storedApis ?? []).map(migrateApi);
    let styles = (storedStyles ?? []).map(migrateStyle);
    let apiId = storedApiId ?? undefined;
    let styleId = storedStyleId ?? undefined;
    let migrated = storedMigrated === true;

    /*
     * 一次性迁移：把旧格式（API + 风格混在一起）拆成两份。
     * 用 imageConfigSplitMigrated 挡住重复迁移 —— 否则用户改完新配置再刷新，
     * 又会被那份没动过的旧数据覆盖回去。
     */
    if (!migrated && (legacyConfigs?.length ?? 0) > 0) {
      apis = [];
      styles = [];
      for (const legacy of legacyConfigs ?? []) {
        const { api, style } = splitLegacyConfig(legacy);
        apis.push(api);
        styles.push(style);
        if (legacy._uid === legacyCurrentId) {
          apiId = api._uid;
          styleId = style._uid;
        }
      }
      migrated = true;
      console.log(`[生图] 已把 ${legacyConfigs!.length} 份旧配置拆成「API + 风格」两份`);
    }

    apiConfigs.value = apis;
    styleConfigs.value = styles;
    currentApiId.value = apiId;
    currentStyleId.value = styleId;
    splitMigrated.value = migrated;
    images.value = storedImages ?? [];
    autoImage.value = { ...DEFAULT_AUTO_IMAGE, ...(storedAuto ?? {}) };
    quickReferenceImage.value = quickRef ?? '';
    quickReferenceStrength.value = quickStrength ?? 0.6;

    // 一份都没有就建一套默认的，保证界面和生图都能用
    let needSave = false;
    if (apiConfigs.value.length === 0) {
      const api: ImageApiEntry = { ...DEFAULT_API, _uid: genId('api'), name: '默认接口' };
      apiConfigs.value.push(api);
      currentApiId.value = api._uid;
      needSave = true;
    }
    if (styleConfigs.value.length === 0) {
      const st: ImageStyleEntry = { ...DEFAULT_STYLE, _uid: genId('sty'), name: '默认风格' };
      styleConfigs.value.push(st);
      currentStyleId.value = st._uid;
      needSave = true;
    }
    if (!apiConfigs.value.some((c) => c._uid === currentApiId.value)) {
      currentApiId.value = apiConfigs.value[0]._uid;
      needSave = true;
    }
    if (!styleConfigs.value.some((c) => c._uid === currentStyleId.value)) {
      currentStyleId.value = styleConfigs.value[0]._uid;
      needSave = true;
    }
    if (needSave || (migrated && !storedMigrated)) await save();
  }

  async function save() {
    await Promise.all([
      store.setItem('imageApiConfigs', apiConfigs.value),
      store.setItem('imageStyleConfigs', styleConfigs.value),
      store.setItem('imageCurrentApiId', currentApiId.value),
      store.setItem('imageCurrentStyleId', currentStyleId.value),
      store.setItem('imageConfigSplitMigrated', true),
      store.setItem('generatedImages', images.value),
      store.setItem('autoImageSettings', autoImage.value),
      store.setItem('quickReferenceImage', quickReferenceImage.value),
      store.setItem('quickReferenceStrength', quickReferenceStrength.value),
    ]);
  }

  async function updateAutoImage(patch: Partial<AutoImageSettings>) {
    autoImage.value = { ...autoImage.value, ...patch };
    await save();
  }

  // ---------------- 生图 API 配置 ----------------

  async function addApi(name = '新接口') {
    const cfg: ImageApiEntry = { ...DEFAULT_API, _uid: genId('api'), name };
    apiConfigs.value.push(cfg);
    currentApiId.value = cfg._uid;
    await save();
    return cfg;
  }
  async function removeApi(uid: string) {
    apiConfigs.value = apiConfigs.value.filter((c) => c._uid !== uid);
    if (currentApiId.value === uid) currentApiId.value = apiConfigs.value[0]?._uid;
    await save();
  }
  async function updateApi(uid: string, patch: Partial<ImageApiEntry>) {
    const cfg = apiConfigs.value.find((c) => c._uid === uid);
    if (cfg) Object.assign(cfg, patch);
    await save();
  }
  async function setCurrentApi(uid: string) {
    currentApiId.value = uid;
    await save();
  }

  // ---------------- 风格配置 ----------------

  async function addStyle(name = '新风格') {
    const cfg: ImageStyleEntry = { ...DEFAULT_STYLE, _uid: genId('sty'), name };
    styleConfigs.value.push(cfg);
    currentStyleId.value = cfg._uid;
    await save();
    return cfg;
  }
  async function removeStyle(uid: string) {
    styleConfigs.value = styleConfigs.value.filter((c) => c._uid !== uid);
    if (currentStyleId.value === uid) currentStyleId.value = styleConfigs.value[0]?._uid;
    await save();
  }
  async function updateStyle(uid: string, patch: Partial<ImageStyleEntry>) {
    const cfg = styleConfigs.value.find((c) => c._uid === uid);
    if (cfg) Object.assign(cfg, patch);
    await save();
  }
  async function setCurrentStyle(uid: string) {
    currentStyleId.value = uid;
    await save();
  }
  async function toggleStyleQuick(uid: string) {
    const cfg = styleConfigs.value.find((c) => c._uid === uid);
    if (cfg) {
      cfg.isQuick = !cfg.isQuick;
      await save();
    }
  }

  // ---------------- 兼容层（旧调用点用） ----------------
  //
  // 旧代码把配置当成"一份"，而且改字段时不分是哪一类。这里按字段名转接：
  // 连接类落到 API 那份，其余落到风格那份。
  // 新写的代码请直接用 updateApi / updateStyle。

  const API_FIELDS = new Set([
    'provider',
    'novelaiToken',
    'host',
    'baseUrl',
    'apiKey',
    'model',
    'novelaiModel',
    'endpoint',
    'llmConfigId',
    'useLlmOptimization',
  ]);

  async function updateConfig(_uid: string, patch: Partial<ImageConfigEntry>) {
    const apiPatch: Record<string, unknown> = {};
    const stylePatch: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(patch)) {
      if (API_FIELDS.has(k)) apiPatch[k] = v;
      else stylePatch[k] = v;
    }
    /*
     * name **不再两边一起改**。
     *
     * 以前这里写 `if ('name' in patch) { apiPatch.name = ...; stylePatch.name = ... }`，
     * 结果用户给接口改个名，风格也跟着改名 —— 两份本该独立的配置被绑在一起
     * （用户反馈的"api 和图片风格共享了一个配置名字"就是它）。
     * 现在改名请用 updateApi / updateStyle 各改各的；
     * 兜底把 name 归到**风格**那份（风格列表是界面上主要的切换入口）。
     */
    if ('name' in patch) stylePatch.name = patch.name;

    const a = apiConfigs.value.find((c) => c._uid === currentApiId.value);
    const s = styleConfigs.value.find((c) => c._uid === currentStyleId.value);
    if (a) Object.assign(a, apiPatch);
    if (s) Object.assign(s, stylePatch);
    await save();
  }

  /** 兼容旧语义：新建一整套（一套接口 + 一套风格） */
  async function addConfig(name = '新配置') {
    const api = await addApi(name);
    const st = await addStyle(name);
    return { ...DEFAULT_CONFIG, ...api, ...st, _uid: st._uid, name } as ImageConfigEntry;
  }
  /** 兼容旧语义：删掉同名的一整套 */
  async function removeConfig(uid: string) {
    const style = styleConfigs.value.find((c) => c._uid === uid);
    const name = style?.name;
    await removeStyle(uid);
    const same = apiConfigs.value.find((c) => c.name === name);
    if (same) await removeApi(same._uid);
  }
  /** 兼容旧语义：按名字把接口和风格一起切过去 */
  async function setCurrent(uid: string) {
    const style = styleConfigs.value.find((c) => c._uid === uid);
    if (style) currentStyleId.value = style._uid;
    const api = apiConfigs.value.find((c) => c.name === style?.name);
    if (api) currentApiId.value = api._uid;
    await save();
  }
  async function toggleQuick(uid: string) {
    await toggleStyleQuick(uid);
  }

  async function addImage(image: GeneratedImage) {
    images.value.push(image);
    if (images.value.length > 50) {
      images.value = images.value.slice(-50);
    }
    await save();
  }

  async function removeImage(id: string) {
    images.value = images.value.filter((img: GeneratedImage) => img.id !== id);
    await save();
  }

  async function clearImages() {
    images.value = [];
    await save();
  }

  return {
    // 新的两份配置
    apiConfigs,
    styleConfigs,
    currentApiId,
    currentStyleId,
    currentApi,
    currentStyle,
    addApi,
    removeApi,
    updateApi,
    setCurrentApi,
    addStyle,
    removeStyle,
    updateStyle,
    setCurrentStyle,
    toggleStyleQuick,
    currentConfigOf,

    // 兼容层（旧名）
    configs,
    currentConfigId,
    images,
    autoImage,
    quickReferenceImage,
    quickReferenceStrength,
    currentConfig,
    imagesForEntity,
    load,
    save,
    addConfig,
    removeConfig,
    updateConfig,
    setCurrent,
    toggleQuick,
    updateAutoImage,
    addImage,
    removeImage,
    clearImages,
  };
});
