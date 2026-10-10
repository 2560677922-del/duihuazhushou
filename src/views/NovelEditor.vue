<script setup lang="ts">
// 小说编辑器
//
// 交互方式和冒险模式基本一致（对话式、输入吸底、每条消息可操作），
// 区别在于这里的每一轮都会**存进章节**，可以随时转成正式正文。
//
// 为什么不做成"一个大文本框 + 侧边 AI 面板"：
//   AI 写作真正需要的操作粒度是"逐轮" —— 改这一轮、重生成这一轮、
//   标注这一轮、从这一轮分支。大文本框做不到这些，改一次就把上下文打乱了。
//   而正稿仍然保留在 content 里，可以通读、导出、手动润色。
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue';
import { useRouter } from 'vue-router';
import { useAssetsStore } from '../stores/assets';
import { useApiConfigStore } from '../stores/apiConfig';
import { useNovelStore, type NovelSettings } from '../stores/novel';
import { assemblePrompt } from '../engine/assembler';
import { streamChatCompletion } from '../engine/apiClient';
import { OpenAIPreset, WorldBook } from 'parsecard';
import type { ChatMessage } from '../engine/types';
import {
  sampleCharacterRaw,
  samplePresetRaw,
  sampleWorldbookRaw,
  sampleRegexRaw,
} from '../engine/sampleData';
import AssetPanel from '../components/AssetPanel.vue';
import NovelReader from '../components/NovelReader.vue';
import ModelPicker from '../components/ModelPicker.vue';
import ContentWithImages from '../components/ContentWithImages.vue';
import MessageActions from '../components/MessageActions.vue';
import ImageQuickBar from '../components/ImageQuickBar.vue';
import ImageGenModal from '../components/ImageGenModal.vue';
import VersionSwitcher from '../components/VersionSwitcher.vue';
import ScrollFabs from '../components/ScrollFabs.vue';
import SummaryPanel from '../components/SummaryPanel.vue';
import { useImageStore } from '../stores/image';
import { useAutoImage } from '../composables/useAutoImage';
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

const assets = useAssetsStore();
const api = useApiConfigStore();
const novel = useNovelStore();
const imageStore = useImageStore();
const storageSettings = useStorageSettingsStore();
const autoImage = useAutoImage();
const { pending: autoImgPending, status: autoImgStatus, error: autoImgError } = autoImage;

const loaded = ref(false);
const showImageGen = ref(false);
/*
 * 从列表点进来时带的是章节 id（路由 /novel/:id）。
 * 不带也能用：会沿用上次选中的章节。
 */
const props = defineProps<{ id?: string }>();
const router = useRouter();

/** 返回章节列表（一级界面） */
function goBack() {
  void router.push('/novel');
}

/**
 * 导出这一章的对话记录（逐轮 user / ai）。
 *
 * 原文还是"你看到的样子"由设置里的「导出用原文」决定
 * （见 lib/chatLog.ts 的说明）。
 * 注意导的是**对话轮次**，不是最终正文 —— 正文的导出在沉浸阅读器里
 * （那里是"成书"视角，按章节排版）。
 */
function exportChat() {
  const c = currentChapter.value;
  if (!c) return;
  const entries: { role: 'user' | 'ai'; text: string }[] = [];
  for (const m of c.messages || []) {
    if (m.user) entries.push({ role: 'user', text: m.user });
    if (m.ai) entries.push({ role: 'ai', text: m.ai });
  }
  const name = `${novel.currentProject?.name || '小说'} - ${c.title || '未命名章节'}`;
  exportChatLog(name, entries);
}

const showAssetPanel = ref(true);
const showSummary = ref(false);
const showChapterList = ref(false);

/**
 * 手机上三块面板（章节列表 / 摘要 / 资产）都是底部抽屉，同时开会叠在一起，
 * 所以做成互斥。桌面端是并排三栏，保持各自独立开关。
 */
function isMobile() {
  return typeof window !== 'undefined' && window.innerWidth <= 768;
}
function togglePanel(which: 'chapters' | 'summary' | 'assets') {
  if (!isMobile()) {
    if (which === 'chapters') showChapterList.value = !showChapterList.value;
    else if (which === 'summary') showSummary.value = !showSummary.value;
    else showAssetPanel.value = !showAssetPanel.value;
    return;
  }
  const nextChapters = which === 'chapters' ? !showChapterList.value : false;
  const nextSummary = which === 'summary' ? !showSummary.value : false;
  const nextAssets = which === 'assets' ? !showAssetPanel.value : false;
  showChapterList.value = nextChapters;
  showSummary.value = nextSummary;
  showAssetPanel.value = nextAssets;
}
function closeAllPanels() {
  showChapterList.value = false;
  showSummary.value = false;
  showAssetPanel.value = false;
}
const showSettings = ref(false);
/** 沉浸式阅读（全屏层） */
const showReader = ref(false);

/**
 * 顶栏第二行的「⋯」是否展开。
 *
 * 折叠规则（用户定的）：第一行只留**天天都在用**的三样 ——
 * 返回 / 当前模型 / 生图 / 摘要；其余（章节切换、章节管理、资产、
 * 阅读、写作设置）全部收进这里，展开才出现。
 */
const headerMore = ref(false);

/**
 * 有没有可读的内容 —— 决定"📖 阅读"按钮是否出现。
 *
 * 一章都没写时点进去只能看到空页，容易让人以为坏了。
 * 所以要求"至少一章有正文，或有 AI 生成的对话内容"。
 * 只看 content 不够：有人只在对话区写、还没点"追加到正文"。
 */
const hasAnythingToRead = computed(() =>
  (novel.chapters || []).some(
    (c) =>
      (c.content && c.content.trim().length > 0) ||
      (c.messages || []).some((m) => (m.ai || '').trim().length > 0),
  ),
);

const streaming = ref(false);
const streamOutput = ref('');
const streamUser = ref('');
const errorMessage = ref('');
const copyingContent = ref(false);

const userInput = ref('');
/** 重新生成时覆盖的目标消息 */
const regenerateTargetId = ref<string | null>(null);
/** 正在重生成的那条消息 id（用于界面上做标记） */
const busyMessageId = ref<string | null>(null);

const regenPrompt = ref('');

/**
 * 对话里那张图上的「🔄 重新生图」：**直接把原提示词发给生图接口**，
 * 不弹配置面板、也不经过 LLM 改写。
 *
 * 提示词是现成的（附件里存着完整版），重生成要的就是"同一条提示词换个种子
 * 再出一次"。以前拿截断后的 description 去重出，画面完全不对。
 */
