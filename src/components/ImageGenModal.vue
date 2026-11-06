<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { useApiConfigStore } from '../stores/apiConfig';
import ModelPicker from './ModelPicker.vue';
import { useImageStore } from '../stores/image';
import { generateImage, extractContext, fetchModels } from '../engine/imageGen';
import type { ImageGenConfig, ImageGenOptions } from '../engine/imageGen';
import type { StoredApiConfig } from '../stores/apiConfig';
import { NOVELAI_MODELS } from '../engine/novelai';
import { buildTagReference, TAG_CATEGORIES } from '../engine/tagLibrary';
import { useStorageSettingsStore } from '../stores/storageSettings';
import { useDnaStore } from '../stores/characterDna';
import { uid } from '../lib/storage';
import ImageParamsPanel from './ImageParamsPanel.vue';

const api = useApiConfigStore();
const imageStore = useImageStore();
const storageSettings = useStorageSettingsStore();
const dnaStore = useDnaStore();

const props = defineProps<{
  contextMessages: { role: string; content: string }[];
  contextLabel?: string;
  scene: 'novel' | 'adventure';
  entityId: string;
  defaultWindow?: number;
  /** 预填的手动提示词（点已生成的图重新生图时用） */
  presetPrompt?: string;
  /** 页面常驻的图生图参考图 */
  referenceImage?: string;
  /** 图生图重绘强度 */
  referenceStrength?: number;
}>();

const emit = defineEmits<{
  generated: [dataUrl: string];
  close: [];
}>();

// ---------- 表单状态 ----------
const generating = ref(false);
const generatingStep = ref('');
const skipOptimization = ref(false);
const manualPrompt = ref('');
const manualNegative = ref('');

/**
 * 「取材范围」直接绑到 store，改一下就落盘。
 *
 * 以前是 `ref(props.defaultWindow ?? 5)` —— 局部状态，关掉弹窗就没了，
 * 下次打开又回到 5。用户明确要求"这个要有保存功能"。
 *
 * props.defaultWindow 仍然作为**首次使用**的初值（存档里还没有值时）。
 */
const contextWindow = computed<number>({
  get() {
    const v = imageStore.quickContextWindow;
    return typeof v === 'number' && v > 0 ? v : (props.defaultWindow ?? 5);
  },
  set(v: number) {
    void imageStore.updateQuickContext(v);
  },
});
/** 只取 AI 消息（配图要的是画面，用户那句"我走过去"帮助不大） */
const aiOnly = computed<boolean>({
  get() {
    return imageStore.quickAiOnly;
  },
  set(v: boolean) {
    void imageStore.updateQuickContext(undefined, v);
  },
});

/** 自定义标签输入框（回车或点「添加」都能提交） */
const customTagInput = ref('');

/** 把输入框里的东西加进自定义标签（支持一次贴一串逗号分隔的） */
function commitCustomTag() {
  const t = customTagInput.value.trim();
  if (!t) return;
  void storageSettings.addCustomTag(t);
  customTagInput.value = '';
}
const result = ref<string | null>(null);
const error = ref('');
const resultPrompt = ref('');
const resultNegativePrompt = ref('');
const resultSeed = ref<number | undefined>(undefined);
const tab = ref<'result' | 'settings'>('settings');
const showModels = ref(false);
const fetchingModels = ref(false);
const availableModels = ref<string[]>([]);
const modelError = ref('');

/**
 * 「用哪套大模型来写提示词」—— 跟着当前生图配置走，**存进 store**。
 *
 * 以前这里是个裸的 `ref<string | undefined>(undefined)`：
 * 选了模型只改了本地变量，从不写回 imageStore，于是弹窗一关就忘了 ——
 * 用户反馈"我选了其他模型，再打开还是显示跟随对话"。
 *
 * 现在直接用 store 里的 llmConfigId（那本来就是为这个用途留的字段，
 * 见 ImageApiEntry.llmConfigId 的注释），选完立刻落盘。
 * 空字符串 = 跟随当前对话模型（ModelPicker 的 allow-follow 约定）。
 */
const selectedLlmConfigId = computed<string | undefined>({
  get() {
    return imageStore.currentApi?.llmConfigId;
  },
  set(v) {
    const api = imageStore.currentApi;
    if (!api) return;
    // 选「跟随」时写空串而不是 undefined：前者是"明确选了跟随"，
    // 后者在 Object.assign 里会被当成"没这个字段"，旧值就清不掉。
    void imageStore.updateApi(api._uid, { llmConfigId: v ?? '' });
  },
});

const currentImageConfig = computed(() => imageStore.currentConfig);

/**
 * 预填提示词：从页面点「重新生图」进来时，把当初的画面描述填好，
 * 并自动切到手动模式（否则会被 LLM 重新改写一遍，就不是同一张图了）。
 */
watch(
  () => props.presetPrompt,
  (v) => {
    if (v && v.trim()) {
      manualPrompt.value = v.trim();
      skipOptimization.value = true;
    }
  },
  { immediate: true },
);

/** 页面常驻的图生图参考图（优先用页面传进来的） */
const effectiveReference = computed(() => props.referenceImage || imageStore.quickReferenceImage || '');
const effectiveStrength = computed(
  () => props.referenceStrength ?? imageStore.quickReferenceStrength ?? 0.6,
);

