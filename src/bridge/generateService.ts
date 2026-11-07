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
import { useTagLibraryStore } from '../stores/tagLibrary';
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
        for (const model of models) {
          const exists = img.apiConfigs.some(
            (c) => (c.baseUrl || '') === baseUrl && (c.model || c.novelaiModel || '') === model,
          );
          if (exists) {
            skipped++;
            continue;
          }
          const made = await img.addApi(model);
          await img.updateApi(made._uid, {
            ...(req.baseUrl ? { baseUrl: String(req.baseUrl) } : {}),
            ...(req.apiKey ? { apiKey: String(req.apiKey) } : {}),
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
      /** 预设子条目（prompt）保存：内容 / 名称 / 角色。 */
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
        await a.save();
        break;
      }
      /** 预设新建子条目。 */
      case 'presetAddPrompt': {
        const a = useAssetsStore();
        const ps: any = (a.presets || []).find((x: any) => x._uid === req.presetId);
        const list = ps?.data?.prompts || ps?.prompts;
        if (!list) throw new Error('找不到预设');
        const id = `native-${Date.now()}`;
        list.push({ identifier: id, name: '新子条目', content: '', role: 'system', system_prompt: true });
        await a.save();
        break;
      }
      /** 预设删除子条目。 */
      case 'presetRemovePrompt': {
        const a = useAssetsStore();
        const ps: any = (a.presets || []).find((x: any) => x._uid === req.presetId);
        const list = ps?.data?.prompts || ps?.prompts;
        if (!list) throw new Error('找不到预设');
        const i = list.findIndex((x: any, j: number) => String(x.identifier || `#${j}`) === req.identifier);
        if (i >= 0) list.splice(i, 1);
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
function imageApiSummary(): string {
  const img = useImageStore();
  const cur: any = img.currentConfig || {};
  return JSON.stringify({
    apis: (img.apiConfigs || []).map((a) => ({
      uid: a._uid,
      name: a.name || '',
      provider: a.provider || 'custom',
      baseUrl: a.baseUrl || '',
      hasKey: !!(a.apiKey || a.novelaiToken),
      model: a.model || a.novelaiModel || '',
      current: a._uid === img.currentApiId,
    })),
    current: {
      uid: img.currentApiId || '',
      baseUrl: cur.baseUrl || '',
      apiKey: cur.apiKey || '',
      model: cur.model || cur.novelaiModel || '',
      width: cur.width || 832,
      height: cur.height || 1216,
      negativePrompt: cur.negativePrompt || '',
    },
  });
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
    prompts: (p.data?.prompts || p.prompts || []).map((x: any, i: number) => ({
      identifier: x.identifier || `#${i}`,
      name: x.name || x.identifier || `#${i}`,
      content: x.content || '',
      role: x.role || '',
      system: x.system_prompt !== false,
      idx: i,
    })),
  }));
  const regexGroups = (a.regexGroups || []).map((g: any) => ({
    id: g._uid || '',
    name: g.name || '未命名',
    current: a.currentRegexGroupIds.includes(g._uid),
    scriptCount: (g.scripts || []).length,
  }));
  return JSON.stringify({
    presets,
    worldBooks: books,
    regexGroups,
    characterId: a.currentCharacterId || '',
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
    styles: (img.styleConfigs || []).map((s) => ({
      uid: s._uid,
      name: s.name || '未命名风格',
      current: s._uid === styleId,
      hasThumb: !!s.thumb,
    })),
    styleUid: style._uid || '',
    styleName: style.name || '默认风格',
    artistTags: style.artistTags || '',
    qualityTags: style.qualityTags || '',
    negativePrompt: style.negativePrompt || '',
    promptTemplate: style.promptTemplate || '',
    thumb: style.thumb || '',
    width: cur.width || 832,
    height: cur.height || 1216,
  });
}

/** 标签库搜索（原生标签生图的快捷标签）。 */
function tagSearch(rawQuery: string): string {
  try {
    const store = useTagLibraryStore();
    const q = (rawQuery || '').trim().toLowerCase();
    const list = (store.tags || []) as any[];
    // 标签库是懒加载的（bundled JSON），第一次调用先触发加载，
    // 原生下一次输入/刷新时就能搜到
    if (!list.length) void store.load();
    const pool = q
      ? list.filter((t) => String(t.tag || '').toLowerCase().includes(q) || String(t.cn || '').toLowerCase().includes(q))
      : list;
    return JSON.stringify({
      total: list.length,
      tags: pool.slice(0, 40).map((t) => ({
        tag: String(t.tag || ''),
        cn: String(t.cn || ''),
        count: t.count ?? 0,
        group: String(t.group || ''),
      })),
    });
  } catch {
    return JSON.stringify({ total: 0, tags: [] });
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
    getImageConfig: imageApiSummary,
    getImageStyle: imageStyleSummary,
    getDisplaySettings: displaySettingsSummary,
    getAssets: assetsDetail,
    getTags: tagSearch,
    fetchModels: (json: string) => {
      void fetchApiModels(json);
    },
    command: (json: string) => {
      void apiCommand(json);
    },
  };
}
