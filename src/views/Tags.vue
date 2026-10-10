<script setup lang="ts">
// 标签库浏览器：浏览/检索完整 Danbooru 标签表（3700+ 条，带中文释义）
//
// 用途有两个：
//   1. 写作/生图时查标签的确切写法（NovelAI 对标签写法很挑）；
//   2. 挑一批标签组成"参考块"，生图优化提示词时会把它附给大模型。
import { ref, computed, onMounted, watch } from 'vue';
import { addAsset } from '../lib/imageAssets';
import { useTagLibraryStore } from '../stores/tagLibrary';
import { useStorageSettingsStore } from '../stores/storageSettings';
import { useImageStore } from '../stores/image';
import { buildTagReference } from '../engine/tagLibrary';
import { generateImage } from '../engine/imageGen';
import { NOVELAI_SAMPLERS, SAMPLER_HINTS } from '../engine/novelai';

const lib = useTagLibraryStore();
const settings = useStorageSettingsStore();
const imageStore = useImageStore();

/**
 * 采样器下拉的两组选项。
 * 与「设置 → 生图配置」里的 ImageParamsPanel 用**同一份源数据**
 * （NOVELAI_SAMPLERS + SAMPLER_HINTS），保证两处永远一致 ——
 * 以后官方加采样器只要改 engine/novelai.ts 一处。
 */
const COMMON_SAMPLERS = [
  'k_euler_ancestral',
  'k_euler',
  'k_dpmpp_2m_sde',
  'k_dpmpp_2m',
  'k_dpmpp_2s_ancestral',
  'k_dpmpp_sde',
  'ddim',
];
const samplerOptions = computed(() => {
  const all = [...NOVELAI_SAMPLERS] as string[];
  return {
    common: COMMON_SAMPLERS.filter((s) => all.includes(s)),
    rest: all.filter((s) => !COMMON_SAMPLERS.includes(s)),
  };
});

const query = ref('');
const showReference = ref(false);

/**
 * 已挑好的标签。
 *
 * 直接读写 store 里那份持久化的列表，而不是组件内部的局部数组 ——
 * 否则刷新页面就全丢了，而且「设置」里那份「我挑好的标签」参考块
 * （生图时会给大模型的那段）永远为空。
 */
const picked = computed({
  get: () => settings.pickedTags ?? [],
  set: (v: string[]) => {
    settings.pickedTags = v;
    void settings.save();
  },
});

// ---------------- 直接生图 ----------------
//
// 这里刻意**不经过大模型**：用户在标签库里挑的就是最终要用的标签，
// 再让 LLM 改写一遍纯属绕远路，还可能把它改错。
// 画师串、质量词、负面提示词这些由生图配置统一带上（在 provider 层拼接），
// 所以这里只需要把勾选的标签作为提示词本身。
const genExtra = ref('');
const genWidth = ref(832);
const genHeight = ref(1216);
const genSeed = ref(-1);
const generating = ref(false);
const genError = ref('');
const genResults = ref<{ dataUrl: string; prompt: string; seed: number }[]>([]);

/** 参考图（图生图）：复用页面上那份常驻设置 */
const refImage = ref(imageStore.quickReferenceImage || '');
const refStrength = ref(imageStore.quickReferenceStrength ?? 0.6);
const refInput = ref<HTMLInputElement | null>(null);

/**
 * 最终提示词：勾选的标签 + 补充说明。
 * 注意顺序 —— 标签在前，补充描述在后，模型对前面的词权重更高。
 */
const genPrompt = computed(() => {
  const tags = picked.value.join(', ');
  const extra = genExtra.value.trim();
  return [tags, extra].filter(Boolean).join(', ');
});

const currentConfig = computed(() => imageStore.currentConfig);
const canGenerate = computed(
  () => !!currentConfig.value && genPrompt.value.length > 0 && !generating.value,
);

async function onPickRef(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0];
  if (!file) return;
  try {
    // 原图直传，不缩放不重编码 —— 缩了会掉画质，也会把元数据抹掉
    const dataUrl = await readAsDataUrl(file);
    refImage.value = dataUrl;
    imageStore.quickReferenceImage = dataUrl;
    imageStore.quickReferenceStrength = refStrength.value;
    await imageStore.save();
  } catch (err) {
    genError.value = err instanceof Error ? err.message : String(err);
  } finally {
    if (refInput.value) refInput.value.value = '';
  }
}

/** 读成 dataURL，不做任何处理 */
function readAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onerror = () => reject(new Error('读取图片失败'));
    r.onload = () => resolve(r.result as string);
    r.readAsDataURL(file);
  });
}

function clearRef() {
  refImage.value = '';
  imageStore.quickReferenceImage = '';
  void imageStore.save();
}

async function onStrengthChange() {
  imageStore.quickReferenceStrength = refStrength.value;
  await imageStore.save();
}