async function regenImageInPlace(messageId: string, att?: ImageAttachment) {
  if (!att) return;
  if (!currentChapter.value || quickImgBusy.value) return;
  const chapterId = currentChapter.value.id;
  // 让这条消息进入"忙"状态，重新出图期间界面上有反馈
  busyMessageId.value = messageId;
  try {
    const fresh = await quickImg.regenerateFromAttachment(att, '小说章节');
    if (!fresh) return;
    /*
     * 旧图保留成历史版本，不直接丢掉。
     * 抽卡有随机性，新的一张未必比旧的好 —— 点开大图可以来回切。
     */
    const merged = pushImageVersion(att, {
      id: `${att.id}-v${Date.now()}`,
      dataUrl: fresh.dataUrl,
      remotePath: fresh.remotePath,
      remoteUrl: fresh.remoteUrl,
      storage: fresh.storage,
      prompt: fresh.prompt ?? att.prompt,
      configName: fresh.configName,
      createdAt: Date.now(),
    });
    await novel.upsertChapterImage(chapterId, merged);
  } finally {
    busyMessageId.value = null;
  }
}

/** 在大图里切到另一版 */
async function switchChapterImageVersion(
  messageId: string,
  attachmentId: string,
  versionId: string,
) {
  const ch = currentChapter.value;
  if (!ch) return;
  const msg = (ch.messages || []).find((m) => m.id === messageId);
  const att = msg?.images?.find((x) => x.id === attachmentId);
  if (!att) return;
  await novel.upsertChapterImage(ch.id, switchToVersion(att, versionId));
}

const currentChapter = computed(() => novel.currentChapter);
const messages = computed(() => currentChapter.value?.messages ?? []);
const settings = computed<NovelSettings>(() => novel.settingsOf());

const charCount = computed(() => (currentChapter.value?.content ?? '').length);