/** 用户在标签库里手挑的标签组成的参考块 */
function pickedReference(): string {
  return storageSettings.pickedTagReferenceText();
}
const currentApiConfig = computed(() => api.currentConfig as StoredApiConfig | undefined);
/**
 * 按「只取 AI 消息」的规则挑出要用的那段上下文。
 *
 * 用户的诉求（原话）：
 *   "点一下倒数第二条消息，他只会携带 ai 消息的倒数第二条 + 之前的 ai 消息"
 *
 * 所以 window 计的是**AI 回合数**，不是消息条数：
 *   取最近 N 条 assistant 消息，它们和各自的用户发言一起构成那段剧情。
 * 这样 N=2 就是"最后两回合"，不会因为中间夹着用户发言而少算一回合。
 *
 * 用户发言**保留**在拼接里（它是剧情的衔接，去掉会让上下文断片），
 * 只是不占"条数"名额。
 */
const effectiveMessages = computed(() => {
  const all = props.contextMessages || [];
  if (!aiOnly.value) return all;

  const aiIdx: number[] = [];
  all.forEach((m, i) => {
    if (m.role === 'assistant') aiIdx.push(i);
  });
  if (aiIdx.length === 0) return all;   // 一条 AI 消息都没有 → 原样返回

  const need = Math.max(1, contextWindow.value);
  const picked = aiIdx.slice(-need);
  const from = picked[0];
  // 从最早那回合开始，一直取到末尾（保留其后的用户发言，剧情才连贯）
  return all.slice(from);
});

const contextPreview = computed(() => extractContext(effectiveMessages.value, 999));
// 注：以前这里有个 llmConfigs 列表给扁平下拉用，现在换成 ModelPicker 了
// （它自己从 store 读 providers 分组，不用外面筛）。

const providerLabel = computed(() => {
  switch (currentImageConfig.value?.provider) {
    case 'novelai':
      return 'NovelAI 官方';
    case 'novelai-relay':
      return 'NovelAI 中转（官方格式）';
    case 'novelai-openai':
      return 'NovelAI 中转（OpenAI 格式）';
    case 'dall-e':
      return 'OpenAI 画图';
    case 'custom':
      return '自定义接口';
    default:
      return '未配置';
  }
});

const isRelayLike = computed(() =>
  ['novelai-relay', 'novelai-openai', 'custom'].includes(currentImageConfig.value?.provider || ''),
);

/** 生成前的配置检查，返回错误文字或空字符串 */
const configIssue = computed(() => {
  const cfg = currentImageConfig.value;
  if (!cfg) return '还没有生图配置，请到「设置」页新建一套。';
  if (cfg.provider === 'novelai') {
    if (!cfg.novelaiToken) return '请填写 NovelAI 持久化 Token。';
    return '';
  }
  if (!cfg.baseUrl) return '请填写请求地址（Base URL）。';
  if (cfg.provider === 'custom') return '';
  if (!cfg.apiKey && !cfg.novelaiToken) return '请填写 API Key 或中转 Token。';
  return '';
});

const canGenerate = computed(() => !generating.value && !configIssue.value);

// ---------- 模型拉取 ----------
async function pullModels() {
  const cfg = currentImageConfig.value;
  const baseUrl = cfg?.baseUrl || '';
  const apiKey = cfg?.apiKey || cfg?.novelaiToken || '';
  if (!baseUrl) {
    modelError.value = '请先填写请求地址（Base URL），例如 https://api.example.com/v1';
    return;
  }
  fetchingModels.value = true;
  modelError.value = '';
  showModels.value = true;
  try {
    availableModels.value = await fetchModels({ baseUrl, apiKey });
  } catch (e) {
    availableModels.value = [];
    modelError.value = e instanceof Error ? e.message : String(e);
  } finally {
    fetchingModels.value = false;
  }
}

function selectModel(model: string) {
  const cfg = currentImageConfig.value;
  if (!cfg) return;
  imageStore.updateConfig(cfg._uid, { model });
  showModels.value = false;
}