async function doGenerate() {
  const cfg = currentConfig.value;
  if (!cfg) {
    genError.value = '还没有生图配置，请先到「设置 → 生图配置」里填好';
    return;
  }
  const prompt = genPrompt.value.trim();
  if (!prompt) {
    genError.value = '先勾几个标签，或写点补充描述';
    return;
  }

  generating.value = true;
  genError.value = '';

  try {
    const result = await generateImage(null, {
      ...cfg,
      width: genWidth.value,
      height: genHeight.value,
      seed: genSeed.value,
      // 图生图：有参考图才带上
      referenceImage: refImage.value || undefined,
      denoisingStrength: refImage.value ? refStrength.value : cfg.denoisingStrength,
    } as any, {
      contextMessages: [],
      skipOptimization: true,
      manualPrompt: prompt,
    } as any);

    const item = {
      dataUrl: result.dataUrl,
      prompt,
      seed: result.seed ?? genSeed.value,
      negativePrompt: result.negativePrompt ?? '',
      model: result.model ?? '',
      provider: result.provider ?? '',
      timestamp: Date.now(),
    };
    genResults.value = [item, ...genResults.value].slice(0, 24);

    /*
     * 存进**图片资产库**，这样「图片库」页里能看到、能管理。
     *
     * 三块数据各存各的（互不拖累）：
     *   · 生图工具（本页）→ 图片资产库，origin = tagtool
     *   · 对话/小说配图   → 图片资产库，origin = auto，聊天里只留 assetId 引用
     *   · 会话数据本身    → 不含任何图片本体
     */
    await addAsset(result.dataUrl, {
      prompt,
      negativePrompt: item.negativePrompt,
      provider: item.provider,
      model: item.model,
      seed: item.seed,
      description: prompt.slice(0, 120),
      timestamp: item.timestamp,
      origin: 'tagtool',
      scene: 'novel',
      entityId: 'tags',
    });
    if (result.dataUrl && result.dataUrl.startsWith('data:')) {
      // 顺手把图从"只读到就丢"变成可复用的资产
      genSaved.value = true;
      setTimeout(() => (genSaved.value = false), 3000);
    }
  } catch (e) {
    genError.value = e instanceof Error ? e.message : String(e);
  } finally {
    generating.value = false;
  }
}

/** 大图查看（手机上点缩略图必须能放大看，否则等于看不见） */
const viewerIndex = ref<number | null>(null);
const viewerItem = computed(() =>
  viewerIndex.value === null ? null : genResults.value[viewerIndex.value] ?? null,
);
const genSaved = ref(false);

// ---------------- 生图设置（就地改，写回配置） ----------------
const showGenSettings = ref(false);

/**
 * 是否用大模型优化提示词。
 * 存在生图配置的 autoImage.skipOptimization 上 —— 语义是"跳过优化"，
 * 所以勾选「优化」= skipOptimization 为 false。
 */
const useLlmOptimize = computed(() => !imageStore.autoImage.skipOptimization);

async function setOptimize(on: boolean) {
  await imageStore.updateAutoImage({ skipOptimization: !on });
}

/** 就地改当前生图配置 */
async function patchConfig(patch: Record<string, unknown>) {
  const id = imageStore.currentConfigId;
  const cfg = imageStore.configs.find((c) => c._uid === id);
  if (!cfg) return;
  Object.assign(cfg, patch);
  await imageStore.save();
}

/**
 * 一键出图：不展开设置、不做确认，直接用当前配置 + 勾选的标签出图。
 * 就是为了"点一下就有图"，所以走和普通生成完全一样的路径。
 */
async function quickGenerate() {
  await doGenerate();
}

function openViewer(i: number) {
  viewerIndex.value = i;
}

function downloadResult(item: { dataUrl: string; seed: number }) {
  const a = document.createElement('a');
  a.href = item.dataUrl;
  a.download = `标签直出-${item.seed}.png`;
  a.click();
}

function reuseAsRef(item: { dataUrl: string }) {
  refImage.value = item.dataUrl;
  imageStore.quickReferenceImage = item.dataUrl;
  void imageStore.save();
}

onMounted(() => {
  void lib.load();
  window.addEventListener('scroll', onPageScroll, { passive: true });
  void lib.loadExtra();
  // 生图配置是直接出图的前提，进页面就准备好
  void imageStore.load();
});

/*
 * 「显示更多」的分页游标。
 *
 * 为什么需要：库里是 4000+ 条，而 browse/search 一次只返回一段
 * （原来浏览 300、搜索 80），且**页面上没有任何"还有更多"的入口**。
 * 于是点进「人物」分类只能看到前 300 个，一千多条没有任何办法看到 ——
 * 用户反馈"标签库感觉缺了很多"，其实是**没翻页**，不是数据少。
 *
 * 现在每次点「显示更多」多放一批，想看多少看多少。
 */
const PAGE_STEP = 400;
const browseLimit = ref(400);

/* ==========================================================================
   三级下钻：���级小方块 → 二级小方块 → 三级标签
   （一级/二级都是"几个字"的小方块，一排放好几个；三级才是标签格子）
   ========================================================================== */

/** NSFW 默认关着 —— 内容多且敏感，不该一进页面就摆在那 */
const showNsfw = ref(false);
/** 当前展开的一级（单选：点一个换另一个，再点一次收起） */
const openGroup = ref('');
/** 当前选中的二级（空 = 看这一级下全部标签） */
const openSub = ref('');

const NSFW_GROUP = 'NSFW';

/** NSFW 分类的条数（开关上要显示"有多少个"） */
const nsfwCount = computed(
  () => lib.groupStats.find((g) => g.name === NSFW_GROUP)?.count ?? 0,
);

