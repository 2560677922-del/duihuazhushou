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
import { streamChatCompletion } from '../engine/apiClient';
import { imageMarkerInstruction } from '../engine/imageMarker';
import type { ChatMessage } from '../engine/types';
import { OpenAIPreset, WorldBook } from 'parsecard';
import { useAssetsStore } from '../stores/assets';
import { useApiConfigStore } from '../stores/apiConfig';
import { useVariablesStore } from '../stores/variables';
import { useStorageSettingsStore } from '../stores/storageSettings';
import { useImageStore } from '../stores/image';
import { fetchModels } from '../engine/imageGen';

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
): ChatMessage[] | null {
  const assets = useAssetsStore();
  const presetRaw = assets.getCurrentPreset();
  if (!presetRaw) return null;

  const preset = OpenAIPreset.fromJSON(presetRaw);
  const worldBooks = assets.getCurrentWorldBooks().map((w: any) =>
    WorldBook.fromJSON(w).toStandaloneJSON(),
  );
  const storageSettings = useStorageSettingsStore();
  const regexScripts = storageSettings.llmContextRaw ? [] : assets.getCurrentRegexScripts();
  const api = useApiConfigStore();
  const variables = useVariablesStore();

  const chatHistory: ChatMessage[] = [...history];
  if (userInput.trim()) chatHistory.push({ role: 'user', content: userInput });

  const result = assemblePrompt({
    preset,
    characterCard: assets.getCurrentCharacter(),
    worldBooks,
    regexScripts,
    chatHistory,
    contextWindowTokens: api.currentConfig?.contextWindow,
    userLookbackTokens: api.currentConfig?.userLookbackTokens,
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
    const imageStore = useImageStore();
    const wantImage =
      typeof req.imageInstruction === 'boolean'
        ? req.imageInstruction
        : imageStore.autoImage.enabled;
    const imageMax = req.imageInstructionMax ?? imageStore.autoImage.maxPerReply;

    const finalMessages = buildMessages(history, cleanInput, wantImage, imageMax, sessionId);
    if (!finalMessages) {
      callNative('onGenError', requestId, '还没有启用预设，请先在「资产」里选一个');
      return;
    }

    const cfg = useApiConfigStore().currentConfig;
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
  const api = useApiConfigStore();
  const cfg: any = img.currentConfig || {};
  const llm: any = api.currentConfig || {};

  return JSON.stringify({
    config: {
      provider: cfg.provider || 'custom',
      baseUrl: cfg.baseUrl || '',
      apiKey: cfg.apiKey || '',
      model: cfg.model || cfg.novelaiModel || '',
      novelaiModel: cfg.novelaiModel || '',
      endpoint: cfg.endpoint || '',
      width: cfg.width || 832,
      height: cfg.height || 1216,
      negativePrompt: cfg.negativePrompt || '',
      artistTags: cfg.artistTags || '',
      qualityTags: cfg.qualityTags || '',
      promptTemplate: cfg.promptTemplate || '',
    },
    autoImage: {
      enabled: !!img.autoImage.enabled,
      maxPerReply: img.autoImage.maxPerReply || 2,
      skipOptimization: img.autoImage.skipOptimization !== false,
      usePreset: !!img.autoImage.usePreset,
      presetMaxChars: img.autoImage.presetMaxChars || 4000,
    },
    llm: {
      baseUrl: llm.baseUrl || '',
      apiKey: llm.apiKey || '',
      model: llm.model || '',
      temperature: llm.temperature ?? 0.7,
      maxTokens: llm.maxTokens || 900,
    },
    // 预设里跟画面相关的片段（可选）。原生只在 usePreset 时拼进提示词。
    presetBlock: img.autoImage.usePreset ? presetSnippet(img.autoImage.presetMaxChars) : '',
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
  }
}

// ===================== 原生设置页的 API 配置桥（window.__dhpApi） =====================
//
// 原生设置页的交互是「输入地址 + 密钥 → 拉取模型 → 选中即可用」，
// 与网页版设置页一致。数据必须落在 apiConfig store：生成走 web 引擎，
// 配置也必须同源，否则原生存一份、web 读一份必然打架。
//
// 通信方式：
//   · getConfig()        —— 同步返回 JSON 串（evaluateJavascript 直接接返回值）
//   · saveConfig(json)   —— 异步保存，完成后回调 AndroidNative.onApiResult
//   · fetchModels(json)  —— 异步拉取模型列表，完成/失败都回调 onApiResult
// 异步结果统一走 onApiResult，原生侧用一次性监听接住。

/** 原生设置页的结果回传（saveConfig / fetchModels 共用一条通道）。 */
function replyApiResult(payload: Record<string, unknown>): void {
  try {
    (window as any).AndroidNative?.onApiResult?.(JSON.stringify(payload));
  } catch {
    /* 不在 App 里（纯浏览器开发）时没有这个桥，静默即可 */
  }
}

/** 当前 API 配置 + 运营商概览。同步调用，原生 evaluateJavascript 接返回值。 */
function apiConfigSummary(): string {
  const api = useApiConfigStore();
  const cur = api.currentConfig;
  return JSON.stringify({
    baseUrl: cur?.baseUrl || '',
    apiKey: cur?.apiKey || '',
    model: cur?.model || '',
    modelName: cur?._name || '',
    providers: api.providers.map((p) => ({
      key: p.key,
      note: p.note || '',
      baseUrl: p.baseUrl || '',
      modelCount: p.models?.length ?? 0,
    })),
  });
}

/**
 * 保存地址 / 密钥 / 选中模型。
 *
 * 地址与密钥按「运营商级」同步（updateProvider 会把同一家全部改掉，
 * 与网页版行为一致）；选模型时这家下已有该模型就切过去，没有就新增。
 */
async function saveApiConfig(raw: string): Promise<void> {
  try {
    const api = useApiConfigStore();
    await api.load();
    const req = JSON.parse(raw || '{}') as { baseUrl?: string; apiKey?: string; model?: string };
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

    const model = (req.model || '').trim();
    if (model) {
      const prov = api.currentProviderFull;
      const hit = prov?.models.find((m) => (m.model || '').trim() === model);
      if (hit) {
        api.currentConfigId = hit._uid;
        await api.save();
      } else {
        await api.addModelToProvider(cur._uid, model);
      }
    }
    replyApiResult({ kind: 'save', ok: true });
  } catch (e) {
    replyApiResult({
      kind: 'save',
      ok: false,
      error: e instanceof Error ? e.message : String(e),
    });
  }
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

/** 装到 window 上，供原生 evaluateJavascript 调用。 */
export function installGenerateService(): void {
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
    saveConfig: (json: string) => {
      void saveApiConfig(json);
    },
    fetchModels: (json: string) => {
      void fetchApiModels(json);
    },
  };
}
