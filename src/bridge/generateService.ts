/**
 * 纯生成通道：原生聊天界面 → ST 兼容引擎 → 大模型。
 *
 * ★ 设计铁律：**这里不写任何会话数据。**
 *
 * 会话（消息、版本、配图引用、草稿）全部由安卓原生存，web 只是"把原生交来的
 * 这一份对话，按 ST 预设/世界书/正则/变量组装成请求，然后发出去"。
 * 所以本文件里没有 `adventure.xxx()` 的任何调用 —— 一旦写了，
 * 立刻会变成"两边各存一份、还互相不知道对方改了没"的同步地狱。
 *
 * 数据流：
 *   原生 SessionDoc
 *     └─ SessionHistory.export()   只取当前版本、剥掉 [[IMG:]] 占位符
 *         └─ __dhpGen.generate()   ← 本文件
 *             ├─ 变量写宏（setvar 等，只在发送时执行一次）
 *             ├─ assemblePrompt()  预设排序 + 世界书触发 + 宏 + 正则 + token 裁剪
 *             └─ streamChatCompletion()
 *                 ├─ onGenDelta   → 原生实时刷新气泡
 *                 ├─ onGenDone    → 原生落盘成正式消息
 *                 └─ onGenError / onGenStopped
 *
 * 为什么变量写宏放在这里而不是原生：
 *   变量表是 ST 兼容层的一部分（{{getvar::x}} / {{setvar::x::1}}），
 *   语法、作用域、全局/会话两级全都由 web 定义。原生不解析这套语法，
 *   只负责"把用户原话发过去"，写宏的副作用留在 web 侧执行。
 */

import { assemblePrompt } from '../engine/assembler';
import { readVariables } from '../engine/variables';
import { collectPresetMessages } from '../engine/summaryPreset';
import { streamChatCompletion } from '../engine/apiClient';
import { imageMarkerInstruction } from '../engine/imageMarker';
import { flattenForEngine } from '../engine/regexGroups';
import {
  activatePromptInOrder,
  getActivePromptOrder,
  listRankedPrompts,
  movePromptInOrder,
} from '../engine/presetOrder';
import type { ChatMessage } from '../engine/types';
import { OpenAIPreset, WorldBook } from 'parsecard';
import { useAssetsStore } from '../stores/assets';
import { useApiConfigStore } from '../stores/apiConfig';
import { useVariablesStore } from '../stores/variables';
import { useStorageSettingsStore } from '../stores/storageSettings';
import { useImageStore } from '../stores/image';
import { resolveCached } from '../lib/lazyImage';
import { fetchModels } from '../engine/imageGen';

// ───────────────────── 原生配置镜像（只读，2026-11-07） ─────────────────────
/**
 * 运营商 / 模型 / 风格这些「连接配置」已经搬到安卓原生 ConnectionStore。
 * 引擎不再把 Pinia store 当配置数据库，只读原生推来的镜像：
 *
 *   · 原生每次配置变更后调 __dhpApi.applyNativeConfig 单向推一份；
 *   · 每次生成请求再带一份 req.config 作双保险（引擎刚启动/镜像还没到时）；
 *   · 网页版独立运行时没有注入，所有读取点自动回退到 store，行为不变。
 *
 * ★ 刻意**不写回 store**：两份权威源迟早分叉，而且 store 落盘是异步的，
 *   写回来的时序根本不可靠。预设 / 世界书 / 正则 / 变量等 ST 兼容层
 *   仍然原样读 store，一个字没动 —— 这是兼容 SillyTavern 的底线。
 */
interface NativeConfigDoc {
  llm: { configs: any[]; currentConfigId?: string; providerNotes?: Record<string, string> };
  image: {
    apiConfigs: any[];
    styleConfigs: any[];
    currentApiId?: string;
    currentStyleId?: string;
    autoImage?: any;
    quickContextWindow?: number;
    quickAiOnly?: boolean;
  };
}

function nativeDoc(): NativeConfigDoc | null {
  const d = (window as any).__dhpNativeConfig as NativeConfigDoc | undefined;
  return d && Array.isArray(d?.llm?.configs) ? d : null;
}

/** 当前对话配置。生成请求里带的 req.config 优先级最高（时序最可靠）。 */
function nativeLlm(uid?: string): any | undefined {
  if (!uid) {
    const rt = (window as any).__dhpRuntimeLlm;
    if (rt) return rt;
  }
  const d = nativeDoc();
  if (!d) return undefined;
  const list = d.llm.configs || [];
  return list.find((c) => c._uid === (uid || d.llm.currentConfigId)) || list[0];
}

/** 合并当前生图 API + 当前风格，形状对齐 image.ts 的 merged()。 */
function nativeImageMerged(): any | null {
  const d = nativeDoc();
  if (!d) return null;
  const apis = d.image.apiConfigs || [];
  const styles = d.image.styleConfigs || [];
  const a = apis.find((x) => x._uid === d.image.currentApiId) || apis[0] || {};
  const s = styles.find((x) => x._uid === d.image.currentStyleId) || styles[0] || {};
  return { ...a, ...s, _uid: s._uid ?? a._uid, name: s.name ?? a.name };
}

function nativeAuto(): any | null {
  return nativeDoc()?.image.autoImage ?? null;
}

/**
 * 一次性迁移：把 web store 里的老配置整份交给原生，之后永不再用。
 * 只导出配置，绝不碰预设 / 世界书 / 正则等 ST 兼容层数据。
 */
function exportNativeConfig(): string {
  const api = useApiConfigStore();
  const img = useImageStore();
  return JSON.stringify({
    llm: {
      configs: api.configs || [],
      currentConfigId: api.currentConfigId || '',
      providerNotes: api.providerNotes || {},
    },
    image: {
      apiConfigs: img.apiConfigs || [],
      styleConfigs: img.styleConfigs || [],
      currentApiId: img.currentApiId || '',
      currentStyleId: img.currentStyleId || '',
      autoImage: img.autoImage || {},
      quickContextWindow: img.quickContextWindow ?? 5,
    },
  });
}

/** 原生配置变更时推来的镜像。只存内存，不写 store。 */
function applyNativeConfig(raw: string): void {
  try {
    (window as any).__dhpNativeConfig = typeof raw === 'string' ? JSON.parse(raw) : raw;
  } catch (e) {
    console.warn('[bridge] applyNativeConfig 解析失败', e);
  }
}

/** 原生交来的一次生成请求。 */
interface NativeGenRequest {
  requestId: string;
  /** 原生会话 id（只用作变量表的作用域 key，不写会话） */
  sessionId: string;
  /** 已按"当前版本"导出好的历史，图片标记已剥 */
  messages: { role: string; content: string }[];
  /** 当前这一句用户输入（重新生成时是那一条的原文） */
  userInput?: string;
  /** 是否在请求末尾追加"可以用 [[IMG: 描述]] 配图"的指令 */
  imageInstruction?: boolean;
  imageInstructionMax?: number;
}

/** 请求 id → 中断句柄。停止生成时按 id 掐断，不会误伤别的会话。 */
const running = new Map<string, AbortController>();

/**
 * "还没建 AbortController 就被取消了"的请求。
 *
 * 用户点了发送马上又点停止时，generate 可能正卡在变量写宏 / 组装提示词，
 * 这时还没有 controller 可 abort。记下来，等它走到下一步自己退出，
 * 否则会白调一次 API（而且用户以为已经停了）。
 */
const cancelled = new Set<string>();

function native(): any {
  return (window as any).AndroidNative;
}

/** 调原生回调；原生对象不存在（纯浏览器调试）时静默跳过。 */
function callNative(fn: string, ...args: unknown[]): void {
  const bridge = native();
  if (!bridge || typeof bridge[fn] !== 'function') return;
  try {
    bridge[fn](...args);
  } catch (e) {
    console.warn(`[gen] 回调 ${fn} 失败`, e);
  }
}

function toChatMessages(raw: { role: string; content: string }[]): ChatMessage[] {
  const out: ChatMessage[] = [];
  for (const m of raw ?? []) {
    if (!m || typeof m.content !== 'string') continue;
    if (!m.content.trim()) continue;
    const role = m.role === 'user' ? 'user' : m.role === 'system' ? 'system' : 'assistant';
    out.push({ role, content: m.content } as ChatMessage);
  }
  return out;
}

/**
 * 按 ST 语义组装最终请求。
 *
 * 与 Adventure.vue 里 `buildFinalMessages` 的唯一差别：
 * 历史不再从 store 读，而是原生直接给。其余（预设取哪个、世界书是否启用、
 * 正则是否作用于上下文）完全一致 —— 这些是"用户在资产页选了什么"，
 * 属于全局设置，两边共用同一份。
 */