/** 顶栏显示的总数（跟着 NSFW 开关走） */
const visibleCount = computed(() =>
  visibleGroups.value.reduce((a, g) => a + g.count, 0),
);

/**
 * 一级分类列表 —— NSFW 关掉时**整类隐藏**，不是只筛标签。
 * 顺序固定，不按数量（按数量排序会让分类名每次刷新都在跳）。
 */
const GROUP_ORDER = ['服装', '角色扮演', '外表', '动作', '场景', '动物', '风格', NSFW_GROUP];
const visibleGroups = computed(() => {
  const stats = new Map(lib.groupStats.map((g) => [g.name, g.count]));
  return GROUP_ORDER.filter((g) => (showNsfw.value || g !== NSFW_GROUP) && stats.has(g)).map(
    (name) => ({ name, count: stats.get(name)! }),
  );
});

/** 某一级下的二级分类（含计数），按条数降序 */
const subIndex = computed(() => {
  const m = new Map<string, Map<string, number>>();
  for (const t of lib.tags) {
    if (!t.sub) continue;
    let inner = m.get(t.group);
    if (!inner) m.set(t.group, (inner = new Map()));
    inner.set(t.sub, (inner.get(t.sub) || 0) + 1);
  }
  return m;
});

/** 二级分类的中文显示名 */
const SUB_ZH: Record<string, string> = {
  outfit: '整装', accents: '配饰', headwear: '头饰', top: '上装', bottom: '下装',
  footwear: '鞋履', legwear: '腿袜', eyewear: '眼镜', underwear: '内衣',
  anatomy: '身体', hair: '发型', eyes: '眼睛', demographics: '人口属性',
  physiological: '生理反应', interaction: '互动', gesture: '手势',
  posture: '姿态', expression: '表情', camera: '镜头', kinetic: '动态',
  combat: '战斗', props: '道具', setting: '场景', atmosphere: '氛围',
  animal: '动物', style: '画风', layout: '构图', handheld: '手持物',
  weapon: '武器', cosplay: 'cosplay', misc: '其他',
};
function subLabel(sub: string): string {
  /* 新库的二级是英文代号（top/headwear…），按映射表翻；
     旧库的二级本来就是中文，直接显示。 */
  if (/[\u4e00-\u9fff]/.test(sub)) return sub;
  return SUB_ZH[sub] || sub;
}

function subsOf(group: string) {
  const inner = subIndex.value.get(group);
  if (!inner) return [];
  return [...inner.entries()]
    .map(([name, count]) => ({ name, label: subLabel(name), count }))
    .sort((a, b) => b.count - a.count);
}

/** 点一级：换一级（单选），二级清空重选；再点同一个收起 */
function pickGroup(name: string) {
  query.value = '';
  browseLimit.value = PAGE_STEP;
  if (openGroup.value === name) {
    openGroup.value = '';
    openSub.value = '';
  } else {
    openGroup.value = name;
    openSub.value = '';
  }
}

/** 点二级：出三级标签；再点同一个取消筛选、退回看整级 */
function pickSub(sub: string) {
  query.value = '';
  browseLimit.value = PAGE_STEP;
  openSub.value = openSub.value === sub ? '' : sub;
}

/** 当前二级的显示名（给标题用） */
const activeSubLabel = computed(() => (openSub.value ? subLabel(openSub.value) : ''));

/** NSFW 关掉时，如果正看着 NSFW 要退回去 */
watch(showNsfw, (on) => {
  if (!on && openGroup.value === NSFW_GROUP) {
    openGroup.value = '';
    openSub.value = '';
  }
});

const results = computed(() => {
  if (!query.value.trim()) {
    if (!openGroup.value) return [];
    return lib.browse(openGroup.value, openSub.value || undefined, browseLimit.value);
  }
  return lib.search(query.value, browseLimit.value, openGroup.value || undefined);
});

/** 当前条件下**总共**有多少个（用来判断还有没有没显示的） */
const totalInView = computed(() => {
  if (query.value.trim()) return lib.search(query.value, 1e9, openGroup.value || undefined).length;
  if (!openGroup.value) return 0;
  return lib.browse(openGroup.value, openSub.value || undefined, 1e9).length;
});

/** 还有多少没显示出来 */
const hiddenCount = computed(() => Math.max(0, totalInView.value - results.value.length));

function showMoreTags() {
  browseLimit.value += PAGE_STEP;
}

/** 换关键词 / 换分类时从头开始，不然会带着上一页的偏移量 */
watch([query, openGroup, openSub], () => {
  browseLimit.value = PAGE_STEP;
});


/** 一级/二级变化时，"显示更多"的偏移要重置 */
watch([openGroup, openSub], () => {
  browseLimit.value = PAGE_STEP;
});

function togglePick(tag: string) {
  picked.value = picked.value.includes(tag)
    ? picked.value.filter((t) => t !== tag)
    : [...picked.value, tag];
}

/**
 * tag → 中文 的查表。
 *
 * 为什么需要：勾选区存的是 `string[]`（纯英文 tag），
 * 直接渲染出来就是一串看不懂的英文 —— 用户反馈"选完之后
 * 显示一大堆不知道哪个是哪个"。
 *
 * 这里只影响**显示**：拼给生图模型的参考块仍然只用英文
 * （见下面 pickedReference —— 模型要的就是标准英文写法）。
 */