onMounted(async () => {
  try {
    await Promise.all([
      assets.load(),
      api.load(),
      novel.load(),
      imageStore.load(),
      // 同冒险页：不加载它，生图就不知道要往 GitHub 传（只留本地）
      storageSettings.load(),
    ]);
    if (assets.characters.length === 0) await loadSampleAssets();
    restoreDraft();
  } catch (e) {
    console.error('加载失败:', e);
  }
  loaded.value = true;

  /*
   * 选中要打开的章节。
   *
   * 路由是 /novel/:id（从列表点进来）。带 id 就切过去；
   * 没带就沿用 store 里上次选中的那个（novel.load() 保证它一定有效）。
   */
  if (props.id && novel.chapters.some((c) => c.id === props.id)) {
    selectChapter(props.id);
  }

  // 手机端一进页面不该是"抽屉开着 + 遮罩盖住正文"（见 Adventure 同名说明）
  if (isMobile()) closeAllPanels();
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

// ---------------- 显示时的正则 ----------------
//
// 组装提示词那遍正则管的是「发给大模型什么」；界面显示的是原始输出，
// 所以「仅显示」类规则（剥思维链标签、折叠 details）需要在这里再跑一遍。
//
// 性能提醒：模板里是 `:content="displayText(m.ai)"`，Vue 每次重渲染都会
// 重新调用这个函数，而它内部要跑 cleanOutput + 全部正则脚本。
// 几十轮对话 = 每次重渲染跑几十遍正则引擎；流式生成时每秒重渲染好几次，
// 手机上必然卡。所以这里用「原文 + 影响结果的状态」做键做记忆化。
const displayCache = new Map<string, string>();
const DISPLAY_CACHE_MAX = 400;
/** 影响显示结果的全部外部状态（正则增删改、思维链设置）拼成的版本串 */
const displayKey = computed(() =>
  [
    storageSettings.hideReasoning ? '1' : '0',
    storageSettings.reasoningCutMarker || '',
    assets.currentRegexGroupIds.join(','),
    assets.regexGroups
      .map((g: any) => `${g._uid}:${(g.scripts || []).length}:${g.enabled === false ? 0 : 1}`)
      .join('|'),
  ].join('~'),
);

function displayText(text: string): string {
  if (!text) return '';
  const key = displayKey.value + '\u0000' + text;
  const hit = displayCache.get(key);
  if (hit !== undefined) return hit;

  try {
    // 先剥思维链，再跑「仅显示」类正则。
    // 推理模型会把"草稿纸"和正文一起返回，用户不该看到那段分析。
    let out = text;
    if (storageSettings.hideReasoning) {
      out = cleanOutput(out, storageSettings.reasoningCutMarker);
    }
    const scripts = assets.getCurrentRegexScripts();
    if (scripts.length) {
      const { messages: msgs } = runRegex(
        [{ role: 'assistant', content: out } as any],
        scripts,
        { placement: { ai: true, reasoning: true }, forDisplay: true },
      );
      out = msgs[0]?.content ?? out;
    }
    if (displayCache.size >= DISPLAY_CACHE_MAX) displayCache.clear();
    displayCache.set(key, out);
    return out;
  } catch {
    return text;
  }
}

// ---------------- 章节 ----------------

function selectChapter(id: string) {
  novel.currentChapterId = id;
  showChapterList.value = false;
  streamOutput.value = '';
  restoreDraft();
  void novel.persistChapters();
}

async function newChapter() {
  await novel.addChapter(`第${novel.chapters.length + 1}章`);
  restoreDraft();
}

async function deleteChapter(id: string) {
  if (!window.confirm('删除这一章？里面的对话和正文都会一起删掉。')) return;
  await novel.removeChapter(id);
  restoreDraft();
}

/** 把消息拼成正文，覆盖或追加到 content */
async function applyToContent(mode: 'replace' | 'append', chapterId: string) {
  const text = novel.composeFromMessages(chapterId);
  if (!text.trim()) return;
  if (mode === 'replace') {
    if (!window.confirm('用对话内容覆盖当前正文？原正文会被替换掉。')) return;
    await novel.updateChapter(chapterId, { content: text });
  } else {
    const c = novel.chapters.find((x) => x.id === chapterId);
    const base = c?.content?.trim() ? c.content.trim() + '\n\n' : '';
    await novel.updateChapter(chapterId, { content: base + text });
  }
  copyingContent.value = false;
}

// ---------------- 发送 ----------------

/** 把消息列表转成送给模型的对话历史 */
function buildHistory(chapterId: string, beforeMessageId?: string | null): ChatMessage[] {
  const c = novel.chapters.find((x) => x.id === chapterId);
  if (!c) return [];
  const out: ChatMessage[] = [];
  const list = c.messages ?? [];
  const stopAt = beforeMessageId ? list.findIndex((m) => m.id === beforeMessageId) : list.length;
  list.slice(0, stopAt < 0 ? list.length : stopAt).forEach((m) => {
    if (m.user?.trim()) out.push({ role: 'user', content: m.user });
    if (m.ai?.trim()) out.push({ role: 'assistant', content: m.ai });
  });
  // 重点标注的消息额外置顶强调一次，避免被 token 裁剪丢掉
  const pinned = list.filter((m) => m.pinned && m.ai?.trim());
  if (pinned.length) {
    out.unshift({
      role: 'system',
      content:
        '【以下是被标记为重点的既有内容，必须与后文保持一致】\n\n' +
        pinned.map((m) => m.ai.trim()).join('\n\n'),
    });
  }
  // 摘要作为背景设定附在最前
  const latestSummary = c.summaries?.[0];
  if (latestSummary?.text?.trim()) {
    out.unshift({
      role: 'system',
      content: `【前情摘要】\n${latestSummary.text.trim()}`,
    });
  }
  return out;
}

async function sendMessage() {
  const text = userInput.value.trim();
  if (!text || !currentChapter.value || streaming.value) return;

  const chapterId = currentChapter.value.id;
  streaming.value = true;
  streamOutput.value = '';
  streamUser.value = text;
  errorMessage.value = '';
  userInput.value = '';

  const replaceId = regenerateTargetId.value;

  try {
    const presetRaw = assets.getCurrentPreset();
    if (!presetRaw) {
      errorMessage.value = '请先选择一个预设（在「资产」页导入并启用）';
      return;
    }
    const cfg = api.currentConfig;
    if (!cfg?.apiKey) {
      errorMessage.value = '请先配置 API（在设置页）';
      return;
    }

    const preset = OpenAIPreset.fromJSON(presetRaw);
    const character = assets.getCurrentCharacter();
    const worldBooks = assets.getCurrentWorldBooks().map((w) =>
      WorldBook.fromJSON(w).toStandaloneJSON(),
    );

    const historyWithUser: ChatMessage[] = [
      ...buildHistory(chapterId, replaceId),
      { role: 'user', content: text },
    ];

    const result = assemblePrompt({
      preset,
      characterCard: character,
      worldBooks,
      /*
       * 正则是否作用在"发给模型的上下文"上，由用户选
       * （设置 → 数据与上下文）。打开"原文直发"就传空数组 ——
       * 这比在引擎里加分支更直白：没脚本 = 不跑正则。
       */
      regexScripts: storageSettings.llmContextRaw ? [] : assets.getCurrentRegexScripts(),
      chatHistory: historyWithUser,
      contextWindowTokens: cfg.contextWindow,
      userLookbackTokens: cfg.userLookbackTokens,
    });

    if (imageStore.autoImage.enabled) {
      const msgs = result.finalMessages;
      const tail = msgs[msgs.length - 1];
      if (tail) {
        msgs[msgs.length - 1] = {
          ...tail,
          content: tail.content + imageMarkerInstruction(true),
        };
      }
    }

    await streamChatCompletion({ ...cfg }, result.finalMessages, {
      onToken: (d) => {
        streamOutput.value += d;
        scheduleDraftSave(chapterId, streamOutput.value, 'chat');
      },
      onDone: async () => {
        flushDraftSave(chapterId, streamOutput.value, 'chat');
        const raw = streamOutput.value;
        if (!raw.trim()) return;

        let msgId: string | undefined;
        if (replaceId) {
          await novel.replaceMessageAi(chapterId, replaceId, raw);
          msgId = replaceId;
        } else {
          const m = await novel.addMessage(chapterId, text, raw);
          msgId = m?.id;
        }
        await novel.clearAiDraft(chapterId);

        // 消息已落库，立刻清掉流式文本，避免它和正式消息同帧渲染（看起来像重复）
        streamOutput.value = '';

        if (imageStore.autoImage.enabled && msgId) {
          await autoImage.processMarkers(
            raw,
            (att) => novel.upsertChapterImage(chapterId, att),
            '小说章节',
          );
        }

        // 自动摘要：到达阈值就后台生成一条，不阻塞界面
        void maybeAutoSummary(chapterId);
      },
    });
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : String(e);
    flushDraftSave(chapterId, streamOutput.value, 'chat');
  } finally {
    streaming.value = false;
    regenerateTargetId.value = null;
    streamUser.value = '';
    busyMessageId.value = null;
    /*
     * 清掉流式文本：模板里流式块的条件是 `streaming || streamOutput`，
     * 留着它会让**同一条回复显示两遍**（消息已落库，流式块还在渲染）。
     * onDone 已被 await，所以这里是在提交之后清理，不会丢内容。
     */
    streamOutput.value = '';
  }
}

async function regenerateMessage(m: { id: string; user: string }) {
  if (streaming.value || !currentChapter.value) return;
  const ch = currentChapter.value;
  const idx = (ch.messages ?? []).findIndex((x) => x.id === m.id);
  if (idx < 0) return;

  // 回溯：这条之后的内容都建立在旧回答上，重新生成前先删掉。
  // 不再弹确认框 —— 点一下就直接重来（用户的明确要求）。
  // 想保留旧线的话用「分支」，不必每次都拦一道。
  const dropped = (ch.messages?.length ?? 0) - idx - 1;
  if (dropped > 0) {
    for (const msg of [...(ch.messages ?? [])].slice(idx + 1)) {
      await novel.removeMessage(ch.id, msg.id);
    }
  }

  regenerateTargetId.value = m.id;
  busyMessageId.value = m.id;
  userInput.value = m.user || '继续';
  await sendMessage();
}

// ---------------- 一键生图 ----------------
// ref 必须解构出来：模板不会自动解包普通对象里的 ref，
// quickImg.busyFor 永远是 ref 对象本身（恒为真），
// 结果就是按钮一直显示"生成中…"、并且点了没反应。
const quickImg = useQuickImage();
const { busyFor: quickImgBusy } = quickImg;

/**
 * 给某一轮直接出一张配图并插进这条消息。
 * 点了就生成、自动插入，不需要确认；不满意可删除或再点一次换一张。
 */
async function quickImageFor(m: { id: string; ai: string }) {
  if (quickImgBusy.value || !currentChapter.value) return;
  const att = await quickImg.generateFor(m.ai, '小说章节', 0);
  if (!att) return;

  // 除了挂附件，还要在正文补一个 [[IMG: 描述]] 标记 ——
  // 图片的显示位置由正文里的标记决定，只挂附件看不到。
  const marker = `\n\n[[IMG: ${att.description}]]`;
  const chapterId = currentChapter.value.id;
  await novel.updateMessage(chapterId, m.id, { ai: m.ai + marker });
  await novel.upsertChapterImage(chapterId, att);
}

/**
 * 用最近一轮内容直接出一张图并插进去（输入框左边的画板按钮）。
 * 不改输入框文字、也不发对话请求 —— 纯粹出图。
 */
async function quickImageFromLast() {
  if (quickImgBusy.value || !currentChapter.value) return;
  const list = currentChapter.value.messages ?? [];
  const last = [...list].reverse().find((m) => m.ai?.trim());
  const source = userInput.value.trim() || last?.ai || currentChapter.value.content || '';
  if (!source.trim()) {
    errorMessage.value = '还没有可用来生成画面的内容，先写点东西';
    return;
  }
  const att = await quickImg.generateFor(source, '小说章节', 0);
  if (!att) return;
  if (last) {
    const marker = `\n\n[[IMG: ${att.description}]]`;
    await novel.updateMessage(currentChapter.value.id, last.id, { ai: last.ai + marker });
    await novel.upsertChapterImage(currentChapter.value.id, att);
  }
}

async function branchAt(messageId: string) {
  const c = await novel.branchChapter(novel.currentChapterId!, messageId);
  if (c) {
    streamOutput.value = '';
    restoreDraft();
  }
}

// ---------------- 草稿（生成中的内容） ----------------

let draftTimer: ReturnType<typeof setTimeout> | null = null;
let draftPending: { chapterId: string; text: string; action: string } | null = null;

function scheduleDraftSave(chapterId: string, text: string, action: string) {
  draftPending = { chapterId, text, action };
  if (draftTimer) return;
  draftTimer = setTimeout(() => {
    draftTimer = null;
    if (draftPending) {
      void novel.setAiDraft(draftPending.chapterId, draftPending.text, draftPending.action);
      draftPending = null;
    }
  }, 1000);
}

function flushDraftSave(chapterId: string, text: string, action: string) {
  if (draftTimer) {
    clearTimeout(draftTimer);
    draftTimer = null;
  }
  draftPending = null;
  void novel.setAiDraft(chapterId, text, action);
}

function onHide() {
  if (document.visibilityState === 'hidden' && draftPending) {
    flushDraftSave(draftPending.chapterId, draftPending.text, draftPending.action);
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

/** 恢复上次没落进消息的生成结果 */
function restoreDraft() {
  const d = currentChapter.value?.aiDraft;
  if (d?.text && !streaming.value) {
    streamOutput.value = '';
  }
}

// ---------------- 摘要 ----------------

const summarizing = ref(false);
const summaryError = ref('');

/** 调用 LLM 生成摘要 */
async function runSummary(
  chapterId: string,
  coveredUpTo: number,
  targetWords: number,
  mode: 'manual' | 'auto',
) {
  const cfg = api.currentConfig;
  if (!cfg?.apiKey) {
    summaryError.value = '需要先在设置页配置 API 才能生成摘要';
    return;
  }
  const source = novel.textForSummary(chapterId, coveredUpTo);
  if (!source.trim()) {
    summaryError.value = '这段区间没有内容可总结';
    return;
  }

  summarizing.value = true;
  summaryError.value = '';
  try {
    // streamChatCompletion 返回完整文本，直接用返回值就够了，
    // 不需要在 onDone 里再回调一次（那样会写两条摘要）。
    const text = await streamChatCompletion(
      { ...cfg },
      [
        {
          role: 'system',
          content:
            `你是小说编辑。把下面的内容压缩成不超过 ${targetWords} 字的摘要。\n` +
            '要求：保留人物、地点、关键事件、已确立的设定和伏笔；' +
            '去掉对话细节和修辞；用第三人称陈述句；不要加评论或标题。',
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
    await novel.addSummary(chapterId, {
      text: trimmed,
      mode,
      coveredUpTo,
      targetWords,
    });
  } catch (e) {
    summaryError.value = e instanceof Error ? e.message : String(e);
  } finally {
    summarizing.value = false;
  }
}

async function manualSummary(targetWords: number, upTo: number) {
  if (!currentChapter.value) return;
  await runSummary(currentChapter.value.id, upTo, targetWords, 'manual');
}

async function maybeAutoSummary(chapterId: string) {
  const due = novel.summaryDue(chapterId);
  if (!due) return;
  // 自动摘要是"尽力而为"，失败不打扰用户
  try {
    await runSummary(chapterId, due.coveredUpTo, settings.value.targetWords, 'auto');
  } catch {
    /* 忽略 */
  }
}

async function updateSettings(patch: Partial<NovelSettings>) {
  await novel.updateSettings(patch);
}

// ---------------- 滚动 ----------------

const chatEl = ref<HTMLElement | null>(null);
function scrollToBottom() {
  nextTick(() => {
    if (chatEl.value) chatEl.value.scrollTop = chatEl.value.scrollHeight;
  });
}
watch([() => messages.value.length, streamOutput], scrollToBottom);

function onUserKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    void sendMessage();
  }
}
</script>

<template>
  <div class="novel-page" v-if="loaded">
    <!--
      顶栏（两行）。

      第一行是**每天都用**的三个：返回 / 当前模型 / 生图 / 摘要。
      第二行是"这次要调什么"：章节切换与统计，右边一个「⋯」把
      其余入口（阅读 / 写作设置 / 章节管理）收进去 ——
      展开之前只有一行，展开之后才是具体选项。

      以前是一股脑平铺三行，手机上吃掉约 300px（整屏三分之一），
      正文窗口被压得很扁。
    -->
    <header class="novel-header">
      <!--
        第一行：只留**天天都在用**的 —— 返回 / 当前模型 / 生图 / 摘要，
        最后是「⋯」。收起状态下整个顶栏就只有这一行。
      -->
      <div class="nh-row">
        <!-- 返回章节列表（一级界面） -->
        <button class="back-btn" @click="goBack" title="返回章节列表">‹</button>
        <!-- 当前模型：点一下切换（运营商 → 模型 两级） -->
        <ModelPicker />
        <span class="nh-spacer"></span>
        <button @click="showImageGen = !showImageGen" title="生图">🎨 生图</button>
        <button @click="togglePanel('summary')" :class="{ active: showSummary }">
          📋 摘要<span v-if="currentChapter?.summaries?.length"> {{ currentChapter.summaries.length }}</span>
        </button>
        <!-- 折叠开关：其余入口都在下面那一块里 -->
        <button class="more-toggle" :class="{ active: headerMore }"
          @click="headerMore = !headerMore"
          :title="headerMore ? '收起' : '更多操作'">
          {{ headerMore ? '收起' : '⋯' }}
        </button>
      </div>

      <!--
        第二块：展开后才出现的子选项。
        用 CSS 控制显隐而不是 v-if —— 桌面端屏幕宽、不需要折叠，
        这些按钮要**常驻显示**（见 .nh-sub-opts 的桌面规则）。
      -->
      <div class="nh-sub-opts" :class="{ open: headerMore }">
        <select
          :value="novel.currentChapterId"
          @change="(e) => selectChapter((e.target as HTMLSelectElement).value)"
          class="chapter-select"
        >
          <option v-for="c in novel.chapters" :key="c.id" :value="c.id">{{ c.title }}</option>
        </select>
        <button class="icon-btn" @click="togglePanel('chapters')" title="章节管理">☰ 章节</button>
        <button @click="togglePanel('assets')">{{ showAssetPanel ? '隐藏资产' : '资产' }}</button>
        <!--
          沉浸式阅读入口。

          只在**有内容可读**时才出现：一章都还没写就点"阅读"，
          进去只能看到空页，反而让人以为坏了。
          判断依据是"任意一章有正文或对话内容"。
        -->
        <button
          v-if="hasAnythingToRead"
          @click="showReader = true"
          title="沉浸式阅读（读完按返回即可继续写）"
        >
          📖 阅读
        </button>
        <button @click="showSettings = !showSettings" :class="{ active: showSettings }" title="写作设置">⚙️</button>
        <button @click="exportChat" title="导出这一章的对话记录（原文或美化后的文本，见设置）">
          ⤓ 导出记录
        </button>
        <span class="stat-badge">{{ messages.length }} 轮 · {{ charCount }} 字</span>
      </div>
    </header>

    <div class="novel-body">
      <!-- 左侧：章节列表 -->
      <aside class="chapter-list" :class="{ open: showChapterList }">
        <!-- 手机端抽屉的手柄 + 关闭（桌面端不显示） -->
        <div class="sheet-handle">
          <span class="grabber"></span>
          <button class="sheet-close" @click="showChapterList = false" title="关闭">✕</button>
        </div>
        <h3>章节</h3>
        <div
          v-for="c in novel.chapters"
          :key="c.id"
          class="chapter-item"
          :class="{ active: c.id === novel.currentChapterId }"
          @click="selectChapter(c.id)"
        >
          <span class="chapter-title">{{ c.title }}</span>
          <button class="danger tiny" @click.stop="deleteChapter(c.id)">×</button>
        </div>
        <button class="new-chapter-btn" @click="newChapter">+ 新建章节</button>
      </aside>

      <!-- 中间：对话 -->
      <div class="editor-area">
        <!--
          写作设置：手机上做成**底部抽屉**，和其它面板（摘要/资产/章节）一致。
          
          之前它是内嵌在正文区顶部的一块（.settings-strip）。
          问题：点的是右上角的 ⚙️，面板却出现在**左边顶部**，视线对不上；
          而且它会把正文挤下去、改变整页布局 —— 手机上尤其别扭。
          改成抽屉后：从底部滑出、停在标签栏之上，点遮罩或 ✕ 关闭，
          和另外三个面板行为完全一样。
        -->
        <div v-if="showSettings" class="sheet-backdrop" @click="showSettings = false"></div>
        <div v-if="showSettings" class="settings-sheet">
          <div class="sheet-handle"></div>
          <button class="sheet-close" @click="showSettings = false" title="关闭">✕</button>
          <div class="sheet-title">⚙️ 写作设置</div>

          <label class="ss-row">
            <span>自动摘要</span>
            <input type="checkbox" :checked="settings.autoSummary"
              @change="updateSettings({ autoSummary: ($event.target as HTMLInputElement).checked })" />
          </label>
          <label class="ss-row">
            <span>触发方式</span>
            <select :value="settings.trigger"
              @change="updateSettings({ trigger: ($event.target as HTMLSelectElement).value as any })">
              <option value="messages">按消息条数</option>
              <option value="chars">按累计字数</option>
            </select>
          </label>
          <label v-if="settings.trigger === 'messages'" class="ss-row">
            <span>每多少轮</span>
            <input type="number" min="1" :value="settings.everyMessages"
              @change="updateSettings({ everyMessages: Number(($event.target as HTMLInputElement).value) || 1 })" />
          </label>
          <label v-else class="ss-row">
            <span>每多少字</span>
            <input type="number" min="200" step="200" :value="settings.everyChars"
              @change="updateSettings({ everyChars: Number(($event.target as HTMLInputElement).value) || 2000 })" />
          </label>
          <label class="ss-row">
            <span>摘要目标字数</span>
            <input type="number" min="50" step="50" :value="settings.targetWords"
              @change="updateSettings({ targetWords: Number(($event.target as HTMLInputElement).value) || 300 })" />
          </label>
          <div class="ss-actions">
            <button @click="applyToContent('append', novel.currentChapterId!)">对话追加到正文</button>
            <button @click="applyToContent('replace', novel.currentChapterId!)">覆盖正文</button>
          </div>
        </div>

        <!-- 正文预览（可折叠） -->
        <details class="content-fold" v-if="currentChapter?.content">
          <summary>当前正文（{{ charCount }} 字）— 点开查看 / 编辑</summary>
          <textarea
            class="content-area"
            :value="currentChapter.content"
            @change="(e) => novel.updateChapter(currentChapter!.id, { content: (e.target as HTMLTextAreaElement).value })"
            rows="8"
          ></textarea>
        </details>

        <!-- 消息流 -->
        <div class="chat-area" ref="chatEl">
          <div v-if="!messages.length && !streamOutput" class="empty-state">
            <p>还没有内容。在下面输入你的想法，AI 会接着写下去。</p>
            <!-- 已裁剪：原为 46 字的界面说明（只对开发者有意义） -->
          </div>

          <div v-for="m in messages" :key="m.id" class="message-group"
            :class="{ pinned: m.pinned, busy: busyMessageId === m.id }">
            <div class="message user" v-if="m.user">
              <div class="msg-role">🧑 你</div>
              <div class="msg-content">{{ m.user }}</div>
              <MessageActions
                :content="m.user"
                :streaming="streaming"
                remove-label="删我的发言"
                confirm-label="删掉我说的这句？"
                @save="(t) => novel.updateMessage(currentChapter!.id, m.id, { user: t })"
                @regenerate="() => regenerateMessage(m)"
                @branch="() => branchAt(m.id)"
                @remove="() => novel.removeMessageUser(currentChapter!.id, m.id)"
              />
            </div>
            <div class="message ai" v-if="m.ai">
              <div class="msg-role">
                🤖 AI
                <span v-if="m.pinned" class="pin-tag">重点</span>
                <VersionSwitcher
                  v-if="m.variants && m.variants.length"
                  :total="m.variants.length"
                  :index="m.variantIndex ?? 0"
                  @switch="(i) => novel.switchMessageVariant(currentChapter!.id, m.id, i)"
                />
                <button class="quick-img" :disabled="!!quickImgBusy"
                  @click="quickImageFor(m)"
                  title="用这段内容生成一张插图，自动插入，不满意可删除或重新生成">
                  {{ quickImgBusy ? '生成中…' : '🎨 配图' }}
                </button>
              </div>
              <div class="msg-content">
                <ContentWithImages
                  :content="displayText(m.ai)"
                  :attachments="m.images"
                  regenerable
                  @regenerate="(att) => regenImageInPlace(m.id, att)"
                  @switch-version="(aid, vid) => switchChapterImageVersion(m.id, aid, vid)"
                />
              </div>
              <div class="msg-extra">
                <label class="pin-toggle">
                  <input type="checkbox" :checked="m.pinned"
                    @change="novel.updateMessage(currentChapter!.id, m.id, { pinned: ($event.target as HTMLInputElement).checked })" />
                  <span>标记为重点</span>
                </label>
                <input class="note-input" type="text" :value="m.note" placeholder="备注…"
                  @change="(e) => novel.updateMessage(currentChapter!.id, m.id, { note: (e.target as HTMLInputElement).value })" />
              </div>
              <MessageActions
                :content="m.ai"
                :streaming="streaming"
                remove-label="删这条回复"
                confirm-label="只删这条回复？"
                @save="(t) => novel.updateMessage(currentChapter!.id, m.id, { ai: t })"
                @regenerate="() => regenerateMessage(m)"
                @branch="() => branchAt(m.id)"
                @remove="() => novel.removeMessageAi(currentChapter!.id, m.id)"
              />
            </div>
          </div>

          <!-- 流式输出 -->
          <div v-if="streaming || streamOutput" class="message-group">
            <div class="message user">
              <div class="msg-role">🧑 你</div>
              <div class="msg-content">{{ streamUser }}</div>
            </div>
            <div class="message ai">
              <div class="msg-role">🤖 AI</div>
              <div class="msg-content">
                <ContentWithImages :content="displayText(streamOutput)" />
                <span v-if="streaming" class="cursor">▋</span>
              </div>
            </div>
            <div v-if="autoImgPending > 0" class="auto-image-status">
              <span class="spinner">⏳</span><span>{{ autoImgStatus }}</span>
            </div>
            <div v-else-if="autoImgError" class="auto-image-error">⚠ 自动配图失败：{{ autoImgError }}</div>
          </div>
        </div>

        <!-- 输入区（吸底） -->
        <div class="input-area">
          <div class="input-row">
            <ImageQuickBar />
            <button class="icon-btn" @click="quickImageFromLast" :disabled="!!quickImgBusy"
              title="用最近一轮内容直接出一张图，自动插入">
              {{ quickImgBusy ? '⏳' : '🎨' }}
            </button>
            <textarea
              v-model="userInput"
              @keydown="onUserKeydown"
              placeholder="写点什么，或直接说「继续」…（Enter 发送，Shift+Enter 换行）"
              :disabled="streaming"
              rows="2"
            ></textarea>
            <button class="primary send-btn" @click="sendMessage"
              :disabled="streaming || !userInput.trim()">
              {{ streaming ? '生成中…' : '发送' }}
            </button>
          </div>
          <div v-if="errorMessage" class="error-inline">
            ⚠ {{ errorMessage }}
            <button @click="errorMessage = ''">×</button>
          </div>
        </div>
      </div>

      <!-- 右侧：摘要 / 资产面板
           手机端：底部抽屉 + 遮罩（见 <style> 里的 @media） -->
      <div
        v-if="showChapterList || showSummary || showAssetPanel"
        class="sheet-backdrop"
        @click="closeAllPanels"
      ></div>
      <aside v-if="showSummary" class="summary-aside">
        <div class="sheet-handle">
          <span class="grabber"></span>
          <button class="sheet-close" @click="showSummary = false" title="关闭">✕</button>
        </div>
        <SummaryPanel
          :chapter="currentChapter"
          :summarizing="summarizing"
          :error="summaryError"
          :target-words="settings.targetWords"
          :message-count="messages.length"
          @summary="(words, upTo) => manualSummary(words, upTo)"
          @remove="(id) => novel.removeSummary(currentChapter!.id, id)"
          @update="(id, text) => novel.updateSummary(currentChapter!.id, id, text)"
          @close="showSummary = false"
        />
      </aside>
      <aside v-else-if="showAssetPanel" class="asset-aside">
        <div class="sheet-handle">
          <span class="grabber"></span>
          <button class="sheet-close" @click="showAssetPanel = false" title="关闭">✕</button>
        </div>
        <AssetPanel :compact="false" />
      </aside>
    </div>

    <!-- 快速回顶 / 回底 -->
    <ScrollFabs :anchor="chatEl" />

    <!-- 生图弹窗 -->
    <ImageGenModal
      v-if="showImageGen && currentChapter"
      :context-messages="buildHistory(currentChapter.id)"
      :context-label="'小说章节'"
      :scene="'novel'"
      :entity-id="currentChapter.id"
      :preset-prompt="regenPrompt"
      :reference-image="imageStore.quickReferenceImage"
      :reference-strength="imageStore.quickReferenceStrength"
      @generated="() => {}"
      @close="showImageGen = false; regenPrompt = ''"
    />

    <!--
      沉浸式阅读：以全屏层盖住整页（不是跳路由）。
      关掉就回到编辑器，选中的章节、输入框里的草稿都还在。
      从"当前这一章"开始读。
    -->
    <NovelReader
      v-if="showReader"
      :start-chapter-id="novel.currentChapterId"
      @close="showReader = false"
    />
  </div>

  <div v-else class="loading">加载中…</div>
</template>

<style scoped>
.novel-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}
.novel-header {
  /*
   * 两行结构（见模板里的注释）。
   * 用 column 而不是 wrap：wrap 会让按钮"随宽度随机折行"，
   * 手机上就变成三行、把正文压扁。定死两行，每行自己横向排。
   */
  display: flex;
  flex-direction: column;
  align-items: stretch;
  padding: 8px 12px;
  gap: 7px;
  background: var(--panel);
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}
.nh-row {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
}
/* 把右侧那组按钮推到最右边 */
/*
 * 第二行：折叠开关 + 统计在左，展开后子选项在右。
 * 收起时这一行只有「⋯」和"几轮几字"，很矮；展开才长出按钮。
 */
/* 折叠开关：跟在摘要后面，别被压扁 */
.more-toggle {
  flex: 0 0 auto;
}

/*
 * 折叠的显隐规则，用「手机为默认 + 桌面用 min-width 覆盖」的写法。
 *
 * 为什么不像别处那样写 `max-width` 媒体查询：
 *   那样会变成"基础规则显示 / 手机查询隐藏"两条互相打架的声明，
 *   谁生效取决于它们在文件里的先后 —— 实测踩过（子选项默认没收起）。
 *   这里让**手机态就是基础规则**，桌面态用 min-width 覆盖，
 *   两条规则的作用范围完全不重叠，不存在顺序问题。
 */
.nh-sub-opts {
  display: none;
  width: 100%;
  /* 展开后独占一行，横向铺开方便点 */
  order: 9;
  padding-top: 5px;
}
.nh-sub-opts.open {
  display: flex;
}
/* 桌面上「⋯」藏起来、子选项常驻（屏幕宽，不需要折叠） */
.more-toggle {
  display: inline-block;
}
@media (min-width: 769px) {
  .more-toggle {
    display: none;
  }
  .nh-sub-opts {
    display: flex;
    align-items: center;
    gap: 6px;
    flex-wrap: wrap;
    width: auto;
    order: 0;
    padding-top: 0;
    min-width: 0;
  }
}

.nh-spacer {
  flex: 1 1 auto;
  min-width: 4px;
}
.nh-row > button {
  flex: 0 0 auto;
  white-space: nowrap;
}
.header-left,
.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.chapter-select {
  font-size: 13px;
  padding: 5px 8px;
  max-width: 220px;
}
.stat-badge {
  font-size: 11px;
  color: var(--text-dim);
  white-space: nowrap;
}
.header-right button.active {
  border-color: var(--accent);
  color: var(--accent);
}
.icon-btn {
  font-size: 15px;
  padding: 5px 9px;
  line-height: 1;
}

.novel-body {
  flex: 1;
  display: flex;
  min-height: 0;
  overflow: hidden;
}

/* 章节列表 */
.chapter-list {
  width: 170px;
  flex-shrink: 0;
  border-right: 1px solid var(--border);
  background: var(--panel);
  padding: 10px;
  overflow-y: auto;
}
.chapter-list h3 {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--text-dim);
  font-weight: 500;
}
.chapter-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  padding: 6px 8px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 12.5px;
  color: var(--text-dim);
}
.chapter-item:hover {
  background: var(--panel-2);
}
.chapter-item.active {
  background: var(--accent);
  color: #0b1220;
  font-weight: 600;
}
.chapter-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tiny {
  font-size: 10px;
  padding: 1px 6px;
  line-height: 1.4;
}
.new-chapter-btn {
  width: 100%;
  margin-top: 8px;
  font-size: 12px;
}

/* 中间 */
.editor-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.settings-sheet {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px 16px 20px;
  background: var(--panel);
  border-bottom: 1px solid var(--border);
  font-size: 12px;
}
/*
 * 设置行：**标签和控件各占一列**，标签不换行。
 * 之前用 flex 默认换行，"触发方式"四个字被挤成两行，
 * 整个抽屉左边缘参差不齐。
 */
.settings-sheet .ss-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.settings-sheet .ss-row > span {
  flex-shrink: 0;
  white-space: nowrap;
  color: var(--text-dim);
}
.settings-sheet .ss-row > input[type='number'],
.settings-sheet .ss-row > select {
  flex: 1;
  min-width: 0;
  max-width: 200px;
}
.settings-sheet .ss-row > input[type='checkbox'] {
  flex: 0 0 auto;
  width: 18px;
  height: 18px;
}
.settings-sheet .ss-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 2px;
}
/* 关闭按钮挪到右上角，别占一整行 */
.settings-sheet .sheet-close {
  align-self: flex-end;
  margin-bottom: -6px;
}
/* 抽屉里的标题（手机端才显示，桌面端面板是内嵌的） */
.sheet-title {
  font-size: 14px;
  font-weight: 700;
  margin-bottom: 12px;
  color: var(--text);
}
.ss-row {
  display: flex;
  align-items: center;
  gap: 5px;
  color: var(--text-dim);
}
.ss-row input[type='number'] {
  width: 68px;
  font-size: 11.5px;
  padding: 3px 6px;
}
.ss-row select {
  font-size: 11.5px;
  padding: 3px 6px;
}
.ss-row input[type='checkbox'] {
  width: auto;
}
.ss-actions {
  display: flex;
  gap: 6px;
  margin-left: auto;
}
.ss-actions button {
  font-size: 11px;
  padding: 3px 10px;
}

.content-fold {
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}
.content-fold summary {
  padding: 7px 12px;
  font-size: 11.5px;
  color: var(--text-dim);
  cursor: pointer;
}
.content-area {
  width: 100%;
  box-sizing: border-box;
  border: none;
  border-top: 1px solid var(--border);
  background: var(--code-bg);
  color: var(--text);
  font-size: 12.5px;
  line-height: 1.7;
  padding: 10px 12px;
  resize: vertical;
  font-family: inherit;
}

.chat-area {
  flex: 1;
  overflow-y: auto;
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.empty-state {
  text-align: center;
  color: var(--text-dim);
  padding: 40px 20px;
  font-size: 13px;
}
.empty-state .hint {
  font-size: 11.5px;
  margin-top: 8px;
  line-height: 1.7;
}

.message-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
  /* 跳渲染：屏幕外的消息不排版不绘制，长章节在手机上才不会一滚一卡 */
  content-visibility: auto;
  contain-intrinsic-size: auto 220px;
}
.message {
  border-radius: 10px;
  padding: 10px 13px;
  border: 1px solid var(--border);
  max-width: 92%;
}
.message.user {
  align-self: flex-end;
  background: color-mix(in srgb, var(--accent) 12%, var(--panel));
  border-color: color-mix(in srgb, var(--accent) 30%, var(--border));
}
.message.ai {
  align-self: flex-start;
  background: var(--panel);
  width: 100%;
  max-width: 100%;
}
.message-group.pinned .message.ai {
  border-color: var(--warn);
  box-shadow: inset 3px 0 0 var(--warn);
}
.message-group.busy .message.ai {
  opacity: 0.6;
}
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
.msg-role {
  font-size: 10.5px;
  color: var(--text-dim);
  margin-bottom: 5px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.pin-tag {
  font-size: 9px;
  padding: 1px 6px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--warn) 25%, transparent);
  color: var(--warn);
}
.msg-content {
  font-size: 13.5px;
  line-height: 1.75;
  white-space: pre-wrap;
  word-break: break-word;
}
.msg-extra {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
  flex-wrap: wrap;
}
.pin-toggle {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 10.5px;
  color: var(--text-dim);
  cursor: pointer;
  white-space: nowrap;
}
.pin-toggle input {
  width: auto;
}
.note-input {
  flex: 1;
  min-width: 100px;
  font-size: 10.5px;
  padding: 3px 8px;
}