function buildMessages(
  history: ChatMessage[],
  userInput: string,
  imageInstruction: boolean,
  imageMax: number,
  sessionId: string,
  assetBinding?: { worldBookIds?: string[]; presetId?: string; regexGroupIds?: string[] },
): ChatMessage[] | null {
  const assets = useAssetsStore();
  // 按对话绑定取资产：绑定 ∩ 资产页启用项。没绑定的对话跟随全局（旧行为）。
  // 资产页停用/删除后绑定自然失效（读不到就当没选）。
  const enabledBookIds: string[] = assets.getCurrentWorldBooks().map((w: any) => w._uid);
  let presetRaw: any;
  if (assetBinding?.presetId) {
    presetRaw = (assets.presets as any[]).find(
      (p) => p._uid === assetBinding!.presetId && p.enabled !== false,
    );
  }
  presetRaw = presetRaw ?? assets.getCurrentPreset();
  if (!presetRaw) return null;

  const preset = OpenAIPreset.fromJSON(presetRaw);
  let worldBooks = assets.getCurrentWorldBooks();
  if (assetBinding?.worldBookIds) {
    const want = assetBinding.worldBookIds.filter((id) => enabledBookIds.includes(id));
    worldBooks = worldBooks.filter((w: any) => want.includes(w._uid));
  }
  const worldBookJsons = worldBooks.map((w: any) => WorldBook.fromJSON(w).toStandaloneJSON());
  const storageSettings = useStorageSettingsStore();
  let regexScripts = storageSettings.llmContextRaw ? [] : assets.getCurrentRegexScripts();
  if (assetBinding?.regexGroupIds && !storageSettings.llmContextRaw) {
    const want = new Set(assetBinding.regexGroupIds);
    // 按组过滤：flattenForEngine 之后脚本已脱组，故回到组层重算。
    // 只保留「对话勾了 ∩ 资产页仍启用」的组，与世界书同一套基准。
    const groups = (assets.regexGroups as any[]).filter(
      (g: any) => want.has(g._uid) && assets.currentRegexGroupIds.includes(g._uid),
    );
    regexScripts = flattenForEngine(groups as any);
  }
  // 上下文裁剪预算来自原生配置镜像；网页版独立运行时回退到 store。
  const llmCfg = nativeLlm() ?? useApiConfigStore().currentConfig;
  const variables = useVariablesStore();

  const chatHistory: ChatMessage[] = [...history];
  if (userInput.trim()) chatHistory.push({ role: 'user', content: userInput });

  const result = assemblePrompt({
    preset,
    characterCard: assets.getCurrentCharacter(),
    worldBooks: worldBookJsons,
    regexScripts,
    chatHistory,
    contextWindowTokens: llmCfg?.contextWindow,
    userLookbackTokens: llmCfg?.userLookbackTokens,
    /*
     * ★ 必须用**原生会话 id**。
     *   用 `snapshot()` 的默认值会落到"网页当前会话"上 —— 那个 id 跟原生不是同一个，
     *   结果是 `{{getvar::x}}` 全读成空，而且发出去之前 `runWrites` 刚写的值也看不到。
     */
    variables: variables.snapshot(sessionId || undefined),
  });

  const msgs = result.finalMessages;
  if (imageInstruction && msgs.length > 0) {
    const tail = msgs[msgs.length - 1];
    msgs[msgs.length - 1] = {
      ...tail,
      content: tail.content + imageMarkerInstruction(true, imageMax),
    };
  }
  return msgs;
}

async function generate(reqJson: string): Promise<void> {
  let req: NativeGenRequest;
  try {
    req = JSON.parse(reqJson || '{}');
  } catch {
    return;
  }
  const requestId = req.requestId || '';
  if (!requestId) return;

  const variables = useVariablesStore();
  const sessionId = req.sessionId || '';

  /*
   * 流式节流。
   *
   * 每个 token 都过一次 JS→Java 桥的话，长回复能到每秒上百次调用，
   * 主线程光序列化字符串就卡住了。这里累积到 80ms 推一次**全量文本**
   * （不是增量）—— 原生直接整段替换，天然不会因为丢块而错位。
   */
  let acc = '';
  let lastPush = 0;
  let timer: ReturnType<typeof setTimeout> | null = null;
  const push = (force: boolean) => {
    const now = Date.now();
    if (!force && now - lastPush < 80) {
      if (!timer) {
        timer = setTimeout(() => {
          timer = null;
          push(true);
        }, 80);
      }
      return;
    }
    if (timer) {
      clearTimeout(timer);
      timer = null;
    }
    lastPush = now;
    callNative('onGenDelta', requestId, acc);
  };

  try {
    // 变量写宏：整段只跑这一次，跑完交给组装器的是干净文本
    const cleanInput = await variables.runWrites(req.userInput || '', sessionId);

    // 用户在这期间点了「停止」：别发请求了
    if (cancelled.delete(requestId)) return;

    const history = toChatMessages(req.messages || []);
    // 请求里带的是原生权威源，先落下：buildMessages / 发请求都读同一份。
    if ((req as any).config) (window as any).__dhpRuntimeLlm = (req as any).config;
    const auto = nativeAuto() ?? useImageStore().autoImage;
    const wantImage =
      typeof req.imageInstruction === 'boolean' ? req.imageInstruction : !!auto.enabled;
    const imageMax = req.imageInstructionMax ?? auto.maxPerReply ?? 2;

    // 按对话资产绑定（世界书/预设/正则）过滤后再组装；没绑定走全局配置
    const finalMessages = buildMessages(
      history,
      cleanInput,
      wantImage,
      imageMax,
      sessionId,
      (req as any).assetBinding,
    );
    if (!finalMessages) {
      callNative('onGenError', requestId, '还没有启用预设，请先在「资产」里选一个');
      return;
    }

    const cfg = nativeLlm() ?? useApiConfigStore().currentConfig;
    if (!cfg) {
      callNative('onGenError', requestId, '还没有配置对话 API');
      return;
    }
    if (!cfg.apiKey) {
      callNative('onGenError', requestId, '对话 API 缺少 Key');
      return;
    }

    const controller = new AbortController();
    running.set(requestId, controller);

    await streamChatCompletion(
      { ...cfg },
      finalMessages,
      {
        onToken: (delta: string) => {
          acc += delta;
          push(false);
        },
        onDone: (text: string) => {
          if (text) acc = text;
          running.delete(requestId);
          push(true);
          callNative('onGenDone', requestId, acc);
        },
      },
      controller.signal,
    );
  } catch (e: any) {
    running.delete(requestId);
    const aborted =
      e?.name === 'AbortError' || (e instanceof DOMException && e.name === 'AbortError');
    if (aborted) {
      // 停止时把最后不到 80ms 的内容补发一次，用户看到的就是"停在哪就是哪"
      if (acc) push(true);
      callNative('onGenStopped', requestId);
    } else {
      callNative('onGenError', requestId, e?.message ? String(e.message) : String(e));
    }
  }
}

/** 停止某一次生成。只掐指定 requestId，别的会话不受影响。 */
function stop(requestId: string): void {
  const c = running.get(requestId);
  if (!c) {
    /*
     * 还没真正发出请求（正在跑变量写宏 / 组装提示词），或者已经结束了。
     *
     * 这两种情况都不该让原生干等 —— 直接回一次「已停止」，
     * 否则界面会一直停在"生成中"，用户只能退出去重进。
     */
    if (cancelled.size > 64) cancelled.clear();
    cancelled.add(requestId);
    callNative('onGenStopped', requestId);
    return;
  }
  running.delete(requestId);
  try {
    c.abort();
  } catch {
    /* ignore */
  }
}

/**
 * 自动配图 / 标签生图需要的那份配置。
 *
 * 这里返回的是**一次性快照**：原生存起来自己发请求，不再回头问 web。
 * 理由很直接 —— 自动配图 = 把一段正文发给对话模型打标签 + 调一次生图接口，
 * 跟 ST 预设、世界书、正则统统无关。走 web 只会多一次往返和一份状态。
 */
function imageConfig(): string {
  const img = useImageStore();
  // 原生注入优先；网页版独立运行时回退到 store。
  const cfg: any = nativeImageMerged() ?? img.currentConfig ?? {};
  const llm: any = nativeLlm() ?? useApiConfigStore().currentConfig ?? {};
  const auto: any = nativeAuto() ?? img.autoImage;

  return JSON.stringify({
    config: {
      provider: cfg.provider || 'custom',
      // 原生按渲染引擎构造请求体：NovelAI 官方格式 / 本地部署 / 通用 OpenAI。
      // engine 是原生新加的显式字段，没有时回落到 web 原有的 provider。
      engine: cfg.engine || cfg.provider || 'custom',
      baseUrl: cfg.baseUrl || '',
      apiKey: cfg.apiKey || '',
      model: cfg.model || cfg.novelaiModel || '',
      novelaiModel: cfg.novelaiModel || '',
      endpoint: cfg.endpoint || '',
      width: cfg.width || 832,
      height: cfg.height || 1216,
      steps: cfg.steps || 28,
      cfgScale: cfg.cfgScale ?? 6,
      sampler: cfg.sampler || '',
      noiseSchedule: cfg.noiseSchedule || '',
      negativePrompt: cfg.negativePrompt || '',
      artistTags: cfg.artistTags || '',
      qualityTags: cfg.qualityTags || '',
      promptTemplate: cfg.promptTemplate || '',
    },
    autoImage: {
      enabled: !!auto.enabled,
      maxPerReply: auto.maxPerReply || 2,
      skipOptimization: auto.skipOptimization !== false,
      usePreset: !!auto.usePreset,
      presetMaxChars: auto.presetMaxChars || 4000,
    },
    llm: {
      baseUrl: llm.baseUrl || '',
      apiKey: llm.apiKey || '',
      model: llm.model || '',
      temperature: llm.temperature ?? 0.7,
      maxTokens: llm.maxTokens || 900,
    },
    // 预设里跟画面相关的片段（可选）。原生只在 usePreset 时拼进提示词。
    presetBlock: auto.usePreset ? presetSnippet(auto.presetMaxChars) : '',
    dnaTags: '',
  });
}