const zhOf = computed(() => {
  const m = new Map<string, string>();
  for (const t of lib.tags) {
    if (t && t.tag) m.set(t.tag, t.zh || '');
  }
  for (const t of lib.extraTags || []) {
    // 导入的标签多半没有中文，别覆盖掉内置的中文
    if (t && t.tag && !m.has(t.tag)) m.set(t.tag, t.zh || '');
  }
  return (tag: string): string => m.get(tag) || '';
});

function pickAllVisible() {
  const add = results.value.map((r) => r.tag).filter((t) => !picked.value.includes(t));
  picked.value = [...picked.value, ...add];
}

/** 把勾选的标签组成一段参考文本 */
const pickedReference = computed(() => {
  if (picked.value.length === 0) return '';
  return (
    '【我挑好的标签】' + picked.value.join(', ') + '\n\n' +
    '写提示词时请优先从上面这些标签里选，保持写法完全一致。'
  );
});

/** 自动分类参考块（原有逻辑） */
const autoReference = computed(() =>
  buildTagReference({
    enabledCategories: settings.tagCategories,
    perCategory: settings.tagPerCategory,
  }),
);

const referenceText = computed(() =>
  [autoReference.value, pickedReference.value].filter(Boolean).join('\n\n'),
);

function copyReference() {
  void navigator.clipboard.writeText(referenceText.value);
}

/**
 * 回顶部。
 *
 * 这个页面现在很长：生图区 + 分类 + 标签格子，选到深处标签后想回分类重新选，
 * 手动往上滑很费劲。点一下直接回顶。
 */
const showTopBtn = ref(false);

function onPageScroll() {
  showTopBtn.value = window.scrollY > 420;
}

function scrollToTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

function clearPicked() {
  picked.value = [];
}
</script>

