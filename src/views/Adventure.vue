<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useAssetsStore } from '../stores/assets';
import { useApiConfigStore } from '../stores/apiConfig';
import { useAdventureStore } from '../stores/adventure';
import { assemblePrompt } from '../engine/assembler';
import { streamChatCompletion } from '../engine/apiClient';
import { OpenAIPreset, WorldBook } from 'parsecard';
import type { ChatMessage } from '../engine/types';
import { sampleCharacterRaw, samplePresetRaw, sampleWorldbookRaw, sampleRegexRaw } from '../engine/sampleData';
import AssetPanel from '../components/AssetPanel.vue';
import ModelPicker from '../components/ModelPicker.vue';
import MarkdownRenderer from '../components/MarkdownRenderer.vue';
import ContentWithImages from '../components/ContentWithImages.vue';
import MessageActions from '../components/MessageActions.vue';
import VersionSwitcher from '../components/VersionSwitcher.vue';
import ScrollFabs from '../components/ScrollFabs.vue';
import { findScroller, scrollToEnd } from '../lib/scroller';
import SummaryPanel from '../components/SummaryPanel.vue';
import ImageQuickBar from '../components/ImageQuickBar.vue';
import ImageGenModal from '../components/ImageGenModal.vue';
import { useImageStore } from '../stores/image';
import { useAutoImage } from '../composables/useAutoImage';
import { useChatGeneration } from '../composables/useChatGeneration';
import {
  imageMarkerInstruction,
  pushImageVersion,
  switchToVersion,
  type ImageAttachment,
} from '../engine/imageMarker';
import { runRegex } from '../engine/regexEngine';
import { cleanOutput } from '../engine/reasoning';
import { useStorageSettingsStore } from '../stores/storageSettings';
import { exportChatLog } from '../lib/chatLog';
import { useQuickImage } from '../composables/useQuickImage';
import { hashRegexGroups } from '../composables/useDisplayText';
import { buildMacroContext } from '../composables/macroContext';
import { readVariables } from '../engine/variables';
import { useVariablesStore } from '../stores/variables';

const assets = useAssetsStore();
const api = useApiConfigStore();
const adventure = useAdventureStore();
const imageStore = useImageStore();
const autoImage = useAutoImage();
const variables = useVariablesStore();
/**
 * 后台自动配图的"代次"。
 *
 * 文字落库就返回、配图在后面自己跑（用户要的"文字是文字，生图是生图"）。
 * 代价是任务会活过页面切换/重新生成/停止，所以每一轮带一个代次号，
 * 旧任务在写回之前先比对，失效就静默丢弃，不会把旧图挂到新消息上。
 */
let autoImageRun = 0;
const storageSettings = useStorageSettingsStore();
// 从 composable 里解出 ref，模板中才能正常自动解包
const { pending: autoImgPending, status: autoImgStatus, error: autoImgError } = autoImage;

const showImageGen = ref(false);
const generatedImages = ref<string[]>([]);

// 生成中显示用的用户指令（streamUser）现在来自 useChatGeneration 的全局状态 ——
// 理由和 streaming 一样：退出页面再回来，它得还在。
// 原先模板里用的是 userInput，但 userInput 发出去时就清空了，
// 所以流式期间上方显示的"你"是空的，才单独留了这个变量。

/**
 * 显示时的正则处理。
 *
 * 组装提示词时跑的那遍正则，作用是**控制发给大模型的内容**
 * （promptOnly / markdownOnly 都在那里判断）。
 * 但界面上显示的是原始输出，所以类似"剥掉思维链标签"、
 * "折叠 <details>"这类**只影响显示**的规则一直没生效 ——
 * 用户看到的就是带一堆 <SUOT>、<details> 的生肉。
 *
 * 这里按「仅显示」语义再跑一遍：markdownOnly 的规则作用于显示文本。
 */
/**
 * 显示文本的记忆化缓存（关键性能优化）。
 *
 * 为什么必须缓存：模板里是 `:content="displayText(a.ai)"`，Vue 每次重渲染
 * 都会重新执行这个函数。而它内部要跑 cleanOutput + 全部正则脚本 ——
 * 20 轮对话就是**每次重渲染跑 20 遍正则引擎**（还会给正则重新排序）。
 * 流式生成时每秒重渲染好几次，手机上直接卡死。
 *
 * 缓存键 = 原文 + 影响结果的所有设置。只要这三样没变，
 * 同一条消息永远返回同一个结果，不重复计算。
 */
const displayCache = new Map<string, string>();
/** 缓存上限：超过就整体清空（简单可靠，不会无限增长） */
const DISPLAY_CACHE_MAX = 400;

/** 影响显示结果的全部外部状态，拼成一个"版本串" */
const displayKey = computed(() =>
  [
    storageSettings.hideReasoning ? '1' : '0',
    storageSettings.reasoningCutMarker || '',
    // 正则脚本变了（增删改/启用禁用/改替换串）就换版本。
    // ★ 必须带脚本内容：只数条数的话，改了替换串缓存不失效，界面还是旧样子
    assets.currentRegexGroupIds.join(','),
    hashRegexGroups(assets.regexGroups),
    // 变量改了界面必须跟着变（和"改了正则没反应"是同一类缓存 bug）
    variables.versionFor(adventure.currentSessionId),
  ].join('~'),
);

/**
 * @param depth 这条消息距最新一条的深度（0 = 最新）。
 *   ST 的 minDepth/maxDepth 按它判断；不传就当最新一条。
 */
function displayText(text: string, depth = 0): string {
  if (!text) return '';
  const key = `${displayKey.value}\u0000${depth}\u0000${text}`;
  const hit = displayCache.get(key);
  if (hit !== undefined) return hit;

  try {
    // 第一步：剥思维链。
    // 推理模型会把"草稿纸"和正文一起返回，用户不该看到那段分析。
    // 接口把推理放在独立字段时这里本来就是干净的（apiClient 已分离）；
    // 混在正文里且有标记时按标记剥；没有标记时靠用户配置的分界标记。
    let out = text;
    if (storageSettings.hideReasoning) {
      out = cleanOutput(out, storageSettings.reasoningCutMarker);
    }
    const tables = variables.snapshot(adventure.currentSessionId);
    const scripts = assets.getCurrentRegexScripts();
    if (scripts.length) {
      const { messages: msgs } = runRegex(
        [{ role: 'assistant', content: out } as any],
        scripts,
        {
          placement: { ai: true, reasoning: true },
          forDisplay: true,
          /*
           * ST 在替换串末尾会跑一遍 substituteParams，美化类正则常写
           * {{char}} / {{user}}。不展开就会原样留在正文里，看起来像没替换。
           */
          macroContext: buildMacroContext(),
            // 美化正则的替换串里也允许 {{getvar::x}}（只读）
            variables: tables,
            // 显示路径只有一条消息，必须显式告诉引擎它在对话里的真实深度，
            // 否则 minDepth >= 1 的 ST 脚本永远不生效
            depthOverride: depth,
          },
        );
        out = msgs[0]?.content ?? out;
    }
    /* 只读展开变量宏；写宏抹成空串，绝不在这里改状态 */
    out = readVariables(out, tables);
    if (displayCache.size >= DISPLAY_CACHE_MAX) displayCache.clear();
    displayCache.set(key, out);
    return out;
  } catch {
    // 任何处理出错都不能把正文弄丢
    return text;
  }
}

/**
 * 重新生图用的预填提示词。
 * 点已生成的图片时，把当初的提示词塞回弹窗，方便在此基础上微调重出。
 */
const regenPrompt = ref('');

/**
 * 重新生成时的目标。
 * 有值 = 这次的输出覆盖这一条 action；空 = 正常追加新的一条。
 */
const regenerateTargetId = ref<string | null>(null);

/**
 * 对话里那张图上的「🔄 重新生图」：**直接把原提示词发给生图接口**，
 * 不弹配置面板、也不经过 LLM 改写。
 *
 * 提示词是现成的（附件里存着完整版），重生成要的就是"同一条提示词换个种子
 * 再出一次"。以前拿截断后的 description 去重出，画面完全不对。
 */
async function regenImageInPlace(actionId: string, att?: ImageAttachment) {
  if (!att) return;
  if (quickImgBusy.value) return;
  const fresh = await quickImg.regenerateFromAttachment(att, '冒险场景');
  if (!fresh) return;
  /*
   * 把新图并进去，**旧图保留成历史版本**。
   *
   * 以前是直接替换（upsert 一份新的），旧图当场没了 ——
   * 但抽卡有随机性，新的未必比旧的好。现在旧图进 versions，
   * 点开大图可以来回切，不满意能切回去。
   */
  const merged = pushImageVersion(att, {
    id: `${att.id}-v${Date.now()}`,
    // ★ 新图在资产库里的 id 必须带上，否则切回这一版取不到图
    assetId: fresh.assetId,
    dataUrl: fresh.dataUrl,
    remotePath: fresh.remotePath,
    remoteUrl: fresh.remoteUrl,
    storage: fresh.storage,
    prompt: fresh.prompt ?? att.prompt,
    configName: fresh.configName,
    createdAt: Date.now(),
  });
  await adventure.upsertActionImage(actionId, merged);
}

/**
 * 空占位符 → 按那段描述补一张图，并**放回用户点的那个位置**。
 *
 * 用在两种场景：模型多写了标记但超过自动配图上限、或者那一张生成失败。
 *
 * 关键：占位符在正文里只是 [[IMG: ...]] 标记，images 数组里没有对应项。
 * 以前直接 upsert（按 id 追加），新图会被"按队列顺序"塞进前面第一个空位 ——
 * 点第 4 个，图却出现在第 3 个。现在先放一个空壳占住该序号（中间补齐），
 * 生成完再原地替换，位置就不会串，也多不出重复图。
 */
