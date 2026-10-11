<script setup lang="ts">
import { onMounted, onUnmounted, ref, computed, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAssetsStore } from '../stores/assets';
import { useApiConfigStore } from '../stores/apiConfig';
import ModelPicker from '../components/ModelPicker.vue';
import SettingsGrid from '../components/SettingsGrid.vue';
import GalleryPanel from '../components/GalleryPanel.vue';
import { useImageStore } from '../stores/image';
import { exportAll, importAll } from '../lib/storage';
import { persist } from '../lib/persist';
import type { PersistStatus } from '../lib/persist';
import { useStorageSettingsStore } from '../stores/storageSettings';
import { useAppearanceStore, BACKGROUND_PRESETS, ACCENT_PRESETS, THEME_PRESETS } from '../stores/appearance';
import { TAG_CATEGORIES, buildTagReference, searchImportedTags, parseTagLibrary } from '../engine/tagLibrary';
import { testGithubConnection } from '../engine/githubStorage';
import type { StoredApiConfig } from '../stores/apiConfig';
import { fetchModels, testImageConnection, probeEndpoint } from '../engine/imageGen';
import { chatCompletion } from '../engine/apiClient';
import {
  NOVELAI_MODELS,
  IMAGE_ENDPOINT_SUFFIXES,
  buildEndpointFromSuffix,
  suffixFromEndpoint,
} from '../engine/novelai';
import ImageParamsPanel from '../components/ImageParamsPanel.vue';

/* ===== 按键音效 ===== */
import { soundPrefs, playSound } from '../lib/sound';
const soundEnabled = ref(soundPrefs.enabled);
const soundVolume = ref(soundPrefs.volume);
function setSoundEnabled(v: boolean) { soundEnabled.value = v; soundPrefs.enabled = v; }
function setSoundVolume(v: number) { soundVolume.value = v; soundPrefs.volume = v; }
function previewSound() { playSound('send'); }

const assets = useAssetsStore();
const api = useApiConfigStore();
const imageStore = useImageStore();
const storageSettings = useStorageSettingsStore();
const appearance = useAppearanceStore();
const loaded = ref(false);
const exportJson = ref('');
const importResult = ref('');
const apiKeyVisible = ref(false);
const imageKeyVisible = ref(false);

/** 「检查更新」按钮状态 */
const updating = ref(false);
const updateMsg = ref('');
async function doCheckUpdate() {
  updating.value = true;
  updateMsg.value = '';
  try {
    const { checkForUpdate } = await import('../main');
    const r = await checkForUpdate();
    updateMsg.value =
      r === 'updated'
        ? '已拉到新版本，页面马上会刷新…'
        : r === 'latest'
          ? '已经是最新版本。若界面仍不对，可尝试清一下缓存或重装 App。'
          : '当前环境不支持自动检查更新（浏览器里直接访问时正常，可能是 App 内嵌页面）。';
  } catch {
    updateMsg.value = '检查更新失败，可尝试清缓存或重装 App。';
  } finally {
    updating.value = false;
  }
}

/*
 * 设置页导航：卡片索引 → 独立详情页。
 *
 * 以前这里是 10 个折叠面板（collapsed 记录谁展开）。
 * 改成现在这样是因为**折叠列表在分区多的时候找不到东西**：
 * 标题一样大、看不出层级，想知道"这里能干什么"得逐个点开。
 *
 * 现在：进设置先看到一屏方块（每块一个功能），点进去是那一块的详情页。
 * 这是主流做法（iOS/Android/Notion 设置都一样），分区越多越明显。
 *
 * ★ 子项状态要和 URL 同步（这是安卓返回手势能正常工作的前提）。
 *
 * 之前 activeSection 只是个内存里的 ref，URL 一直是 /#/settings ——
 * 于是「进子项」在浏览器历史里**不留任何记录**，返回手势直接穿透，
 * 表现为"在子项里滑两次退出，而不是退回设置首页"。
 * （对比：冒险页的二级是 /adventure/:id，真路由，所以能正常退回。）
 *
 * 改法：进子项时把 sec 写进 query，用 **replace** 而不是 push ——
 *   · replace 保证一级页面之间不堆栈（和底部标签栏一个道理）
 *   · query 变了历史里就有一条记录，返回手势自然回到 /#/settings
 *   · 再滑一次才退出应用 —— 这才是安卓该有的行为
 */
const route = useRoute();
const router = useRouter();

function normalizeSec(v: unknown): string | null {
  const s = typeof v === 'string' ? v : '';
  return s && cards.some((c) => c.key === s) ? s : null;
}

const activeSection = ref<string | null>(normalizeSec(route.query.sec));

/** 进入某个子项（URL 同步，replace 不堆栈） */
function openSection(key: string) {
  activeSection.value = key;
  void router.replace({ path: '/settings', query: { sec: key } });
}

/** 回到设置首页 */
function closeSection() {
  activeSection.value = null;
  void router.replace({ path: '/settings', query: {} });
}

/* 浏览器/安卓返回手势、浏览器前进后退，都同步回界面状态 */
watch(
  () => route.query.sec,
  (v) => { activeSection.value = normalizeSec(v); },
);

/** 卡片索引的数据 */
const cards = [
  { key: 'api', icon: '🤖', title: '对话模型接口', desc: '大语言模型：地址、密钥、模型、温度、上下文窗口' },
  /*
   * 生图接口 和 出图风格 是**两个独立子项**。
   *
   * 混在一页里的问题：找接口设置要在风格卡片中间翻，
   * 调风格又怕手滑把地址改了 —— 而这两件事使用频率都不高，
   * 放一起只会互相干扰。拆开各自干净。
   */
  { key: 'image', icon: '🔌', title: '生图接口', desc: '地址、密钥、模型（怎么连上去）' },
  { key: 'style', icon: '🎨', title: '出图风格', desc: '画师串 / 反向提示词 / 采样器 / 参数' },
  /*
   * 「显示与上下文」单独一项。
   * 它管的是"界面上看到什么、交给模型/导出的是什么"，跟生图无关 ——
   * 早先混在生图配置里，找"隐藏思维链"的人根本不会去翻生图接口。
   */
  { key: 'display', icon: '👁️', title: '显示与上下文', desc: '思维链 / 正则作用范围（原文还是处理后的文本）' },
  { key: 'library', icon: '🖼️', title: '图片库', desc: '查看占用、清理无用缓存' },
  { key: 'tags', icon: '🏷️', title: '生图标签库', desc: '给大模型的标准 Danbooru 标签参考' },
  { key: 'appearance', icon: '🌸', title: '界面外观', desc: '背景 / 主题配色 / 正文宽度' },
  { key: 'gh', icon: '☁️', title: '图片存放位置', desc: '本地存储或 GitHub 仓库（自动上传）' },
  { key: 'persist', icon: '💾', title: '数据持久化', desc: '把配置写到本地文件（APK 用）' },
  { key: 'data', icon: '📦', title: '数据导入导出', desc: '备份 / 恢复全部配置' },
  { key: 'about', icon: '✨', title: '关于', desc: '版本、更新检查' },
];

/** 详情页标题 */
function titleOf(key: string): string {
  const c = cards.find((x) => x.key === key);
  return c ? c.icon + ' ' + c.title : '设置';
}

// 图片接口模型拉取
const fetchingImgModels = ref(false);
const imgModels = ref<string[]>([]);
const imgModelError = ref('');
const showImgModels = ref(false);

// 大模型列表拉取
const fetchingLlmModels = ref(false);
const llmModels = ref<string[]>([]);
const llmModelError = ref('');
const showLlmModels = ref(false);

onMounted(async () => {
  // 手机上先把次要分区折起来，进页面就是一份"目录"而不是七千像素的长表单

  try {
    await Promise.all([
      assets.load(),
      api.load(),
      imageStore.load(),
      storageSettings.load(),
    ]);
  } catch (e) {
    console.error('加载失败:', e);
  }
  loaded.value = true;
  offPersist = persist.onChange((s) => (persistStatus.value = s));
  void refreshBackups();
  void refreshOrigins();
});

onUnmounted(() => offPersist?.());

// ---------- 标签库 ----------
const showTagPreview = ref(false);
const tagQuery = ref('');
const tagLibMsg = ref('');
const tagLibOk = ref(false);

const tagPreviewText = computed(() =>
  buildTagReference({
    enabledCategories: storageSettings.tagCategories,
    perCategory: storageSettings.tagPerCategory,
    extraTags: searchImportedTags(storageSettings.importedTagLib ?? undefined, tagQuery.value, 40),
  }),
);

const tagSearchResults = computed(() =>
  searchImportedTags(storageSettings.importedTagLib ?? undefined, tagQuery.value, 40),
);

async function onTagLibFile(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0];
  if (!file) return;
  tagLibMsg.value = '';
  try {
    const raw = JSON.parse(await file.text());
    const lib = parseTagLibrary(raw, file.name);
    if (lib.tags.length === 0) {
      tagLibOk.value = false;
      tagLibMsg.value =
        '这个文件里没有解析出标签。支持的形状：数组 [{tag, zh}]、{ tags: [...] }、或 { "1girl": "..." } 字典。';
      return;
    }
    await storageSettings.setImportedTagLib(lib);
    tagLibOk.value = true;
    tagLibMsg.value = `导入成功：${lib.tags.length} 个标签（来自 ${file.name}）`;
  } catch (err) {
    tagLibOk.value = false;
    tagLibMsg.value = `导入失败：${err instanceof Error ? err.message : String(err)}`;
  }
}

// ---------- 界面外观 ----------
const bgError = ref('');
async function onBackgroundFile(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0];
  if (!file) return;
  bgError.value = '';
  try {
    await appearance.setCustomImageFile(file);
  } catch (err) {
    bgError.value = err instanceof Error ? err.message : String(err);
  }
}

// ---------- GitHub 图片仓库 ----------
const ghTesting = ref(false);
const ghTestResult = ref<{ ok: boolean; message: string } | null>(null);

async function testGithub() {
  const cfg = storageSettings.currentGithub();
  if (!cfg) return;
  ghTesting.value = true;
  ghTestResult.value = null;
  try {
    const r = await testGithubConnection(cfg);
    ghTestResult.value = { ok: r.ok, message: r.message };
    if (r.ok) {
      // 探测结果回存，显示图片时才知道要不要带令牌拉取
      await storageSettings.updateGithub(cfg._uid, {
        isPrivate: r.isPrivate,
        branch: cfg.branch || r.defaultBranch || 'main',
      });
    }
  } finally {
    ghTesting.value = false;
  }
}

// ---------- 本地落盘状态 ----------
const persistStatus = ref<PersistStatus>(persist.getStatus());
const backups = ref<{ name: string; bytes: number; mtime: number }[]>([]);
const origins = ref<{ origin: string; keys: number; updatedAt: number }[]>([]);
let offPersist: (() => void) | undefined;

async function refreshOrigins() {
  origins.value = await persist.listOrigins();
}

const persistModeLabel = computed(() => {
  switch (persistStatus.value.mode) {
    case 'file':
      return '本地文件（权威副本）';
    case 'browser':
      return '仅浏览器存储（会因清缓存/换地址丢失）';
    default:
      return '检测中…';
  }
});