<template>
  <div class="tag-page">
    <div class="page-head">
      <h2>🏷️ 标签库</h2>
      <div class="head-meta">
        <template v-if="lib.loading && !lib.count">加载中…</template>
        <template v-else-if="lib.error" class="err">{{ lib.error }}</template>
        <template v-else>
          共 <strong>{{ visibleCount }}</strong> 个标签 · {{ visibleGroups.length }} 个分类
          <span v-if="!showNsfw && nsfwCount" class="dim">
            （另有 {{ nsfwCount }} 个 NSFW 标签，已按开关隐藏）
          </span>
          <span v-if="lib.extraTags.length" class="dim">（另有导入的 {{ lib.extraTags.length }} 个）</span>
        </template>
      </div>
    </div>

    <!--
      ===== 直接生图（放在标签库最上面）=====
      为什么提到最前：这是本页最"能出成果"的功能（勾标签 → 直接出图），
      压在标签列表下面要滚很久才看到，等于藏起来了。
    -->
    <details class="gen-collapse" :open="true">
      <summary class="gen-summary">🎨 直接生图<span class="dim">勾标签直接出图，不走大模型</span></summary>
      <div class="gen-inner">
      <div class="gen-row">
        <label class="gen-label">生图配置</label>
        <select :value="imageStore.currentConfigId"
          @change="imageStore.setCurrent(($event.target as HTMLSelectElement).value)">
          <option v-for="c in imageStore.configs" :key="c._uid" :value="c._uid">{{ c.name }}</option>
        </select>
        <span v-if="!imageStore.configs.length" class="err">没有配置，请先到「设置 → 生图配置」新建</span>
      </div>

      <div class="gen-row">
        <label class="gen-label">尺寸</label>
        <input type="number" class="num" v-model.number="genWidth" step="64" min="64" max="2048" />
        <span class="dim">×</span>
        <input type="number" class="num" v-model.number="genHeight" step="64" min="64" max="2048" />
        <label class="gen-label">种子</label>
        <input type="number" class="num" v-model.number="genSeed" />
        <span class="dim">-1 为随机</span>
      </div>

      <div class="gen-row">
        <label class="gen-label">补充描述</label>
        <input type="text" class="grow" v-model="genExtra"
          placeholder="可留空。会拼在勾选标签后面，例如 1girl, standing, night" />
      </div>

      <!-- 图生图 -->
      <div class="gen-row">
        <label class="gen-label">图生图</label>
        <input ref="refInput" type="file" accept="image/*" class="hidden-file" @change="onPickRef" />
        <template v-if="refImage">
          <img :src="refImage" class="ref-thumb" alt="参考图" title="点一下清除" @click="clearRef" />
          <input type="range" min="0.1" max="0.95" step="0.05" v-model.number="refStrength"
            @change="onStrengthChange" />
          <span class="dim">强度 {{ Math.round(refStrength * 100) }}%</span>
          <button class="mini" @click="clearRef">清除</button>
        </template>
        <template v-else>
          <button class="mini" @click="refInput?.click()">放入参考图</button>
          <span class="dim">不放就是纯文生图</span>
        </template>
      </div>

      <div class="gen-prompt">
        <span class="gen-label">最终提示词</span>
        <pre>{{ genPrompt || '(还没勾选标签)' }}</pre>
      </div>

      <div class="gen-actions">
        <button class="primary gen-go" :disabled="!canGenerate" @click="doGenerate">
          {{ generating ? '生成中…' : '生成图片' }}
        </button>
        <button class="gen-go" :disabled="!canGenerate" @click="quickGenerate"
          title="用当前配置直接出图，不展开任何面板">
          ⚡ 一键出图
        </button>
        <button class="mini" @click="showGenSettings = !showGenSettings">
          {{ showGenSettings ? '收起设置' : '生图设置' }}
        </button>
        <span v-if="genError" class="err">{{ genError }}</span>
      </div>

      <!-- 生图设置：常用的那几项，改完立刻生效（写回生图配置） -->
      <div v-if="showGenSettings" class="gen-settings">
        <label class="gs-row">
          <input type="checkbox" :checked="useLlmOptimize"
            @change="setOptimize(($event.target as HTMLInputElement).checked)" />
          <span>
            用大模型优化提示词
            <em class="gs-note">
              （关闭 = 勾选的标签直接作为提示词，更快更省，推荐）
            </em>
          </span>
        </label>
        <div class="gs-row">
          <label>画师串</label>
          <textarea rows="2" class="gs-area" :value="currentConfig?.artistTags ?? ''"
            placeholder="(artist:xxx:1.0), …"
            @change="patchConfig({ artistTags: ($event.target as HTMLTextAreaElement).value })"></textarea>
        </div>
        <div class="gs-row">
          <label>质量词</label>
          <input type="text" :value="currentConfig?.qualityTags ?? ''"
            placeholder="best quality, amazing quality…"
            @change="patchConfig({ qualityTags: ($event.target as HTMLInputElement).value })" />
        </div>
        <div class="gs-row">
          <label>负面提示词</label>
          <textarea rows="2" class="gs-area" :value="currentConfig?.negativePrompt ?? ''"
            @change="patchConfig({ negativePrompt: ($event.target as HTMLTextAreaElement).value })"></textarea>
        </div>
        <div class="gs-row gs-inline">
          <label>步数</label>
          <input type="number" class="gs-num" :value="currentConfig?.steps ?? 28" min="1" max="100"
            @change="patchConfig({ steps: Number(($event.target as HTMLInputElement).value) || 28 })" />
          <label>CFG</label>
          <input type="number" class="gs-num" step="0.5" :value="currentConfig?.cfgScale ?? 5" min="0" max="30"
            @change="patchConfig({ cfgScale: Number(($event.target as HTMLInputElement).value) || 5 })" />
          <label>采样器</label>
          <!--
            以前这里是**纯文本输入框** —— 用户得凭记忆手打
            `k_dpmpp_2m_sde_gpu` 这种名字，打错一个字母就出不了图，
            而且根本不知道有哪些可选。
            改成和「设置 → 生图配置」完全同源的下拉：
            常用 / 全部两组，每项带中文说明，共 14 个官方采样器。
          -->
          <select class="gs-sampler" :value="currentConfig?.sampler ?? ''"
            @change="patchConfig({ sampler: ($event.target as HTMLSelectElement).value })">
            <optgroup label="常用">
              <option v-for="s in samplerOptions.common" :key="s" :value="s">
                {{ s }} — {{ SAMPLER_HINTS[s] || '' }}
              </option>
            </optgroup>
            <optgroup label="全部（含 GPU / RTX 变体）">
              <option v-for="s in samplerOptions.rest" :key="s" :value="s">
                {{ s }} — {{ SAMPLER_HINTS[s] || '' }}
              </option>
            </optgroup>
          </select>
        </div>
        <p class="gs-hint">
          这里的修改会直接写回当前生图配置，和「设置 → 生图配置」是同一份数据。
        </p>
      </div>

      <div v-if="genResults.length" class="gen-gallery">
        <div v-for="(g, i) in genResults" :key="i" class="gen-item">
          <img :src="g.dataUrl" alt="生成结果" loading="lazy" @click="openViewer(i)" />
          <div class="gen-item-actions">
            <button class="mini" @click="openViewer(i)">查看</button>
            <button class="mini" @click="downloadResult(g)">下载</button>
            <button class="mini" @click="reuseAsRef(g)">用作参考图</button>
          </div>
          <div class="gen-item-prompt">{{ g.prompt }}</div>
        </div>
      </div>
      <p v-if="genSaved" class="gen-saved">✓ 已存入「图片库」，可在左侧导航查看</p>

      <!-- 大图查看 -->
      <div v-if="viewerItem" class="gen-viewer" @click.self="viewerIndex = null">
        <div class="gv-inner">
          <img :src="viewerItem.dataUrl" alt="预览" />
          <div class="gv-info">
            <div class="gv-row"><span>种子</span><em>{{ viewerItem.seed }}</em></div>
            <div class="gv-row gv-prompt"><span>提示词</span><em>{{ viewerItem.prompt }}</em></div>
          </div>
          <div class="gv-actions">
            <button class="mini" @click="downloadResult(viewerItem!)">下载</button>
            <button class="mini" @click="reuseAsRef(viewerItem!)">用作参考图</button>
            <button class="mini" @click="viewerIndex = null">关闭</button>
          </div>
        </div>
      </div>
      </div>
    </details>

    <!-- 回顶部：页面很长，选到深处的标签后想回分类重新选，滑上去很费劲 -->
    <button v-show="showTopBtn" class="to-top" @click="scrollToTop" title="回到最上方">
      ↑ 顶部
    </button>

    <p class="hint">
      下面是完整的 Danbooru 标签表，带中文释义。NovelAI 对标签写法很挑剔
      （要写 <code>silver hair</code> 而不是 <code>silver colored hair</code>），
      所以拿不准就到这里查一下。<br>
      勾选一批标签后，会在生图优化提示词时作为「参考块」一起发给大模型。
    </p>

    <!-- 搜索 -->
    <div class="search-row">
      <input type="text" v-model="query" placeholder="搜索标签：中文或英文都行，例如「银发」/ silver / 汉服" />
    </div>

    <!--
      NSFW 开关。
      放最上面是因为它决定**后面所有列表看到什么** —— 关掉时整个 NSFW 分类
      连同它的标签一起从界面上消失（不是只筛搜索结果）。
      标签本身不会丢，随时能开回来。
    -->
    <label class="nsfw-switch">
      <input type="checkbox" v-model="showNsfw" />
      <span class="nsfw-txt">
        显示 NSFW 标签<em>{{ nsfwCount }} 个</em>
      </span>
    </label>

    <!--
      ============ 一级分类：小方块，常显 ============
      一级/二级都只有几个字，所以做成小方块（chip），一排放好几个，
      8 个分类一屏就看完了，不用像列表那样一行占一整屏。
    -->
    <div class="chip-row lv1">
      <button
        v-for="g in visibleGroups"
        :key="g.name"
        class="chip c1"
        :class="{ active: openGroup === g.name }"
        @click="pickGroup(g.name)"
      >
        {{ g.name }}<em>{{ g.count }}</em>
      </button>
    </div>

    <!-- ============ 二级分类：小方块，点了一级才出 ============ -->
    <div v-if="openGroup && !query.trim()" class="chip-row lv2">
      <span class="chip-lead">{{ openGroup }} ·</span>
      <button
        class="chip c2 all"
        :class="{ active: !openSub }"
        @click="openSub = ''; query = ''"
      >
        全部
      </button>
      <button
        v-for="s in subsOf(openGroup)"
        :key="s.name"
        class="chip c2"
        :class="{ active: openSub === s.name }"
        @click="pickSub(s.name)"
      >
        {{ s.label }}<em>{{ s.count }}</em>
      </button>
    </div>

    <!-- ============ 三级：标签格子（中英对照） ============ -->
    <div v-if="!openGroup && !query.trim()" class="cat-hint">
      点上面任意一个分类 → 选二级 → 看到里面的标签。
    </div>

    <!-- 已选 -->
    <div v-if="picked.length" class="picked-box">
      <div class="picked-head">
        <span>已选 {{ picked.length }} 个标签</span>
        <div class="row">
          <button class="mini" @click="showReference = !showReference">
            {{ showReference ? '隐藏' : '预览' }}参考块
          </button>
          <button class="mini" @click="copyReference">复制参考块</button>
          <button class="mini danger" @click="clearPicked">清空</button>
        </div>
      </div>
      <div class="picked-tags">
        <button v-for="t in picked" :key="t" class="picked-tag" @click="togglePick(t)">
          <span class="pt-en">{{ t }}</span>
          <span v-if="zhOf(t)" class="pt-zh">{{ zhOf(t) }}</span>
          <span class="x">×</span>
        </button>
      </div>
      <pre v-if="showReference" class="ref-preview">{{ referenceText }}</pre>
    </div>


    <p class="hint" style="margin: 0 0 8px;">勾选的标签会作为「参考块」一起发给生图模型（只用英文标准写法）。</p>

    <!-- 结果 -->
    <div class="result-head">
      <span class="dim">
        <template v-if="query.trim()">搜索「{{ query }}」找到 {{ totalInView }} 个<template v-if="hiddenCount > 0">（已显示前 {{ results.length }} 个）</template></template>
        <template v-else-if="openSub">「{{ openGroup }} · {{ activeSubLabel }}」下的标签</template>
        <template v-else-if="openGroup">「{{ openGroup }}」下的全部标签<template v-if="hiddenCount > 0">（共 {{ totalInView }} 个）</template></template>
        <template v-else>输入关键词搜索，或点上面的分类浏览</template>
      </span>
      <button v-if="results.length" class="mini" @click="pickAllVisible">全部选中当前 {{ results.length }} 条</button>
    </div>

    <!-- 三级：标签格子（中英对照） -->
    <div class="tag-grid">
      <button v-for="t in results" :key="t.tag" class="tag-cell"
        :class="{ on: picked.includes(t.tag) }" @click="togglePick(t.tag)">
        <span class="t">{{ t.tag }}</span>
        <span class="z">{{ t.zh || '—' }}</span>
        <span v-if="!openGroup || !openSub" class="g">{{ t.group }}<template v-if="t.sub"> / {{ subLabel(t.sub) }}</template></span>
      </button>
    </div>

    <!--
      「显示更多」：库里 4000+ 条，一次只渲染一批。
      以前没有这个入口，分类里上千条根本翻不到（用户以为库不全）。
    -->
    <div v-if="hiddenCount > 0" class="more-tags">
      <span class="dim">还有 {{ hiddenCount }} 个没显示</span>
      <button class="mini primary" @click="showMoreTags">显示更多</button>
    </div>

    <div v-if="!results.length && !lib.loading" class="empty">
      <template v-if="query.trim()">没有匹配的标签，换个词试试（中文释义也能搜）。</template>
      <template v-else>点上面的分类，或输入关键词开始搜索。</template>
    </div>
  </div>