async function makeImageAt(actionId: string, description: string, ordinal: number) {
  if (quickImgBusy.value) return;
  // 自动配图还在跑时这些空位马上就会被填上；两条路同时写同一条消息的
  // images 会互相覆盖，先挡掉，等它跑完再手动点
  if (autoImgPending.value > 0) return;
  const act = currentSession.value?.actions.find((a) => a.id === actionId);
  if (!act) return;

  // 该位置若已有失败壳，复用它的 id，重试不会多出一张
  const existing = act.images?.[ordinal];
  const placeholder: ImageAttachment = {
    id: existing?.id ?? `att-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
    markdownIndex: ordinal,
    description,
    createdAt: Date.now(),
  };
  await adventure.setActionImageAt(actionId, ordinal, placeholder);

  const att = await quickImg.generateFor(description, '冒险场景', ordinal);
  // 生成期间切走了会话 / 删了消息 → 别把图挂到一条已经不存在的位置上
  if (!currentSession.value?.actions.some((a) => a.id === actionId)) return;

  if (!att) {
    await adventure.setActionImageAt(actionId, ordinal, {
      ...placeholder,
      error: '生成失败，点「重试」再来一次',
    });
    return;
  }
  await adventure.setActionImageAt(actionId, ordinal, {
    ...att,
    id: placeholder.id,
    markdownIndex: ordinal,
    // 保留标记里的原始描述：重新生图、figcaption 和精确配对都靠它
    description,
  });
}

/** 在大图里切到另一版 */
async function switchActionImageVersion(actionId: string, attachmentId: string, versionId: string) {
  const act = currentSession.value?.actions.find((a) => a.id === actionId);
  const att = act?.images?.find((x) => x.id === attachmentId);
  if (!att) return;
  await adventure.upsertActionImage(actionId, switchToVersion(att, versionId));
}

/**
 * 用某一步的指令重新生成 —— **在当前会话里回溯**，不是新开一个会话。
 *
 * 语义（和 SillyTavern 的 swipe / regenerate 一致）：
 *   1. 删掉这一步**之后**的所有内容（那些是建立在旧回答之上的，已经作废）
 *   2. 用同一条指令重新生成，结果替换这一步
 *
 * 想要保留旧回答、另外走一条线，用「分支」。
 */
async function regenerateWith(action: { id: string; user: string }) {
  if (streaming.value) return;
  const session = currentSession.value;
  if (!session) return;

  const idx = session.actions.findIndex((a) => a.id === action.id);
  if (idx < 0) return;

  /*
   * 回溯：这一步之后的内容建立在旧回答上，重新生成就该作废。
   *
   * 但**当前这一条不删** —— 它是被替换的对象，旧内容会存进 variants，
   * 之后可以用消息上的「‹ ›」翻回来看。这样试几版不用重来。
   *
   * 不再弹确认框：点一下就直接重来。
   * 旧回答留在 variants 里、后面被删的内容也还能靠撤销/分支找回，
   * 每次都要确认一遍反而碍事（用户的明确要求）。
   */
  const dropped = session.actions.length - idx - 1;
  if (dropped > 0) {
    session.actions = session.actions.slice(0, idx + 1);
    await adventure.save();
  }

  /*
   * ★ 先清掉上一次没生成完的草稿。
   *
   * 不清的话：草稿块（"未完成的草稿"）还挂在正文下面，重新生成过程中
   * 它会被新的流式内容覆盖 —— 用户看到的就是"刚点重新生成，
   * 底下凭空多了一条一模一样的"，非常困惑。
   */
  if (session.draft) await adventure.clearDraft();

  userInput.value = action.user;
  regenerateTargetId.value = action.id;
  // 用输入框里可能存在的改写内容作为新的用户指令版本。
  // 这样"改了指令再重新生成"也会留下版本，能翻回原来那句。
  await adventure.prepareUserVariant(action.id, action.user);
  await sendAction();
}

/** 从某一步分支：复制出新会话，原会话保持不动 */
async function branchAt(actionId: string) {
  const s = await adventure.branchFrom(actionId);
  if (s) {
    showSessionList.value = false;
    streamOutput.value = '';
  }
}

// ---------------- 一键生图 ----------------
//
// 必须把 ref **解构出来**再用。
// 之前模板里写的是 quickImg.busyFor —— 一个普通对象里的 ref 不会被模板
// 自动解包，所以那个值永远是 ref 对象本身、永远为真：
// 按钮一直显示"⏳"并且一直是禁用状态（而且根本没点过）。
const quickImg = useQuickImage();
const { busyFor: quickImgBusy, error: quickImgError } = quickImg;

/**
 * 给某条 AI 消息直接出一张配图并插进去。
 *
 * 不需要任何确认：点了就生成、生成完自动插入。
 * 不满意的话消息上的「删除」能移除配图，或者再点一次换一张。
 */
async function quickImageFor(action: { id: string; ai: string; images?: any[] }) {
  if (quickImgBusy.value) return;
  const att = await quickImg.generateFor(action.ai, '冒险场景', 0);
  if (!att) return;

  /*
   * 除了挂附件，还要在正文里补一个 [[IMG: 描述]] 标记。
   *
   * 图片显示在哪个位置是由**正文里的标记**决定的。只挂附件不写标记时，
   * 兜底逻辑会把它补到末尾 —— 但用户点的是"给这一条配图"，
   * 期望它落在这段正文里。
   */
  const marker = `\n\n[[IMG: ${att.description}]]`;
  await adventure.updateAction(action.id, { ai: action.ai + marker });
  await adventure.upsertActionImage(action.id, att);
}

/**
 * 输入框旁边那个画板按钮。
 *
 * 用户的要求：**点一下直接出图**，不要展开二级面板、不要确认。
 * 出图后自动挂到最近一条 AI 回复上（没有就新建一条只带图的记录）。
 *
 * 提示词来源优先级：
 *   1. 输入框里已经写了内容（用户可能是在描述想要的画面）
 *   2. 否则用最近一段剧情作为上下文，交给 LLM 优化
 */
async function quickImageFromInput() {
  if (quickImgBusy.value) return;
  const session = currentSession.value;
  if (!session) {
    errorMessage.value = '还没有会话，先开始一段冒险';
    return;
  }

  const typed = userInput.value.trim();
  const lastAi = [...session.actions].reverse().find((a) => a.ai?.trim());
  const source = typed || lastAi?.ai || session.sceneDescription || '';
  if (!source.trim()) {
    errorMessage.value = '还没有可用来生成画面的内容，先聊两句或写一段场景';
    return;
  }

  const att = await quickImg.generateFor(source, '冒险场景', 0);
  if (!att) {
    if (quickImgError.value) errorMessage.value = quickImgError.value;
    return;
  }

  const marker = `\n\n[[IMG: ${att.description}]]`;
  if (lastAi) {
    // 在图库/消息里都留下，并在正文末尾补标记让它在对话里可见
    await adventure.updateAction(lastAi.id, { ai: lastAi.ai + marker });
    await adventure.upsertActionImage(lastAi.id, att);
  } else {
    // 一条正文都没有：建一条只承载配图的记录，别让图无处可挂
    const act = await adventure.addAction(typed || '（场景配图）', marker.trim());
    if (act) await adventure.upsertActionImage(act.id, att);
  }
  // 用掉了输入框内容就清掉，避免用户以为还要再点发送
  if (typed) userInput.value = '';
}

/** 继续生成未完成的草稿：把草稿内容当作已有上下文，接着往下写 */
async function resumeDraft() {
  const d = currentSession.value?.draft;
  if (!d) return;
  userInput.value = d.user || '继续';
  await sendAction();
}

const userInput = ref('');
/*
 * 生成状态**放在模块级**（见 composables/useChatGeneration.ts）。
 *
 * 这里故意重命名成 streaming / streamOutput / streamUser 三个局部名字：
 * 模板和下面所有逻辑都照旧用它们，改动只集中在这一行。
 * 但底层是全局单例 —— 所以「退出聊天页再回来」时状态还在，
 * 看起来就是"生成没断"，而不是一片空白。
 */
const {
  streaming,
  output: streamOutput,
  userText: streamUser,
  sessionId: generationSessionId,
  startedAt: genStartedAt,
  stopped: genStopped,
  begin: beginGeneration,
  stop: stopGeneration,
  finish: finishGeneration,
} = useChatGeneration();
/** 这次生成是不是"从别的页面切回来才看到的"（用来多给一句提示） */
const resumedWhileStreaming = ref(false);
/** 「已经等了多久」，每秒刷新一次，只在这个会话正在生成时有意义 */
const genElapsed = ref(0);
let genClock: ReturnType<typeof setInterval> | null = null;
watch(
  [streaming, genStartedAt],
  ([on, t]) => {
    if (genClock) {
      clearInterval(genClock);
      genClock = null;
    }
    if (!on || !t) {
      genElapsed.value = 0;
      return;
    }
    const tick = () => (genElapsed.value = Math.round((Date.now() - t) / 1000));
    tick();
    genClock = setInterval(tick, 1000);
  },
  { immediate: true },
);
onUnmounted(() => {
  if (genClock) clearInterval(genClock);
});
/** 当前会话正在生成（别的会话在跑时不显示） */
const generatingHere = computed(
  () => streaming.value && generationSessionId.value === adventure.currentSessionId,
);

/**
 * 正在被「原地替换」的那条消息 id。
 *
 * 为什么需要它：
 *   重新生成以前是"原消息原地不动 + 底下又流出来一块一模一样的"，
 *   连用户文字都重复一遍 —— 看起来就像出了 bug（用户原话：
 *   "总以为是出bug在底下又来一套一摸一样的"）。
 *
 *   正确做法是**在原来的位置替换**：旧内容先让位，新内容直接长在那儿。
 *   因为 regenerateWith 已经把这一步之后的消息都截掉了，
 *   被替换的那条永远是最后一条，所以"位置"天然就对得上。
 *
 * 只有这一条本来就有 AI 正文才谈得上替换；
 * 如果 ai 是空的（比如回复被删了），替换下来没有落脚点，
 * 就退回下面那块常规流式区域 —— 否则新内容会整个看不见。
 */
const inPlaceRegenId = computed<string | null>(() => {
  const id = regenerateTargetId.value;
  if (!id) return null;
  const a = currentSession.value?.actions.find((x) => x.id === id);
  return a?.ai ? id : null;
});
/** 这一条是不是正在被原地重新生成 */
const isRegenerating = (id: string) => inPlaceRegenId.value === id;

const errorMessage = ref('');
const showAssetPanel = ref(true);
/** 手机上头部「⋯」展开状态（桌面端按钮本来就都显示，不影响） */
const headerMore = ref(false);

/**
 * 输入框是否收起。
 *
 * 手机上输入区（文本框 + 发送 + 参考图）要占掉近 1/4 屏。
 * 纯阅读/回看剧情时它很碍事，所以给一个折叠开关：
 * 收起后只留一条细栏，正文立刻多出一大截。
 * 不落盘 —— 这是"当下想多看几行"的临时状态，不是长期偏好。
 */
const inputCollapsed = ref(false);

/**
 * 手机上三块侧边面板都是"底部抽屉"，同时开会叠在一起，所以互斥。
 * 桌面端是并排的三栏，保持原来的独立开关（用户可以同时看会话列表和资产）。
 */
function isMobile() {
  return typeof window !== 'undefined' && window.innerWidth <= 768;
}
function togglePanel(which: 'sessions' | 'summary' | 'assets') {
  if (!isMobile()) {
    if (which === 'sessions') showSessionList.value = !showSessionList.value;
    else if (which === 'summary') showSummary.value = !showSummary.value;
    else showAssetPanel.value = !showAssetPanel.value;
    return;
  }
  const nextSessions = which === 'sessions' ? !showSessionList.value : false;
  const nextSummary = which === 'summary' ? !showSummary.value : false;
  const nextAssets = which === 'assets' ? !showAssetPanel.value : false;
  showSessionList.value = nextSessions;
  showSummary.value = nextSummary;
  showAssetPanel.value = nextAssets;
}
function closeAllPanels() {
  showSessionList.value = false;
  showSummary.value = false;
  showAssetPanel.value = false;
}
/*
 * 从列表点进来时带的是会话 id（路由 /adventure/:id）。
 * 不带也能用：会沿用上次选中的会话。
 */
const props = defineProps<{ id?: string }>();
const router = useRouter();

/** 返回一级列表 */
function goBack() {
  void router.push('/adventure');
}

/* ---------------- 会话改名 ---------------- */

/** 是否正在改标题 */
const renaming = ref(false);
const renameText = ref('');
const renameInput = ref<HTMLInputElement | null>(null);

function startRename() {
  if (!currentSession.value) return;
  renaming.value = true;
  renameText.value = currentSession.value.title || '';
  void nextTick(() => {
    renameInput.value?.focus();
    renameInput.value?.select();
  });
}

async function commitRename() {
  if (!renaming.value) return;
  renaming.value = false;
  await adventure.updateSessionTitle(renameText.value);
}

function cancelRename() {
  renaming.value = false;
}

/**
 * 导出这段冒险的对话记录。
 *
 * 原文还是"你看到的样子"由设置里的「导出用原文」决定
 * （见 lib/chatLog.ts 的说明）。开局场景也一并带上，
 * 否则别人拿到文件不知道故事从哪开始。
 */
function exportChat() {
  const s = currentSession.value;
  if (!s) return;
  const entries: { role: 'user' | 'ai'; text: string }[] = [];
  /* 开局场景放在最前面，否则别人拿到文件不知道故事从哪开始 */
  const opening = String(s.sceneDescription || '').trim();
  if (opening) entries.push({ role: 'ai', text: opening });
  for (const a of s.actions || []) {
    if (a.user) entries.push({ role: 'user', text: a.user });
    if (a.ai) entries.push({ role: 'ai', text: a.ai });
  }
  exportChatLog(s.title || '冒险记录', entries);
}

const showSessionList = ref(false);
/** 聊天区元素：ScrollFabs 靠它向上找可滚动祖先 */
const chatEl = ref<HTMLElement | null>(null);
const loaded = ref(false);

onMounted(async () => {
  try {
    await Promise.all([
      assets.load(),
      api.load(),
      adventure.load(),
      imageStore.load(),
      /*
       * storageSettings 必须在这里加载。
       *
       * 它保存着"图片存哪"（本地 / GitHub）以及标签参考设置。
       * 以前这一页不加载它，靠设置页或图片库页顺手读过才有值 ——
       * 于是直接进冒险页时 backend 还是默认的 'browser'、githubConfigs
       * 还是空数组，activeGithub() 返回 undefined，
       * **生图就默默只存本地、不上传仓库了**（配了也白配）。
       */
      storageSettings.load(),
      // 变量表要在首屏渲染前就位，否则第一帧会显示没展开的 {{getvar::}}
      variables.load(),
    ]);
    if (assets.characters.length === 0) {
      await loadSampleAssets();
    }
  } catch (e) {
    console.error('加载失败:', e);
  }
  loaded.value = true;

  /*
   * 选中要打开的那个会话。
   *
   * 路由是 /adventure/:id（从列表点进来）。带上 id 就切过去；
   * 没带 id（比如用户翻了旧书签，或者直接从地址栏进 /adventure）
   * 就沿用 store 里上次选中的那个 —— store.load() 已经保证了
   * currentSessionId 一定指向某个存在的会话，不会出现空白页。
   */
  if (props.id && adventure.sessions.some((s) => s.id === props.id)) {
    await adventure.switchSession(props.id);
  }

  /*
   * 手机上默认把所有侧面板收起来。
   *
   * showAssetPanel 的初值是 true（桌面端右侧要有那一栏）。
   * 手机端那些面板是"底部抽屉"，初值 true 就意味着一进页面
   * 抽屉是开着的、还盖着一层遮罩 —— 正文根本看不见。
   */
  if (isMobile()) closeAllPanels();

  /*
   * 「我离开的时候有个请求还在跑」—— 回来要明确告诉用户。
   *
   * 生成状态是模块级的（见 useChatGeneration），所以切走再回来它还在。
   * 但如果什么都不提示，用户看到的是"正文自己在长"，会以为是错乱。
   * 这里在**当前会话正好是那个在生成的会话**时给一句提示，
   * 并顺手把已经流出来的内容显示出来（模板里的流式块会接上）。
   */
  if (streaming.value && generationSessionId.value === adventure.currentSessionId) {
    resumedWhileStreaming.value = true;
  }

  /* 进会话默认看最新一条，而不是停在开头 */
  settleAtBottom();
});

async function loadSampleAssets() {

  await assets.importCharacter(sampleCharacterRaw);
  await assets.importPreset(samplePresetRaw);
  await assets.importWorldBook(sampleWorldbookRaw);
  await assets.importRegexScript(sampleRegexRaw);
  assets.currentCharacterId = assets.characters[assets.characters.length - 1]._uid;
  assets.currentPresetId = assets.presets[assets.presets.length - 1]._uid;
  assets.currentWorldBookIds = assets.worldBooks.map((w) => w._uid);
  assets.currentRegexGroupIds = assets.regexGroups.map((g: any) => g._uid);
}

const currentSession = computed(() => adventure.currentSession);

/**
 * 第 index 条消息距最新一条的深度（0 = 最新）。
 *
 * 给显示类正则用：ST 的 minDepth / maxDepth 是按聊天位置算的，
 * 而显示路径一次只处理一条消息，引擎自己算不出它在第几条。
 */
function msgDepth(index: number): number {
  const n = currentSession.value?.actions.length ?? 1;
  return Math.max(0, n - 1 - index);
}

/**
 * 生图弹窗要用的「上下文消息」——**按真实角色**展开。
 *
 * 以前是这么拼的：
 *   actions.map(a => ({ role: 'user', content: a.user }))
 *     .concat([{ role: 'assistant', content: 最后一条的 ai }])
 * 也就是所有轮次的 AI 正文都被贴上了 user 标签，只在末尾补了一条 assistant。
 * 后果（用户反馈"写着 5 条，预览只看到一条"）：
 *   弹窗里按"只取 AI 消息"筛选时只能命中末尾那一条，
 *   前面几十轮的 AI 叙述全被当成用户发言，筛不出来。
 *
 * 现在逐轮展开成 user / assistant 两条，角色真实对应。
 * 语义上 openAiMessages 已经是这个形状了，直接复用它的内容，
 * 但只保留 user/assistant（系统提示、场景描述不属于"过往消息"）。
 */
const imageContextMessages = computed<{ role: string; content: string }[]>(() => {
  const session = currentSession.value;
  if (!session) return [];
  const out: { role: string; content: string }[] = [];
  for (const a of session.actions) {
    if (a.user?.trim()) out.push({ role: 'user', content: a.user });
    if (a.ai?.trim()) out.push({ role: 'assistant', content: a.ai });
  }
  return out;
});

/**
 * 送给大模型的历史。
 *
 * 这里之前只放了 assistant 消息、把用户每一轮的指令全丢了 ——
 * 后果是模型看不到自己说过什么、也不知道用户要求过什么，
 * 多轮下来会明显"失忆"、重复或跑题。
 * 正确顺序是：场景描述 → (用户指令, AI 回复) × N。
 */
const history = computed<ChatMessage[]>(() => {
  const session = currentSession.value;
  if (!session) return [];
  const out: ChatMessage[] = [];
  if (session.sceneDescription) {
    out.push({ role: 'assistant', content: session.sceneDescription });
  }
  for (const a of session.actions) {
    if (a.user?.trim()) out.push({ role: 'user', content: a.user });
    if (a.ai?.trim()) out.push({ role: 'assistant', content: a.ai });
  }
  return out;
});

/**
 * 组装"重新生成"时的历史：截到被替换那条之前。
 *
 * 为什么单独抽出来：重新生成时模型不该看到自己的旧答案，
 * 否则它会倾向重复它。这段"按 id 截断再展开"的逻辑放在
 * sendAction 里会让主流程多一个分支，拆出来主函数只剩一行调用。
 */
function historyForRegenerate(replaceId: string): ChatMessage[] {
  const s = currentSession.value;
  if (!s) return history.value;
  const idx = s.actions.findIndex((a) => a.id === replaceId);
  const out: ChatMessage[] = [];
  if (s.sceneDescription) out.push({ role: 'assistant', content: s.sceneDescription });
  s.actions.slice(0, idx < 0 ? s.actions.length : idx).forEach((a) => {
    if (a.user?.trim()) out.push({ role: 'user', content: a.user });
    if (a.ai?.trim()) out.push({ role: 'assistant', content: a.ai });
  });
  return out;
}

/**
 * 组装发给模型的最终消息列表。
 *
 * 失败时把原因写进 errorMessage 并返回 null —— 调用方（sendAction）
 * 看到 null 直接 return，不用关心具体是哪一步没过。
 */
function buildFinalMessages(baseHistory: ChatMessage[], action: string): ChatMessage[] | null {
  const presetRaw = assets.getCurrentPreset();
  if (!presetRaw) {
    errorMessage.value = '请先选择一个预设（在「资产」页导入并启用）';
    return null;
  }
  const preset = OpenAIPreset.fromJSON(presetRaw);
  const character = assets.getCurrentCharacter();
  const worldBooks = assets.getCurrentWorldBooks().map((w) =>
    WorldBook.fromJSON(w).toStandaloneJSON(),
  );
  /*
   * 正则要不要作用在"发给模型的上下文"上，由用户选（设置 → 数据与上下文）。
   * 关掉（默认）= 跑正则：带 promptOnly 的规则生效、markdownOnly 的跳过；
   * 打开 = 原文直发，连提示词类正则也不跑。
   */
  const regexScripts = storageSettings.llmContextRaw
    ? []
    : assets.getCurrentRegexScripts();

  const result = assemblePrompt({
    preset,
    characterCard: character,
    worldBooks,
    regexScripts,
    chatHistory: [...baseHistory, { role: 'user' as const, content: action }],
    contextWindowTokens: api.currentConfig?.contextWindow,
    userLookbackTokens: api.currentConfig?.userLookbackTokens,
    // 预设 / 世界书里的 {{getvar::x}} 在这里只读展开
    variables: variables.snapshot(adventure.currentSessionId),
  });

  // 告诉模型可以用 [[IMG: 描述]] 自行选择配图位置（只作本次请求的附加指令）
  if (imageStore.autoImage.enabled) {
    const msgs = result.finalMessages;
    const tail = msgs[msgs.length - 1];
    if (tail) {
      msgs[msgs.length - 1] = {
        ...tail,
        content: tail.content + imageMarkerInstruction(true, imageStore.autoImage.maxPerReply),
      };
    }
  }
  return result.finalMessages;
}

async function sendAction() {
  /*
   * 早退也必须清掉「正在替换哪一条」。
   *
   * regenerateWith 是**先设好 target 再调这里**的；如果这里直接 return
   * （输入为空、没有会话），下面的 finally 不会执行 ——
   * 那条消息就会一直被当成"正在重新生成"而隐藏起来，再也回不来。
   */
  if (!userInput.value.trim() || !currentSession.value) {
    regenerateTargetId.value = null;
    return;
  }

  errorMessage.value = '';
  resumedWhileStreaming.value = false;

  const action = userInput.value;
  // 记住这次发的是什么，流式输出期间界面上要显示
  // （userInput 下面会被清空，所以必须用独立的变量记）
  userInput.value = '';

  /*
   * 开始一次生成：拿一个 AbortSignal，「停止」按钮就靠它。
   * 状态存在模块级，所以中途切页面回来，signal 和状态都还在。
   */
  const signal = beginGeneration(currentSession.value.id, action);

  // 重新生成：历史里要排除掉将被替换的那一条，否则模型会看到
  // "自己的旧答案" 然后倾向重复它
  // 新一轮开始 → 让上一轮还在后台跑的自动配图彻底失去写入资格
  autoImageRun++;
  const replaceId = regenerateTargetId.value;

  /*
   * 把这一轮的流式结果落进会话。
   *
   * 抽成函数是因为**正常完成和用户按「停止」都要走它**：
   * 停止时已经流出来的正文不该白丢，得照样存成一条 AI 回复。
   *
   * 定义在 try 外面 —— catch 里处理"被停止"时也要调用它，
   * 写在 try 里面 catch 就看不见了（作用域）。
   */
  const commitStreamResult = async (raw: string, allowAutoImage = true) => {
    /*
     * ★ 变量写宏的第二个执行点：AI 回复落库，整段只跑这一次。
     *
     * 这是流式结束 / 用户点停止时**唯一**的落库入口，所以不会重复执行。
     * 落库的是展开后的干净文本，正文里不会残留 {{setvar::...}}；
     * 重新生成时历史来自已落库文本，也不会再触发一次写操作。
     */
    const cleanRaw = await variables.runWrites(raw, adventure.currentSessionId);
    flushDraftSave(action, cleanRaw);
    if (!cleanRaw.trim()) {
      await adventure.clearDraft();
      return;
    }

    let act;
    if (replaceId) {
      // 重新生成：覆盖原来那一条，不留草稿
      await adventure.replaceActionAi(replaceId, cleanRaw);
      await adventure.clearDraft();
      const s = currentSession.value;
      act = s?.actions.find((a) => a.id === replaceId);
      // 覆盖时旧配图已经不匹配新正文了，清掉重新配
      if (act?.images?.length) {
        for (const im of [...act.images]) {
          await adventure.removeActionImage(act.id, im.id);
        }
      }
    } else {
      act = await adventure.commitDraft(cleanRaw);
    }

    // 消息已经落库，立刻清掉流式文本，避免它和正式消息同帧渲染（看起来像重复）
    streamOutput.value = '';

    void maybeAutoSummary(currentSession.value?.id ?? '');

    /*
     * ★ 自动配图**不在这里等**（用户要求：文字是文字，生图是生图）。
     *
     * 以前这里是 await —— 生图慢的时候（十秒甚至更久），
     * 整条回复要等图全出完才显示，用户盯着"正在生成…"看不出已经写完了。
     * 现在文字一落库就返回，配图在后台自己跑，出好一张挂一张。
     *
     * 错误也不能往外抛：那会把"消息已成功保存"变成一次失败。
     */
    if (act && imageStore.autoImage.enabled && allowAutoImage) {
      const actionId = act.id;
      const sessionId = currentSession.value?.id;
      const run = ++autoImageRun;
      const isCurrent = () => run === autoImageRun && currentSession.value?.id === sessionId;
      void autoImage
        .processMarkers(
          cleanRaw,
          (att) => {
            // 切走会话 / 删了消息 / 又发了一轮：旧任务的结果直接丢掉
            if (!isCurrent()) return Promise.resolve();
            return adventure.upsertActionImage(actionId, att);
          },
          '冒险场景',
          { isCurrent },
        )
        .catch((e) => console.warn('[自动配图] 后台生成失败：', e));
    } else {
      // 被「停止」或本就不配图时，让可能还在后台跑的上一轮立刻失效
      autoImageRun++;
    }
  };

  const baseHistory = replaceId ? historyForRegenerate(replaceId) : history.value;

  try {
    /*
     * ★ 变量写宏的第一个执行点：用户发送时，整段只跑这一次。
     *
     * 显示路径每秒重渲染数次，绝不能在那里执行 setvar/incvar ——
     * 计数器会被重复累加。这里执行完把干净文本交给组装器，
     * 原始输入仍照常存进会话（显示时写宏会被抹成空串，视觉上是干净的）。
     */
    const cleanAction = await variables.runWrites(action, adventure.currentSessionId);
    const finalMessages = buildFinalMessages(baseHistory, cleanAction);
    if (!finalMessages) return;

    // 调用 API
    const cfg = api.currentConfig;
    if (!cfg) {
      errorMessage.value = '请先配置 API（在设置页）';
      return;
    }
    if (!cfg.apiKey) {
      errorMessage.value = '请填写 API Key';
      return;
    }

    await streamChatCompletion(
      { ...cfg },
      finalMessages,
      {
        onToken: (d) => {
          streamOutput.value += d;
          // 边收边存：切窗口/锁屏/刷新都不会白等一场。
          // 节流到约每秒一次，避免每来一个 token 就写一次盘。
          scheduleDraftSave(action, streamOutput.value);
        },
        onDone: async () => {
          await commitStreamResult(streamOutput.value);
        },
      },
      signal,
    );
  } catch (e) {
    /*
     * 被「停止」掐断的不算错误。
     *
     * fetch 被 abort 会抛 AbortError，如果照旧显示成红色报错，
     * 用户会以为"程序坏了"，其实是他自己点的停止。
     */
    const aborted =
      genStopped.value ||
      (e instanceof DOMException && e.name === 'AbortError') ||
      (e instanceof Error && e.name === 'AbortError');

    if (aborted) {
      const raw = streamOutput.value;
      if (!raw.trim()) {
        /*
         * 一个字都还没出来 —— 基本就是"发出去才发现打错字了"。
         * 这种直接把这一轮丢掉，并把内容**还回输入框**，
         * 用户改一下就能重发，不用先去删两条消息。
         */
        await adventure.clearDraft();
        userInput.value = action;
      } else {
        // 已经生成了一部分：留着，别白等。
        // 但用户是主动点停止的，就别再偷偷启动一轮自动配图了
        await commitStreamResult(raw, false);
      }
    } else {
      errorMessage.value = e instanceof Error ? e.message : String(e);
      // 出错时保留草稿，用户回来后还能看到已经生成的部分
      flushDraftSave(action, streamOutput.value);
    }
  } finally {
    regenerateTargetId.value = null;
    /*
     * 必须清掉流式文本。
     *
     * 模板里流式块的条件是 `streaming || streamOutput` —— 只要 streamOutput
     * 还有内容它就一直渲染。生成完成后消息已经落库（草稿块或正式消息都在），
     * 这里不清的话**同一段 AI 回复会显示两遍**。
     * onDone 已被 await（见 apiClient 的 finish），所以此处清理是在
     * 提交完成之后，不会把还没读到的内容抹掉。
     */
    finishGeneration();
  }
}

/**
 * 草稿落盘的节流。
 *
 * 流式输出每秒可能来几十个 token，直接每次都写盘会把存储打爆。
 * 这里保证「最多每秒写一次」，并且页面被隐藏/关闭前强制补一次。
 */
let draftTimer: ReturnType<typeof setTimeout> | null = null;
let draftPending: { user: string; ai: string } | null = null;

function scheduleDraftSave(user: string, ai: string) {
  draftPending = { user, ai };
  if (draftTimer) return;
  draftTimer = setTimeout(() => {
    draftTimer = null;
    flushDraftSave(draftPending?.user ?? user, draftPending?.ai ?? ai);
  }, 1000);
}

function flushDraftSave(user: string, ai: string) {
  if (draftTimer) {
    clearTimeout(draftTimer);
    draftTimer = null;
  }
  draftPending = null;
  void adventure.setDraft(user, ai);
}

/** 页面被隐藏或即将关闭时，把还没落盘的那一段补上 */
function onHide() {
  if (document.visibilityState === 'hidden' && draftPending) {
    flushDraftSave(draftPending.user, draftPending.ai);
  }
}

onMounted(() => {
  document.addEventListener('visibilitychange', onHide);
  window.addEventListener('beforeunload', onHide);
});
onUnmounted(() => {
  document.removeEventListener('visibilitychange', onHide);
  window.removeEventListener('beforeunload', onHide);
});

/** 开场场景的编辑状态 */
const editingScene = ref(false);
const sceneDraft = ref('');

function saveScene() {
  void adventure.updateSceneDescription(sceneDraft.value.trim());
}

async function clearScene() {
  if (!window.confirm('清空开场场景？已生成的对话不受影响。')) return;
  sceneDraft.value = '';
  await adventure.updateSceneDescription('');
}

/** 切换会话时同步场景编辑框 */
watch(
  () => currentSession.value?.id,
  () => {
    editingScene.value = false;
    sceneDraft.value = currentSession.value?.sceneDescription ?? '';
  },
  { immediate: true },
);

async function deleteSession(id: string) {
  await adventure.removeSession(id);
}

// ---------------- 摘要 ----------------
//
// 和小说那边同一套机制：把前面发生的事压成「前情提要」附在请求最前面。
// 冒险会话往往很长（几十上百轮），不摘要的话要么被 token 裁剪丢掉设定，
// 要么每轮都把全文塞给模型、又慢又贵。
const showSummary = ref(false);
const summarizing = ref(false);
const summaryError = ref('');

const sessionSettings = computed(() => adventure.settingsOf());

async function runSummary(
  sessionId: string,
  coveredUpTo: number,
  targetWords: number,
  mode: 'manual' | 'auto',
) {
  const cfg = api.currentConfig;
  if (!cfg?.apiKey) {
    summaryError.value = '需要先在设置页配置 API 才能生成摘要';
    return;
  }
  const source = adventure.textForSummary(sessionId, coveredUpTo);
  if (!source.trim()) {
    summaryError.value = '这段区间没有内容可总结';
    return;
  }

  summarizing.value = true;
  summaryError.value = '';
  try {
    const text = await streamChatCompletion(
      { ...cfg },
      [
        {
          role: 'system',
          content:
            `你是冒险游戏的记录员。把下面的内容压缩成不超过 ${targetWords} 字的摘要。\n` +
            '要求：保留人物、地点、已发生的关键事件、人物关系变化、以及玩家做过的选择；' +
            '保留尚未解决的线索和伏笔；用陈述句；不要加评论、标题或建议。',
        },
        { role: 'user', content: source.slice(-20000) },
      ],
      {},
    );
    const trimmed = (text || '').trim();
    if (!trimmed) {
      summaryError.value = '模型没有返回内容';
      return;
    }
    await adventure.addSummary({ text: trimmed, mode, coveredUpTo, targetWords });
  } catch (e) {
    summaryError.value = e instanceof Error ? e.message : String(e);
  } finally {
    summarizing.value = false;
  }
}

async function manualSummary(targetWords: number, upTo: number) {
  const s = currentSession.value;
  if (!s) return;
  await runSummary(s.id, upTo, targetWords, 'manual');
}

/** 到达阈值就后台生成一条，失败不打扰用户 */
async function maybeAutoSummary(sessionId: string) {
  const due = adventure.summaryDue(sessionId);
  if (!due) return;
  try {
    await runSummary(sessionId, due.coveredUpTo, sessionSettings.value.targetWords, 'auto');
  } catch {
    /* 自动摘要尽力而为 */
  }
}

async function updateSessionSettings(patch: Partial<typeof sessionSettings.value>) {
  await adventure.updateSettings(patch);
}

/**
 * 跟到最新一条。
 *
 * 以前写死 `getElementById('chat-area').scrollTop = scrollHeight`，
 * 但手机上聊天区是 overflow:visible、真正滚动的是 window，那行完全没效果。
 * 现在统一走 findScroller()：桌面滚 .chat-area，手机滚整页。
 */
function scrollToBottom() {
  nextTick(() => {
    scrollToEnd(findScroller(chatEl.value), 'auto');
  });
}

/**
 * 进入会话时直接落在最下方（用户明确要求）。
 *
 * 为什么要补好几帧：消息气泡带 content-visibility:auto，屏幕外的块先按
 * 220px 占位，真实排版 / 图片懒加载之后 scrollHeight 还会再变。
 * 只滚一次会停在"看起来快到底、其实没到"的位置。
 * 全程瞬时滚动，不让用户看一段动画。
 */
function settleAtBottom() {
  void nextTick(() => {
    const jump = () => {
      if (!chatEl.value) return;
      scrollToEnd(findScroller(chatEl.value), 'auto');
    };
    jump();
    requestAnimationFrame(() => requestAnimationFrame(jump));
    setTimeout(jump, 180);
  });
}

watch([() => adventure.currentSession?.actions.length, streamOutput], scrollToBottom);

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    sendAction();
  }
}

function handleImageGenerated(dataUrl: string) {
  if (!currentSession.value) return;
  // 将图片追加到场景描述中
  const current = currentSession.value.sceneDescription;
  adventure.updateSceneDescription(current + '\n\n' + `![场景插图](${dataUrl})`);
  generatedImages.value.push(dataUrl);
  showImageGen.value = false;
}
</script>

<template>
  <div class="adventure-page" v-if="loaded">
    <div class="adventure-header">
      <!--
        第一行：只留**天天都在用**的 —— 返回 / 会话名 / 当前模型 /
        生图 / 摘要，最后是「⋯」。收起时整个顶栏就这一行。
      -->
      <div class="ah-row">
        <div class="header-left">
          <!--
            返回按钮：回到会话列表（一级界面）。
            手机上这是"二级页面"的标准出口，必须有 —— 以前只能靠底部标签栏
            重新点一遍"冒险"，语义上不对（用户想的是"返回"，不是"换 tab"）。
          -->
          <button class="back-btn" @click="goBack" title="返回会话列表">‹</button>
          <!--
            标题可点＝改名。
            以前这一幕完全没有改名入口，所有会话都叫「新冒险」（用户报的）。
          -->
          <input v-if="renaming" ref="renameInput" class="hdr-rename" v-model="renameText"
            maxlength="40" @keyup.enter="commitRename" @keyup.esc="cancelRename" @blur="commitRename" />
          <h2 v-else class="hdr-title" @click="startRename" title="点一下改名">
            {{ currentSession?.title || '冒险' }}
          </h2>
          <!-- 当前模型：点一下切换（运营商 → 模型 两级） -->
          <ModelPicker />
        </div>
        <div class="header-right">
          <button @click="showImageGen = !showImageGen">🎨 生图</button>
          <button @click="togglePanel('summary')" :class="{ active: showSummary }">
            📋 摘要<span v-if="currentSession?.summaries?.length"> {{ currentSession.summaries.length }}</span>
          </button>
          <!-- 手机上先收进「⋯」，避免一排按钮挤掉内容区高度 -->
          <button class="more-toggle" :class="{ active: headerMore }"
            @click="headerMore = !headerMore"
            :title="headerMore ? '收起更多操作' : '更多操作'">
            {{ headerMore ? '收起' : '⋯' }}
          </button>
        </div>
      </div>

      <!--
        第二块：展开后才出现的子选项。
        用 CSS 控制显隐而不是 v-if —— 桌面端屏幕宽、不需要折叠，
        这些按钮要**常驻显示**（见 .extra-btns 的桌面规则）。
      -->
      <div class="extra-btns" :class="{ open: headerMore }">
        <!--
          这里**不放**「会话列表」和「新开会话」：
          返回键 ← 回到的就是会话列表，那一页一目了然、想开新的点一下就行。
          在会话里再塞一份是重复入口，还占折叠层的位置。
          折叠层只留"这一笔对话的临时设置"。
        -->
        <!--
          参考图从"输入框上方"搬到这里。
          它原本常驻在输入区，占掉一整行 —— 而输入区正是最缺高度的地方。
          它属于"这次要用哪张参考图"，是**偶尔改一次**的东西，
          收进「⋯」里正合适；真要用时展开就能看到当前缩略图。
        -->
        <ImageQuickBar />
        <button
          :class="{ active: imageStore.autoImage.enabled }"
          :title="imageStore.autoImage.enabled
            ? '已开启：AI 会在正文里自行选位置配图（图片不会进入大模型上下文）'
            : '开启后 AI 会在正文里自行选位置配图'"
          @click="imageStore.updateAutoImage({ enabled: !imageStore.autoImage.enabled })"
        >
          🖼️ 自动配图 {{ imageStore.autoImage.enabled ? '开' : '关' }}
        </button>
        <button @click="togglePanel('assets')">
          {{ showAssetPanel ? '隐藏' : '显示' }}资产
        </button>
        <button @click="exportChat" title="导出这段冒险的对话记录（原文或美化后的文本，见设置）">
          ⤓ 导出记录
        </button>
      </div>
    </div>

    <div class="adventure-body">
      <!-- 左侧：会话列表 -->
      <aside v-if="showSessionList" class="session-list">
        <!-- 手机端抽屉的手柄 + 关闭（桌面端不显示） -->
        <div class="sheet-handle">
          <span class="grabber"></span>
          <button class="sheet-close" @click="showSessionList = false" title="关闭">✕</button>
        </div>
        <h3>会话</h3>
        <div v-for="s in adventure.sessions" :key="s.id" class="session-item"
          :class="{ active: s.id === adventure.currentSessionId }"
          @click="adventure.switchSession(s.id)">
          <span class="session-name">{{ s.title }}</span>
          <span class="session-actions">{{ s.actions.length }} 步</span>
          <button class="danger tiny" @click.stop="deleteSession(s.id)">×</button>
        </div>
      </aside>

      <!-- 中间：场景 -->
      <div class="chat-area-wrapper">
        <div class="chat-area" id="chat-area" ref="chatEl">
          <!--
            ★ 未完成生成 —— 提示条放在正文**最上面**，而不是只在末尾渲染一个草稿块。

            为什么：
            之前草稿块渲染在正文最后面。用户退出对话再进来时，
            store 是异步加载的，那一会儿里页面上什么都看不到，
            要等加载完才突然冒出来一条"未完成的草稿" —— 用户根本不知道
            刚才那次生成到底成没成，只会觉得"消息丢了"。

            现在：进对话第一眼就能看到"上次没生成完"，附带三个明确动作。
          -->
          <div v-if="!streaming && currentSession?.draft?.ai" class="unfinished-bar">
            <div class="ub-head">
              <span class="ub-icon">⚠️</span>
              <span class="ub-title">上次有一段生成没完成</span>
            </div>
            <p class="ub-sub">
              已经写了一半：<b>{{ (currentSession.draft.ai || '').slice(0, 40) }}{{ (currentSession.draft.ai || '').length > 40 ? '…' : '' }}</b>
            </p>
            <div class="ub-acts">
              <button class="ma primary" @click="adventure.commitDraft()">保留这条</button>
              <button class="ma" @click="resumeDraft">继续生成</button>
              <button class="ma danger" @click="adventure.clearDraft()">丢弃</button>
            </div>
          </div>

          <!-- 开场场景：可编辑、可清空。不预填任何剧情，
               由用户自己写（或交给世界书/角色卡） -->
          <div class="scene-card">
            <div class="scene-label">
              📍 开场场景
              <div class="scene-acts">
                <button class="mini" @click="editingScene = !editingScene">
                  {{ editingScene ? '收起' : (currentSession?.sceneDescription ? '编辑' : '填写') }}
                </button>
                <button v-if="currentSession?.sceneDescription" class="mini danger"
                  @click="clearScene">清空</button>
              </div>
            </div>
            <div v-if="!editingScene" class="scene-text">
              <MarkdownRenderer v-if="currentSession?.sceneDescription"
                :content="currentSession.sceneDescription" />
              <span v-else class="scene-empty">
                还没有开场场景。可以留空直接开始（世界书和角色卡也会提供背景），
                也可以点「填写」写一段。
              </span>
            </div>
            <textarea v-else class="scene-edit" rows="4" v-model="sceneDraft"
              placeholder="例如：深夜的图书馆，你正在查阅一本古籍…（留空也可以）"
              @change="saveScene"></textarea>
          </div>

          <!-- 对话历史 -->
          <div v-for="(a, ai) in currentSession?.actions ?? []" :key="a.id" class="message-group">
            <!-- 用户侧：删掉自己那句时**不动** AI 的回复，反之亦然 -->
            <div v-if="a.user" class="message user">
              <div class="msg-role">🧑 你</div>
              <div class="msg-content">{{ a.user }}</div>
              <MessageActions
                :content="a.user"
                :streaming="streaming"
                remove-label="删我的发言"
                confirm-label="删掉我说的这句？"
                regenerate-label="重新发送"
                @save="(t) => adventure.updateAction(a.id, { user: t })"
                @regenerate="() => regenerateWith(a)"
                @branch="() => branchAt(a.id)"
                @remove="() => adventure.removeActionUser(a.id)"
              />
            </div>
            <!--
              ★ 条件里带上 "有历史版本"。
              只写 v-if="a.ai" 时，一旦当前版本正文为空（旧数据里可能残留
              一个空版本），整块会消失 —— 连「‹ ›」切换按钮也一起没了，
              用户就再也切不回有内容的那一版（只能重新生成"救"回来）。
              只要还有版本可翻，这块就必须显示，否则等于把自己锁死。
            -->
            <div v-if="a.ai || (a.variants && a.variants.length)" class="message ai">
              <div class="msg-role">
                🤖 AI
                <!--
                  正在重新生成时，把「‹ ›」和「配图」先收起来：
                  这一刻内容正被覆盖，中途切版本/配图会打架。
                  生成完它们自己会回来（那时 variants 里已经有旧版可翻）。
                -->
                <span v-if="isRegenerating(a.id)" class="regen-tag">重新生成中…</span>
                <VersionSwitcher
                  v-else-if="a.variants && a.variants.length"
                  :total="a.variants.length"
                  :index="a.variantIndex ?? 0"
                  @switch="(i) => adventure.switchVariant(a.id, i)"
                />
                <!-- 一键生图：点一下就用这条内容出一张图，自动插进来 -->
                <button v-if="!isRegenerating(a.id)" class="quick-img" :disabled="!!quickImgBusy"
                  @click="quickImageFor(a)"
                  title="用这段内容生成一张插图，自动插入，不满意可删除或重新生成">
                  {{ quickImgBusy ? '生成中…' : '🎨 配图' }}
                </button>
              </div>
              <div class="msg-content">
                <!--
                  ★ 重新生成：**就在这个位置**换内容。
                    旧正文先让位，新正文流式长在这里 ——
                    底下不会再冒出第二份一模一样的。
                -->
                <template v-if="isRegenerating(a.id)">
                  <ContentWithImages :content="displayText(streamOutput)" />
                  <span v-if="streaming" class="cursor">▋</span>
                </template>
                <ContentWithImages
                  v-else
                  :content="displayText(a.ai, msgDepth(ai))"
                  :attachments="a.images"
                  removable
                  regenerable
                  @remove-image="(id) => adventure.removeActionImage(a.id, id)"
                  @regenerate="(att) => regenImageInPlace(a.id, att)"
                  @request-image="(desc, ordinal) => makeImageAt(a.id, desc, ordinal)"
                  @switch-version="(aid, vid) => switchActionImageVersion(a.id, aid, vid)"
                />
                <!-- 当前版本没有正文（历史残留的空版本）→ 明确说清，并给一条出路 -->
                <p v-if="!isRegenerating(a.id) && !a.ai" class="empty-reply">
                  这一版没有内容，点上方的「‹」切回有回复的那一版
                </p>
              </div>
              <MessageActions
                v-if="!isRegenerating(a.id)"
                :content="a.ai"
                :streaming="streaming"
                remove-label="删这条回复"
                confirm-label="只删这条回复？"
                @save="(t) => adventure.updateAction(a.id, { ai: t })"
                @regenerate="() => regenerateWith(a)"
                @branch="() => branchAt(a.id)"
                @remove="() => adventure.removeActionAi(a.id)"
              />
            </div>
          </div>

          <!--
            未完成草稿的提示条已挪到正文最上面（见 chat-area 开头）。
            原来在这里渲染一整块"用户消息 + AI 消息"，问题是：
              · 它在正文末尾，用户要滚到底才看得见
              · 退出再进来时 store 异步加载，那一会儿页面空白，
                用户以为消息丢了
            顶部提示条 + 这里的流式块就够了。
          -->

          <!-- 流式输出 -->
          <div v-if="streaming || streamOutput" class="message-group">
            <!--
              「还在生成中」提示条。
              存在的意义：用户切走再回来时，看到的是正文自己在长 ——
              没有这句话会以为是界面错乱。顺便给出已等待时间和停止入口。
            -->
            <div v-if="generatingHere" class="gen-banner">
              <span class="spinner">⏳</span>
              <span class="gen-text">
                正在生成{{ genElapsed > 0 ? `（已 ${genElapsed} 秒）` : '…' }}
                <em v-if="resumedWhileStreaming" class="gen-hint">离开页面时也没断，还在继续</em>
              </span>
              <button class="gen-stop" @click="stopGeneration" title="停止这次生成">⏹ 停止</button>
            </div>
            <!--
              ★ 只有「新发一条」才在这里渲染正文。
                重新生成走的是**原地替换**（正文长在上面对应那条消息的位置），
                这里就只剩状态条 —— 否则用户文字和 AI 正文会各重复一遍，
                看起来像凭空多出一套一模一样的（用户报的就是这个）。
            -->
            <template v-if="!inPlaceRegenId">
              <div class="message user" v-if="streamUser || streaming">
                <div class="msg-role">🧑 你</div>
                <div class="msg-content">{{ streamUser || '...' }}</div>
              </div>
              <div class="message ai">
                <div class="msg-role">🤖 AI</div>
                <div class="msg-content">
                  <ContentWithImages :content="displayText(streamOutput)" />
                  <span v-if="streaming" class="cursor">▋</span>
                </div>
              </div>
            </template>
            <!-- 自动配图进度 -->
            <div v-if="autoImgPending > 0" class="auto-image-status">
              <span class="spinner">⏳</span>
              <span>{{ autoImgStatus }}</span>
            </div>
            <div v-else-if="autoImgError" class="auto-image-error">
              ⚠ 自动配图失败：{{ autoImgError }}
            </div>
          </div>

          <!-- 空状态 -->
          <div v-if="!currentSession?.sceneDescription && !currentSession?.actions.length && !streaming"
            class="empty-state">
            <p>选择一个会话开始冒险，或点击"+ 新冒险"创建新场景。</p>
            <p class="hint">输入行动描述，AI 将根据预设和世界书生成后续剧情。</p>
          </div>
        </div>

        <!-- 快速回顶 / 回底 -->
        <ScrollFabs :anchor="chatEl" />

        <!-- 底部输入 -->
        <div class="input-area" :class="{ collapsed: inputCollapsed }">
          <!--
            输入区折叠开关。
            收起后只剩这条细栏，正文立刻多出一大截 —— 回看剧情时很有用。
          -->
          <button class="input-toggle" @click="inputCollapsed = !inputCollapsed"
            :title="inputCollapsed ? '展开输入框' : '收起输入框，多看几行正文'">
            <span class="it-grip"></span>
            {{ inputCollapsed ? '⌃ 展开输入' : '⌄ 收起' }}
          </button>
          <div class="input-row" v-show="!inputCollapsed">
            <button class="img-gen-btn" @click="quickImageFromInput"
              :disabled="!!quickImgBusy"
              :title="userInput.trim()
                ? '用输入框里的内容直接出一张图（自动插入当前场景）'
                : '用最近一段剧情直接出一张图（自动插入）'">
              {{ quickImgBusy ? '⏳' : '🎨' }}
            </button>
            <textarea
              v-model="userInput"
              @keydown="onKeydown"
              placeholder="输入你的行动...（Enter 发送，Shift+Enter 换行）"
              :disabled="streaming"
              rows="2"
            ></textarea>
            <button class="primary send-btn" v-if="streaming" @click="stopGeneration"
              title="停止这次生成（已经收到的内容会保留）">
              ⏹ 停止
            </button>
            <button class="primary send-btn" v-else @click="sendAction" :disabled="!userInput.trim()">
              发送
            </button>
          </div>
        </div>
      </div>

      <!-- 右侧：摘要 / 资产面板（互斥，避免挤成三栏）
           手机端：底部抽屉 + 遮罩（见 <style> 里的 @media） -->
      <div
        v-if="showSessionList || showSummary || showAssetPanel"
        class="sheet-backdrop"
        @click="closeAllPanels"
      ></div>
      <aside v-if="showSummary" class="asset-aside summary-aside">
        <div class="sheet-handle">
          <span class="grabber"></span>
          <button class="sheet-close" @click="showSummary = false" title="关闭">✕</button>
        </div>
        <SummaryPanel
          :chapter="(currentSession as any)"
          :summarizing="summarizing"
          :error="summaryError"
          :target-words="sessionSettings.targetWords"
          :message-count="currentSession?.actions?.length ?? 0"
          @summary="manualSummary"
          @remove="(id) => adventure.removeSummary(id)"
          @update="(id, text) => adventure.updateSummary(id, text)"
          @close="showSummary = false"
        />
        <!-- 摘要策略 -->
        <div class="sum-settings">
          <label class="ss-row">
            <span>自动摘要</span>
            <input type="checkbox" :checked="sessionSettings.autoSummary"
              @change="updateSessionSettings({ autoSummary: ($event.target as HTMLInputElement).checked })" />
          </label>
          <label class="ss-row">
            <span>触发方式</span>
            <select :value="sessionSettings.trigger"
              @change="updateSessionSettings({ trigger: ($event.target as HTMLSelectElement).value as any })">
              <option value="messages">按轮数</option>
              <option value="chars">按字数</option>
            </select>
          </label>
          <label v-if="sessionSettings.trigger === 'messages'" class="ss-row">
            <span>每多少轮</span>
            <input type="number" min="1" :value="sessionSettings.everyMessages"
              @change="updateSessionSettings({ everyMessages: Number(($event.target as HTMLInputElement).value) || 1 })" />
          </label>
          <label v-else class="ss-row">
            <span>每多少字</span>
            <input type="number" min="200" step="200" :value="sessionSettings.everyChars"
              @change="updateSessionSettings({ everyChars: Number(($event.target as HTMLInputElement).value) || 2000 })" />
          </label>
          <label class="ss-row">
            <span>摘要目标字数</span>
            <input type="number" min="50" step="50" :value="sessionSettings.targetWords"
              @change="updateSessionSettings({ targetWords: Number(($event.target as HTMLInputElement).value) || 300 })" />
          </label>
        </div>
      </aside>
      <aside v-else-if="showAssetPanel" class="asset-aside">
        <div class="sheet-handle">
          <span class="grabber"></span>
          <button class="sheet-close" @click="showAssetPanel = false" title="关闭">✕</button>
        </div>
        <AssetPanel :compact="false" />
      </aside>
    </div>

    <!-- 错误提示 -->
    <div v-if="errorMessage" class="error-bar">
      <span>⚠ {{ errorMessage }}</span>
      <button @click="errorMessage = ''">×</button>
    </div>

    <!-- 生图弹窗 -->
    <ImageGenModal
      v-if="showImageGen && currentSession"
      :context-messages="imageContextMessages"
      :context-label="'冒险场景'"
      :scene="'adventure'"
      :entity-id="currentSession.id"
      :preset-prompt="regenPrompt"
      :reference-image="imageStore.quickReferenceImage"
      :reference-strength="imageStore.quickReferenceStrength"
      @generated="handleImageGenerated"
      @close="showImageGen = false; regenPrompt = ''"
    />
  </div>

  <div v-else class="loading">加载中…</div>
</template>

<style scoped>
.adventure-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}
.adventure-header {
  /*
   * 竖排两块的容器：第一行是常驻操作，第二块是展开后的子选项。
   * 原来是一整行 flex + 靠 wrap 折行 —— 折在哪取决于宽度，
   * 手机上会随机变成两三行，高度不可控。
   */
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 7px;
  padding: 10px 16px;
  background: var(--glass);
  -webkit-backdrop-filter: blur(12px) saturate(1.25);
  backdrop-filter: blur(12px) saturate(1.25);
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
  /* 吸顶：手机上滚长对话时标题栏一直在，随时能操作 */
  position: sticky;
  top: 0;
  z-index: 25;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.28);
}
/*
 * ========================================================================
 * Material 3 顶栏
 * ========================================================================
 *
 * 和 iOS 顶栏的三个典型差别，也是"安卓味"的来源：
 *
 *  1. **扁平图标按钮，不是描边胶囊**
 *     iOS 常给每个按钮画一圈描边；M3 里工具栏按钮是纯图标 + 状态层
 *     （按下时叠一层主色 8% 的圆角矩形），不画边框。
 *
 *  2. **触控区 48dp**
 *     安卓无障碍底线是 48dp，比 iOS 的 44pt 大一号。手机上点不准多半是没到。
 *
 *  3. **标题用大号字，不跟按钮抢位置**
 *     标题（titleLarge 22sp）单独占一行，工具按钮排下方 ——
 *     这是 M3 TopAppBar 的标准形态，也顺带解决了"标题被截成新.."的问题。
 *
 * 全部用 --md-* 令牌，不影响任何其它页面。
 */
.adventure-header {
  /* M3 顶栏：不用毛玻璃，用实色 surface —— 安卓原生顶栏本来就不透明 */
  background: var(--md-surface-container, var(--panel-2));
  -webkit-backdrop-filter: none;
  backdrop-filter: none;
  box-shadow: var(--md-elev-2);
  padding: 0;
}

.header-left {
  display: flex;
  align-items: center;
  gap: var(--md-space-1, 4px);
  min-width: 0;
  flex: 1 1 auto;
}

/*
 * 标题和模型选择器的宽度分配。
 *
 * 踩过的坑：两边都 `flex: 0 1 auto` 时，空间不够会**按比例一起缩**，
 * 结果标题被挤成"新.."、模型选择器缩成一个圆点 —— 两边都看不清。
 *
 * 现在改成明确的优先级（安卓上顶栏空间就是很紧，必须有取舍顺序）：
 *   1. 返回键 48dp 固定
 *   2. 会话名   可缩，但保底 4 个字
 *   3. 模型选择器 保底 88px（约能显示 5-6 个字符），标题先让位
 *
 * 理由：模型名被截断不要紧 —— 点开能看到完整名字；
 *      而会话名是用户识别"我在哪个对话"的唯一线索，不能没。
 */
.header-left h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 500;
  flex: 0 1 auto;
  min-width: 4em;
  max-width: 40%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 模型选择器：保底能看出是哪个模型，标题先让位 */
.adventure-header .model-picker {
  flex: 1 1 auto;
  min-width: 88px;
  max-width: 46%;
}

/* 顶栏内所有按钮 → M3 图标按钮 */
.adventure-header button {
  flex: 0 0 auto;
  white-space: nowrap;
  min-height: var(--md-touch-sm, 40px);
  padding: 0 var(--md-space-3, 12px);
  border-radius: var(--md-shape-full, 999px);
  background: transparent;
  border: none;
  box-shadow: none;
  color: var(--text-dim);
  font-size: 13px;
  transition: background-color var(--md-dur-short, 120ms) var(--md-ease-standard),
    color var(--md-dur-short, 120ms) var(--md-ease-standard);
}
/* 状态层：按下叠一层主色，安卓的"触感"就是这么来的 */
.adventure-header button:active {
  background: color-mix(in srgb, var(--accent) 16%, transparent);
  color: var(--accent);
}
.adventure-header button.active {
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
}

/* 返回键：M3 里是纯图标按钮 */
.adventure-header .back-btn {
  width: var(--md-touch, 48px);
  height: var(--md-touch, 48px);
  min-height: var(--md-touch, 48px);
  padding: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  border-radius: var(--md-shape-full, 999px);
}

/* 模型选择器也走 M3 状态层 */
.adventure-header .model-picker .mp-trigger {
  min-height: var(--md-touch-sm, 40px);
  border-radius: var(--md-shape-full, 999px);
  background: transparent;
  border: none;
  box-shadow: none;
  color: var(--text-dim);
  padding: 0 var(--md-space-3, 12px);
}
.adventure-header .model-picker .mp-trigger.on {
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
}

/* 顶栏里的按钮别被压扁 */
.adventure-header button {
  flex: 0 0 auto;
  white-space: nowrap;
}
/*
 * 返回键：二级页面回到一级列表的标准出口。
 * 做成圆形，符合手机上"返回"的心理模型 ——
 * 而不是让用户再去点一遍底部「冒险」tab（那是"换栏目"，语义不对）。
 */
.back-btn {
  flex-shrink: 0;
  width: 34px;
  height: 34px;
  padding: 0;
  border-radius: 50%;
  font-size: 20px;
  line-height: 1;
  color: var(--text);
  background: var(--panel-2);
  border: 1px solid var(--border);
}
.back-btn:active {
  transform: scale(0.94);
}
/* 会话标题：可点改名 */
.hdr-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  max-width: 42vw;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: text;
  padding: 2px 6px;
  border-radius: 5px;
}
.hdr-title:hover {
  background: var(--panel-2);
}
.hdr-rename {
  font-size: 15px;
  font-weight: 600;
  max-width: 42vw;
  padding: 2px 6px;
  border-radius: 5px;
}
.session-title {
  font-size: 12px;
  color: var(--text-dim);
  padding: 2px 8px;
  background: var(--panel-2);
  border-radius: 4px;
}
.header-right {
  display: flex;
  gap: 6px;
}
.header-right button {
  font-size: 12px;
  padding: 4px 10px;
}
.adventure-body {
  display: flex;
  flex: 1;
  overflow: hidden;
}
.session-list {
  width: 200px;
  background: var(--panel);
  border-right: 1px solid var(--border);
  padding: 12px;
  overflow-y: auto;
  flex-shrink: 0;
}
.session-list h3 {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--text-dim);
  text-transform: uppercase;
}
.session-item {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 8px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 12px;
  margin-bottom: 2px;
}
.session-item:hover {
  background: var(--panel-2);
}
.session-item.active {
  background: var(--accent);
  color: #0b1220;
}
.session-item.active .session-actions {
  color: rgba(11, 18, 32, 0.7);
}
.session-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-actions {
  font-size: 10px;
  color: var(--text-dim);
}
.tiny {
  padding: 0 4px;
  font-size: 12px;
  line-height: 1.5;
}
.chat-area-wrapper {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.chat-area {
  flex: 1;
  overflow-y: auto;
  /*
   * ★ 横向锁死。美化正则可能吐出整份 CSS/HTML 或超长 <pre> 代码块，
   * 内容宽度会把 flex 容器撑出视口，整页变得能左右拖（用户反馈的「超框」）。
   * 用 clip 而不是 hidden：clip 不创建滚动容器，不会破坏下面手机端
   * input-area 的 position:sticky。
   */
  overflow-x: clip;
  min-width: 0;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
/* ==========================================================================
   未完成生成提示条
   --------------------------------------------------------------------------
   放在正文最上面而不是末尾 —— 用户退出再进来时一眼就能看到
   "上次没生成完"，不用等 store 加载完才突然冒出来。
   配色用警告色，和普通消息明显区分开。
   ========================================================================== */
.unfinished-bar {
  margin: 0 0 12px;
  padding: 12px 14px;
  border-radius: var(--md-shape-l, 16px);
  border: 1px solid rgba(245, 158, 11, 0.45);
  background: rgba(245, 158, 11, 0.12);
  box-shadow: var(--md-elev-1);
}
.ub-head {
  display: flex;
  align-items: center;
  gap: 6px;
}
.ub-icon {
  font-size: 15px;
}
.ub-title {
  font-size: 13.5px;
  font-weight: 600;
  color: #f5a623;
}
.ub-sub {
  margin: 6px 0 10px;
  font-size: 12px;
  color: var(--text-dim);
  line-height: 1.5;
  word-break: break-all;
}
.ub-sub b {
  color: var(--text);
  font-weight: 500;
}
.ub-acts {
  display: flex;
  gap: 8px;
}
.ub-acts .ma {
  min-height: var(--md-touch-sm, 40px);
  padding: 0 14px;
  border-radius: var(--md-shape-full, 999px);
}

.scene-card {
  background: var(--surface-1), var(--panel);
  border: 1px solid var(--border);
  border-top-color: var(--hairline-strong);
  border-radius: var(--radius);
  padding: 15px;
  margin-bottom: 4px;
  box-shadow: var(--shadow-1);
}
.scene-label {
  font-size: 12px;
  color: var(--accent);
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.scene-acts {
  margin-left: auto;
  display: flex;
  gap: 5px;
}
.scene-acts .mini {
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--panel);
  color: var(--text-dim);
  cursor: pointer;
}
.scene-acts .mini:hover {
  border-color: var(--accent);
  color: var(--text);
}
.scene-acts .mini.danger:hover {
  border-color: var(--danger);
  color: var(--danger);
}
.scene-empty {
  font-size: 11.5px;
  color: var(--text-dim);
  line-height: 1.7;
}
.scene-edit {
  width: 100%;
  box-sizing: border-box;
  font-size: 12.5px;
  line-height: 1.7;
  padding: 8px 10px;
  font-family: inherit;
  resize: vertical;
}
.message-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
  /* flex 子项默认 min-width:auto，会被长代码块撑开；锁到父容器宽度 */
  min-width: 0;
  max-width: 100%;
  /*
   * 跳渲染（手机上最有效的一招）。
   *
   * 屏幕外的消息浏览器直接跳过排版和绘制。长会话动辄几十上百轮，
   * 不加这个的话：每滚一下、每来一个流式 token，都要把所有消息重排一遍，
   * 手机上就会明显卡顿、发烫。
   *
   * contain-intrinsic-size 的 `auto` 让浏览器记住每条实测过的高度，
   * 这样滚动条长度不会来回跳、"回到底部"也仍然准。
   */
  content-visibility: auto;
  contain-intrinsic-size: auto 220px;
}
/* ==========================================================================
   Material 3 消息气泡
   --------------------------------------------------------------------------
   M3 和 iOS 在气泡上有三个明显差别，也是"安卓味"的来源：

   1. **圆角不对称，且尾部那一角是直角**
      iOS 气泡四个角一样大（或者统一小圆角）。
      M3 是"远离尾巴的那个角保持大圆角，朝向尾巴的那一角收成小角"，
      视觉上更像安卓。

   2. **描边更轻**
      现在用的 --border 在浅色主题下偏重，气泡看着像"卡片"而不像"消息"。
      换成 M3 的 outline-variant（透明度更低、带一点主色倾向）。

   3. **AI 气泡更宽**
      85% 太窄，正文一多就挤成一条。M3 里次要容器给到 92%，
      AI 侧（更需要读正文）实际可以到 96%。
   ========================================================================== */
.message {
  display: flex;
  flex-direction: column;
  gap: var(--md-space-1, 4px);
  padding: 12px 16px;
  min-width: 0;
  max-width: 100%;
  /* M3 shape-l：16px。尾巴那一角下面单独收小 */
  border-radius: var(--md-shape-l, 16px);
  max-width: 85%;
  /* M3 阴影比 iOS 轻、扩散大 —— 气泡是"浮起"而不是"贴纸" */
  box-shadow: var(--md-elev-1);
  transition: box-shadow var(--md-dur-short, 120ms) var(--md-ease-standard);
}
.message.user {
  /* 自己的发言用"主色容器"，AI 用"次要容器" —— 这是 M3 的语义配色 */
  background: var(--md-primary-container, var(--panel-2));
  border: 1px solid transparent;
  color: var(--md-on-primary-container, var(--text));
  align-self: flex-end;
  /* 尾巴角收小，指向发送方向 */
  border-bottom-right-radius: var(--md-shape-xs, 4px);
}
.message.ai {
  background: var(--md-surface-container-high, var(--panel));
  border: 1px solid var(--md-outline-variant, transparent);
  align-self: flex-start;
  /* AI 侧正文多，给更宽 —— 之前 85% 挤成一条 */
  max-width: 92%;
  border-bottom-left-radius: var(--md-shape-xs, 4px);
}
.msg-role {
  font-size: 11px;
  color: var(--text-dim);
  margin-bottom: 4px;
  display: flex;
  align-items: center;
  gap: 8px;
}
/* 一键生图：挂在每条 AI 消息上，点一下就出图并自动插入 */
.quick-img {
  font-size: 10px;
  padding: 1px 8px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--panel-2);
  color: var(--text-dim);
  cursor: pointer;
  margin-left: auto;
}
.quick-img:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent);
}
.quick-img:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
/* 当前版本没有正文时的提示（历史残留的空版本，给用户一条出路） */
.empty-reply {
  margin: 0;
  font-size: 12px;
  color: var(--text-dim);
  font-style: italic;
}
/*
 * 「重新生成中…」小标签。
 *
 * 重新生成时这一条的正文会被就地换掉，给个明确的字样，
 * 否则用户只看到自己那条回复突然在重写，不知道发生了什么。
 */
.regen-tag {
  font-size: 10px;
  padding: 1px 8px;
  border-radius: 999px;
  border: 1px solid var(--accent);
  color: var(--accent);
  background: transparent;
}
.msg-content {
  font-size: 14px;
  line-height: 1.6;
  /* 连 --dream-parallel-xxx 这种超长无空格标识符也要能断 */
  word-break: break-word;
  overflow-wrap: anywhere;
  min-width: 0;
  max-width: 100%;
  overflow-x: clip;
}
.cursor {
  animation: blink 1s steps(2) infinite;
  color: var(--accent);
}
.auto-image-status {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  margin-top: 6px;
  border-radius: 8px;
  background: var(--panel-2);
  border: 1px solid var(--border);
  font-size: 12px;
  color: var(--text-dim);
}
/*
 * 「正在生成中」提示条。
 *
 * 用户切走再回来时，正文在长但没有任何说明，很容易被当成界面错乱 ——
 * 所以这条必须显眼，并且把「停止」放在手边。
 */
.gen-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 8px 12px;
  margin-bottom: 6px;
  border-radius: 8px;
  background: var(--panel-2);
  border: 1px solid var(--accent);
  font-size: 12px;
  color: var(--text-dim);
}
.gen-banner .spinner {
  animation: blink 1s steps(2) infinite;
}
.gen-text {
  flex: 1 1 auto;
  min-width: 0;
}
.gen-hint {
  display: block;
  margin-top: 2px;
  font-style: normal;
  font-size: 11px;
  color: var(--accent);
  opacity: 0.85;
}
.gen-stop {
  flex-shrink: 0;
  padding: 5px 12px;
  border-radius: 999px;
  border: 1px solid var(--danger, #f87171);
  background: transparent;
  color: var(--danger, #f87171);
  font-size: 12px;
  cursor: pointer;
}
.gen-stop:hover {
  background: rgba(248, 113, 113, 0.12);
}
.auto-image-error {
  margin-top: 6px;
  padding: 8px 12px;
  border-radius: 8px;
  background: rgba(248, 113, 113, 0.1);
  border: 1px solid rgba(248, 113, 113, 0.3);
  color: var(--danger);
  font-size: 12px;
  word-break: break-word;
}
.header-right button.active {
  border-color: var(--accent);
  color: var(--accent);
  font-weight: 600;
}
@keyframes blink {
  50% { opacity: 0; }
}
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  flex: 1;
  color: var(--text-dim);
  text-align: center;
  padding: 40px;
}
.empty-state p {
  margin: 4px 0;
  font-size: 13px;
}
.input-area {
  display: flex;
  flex-direction: column;
  gap: var(--md-space-2, 8px);
  padding: var(--md-space-3, 12px) var(--md-space-4, 16px);
  /*
   * Material 3 的输入区：**实色 surface + 一条极细的 outline**
   *
   * 原来用毛玻璃（blur 14px）。毛玻璃在安卓 WebView 上很贵 ——
   * 每次滚动都要重新采样背景，是手机上掉帧的常见来源。
   * M3 规范里 bottom sheet / 输入区本来也是实色的，改完更跟手。
   */
  background: var(--md-surface-container, var(--panel-2));
  -webkit-backdrop-filter: none;
  backdrop-filter: none;
  border-top: 1px solid var(--md-outline-variant, var(--border));
  box-shadow: var(--md-elev-2);
  /* 输入区吸底：AI 正文在上面自然滚动，指令区始终在下方 */
  position: sticky;
  bottom: 0;
  z-index: 20;
  padding-bottom: calc(12px + env(safe-area-inset-bottom, 0px));
}

/*
 * 输入区折叠开关。
 *
 * 平时是一条只有十几像素高的细栏（中间一根小把手 + 「收起」），
 * 点一下就把 textarea / 发送按钮整行收掉 —— 回看剧情时正文立刻多出一大截。
 * 收起状态用低调的样式，别抢正文的注意力。
 */
.input-toggle {
  align-self: center;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 1px 12px;
  height: 16px;
  min-height: 0;
  min-width: 0;
  font-size: 10.5px;
  line-height: 1;
  color: var(--text-dim);
  background: transparent !important;
  border: none;
  box-shadow: none;
  cursor: pointer;
}
.input-toggle:hover {
  color: var(--accent);
}
/* 中间那根"把手"，提示这里可以拖动/收起 */
.it-grip {
  width: 34px;
  height: 3px;
  border-radius: 2px;
  background: color-mix(in srgb, var(--text-dim) 45%, transparent);
}
.input-toggle:hover .it-grip {
  background: var(--accent);
}
/* 收起时把整条输入区压扁，只留开关那一行 */
.input-area.collapsed {
  padding-top: 4px;
  padding-bottom: calc(4px + env(safe-area-inset-bottom, 0px));
  gap: 0;
}

/* 快捷条下面那一行：生图按钮 + 输入框 + 发送 */
.input-row {
  display: flex;
  gap: var(--md-space-2, 8px);
  align-items: flex-end;
}

/*
 * Material 3 Filled Text Field
 *
 * M3 的输入框是"填充式"：有底色、圆角 4px（shape-xs），
 * **没有描边**，聚焦时才浮起 outline。
 * 之前用的是普通 input 的默认样式（描边 + 直角），
 * 和安卓其它控件放一起会显得很"网页"。
 */
.input-area textarea {
  flex: 1;
  resize: none;
  /* M3 触控高度 56dp（Filled Text Field 的标准高度），比 40 大一圈 */
  min-height: 48px;
  max-height: 132px;
  padding: 14px 16px;
  border-radius: var(--md-shape-s, 8px) var(--md-shape-s, 8px)
    var(--md-shape-xs, 4px) var(--md-shape-xs, 4px);
  background: color-mix(in srgb, var(--text) 5%, transparent);
  border: 1px solid transparent;
  transition: border-color var(--md-dur-short, 120ms) var(--md-ease-standard),
    background-color var(--md-dur-short, 120ms) var(--md-ease-standard);
}
.input-area textarea:focus {
  outline: none;
  border-color: var(--accent);
  background: color-mix(in srgb, var(--text) 7%, transparent);
}

/* 发送按钮：M3 Filled Button —— 全圆角胶囊 + 主色容器 */
.send-btn {
  align-self: flex-end;
  height: 48px;
  min-width: 96px;
  padding: 0 var(--md-space-6, 24px);
  border-radius: var(--md-shape-full, 999px);
  background: var(--accent);
  color: var(--surface-1, #fff);
  border: none;
  box-shadow: none;
  font-weight: 500;
  transition: opacity var(--md-dur-short, 120ms) var(--md-ease-standard),
    transform var(--md-dur-short, 120ms) var(--md-ease-standard);
}
.send-btn:active:not(:disabled) {
  transform: scale(0.97);
  opacity: 0.88;
}
/*
 * 禁用态：不要用 opacity —— 那会把主色也一起变灰，
 * 看着像"按钮坏了"。M3 的做法是降到"on-surface 12%"这一档。
 */
.send-btn:disabled {
  background: color-mix(in srgb, var(--text) 12%, transparent);
  color: color-mix(in srgb, var(--text) 38%, transparent);
  box-shadow: none;
}

/* 生图按钮：M3 Icon Button 48dp 圆形状态层 */
.img-gen-btn {
  align-self: flex-end;
  height: 48px;
  width: 48px;
  min-width: 48px;
  padding: 0;
  font-size: 20px;
  border-radius: var(--md-shape-full, 999px);
  background: transparent;
  border: none;
  color: var(--text-dim);
  transition: background-color var(--md-dur-short, 120ms) var(--md-ease-standard);
}
.img-gen-btn:active {
  background: color-mix(in srgb, var(--accent) 16%, transparent);
  color: var(--accent);
}
.asset-aside {
  width: 280px;
  background: var(--panel);
  border-left: 1px solid var(--border);
  padding: 12px;
  overflow-y: auto;
  flex-shrink: 0;
}
.error-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 16px;
  background: rgba(248, 113, 113, 0.15);
  border-top: 1px solid rgba(248, 113, 113, 0.3);
  color: var(--danger);
  font-size: 12px;
  flex-shrink: 0;
}
.error-bar button {
  padding: 0 6px;
  background: none;
  border: none;
  color: var(--danger);
  cursor: pointer;
}
.loading {
  padding: 40px;
  text-align: center;
  color: var(--text-dim);
}

/* ---------------------------------------------------------------------------
 * 手机端布局
 *
 * 必须写在组件自己的 scoped 块里：scoped 编译成 `.adventure-page[data-v-xxx]`，
 * 特异性高于全局 main.css 里的同名规则，放全局会被压掉。
 * --------------------------------------------------------------------------- */
@media (max-width: 768px) {
  .adventure-page {
    /*
     * 撑满至少一屏：内容短时也让输入框被推到视口底部，不浮在中间。
     * 用 dvh 而不是百分比（flex 子项的百分比 min-height 不一定解析）。
     * 减去顶部吸顶导航的高度（约 53px，含安全区）。
     * 内容长时 min-height 允许自然撑高，文档照常滚。
     */
    height: auto;
    min-height: calc(100dvh - var(--tabbar-h));
    overflow: visible;
  }

  /*
   * 这三层在桌面端是 overflow:hidden 的内滚容器，
   * 手机端必须全部放开成 visible —— 否则 .input-area 的
   * position:sticky;bottom:0 会被困在最内层容器里，
   * 粘在"容器内容底部"而不是"视口底部"，表现为输入框跑到屏幕中间。
   * （.chat-area-wrapper 之前漏了没改，是"对话框在中间"的直接原因）
   */
  .adventure-body,
  .chat-area-wrapper,
  .chat-area {
    overflow: visible;
    min-height: 0;
  }
  /* 手机上整页由文档滚动，chat-area 不再自己内滚；
     但横向仍要 clip，否则长代码块会把整页撑宽、能左右拖 */
  .chat-area {
    overflow-y: visible;
    overflow-x: clip;
  }

  /* 输入区吸底：手机上键盘弹出/手势条遮挡时，
     按钮不会跑到屏幕外点不到 */
  .input-area {
    position: sticky;
    /*
     * 贴在**底部标签栏之上**，不是视口底部。
     * 手机导航现在固定在底部（58px），
     * 写 bottom:0 的话输入框会被标签栏整个盖住。
     */
    bottom: calc(var(--tabbar-h) + env(safe-area-inset-bottom, 0px));

    z-index: 20;
    padding: 8px 10px;
    padding-bottom: calc(8px + env(safe-area-inset-bottom, 0px));
    background: var(--glass);
    -webkit-backdrop-filter: blur(16px) saturate(1.3);
    backdrop-filter: blur(16px) saturate(1.3);
    border-top: 1px solid var(--border);
    box-shadow: var(--shadow-up);
  }

  /*
   * 手机上输入区改成纵向堆叠。
   *
   * 390px 宽的屏幕上，参考图条（缩略图 + 强度滑杆 + 清除）一占，
   * 输入框只剩 180 出头 —— 打字时一行放不下几个字，非常难用。
   * 现在输入框独占一行（整宽），按钮另起一行。
   * （参考图已经搬到顶栏「⋯」里，不再占这里的高度。）
   */
  .input-row {
    flex-wrap: wrap;
  }
  .input-area textarea {
    flex: 1 1 100%;
    order: 2;
    min-height: 48px;
    /* 15px 起步：更小的字号在手机上看着费劲，也容易被系统自动放大 */
    font-size: 15px;
    line-height: 1.5;
    /* 大圆角胶囊：聊天输入框的标准形态 */
    border-radius: var(--radius-lg);
    padding: 12px 15px;
  }
  .img-gen-btn {
    order: 3;
    width: 46px;
    height: 46px;
    border-radius: var(--radius);
  }
  .send-btn {
    order: 4;
    /* 发送按钮占满剩余宽度，拇指够得着、也好点 */
    flex: 1 1 auto;
    height: 46px;
    font-size: 14px;
  }

  /* ---- 右侧资产/摘要面板：手机端改成底部抽屉 ----
   * 桌面端是右侧 280px 固定栏；手机屏窄，没法并排，
   * 改成从底部滑上来、占大半屏的抽屉，点遮罩或 ✕ 关闭。
   * aside 本身是 position:fixed，会脱离 .adventure-body 的
   * overflow:hidden，不受影响。 */
  .asset-aside {
    position: fixed;
    /* 左右各让出 10px（原来 0）：抽屉不再贴着屏幕边，
       内容也不顶到全面屏的圆角/手势区上。 */
    left: 10px;
    right: 10px;
    /*
     * 停在**底部标签栏之上**，不是屏幕最底。
     * 写 bottom:0 的话抽屉会和固定标签栏重叠，最后几行内容被压住看不见
     * —— 资产面板内容长（模型/配置/角色卡/预设/世界书/DNA/正则），
     * 正好是最底下那几项被挡，属于必须修的问题。
     */
    bottom: var(--tabbar-h);
    width: auto;
    max-height: 68vh;
    border: 1px solid var(--border);
    border-bottom: none;
    border-radius: var(--radius-lg) var(--radius-lg) 0 0;
    background: var(--panel);
    box-shadow: var(--shadow-3);
    z-index: 40;
    /* 抽屉内容自己滚，不撑长整页 */
    overflow-y: auto;
    /* 左右内边距：里面的小节不再贴边 */
    padding: 0 14px calc(14px + env(safe-area-inset-bottom, 0px));
  }

  /*
   * 会话列表同样是抽屉。
   *
   * 之前它被全局 main.css 的 `.session-list { display:none }` 直接藏掉了，
   * 而头部"会话列表"按钮只是切 v-if —— 元素是出来了但看不见，
   * 手机上表现为**点了完全没反应**，没法切换会话。
   * （资产面板当初是同一个毛病，这里把会话列表也补上。）
   */
  .session-list {
    position: fixed;
    left: 10px;
    right: 10px;
    bottom: var(--tabbar-h);
    width: auto;
    max-height: 62vh;
    border: 1px solid var(--border);
    border-bottom: none;
    border-radius: var(--radius-lg) var(--radius-lg) 0 0;
    box-shadow: var(--shadow-3);
    z-index: 40;
    overflow-y: auto;
    /* 抽屉左右内边距 + 滚到底也能看全最后一行（不被底部标签栏压住） */
    padding: 0 14px calc(14px + env(safe-area-inset-bottom, 0px));
  }
  /* 抽屉里每一行做成手指好点的高度 */
  .session-item {
    padding: 12px 10px;
    font-size: 13.5px;
  }
}

/* 桌面端：头部「⋯」不显示，所有按钮平铺 */
/* 顶栏第一行：返回 / 标题 / 模型 在左，生图 / 摘要 / ⋯ 在右 */
.ah-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  min-width: 0;
}

.more-toggle {
  display: none;
}
.extra-btns {
  display: flex;
  gap: 6px;
}

/* 桌面端：遮罩和抽屉手柄都不显示（只手机用） */
.sheet-backdrop,
.sheet-handle {
  display: none;
}

/* 手机端：遮罩 + 抽屉顶部手柄 */
@media (max-width: 768px) {
  .sheet-backdrop {
    display: block;
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.5);
    z-index: 35;
    -webkit-backdrop-filter: blur(1px);
    backdrop-filter: blur(1px);
  }
  /*
   * 头部按钮收进「⋯」。
   * 手机上原来一排 6 个按钮要折成 2~3 行，白白吃掉一百多像素高度，
   * 正文区被压得很小。现在默认只留「⋯ + 新冒险 + 摘要 + 资产」一行，
   * 点「⋯」才展开其余。
   */
  /* 顶栏第一行：返回 / 标题 / 模型 在左，生图 / 摘要 / ⋯ 在右 */
.ah-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  min-width: 0;
}

.more-toggle {
    display: inline-block;
  }
  .extra-btns {
    display: none;
    /* 展开后独占一行，横向铺开方便点 */
    width: 100%;
    order: 9;
    flex-wrap: wrap;
    padding-top: 6px;
  }
  .extra-btns.open {
    display: flex;
  }
  .sheet-handle {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 8px 14px 6px;
    position: sticky;
    top: 0;
    background: var(--panel);
    border-radius: 14px 14px 0 0;
    z-index: 1;
  }
  .sheet-handle .grabber {
    width: 38px;
    height: 4px;
    border-radius: 2px;
    background: var(--text-dim);
    opacity: 0.5;
  }
  .sheet-handle .sheet-close {
    border: none;
    background: var(--panel-2);
    color: var(--text-dim);
    width: 26px;
    height: 26px;
    padding: 0;
    border-radius: 50%;
    font-size: 13px;
    line-height: 1;
  }
}
/* 摘要面板旁的策略设置 */
.summary-aside {
  display: flex;
  flex-direction: column;
}
.sum-settings {
  display: flex;
  flex-direction: column;
  gap: 7px;
  padding: 10px 12px 14px;
  border-top: 1px solid var(--border);
  font-size: 11.5px;
}
.ss-row {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text-dim);
}
.ss-row span { white-space: nowrap; }
.ss-row input[type='number'] {
  width: 72px;
  font-size: 11.5px;
  padding: 3px 6px;
}
.ss-row select { font-size: 11.5px; padding: 3px 6px; }
.ss-row input[type='checkbox'] { width: auto; }</style>