// ---------- 生成 ----------
async function startGenerate() {
  generating.value = true;
  error.value = '';
  result.value = null;
  resultPrompt.value = '';
  resultNegativePrompt.value = '';
  resultSeed.value = undefined;

  const imgCfg = currentImageConfig.value;
  if (!imgCfg) {
    error.value = '请先在「设置」页配置生图接口';
    generating.value = false;
    return;
  }
  if (configIssue.value) {
    error.value = configIssue.value;
    generating.value = false;
    return;
  }

  // 空值（跟随对话）就用当前对话模型
  const llmConfigId = selectedLlmConfigId.value || '';
  const llmConfig = (
    llmConfigId ? api.configs.find((c: any) => c._uid === llmConfigId) : currentApiConfig.value
  ) as StoredApiConfig | undefined;

  const wantOptimize =
    !skipOptimization.value && !manualPrompt.value.trim() && !!llmConfig?.apiKey;

  generatingStep.value = wantOptimize
    ? '🤖 正在用 LLM 优化生图提示词…'
    : '🎨 正在生成图片…';

  try {
    const imageConfig: ImageGenConfig = {
      provider: imgCfg.provider,
      novelaiToken: imgCfg.novelaiToken,
      novelaiModel: imgCfg.novelaiModel,
      host: imgCfg.host || undefined,
      baseUrl: imgCfg.baseUrl,
      apiKey: imgCfg.apiKey,
      model: imgCfg.model,
      ucPreset: imgCfg.ucPreset,
      qualityToggle: imgCfg.qualityToggle,
      cfgRescale: imgCfg.cfgRescale,
      noiseSchedule: imgCfg.noiseSchedule,
      sm: imgCfg.sm,
      smDyn: imgCfg.smDyn,
      autoSmea: imgCfg.autoSmea,
      dynamicThresholding: imgCfg.dynamicThresholding,
      sampler: imgCfg.sampler,
      steps: imgCfg.steps,
      width: imgCfg.width,
      height: imgCfg.height,
      cfgScale: imgCfg.cfgScale,
      seed: imgCfg.seed,
      artistTags: imgCfg.artistTags,
      qualityTags: imgCfg.qualityTags,
      negativePrompt: imgCfg.negativePrompt,
      promptTemplate: imgCfg.promptTemplate,
      /*
       * 图生图底图：**只**用"本次生成"的那张参考图。
       *
       * 以前还兜底读 imgCfg.referenceImage（配置里存的），
       * 那是风格里那张展示图的来源，会把出图悄悄变成图生图。
       * 现在 merged() 已经保证配置里不带 referenceImage，
       * 这条兜底永远取不到值，留着只会让读代码的人以为还有第二个来源。
       */
      referenceImage: effectiveReference.value || undefined,
      denoisingStrength: effectiveReference.value
        ? effectiveStrength.value
        : imgCfg.denoisingStrength,
    };

    const options: ImageGenOptions = {
      // 用筛好的那一段（只取 AI 回合），并且窗口给满 ——
      // 条数已经在上面的 effectiveMessages 里决定过了，
      // 这里再截一次会把刚挑好的内容又切掉。
      contextMessages: effectiveMessages.value,
      contextWindow: effectiveMessages.value.length,
      contextLabel: props.contextLabel,
      skipOptimization: skipOptimization.value,
      manualPrompt: manualPrompt.value.trim() || undefined,
      manualNegativePrompt: manualNegative.value.trim() || undefined,
      // 标签参考：给优化提示词的模型一份标准写法锚点，减少自造同义词。
      // 两部分组成：按分类自动组装的参考块 + 用户在标签库里手挑的标签。
      tagReference:
        storageSettings.tagReferenceEnabled && !skipOptimization.value
          ? [
              buildTagReference({
                enabledCategories: storageSettings.tagCategories,
                perCategory: storageSettings.tagPerCategory,
              }),
              pickedReference(),
            ]
              .filter(Boolean)
              .join('\n\n')
          : undefined,
      // 角色 DNA：勾选（enabled）的那几条，锚定固定外观
      dnaBlock: dnaStore.dnaBlock() || undefined,
      dnaTags: dnaStore.dnaTags() || undefined,
    };

    const genResult = await generateImage(
      llmConfig?.apiKey
        ? {
            baseUrl: llmConfig.baseUrl || '',
            apiKey: llmConfig.apiKey,
            model: llmConfig.model || '',
            temperature: llmConfig.temperature ?? 0.7,
            maxTokens: 900,
          }
        : null,
      imageConfig,
      options,
    );

    result.value = genResult.dataUrl;
    resultPrompt.value = genResult.prompt;
    resultNegativePrompt.value = genResult.negativePrompt || '';
    resultSeed.value = genResult.seed;
    tab.value = 'result';

    await imageStore.addImage({
      id: uid(),
      dataUrl: genResult.dataUrl,
      prompt: genResult.prompt,
      negativePrompt: genResult.negativePrompt || '',
      provider: genResult.provider,
      model: genResult.model,
      seed: genResult.seed,
      timestamp: Date.now(),
      context: contextPreview.value.slice(0, 200),
      scene: props.scene,
      entityId: props.entityId,
    });
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    generating.value = false;
    generatingStep.value = '';
  }
}

function insertImage() {
  if (result.value) {
    emit('generated', result.value);
    emit('close');
  }
}

function downloadImage() {
  if (!result.value) return;
  const a = document.createElement('a');
  a.href = result.value;
  a.download = `场景配图-${Date.now()}.png`;
  a.click();
}

async function copyPrompt(text: string) {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    /* 剪贴板不可用时忽略 */
  }
}

// 参考图上传（图生图）
/*
 * 参考图写进 store 级的 quickReferenceImage，**不写进任何配置**。
 *
 * 这一处原来写着 `updateConfig(cfg._uid, { referenceImage })`，
 * 而紧挨着的模板注释是「参考图是"本次生成"的输入，不属于任何配置」——
 * 注释和实现是矛盾的：放一张参考图会把**当前风格**永久改掉，
 * 而且那份风格以后每次出图都会变成图生图。
 *
 * 现在统一存到 quickReferenceImage（界面上那张"角色形象参考"），
 * 语义是"这一笔要照着谁画"，和风格本身无关。
 */
async function onReferenceFile(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = () => {
    imageStore.quickReferenceImage = reader.result as string;
    void imageStore.save();
  };
  reader.readAsDataURL(file);
}

function clearReference() {
  imageStore.quickReferenceImage = '';
  void imageStore.save();
}
</script>