</template>

<style scoped>
.tag-page {
  padding: 20px 24px 40px;
  max-width: var(--content-max, 1100px);
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.page-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.page-head h2 {
  margin: 0;
  font-size: 20px;
}
.head-meta {
  font-size: 12px;
  color: var(--text-dim);
}
.head-meta strong {
  color: var(--accent);
}
.err {
  color: var(--danger);
}
.hint {
  font-size: 11px;
  color: var(--text-dim);
  margin: 0;
  line-height: 1.65;
}
.hint code {
  background: var(--code-bg);
  padding: 0 4px;
  border-radius: 3px;
}
.search-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.search-row input {
  flex: 2;
  min-width: 180px;
  font-size: 13px;
  padding: 8px 11px;
}
.search-row select {
  flex: 1;
  min-width: 120px;
  font-size: 12px;
  padding: 8px 10px;
}
.cat-chips {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.chip {
  font-size: 11.5px;
  padding: 4px 11px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--panel);
  color: var(--text-dim);
  cursor: pointer;
}
.chip.active {
  border-color: var(--accent);
  color: var(--accent);
  font-weight: 600;
}
.chip em {
  font-style: normal;
  font-size: 9.5px;
  opacity: 0.65;
  margin-left: 4px;
}
.picked-box {
  border: 1px solid var(--accent);
  border-radius: 8px;
  padding: 9px 11px;
  background: var(--panel-2);
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.picked-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--accent);
}
.row {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.mini {
  font-size: 10.5px;
  padding: 3px 10px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--panel);
  color: var(--text-dim);
  cursor: pointer;
}
.mini:hover {
  color: var(--text);
  border-color: var(--accent);
}
.mini.danger:hover {
  color: var(--danger);
  border-color: var(--danger);
}
.picked-tags {
  display: flex;
  gap: 5px;
  flex-wrap: wrap;
  max-height: 120px;
  overflow-y: auto;
}
.picked-tag {
  font-size: 11px;
  padding: 2px 9px;
  border-radius: 999px;
  border: 1px solid var(--accent);
  background: var(--panel);
  color: var(--accent);
  cursor: pointer;
}
.picked-tag .x {
  opacity: 0.6;
  margin-left: 3px;
}
.ref-preview {
  margin: 0;
  padding: 8px 10px;
  background: var(--code-bg);
  border-radius: 6px;
  font-size: 10.5px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 220px;
  overflow-y: auto;
}
/* 「显示更多」：库里 4000+ 条，一次只渲染一批 */
.more-tags {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 14px 0 4px;
}