.cursor {
  animation: blink 1s steps(2) infinite;
  color: var(--accent);
}
@keyframes blink {
  to {
    opacity: 0;
  }
}

/* 输入区 */
.input-area {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 14px;
  padding-bottom: calc(10px + env(safe-area-inset-bottom, 0px));
  background: var(--panel);
  border-top: 1px solid var(--border);
  position: sticky;
  bottom: 0;
  z-index: 20;
  flex-shrink: 0;
}
.input-row {
  display: flex;
  gap: 8px;
  align-items: flex-end;
}
.input-row textarea {
  flex: 1;
  resize: none;
  min-height: 42px;
  max-height: 140px;
  font-size: 13px;
  padding: 9px 11px;
  font-family: inherit;
}
.send-btn {
  padding: 9px 20px;
  white-space: nowrap;
}
.error-inline {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11.5px;
  color: var(--danger);
}
.error-inline button {
  padding: 0 6px;
  background: none;
  border: none;
  color: var(--danger);
  cursor: pointer;
}

.asset-aside,
.summary-aside {
  width: 280px;
  flex-shrink: 0;
  border-left: 1px solid var(--border);
  background: var(--panel);
  overflow-y: auto;
}
.loading {
  padding: 40px;
  text-align: center;
  color: var(--text-dim);
}