/** 从当前角色的 DNA 里取"外观标签"（如果有）。没装就当空串。 */
function presetSnippet(maxChars: number): string {
  try {
    const assets = useAssetsStore();
    const presetRaw: any = assets.getCurrentPreset();
    if (!presetRaw) return '';
    const prompts: any[] = presetRaw?.prompts ?? [];
    const text = prompts
      .filter((p) => p && p.enabled !== false && typeof p.content === 'string')
      .map((p) => p.content as string)
      .join('\n');
    return text.slice(0, Math.max(0, maxChars));
  } catch {
    return '';
  }
}

/**
 * 显示配置快照：原生渲染历史消息时用的正则 / 变量 / 宏上下文。
 *
 * 与生成通道分开是有意的 —— 显示是"每个会话打开时拉一次"，
 * 生成是"每次发送拉一次"，两条链路频率差两个数量级，不该共用一个接口。
 */
function displayConfig(sessionId: string): string {
  try {
    const assets = useAssetsStore();
    const variables = useVariablesStore();
    const character: any = assets.getCurrentCharacter();
    const scripts = assets.getCurrentRegexScripts();

    return JSON.stringify({
      scripts,
      variables: variables.snapshot(sessionId || undefined),
      hideReasoning: false,
      reasoningCutMarker: '',
      macroContext: {
        char: character?.name || character?.data?.name || '',
        user: '用户',
        persona: '',
        description: character?.description || character?.data?.description || '',
        scenario: character?.scenario || character?.data?.scenario || '',
        original: character?.first_mes || character?.data?.first_mes || '',
        mes_example: character?.mes_example || character?.data?.mes_example || '',
        chat: '',
        currentMessage: '',
        lastMessage: '',
      },
    });
  } catch (e) {
    console.warn('[gen] 读显示配置失败', e);
    return '{}';
  }
}

/**
 * 资产清单：原生"引擎设置"页用它显示"有哪些预设 / 世界书 / 角色可启用"。
 *
 * ★ 这里只导出**清单和当前启用项**，不导出内容。
 *   预设正文动辄几十万字，一个个塞过桥没有意义；原生只需要知道
 *   "有哪些、现在用的是哪个"，真正的内容在组装请求时由本文件自己取。
 */
function assetCatalog(): string {
  try {
    const a = useAssetsStore();
    const brief = (list: any[]) =>
      (list || []).map((x: any) => ({
        id: x._uid || x.id || '',
        name: x.name || x.data?.name || x._uid || '未命名',
      }));
    return JSON.stringify({
      presets: brief(a.presets),
      worldBooks: brief(a.worldBooks),
      characters: brief(a.characters),
      regexGroups: brief(a.regexGroups),
      current: {
        presetId: a.currentPresetId || '',
        characterId: a.currentCharacterId || '',
        worldBookIds: a.currentWorldBookIds || [],
        regexGroupIds: a.currentRegexGroupIds || [],
      },
    });
  } catch (e) {
    console.warn('[gen] 读资产清单失败', e);
    return '{}';
  }
}

/**
 * 切换启用项。数据仍然存在 ST 兼容层（网页的 assets store），
 * 原生只是"下指令"，不复制一份预设进来 —— 这样两边永远不会分叉。
 */
async function setActive(json: string): Promise<void> {
  let req: any;
  try {
    req = JSON.parse(json || '{}');
  } catch {
    return;
  }
  const a = useAssetsStore();
  if (typeof req.presetId === 'string') {
    await a.setCurrentPreset(req.presetId || undefined);
    // 「启用这个预设」＝设为默认 + 对话里可选；「停用」＝取消默认 + 从对话选项移除。
    // 资产页启停以这里为基准，对话勾选只在这个范围内生效。
    for (const p of a.presets as any[]) {
      if (p._uid === req.presetId) p.enabled = true;
    }
    if (!req.presetId) {
      // 取消默认时不动 enabled（仍可被对话选用）；显式停用走 presetsEnabled
    }
    await a.save();
  }
  if (typeof req.characterId === 'string') {
    await a.setCurrentCharacter(req.characterId || undefined);
  }
  if (Array.isArray(req.worldBookIds)) {
    const want: string[] = req.worldBookIds;
    for (const id of [...a.currentWorldBookIds]) {
      if (!want.includes(id)) await a.toggleWorldBook(id, false);
    }
    for (const id of want) {
      if (!a.currentWorldBookIds.includes(id)) await a.toggleWorldBook(id, true);
    }
  }
  if (Array.isArray(req.regexGroupIds)) {
    const want: string[] = req.regexGroupIds;
    for (const id of [...a.currentRegexGroupIds]) {
      if (!want.includes(id)) await a.toggleRegexGroup(id, false);
    }
    for (const id of want) {
      if (!a.currentRegexGroupIds.includes(id)) await a.toggleRegexGroup(id, true);
    }
    // 整组开关要连 group.enabled 一起写：flattenForEngine 会先按它过滤，
    // 只改 currentRegexGroupIds 会出现「组显示已启用但脚本一条都没跑」的错位。
    for (const g of a.regexGroups as any[]) g.enabled = want.includes(g._uid);
    await a.save();
  }
  // 预设「启用」＝对话里可不可选（可见性），与"使用中"（默认预设）解耦。
  if (Array.isArray(req.presetsEnabled)) {
    for (const pe of req.presetsEnabled) {
      const p = (a.presets as any[]).find((x) => x._uid === pe.id);
      if (p) p.enabled = pe.enabled !== false;
    }
    await a.save();
  }
}

// ===================== 原生设置页的 API 配置桥（window.__dhpApi） =====================
//
// 原生设置页的交互与网页版对齐：
//   填地址 + 密钥 → 拉取模型 → **勾选多个一次加入这家** → 点模型切换使用。
// 数据必须落在 apiConfig store：生成走 web 引擎，配置也必须同源，
// 否则原生存一份、web 读一份必然打架。
//
// 通信方式：
//   · getConfig()        —— 同步返回 JSON 串（evaluateJavascript 直接接返回值）
//   · 其余均为异步，完成后回调 AndroidNative.onApiResult（kind 标明哪条命令）
// 异步结果统一走 onApiResult，原生侧用一次性监听接住。

/** 原生设置页的结果回传（所有异步命令共用一条通道）。 */
function replyApiResult(payload: Record<string, unknown>): void {
  try {
    (window as any).AndroidNative?.onApiResult?.(JSON.stringify(payload));
  } catch {
    /* 不在 App 里（纯浏览器开发）时没有这个桥，静默即可 */
  }
}

/** 当前 API 配置 + 运营商/模型全量概览。同步调用，原生 evaluateJavascript 接返回值。 */
function apiConfigSummary(): string {
  const api = useApiConfigStore();
  const cur = api.currentConfig;
  return JSON.stringify({
    baseUrl: cur?.baseUrl || '',
    apiKey: cur?.apiKey || '',
    model: cur?.model || '',
    modelName: cur?._name || '',
    uid: cur?._uid || '',
    params: {
      temperature: cur?.temperature ?? 0.8,
      maxTokens: cur?.maxTokens ?? 2000,
      contextWindow: cur?.contextWindow ?? 1000000,
      userLookbackTokens: cur?.userLookbackTokens ?? 200000,
    },
    providers: api.providers.map((p) => ({
      key: p.key,
      note: p.note || '',
      baseUrl: p.baseUrl || '',
      hasKey: p.hasKey,
      models: p.models.map((m) => ({
        uid: m._uid,
        model: m.model || '',
        name: m._name || '',
        current: m._uid === api.currentConfigId,
      })),
    })),
  });
}

/**
 * 统一的异步命令入口：原生把 { cmd, ... } 串进来，这里分发。
 * 单独一个入口省得桥上堆一堆方法名，也方便回传时带 kind。
 */