<template>
  <div class="image-gen-overlay" @click.self="emit('close')">
    <div class="image-gen-modal">
      <div class="modal-header">
        <div class="title-wrap">
          <h3>🎨 场景配图生成</h3>
          <span class="provider-badge">{{ providerLabel }}</span>
        </div>
        <button class="close-btn" @click="emit('close')">✕</button>
      </div>

      <!-- 顶部页签 -->
      <div class="tabs">
        <button :class="{ active: tab === 'settings' }" @click="tab = 'settings'">参数设置</button>
        <button :class="{ active: tab === 'result' }" @click="tab = 'result'">
          生成结果<span v-if="result" class="dot"></span>
        </button>
      </div>

      <div class="modal-body">
        <!-- ========================= 参数设置 ========================= -->
        <template v-if="tab === 'settings'">
          <!-- 配置异常提示 -->
          <div v-if="configIssue" class="warn-msg">
            ⚠ {{ configIssue }}
            <button class="mini-btn" @click="tab = 'settings'">去设置页修改并保存后重试</button>
          </div>

          <!-- 取材范围 -->
          <section class="card">
            <div class="card-head">
              <span>📖 取材范围</span>
              <span class="hint">
                {{ aiOnly ? `取最近 ${contextWindow} 回合 AI 叙述` : `取最近 ${contextWindow} 条内容` }}
              </span>
            </div>
            <!--
              「只取 AI 消息」默认开。
              配图要的是**画面**，AI 的叙述才是画面来源；用户发言多是
              "我向前走一步"这类动作指令，对画面帮助小，还会稀释注意力。
              关掉就退回"按消息条数取材"，和以前一样。
            -->
            <label class="switch" style="margin-bottom: 8px;">
              <input type="checkbox" v-model="aiOnly" />
              <span>只取 AI 的叙述（按回合计数，不含我的发言）</span>
            </label>
            <div class="slider-row">
              <input type="range" min="1" max="20" v-model.number="contextWindow" />
              <input type="number" min="1" max="20" v-model.number="contextWindow" class="num-sm" />
            </div>
            <p class="hint" style="margin: 4px 0 0;">
              这个设置会自动记住，下次打开还是它。
            </p>
            <details class="preview-details">
              <summary>查看取材内容预览</summary>
              <div class="preview-content">{{ contextPreview || '（暂无内容）' }}</div>
            </details>
          </section>

          <!-- 提示词来源 -->
          <section class="card">
            <div class="card-head"><span>✍️ 提示词来源</span></div>
            <label class="switch">
              <input type="checkbox" v-model="skipOptimization" />
              <span>不调用 LLM，直接使用我写的提示词</span>
            </label>

            <template v-if="skipOptimization">
              <div class="form-group">
                <label>正向提示词（英文逗号分隔标签）</label>
                <textarea rows="3" v-model="manualPrompt"
                  placeholder="1girl, silver hair, red eyes, standing in a library, soft lighting"></textarea>
              </div>
            </template>
            <template v-else>
              <div class="form-group">
                <label>
                  使用哪套大模型来转换提示词
                  <span class="hint">留空则用当前对话模型</span>
                </label>
                <!--
                  生图调试时经常要换"写提示词的模型"：
                  有的模型更懂 Danbooru 标签，出图明显更准。
                  用统一的 ModelPicker，形态和对话界面一致（运营商 → 模型）。
                -->
                <ModelPicker
                  :model-value="selectedLlmConfigId ?? ''"
                  allow-follow
                  label="提示词模型"
                  @update:model-value="(v) => (selectedLlmConfigId = v)"
                />
              </div>
              <div class="form-group">
                <label>
                  补充一句画面要求（可留空，会附加给 LLM）
                </label>
                <input type="text" v-model="manualPrompt"
                  placeholder="例如：只要上半身特写，夜晚，雨中" />
              </div>
              <!-- 已裁剪：原为 86 字的界面说明（只对开发者有意义） -->
            </template>

            <div class="form-group">
              <label>本次指定负向提示词（可留空）</label>
              <textarea rows="2" v-model="manualNegative"
                placeholder="lowres, bad anatomy, bad hands, watermark"></textarea>
            </div>
          </section>

          <!-- 接口与模型 -->
          <section class="card">
            <div class="card-head">
              <span>🔌 本次使用（接口 / 风格）</span>
            </div>

            <!--
              这里只负责"选"，不负责"改"。
              生成时临时切一下接口或风格是很常见的事（换个中转、换套画风试效果），
              在生图弹窗里直接选最顺手；真要改连接或参数，去「设置 → 生图配置」。
              原来这里把 baseUrl / apiKey / model 直接铺出来让人改，
              既容易误改线上配置，也和"选配置"这件事混在一起。
            -->
            <div class="form-group">
              <label>生图接口</label>
              <select :value="imageStore.currentApiId"
                @change="imageStore.setCurrentApi(($event.target as HTMLSelectElement).value)">
                <option v-for="c in imageStore.apiConfigs" :key="c._uid" :value="c._uid">
                  {{ c.name }}{{ c.baseUrl ? ' · ' + c.baseUrl : '' }}
                </option>
              </select>
            </div>

            <div class="form-group">
              <label>出图风格<span class="hint-inline">画师串 / 反向提示词 / 采样器等</span></label>
              <select :value="imageStore.currentStyleId"
                @change="imageStore.setCurrentStyle(($event.target as HTMLSelectElement).value)">
                <option v-for="c in imageStore.styleConfigs" :key="c._uid" :value="c._uid">
                  {{ c.name }}{{ c.artistTags ? ' · ' + c.artistTags.slice(0, 24) : '' }}
                </option>
              </select>
            </div>

            <!-- 已裁剪：原为 262 字的界面说明（只对开发者有意义） -->

            <details class="adv-edit">
              <summary>高级：直接改这份配置</summary>

            <div v-if="isRelayLike" class="form-group">
              <label>请求地址（Base URL）</label>
              <input type="text" :value="currentImageConfig?.baseUrl"
                placeholder="https://api.example.com/v1"
                @change="currentImageConfig && imageStore.updateConfig(currentImageConfig._uid, { baseUrl: ($event.target as HTMLInputElement).value })" />
              <p class="tip">
                中转站填到 <code>/v1</code> 即可，程序会自动补全端点；
                也可以直接粘贴完整地址。
              </p>
            </div>

            <div v-if="currentImageConfig?.provider === 'novelai'" class="form-group">
              <label>NovelAI 持久化 Token</label>
              <input type="password" :value="currentImageConfig?.novelaiToken"
                @change="currentImageConfig && imageStore.updateConfig(currentImageConfig._uid, { novelaiToken: ($event.target as HTMLInputElement).value })" />
            </div>

            <div v-else-if="isRelayLike" class="form-group">
              <label>API Key / 中转 Token</label>
              <input type="password" :value="currentImageConfig?.apiKey"
                placeholder="sk-... 或 中转站令牌"
                @change="currentImageConfig && imageStore.updateConfig(currentImageConfig._uid, { apiKey: ($event.target as HTMLInputElement).value })" />
            </div>

            <div class="form-group">
              <label>模型</label>
              <div class="row-inline">
                <input type="text" :value="currentImageConfig?.model || currentImageConfig?.novelaiModel"
                  placeholder="nai-diffusion-4-5-full"
                  @change="currentImageConfig && imageStore.updateConfig(currentImageConfig._uid, { model: ($event.target as HTMLInputElement).value, novelaiModel: ($event.target as HTMLInputElement).value })" />
                <button class="mini-btn strong" :disabled="fetchingModels" @click="pullModels">
                  {{ fetchingModels ? '拉取中…' : '📥 拉取模型' }}
                </button>
              </div>
              <p class="tip">
                点「拉取模型」会请求 <code>{地址}/models</code>，自动列出该接口可用模型。
              </p>
            </div>

            <!-- 模型选择面板 -->
            <div v-if="showModels" class="model-panel">
              <div class="model-panel-head">
                <span v-if="fetchingModels">正在请求模型列表…</span>
                <span v-else-if="modelError" class="err">拉取失败</span>
                <span v-else>共 {{ availableModels.length }} 个可用模型</span>
                <button class="mini-btn" @click="showModels = false">关闭</button>
              </div>
              <div v-if="modelError" class="model-error">{{ modelError }}</div>
              <div v-else class="model-items">
                <label v-for="m in availableModels" :key="m" class="model-item">
                  <input type="radio" :checked="(currentImageConfig?.model || currentImageConfig?.novelaiModel) === m"
                    @change="selectModel(m)" />
                  <span>{{ m }}</span>
                </label>
              </div>
            </div>

            <!-- NovelAI 官方模型快选 -->
            <div v-if="currentImageConfig?.provider === 'novelai'" class="chip-row">
              <button v-for="m in NOVELAI_MODELS" :key="m.value" class="chip"
                :class="{ active: currentImageConfig?.novelaiModel === m.value }"
                @click="currentImageConfig && imageStore.updateConfig(currentImageConfig._uid, { novelaiModel: m.value, model: m.value })">
                {{ m.label }}
              </button>
            </div>
            </details>

            <!--
              参考图是"本次生成"的输入，不属于任何配置 —— 所以它存在
              store 级的 quickReferenceImage 里，切风格/切接口都不会带走它。
            -->
            <div v-if="isRelayLike" class="form-group">
              <label>参考图（图生图，可留空）</label>
              <div class="row-inline">
                <input type="file" accept="image/*" @change="onReferenceFile" />
                <button v-if="effectiveReference" class="mini-btn" @click="clearReference">
                  移除参考图
                </button>
              </div>
              <img v-if="effectiveReference" :src="effectiveReference" class="ref-thumb" alt="参考图" />
              <!-- 已裁剪：原为 83 字的界面说明（只对开发者有意义） -->
            </div>
          </section>

          <!-- 生图参数面板 -->
          <section class="card">
            <div class="card-head"><span>🎛️ 生图参数</span></div>
            <ImageParamsPanel v-if="currentImageConfig" :config="currentImageConfig" />
          </section>

          <!-- 角色 DNA -->
          <section class="card">
            <div class="card-head">
              <span>🧬 角色 DNA（锚定外观）</span>
              <span class="hint">勾选要锚定的角色特征</span>
            </div>
            <div v-if="dnaStore.items.length" class="dna-list">
              <label v-for="d in dnaStore.items" :key="d._uid" class="dna-item"
                :class="{ on: d.enabled !== false }">
                <input type="checkbox" :checked="d.enabled !== false"
                  @change="dnaStore.setEnabled(d._uid, ($event.target as HTMLInputElement).checked)" />
                <span class="dna-name">{{ d.name }}</span>
                <span class="dna-preview">{{ (d.dna || '(未填写)').slice(0, 60) }}</span>
              </label>
            </div>
            <p v-else class="tip">
              还没有角色 DNA。到「资产 → 角色 DNA」新建一条，写上角色的英文外观描述
              （如 <code>silver hair, red eyes</code>），生图时勾选即可锚定。
            </p>
            <!-- 这里以前还有一句"没勾选就用常驻的那条" —— 现在没有隐藏兜底了：勾了就生效，没勾就不生效 -->
            <p v-if="dnaStore.items.length && !dnaStore.enabledItems.length" class="tip">
              当前没有勾选任何角色 DNA，生图不会锚定角色外观。
            </p>
          </section>

          <!--
            ★ 标签参考：这里可以直接勾选**带哪些分类**。
            以前这里只有一句"分类与每类数量可在「设置 → 标签库」里调" ——
            想临时少带几类得跑去设置页改，回来又得改回去，很不自由。
            现在和标签库页一样可以在原地勾选，改完立刻落盘。
          -->
          <section v-if="!skipOptimization" class="card">
            <div class="card-head">
              <span>🏷️ 标签参考</span>
              <span class="hint">已选 {{ storageSettings.tagCategories.length }} 类 · 每类 {{ storageSettings.tagPerCategory }} 个</span>
            </div>
            <label class="switch">
              <input type="checkbox" :checked="storageSettings.tagReferenceEnabled"
                @change="storageSettings.tagReferenceEnabled = ($event.target as HTMLInputElement).checked; storageSettings.save()" />
              <span>优化提示词时附上 NovelAI 标准标签表（推荐开启）</span>
            </label>

            <!-- 关掉总开关就收起细节，避免一堆勾选框看着乱 -->
            <template v-if="storageSettings.tagReferenceEnabled">
              <div class="row" style="margin-top: 8px; align-items: center;">
                <div class="col">
                  <label>每类取几个</label>
                  <input type="number" min="8" max="60" step="4"
                    v-model.number="storageSettings.tagPerCategory"
                    @change="storageSettings.save()" />
                </div>
                <div class="col" style="flex: 0 0 auto; align-self: end;">
                  <button class="mini-btn" @click="storageSettings.tagCategories = TAG_CATEGORIES.map(c => c.id); storageSettings.save()">
                    全选
                  </button>
                  <button class="mini-btn" @click="storageSettings.tagCategories = []; storageSettings.save()">
                    全不选
                  </button>
                </div>
              </div>

              <!-- 分类勾选格：点哪个带哪个 -->
              <div class="tag-cat-grid">
                <label v-for="c in TAG_CATEGORIES" :key="c.id" class="tag-cat"
                  :class="{ on: storageSettings.tagCategories.includes(c.id) }">
                  <input type="checkbox" :checked="storageSettings.tagCategories.includes(c.id)"
                    @change="storageSettings.toggleTagCategory(c.id, ($event.target as HTMLInputElement).checked)" />
                  <span class="tc-name">{{ c.name }}</span>
                </label>
              </div>

              <!-- 手挑的标签也带上（和标签库页里"已选"是同一份） -->
              <p v-if="storageSettings.pickedTags.length" class="tip">
                另外还会带上你在标签库里手挑的 {{ storageSettings.pickedTags.length }} 个标签。
              </p>

              <!--
                ★ 自定义标签：自己敲的、标签库里没有的（比如角色专属设定）。
                和"在标签库里挑"是两条路：
                  挑的那个有中英对照、能清空重选；
                  这里的是一段固定要带上的文字，不该被"清空已选"带走。
              -->
              <div class="custom-tags">
                <label>自己加标签<span class="hint-inline">逗号分隔，会一起带给模型</span></label>
                <div class="ct-input-row">
                  <input type="text" v-model="customTagInput"
                    placeholder="例如：scar on left eye, black choker, my oc"
                    @keyup.enter="commitCustomTag" />
                  <button class="mini-btn" :disabled="!customTagInput.trim()" @click="commitCustomTag">
                    添加
                  </button>
                </div>
                <div v-if="storageSettings.customTags.length" class="ct-pills">
                  <button v-for="t in storageSettings.customTags" :key="t" class="ct-pill"
                    :title="'点一下移除：' + t" @click="storageSettings.removeCustomTag(t)">
                    {{ t }}<span class="ct-x">×</span>
                  </button>
                </div>
                <p v-else class="tip">还没有自定义标签。上面写几个，每次出图都会带上。</p>
              </div>
            </template>
          </section>

          <button class="primary generate-btn" :disabled="!canGenerate" @click="startGenerate">
            {{ generating ? '生成中…' : '🎨 开始生成' }}
          </button>
        </template>

        <!-- ========================= 生成结果 ========================= -->
        <template v-else>
          <div v-if="generating" class="generating-status">
            <span class="spinner">⏳</span>
            <span>{{ generatingStep }}</span>
          </div>

          <div v-if="error" class="error-msg">
            <strong>生成失败</strong>
            <pre>{{ error }}</pre>
          </div>

          <div v-if="result" class="result-section">
            <img :src="result" class="result-image" alt="生成的图片" />

            <div class="meta-grid">
              <div class="meta-item" v-if="resultSeed !== undefined">
                <span class="meta-key">随机种子</span>
                <span class="meta-val">{{ resultSeed }}</span>
              </div>
              <div class="meta-item">
                <span class="meta-key">模型</span>
                <span class="meta-val">{{ currentImageConfig?.model || currentImageConfig?.novelaiModel }}</span>
              </div>
            </div>

            <div class="result-info">
              <div class="info-head">
                <span class="prompt-label">正向提示词</span>
                <button class="mini-btn" @click="copyPrompt(resultPrompt)">复制</button>
              </div>
              <p class="prompt-text">{{ resultPrompt }}</p>
            </div>

            <div class="result-info" v-if="resultNegativePrompt">
              <div class="info-head">
                <span class="prompt-label">负向提示词</span>
                <button class="mini-btn" @click="copyPrompt(resultNegativePrompt)">复制</button>
              </div>
              <p class="prompt-text">{{ resultNegativePrompt }}</p>
            </div>

            <div class="result-actions">
              <button class="primary" @click="insertImage">📎 插入到场景</button>
              <button @click="downloadImage">⬇️ 下载</button>
              <button @click="result = null">✕ 关闭预览</button>
            </div>
            <p class="tip">图片已自动存入「图片库」，可在设置页查看历史。</p>
          </div>

          <div v-else-if="!generating && !error" class="empty-state">
            <p>还没有生成结果。</p>
            <button class="primary" @click="tab = 'settings'">去调整参数并生成</button>
          </div>

          <div v-if="generating || error" class="sticky-actions">
            <button class="primary" :disabled="generating" @click="startGenerate">🔁 重新生成</button>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.image-gen-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.65);
  backdrop-filter: blur(2px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
  padding: 16px;
}
.image-gen-modal {
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 14px;
  width: 100%;
  max-width: 1080px;
  height: 88vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.4);
}
.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 18px;
  border-bottom: 1px solid var(--border);
  flex: 0 0 auto;
}
.title-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
}
.modal-header h3 {
  margin: 0;
  font-size: 16px;
}
.provider-badge {
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--panel-2);
  border: 1px solid var(--border);
  color: var(--text-dim);
}
.close-btn {
  padding: 2px 10px;
  background: none;
  border: none;
  color: var(--text-dim);
  cursor: pointer;
  font-size: 18px;
}
.close-btn:hover {
  color: var(--text);
}
.tabs {
  display: flex;
  gap: 4px;
  padding: 8px 18px 0;
  border-bottom: 1px solid var(--border);
  flex: 0 0 auto;
}
.tabs button {
  position: relative;
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  padding: 8px 14px;
  font-size: 13px;
  color: var(--text-dim);
  cursor: pointer;
}
.tabs button.active {
  color: var(--accent);
  border-bottom-color: var(--accent);
  font-weight: 600;
}
.dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent-2);
  margin-left: 6px;
  vertical-align: middle;
}
.modal-body {
  padding: 16px 18px 24px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
  flex: 1;
}
.card {
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 12px 14px;
  background: var(--panel-2);
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--accent);
  flex-wrap: wrap;
}
/* 「高级：直接改这份配置」折叠块 —— 默认收起，免得误改线上配置 */
.adv-edit {
  margin-top: 10px;
  border-top: 1px dashed var(--border);
  padding-top: 8px;
}
.adv-edit > summary {
  cursor: pointer;
  font-size: 11.5px;
  color: var(--text-dim);
  padding: 4px 0;
  user-select: none;
}
.adv-edit > summary:hover {
  color: var(--accent);
}
.adv-edit[open] > summary {
  margin-bottom: 6px;
}
.hint {
  font-size: 10px;
  font-weight: 400;
  color: var(--text-dim);
}
.tip {
  font-size: 10px;
  color: var(--text-dim);
  margin: 0;
  line-height: 1.6;
}
.tip code {
  background: var(--code-bg);
  padding: 0 4px;
  border-radius: 3px;
}