/* 已选标签：英文在上、中文在下 —— 光看英文认不出是什么 */
.pt-en {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
}
.pt-zh {
  font-size: 10.5px;
  opacity: 0.72;
}

.result-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.dim {
  color: var(--text-dim);
  font-size: 11.5px;
}
/* ==========================================================================
   分类：两级小方块（chip）
   ========================================================================== */
.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 8px 0;
}
.chip {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  padding: 7px 12px;
  min-height: 34px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--surface-1, var(--panel-2));
  box-shadow: none;
  color: var(--text);
  font-size: 13px;
  line-height: 1.2;
  white-space: nowrap;
}
.chip em {
  font-style: normal;
  font-size: 10.5px;
  opacity: 0.6;
}
.chip:hover {
  border-color: var(--accent);
}
.chip.active {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--surface-1, #fff);
  font-weight: 600;
}
.chip.active em {
  opacity: 0.85;
}
.chip-lead {
  align-self: center;
  font-size: 12px;
  color: var(--text-dim);
  flex: 0 0 auto;
}
.chip-row.lv2 {
  padding: 8px 10px;
  border: 1px dashed var(--border);
  border-radius: var(--radius);
  background: color-mix(in srgb, var(--panel-2) 55%, transparent);
}

/* 回顶部按钮：右下角悬浮，滚过一屏才出现 */
.to-top {
  position: fixed;
  right: 14px;
  bottom: calc(var(--tabbar-h, 63px) + 16px);
  z-index: 35;
  padding: 8px 14px;
  min-height: 38px;
  border-radius: 999px;
  background: var(--accent);
  color: var(--surface-1, #fff);
  border: none;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.28);
  font-size: 12.5px;
}

/* 生图区：默认展开（这是本页最出成果的功能），但能收起来 */
.gen-collapse {
  margin: 10px 0 4px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-1, var(--panel-2));
  overflow: hidden;
}
.gen-summary {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 11px 12px;
  min-height: 42px;
  font-size: 13.5px;
  font-weight: 600;
  cursor: pointer;
  list-style: none;
}
.gen-summary::-webkit-details-marker {
  display: none;
}
.gen-summary .dim {
  font-weight: 400;
  font-size: 11.5px;
}
.gen-inner {
  padding: 0 12px 12px;
}

.cat-hint {
  padding: 14px 12px;
  text-align: center;
  color: var(--text-dim);
  font-size: 12.5px;
}