async function apiCommand(raw: string): Promise<void> {
  let req: any = {};
  try {
    req = JSON.parse(raw || '{}');
  } catch {
    replyApiResult({ kind: 'error', ok: false, error: '参数不是合法 JSON' });
    return;
  }
  const cmd = String(req.cmd || '');
  try {
    const api = useApiConfigStore();
    await api.load();

    switch (cmd) {
      /** 保存地址/密钥（运营商级同步）。 */
      case 'saveConn': {
        let cur = api.currentConfig;
        if (!cur) {
          await api.addConfig('默认配置');
          cur = api.currentConfig;
        }
        if (!cur) throw new Error('无法创建 API 配置');
        const patch: { baseUrl?: string; apiKey?: string } = {};
        if (typeof req.baseUrl === 'string') patch.baseUrl = req.baseUrl;
        if (typeof req.apiKey === 'string') patch.apiKey = req.apiKey;
        if (Object.keys(patch).length) await api.updateProvider(cur._uid, patch);
        break;
      }
      /**
       * 多选批量加入：先把这家连接信息对齐，再 addModelsToProvider。
       * 与网页版「勾选多个 → 一次加入这家」同一条路径。
       */
      case 'addModels': {
        let cur = api.currentConfig;
        if (!cur) {
          await api.addConfig('默认配置');
          cur = api.currentConfig;
        }
        if (!cur) throw new Error('无法创建 API 配置');
        const patch: { baseUrl?: string; apiKey?: string } = {};
        if (typeof req.baseUrl === 'string') patch.baseUrl = req.baseUrl;
        if (typeof req.apiKey === 'string') patch.apiKey = req.apiKey;
        if (Object.keys(patch).length) await api.updateProvider(cur._uid, patch);
        const models: string[] = Array.isArray(req.models)
          ? req.models.map((m: unknown) => String(m)).filter(Boolean)
          : [];
        if (!models.length) throw new Error('没有要添加的模型');
        const r = await api.addModelsToProvider(cur._uid, models);
        replyApiResult({ kind: cmd, ok: true, added: r.added, skipped: r.skipped });
        return;
      }
      /** 切换使用的模型（按 uid）。 */
      case 'useModel': {
        const uid = String(req.uid || '');
        if (!api.configs.some((c) => c._uid === uid)) throw new Error('模型不存在或已删除');
        api.currentConfigId = uid;
        await api.save();
        break;
      }
      /** 删除单个模型（按 uid）。 */
      case 'removeModel': {
        const uid = String(req.uid || '');
        if (!api.configs.some((c) => c._uid === uid)) throw new Error('模型不存在或已删除');
        await api.removeModelFromProvider(uid);
        break;
      }
      /** 保存当前模型的生成参数。 */
      case 'saveParams': {
        const cur = api.currentConfig;
        if (!cur) throw new Error('当前没有选中的模型');
        const p = req.params || {};
        if (p.temperature !== undefined) cur.temperature = Number(p.temperature);
        if (p.maxTokens !== undefined) cur.maxTokens = Number(p.maxTokens);
        if (p.contextWindow !== undefined) cur.contextWindow = Number(p.contextWindow);
        if (p.userLookbackTokens !== undefined) {
          cur.userLookbackTokens = Number(p.userLookbackTokens);
        }
        await api.save();
        break;
      }

      // ─────────── 生图接口（交互对齐 LLM 运营商） ───────────

      /** 保存生图接口的地址/密钥（按 uid 或当前接口）。 */
      case 'imgSaveConn': {
        const img = useImageStore();
        const uid = String(req.uid || img.currentApiId || '');
        let entry = img.apiConfigs.find((c) => c._uid === uid);
        if (!entry) entry = await img.addApi('新接口');
        const patch: { baseUrl?: string; apiKey?: string } = {};
        if (typeof req.baseUrl === 'string') patch.baseUrl = req.baseUrl;
        if (typeof req.apiKey === 'string') patch.apiKey = req.apiKey;
        if (Object.keys(patch).length) await img.updateApi(entry._uid, patch);
        break;
      }
      /**
       * 多选批量加入生图模型：每个模型建一条接口记录（同 baseUrl/apiKey），
       * 已存在「同地址+同模型」的跳过 —— 与 LLM 侧语义一致。
       */
      case 'imgAddModels': {
        const img = useImageStore();
        let cur = img.apiConfigs.find((c) => c._uid === img.currentApiId);
        if (!cur) cur = await img.addApi('新接口');
        const patch: { baseUrl?: string; apiKey?: string } = {};
        if (typeof req.baseUrl === 'string') patch.baseUrl = req.baseUrl;
        if (typeof req.apiKey === 'string') patch.apiKey = req.apiKey;
        if (Object.keys(patch).length) await img.updateApi(cur._uid, patch);
        const baseUrl = (req.baseUrl ?? cur.baseUrl ?? '') as string;
        const models: string[] = Array.isArray(req.models)
          ? req.models.map((m: unknown) => String(m)).filter(Boolean)
          : [];
        if (!models.length) throw new Error('没有要添加的模型');
        let added = 0;
        let skipped = 0;
        // 当前条目还没选模型 → 直接把第一个模型写进它，避免多出一条"空模型"
        let anchorEmpty = !(cur.model || cur.novelaiModel || '');
        for (const model of models) {
          const exists = img.apiConfigs.some(
            (c) => (c.baseUrl || '') === baseUrl && (c.model || c.novelaiModel || '') === model,
          );
          if (exists) {
            skipped++;
            continue;
          }
          if (anchorEmpty) {
            await img.updateApi(cur._uid, { model });
            anchorEmpty = false;
            added++;
            continue;
          }
          const made = await img.addApi(model);
          await img.updateApi(made._uid, {
            baseUrl: baseUrl || (req.baseUrl ? String(req.baseUrl) : ''),
            apiKey: req.apiKey ? String(req.apiKey) : cur.apiKey,
            model,
          });
          added++;
        }
        replyApiResult({ kind: cmd, ok: true, added, skipped });
        return;
      }
      /** 切换使用的生图接口。 */
      case 'imgUseApi': {
        const uid = String(req.uid || '');
        if (!useImageStore().apiConfigs.some((c) => c._uid === uid)) {
          throw new Error('接口不存在或已删除');
        }
        await useImageStore().setCurrentApi(uid);
        break;
      }
      /** 删除生图接口。 */
      case 'imgRemoveApi': {
        const uid = String(req.uid || '');
        if (!useImageStore().apiConfigs.some((c) => c._uid === uid)) {
          throw new Error('接口不存在或已删除');
        }
        await useImageStore().removeApi(uid);
        break;
      }
      /** 保存生图参数：尺寸走 API，提示词/负面走风格（与 store 的职责划分一致）。 */
      case 'imgSaveParams': {
        const img = useImageStore();
        const p = req.params || {};
        const api = img.apiConfigs.find((c) => c._uid === img.currentApiId);
        const apiPatch: Record<string, unknown> = {};
        if (p.width !== undefined) apiPatch.width = Number(p.width);
        if (p.height !== undefined) apiPatch.height = Number(p.height);
        if (Object.keys(apiPatch).length) {
          if (!api) throw new Error('当前没有选中的生图接口');
          await img.updateApi(api._uid, apiPatch);
        }
        const styleId = img.currentStyleId;
        const stylePatch: Record<string, unknown> = {};
        if (typeof p.negativePrompt === 'string') stylePatch.negativePrompt = p.negativePrompt;
        if (typeof p.artistTags === 'string') stylePatch.artistTags = p.artistTags;
        if (typeof p.qualityTags === 'string') stylePatch.qualityTags = p.qualityTags;
        if (typeof p.promptTemplate === 'string') stylePatch.promptTemplate = p.promptTemplate;
        if (Object.keys(stylePatch).length) {
          if (!styleId) throw new Error('当前没有选中的生图风格');
          await img.updateStyle(styleId, stylePatch);
        }
        break;
      }

      // ─────────── 运营商管理（LLM 与生图各一套） ───────────

      /** 新建 LLM 运营商（地址+密钥），并切为当前。 */
      case 'llmAddProvider': {
        const api = useApiConfigStore();
        await api.load();
        const name = String(req.name || hostOf(req.baseUrl) || '新运营商');
        await api.addConfig(name);
        const cur = api.currentConfig;
        if (!cur) throw new Error('创建运营商失败');
        await api.updateProvider(cur._uid, {
          baseUrl: String(req.baseUrl || ''),
          apiKey: String(req.apiKey || ''),
        });
        // 运营商显示名存在 providerNotes（按 baseUrl 分组），不是 model 的 _name。
        // 上一版漏了这一步 —— 用户填了名字却看不到，以为「没法自己加名字」。
        const prov = api.providers.find((pp) => pp.models.some((m) => m._uid === cur._uid));
        if (prov && req.name) await api.setProviderNote(prov.key, String(req.name));
        replyApiResult({ kind: cmd, ok: true, uid: cur._uid });
        return;
      }

      /** 新建生图运营商（地址+密钥），并切为当前。 */
      case 'imgAddProvider': {
        const img = useImageStore();
        const name = String(req.name || hostOf(req.baseUrl) || '新运营商');
        const made = await img.addApi(name);
        await img.updateApi(made._uid, {
          baseUrl: String(req.baseUrl || ''),
          apiKey: String(req.apiKey || ''),
          // 新建运营商不继承默认模型：模型由「拉取模型」添加
          model: '',
          novelaiModel: '',
        });
        await img.setCurrentApi(made._uid);
        replyApiResult({ kind: cmd, ok: true, uid: made._uid });
        return;
      }

      /**
       * 取某套风格的参考图（已解析成 dataURL）。
       * thumb 可能是 blob: 引用（网页端压缩后存图库），必须走 resolveCached 解析，
       * 原生直接拿来当 base64 用当然显示不出来（用户："直接是单个字"）。
       */
      case 'getStyleThumb': {
        const img = useImageStore();
        const st: any = (img.styleConfigs || []).find((x) => x._uid === req.uid);
        const raw = st?.thumb || '';
        const dataUrl = raw ? (await resolveCached(raw)) || '' : '';
        replyApiResult({ kind: cmd, ok: true, uid: req.uid, thumb: dataUrl || '' });
        return;
      }

      // ─────────── 运营商重命名 ───────────

      /** 改 LLM 运营商名字（providerNotes，按 baseUrl 分组）。 */
      case 'llmRenameProvider': {
        const api = useApiConfigStore();
        await api.load();
        const key = String(req.key || '');
        const name = String(req.name || '').trim();
        if (!key) throw new Error('缺少运营商标识');
        await api.setProviderNote(key, name);
        break;
      }

      /**
       * 改生图运营商名字。
       * 生图的显示名就是组内条目的 name（没有单独的 notes 表），
       * 所以整组一起改，否则换模型时名字又跳回旧的。
       */
      case 'imgRenameProvider': {
        const img = useImageStore();
        const key = String(req.key || '');
        const name = String(req.name || '').trim();
        if (!name) throw new Error('名字不能为空');
        const list = (img.apiConfigs || []).filter(
          (a) => ((a.baseUrl || '').trim().replace(/\/+$/, '') || `__own__:${a._uid}`) === key,
        );
        if (!list.length) throw new Error('找不到这个运营商');
        for (const a of list) await img.updateApi(a._uid, { name });
        break;
      }

      // ─────────── 生图风格（多套 + 参考图） ───────────

      /** 新建风格。 */
      case 'imgAddStyle': {
        const img = useImageStore();
        const made = await img.addStyle(String(req.name || '新风格'));
        replyApiResult({ kind: cmd, ok: true, uid: made?._uid || '' });
        return;
      }
      /** 切换使用的风格。 */
      case 'imgUseStyle': {
        const img = useImageStore();
        const uid = String(req.uid || '');
        if (!img.styleConfigs.some((s) => s._uid === uid)) throw new Error('风格不存在或已删除');
        await img.setCurrentStyle(uid);
        break;
      }
      /** 删除风格（最后一支不允许删空）。 */
      case 'imgRemoveStyle': {
        const img = useImageStore();
        const uid = String(req.uid || '');
        if (img.styleConfigs.length <= 1) throw new Error('至少要保留一套风格');
        await img.removeStyle(uid);
        break;
      }
      /** 重命名风格。 */
      case 'imgRenameStyle': {
        const img = useImageStore();
        const uid = String(req.uid || '');
        const s = img.styleConfigs.find((x) => x._uid === uid);
        if (!s) throw new Error('风格不存在');
        await img.updateStyle(uid, { name: String(req.name || s.name) });
        break;
      }
      /** 保存某套风格的提示词字段与参考图（thumb 只作展示）。 */
      case 'imgSaveStyle': {
        const img = useImageStore();
        const uid = String(req.uid || img.currentStyleId || '');
        const s = img.styleConfigs.find((x) => x._uid === uid);
        if (!s) throw new Error('风格不存在');
        const p = req.style || {};
        const patch: Record<string, unknown> = {};
        if (typeof p.artistTags === 'string') patch.artistTags = p.artistTags;
        if (typeof p.qualityTags === 'string') patch.qualityTags = p.qualityTags;
        if (typeof p.negativePrompt === 'string') patch.negativePrompt = p.negativePrompt;
        if (typeof p.promptTemplate === 'string') patch.promptTemplate = p.promptTemplate;
        if (typeof p.thumb === 'string') patch.thumb = p.thumb;
        if (Object.keys(patch).length) await img.updateStyle(uid, patch);
        break;
      }

      // ─────────── 对话生图（自动配图的配置与快速出图） ───────────

      /** 保存对话生图全局设置：上文条数 / 优化开关 / 提示词模型。 */
      case 'imgSaveAuto': {
        const img = useImageStore();
        const p = req.auto || {};
        if (p.enabled !== undefined) await img.updateAutoImage({ enabled: !!p.enabled });
        if (p.maxPerReply !== undefined) await img.updateAutoImage({ maxPerReply: Number(p.maxPerReply) });
        if (p.quickContextWindow !== undefined) {
          await img.updateQuickContext(Number(p.quickContextWindow), !!p.aiOnly);
        }
        // 优化开关与提示词模型挂在"接口"上（同一家共用一套 LLM 设置）
        const apiId = String(req.uid || img.currentApiId || '');
        const target = img.apiConfigs.find((c) => c._uid === apiId);
        if (target) {
          const patch: Record<string, unknown> = {};
          if (p.useLlmOptimization !== undefined) patch.useLlmOptimization = !!p.useLlmOptimization;
          if (typeof p.llmConfigId === 'string') patch.llmConfigId = p.llmConfigId || undefined;
          if (Object.keys(patch).length) await img.updateApi(target._uid, patch);
        }
        break;
      }

      /** 保存采样参数（挂在当前风格上）。 */
      case 'imgSaveSampling': {
        const img = useImageStore();
        const uid = String(req.uid || img.currentStyleId || '');
        const st = img.styleConfigs.find((x) => x._uid === uid);
        if (!st) throw new Error('风格不存在');
        const p = req.sampling || {};
        const patch: Record<string, unknown> = {};
        if (p.steps !== undefined) patch.steps = Number(p.steps);
        if (typeof p.sampler === 'string') patch.sampler = p.sampler;
        if (p.cfgScale !== undefined) patch.cfgScale = Number(p.cfgScale);
        if (typeof p.noiseSchedule === 'string') patch.noiseSchedule = p.noiseSchedule;
        if (Object.keys(patch).length) await img.updateStyle(uid, patch);
        break;
      }

      /**
       * 对话生图：把"最近 N 条上下文"交给 LLM 转成生图标签。
       * 用不用模型由调用方决定；这里只负责"用模型时怎么调"。
       * 结果经 onApiResult(kind=optimizeImagePrompt, prompt=…)。
       */
      case 'optimizeImagePrompt': {
        // 提示词模型配置来自原生镜像；网页版独立运行时才回退到 store。
        const doc = nativeDoc();
        const api = useApiConfigStore();
        if (!doc) await api.load();
        const list: any[] = doc ? doc.llm.configs || [] : (api.configs || []);
        const reqCfg = list.find((c) => c._uid === req.llmConfigId);
        const cfg = reqCfg || (doc ? nativeLlm(req.llmConfigId) || nativeLlm() : api.currentConfig);
        if (!cfg) throw new Error('没有可用的对话模型配置');
        const text = String(req.context || '').slice(0, 6000);
        if (!text.trim()) throw new Error('上下文为空');
        const sys =
          '你是 NovelAI 标签撰写器。把给定的剧情片段概括成一段英文 Danbooru 风格标签，' +
          '只输出标签本身，用英文逗号分隔，不要解释、不要引号、不要换行。' +
          '包含人物外观、服装、动作、场景、镜头与氛围；不要出现人名。';
        const out = await streamChatCompletion(
          {
            ...(cfg as any),
            temperature: 0.5,
            maxTokens: 220,
            stream: false,
          } as any,
          [
            { role: 'system', content: sys },
            { role: 'user', content: text },
          ],
          {},
        );
        const tags = String(out || '').replace(/^["'`]|["'`]$/g, '').trim();
        if (!tags) throw new Error('模型没有返回标签');
        replyApiResult({ kind: cmd, ok: true, prompt: tags });
        return;
      }

      /** 保存显示设置（思维链/正则作用范围）。 */
      case 'saveDisplay': {
        const s = useStorageSettingsStore();
        const p = req.settings || {};
        if (typeof p.hideReasoning === 'boolean') s.hideReasoning = p.hideReasoning;
        if (typeof p.reasoningCutMarker === 'string') s.reasoningCutMarker = p.reasoningCutMarker;
        if (typeof p.llmContextRaw === 'boolean') s.llmContextRaw = p.llmContextRaw;
        if (typeof p.exportRaw === 'boolean') s.exportRaw = p.exportRaw;
        await s.save?.();
        break;
      }

      // ─────────── 引擎资产开关（预设/世界书/正则） ───────────

      /** 切换资产启用：直接复用 __dhpGen.setActive 的既有语义。 */
      case 'setAssets': {
        await setActive(JSON.stringify(req));
        break;
      }

      /**
       * 导入资产：原生资产页选了 ST 的 .json（预设/世界书/正则）。
       * 直接走 importAnyJson 的统一识别（支持数组混装、容器对象、
       * entries 对象索引等历史坑位），原生不用自己判类型。
       */
      case 'importAsset': {
        const a = useAssetsStore();
        let raw: unknown = req.raw;
        if (typeof raw === 'string') {
          try {
            raw = JSON.parse(raw);
          } catch {
            throw new Error('文件内容不是合法 JSON');
          }
        }
        const items = await a.importAnyJson(raw);
        replyApiResult({ kind: cmd, ok: true, imported: items.length });
        return;
      }

      /** 导出资产：按 id 取库里的原始对象，原样 JSON 回传给原生落盘。 */
      case 'exportAsset': {
        const a = useAssetsStore();
        const id = String(req.id || '');
        const find = (list: any[]) => list.find((x) => x._uid === id);
        const obj =
          find(a.presets) || find(a.worldBooks) || find(a.regexGroups) || find(a.characters);
        if (!obj) throw new Error('找不到要导出的资产');
        replyApiResult({
          kind: cmd,
          ok: true,
          id,
          name: obj.name || obj.data?.name || 'asset',
          json: JSON.stringify(obj, null, 2),
        });
        return;
      }

      // ─────────── 资产子条目编辑（世界书条目 / 预设 prompts） ───────────

      /** 世界书条目保存（ST 字段全集：触发关键词 / 选择性触发 / 常驻 / 概率 / 深度 / 位置 / 角色）。 */
      case 'wbSaveEntry': {
        const a = useAssetsStore();
        const book: any = (a.worldBooks || []).find((w: any) => w._uid === req.bookId);
        const list = book?.data?.entries || book?.entries;
        if (!list) throw new Error('找不到世界书');
        const e: any = list.find((x: any) => Number(x.uid) === Number(req.uid));
        if (!e) throw new Error('找不到条目');
        const p = req.entry || {};
        const splitKeys = (s: string) =>
          s.split(/[,，\n]/).map((x: string) => x.trim()).filter(Boolean);
        if (typeof p.comment === 'string') e.comment = p.comment;
        if (typeof p.content === 'string') e.content = p.content;
        if (typeof p.keys === 'string') e.keys = splitKeys(p.keys);
        if (typeof p.secondary === 'string') e.secondary_keys = splitKeys(p.secondary);
        if (typeof p.constant === 'boolean') e.constant = p.constant;
        if (typeof p.selective === 'boolean') e.selective = p.selective;
        if (p.selectiveLogic !== undefined) e.selectiveLogic = Number(p.selectiveLogic);
        if (typeof p.enabled === 'boolean') e.enabled = p.enabled;
        if (p.probability !== undefined) e.probability = Number(p.probability);
        if (p.useProbability !== undefined) e.useProbability = !!p.useProbability;
        if (p.depth !== undefined) e.depth = Number(p.depth);
        if (p.position !== undefined) e.position = Number(p.position);
        if (p.role !== undefined) e.role = p.role === null ? null : Number(p.role);
        if (p.insertion_order !== undefined) e.insertion_order = Number(p.insertion_order);
        await a.save();
        break;
      }
      /** 世界书改名（不影响 _uid / 条目 / 当前启用状态）。 */
      case 'wbRename': {
        const a = useAssetsStore();
        const book: any = (a.worldBooks || []).find((w: any) => w._uid === req.bookId);
        if (!book) throw new Error('找不到世界书');
        const name = String(req.name ?? '').trim();
        if (!name) throw new Error('名称不能为空');
        setAssetName(book, name);
        await a.save();
        break;
      }
      /** 世界书新建条目（uid 取现有最大值 +1）。 */
      case 'wbAddEntry': {
        const a = useAssetsStore();
        const book: any = (a.worldBooks || []).find((w: any) => w._uid === req.bookId);
        const list = book?.data?.entries || book?.entries;
        if (!list) throw new Error('找不到世界书');
        const maxUid = list.reduce((m: number, x: any) => Math.max(m, Number(x.uid) || 0), -1);
        list.push({
          uid: maxUid + 1,
          comment: '新条目',
          keys: [],
          secondary_keys: [],
          content: '',
          constant: false,
          selective: true,
          selectiveLogic: 0,
          enabled: true,
          insertion_order: 100,
          position: 0,
          depth: 4,
          probability: 100,
          useProbability: false,
        });
        await a.save();
        break;
      }
      /** 世界书删除条目。 */
      case 'wbRemoveEntry': {
        const a = useAssetsStore();
        const book: any = (a.worldBooks || []).find((w: any) => w._uid === req.bookId);
        const list = book?.data?.entries || book?.entries;
        if (!list) throw new Error('找不到世界书');
        const i = list.findIndex((x: any) => Number(x.uid) === Number(req.uid));
        if (i >= 0) list.splice(i, 1);
        await a.save();
        break;
      }
      /**
       * 预设子条目保存：**部分更新**（只传 enabled 就是快捷开关，不碰其它字段）。
       * enabled 会同步 prompt_order，见 syncPromptEnabled 的说明。
       */
      case 'presetSavePrompt': {
        const a = useAssetsStore();
        const ps: any = (a.presets || []).find((x: any) => x._uid === req.presetId);
        const list = ps?.data?.prompts || ps?.prompts;
        if (!list) throw new Error('找不到预设');
        const it: any = list.find((x: any, i: number) => String(x.identifier || `#${i}`) === req.identifier);
        if (!it) throw new Error('找不到子条目');
        const p = req.prompt || {};
        if (typeof p.name === 'string') it.name = p.name;
        if (typeof p.content === 'string') it.content = p.content;
        if (typeof p.role === 'string') it.role = p.role;
        if (typeof p.system === 'boolean') it.system_prompt = p.system;
        if (typeof p.marker === 'boolean') it.marker = p.marker;
        if (typeof p.enabled === 'boolean') {
          it.enabled = p.enabled;
          syncPromptEnabled(ps, String(it.identifier ?? req.identifier), p.enabled);
        }
        await a.save();
        break;
      }
      /**
       * 子条目排序 / 激活。
       *
       * 顺序的权威是 prompt_order 的 order[]，不是 prompts[] 数组 ——
       * 所以这两个命令都只动排序表（presetOrder.ts），绝不重排提示词库本身。
       *
       * dir=-1 上移 / dir=1 下移；边界或找不到时什么都不做（原生会提示「已经到顶」）。
       * 「加入排序」对应 ST / Tavo 里把未激活提示词拖回主列表：追加到末尾并启用。
       */
      case 'presetMovePrompt': {
        const a = useAssetsStore();
        const ps: any = (a.presets || []).find((x: any) => x._uid === req.presetId);
        if (!ps) throw new Error('找不到预设');
        const dir = req.dir === -1 || req.dir === 'up' ? -1 : 1;
        if (movePromptInOrder(ps, String(req.identifier || ''), dir)) await a.save();
        break;
      }
      case 'presetActivatePrompt': {
        const a = useAssetsStore();
        const ps: any = (a.presets || []).find((x: any) => x._uid === req.presetId);
        if (!ps) throw new Error('找不到预设');
        if (activatePromptInOrder(ps, String(req.identifier || ''))) await a.save();
        break;
      }
      /**
       * 预设改名。只改显示名，不动 id / 子条目 / prompt_order，
       * 所以正在进行的对话引用的 presetId 不会失效。
       */
      case 'presetRename': {
        const a = useAssetsStore();
        const ps: any = (a.presets || []).find((x: any) => x._uid === req.presetId);
        if (!ps) throw new Error('找不到预设');
        const name = String(req.name ?? '').trim();
        if (!name) throw new Error('名称不能为空');
        setAssetName(ps, name);
        await a.save();
        break;
      }
      /** 预设新建子条目：同时把它接进 prompt_order，否则新条目能编辑却不会进提示词。 */
      case 'presetAddPrompt': {
        const a = useAssetsStore();
        const ps: any = (a.presets || []).find((x: any) => x._uid === req.presetId);
        const list = ps?.data?.prompts || ps?.prompts;
        if (!list) throw new Error('找不到预设');
        const id = `native-${Date.now()}`;
        list.push({ identifier: id, name: '新子条目', content: '', role: 'system', system_prompt: true, enabled: true });
        // 只接进「当前生效那一组」，不往所有角色组里复制一份（那会让别的角色组莫名多条）。
        activatePromptInOrder(ps, id);
        await a.save();
        break;
      }
      /** 预设删除子条目：prompts 与 prompt_order 里的同一 identifier 一起清掉。 */
      case 'presetRemovePrompt': {
        const a = useAssetsStore();
        const ps: any = (a.presets || []).find((x: any) => x._uid === req.presetId);
        const list = ps?.data?.prompts || ps?.prompts;
        if (!list) throw new Error('找不到预设');
        const i = list.findIndex((x: any, j: number) => String(x.identifier || `#${j}`) === req.identifier);
        if (i >= 0) list.splice(i, 1);
        const po = ps?.data?.prompt_order ?? ps?.prompt_order;
        const drop = (arr: any[]) => {
          for (let k = arr.length - 1; k >= 0; k--) {
            const o = arr[k];
            if (o && typeof o === 'object' && Array.isArray(o.order)) drop(o.order);
            else if (String(o?.identifier ?? o?.id ?? o) === req.identifier) arr.splice(k, 1);
          }
        };
        if (Array.isArray(po)) drop(po);
        else if (po && typeof po === 'object') for (const v of Object.values(po)) if (Array.isArray(v)) drop(v);
        await a.save();
        break;
      }

      // ─────────── 正则组内脚本（逐条开关 / 编辑 / 新建 / 删除） ───────────

      /** 按组 id + 脚本 id（或 #下标）找一条脚本；找不到直接抛，原生会显示错误。 */
      case 'regexSaveScript': {
        const a = useAssetsStore();
        const g: any = (a.regexGroups || []).find((x: any) => x._uid === req.groupId);
        if (!g) throw new Error('找不到正则组');
        const list = g.scripts || (g.scripts = []);
        const idx = list.findIndex((s: any, i: number) => String(s.id || `#${i}`) === req.scriptId);
        if (idx < 0) throw new Error('找不到这条正则');
        const s = list[idx];
        const p = req.script || {};
        if (typeof p.name === 'string') s.scriptName = p.name;
        if (typeof p.findRegex === 'string') s.findRegex = p.findRegex;
        if (typeof p.replaceString === 'string') s.replaceString = p.replaceString;
        if (typeof p.disabled === 'boolean') s.disabled = p.disabled;
        if (typeof p.markdownOnly === 'boolean') s.markdownOnly = p.markdownOnly;
        if (typeof p.promptOnly === 'boolean') s.promptOnly = p.promptOnly;
        if (typeof p.runOnEdit === 'boolean') s.runOnEdit = p.runOnEdit;
        if (p.substituteRegex !== undefined) s.substituteRegex = Number(p.substituteRegex) || 0;
        if (Array.isArray(p.placement)) s.placement = p.placement.map((v: any) => Number(v));
        if (Array.isArray(p.trimStrings)) s.trimStrings = p.trimStrings;
        if (p.minDepth !== undefined) s.minDepth = p.minDepth === '' || p.minDepth === null ? null : Number(p.minDepth);
        if (p.maxDepth !== undefined) s.maxDepth = p.maxDepth === '' || p.maxDepth === null ? null : Number(p.maxDepth);
        g.updatedAt = Date.now();
        await a.save();
        break;
      }
      /** 正则组改名：只改组名，组内脚本与启用状态原样保留。 */
      case 'regexRename': {
        const a = useAssetsStore();
        const g: any = (a.regexGroups || []).find((x: any) => x._uid === req.groupId);
        if (!g) throw new Error('找不到正则组');
        const name = String(req.name ?? '').trim();
        if (!name) throw new Error('名称不能为空');
        setAssetName(g, name);
        g.updatedAt = Date.now();
        await a.save();
        break;
      }
      /** 组内新建一条脚本，默认关闭（避免空正则立刻参与匹配报错）。 */
      case 'regexAddScript': {
        const a = useAssetsStore();
        const g: any = (a.regexGroups || []).find((x: any) => x._uid === req.groupId);
        if (!g) throw new Error('找不到正则组');
        const list = g.scripts || (g.scripts = []);
        list.push({
          id: `native-rx-${Date.now()}`,
          scriptName: '新正则',
          findRegex: '',
          replaceString: '',
          trimStrings: [],
          placement: [2],
          disabled: true,
          markdownOnly: false,
          promptOnly: false,
          runOnEdit: false,
          substituteRegex: 0,
          minDepth: null,
          maxDepth: null,
        });
        g.updatedAt = Date.now();
        await a.save();
        break;
      }
      case 'regexRemoveScript': {
        const a = useAssetsStore();
        const g: any = (a.regexGroups || []).find((x: any) => x._uid === req.groupId);
        if (!g) throw new Error('找不到正则组');
        const list: any[] = g.scripts || [];
        const i = list.findIndex((s: any, j: number) => String(s.id || `#${j}`) === req.scriptId);
        if (i >= 0) list.splice(i, 1);
        g.updatedAt = Date.now();
        await a.save();
        break;
      }

      default:
        throw new Error(`未知命令：${cmd}`);
    }
    replyApiResult({ kind: cmd, ok: true });
  } catch (e) {
    replyApiResult({ kind: cmd, ok: false, error: e instanceof Error ? e.message : String(e) });
  }
}

/** 生图接口列表：交互对齐 LLM 运营商（地址/密钥/拉模型/多选添加/快捷切换）。 */
/** 从地址里取主机名，作为运营商默认显示名。 */
function hostOf(baseUrl?: string): string {
  try {
    return new URL(String(baseUrl || '')).host;
  } catch {
    return '';
  }
}

function imageApiSummary(): string {
  const img = useImageStore();
  const api = useApiConfigStore();
  const cur: any = img.currentConfig || {};
  return JSON.stringify({
    // 兼容字段：扁平列表（老调用方还在用；新界面请用 providers）
    apis: (img.apiConfigs || []).map((a) => ({
      uid: a._uid,
      name: a.name || '',
      provider: a.provider || 'custom',
      baseUrl: a.baseUrl || '',
      hasKey: !!(a.apiKey || a.novelaiToken),
      model: a.model || a.novelaiModel || '',
      current: a._uid === img.currentApiId,
    })),
    // 运营商 = 同一 baseUrl（地址相同就是同一家，其下可挂多个模型）
    providers: (() => {
      const groups = new Map<string, any[]>();
      for (const a of img.apiConfigs || []) {
        const key = (a.baseUrl || '').trim().replace(/\/+$/, '') || `__own__:${a._uid}`;
        const arr = groups.get(key);
        if (arr) arr.push(a);
        else groups.set(key, [a]);
      }
      return [...groups.entries()].map(([key, list]) => ({
        key,
        baseUrl: list[0].baseUrl || '',
        name: list[0].name || hostOf(list[0].baseUrl) || '(未填地址)',
        hasKey: list.some((x) => !!(x.apiKey || x.novelaiToken)),
        current: list.some((x) => x._uid === img.currentApiId),
        models: list
          .map((x) => ({
            uid: x._uid,
            model: x.model || x.novelaiModel || '',
            name: x.name || x.model || '',
            current: x._uid === img.currentApiId,
          }))
          .filter((m) => m.model !== '' || list.length === 1),
      }));
    })(),
    current: {
      uid: img.currentApiId || '',
      // 当前模型所属运营商名（界面显示「运营商 · 模型」用，别再只显示模型）
      apiName:
        (img.apiConfigs || []).find(
          (a) =>
            ((a.baseUrl || '').trim().replace(/\/+$/, '') || `__own__:${a._uid}`) ===
            ((cur.baseUrl || '').trim().replace(/\/+$/, '') || `__own__:${img.currentApiId}`),
        )?.name || hostOf(cur.baseUrl) || '',
      baseUrl: cur.baseUrl || '',
      apiKey: cur.apiKey || '',
      model: cur.model || cur.novelaiModel || '',
      width: cur.width || 832,
      height: cur.height || 1216,
      negativePrompt: cur.negativePrompt || '',
      // 对话生图：提示词优化开关与所用 LLM
      useLlmOptimization: img.currentApi?.useLlmOptimization !== false,
      llmConfigId: img.currentApi?.llmConfigId || '',
    },
    // 对话生图全局设置
    auto: {
      enabled: !!img.autoImage?.enabled,
      maxPerReply: img.autoImage?.maxPerReply ?? 2,
      quickContextWindow: img.quickContextWindow ?? 5,
      skipOptimization: img.autoImage?.skipOptimization !== false,
    },
    // 可选的提示词模型（LLM 配置清单）
    llmConfigs: (api.configs || []).map((c) => ({
      uid: c._uid,
      name: c._name || c.model || '未命名',
      model: c.model || '',
      current: c._uid === api.currentConfigId,
    })),
  });
}

/**
 * 单条 prompt 开关要同时写两处，否则「界面显示关了但提示词还在」：
 * `assembler.ts` 读排序表的 order[].enabled（见 presetOrder.ts），
 * 部分旧工具/导入器则读 prompts[].enabled。两处都同步才对任何版本预设生效。
 *
 * 只写**当前生效那一组**：ST 里同一个 prompt 可以按角色分组有不同的启停，
 * 把所有组一起改会把别的角色组的设置抹掉（这是以前 forEach 全遍历的隐患）。
 */
function syncPromptEnabled(ps: any, identifier: string, enabled: boolean): void {
  const order = getActivePromptOrder(ps);
  if (order) {
    for (const e of order) {
      if (typeof e !== 'object' || !e) continue;
      if (String(e.identifier ?? e.id ?? '') === identifier) e.enabled = enabled;
    }
  }
  // store 里若存的是 parsecard 实例（而不是 toJSON 后的普通对象），
  // 顺带用官方 API 同步它内部的顺序表。
  if (typeof ps?.setPromptEnabled === 'function') {
    try { ps.setPromptEnabled(identifier, enabled); } catch { /* 普通对象无此方法 */ }
  }
}

/**
 * 资产改名：ST 的预设/世界书有两种落盘形态 —— 扁平 `{ name }` 与
 * `{ data: { name } }`；预设还可能有 `preset_name`，正则组可能带 `scriptName`。
 *
 * 显示层取 `name || data.name`，web 资产页改名时也是「存在哪个就同步哪个」，
 * 这里保持同一套写法，避免原生改完、网页打开还是旧名字。
 */
function setAssetName(obj: any, name: string): void {
  obj.name = name;
  if (obj.preset_name !== undefined) obj.preset_name = name;
  if (obj.scriptName !== undefined) obj.scriptName = name;
  if (obj.data && typeof obj.data === 'object') obj.data.name = name;
}

/**
 * 引擎资产明细：预设 / 世界书（含全部条目）/ 正则组，供原生浏览。
 * 数据原样来自 assets store（ST 兼容层），原生只读不复制。
 */
function assetsDetail(): string {
  const a = useAssetsStore();
  const books = (a.worldBooks || []).map((w: any) => ({
    id: w._uid || '',
    name: w.name || w.data?.name || '未命名',
    current: a.currentWorldBookIds.includes(w._uid),
    entries: (w.data?.entries || w.entries || []).map((e: any) => ({
      uid: e.uid,
      comment: e.comment || '',
      keys: e.keys || [],
      secondary: e.secondary_keys || [],
      content: e.content || '',
      constant: !!e.constant,
      enabled: e.enabled !== false && e.disable !== true,
      // ST 触发字段全集（原生编辑器要能原样展示与回写）
      selective: e.selective !== false,
      selectiveLogic: e.selectiveLogic ?? 0,
      probability: e.probability ?? 100,
      useProbability: !!e.useProbability,
      depth: e.depth ?? 4,
      position: e.position ?? 0,
      role: e.role === null || e.role === undefined ? -1 : e.role,
      insertion_order: e.insertion_order ?? 100,
    })),
  }));
  const presets = (a.presets || []).map((p: any) => ({
    id: p._uid || '',
    name: p.name || p.data?.name || '未命名',
    current: p._uid === a.currentPresetId,
    // 「启用」＝对话里可不可选（可见性基准）；缺省视为启用
    enabled: p.enabled !== false,
    /*
     * 子条目顺序 = 发送顺序，**只能来自 prompt_order**（见 engine/presetOrder.ts）。
     * prompts[] 是「提示词库」，顺序是历史插入顺序，导入后跟 ST 对不上，
     * 所以这里统一走 listRankedPrompts：
     *   - ordered=true  在排序表里，position 从 1 递增；
     *   - ordered=false 在 ST 里属于「未激活提示词」，不参与生成，排到末尾单独分区。
     * 启停以 order[].enabled 为准（ST 唯一权威），未激活恒为 false。
     */
    prompts: listRankedPrompts(p).map((e) => ({
      identifier: e.identifier,
      name: e.prompt?.name || e.identifier,
      content: e.prompt?.content || '',
      role: e.prompt?.role || '',
      system: e.prompt?.system_prompt !== false,
      enabled: e.enabled,
      ordered: e.ordered,
      position: e.position,
      marker: !!e.prompt?.marker,
      idx: e.index,
    })),
  }));
  const regexGroups = (a.regexGroups || []).map((g: any) => ({
    id: g._uid || '',
    name: g.name || '未命名',
    current: a.currentRegexGroupIds.includes(g._uid),
    enabled: g.enabled !== false,
    scriptCount: (g.scripts || []).length,
    // 组内脚本明细：原生资产页要能逐条开关 / 编辑 / 新建 / 删除。
    scripts: (g.scripts || []).map((s: any, i: number) => ({
      id: String(s.id || `#${i}`),
      name: s.scriptName || s.name || '未命名脚本',
      findRegex: s.findRegex || '',
      replaceString: s.replaceString || '',
      disabled: s.disabled === true,
      markdownOnly: !!s.markdownOnly,
      promptOnly: !!s.promptOnly,
      runOnEdit: !!s.runOnEdit,
      substituteRegex: Number(s.substituteRegex || 0),
      placement: Array.isArray(s.placement) ? s.placement.map((v: any) => Number(v)) : [],
      minDepth: s.minDepth ?? null,
      maxDepth: s.maxDepth ?? null,
      trimStrings: Array.isArray(s.trimStrings) ? s.trimStrings : [],
      idx: i,
    })),
  }));
  return JSON.stringify({
    presets,
    worldBooks: books,
    regexGroups,
    characterId: a.currentCharacterId || '',
  });
}

/**
 * 导出「摘要专用预设」：**只带预设自身的提示词，不带角色卡与世界书**。
 *
 * 为什么需要单独一条通道：
 *   `assemblePrompt()` 是给"扮演"用的 —— 它会触发世界书、注入角色卡、跑正则、
 *   按 token 裁剪。摘要要的恰恰相反：只要预设的"工作方式"（写作规则、格式要求、
 *   越狱/前置语），不要人设与世界观，也不要裁剪（预设被砍一半就失效了）。
 *
 * 宏处理：`{{char}}` / `{{user}}` 保留成名字（摘要要认人）；
 * `{{description}}` / `{{personality}}` / `{{scenario}}` / `{{persona}}` /
 * `{{mes_example}}` / `{{first_mes}}` / `{{original}}` 一律清空 —— 这就是
 * "不带角色卡"的落点：预设里引用了角色卡的地方变成空白，而不是把整条规则删掉。
 *
 * 全程同步、不改任何 store，返回 JSON 字符串给原生 evaluateJavascript 取走。
 */
function summaryPreset(presetId: string): string {
  const a = useAssetsStore();
  const id = String(presetId || '');
  const raw =
    (id ? (a.presets || []).find((p: any) => p._uid === id) : undefined) ??
    a.getCurrentPreset();
  if (!raw) return JSON.stringify({ ok: false, reason: '没有可用预设', messages: [] });

  const preset: any = OpenAIPreset.fromJSON(raw);
  const card: any = a.getCurrentCharacter?.();
  const char =
    String(preset.getSetting?.('name_char') || '').trim() ||
    String(card?.name || card?.data?.name || '').trim();
  const user = String(preset.getSetting?.('name_user') || '').trim() || '用户';

  // 与真实组装同一套排序规则：未激活提示词不进摘要（它就是"不发生效"的部分）
  const entries: any[] = listRankedPrompts(preset)
    .filter((e) => e.ordered)
    .map((e) => ({ enabled: e.enabled, prompt: e.prompt }));
  const vars = useVariablesStore();
  /*
   * 角色卡字段一律置空、只留名字 —— 见 collectPresetMessages 的注释。
   * 这条规则是"摘要带预设但不带角色卡"的核心，所以放在纯函数里可单测。
   */
  const messages = collectPresetMessages(
    entries,
    {
      char,
      user,
      description: '',
      persona: '',
      scenario: '',
      original: '',
      mes_example: '',
      chat: '',
      currentMessage: '',
      lastMessage: '',
    },
    (t) => readVariables(t, vars as any),
  );
  return JSON.stringify({
    ok: true,
    name: raw.name || raw.data?.name || '',
    id: raw._uid || '',
    messages,
  });
}

/** 拉取对话模型列表（复用 imageGen 的 fetchModels：兼容 /v1/models 各形态）。 */
async function fetchApiModels(raw: string): Promise<void> {
  try {
    const req = JSON.parse(raw || '{}') as { baseUrl?: string; apiKey?: string };
    const models = await fetchModels({ baseUrl: req.baseUrl || '', apiKey: req.apiKey });
    replyApiResult({ kind: 'models', ok: true, models });
  } catch (e) {
    replyApiResult({
      kind: 'models',
      ok: false,
      error: e instanceof Error ? e.message : String(e),
    });
  }
}

/** 显示设置（思维链隐藏 / 截断标记 / 发给模型的文本是否跳过正则 / 导出用原文）。 */
function displaySettingsSummary(): string {
  const s = useStorageSettingsStore();
  return JSON.stringify({
    hideReasoning: !!s.hideReasoning,
    reasoningCutMarker: s.reasoningCutMarker || '',
    llmContextRaw: !!s.llmContextRaw,
    exportRaw: !!s.exportRaw,
  });
}

/**
 * 生图风格：多套配置列表 + 当前风格详情（含参考图 thumb）。
 * 与网页版一致 —— 风格可存多套，随用随切，参考图只作展示（不参与出图）。
 */
function imageStyleSummary(): string {
  const img = useImageStore();
  const cur: any = img.currentConfig || {};
  const styleId = img.currentStyleId;
  const style: any = (img.styleConfigs || []).find((s) => s._uid === styleId) || {};
  return JSON.stringify({
    styles: (img.styleConfigs || []).map((s) => {
      const raw = String(s.thumb || '');
      return {
        uid: s._uid,
        name: s.name || '未命名风格',
        current: s._uid === styleId,
        hasThumb: !!raw,
        // 只有 dataURL 能同步直出；旧数据可能是 blob: 引用，
        // 交给 getStyleThumb 命令异步解析（原生读到后会落本地文件缓存）。
        thumb: raw.startsWith('data:') ? raw : '',
      };
    }),
    styleUid: style._uid || '',
    styleName: style.name || '默认风格',
    artistTags: style.artistTags || '',
    qualityTags: style.qualityTags || '',
    negativePrompt: style.negativePrompt || '',
    promptTemplate: style.promptTemplate || '',
    thumb: style.thumb || '',
    sampler: style.sampler || '',
    steps: style.steps ?? 28,
    cfgScale: style.cfgScale ?? 6,
    noiseSchedule: style.noiseSchedule || '',
    width: cur.width || 832,
    height: cur.height || 1216,
  });
}

/** 装到 window 上，供原生 evaluateJavascript 调用。 */
export function installGenerateService(): void {
  // 配置 store 的持久化是异步的（IndexedDB）。引擎启动就预热，
  // 保证原生首次迁移调 exportNativeConfig() 时拿到的是真实老数据，
  // 而不是「store 还没 load 完」的空数组。
  void useApiConfigStore().load();
  void useImageStore().load();
  (window as any).__dhpGen = {
    generate: (json: string) => {
      void generate(json);
    },
    stop,
    imageConfig,
    displayConfig,
    assetCatalog,
    setActive: (json: string) => {
      void setActive(json);
    },
  };
  // 原生设置页的 API 配置桥（同一次安装，不用 main.ts 再 import 一遍）
  (window as any).__dhpApi = {
    getConfig: apiConfigSummary,
    getImageConfig: imageApiSummary,
    getImageStyle: imageStyleSummary,
    getDisplaySettings: displaySettingsSummary,
    getAssets: assetsDetail,
    // 摘要专用：只要预设，不要角色卡/世界书/正则（见 summaryPreset 注释）
    exportSummaryPreset: summaryPreset,
    // 配置原生化：一次性迁移 + 单向镜像推送（见文件头「原生配置镜像」注释）
    exportNativeConfig,
    applyNativeConfig,
    fetchModels: (json: string) => {
      void fetchApiModels(json);
    },
    command: (json: string) => {
      void apiCommand(json);
    },
  };
}