/*
 * 标签分类勾选格。
 *
 * 和「设置 → 标签库」里那套视觉保持一致（那个是用户已经看惯的样子），
 * 但这里是 modal 内的 scoped 样式，所以要自己写一份。
 * 手机上两列，够点、又不至于把弹窗撑得太长。
 */
.tag-cat-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px;
  margin-top: 8px;
}
.tag-cat {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm, 6px);
  font-size: 11.5px;
  color: var(--text-dim);
  cursor: pointer;
  user-select: none;
}
.tag-cat.on {
  border-color: var(--accent);
  color: var(--text);
  background: color-mix(in srgb, var(--accent) 10%, transparent);
}
.tag-cat input {
  width: auto;
  margin: 0;
  flex-shrink: 0;
}
.tag-cat .tc-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---- 自定义标签 ---- */
.custom-tags {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--border);
}
.custom-tags > label {
  display: block;
  font-size: 11px;
  color: var(--text-dim);
  margin-bottom: 5px;
}
.ct-input-row {
  display: flex;
  gap: 6px;
  align-items: center;
}
.ct-input-row input {
  flex: 1;
  min-width: 0;
}
.ct-input-row .mini-btn {
  flex-shrink: 0;
}
.ct-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 7px;
}
.ct-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--panel-2);
  color: var(--text);
  cursor: pointer;
}
.ct-pill:hover {
  border-color: var(--danger, #e5484d);
  color: var(--danger, #e5484d);
}
.ct-x {
  opacity: 0.5;
  font-size: 12px;
}
/* ---- 角色 DNA ---- */
.dna-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.dna-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 9px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--panel);
  cursor: pointer;
  font-size: 11.5px;
  flex-wrap: wrap;
}
.dna-item.on {
  border-color: var(--accent);
  background: color-mix(in srgb, var(--accent) 12%, var(--panel));
}
.dna-item input {
  width: auto;
}
.dna-name {
  color: var(--text);
  font-weight: 600;
  white-space: nowrap;
}
.dna-pin {
  font-size: 9px;
  padding: 1px 6px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--accent) 25%, transparent);
  color: var(--accent);
  margin-left: 4px;
  font-weight: 400;
}
.dna-preview {
  flex: 1;
  min-width: 120px;
  color: var(--text-dim);
  font-size: 10.5px;
  font-family: ui-monospace, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: right;
}
.form-group {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.form-group label {
  font-size: 11px;
  color: var(--text-dim);
}
.form-group input[type='text'],
.form-group input[type='password'],
.form-group select,
.form-group textarea {
  font-size: 12px;
  padding: 6px 8px;
  width: 100%;
  box-sizing: border-box;
}
.form-group textarea {
  resize: vertical;
  font-family: inherit;
}
.row-inline {
  display: flex;
  gap: 6px;
  align-items: center;
}
.mini-btn {
  font-size: 10px;
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--panel);
  border: 1px solid var(--border);
  color: var(--text-dim);
  cursor: pointer;
  white-space: nowrap;
}
.mini-btn:hover:not(:disabled) {
  color: var(--text);
  border-color: var(--accent);
}
.mini-btn:disabled {
  opacity: 0.6;
  cursor: default;
}
.mini-btn.strong {
  border-color: var(--accent);
  color: var(--accent);
  font-size: 11px;
  padding: 6px 12px;
  border-radius: 6px;
}
.chip-row {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.chip {
  font-size: 10px;
  padding: 3px 9px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--panel);
  color: var(--text-dim);
  cursor: pointer;
}
.chip:hover {
  border-color: var(--accent);
  color: var(--text);
}
.chip.active {
  border-color: var(--accent);
  color: var(--accent);
  font-weight: 600;
}
.switch {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  cursor: pointer;
}
.switch input {
  width: auto;
}
.slider-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.slider-row input[type='range'] {
  flex: 1;
}
.num-sm {
  width: 64px;
  text-align: center;
  font-size: 12px;
  padding: 4px 6px;
}
.preview-details summary {
  font-size: 11px;
  color: var(--text-dim);
  cursor: pointer;
}
.preview-content {
  font-size: 11px;
  color: var(--text-dim);
  max-height: 140px;
  overflow-y: auto;
  padding: 8px;
  margin-top: 6px;
  background: var(--code-bg);
  border-radius: 6px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.warn-msg {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 10px 12px;
  border-radius: 8px;
  background: rgba(250, 204, 21, 0.12);
  border: 1px solid rgba(250, 204, 21, 0.35);
  color: #facc15;
  font-size: 12px;
}
.model-panel {
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow: hidden;
  background: var(--panel);
}
.model-panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 10px;
  background: var(--code-bg);
  font-size: 11px;
  color: var(--text-dim);
}
.model-panel-head .err {
  color: var(--danger);
}
.model-error {
  padding: 10px;
  font-size: 11px;
  color: var(--danger);
  white-space: pre-wrap;
  line-height: 1.6;
}
.model-items {
  display: flex;
  flex-direction: column;
  max-height: 220px;
  overflow-y: auto;
}
.model-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 10px;
  font-size: 11px;
  cursor: pointer;
}
.model-item:hover {
  background: var(--panel-2);
}
.model-item input {
  width: auto;
}
.model-item span {
  font-family: ui-monospace, monospace;
  word-break: break-all;
}
.ref-thumb {
  max-width: 140px;
  border-radius: 6px;
  border: 1px solid var(--border);
}
.generate-btn {
  width: 100%;
  padding: 12px;
  font-size: 14px;
  border-radius: 8px;
}
.generating-status {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px;
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 13px;
}
.spinner {
  animation: spin 1s linear infinite;
  display: inline-block;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
.error-msg {
  padding: 12px;
  background: rgba(248, 113, 113, 0.1);
  border: 1px solid rgba(248, 113, 113, 0.3);
  border-radius: 8px;
  color: var(--danger);
  font-size: 12px;
}
.error-msg strong {
  display: block;
  margin-bottom: 6px;
}
.error-msg pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  line-height: 1.6;
}
.empty-state {
  text-align: center;
  padding: 40px 20px;
  color: var(--text-dim);
  font-size: 13px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: center;
}
.result-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.result-image {
  width: 100%;
  max-height: 52vh;
  object-fit: contain;
  border-radius: 10px;
  border: 1px solid var(--border);
  background: var(--code-bg);
}
.meta-grid {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.meta-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 6px 10px;
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: 6px;
  min-width: 90px;
}
.meta-key {
  font-size: 10px;
  color: var(--text-dim);
}
.meta-val {
  font-size: 12px;
  font-family: ui-monospace, monospace;
  word-break: break-all;
}
.result-info {
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 10px;
}
.info-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}
.prompt-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-dim);
}
.prompt-text {
  margin: 0;
  font-size: 11px;
  line-height: 1.7;
  word-break: break-word;
  color: var(--text);
  max-height: 140px;
  overflow-y: auto;
}
.result-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.result-actions button {
  flex: 1;
  min-width: 110px;
  padding: 10px;
  border-radius: 8px;
}
.sticky-actions {
  display: flex;
  gap: 8px;
}
.sticky-actions button {
  flex: 1;
  padding: 10px;
}

@media (max-width: 768px) {
  .image-gen-modal {
    height: 94vh;
    max-width: 100%;
  }
  .modal-body {
    padding: 12px;
  }
  .result-actions button {
    min-width: 0;
  }
}
</style>