function formatBytes(n?: number) {
  if (!n) return '—';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(2)} MB`;
}

function formatTime(t?: number) {
  return t ? new Date(t).toLocaleString('zh-CN') : '—';
}

async function refreshBackups() {
  backups.value = await persist.listBackups();
}

/** 立刻把当前配置写盘 */
const savingNow = ref(false);

/*
 * 手动写入 / 刷新备份的反馈文字。
 *
 * 之前这两个按钮点完**没有任何反馈** —— 按钮从"写入中…"变回原样就结束了，
 * 用户不知道成功还是失败，只能靠猜（商业软件不该这样）。
 * 现在无论成败都给一句明确的话，几秒后自动消失。
 */
const saveMsg = ref('');
const saveMsgOk = ref(true);
let saveMsgTimer: ReturnType<typeof setTimeout> | undefined;

function flashSaveMsg(text: string, ok = true) {
  saveMsg.value = text;
  saveMsgOk.value = ok;
  if (saveMsgTimer) clearTimeout(saveMsgTimer);
  saveMsgTimer = setTimeout(() => (saveMsg.value = ''), 6000);
}

async function saveToFileNow() {
  savingNow.value = true;
  try {
    await persist.flush();
    await refreshBackups();
    flashSaveMsg('已写入本地文件 ✓');
  } catch (e) {
    flashSaveMsg('写入失败：' + (e instanceof Error ? e.message : String(e)), false);
  } finally {
    savingNow.value = false;
  }
}

const currentConfig = computed(() => api.currentConfig as StoredApiConfig | undefined);
/** 当前选中的运营商（地址 / 密钥 / 备注都挂在它上面） */
const currentProvider = computed(() => api.currentProviderFull);

/**
 * 切换运营商 —— 切到那一家的**第一个**模型。
 *
 * 为什么不是"切到同名模型"：不同家的模型名完全不一样
 * （一家是 gemini-*、另一家是 gpt-*），硬找同名只会落空。
 * 切到该家第一个模型、再由用户在下拉里挑具体模型，最不容易出错。
 */
async function onSwitchProvider(uid: string) {
  if (uid) await api.setCurrent(uid);
}

/*
 * 连接信息（地址 / 密钥 / 备注）的输入处理
 * ---------------------------------------------------------------------------
 * 这里从 @change 改成 @input 驱动，是因为一个实测出来的 bug：
 *
 *   地址框原来只在 @change（**失焦**）时才写入。手机上用户填完地址
 *   直接点「拉取模型列表」，Android WebView 往往不先失焦就派发点击，
 *   change 根本没触发 → 地址还是空的 → 拉取报"请先填写请求地址"。
 *   （实测：只派发 change 能存进去，只派发 input 存不进去，正是这个原因。）
 *
 * 现在：@input 就写进**内存**（后续操作立刻读到最新值），落盘防抖 500ms；
 * @change（失焦）时立刻落盘，保证不丢。
 *
 * 为什么不用 v-model：这两个字段是"运营商级别"的，要同步到这一家下面
 * 所有模型，直接绑 currentConfig.baseUrl 只会改到当前那一条 ——
 * 那正是被抱怨的"每个模型都要配一遍"。
 */

/** 开始编辑时那一家的键。清空地址再重打时靠它保持"同一家"的判断 */
const editingProviderKey = ref<string | null>(null);
let connSaveTimer: number | undefined;

function onConnFocus() {
  editingProviderKey.value = currentProvider.value?.key ?? null;
}

function onConnBlur() {
  editingProviderKey.value = null;
}

/**
 * 提交连接信息。
 * @param immediate true = 立刻落盘（失焦时用）；false = 防抖落盘（打字时用）
 */
function commitConn(patch: { baseUrl?: string; apiKey?: string }, immediate = false) {
  const cur = api.currentConfig;
  if (!cur) return;
  void api.updateProvider(cur._uid, patch, {
    persist: false,
    groupKey: editingProviderKey.value ?? undefined,
  });
  clearTimeout(connSaveTimer);
  if (immediate) {
    void api.save();
    return;
  }
  connSaveTimer = window.setTimeout(() => void api.save(), 500);
}

/** 备注：只在内存里更新，同样防抖落盘 */
function commitNote(key: string, note: string, immediate = false) {
  if (!key) return;
  void api.setProviderNote(key, note);
  clearTimeout(connSaveTimer);
  if (immediate) return;
  connSaveTimer = window.setTimeout(() => void api.save(), 500);
}

/** 新增一家：先建一条空白配置（地址留空，等用户填） */
async function addProvider() {
  const cfg = await api.addConfig('新运营商');
  // 清掉继承来的默认模型名，避免误以为是可用的
  await api.updateConfig(cfg._uid, { model: '', apiKey: '', baseUrl: '' });
}

/** 删除当前这一家（它下面的模型一起删） */
async function removeCurrentProvider() {
  const p = currentProvider.value;
  if (!p) return;
  const n = p.models.length;
  const ok = window.confirm(
    n > 1
      ? `确定删除这一家的全部 ${n} 个模型吗？\n\n（地址：${p.baseUrl || '未填'}）`
      : '确定删除这家运营商吗？',
  );
  if (!ok) return;
  const cur = api.currentConfig;
  if (cur) await api.removeProvider(cur._uid);
}

/**
 * 在这家下面加一个模型。
 *
 * store 里会自动复用这一家的 baseUrl / apiKey —— 不用用户再填一遍密钥，
 * 这正是这次改动的目的。
 *
 * 传 uid 而不是 baseUrl：新建的运营商地址是空的，用空地址定位会让
 * 两家没填地址的运营商混在一起（见 store 里 providerKeyOf 的说明）。
 */
async function addModelToCurrentProvider() {
  const cur = api.currentConfig;
  if (!cur) return;
  await api.addModelToProvider(cur._uid, '', '新模型');
}

/** 删除当前模型（这家还有别的模型时用它） */
async function removeCurrentModel() {
  const cfg = currentConfig.value;
  if (!cfg) return;
  const ok = window.confirm(`确定删除模型「${cfg.model || cfg._name}」吗？`);
  if (!ok) return;
  await api.removeConfig(cfg._uid);
}

const imgProviderLabel: Record<string, string> = {
  novelai: 'NovelAI 官方',
  'novelai-relay': 'NovelAI 中转（官方格式）',
  'novelai-openai': 'NovelAI 中转（OpenAI 格式）',
  'dall-e': 'OpenAI 画图（DALL·E / gpt-image）',
  custom: '自定义接口',
};

function patchImage(patch: Record<string, unknown>) {
  const cfg = imageStore.currentConfig;
  if (cfg) imageStore.updateConfig(cfg._uid, patch as any);
}

/* ---------- 风格缩略图 ---------- */

/** 隐藏的文件选择框（换风格缩略图用） */
const styleThumbInput = ref<HTMLInputElement | null>(null);
/** 这次点的是哪套风格 */
let styleThumbTarget = '';

function pickStyleThumb(uid: string) {
  styleThumbTarget = uid;
  styleThumbInput.value?.click();
}

/**
 * 读用户选的那张图，压到 256px 见方再存进 **风格参考图（thumb）**。
 *
 * 这是"这套画风长什么样"的展示图，**不参与出图**。
 * 真正驱动 img2img 的"照着谁画"那张图，在对话/生图界面里放
 * （存 quickReferenceImage）—— 两件事，两个地方，别混。
 *
 * 为什么要压：原图动辄几 MB，存进 IndexedDB 会把配置撑大、同步变慢。
 * 256px 见方做卡片缩略图足够清楚。
 */
async function onStyleThumbPicked(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file || !styleThumbTarget) return;
  try {
    const dataUrl = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.onerror = () => reject(new Error('读取失败'));
      reader.readAsDataURL(file);
    });
    const shrunk = await new Promise<string>((resolve) => {
      const img = new Image();
      img.onload = () => {
        const SIZE = 256;
        const c = document.createElement('canvas');
        c.width = SIZE;
        c.height = SIZE;
        const ctx = c.getContext('2d');
        if (!ctx) return resolve(dataUrl);
        // 居中裁成正方形，卡片里才不会变形
        const side = Math.min(img.width, img.height);
        ctx.drawImage(
          img,
          (img.width - side) / 2,
          (img.height - side) / 2,
          side,
          side,
          0,
          0,
          SIZE,
          SIZE,
        );
        resolve(c.toDataURL('image/jpeg', 0.82));
      };
      img.onerror = () => resolve(dataUrl);
      img.src = dataUrl;
    });
    await imageStore.updateStyle(styleThumbTarget, { thumb: shrunk });
  } catch {
    /* 读图失败就当没点过，不打断用户 */
  }
}

/** 生图接口拉取模型 */
async function pullImageModels() {
  const cfg = imageStore.currentConfig;
  if (!cfg) return;
  const baseUrl = cfg.baseUrl || '';
  if (!baseUrl) {
    imgModelError.value = '请先填写请求地址（Base URL）';
    showImgModels.value = true;
    return;
  }
  fetchingImgModels.value = true;
  imgModelError.value = '';
  showImgModels.value = true;
  try {
    imgModels.value = await fetchModels({
      baseUrl,
      apiKey: cfg.apiKey || cfg.novelaiToken || '',
    });
  } catch (e) {
    imgModels.value = [];
    imgModelError.value = e instanceof Error ? e.message : String(e);
  } finally {
    fetchingImgModels.value = false;
  }
}

/** 对话大模型拉取 */
async function pullLlmModels() {
  const cfg = currentConfig.value;
  if (!cfg) return;
  if (!cfg.baseUrl) {
    llmModelError.value = '请先填写请求地址（Base URL）';
    showLlmModels.value = true;
    return;
  }
  fetchingLlmModels.value = true;
  llmModelError.value = '';
  showLlmModels.value = true;
  try {
    llmModels.value = await fetchModels({ baseUrl: cfg.baseUrl, apiKey: cfg.apiKey });
  } catch (e) {
    llmModels.value = [];
    llmModelError.value = e instanceof Error ? e.message : String(e);
  } finally {
    fetchingLlmModels.value = false;
  }
}

function useImageModel(model: string) {
  patchImage({ model, novelaiModel: model });
  showImgModels.value = false;
}

function useLlmModel(model: string) {
  if (currentConfig.value) {
    currentConfig.value.model = model;
    api.save();
  }
  showLlmModels.value = false;
}

// 注：图片库的"下载 / 删除 / 清理"现在由 GalleryPanel 组件负责，
// 这里不再需要本地的 downloadGenerated（已随第三份实现一起删掉）。

// ---------- 连通性测试 ----------
const testingLlm = ref(false);
const llmTestResult = ref<{ ok: boolean; message: string } | null>(null);

const testingImg = ref(false);
const imgTestResult = ref<{ ok: boolean; message: string; endpoint?: string } | null>(null);

/** 测试对话模型接口：发一条极短的真实请求 */
async function testLlmConnection() {
  const cfg = currentConfig.value;
  if (!cfg) return;
  if (!cfg.baseUrl) {
    llmTestResult.value = { ok: false, message: '请先填写请求地址（Base URL）' };
    return;
  }
  if (!cfg.model) {
    llmTestResult.value = { ok: false, message: '请先填写模型名称' };
    return;
  }
  testingLlm.value = true;
  llmTestResult.value = null;
  const started = Date.now();
  try {
    const reply = await chatCompletion(
      { ...cfg, maxTokens: 32, temperature: 0 },
      [{ role: 'user', content: '请只回复两个字：正常' }],
    );
    llmTestResult.value = {
      ok: true,
      message: `连接成功（${Date.now() - started}ms）· 模型回复：${reply.trim().slice(0, 60) || '（空回复）'}`,
    };
  } catch (e) {
    llmTestResult.value = {
      ok: false,
      message: e instanceof Error ? e.message : String(e),
    };
  } finally {
    testingLlm.value = false;
  }
}

/** 测试生图接口：真的生成一张最小参数的图，确认地址/密钥/模型都能用 */
async function testImgConnection() {
  const cfg = imageStore.currentConfig;
  if (!cfg) return;
  testingImg.value = true;
  imgTestResult.value = null;
  const started = Date.now();
  try {
    const r = await testImageConnection({
      provider: cfg.provider,
      novelaiToken: cfg.novelaiToken,
      novelaiModel: cfg.novelaiModel,
      baseUrl: cfg.baseUrl,
      apiKey: cfg.apiKey,
      model: cfg.model,
      ucPreset: cfg.ucPreset,
      qualityToggle: cfg.qualityToggle,
      cfgRescale: cfg.cfgRescale,
      noiseSchedule: cfg.noiseSchedule,
      sampler: cfg.sampler,
      steps: 1,
      width: 832,
      height: 1216,
      cfgScale: cfg.cfgScale,
      negativePrompt: 'lowres',
      artistTags: cfg.artistTags,
      qualityTags: '',
    });
    imgTestResult.value = {
      ok: r.ok,
      message: `${r.message}（耗时 ${Date.now() - started}ms）`,
      endpoint: r.endpoint,
    };
    // 测试成功就把可用端点回填到端点面板，省得再手点一遍
    if (r.ok && r.endpoint) {
      const suf = suffixFromEndpoint(r.endpoint);
      if (suf) selectedSuffix.value = suf;
    }
  } catch (e) {
    imgTestResult.value = {
      ok: false,
      message: e instanceof Error ? e.message : String(e),
    };
  } finally {
    testingImg.value = false;
  }
}

// ---------- 生图端点自选 / 自测 ----------
/** 每个候选后缀的测试结果 */
const endpointResults = ref<Record<string, { ok: boolean; message: string; testing?: boolean }>>({});
const selectedSuffix = ref('');
const showEndpointPanel = ref(false);

/** 去掉已知后缀后的“基础地址”，用于拼接候选 */
function baseUrlWithoutSuffix(): string {
  const cfg = imageStore.currentConfig;
  const raw = (cfg?.baseUrl || '').trim().replace(/\/+$/, '');
  for (const s of IMAGE_ENDPOINT_SUFFIXES) {
    if (raw.endsWith(s.suffix)) return raw.slice(0, -s.suffix.length).replace(/\/+$/, '');
  }
  return raw;
}

const candidateUrls = computed(() => {
  const base = baseUrlWithoutSuffix();
  if (!base) return [];
  return IMAGE_ENDPOINT_SUFFIXES.map((s) => ({
    ...s,
    url: buildEndpointFromSuffix(base, s.suffix),
  }));
});

function openEndpointPanel() {
  const cfg = imageStore.currentConfig;
  // 已保存的端点 → 回显选中的后缀
  const saved = cfg?.endpoint || '';
  selectedSuffix.value = saved ? suffixFromEndpoint(saved) : '';
  endpointResults.value = {};
  showEndpointPanel.value = !showEndpointPanel.value;
}

/** 逐个测试候选后缀，找出这个站点真正可用的那个 */
async function testAllEndpoints() {
  const cfg = imageStore.currentConfig;
  if (!cfg) return;
  const list = candidateUrls.value;
  if (list.length === 0) return;

  for (const c of list) {
    endpointResults.value = {
      ...endpointResults.value,
      [c.suffix]: { ok: false, message: '测试中…', testing: true },
    };
    const r = await probeEndpoint(c.url, imageConfigForTest(cfg, c.url));
    endpointResults.value = {
      ...endpointResults.value,
      [c.suffix]: { ok: r.ok, message: r.message },
    };
  }
}

/** 只测某一条 */
async function testOneEndpoint(suffix: string, url: string) {
  const cfg = imageStore.currentConfig;
  if (!cfg) return;
  endpointResults.value = { ...endpointResults.value, [suffix]: { ok: false, message: '测试中…', testing: true } };
  const r = await probeEndpoint(url, imageConfigForTest(cfg, url));
  endpointResults.value = { ...endpointResults.value, [suffix]: { ok: r.ok, message: r.message } };
  if (r.ok) selectedSuffix.value = suffix;
}

/** 组装一个用于探测的引擎配置 */
function imageConfigForTest(cfg: any, endpoint?: string) {
  return {
    provider: cfg.provider,
    novelaiToken: cfg.novelaiToken,
    novelaiModel: cfg.novelaiModel,
    baseUrl: cfg.baseUrl,
    apiKey: cfg.apiKey,
    model: cfg.model,
    sampler: cfg.sampler,
    steps: 1,
    width: 832,
    height: 1216,
    cfgScale: cfg.cfgScale,
    negativePrompt: 'lowres',
    endpoint,
  };
}

/** 把选中的后缀保存到配置 */
function saveSelectedEndpoint() {
  const cfg = imageStore.currentConfig;
  if (!cfg) return;
  const base = baseUrlWithoutSuffix();
  if (!selectedSuffix.value || !base) return;
  patchImage({ endpoint: buildEndpointFromSuffix(base, selectedSuffix.value) });
}

/** 清除显式端点，回到自动探测 */
function clearEndpoint() {
  selectedSuffix.value = '';
  patchImage({ endpoint: '' });
}

/**
 * 导出全部数据。
 *
 * 原来只把 JSON 填进页面上的文本框，让用户自己全选复制 ——
 * 那是"查看"不是"导出"。备份是低频但关键的操作，
 * 用户的心智是"给我一个文件存起来"，所以这里**同时**：
 *   1. 存一份文件（.json，带时间戳，方便留多份）
 *   2. 文本框里也留一份（方便直接看内容 / 手工复制到别的地方）
 */
async function doExport() {
  const data = await exportAll();
  const json = JSON.stringify(data, null, 2);
  exportJson.value = json;

  try {
    const stamp = new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-');
    const blob = new Blob([json], { type: 'application/json;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `冒险模拟器-备份-${stamp}.json`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    // 立刻 revoke 有些浏览器会来不及下载，延后一点
    setTimeout(() => URL.revokeObjectURL(url), 4000);
  } catch {
    /* 下载失败不影响文本框里的内容，用户还能手动复制 */
  }
}

const importMode = ref<'merge' | 'replace'>('merge');

async function doImport() {
  const text = await (document.getElementById('import-file') as HTMLInputElement | null)?.files
    ?.item(0)?.text()
    .catch(() => null) || null;
  if (!text) {
    importResult.value = '请先选择文件';
    return;
  }
  try {
    const data = JSON.parse(text);
    if (importMode.value === 'replace') {
      const ok = window.confirm(
        '「完全替换」会先清空当前所有配置与资产，再写入文件内容。\n\n' +
          '落盘文件会留下一份备份（data/backups），但浏览器缓存会被清空。\n' +
          '确定要继续吗？',
      );
      if (!ok) {
        importResult.value = '已取消';
        return;
      }
    }
    const r = await importAll(data, importMode.value);
    await Promise.all([assets.load(), api.load()]);
    importResult.value =
      `导入成功：写入 ${r.imported} 项` +
      (importMode.value === 'merge' ? '（增补模式，原有配置未被删除）' : '（已完全替换）');
    setTimeout(() => (importResult.value = ''), 6000);
  } catch (e) {
    importResult.value = `导入失败：${e instanceof Error ? e.message : '文件解析错误'}`;
  }
}

function onFileChange() {
  doImport();
}

function toggleApiKey() {
  apiKeyVisible.value = !apiKeyVisible.value;
}
</script>

<template>
  <div class="settings-page" v-if="loaded">
    <div class="settings-head">
      <button v-if="activeSection" class="back-btn" @click="closeSection" title="返回设置">‹</button>
      <h2>{{ activeSection ? titleOf(activeSection) : '⚙️ 设置' }}</h2>
    </div>

    <!-- 卡片索引：一屏看全部功能分区 -->
    <SettingsGrid v-if="!activeSection" :cards="cards" @open="openSection" />

    <!-- API 配置 -->
    <section class="section" v-if="activeSection === 'api'">
      <div>
      <!--
        设置页按「运营商 → 模型」两层组织。
        
        用户的心智是：**一个中转站填一次地址和密钥，然后在它下面挑模型**。
        而数据里每条记录都带着自己的 baseUrl/apiKey（历史原因），
        所以这里把地址/密钥当作"这一家的属性"来编辑 ——
        改一次就同步到这家下面所有模型（见 store 的 updateProvider）。
        模型名 / 上下文窗口这些仍然是每个模型各自的。
      -->
      <div class="row">
        <div class="col">
          <label>
            运营商（接口地址）
            <span class="hint-inline">同一家只需填一次地址和密钥</span>
          </label>
          <select :value="api.currentConfigId" @change="onSwitchProvider(($event.target as HTMLSelectElement).value)">
            <option v-for="p in api.providers" :key="p.key" :value="p.models[0]?._uid">
              {{ p.note || p.baseUrl || '(未填地址)' }} · {{ p.models.length }} 个模型
            </option>
          </select>
        </div>
        <div class="col" style="flex: 0 0 auto; align-self: end;">
          <button @click="addProvider">＋ 新增运营商</button>
          <button class="danger" @click="removeCurrentProvider"
            :disabled="api.providers.length <= 1">删除这家</button>
        </div>
      </div>

      <div class="row" v-if="currentProvider" style="margin-top: 8px;">
        <div class="col" style="flex: 2;">
          <label>请求地址（Base URL）</label>
          <!--
            用 @input 而不是 @change：手机上填完地址直接点「拉取模型列表」时，
            Android WebView 常常不先失焦就派发点击，change 不触发 → 地址没存下来
            → 拉取报"请先填写请求地址"。@input 边打字边同步就没这个问题。
            @change 保留，用于失焦时立刻落盘（防抖的兜底）。
          -->
          <input type="text" :value="currentProvider.baseUrl"
            placeholder="https://api.your-provider.com/v1" @focus="onConnFocus" @blur="onConnBlur"
            @input="commitConn({ baseUrl: ($event.target as HTMLInputElement).value })"
            @change="commitConn({ baseUrl: ($event.target as HTMLInputElement).value }, true)" />
        </div>
        <div class="col" style="flex: 2;">
          <label>密钥（API Key）
            <button class="toggle-key" @click="toggleApiKey">
              {{ apiKeyVisible ? '隐藏' : '显示' }}</button>
          </label>
          <!--
            密钥用 :value + input/change 而不是 v-model：
            它要写回这一家的**所有**模型，直接绑 currentConfig.apiKey
            只会改到当前那一条（那正是被抱怨的"每个模型都要配一遍"）。
          -->
          <input :type="apiKeyVisible ? 'text' : 'password'" :value="currentProvider.apiKey"
            placeholder="sk-..." @focus="onConnFocus" @blur="onConnBlur"
            @input="commitConn({ apiKey: ($event.target as HTMLInputElement).value })"
            @change="commitConn({ apiKey: ($event.target as HTMLInputElement).value }, true)" />
        </div>
      </div>

      <div class="row" v-if="currentProvider" style="margin-top: 8px;">
        <div class="col" style="flex: 2;">
          <label>
            备注名称
            <span class="hint-inline">给这家起个好认的名字（可留空）</span>
          </label>
          <input type="text" :value="currentProvider.note" placeholder="例如：主力站"
            @input="commitNote(currentProvider!.key, ($event.target as HTMLInputElement).value)"
            @change="commitNote(currentProvider!.key, ($event.target as HTMLInputElement).value, true)" />
        </div>
      </div>

      <!-- 这一家下面的模型：可切、可加、可删 -->
      <div class="row" v-if="currentProvider" style="margin-top: 10px;">
        <div class="col">
          <label>
            当前使用的模型
            <span class="hint-inline">这家下面共 {{ currentProvider.models.length }} 个</span>
          </label>
          <select :value="api.currentConfigId" @change="(e) => api.setCurrent((e.target as HTMLSelectElement).value)">
            <option v-for="m in currentProvider.models" :key="m._uid" :value="m._uid">
              {{ m.model || '(未填模型名)' }}
            </option>
          </select>
        </div>
        <div class="col" style="flex: 0 0 auto; align-self: end;">
          <button @click="addModelToCurrentProvider">＋ 加模型</button>
          <button class="danger" @click="removeCurrentModel"
            :disabled="currentProvider.models.length <= 1">删除此模型</button>
        </div>
      </div>

      <div class="row" v-if="currentConfig" style="margin-top: 8px;">
        <div class="col" style="flex: 2;">
          <label>模型名称</label>
          <input type="text" v-model="currentConfig.model"
            placeholder="gpt-4o-mini" @change="api.save()" />
        </div>
        <div class="col" style="flex: 0 0 auto; align-self: end;">
          <button :disabled="fetchingLlmModels" @click="pullLlmModels">
            {{ fetchingLlmModels ? '拉取中…' : '📥 拉取模型列表' }}
          </button>
        </div>
        <div class="col" style="flex: 0 0 auto; align-self: end;">
          <button :disabled="testingLlm" @click="testLlmConnection">
            {{ testingLlm ? '测试中…' : '🔍 测试连接' }}
          </button>
        </div>
      </div>

      <div v-if="llmTestResult" class="test-result" :class="llmTestResult.ok ? 'ok' : 'bad'">
        {{ llmTestResult.ok ? '✅ ' : '❌ ' }}{{ llmTestResult.message }}
      </div>

      <!-- 拉取到的模型列表 -->
      <div v-if="showLlmModels" class="model-panel">
        <div class="model-panel-head">
          <span v-if="fetchingLlmModels">正在请求模型列表…</span>
          <span v-else-if="llmModelError" class="err">拉取失败</span>
          <span v-else>共 {{ llmModels.length }} 个可用模型，点击即可选用</span>
          <button class="mini-btn" @click="showLlmModels = false">关闭</button>
        </div>
        <div v-if="llmModelError" class="model-error">{{ llmModelError }}</div>
        <div v-else class="model-items">
          <button v-for="m in llmModels" :key="m" class="model-row"
            :class="{ active: currentConfig?.model === m }" @click="useLlmModel(m)">
            {{ m }}
          </button>
        </div>
      </div>

      <div class="row" v-if="currentConfig" style="margin-top: 8px;">
        <div class="col">
          <label>温度（Temperature）<span class="hint-inline">越高越随机，写作建议 0.8</span></label>
          <input type="number" step="0.1" v-model.number="currentConfig.temperature" @change="api.save()" />
        </div>
        <div class="col">
          <label>单次最大生成长度（Max Tokens）</label>
          <input type="number" v-model.number="currentConfig.maxTokens" @change="api.save()" />
        </div>
        <div class="col">
          <label>
            上下文窗口（Context Window）
            <span class="hint-inline">单位：token，现代模型一般 100 万</span>
          </label>
          <!--
            下面三个快捷值是"点一下就填好"，省得每次都手打一长串 0 ——
            用户明确说过不想每次都调。100 万是当前主流模型的量级
            （Gemini / Claude / GPT 新一代），也是新配置的默认值。
          -->
          <input type="number" v-model.number="currentConfig.contextWindow" @change="api.save()" />
          <div class="quick-nums">
            <button v-for="v in [1000000, 200000, 128000]" :key="v" class="mini"
              :class="{ on: currentConfig.contextWindow === v }"
              @click="currentConfig.contextWindow = v; api.save()">
              {{ v === 1000000 ? '100 万' : v === 200000 ? '20 万' : '12.8 万' }}
            </button>
          </div>
        </div>
        <div class="col">
          <label>历史对话预算<span class="hint-inline">单位：token</span></label>
          <input type="number" v-model.number="currentConfig.userLookbackTokens" @change="api.save()" />
        </div>
      </div>
      <!-- 已裁剪：原为 47 字的界面说明（只对开发者有意义） -->
      </div>
    </section>

    <!-- 生图配置 -->
    <section class="section" v-if="activeSection === 'image'">
      <div>
      <!-- ① 生图接口：只管怎么连上去。换中转/换模型只动这里，画风不受影响。 -->
      <div class="subsection-title" style="margin-top: 0;">① 生图接口（连接）</div>
      <div class="row">
        <div class="col">
          <label>当前接口</label>
          <select :value="imageStore.currentApiId"
            @change="(e) => imageStore.setCurrentApi((e.target as HTMLSelectElement).value)">
            <option v-for="c in imageStore.apiConfigs" :key="c._uid" :value="c._uid">{{ c.name }}</option>
          </select>
        </div>
        <div class="col" v-if="imageStore.currentApi">
          <label>接口名称</label>
          <!--
            只改**接口**这一份的名字。
            以前走 patchImage → updateConfig，而那里会把 name 同时写到
            风格那一份上 —— 于是"给接口改个名，风格也跟着变了"。
            接口和风格是两份独立配置，改名必须各改各的。
          -->
          <input type="text" :value="imageStore.currentApi.name"
            @change="imageStore.updateApi(imageStore.currentApi!._uid, { name: ($event.target as HTMLInputElement).value })" />
        </div>
        <div class="col" style="flex: 0 0 auto; align-self: end;">
          <button @click="imageStore.addApi()">新建接口</button>
          <button class="danger"
            @click="imageStore.currentApiId && imageStore.removeApi(imageStore.currentApiId)"
            :disabled="imageStore.apiConfigs.length <= 1">删除</button>
        </div>
      </div>

      <template v-if="imageStore.currentApi">
        <div class="row" style="margin-top: 8px;">
          <div class="col" style="flex: 2;">
            <label>接口类型</label>
            <select :value="imageStore.currentApi.provider"
              @change="patchImage({ provider: ($event.target as HTMLSelectElement).value })">
              <option v-for="(label, key) in imgProviderLabel" :key="key" :value="key">{{ label }}</option>
            </select>
          </div>
          <div class="col" v-if="imageStore.currentApi.provider === 'novelai'">
            <label>NovelAI 持久化 Token</label>
            <input type="password" :value="imageStore.currentApi.novelaiToken"
              @change="patchImage({ novelaiToken: ($event.target as HTMLInputElement).value })" />
          </div>
        </div>

        <div v-if="imageStore.currentApi.provider !== 'novelai'" class="row" style="margin-top: 8px;">
          <div class="col" style="flex: 2;">
            <label>请求地址（Base URL）<span class="hint-inline">中转站填到 /v1 即可，程序自动补全端点</span></label>
            <p class="hint" style="margin: 6px 0 0;">填好地址和密钥后，点「📥 拉取模型列表」自动读取可用模型。</p>
            <input type="text" :value="imageStore.currentApi.baseUrl"
              placeholder="https://api.example.com/v1"
              @change="patchImage({ baseUrl: ($event.target as HTMLInputElement).value })" />
          </div>
          <div class="col" style="flex: 2;">
            <label>密钥 / 中转令牌
              <button class="toggle-key" @click="imageKeyVisible = !imageKeyVisible">
                {{ imageKeyVisible ? '隐藏' : '显示' }}</button>
            </label>
            <input :type="imageKeyVisible ? 'text' : 'password'" :value="imageStore.currentApi.apiKey"
              @change="patchImage({ apiKey: ($event.target as HTMLInputElement).value })" />
          </div>
        </div>

        <div class="row" v-if="imageStore.currentApi.provider !== 'novelai'" style="margin-top: 8px;">
          <div class="col" style="flex: 2;">
            <label>模型名称</label>
            <input type="text" :value="imageStore.currentApi.model"
              placeholder="nai-diffusion-4-5-full"
              @change="patchImage({ model: ($event.target as HTMLInputElement).value, novelaiModel: ($event.target as HTMLInputElement).value })" />
          </div>
          <div class="col" style="flex: 2;">
            <label>
              中转令牌（可选）
              <span class="hint-inline">部分中转站用 token 而不是 Authorization，两个都填或只填一个都可以</span>
            </label>
            <input type="password" :value="imageStore.currentApi.novelaiToken"
              placeholder="留空则只用上面的密钥"
              @change="patchImage({ novelaiToken: ($event.target as HTMLInputElement).value })" />
          </div>
          <div class="col" style="flex: 0 0 auto; align-self: end;">
            <button :disabled="fetchingImgModels" @click="pullImageModels">
              {{ fetchingImgModels ? '拉取中…' : '📥 拉取模型列表' }}
            </button>
          </div>
          <div class="col" style="flex: 0 0 auto; align-self: end;">
            <button :disabled="testingImg" @click="testImgConnection">
              {{ testingImg ? '测试中…' : '🔍 测试连接' }}
            </button>
          </div>
        </div>

        <!--
          「帮我写提示词」用哪个对话模型。
          留空 = 跟随当前对话模型。可以指定别的模型：
          比如对话用擅长写小说的，提示词用更懂 Danbooru 标签的。
          这一项对**三条生图路径都生效**（弹窗 / 输入框旁边 / 自动配图）。
        -->
        <div class="row" style="margin-top: 8px;">
          <div class="col" style="flex: 2;">
            <label>
              写提示词用的大模型
              <span class="hint-inline">生图时把正文转成标签提示词的那个模型</span>
            </label>
            <!--
              生图写提示词用哪个模型。用统一的 ModelPicker（运营商 → 模型），
              和对话界面、生图弹窗的交互保持一致。
              注意：这是**持久化**设置，改动会存进生图接口配置。
            -->
            <ModelPicker
              :model-value="imageStore.currentApi.llmConfigId ?? ''"
              allow-follow
              label="提示词模型"
              @update:model-value="(v) => patchImage({ llmConfigId: v || undefined })"
            />
          </div>
        </div>
      </template>


        <!-- 自动配图 -->
        <div class="subsection-title">自动配图（由大模型自行选择插图位置）</div>
        <div class="auto-image-box">
          <label class="switch">
            <input type="checkbox" :checked="imageStore.autoImage.enabled"
              @change="imageStore.updateAutoImage({ enabled: ($event.target as HTMLInputElement).checked })" />
            <span>开启自动配图</span>
          </label>
          <p class="tip">
            开启后，大模型可以在正文里写 <code>[[IMG: 画面描述]]</code> 标记来自选配图位置。<br>
            <strong>隔离保证</strong>：标记在送进大模型前会被统一剥掉，图片以附件形式单独存放，
            都不会回到对话上下文里——所以配图既不占 token，也不会让模型"看见"自己的图。
          </p>
          <div class="row">
            <div class="col">
              <label>一次回复最多配几张<span class="hint-inline">防止刷额度</span></label>
              <input type="number" min="1" max="10" :value="imageStore.autoImage.maxPerReply"
                @change="imageStore.updateAutoImage({ maxPerReply: Number(($event.target as HTMLInputElement).value) })" />
            </div>
            <div class="col">
              <label>提示词处理</label>
              <select :value="imageStore.autoImage.skipOptimization ? 'direct' : 'llm'"
                @change="imageStore.updateAutoImage({ skipOptimization: ($event.target as HTMLSelectElement).value === 'direct' })">
                <option value="direct">直接用标记里的描述（省 token，推荐）</option>
                <option value="llm">再过一遍大模型优化（更精细，更慢更贵）</option>
              </select>
            </div>
          </div>

          <!--
            预设参考：生图时把预设内容也交给大模型。

            这里以前挂着一段四行的解释（"预设里多数是怎么写小说的指令……
            对画成什么样帮助有限……还可能把模型带偏……建议先试几条对比效果"）。
            用户明确说这段是多余的：预设用来破甲/改风格是常识，
            不需要解释，**把选择权给他就行**。现在只留一行说清代价。
          -->
          <div class="row" style="margin-top: 10px;">
            <div class="col">
              <label class="switch">
                <input type="checkbox" :checked="imageStore.autoImage.usePreset"
                  @change="imageStore.updateAutoImage({ usePreset: ($event.target as HTMLInputElement).checked })" />
                <span>生图时参考当前预设</span>
              </label>
              <p class="hint" style="margin: 4px 0 0;">
                开启后会多走一遍大模型，更慢、更耗 token。
              </p>
            </div>
          </div>
        </div>


        <!-- 生图端点自选 / 自测 -->
        <template v-if="imageStore.currentConfig">
        <div class="row" style="margin-top: 8px;">
          <button @click="openEndpointPanel">
            {{ showEndpointPanel ? '▲ 收起端点设置' : '🧭 端点设置（自己挑/自己测）' }}
          </button>
          <button v-if="imageStore.currentConfig.endpoint" @click="clearEndpoint">
            ↩ 改回自动探测
          </button>
        </div>

        <div v-if="showEndpointPanel" class="endpoint-panel">
          <p class="tip">
            请求地址填站点根或 <code>/v1</code> 即可，下面会把候选后缀拼出来。
            点「全部测一遍」会逐个真发一次最小请求，能返回图片的即为可用端点。
          </p>

          <div v-if="imageStore.currentConfig.endpoint" class="saved-endpoint">
            当前固定使用：<code>{{ imageStore.currentConfig.endpoint }}</code>
          </div>

          <div class="endpoint-list">
            <div v-for="c in candidateUrls" :key="c.suffix" class="endpoint-row"
              :class="{ chosen: selectedSuffix === c.suffix, good: endpointResults[c.suffix]?.ok }">
              <label class="endpoint-radio">
                <input type="radio" :value="c.suffix" v-model="selectedSuffix" />
                <span class="endpoint-label">{{ c.label }}</span>
              </label>
              <span class="endpoint-hint">{{ c.hint }}</span>
              <span class="endpoint-status">
                <span v-if="endpointResults[c.suffix]?.testing" class="dim">测试中…</span>
                <span v-else-if="endpointResults[c.suffix]" :class="endpointResults[c.suffix].ok ? 'ok-text' : 'bad-text'">
                  {{ endpointResults[c.suffix].ok ? '✅ 可用' : '❌ ' + endpointResults[c.suffix].message.slice(0, 60) }}
                </span>
              </span>
              <button class="mini-btn" :disabled="endpointResults[c.suffix]?.testing"
                @click="testOneEndpoint(c.suffix, c.url)">测这个</button>
            </div>
          </div>

          <div v-if="candidateUrls.length === 0" class="hint">请先在上面填写请求地址（Base URL）。</div>

          <div class="row" style="margin-top: 8px;" v-if="candidateUrls.length">
            <button :disabled="!candidateUrls.length" @click="testAllEndpoints">🧪 全部测一遍</button>
            <button class="primary" :disabled="!selectedSuffix" @click="saveSelectedEndpoint">
              💾 保存选中的端点
            </button>
          </div>
        </div>

        <!-- 已裁剪：原为 82 字的界面说明（只对开发者有意义） -->

        <!-- NovelAI 官方模型快选 -->
        <div v-if="imageStore.currentConfig.provider === 'novelai'" class="chip-row" style="margin-top: 8px;">
          <button v-for="m in NOVELAI_MODELS" :key="m.value" class="chip"
            :class="{ active: imageStore.currentConfig.novelaiModel === m.value }"
            @click="patchImage({ novelaiModel: m.value, model: m.value })">
            {{ m.label }}
          </button>
        </div>

        <!-- 生图模型列表 -->
        <div v-if="showImgModels" class="model-panel">
          <div class="model-panel-head">
            <span v-if="fetchingImgModels">正在请求模型列表…</span>
            <span v-else-if="imgModelError" class="err">拉取失败</span>
            <span v-else>共 {{ imgModels.length }} 个可用模型，点击即可选用</span>
            <button class="mini-btn" @click="showImgModels = false">关闭</button>
          </div>
          <div v-if="imgModelError" class="model-error">{{ imgModelError }}</div>
          <div v-else class="model-items">
            <button v-for="m in imgModels" :key="m" class="model-row"
              :class="{ active: imageStore.currentConfig.model === m }" @click="useImageModel(m)">
              {{ m }}
            </button>
          </div>
        </div>

      </template>

      <!-- 已裁剪：原为 135 字的界面说明（只对开发者有意义） -->
      </div>
    </section>

<!--
  出图风格：和「生图配置」是**两个独立的子项**。

  为什么要拆：接口是"怎么连上去"（地址/密钥/模型），
  风格是"出什么画风"（画师串/反向词/采样器/参数）。
  混在一页里，找接口设置要在风格卡片中间翻；调风格又怕手滑改到地址。
  拆开后各自干净，而且风格可以跨接口复用这一点也说得更清楚。
-->
<section class="section" v-if="activeSection === 'style'">
  <div>
      <!-- ② 出图风格：画师串 / 反向提示词 / 采样器 / 尺寸…可以跨不同接口复用。 -->
      <div class="subsection-title" style="margin-top: 16px;">② 出图风格（画师串 / 反向提示词 / 参数）</div>

      <!--
        风格列表做成**正方块卡片、一排三个**。

        为什么不用下拉框：
          风格是"看长相选"的东西（清新 / 暗黑 / 赛璐璐…），
          下拉框只有一行文字，名字起得随意点就分不清谁是谁。
          方块卡片能挂一张缩略图，扫一眼就知道是哪套。
        点卡片 = 选它（马上生效），长按/点右上角小图 = 换参考图。
      -->
      <div class="style-grid">
        <div v-for="st in imageStore.styleConfigs" :key="st._uid" class="style-card"
          :class="{ active: st._uid === imageStore.currentStyleId }"
          @click="imageStore.setCurrentStyle(st._uid)">
          <div class="sc-thumb" :style="st.thumb ? { backgroundImage: `url(${st.thumb})` } : {}">
            <span v-if="!st.thumb" class="sc-thumb-empty">🎨</span>
            <!-- 换风格参考图：点这个小按钮，不触发"选中风格" -->
            <button class="sc-pick"
              title="放一张风格参考图 —— 只是让你直观看这套画风的效果，不会影响出图方式"
              @click.stop="pickStyleThumb(st._uid)">📷</button>
            <span v-if="st.thumb" class="sc-clear" title="移除风格参考图"
              @click.stop="imageStore.updateStyle(st._uid, { thumb: '' })">✕</span>
          </div>
          <div class="sc-name">{{ st.name || '(未命名)' }}</div>
        </div>
        <button class="style-card style-card-add" @click="imageStore.addStyle()">
          <span class="sc-plus">＋</span>
          <span class="sc-name">新建风格</span>
        </button>
      </div>

      <div class="row" style="margin-top: 10px;">
        <div class="col" v-if="imageStore.currentStyle">
          <label>风格名称</label>
          <!-- 只改**风格**这一份的名字（理由同上面的「接口名称」） -->
          <input type="text" :value="imageStore.currentStyle.name"
            @change="imageStore.updateStyle(imageStore.currentStyle!._uid, { name: ($event.target as HTMLInputElement).value })" />
        </div>
        <div class="col" style="flex: 0 0 auto; align-self: end;">
          <button class="danger"
            @click="imageStore.currentStyleId && imageStore.removeStyle(imageStore.currentStyleId)"
            :disabled="imageStore.styleConfigs.length <= 1">删除这套风格</button>
        </div>
      </div>
      <!-- 已裁剪：原为 83 字的界面说明（只对开发者有意义） -->
      <!-- 换缩略图用的隐藏文件选择框 -->
      <input ref="styleThumbInput" type="file" accept="image/*" style="display: none"
        @change="onStyleThumbPicked" />

        <div v-if="imgTestResult" class="test-result" :class="imgTestResult.ok ? 'ok' : 'bad'">
          {{ imgTestResult.ok ? '✅ ' : '❌ ' }}{{ imgTestResult.message }}
          <div v-if="imgTestResult.endpoint" class="test-endpoint">
            实际可用端点：{{ imgTestResult.endpoint }}
          </div>
        </div>

        <!-- 生图参数 -->
        <div class="subsection-title">生图参数（与 NovelAI 官方请求体一致）</div>
        <!--
          currentConfig 是 computed，可能为空（一条接口/风格都没建时）。
          这里必须 v-if —— 之前它在 image section 里，
          外面那层 <template v-if="imageStore.currentConfig"> 兜住了；
          拆到独立 section 后这个守卫没了，会把 undefined 传进组件。
        -->
        <ImageParamsPanel v-if="imageStore.currentConfig" :config="imageStore.currentConfig" />
      </div>
    </section>

    <!-- 图片库 -->
    <section class="section" v-if="activeSection === 'library'">
      <!--
        复用「资产 → 图片库」那个面板，不再单独写一份。

        以前这里是第三份图片库实现（资产页一份、独立页一份、这里一份），
        能力对不齐：这里只有"清空/下载/删除"，没有占用统计和「清理无用缓存」
        —— 而缓存恰恰是最需要处理的（历史 bug 会让它涨到几百 MB）。
        换成一个组件后，一处改进三处都受益。
      -->
      <GalleryPanel />
    </section>

    <!-- 资产管理已移到左侧导航的「资产」页，这里只放一个入口 -->
    <!--
      「资产与世界书」子项已删。

      原因：它整个 section 里只有一个「前往资产页」跳转按钮，而资产本来就是
      底部标签栏里的独立一级页面。设置里再放一个中转纯属多余 ——
      用户看到它会以为这里能管资产，点进去发现什么也没有。
    -->

    <!-- 标签库 -->
    <section class="section" v-if="activeSection === 'tags'">
      <div>
        <label class="switch">
          <input type="checkbox" v-model="storageSettings.tagReferenceEnabled"
            @change="storageSettings.save()" />
          <span>优化提示词时附上标准标签参考</span>
        </label>
        <p class="tip">
          作用是给模型一份「标准写法锚点」：让它写 <code>silver hair</code> 而不是
          <code>silver colored hair</code>，写 <code>1girl</code> 而不是 <code>one girl</code>。
          NovelAI 对 Danbooru 标签认得很死，写法不对就等于没写。<br>
          参考块会追加在提示词优化的 system 末尾，按你勾选的分类组装，
          总量控制在几百 token（不会像完整 Danbooru 那样几万条把上下文冲掉）。
        </p>

        <div class="row" style="margin-top: 8px;">
          <div class="col">
            <label>每个分类取多少个标签</label>
            <input type="number" min="8" max="60" step="4" v-model.number="storageSettings.tagPerCategory"
              @change="storageSettings.save()" />
          </div>
          <div class="col" style="flex: 0 0 auto; align-self: end;">
            <button @click="storageSettings.tagCategories = TAG_CATEGORIES.map(c => c.id); storageSettings.save()">
              全选
            </button>
            <button @click="storageSettings.tagCategories = []; storageSettings.save()">全不选</button>
          </div>
        </div>

        <div class="tag-cat-grid">
          <label v-for="c in TAG_CATEGORIES" :key="c.id" class="tag-cat"
            :class="{ on: storageSettings.tagCategories.includes(c.id) }">
            <input type="checkbox" :checked="storageSettings.tagCategories.includes(c.id)"
              @change="storageSettings.toggleTagCategory(c.id, ($event.target as HTMLInputElement).checked)" />
            <span class="tc-name">{{ c.name }}</span>
            <span class="tc-count">{{ c.tags.length }}</span>
          </label>
        </div>

        <!-- 预览组装出来的参考块 -->
        <div class="row" style="margin-top: 8px;">
          <button @click="showTagPreview = !showTagPreview">
            {{ showTagPreview ? '隐藏' : '预览' }}将要发给模型的标签参考
          </button>
        </div>
        <pre v-if="showTagPreview" class="tag-preview">{{ tagPreviewText }}</pre>

        <!-- 导入完整标签库 -->
        <div class="subsection-title">导入完整标签库（可选）</div>
        <p class="tip">
          想要 <strong>完整 Danbooru 标签</strong>的话，可以从社区插件仓库里把标签 JSON 导出后导进来
          （例如 chami_tavern-scene-plugin 的 <code>data/static-tags*.json</code>，
          那是 1~3.5MB 的整份转储）。导入后可以在这里检索，检索到的标签会追加到参考块里。<br>
          说明：我没有把那份转储内置进来 —— 它合起来 8MB 以上、需要 SQLite 才能查，
          对「给模型一份参考」这个目的来说过重了，按需导入更合适。
        </p>
        <div class="row">
          <input type="file" accept=".json,application/json" @change="onTagLibFile" />
          <button v-if="storageSettings.importedTagLib" class="danger" @click="storageSettings.setImportedTagLib(null)">
            移除已导入的标签库
          </button>
        </div>
        <div v-if="tagLibMsg" class="test-result" :class="tagLibOk ? 'ok' : 'bad'">{{ tagLibMsg }}</div>

        <div v-if="storageSettings.importedTagLib" class="row" style="margin-top: 8px;">
          <div class="col">
            <label>
              在标签库里检索（中英文都行）
              <span class="hint-inline">
                已导入 {{ storageSettings.importedTagLib.tags.length }} 个标签
              </span>
            </label>
            <input type="text" v-model="tagQuery" placeholder="例如：银发 / silver / 图书馆" />
          </div>
        </div>
        <div v-if="tagSearchResults.length" class="tag-search-results">
          <span v-for="t in tagSearchResults" :key="t.tag" class="tag-chip" :title="t.zh">
            {{ t.tag }}<em v-if="t.zh"> · {{ t.zh }}</em>
          </span>
        </div>
      </div>
    </section>

    <!--
      显示与上下文。

      为什么单开一项（而不是塞在「生图配置」里）：
        这一项管的都是"**你在界面上看到什么 / 交给模型什么**"，
        跟"怎么连生图接口、画风怎么调"完全是两回事。
        早先它们被放在生图配置里，用户去找"隐藏思维链"或
        "导出用原文"时根本想不到要去生图接口里翻 —— 归类错了。
      「生图配置」只留真生图的东西：接口 / 风格 / 是否带预设 / 提示词模板 / 参数。
    -->
    <section class="section" v-if="activeSection === 'display'">
      <div>
        <div class="subsection-title" style="margin-top: 0;">思维链（推理过程）</div>
        <!-- 已裁剪：原为 135 字的界面说明（只对开发者有意义） -->
        <div class="row">
          <div class="col">
            <label>界面是否隐藏思维链</label>
            <select :value="storageSettings.hideReasoning ? 'hide' : 'show'"
              @change="storageSettings.hideReasoning = ($event.target as HTMLSelectElement).value === 'hide'; storageSettings.save()">
              <option value="hide">隐藏（推荐）</option>
              <option value="show">显示</option>
            </select>
          </div>
          <div class="col">
            <label>分界标记<span class="hint-inline">可留空</span></label>
            <input type="text" :value="storageSettings.reasoningCutMarker"
              placeholder="例如 ---正文--- 或 思考完毕"
              @change="storageSettings.reasoningCutMarker = ($event.target as HTMLInputElement).value; storageSettings.save()" />
          </div>
        </div>

        <!--
          正则的作用范围 —— 两个"原文 / 处理过"的选择。

          为什么要给这两个开关：
            正则有两类作用 —— 改语义的（提示词类）和纯美化的（只改显示）。
            平时"美化只影响显示、不影响上下文"是对的（见 markdownOnly 语义）。
            但总有人需要反过来：
              · 想让模型拿到**完全没被动过**的原始文本
                （排查"是正则改坏了还是模型本来就胡说"，或自己那套正则改过头了）；
              · 想导出的记录**保留标记**，换到别的平台继续用
                （那边可能认得 <details> / 状态栏标签，被替换掉反而丢信息）。
            所以各留一个开关，默认都关（保持一直以来的行为）。
        -->
        <div class="subsection-title" style="margin-top: 14px;">正则作用范围</div>
        <div class="row">
          <div class="col">
            <label>
              发给大模型的上下文
              <span class="hint-inline">原文 = 完全跳过正则</span>
            </label>
            <select :value="storageSettings.llmContextRaw ? 'raw' : 'processed'"
              @change="storageSettings.llmContextRaw = ($event.target as HTMLSelectElement).value === 'raw'; storageSettings.save()">
              <option value="processed">跑过正则的文本（推荐）</option>
              <option value="raw">原文（不跑正则）</option>
            </select>
          </div>
          <div class="col">
            <label>
              导出聊天记录
              <span class="hint-inline">原文 = 保留标签，方便换平台</span>
            </label>
            <select :value="storageSettings.exportRaw ? 'raw' : 'processed'"
              @change="storageSettings.exportRaw = ($event.target as HTMLSelectElement).value === 'raw'; storageSettings.save()">
              <option value="processed">你看到的样子（推荐）</option>
              <option value="raw">原文（含标记）</option>
            </select>
          </div>
        </div>
        <!-- 已裁剪：原为 109 字的界面说明（只对开发者有意义） -->
      </div>
    </section>

    <!-- 界面外观 -->
    <section class="section" v-if="activeSection === 'appearance'">
      <div>
        <!--
          按键音效。

          默认开着但音量压得很低（0.6 × 0.045 增益）——
          音效的作用是"确认点到了"，不是让人听见。
          不喜欢的人可以在这里直接关掉。
        -->
        <div class="subsection-title">按键音效</div>
        <label class="switch">
          <input type="checkbox" :checked="soundEnabled" @change="setSoundEnabled(($event.target as HTMLInputElement).checked)" />
          <span>点击按钮时播放提示音</span>
        </label>
        <div v-if="soundEnabled" class="row" style="margin-top: 8px;">
          <div class="col">
            <label>音量<em class="hint-inline">{{ Math.round(soundVolume * 100) }}%</em></label>
            <input type="range" min="0" max="100" :value="Math.round(soundVolume * 100)"
              @input="setSoundVolume(Number(($event.target as HTMLInputElement).value) / 100)" />
          </div>
          <div class="col" style="flex: 0 0 auto;">
            <button class="mini" @click="previewSound">试听</button>
          </div>
        </div>
        <!--
          整套外观预设：一次换掉「背景 + 主色 + 面板透明度」。
          
          为什么要这个：光换主色看不出多大差别，真正决定观感的是
          背景（明暗/材质）和面板透明度的组合。用户要的是"换个风格"，
          而不是自己去凑这三个值 —— 那是调参，不是选风格。
          
          想细调的人下面照样能一项项改（预设只是把三个值一次填进去，
          填完还能继续微调，不会被锁死）。
        -->
        <label class="block-label">整套外观</label>
        <div class="theme-grid">
          <button
            v-for="t in THEME_PRESETS"
            :key="t.id"
            class="theme-card"
            :class="{ active: appearance.themeId === t.id, 'light-preview': t.background === 'paper' }"
            :style="{ background: t.preview }"
            :title="t.name + ' — ' + t.desc"
            @click="appearance.applyTheme(t.id)"
          >
            <span class="theme-name">{{ t.name }}</span>
            <span class="theme-desc">{{ t.desc }}</span>
          </button>
        </div>
        <p class="tip">
          点一下即换整套配色与背景；下面的背景 / 主色 / 透明度仍可继续单独微调。
        </p>

        <label class="block-label" style="margin-top: 12px;">背景（细调）</label>
        <div class="bg-grid">
          <button v-for="p in BACKGROUND_PRESETS" :key="p.id" class="bg-swatch"
            :class="{ active: appearance.background === p.id }"
            :style="{ background: p.preview }"
            :title="p.name"
            @click="appearance.background = p.id; appearance.save()">
            <span class="bg-name">{{ p.name }}</span>
          </button>
          <button v-if="appearance.customImage" class="bg-swatch"
            :class="{ active: appearance.background === 'custom' }"
            :style="{ backgroundImage: `url(${appearance.customImage})`, backgroundSize: 'cover' }"
            @click="appearance.background = 'custom'; appearance.save()">
            <span class="bg-name">自定义</span>
          </button>
        </div>

        <div class="row" style="margin-top: 8px;">
          <div class="col">
            <label>自定义背景图（PNG / JPG / WebP）</label>
            <input type="file" accept="image/*" @change="onBackgroundFile" />
          </div>
          <div class="col" style="flex: 0 0 auto; align-self: end;">
            <button v-if="appearance.customImage" class="danger" @click="appearance.clearCustomImage()">
              移除自定义背景
            </button>
          </div>
        </div>
        <p class="tip">
          背景图会自动缩到长边 1920 后存入本地，避免把原图整张塞进去。
          想用自己收藏的插画就选这里。
        </p>

        <div class="row" style="margin-top: 10px;">
          <div class="col">
            <label>背景压暗（自定义图时生效）· {{ Math.round(appearance.customImageDim * 100) }}%</label>
            <input type="range" min="0" max="0.85" step="0.05"
              v-model.number="appearance.customImageDim" @change="appearance.save()" />
          </div>
          <div class="col">
            <label>面板不透明度 · {{ Math.round(appearance.panelOpacity * 100) }}%</label>
            <input type="range" min="0.5" max="1" step="0.02"
              v-model.number="appearance.panelOpacity" @change="appearance.save()" />
          </div>
          <div class="col">
            <label>内容宽度 · {{ appearance.contentWidth }}px</label>
            <input type="range" min="900" max="1800" step="50"
              v-model.number="appearance.contentWidth" @change="appearance.save()" />
          </div>
        </div>

        <label class="block-label" style="margin-top: 10px;">主色</label>
        <div class="chip-row">
          <button v-for="a in ACCENT_PRESETS" :key="a.id" class="accent-chip"
            :class="{ active: appearance.accent === a.value }"
            :style="{ background: a.value }" :title="a.name"
            @click="appearance.accent = a.value; appearance.save()"></button>
        </div>
      </div>
    </section>

    <!-- 图片存放位置 -->
    <section class="section" v-if="activeSection === 'gh'">
      <div>
        <div class="row">
          <div class="col">
            <label>生成的图片存到哪里</label>
            <select :value="storageSettings.backend"
              @change="storageSettings.setBackend(($event.target as HTMLSelectElement).value as any)">
              <option value="browser">只存本地（浏览器 + 落盘文件）</option>
              <option value="github">存到我的 GitHub 仓库</option>
            </select>
          </div>
        </div>

        <template v-if="storageSettings.backend === 'github'">
          <div class="row" style="margin-top: 8px;">
            <div class="col">
              <label>当前仓库</label>
              <select :value="storageSettings.currentGithubId"
                @change="(e) => storageSettings.setCurrentGithub((e.target as HTMLSelectElement).value)">
                <option v-for="c in storageSettings.githubConfigs" :key="c._uid" :value="c._uid">
                  {{ c.name }}{{ c.owner && c.repo ? `（${c.owner}/${c.repo}）` : '（未填写）' }}
                </option>
              </select>
            </div>
            <div class="col" v-if="storageSettings.currentGithub()">
              <label>备注名称</label>
              <input type="text" v-model="storageSettings.currentGithub()!.name"
                @change="storageSettings.save()" />
            </div>
            <div class="col" style="flex: 0 0 auto; align-self: end;">
              <button @click="storageSettings.addGithub()">新建仓库配置</button>
              <button class="danger"
                :disabled="storageSettings.githubConfigs.length <= 1"
                @click="storageSettings.currentGithubId && storageSettings.removeGithub(storageSettings.currentGithubId)">
                删除
              </button>
            </div>
          </div>

          <template v-if="storageSettings.currentGithub()">
            <div class="row" style="margin-top: 8px;">
              <div class="col">
                <label>用户名 / 组织名<span class="hint-inline">owner</span></label>
                <input type="text" v-model="storageSettings.currentGithub()!.owner"
                  placeholder="your-name"
                  @change="storageSettings.save()" />
              </div>
              <div class="col">
                <label>仓库名<span class="hint-inline">repo</span></label>
                <input type="text" v-model="storageSettings.currentGithub()!.repo"
                  placeholder="my-images"
                  @change="storageSettings.save()" />
              </div>
              <div class="col">
                <label>分支<span class="hint-inline">默认 main</span></label>
                <input type="text" v-model="storageSettings.currentGithub()!.branch"
                  placeholder="main"
                  @change="storageSettings.save()" />
              </div>
            </div>

            <div class="row" style="margin-top: 8px;">
              <div class="col" style="flex: 2;">
                <label>
                  访问令牌（PAT）
                  <button class="toggle-key" @click="imageKeyVisible = !imageKeyVisible">
                    {{ imageKeyVisible ? '隐藏' : '显示' }}
                  </button>
                </label>
                <input :type="imageKeyVisible ? 'text' : 'password'"
                  v-model="storageSettings.currentGithub()!.token"
                  placeholder="github_pat_..."
                  @change="storageSettings.save()" />
              </div>
              <div class="col" style="flex: 0 0 auto; align-self: end;">
                <button :disabled="ghTesting" @click="testGithub">
                  {{ ghTesting ? '测试中…' : '🔍 测试连接' }}
                </button>
              </div>
            </div>

            <div v-if="ghTestResult" class="test-result" :class="ghTestResult.ok ? 'ok' : 'bad'">
              {{ ghTestResult.ok ? '✅ ' : '❌ ' }}{{ ghTestResult.message }}
            </div>

            <div class="row" style="margin-top: 8px;">
              <div class="col">
                <label>仓库内目录</label>
                <input type="text" v-model="storageSettings.currentGithub()!.path"
                  placeholder="images"
                  @change="storageSettings.save()" />
              </div>
              <div class="col">
                <label>图片格式</label>
                <select v-model="storageSettings.currentGithub()!.format"
                  @change="storageSettings.save()">
                  <option value="webp">WebP（体积最小，推荐）</option>
                  <option value="jpeg">JPEG（兼容最好）</option>
                  <option value="png">PNG（无损，体积大）</option>
                </select>
              </div>
              <div class="col">
                <label>
                  压缩上限（KB）
                  <span class="hint-inline">GitHub 单文件约 1MB，建议 700~900</span>
                </label>
                <input type="number" min="64" max="1000" step="50"
                  v-model.number="storageSettings.currentGithub()!.maxKB"
                  @change="storageSettings.save()" />
              </div>
            </div>

            <div class="switch-row" style="margin-top: 8px;">
              <label class="switch">
                <input type="checkbox" v-model="storageSettings.currentGithub()!.enabled"
                  @change="storageSettings.save()" />
                <span>启用这个仓库（关闭后图片只存本地）</span>
              </label>
              <label class="switch" title="远端链接在部分网络下解析不了，只存链接会变成占位符，所以这一项固定开启">
                <input type="checkbox" checked disabled />
                <span>
                  本地始终留一份
                  <em class="hint-inline">（固定开启）</em>
                </span>
              </label>
              <label class="switch">
                <input type="checkbox" v-model="storageSettings.fallbackToLocal"
                  @change="storageSettings.save()" />
                <span>上传失败时自动回退到本地存储</span>
              </label>
            </div>

            <p class="tip">
              用「用户名 + 仓库名 + 细粒度令牌」就能访问，
              在 GitHub → Settings → Developer settings → Personal access tokens →
              Fine-grained tokens 生成，Repository access 选中该仓库，
              权限勾选 <code>Contents: Read and write</code> 即可。<br>
              <strong>提醒</strong>：公开仓库里任何人只要知道链接就能看到这些图；
              想私密就把仓库设为私有（程序会自动带令牌取图，但显示会稍慢一点）。
              令牌会明文存在本地配置文件里，别把这个项目目录提交到公开仓库。
            </p>
          </template>

          <div v-if="!storageSettings.currentGithub()?.enabled" class="warn-msg" style="margin-top: 8px;">
            ⚠ 当前仓库未启用，图片仍会存到本地。
          </div>
        </template>
      </div>
    </section>

    <!-- 本地持久化 -->
    <section class="section" v-if="activeSection === 'persist'">
      <!-- 顶部标题栏已说明这是哪一页，这里只留状态徽章（模式信息有用，不能丢） -->
      <div class="status-line">
        <span class="mode-badge" :class="persistStatus.mode">{{ persistModeLabel }}</span>
      </div>
      <div>
        <div v-if="persistStatus.mode === 'file'" class="persist-grid">
          <div class="persist-item">
            <span class="k">数据文件</span>
            <span class="v mono">{{ persistStatus.file }}</span>
          </div>
          <div class="persist-item">
            <span class="k">文件大小</span>
            <span class="v">{{ formatBytes(persistStatus.bytes) }}</span>
          </div>
          <div class="persist-item">
            <span class="k">最近写入</span>
            <span class="v">{{ formatTime(persistStatus.lastSavedAt || persistStatus.mtime) }}</span>
          </div>
          <div class="persist-item">
            <span class="k">备份份数</span>
            <span class="v">{{ persistStatus.backupCount ?? 0 }} 份</span>
          </div>
          <div class="persist-item">
            <span class="k">当前访问地址</span>
            <span class="v mono">{{ persistStatus.origin }}</span>
          </div>
          <div class="persist-item">
            <span class="k">该地址已落盘</span>
            <span class="v">{{ persistStatus.originKeys ?? 0 }} 项</span>
          </div>
        </div>

        <details v-if="origins.length > 1" class="preview-details" style="margin-top: 8px;">
          <summary>其他访问地址的数据（{{ origins.length }} 个来源）</summary>
          <!-- 已裁剪：原为 70 字的界面说明（只对开发者有意义） -->
          <div class="backup-list">
            <div v-for="o in origins" :key="o.origin" class="backup-row">
              <span class="mono">{{ o.origin }}</span>
              <span class="dim">{{ o.keys }} 项 · {{ formatTime(o.updatedAt) }}</span>
            </div>
          </div>
        </details>

        <div v-else-if="persistStatus.mode === 'browser'" class="warn-msg">
          ⚠ 当前没有检测到本地落盘接口，配置只存在浏览器里。
          用 <code>npm run dev</code> / <code>start.cmd</code> 启动的开发服务器才会提供落盘能力；
          纯静态部署（含手机 PWA）读不到文件。
        </div>

        <p v-if="persistStatus.error" class="hint error-text">{{ persistStatus.error }}</p>

        <div class="row" style="margin-top: 8px;">
          <button :disabled="savingNow || persistStatus.mode !== 'file'" @click="saveToFileNow">
            {{ savingNow ? '写入中…' : '💾 立即写入本地文件' }}
          </button>
          <button @click="refreshBackups">🔄 刷新备份列表</button>
        </div>

        <!-- 操作结果：成功/失败都说清楚，别让用户猜（点了没反应最伤体验） -->
        <p v-if="saveMsg" class="op-msg" :class="{ bad: !saveMsgOk }">{{ saveMsg }}</p>

        <!-- 已裁剪：原为 91 字的界面说明（只对开发者有意义） -->

        <details v-if="backups.length" class="preview-details">
          <summary>查看备份列表（{{ backups.length }} 份）</summary>
          <div class="backup-list">
            <div v-for="b in backups" :key="b.name" class="backup-row">
              <span class="mono">{{ b.name }}</span>
              <span class="dim">{{ formatBytes(b.bytes) }} · {{ formatTime(b.mtime) }}</span>
            </div>
          </div>
        </details>
      </div>
    </section>

    <!-- 导入导出 -->
    <section class="section" v-if="activeSection === 'data'">
      <div>
        <div class="row">
          <button @click="doExport">导出所有数据为 JSON</button>
        </div>
        <textarea v-if="exportJson" v-model="exportJson" rows="8" class="export-textarea"
          readonly></textarea>
        <div class="row" style="margin-top: 8px;">
          <input type="file" id="import-file" accept=".json,application/json" @change="onFileChange" />
          <span v-if="importResult" class="import-result">{{ importResult }}</span>
        </div>
        <div class="row" style="margin-top: 8px;">
          <label class="radio-row">
            <input type="radio" value="merge" v-model="importMode" />
            <span>增补（推荐）· 只覆盖文件里有的项，不动其他配置</span>
          </label>
          <label class="radio-row">
            <input type="radio" value="replace" v-model="importMode" />
            <span class="danger-text">完全替换 · 会先清空现有配置（有确认与备份）</span>
          </label>
        </div>
        <!-- 已裁剪：原为 59 字的界面说明（只对开发者有意义） -->
      </div>
    </section>

    <!-- 关于 -->
    <section class="section" v-if="activeSection === 'about'">
      <div>
        <p>冒险模拟器</p>
        <!-- 已裁剪：原为 78 字的界面说明（只对开发者有意义） -->
        <!-- 已裁剪：原为 49 字的界面说明（只对开发者有意义） -->
        <!--
          手机上的界面是被 Service Worker 缓存后渲染的，偶尔会出现
          "改了却还是老样子"。这里给一个人工兜底入口。
        -->
        <div class="row" style="margin-top: 10px;">
          <div class="col" style="flex: 0 0 auto;">
            <button :disabled="updating" @click="doCheckUpdate">
              {{ updating ? '检查中…' : '🔄 检查更新' }}
            </button>
          </div>
          <div class="col">
            <!-- 已裁剪：原为 59 字的界面说明（只对开发者有意义） -->
          </div>
        </div>
      </div>
    </section>
  </div>

  <div v-else class="loading">加载中…</div>
</template>

<style scoped>
.settings-page {
  padding: 24px;
  max-width: 800px;
  margin: 0 auto;
}
.settings-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 20px;
}
.head-tools {
  display: flex;
  gap: 6px;
}
.settings-page h2 {
  margin: 0;
  font-size: 20px;
}
.section {
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 16px;
  margin-bottom: 16px;
}
.section h3 {
  margin: 0 0 12px;
  font-size: 14px;
  color: var(--accent);
}
/* 操作反馈文字：成功绿、失败红，几秒后自动消失 */
/*
 * 快捷数值按钮（上下文窗口那几个常用值）。
 * 做成一排小胶囊，点一下就填好 —— 省得手打一长串 0。
 */
.quick-nums {
  display: flex;
  gap: 6px;
  margin-top: 6px;
  flex-wrap: wrap;
}
.quick-nums .mini {
  font-size: 11px;
  padding: 4px 10px;
  border-radius: var(--radius-pill);
}
.quick-nums .mini.on {
  border-color: var(--accent);
  color: var(--accent);
  font-weight: 700;
}

.op-msg {
  margin: 8px 0 0;
  padding: 7px 12px;
  border-radius: var(--radius-sm);
  font-size: 12px;
  background: color-mix(in srgb, var(--accent) 12%, var(--panel-2));
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 22%, transparent);
}
.op-msg.bad {
  background: color-mix(in srgb, var(--danger) 12%, var(--panel-2));
  color: var(--danger);
  border-color: color-mix(in srgb, var(--danger) 26%, transparent);
}

.status-line {
  margin-bottom: 10px;
}
.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  cursor: pointer;
  user-select: none;
  padding: 2px 0;
}
.section-head:hover {
  color: var(--accent-2);
}
.section-head .chev {
  font-size: 10px;
  color: var(--text-dim);
}
/*
 * 风格方块卡片：一排三个。
 *
 * 用 grid 而不是 flex：三列等宽、间距一致，
 * 最后一行的"＋新建风格"也能自然对齐到格子里。
 */
.style-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  margin: 8px 0 4px;
}
.style-card {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 6px;
  /* 关键：重置全局按钮样式 —— 卡片本身是 div，但"新建"那张是 button */
  background: var(--surface-1, var(--panel-2));
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  cursor: pointer;
  min-width: 0;
  text-align: center;
  box-shadow: none;
  transition: border-color 0.15s, box-shadow 0.15s, transform 0.08s;
}
.style-card:hover {
  border-color: var(--accent);
}
.style-card:active {
  transform: scale(0.97);
}
/* 选中的那套：主色描边 + 轻微高亮，一眼看出当前用哪套 */
.style-card.active {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--accent) 35%, transparent);
}
/* 正方形缩略图区 */
.sc-thumb {
  position: relative;
  width: 100%;
  aspect-ratio: 1 / 1;
  border-radius: var(--radius);
  background-color: var(--panel-2);
  background-size: cover;
  background-position: center;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}
.sc-thumb-empty {
  font-size: 26px;
  opacity: 0.35;
}
.sc-pick,
.sc-clear {
  position: absolute;
  top: 4px;
  min-width: 0;
  min-height: 0;
  width: 24px;
  height: 24px;
  padding: 0;
  font-size: 12px;
  line-height: 1;
  border-radius: 50%;
  opacity: 0.9;
}
.sc-pick {
  left: 4px;
}
.sc-clear {
  right: 4px;
  background: rgba(0, 0, 0, 0.5);
  color: #fff;
  border-color: transparent;
}
.sc-name {
  font-size: 11.5px;
  line-height: 1.25;
  color: var(--text);
  /* 名字给两行：用户给风格起名常常带后缀（"清新-日常"），
     只给一行会被截成"清新-日…"，认不出是哪套。 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-word;
}
.style-card.active .sc-name {
  color: var(--accent);
  font-weight: 700;
}
.style-card-add {
  border-style: dashed;
  color: var(--text-dim);
  justify-content: center;
}
.sc-plus {
  font-size: 26px;
  line-height: 1;
  opacity: 0.6;
}
.style-card-add .sc-name {
  color: var(--text-dim);
}

.subsection-title {
  margin: 14px 0 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text);
  padding-top: 10px;
  border-top: 1px dashed var(--border);
}
.hint-inline {
  font-size: 10px;
  color: var(--text-dim);
  margin-left: 4px;
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
  background: var(--panel-2);
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
.mini-btn {
  font-size: 10px;
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--panel-2);
  border: 1px solid var(--border);
  color: var(--text-dim);
  cursor: pointer;
}
.mini-btn:hover {
  color: var(--text);
  border-color: var(--accent);
}
.mini-btn.danger {
  color: var(--danger);
}
.model-panel {
  margin-top: 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow: hidden;
  background: var(--panel-2);
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
  max-height: 260px;
  overflow-y: auto;
}
.model-row {
  text-align: left;
  background: none;
  border: none;
  border-bottom: 1px solid var(--border);
  padding: 6px 10px;
  font-size: 11px;
  font-family: ui-monospace, monospace;
  color: var(--text-dim);
  cursor: pointer;
  border-radius: 0;
}
.model-row:hover {
  background: var(--panel);
  color: var(--text);
}
.model-row.active {
  color: var(--accent);
  font-weight: 600;
}
.gallery {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 10px;
}
.gallery-item {
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow: hidden;
  background: var(--panel-2);
  display: flex;
  flex-direction: column;
}
.gallery-item img {
  width: 100%;
  height: 130px;
  object-fit: cover;
  background: var(--code-bg);
}
.gallery-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 4px;
  padding: 4px 6px;
  font-size: 9px;
  color: var(--text-dim);
}
.gallery-meta .badge {
  background: var(--code-bg);
  padding: 1px 5px;
  border-radius: 999px;
}
.gallery-prompt {
  margin: 0;
  padding: 0 6px 4px;
  font-size: 9px;
  color: var(--text-dim);
  line-height: 1.4;
  max-height: 42px;
  overflow: hidden;
}
.gallery-actions {
  display: flex;
  gap: 4px;
  padding: 4px 6px 6px;
}
.gallery-actions button {
  flex: 1;
  padding: 3px 0;
}
.test-result {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 6px;
  font-size: 11px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.test-result.ok {
  background: rgba(74, 222, 128, 0.1);
  border: 1px solid rgba(74, 222, 128, 0.35);
  color: #4ade80;
}
.test-result.bad {
  background: rgba(248, 113, 113, 0.1);
  border: 1px solid rgba(248, 113, 113, 0.35);
  color: var(--danger);
}
.test-endpoint {
  margin-top: 4px;
  font-family: ui-monospace, monospace;
  opacity: 0.85;
}
.mode-badge {
  font-size: 10px;
  font-weight: 400;
  padding: 2px 8px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--panel-2);
  color: var(--text-dim);
  margin-left: 6px;
}
.mode-badge.file {
  color: #4ade80;
  border-color: rgba(74, 222, 128, 0.4);
}
.mode-badge.browser {
  color: #facc15;
  border-color: rgba(250, 204, 21, 0.4);
}
.persist-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 8px;
}
.persist-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 6px 10px;
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: 6px;
  min-width: 0;
}
.persist-item .k {
  font-size: 10px;
  color: var(--text-dim);
}
.persist-item .v {
  font-size: 11px;
  word-break: break-all;
}
.mono {
  font-family: ui-monospace, monospace;
}
.error-text {
  color: var(--danger);
}
.danger-text {
  color: var(--danger);
}
.radio-row {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--text-dim);
  cursor: pointer;
}
.radio-row input {
  width: auto;
}
.backup-list {
  margin-top: 6px;
  max-height: 200px;
  overflow-y: auto;
  background: var(--code-bg);
  border-radius: 6px;
  padding: 6px 8px;
}
.backup-row {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  font-size: 10px;
  padding: 2px 0;
  color: var(--text-dim);
}
.backup-row .dim {
  white-space: nowrap;
  opacity: 0.8;
}
.endpoint-panel {
  margin-top: 10px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel-2);
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.saved-endpoint {
  font-size: 11px;
  color: var(--accent);
}
.endpoint-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.endpoint-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 8px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--panel);
  font-size: 11px;
  flex-wrap: wrap;
}
.endpoint-row.chosen {
  border-color: var(--accent);
}
.endpoint-row.good {
  border-color: rgba(74, 222, 128, 0.5);
}
.endpoint-radio {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  min-width: 190px;
}
.endpoint-radio input {
  width: auto;
}
.endpoint-label {
  font-family: ui-monospace, monospace;
  font-size: 11px;
  color: var(--text);
}
.endpoint-hint {
  font-size: 10px;
  color: var(--text-dim);
  flex: 1;
  min-width: 100px;
}
.endpoint-status {
  font-size: 10px;
  min-width: 70px;
}
.ok-text {
  color: #4ade80;
}
.bad-text {
  color: var(--danger);
}
.dim {
  color: var(--text-dim);
}
.auto-image-box {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel-2);
}
.auto-image-box .switch {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  cursor: pointer;
}
.auto-image-box .switch input {
  width: auto;
}
.auto-image-box .tip code {
  background: var(--code-bg);
  padding: 0 4px;
  border-radius: 3px;
}
.block-label {
  display: block;
  font-size: 11px;
  color: var(--text-dim);
  margin-bottom: 6px;
}
/*
 * 整套外观预设卡片：网格平铺，每张就是一套配色的小样。
 * 手机两列（能一眼扫完 6 套），宽屏自适应多列。
 */
.theme-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(118px, 1fr));
  gap: 10px;
}
.theme-card {
  position: relative;
  height: 74px;
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  border: 2px solid var(--border);
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  align-items: flex-start;
  gap: 1px;
  overflow: hidden;
  box-shadow: var(--shadow-1);
  transition: transform 0.12s, border-color 0.12s;
}
.theme-card:hover {
  transform: translateY(-1px);
}
.theme-card.active {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--accent) 35%, transparent);
}
/* 名字和说明压在色块上：加一层暗底保证在浅色/深色底上都读得清 */
.theme-name,
.theme-desc {
  position: relative;
  z-index: 1;
  text-shadow: 0 1px 3px rgba(0, 0, 0, 0.65), 0 0 10px rgba(0, 0, 0, 0.4);
}
.theme-name {
  font-size: 12.5px;
  font-weight: 700;
  color: #fff;
}
.theme-desc {
  font-size: 10px;
  color: rgba(255, 255, 255, 0.85);
}
/* 浅色预设（纸色）上白字看不清 → 换成深字 + 浅色描边 */
.theme-card.light-preview .theme-name {
  color: #2a2520;
  text-shadow: 0 1px 2px rgba(255, 255, 255, 0.9);
}
.theme-card.light-preview .theme-desc {
  color: rgba(42, 37, 32, 0.75);
  text-shadow: 0 1px 2px rgba(255, 255, 255, 0.9);
}

.bg-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(96px, 1fr));
  gap: 8px;
}
.bg-swatch {
  position: relative;
  height: 62px;
  border-radius: 8px;
  border: 2px solid var(--border);
  cursor: pointer;
  padding: 0;
  background-size: cover;
  background-position: center;
  overflow: hidden;
}
.bg-swatch.active {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--accent) 40%, transparent);
}
.bg-name {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  font-size: 10px;
  padding: 2px 5px;
  background: rgba(0, 0, 0, 0.6);
  color: #fff;
  text-align: left;
}
.accent-chip {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  border: 2px solid var(--border);
  cursor: pointer;
  padding: 0;
}
.accent-chip.active {
  border-color: var(--text);
  box-shadow: 0 0 0 2px var(--accent);
}
.tag-cat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 6px;
  margin-top: 8px;
}
.tag-cat {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 8px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--panel-2);
  font-size: 11px;
  cursor: pointer;
}
.tag-cat.on {
  border-color: var(--accent);
}
.tag-cat input {
  width: auto;
}
.tc-name {
  flex: 1;
}
.tc-count {
  font-size: 9px;
  color: var(--text-dim);
}
.tag-preview {
  margin: 8px 0 0;
  padding: 8px 10px;
  background: var(--code-bg);
  border-radius: 6px;
  font-size: 10px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 260px;
  overflow-y: auto;
}
.tag-search-results {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 8px;
  max-height: 200px;
  overflow-y: auto;
}
.tag-chip {
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--code-bg);
  border: 1px solid var(--border);
  color: var(--text);
}
.tag-chip em {
  color: var(--text-dim);
  font-style: normal;
}
.row {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: center;
}
.col {
  flex: 1 1 0;
  min-width: 0;
}
.toggle-key {
  font-size: 10px;
  padding: 1px 6px;
  margin-left: 4px;
}
.asset-summary {
  display: flex;
  gap: 16px;
  margin-bottom: 12px;
}
.asset-count {
  text-align: center;
  padding: 12px 20px;
  background: var(--panel-2);
  border-radius: 8px;
  border: 1px solid var(--border);
}
.count-num {
  font-size: 24px;
  font-weight: 700;
  color: var(--accent);
}
.count-label {
  font-size: 11px;
  color: var(--text-dim);
}
.export-textarea {
  width: 100%;
  margin-top: 8px;
  font-family: monospace;
  font-size: 11px;
  background: var(--code-bg);
  color: var(--text-dim);
  resize: vertical;
}
.import-result {
  font-size: 12px;
  color: var(--accent-2);
}
.loading {
  padding: 40px;
  text-align: center;
  color: var(--text-dim);
}
</style>