/* ---------------------------------------------------------------------------
 * 手机端：整页自然滚动 + 章节列表变抽屉
 * 规则必须写在 scoped 块里，否则被组件自己的样式压掉（见 main.css 的说明）
 * --------------------------------------------------------------------------- */
@media (max-width: 768px) {
  .novel-page {
    height: auto;
    /*
     * 撑满至少一屏：内容短时也让输入框被推到视口底部，不浮在中间。
     * 用 dvh 而不是百分比（flex 子项的百分比 min-height 不一定解析）。
     * 减去顶部吸顶导航的高度（约 53px，含安全区）。
     */
    min-height: calc(100dvh - var(--tabbar-h));
    overflow: visible;
  }
  .novel-body {
    overflow: visible;
    min-height: 0;
  }
  .chapter-list {
    display: none;
    width: 100%;
    border-right: none;
    border-bottom: 1px solid var(--border);
  }
  .chapter-list.open {
    /*
     * 手机上章节列表做成底部抽屉。
     *
     * 原来只是 `display: block` 的内联展开：`.novel-body` 是 flex 行，
     * width:100% 的它被拉伸到和正文一样高 —— 实测开一次会在上面顶出一个
     * **1559px 高的空块**，正文直接被推到屏幕外。改成抽屉后与其他面板一致，
     * 点遮罩或 ✕ 关闭。
     */
    display: block;
    position: fixed;
    left: 0;
    right: 0;
    /* 同摘要/资产抽屉：停在标签栏之上，避免重叠压住内容 */
    bottom: var(--tabbar-h);
    width: 100%;
    max-height: 62vh;
    border-right: none;
    border-top: 1px solid var(--border);
    border-radius: var(--radius-lg) var(--radius-lg) 0 0;
    background: var(--panel);
    box-shadow: var(--shadow-3);
    z-index: 40;
    overflow-y: auto;
    padding-bottom: 16px;
  }
  /* 抽屉里每一行做成手指好点的高度 */
  .chapter-item {
    padding: 12px 10px;
    font-size: 13.5px;
  }
  .editor-area {
    min-height: 0;
  }
  .chat-area {
    overflow: visible;
    overflow-y: visible;
    padding: 10px 12px;
  }
  /* 摘要 / 资产面板：手机端改成底部抽屉（与 Adventure 一致） */
  /*
   * 摘要/资产抽屉：停在**底部标签栏之上**（不是屏幕最底），
   * 否则抽屉会和固定标签栏重叠，最下面几行内容被压住看不见。
   */
  /* 写作设置抽屉：和其它面板行为一致（底部滑出、停在标签栏之上） */
  .settings-sheet {
    position: fixed;
    left: 0;
    right: 0;
    bottom: var(--tabbar-h);
    width: 100%;
    max-height: 62vh;
    border-radius: var(--radius-lg) var(--radius-lg) 0 0;
    box-shadow: var(--shadow-3);
    z-index: 40;
    overflow-y: auto;
    padding-bottom: 16px;
  }

  .asset-aside,
  .summary-aside {
    position: fixed;
    /* 左右各让出 10px，不再贴着屏幕边 —— 抽屉像一块浮起来的卡片，
       内容也不会顶到圆角/手势区上（原来 left/right 都是 0）。 */
    left: 10px;
    right: 10px;
    width: auto;
    bottom: var(--tabbar-h);
    max-height: 68vh;
    border: 1px solid var(--border);
    border-bottom: none;
    border-radius: 16px 16px 0 0;
    background: var(--panel);
    box-shadow: 0 -10px 36px rgba(0, 0, 0, 0.45);
    z-index: 40;
    overflow-y: auto;
    /* 抽屉自己的左右内边距：里面的小节不再贴边 */
    padding: 0 14px calc(14px + env(safe-area-inset-bottom, 0px));
  }
  .message {
    max-width: 100%;
  }
  .novel-header {
    padding: 6px 10px;
    gap: 6px;
  }
  .nh-row {
    /* 间距和按钮都要收：一行要塞「返回 + 模型 + 摘要 + 资产 + 生图」，
       不收紧最后一个按钮会顶到屏幕最右边（手指不好点） */
    gap: 4px;
  }
  .novel-header button {
    padding: 5px 7px;
    font-size: 11.5px;
  }
  .novel-header .back-btn,
  .novel-header .icon-btn {
    padding: 5px 7px;
    min-width: 0;
  }
  /* 模型选择器别把整行吃掉 —— 名字长的模型允许省略，后面有完整列表可看 */
  .novel-header :deep(.model-picker) {
    max-width: 34vw;
  }
  .stat-badge {
    font-size: 10.5px;
    padding: 2px 5px;
    white-space: nowrap;
  }
  .chapter-select {
    max-width: 96px;
    font-size: 11.5px;
    padding: 4px 5px;
  }
  .input-area {
    padding: 8px 10px;
    padding-bottom: calc(8px + env(safe-area-inset-bottom, 0px));
    position: sticky;
    /*
     * 贴在**底部标签栏之上**，不是视口底部。
     * 手机导航现在固定在底部（58px），写 bottom:0 会被标签栏整个盖住。
     */
    bottom: calc(var(--tabbar-h) + env(safe-area-inset-bottom, 0px));
    z-index: 20;
    background: var(--glass);
    -webkit-backdrop-filter: blur(16px) saturate(1.3);
    backdrop-filter: blur(16px) saturate(1.3);
    border-top: 1px solid var(--border);
    box-shadow: var(--shadow-up);
  }
  /*
   * 手机上输入区纵向堆叠（与冒险页一致）：
   * 参考图条一行、输入框整宽一行、按钮一行。
   * 挤在一行时输入框只剩 180 出头，手机上根本没法打字。
   */
  .input-row {
    flex-wrap: wrap;
  }
  .input-area .input-row :deep(.quick-bar) {
    flex: 1 1 100%;
    order: 1;
  }
  .input-area .input-row textarea {
    flex: 1 1 100%;
    order: 2;
    min-height: 46px;
    font-size: 15px;
    line-height: 1.5;
  }
  .input-area .input-row .icon-btn {
    order: 3;
    width: 46px;
    height: 46px;
  }
  .input-area .input-row .send-btn {
    order: 4;
    flex: 1 1 auto;
    min-height: 46px;
    font-size: 14px;
  }
  .ss-actions {
    margin-left: 0;
  }
}

/* 桌面端：遮罩和抽屉手柄不显示 */
.sheet-backdrop,
.sheet-handle {
  display: none;
}
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
</style>