/* NSFW 开关 */
.nsfw-switch {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 9px 12px;
  margin: 8px 0 2px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-1, var(--panel-2));
  cursor: pointer;
  min-height: 42px;
}
.nsfw-switch input {
  width: 18px;
  height: 18px;
  flex: 0 0 auto;
  min-width: 0;
  min-height: 0;
}
.nsfw-txt {
  font-size: 13px;
  display: flex;
  align-items: baseline;
  gap: 6px;
}
.nsfw-txt em {
  font-style: normal;
  font-size: 11px;
  color: var(--text-dim);
}

.tag-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(210px, 1fr));
  gap: 6px;
}
.tag-cell {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  text-align: left;
  padding: 7px 10px;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: var(--panel);
  cursor: pointer;
  min-width: 0;
}
.tag-cell:hover {
  border-color: var(--accent);
}
.tag-cell.on {
  border-color: var(--accent);
  background: color-mix(in srgb, var(--accent) 14%, var(--panel));
}
.tag-cell .t {
  font-size: 12px;
  color: var(--text);
  font-family: ui-monospace, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}
.tag-cell .z {
  font-size: 11px;
  color: var(--text-dim);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}
.tag-cell .g {
  font-size: 9.5px;
  color: var(--text-dim);
  opacity: 0.7;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}
.empty {
  padding: 30px;
  text-align: center;
  color: var(--text-dim);
  font-size: 12px;
}

/* ===== 直接生图 ===== */
.gen-box {
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--panel);
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 9px;
}
.gen-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}
.gen-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
}
.gen-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 12px;
}
.gen-label {
  font-size: 11px;
  color: var(--text-dim);
  white-space: nowrap;
  min-width: 56px;
}
.gen-row select,
.gen-row input[type='text'],
.gen-row input.num {
  font-size: 12px;
  padding: 5px 8px;
}
.gen-row input.num {
  width: 76px;
}
.gen-row .grow {
  flex: 1;
  min-width: 160px;
}
.hidden-file {
  display: none;
}
.ref-thumb {
  width: 40px;
  height: 40px;
  object-fit: cover;
  border-radius: 6px;
  border: 1px solid var(--accent);
  cursor: pointer;
}
.gen-row input[type='range'] {
  width: 110px;
}
.gen-prompt {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.gen-prompt pre {
  margin: 0;
  padding: 8px 10px;
  background: var(--code-bg);
  border-radius: 6px;
  font-size: 11px;
  font-family: ui-monospace, monospace;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 110px;
  overflow-y: auto;
}
.gen-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.gen-go {
  padding: 8px 22px;
  font-size: 13px;
}
.gen-gallery {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 10px;
  margin-top: 4px;
}
.gen-item {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.gen-item img {
  width: 100%;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: var(--code-bg);
  display: block;
  /* 手机上点图放大：给个明确的可点反馈 */
  cursor: zoom-in;
}
.gen-saved {
  margin: 4px 0 0;
  font-size: 11px;
  color: var(--accent-2);
}
/* 大图查看 */
.gen-viewer {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.85);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 120;
  padding: 14px;
}
.gv-inner {
  max-width: 860px;
  width: 100%;
  max-height: 92vh;
  overflow-y: auto;
  background: var(--panel);
  border-radius: 12px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.gv-inner img {
  width: 100%;
  border-radius: 8px;
  background: var(--code-bg);
}
.gv-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 11px;
}
.gv-row {
  display: flex;
  gap: 8px;
}
.gv-row span {
  color: var(--text-dim);
  flex-shrink: 0;
  min-width: 44px;
}
.gv-row em {
  font-style: normal;
  color: var(--text);
  word-break: break-word;
}
.gv-prompt em {
  font-family: ui-monospace, monospace;
  font-size: 10.5px;
}
.gv-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

/* 生图设置 */
.gen-settings {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel-2);
  margin-top: 4px;
}
.gs-row {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  color: var(--text-dim);
  flex-wrap: wrap;
}
.gs-row input[type='checkbox'] {
  width: auto;
}
.gs-row label {
  white-space: nowrap;
}
.gs-row input[type='text'],
.gs-row textarea {
  flex: 1;
  min-width: 140px;
  font-size: 11.5px;
  padding: 4px 8px;
  font-family: inherit;
}
.gs-area {
  resize: vertical;
  font-family: ui-monospace, monospace !important;
}
.gs-num {
  width: 64px;
  font-size: 11.5px;
  padding: 3px 6px;
}
.gs-num-wide {
  flex: 1;
  min-width: 110px;
  font-size: 11.5px;
  padding: 3px 6px;
}
.gs-inline {
  gap: 8px;
}
.gs-note {
  font-style: normal;
  color: var(--text-dim);
  opacity: 0.8;
}
/* 采样器下拉：跟旁边的数字输入框同一行，但内容长（带中文说明）所以更宽 */
.gs-sampler {
  flex: 1 1 150px;
  min-width: 0;
  font-size: 12px;
  padding: 5px 6px;
}

.gs-hint {
  margin: 0;
  font-size: 10px;
  color: var(--text-dim);
}
.gen-item-actions {
  display: flex;
  gap: 5px;
}
.gen-item-prompt {
  font-size: 9.5px;
  color: var(--text-dim);
  font-family: ui-monospace, monospace;
  max-height: 42px;
  overflow: hidden;
  word-break: break-all;
}
.err {
  color: var(--danger);
  font-size: 11.5px;
}

@media (max-width: 768px) {
  .gen-label {
    min-width: 0;
  }
  .gen-gallery {
    grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  }
}
</style>