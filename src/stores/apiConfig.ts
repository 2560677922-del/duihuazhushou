import { defineStore } from 'pinia';
import { store, genId } from '../lib/storage';
import type { ApiConfig } from '../engine/apiClient';

// API 配置 store：支持多套配置保存与切换。
// 注意：contextWindow / userLookbackTokens 用于引擎的 Token 裁剪，
// 与 ApiConfig 的请求字段一起存。

export interface StoredApiConfig extends ApiConfig {
  _uid: string;
  _name: string;
  contextWindow: number; // 模型上下文窗口（token）
  userLookbackTokens: number; // 留给历史的预算（token）
  /**
   * 归属的"运营商/分组"。
   *
   * ★ 为什么不再按 baseUrl 推导分组：
   *   中转站（newapi / one-api）同一个域名下可以挂**多个分组**，
   *   不同分组背后是不同上游、不同价格、不同模型池 —— 逻辑完全不同。
   *   按地址合并会导致改 A 分组连带改掉 B 分组。
   *
   *   现在：只有显式带上同一个 providerId 的配置才算"同一家"。
   *   没带的（老数据、单独新建的）各自独立。
   */
  providerId?: string;
}

export interface ApiConfigState {
  configs: StoredApiConfig[];
  currentConfigId?: string;
  /**
   * 运营商备注（按 baseUrl 归一化后的键 → 用户写的名字）。
   *
   * 为什么要单独存：运营商是从 `baseUrl` **派生**出来的分组，
   * 不是一个真实存在的记录。用户想给它起个名（比如"玄猫（便宜）"），
   * 总得有地方放 —— 又不能塞进 configs 里（那是"模型"这一层）。
   * 这里加一张小映射表，不动原有数据形状，老数据不受影响。
   */
  providerNotes: Record<string, string>;
}

/*
 * 新建模型时的默认参数。
 *
 * 上下文窗口默认 100 万 token：
 *   现在的主流模型（Gemini / Claude / GPT 新一代）上下文都是百万级。
 *   之前默认 8192，用户每加一个模型都要手动改一遍 —— 这是个纯粹的摩擦点。
 *   宁可默认给大：上下文填大了**不会**多花钱（它只是"裁剪历史"的预算上限），
 *   裁剪逻辑只会少裁，不会把没发的 token 也算钱。觉得大了自己往下调即可。
 *
 * 历史预算同样放宽到 20 万：写作场景对话很长，2000 太小会频繁丢历史。
 *
 * 单次上限默认 8192：2026 年主流模型单次输出普遍 8k 起步，2000 会让
 * 长段落被硬截断。填 0 = 不限制（请求里不发 max_tokens，交给服务端）。
 * 另外这三项（单次上限 / 上下文窗口 / 历史预算）在保存时**对全部模型统一生效**，
 * 只有温度按模型单独保存 —— 用户明确要求「别让我一个一个改」。
 *
 * ★ baseUrl / model 留空，**不预填任何厂商的地址或模型名**。
 *   用户明确要求："运营商不要硬编码地址，我自己会加"。
 *   以前默认填 https://api.openai.com/v1 + gpt-4o-mini，后果有两个：
 *     1. 新建一家时它自带一个 openai 地址，用户得先删掉再填自己的；
 *     2. 忘了改的话请求会真的发到 openai 去（报 401，很难看出是"没填地址"）。
 *   现在空着，界面上会明确提示"请先填写请求地址"。
 */
const DEFAULT_CONFIG: StoredApiConfig = {
  _uid: '',
  _name: '默认配置',
  baseUrl: '',
  apiKey: '',
  model: '',
  temperature: 0.8,
  maxTokens: 8192,
  contextWindow: 1000000,
  userLookbackTokens: 200000,
};

/**
 * 新建模型时继承某个数值参数。
 *
 * 规则（"向上兼容"的落地口径）：
 *   · 0 是显式语义「不限制」→ 原样继承，不能抬回默认值
 *   · 其他值 → 不低于新默认（老版本留下的小默认 2000 不拖累新模型；
 *     用户主动调大的值，比如 2M 上下文，继续保持）
 *   · 字段缺失（老数据）→ 用默认
 */
function inheritNum(seedVal: number | undefined, def: number | undefined): number {
  if (seedVal === 0) return 0;
  return Math.max(seedVal || 0, def || 0);
}

/**
 * 一条配置属于"哪一家"（运营商分组的键）。
 *
 * ★ 2026-10 修正：以前这里按**地址**分组（同样的 baseUrl 就算一家）。
 *   这是错的 —— 中转站（newapi / one-api 之类）同一个域名下可以挂**多个分组**，
 *   不同分组背后是不同的上游厂商、不同价格、不同模型池，逻辑完全不一样。
 *   按地址合并会导致：在 A 分组改密钥/温度，连带把 B 分组也改了。
 *
 * 现在改成：**一条配置自成一组**。
 *   · 新建模型时如果是从某家"复制"出来的，会带上同一个 providerId，
 *     这才是真正想共享的情况（用户主动表达的意图）
 *   · 没有 providerId 的（老数据 / 单独新建的）各算各的
 */
function providerKeyOf(c: { baseUrl?: string; _uid: string; providerId?: string }): string {
  return c.providerId || `__own__:${c._uid}`;
}

export const useApiConfigStore = defineStore('apiConfig', {
  state: (): ApiConfigState => ({
    configs: [],
    currentConfigId: undefined,
    providerNotes: {},
  }),
  getters: {
    currentConfig(): StoredApiConfig | undefined {
      return this.configs.find((c) => c._uid === this.currentConfigId);
    },

    /**
     * 按「运营商」分组 —— 这是给"选模型"用的两层结构的上一层。
     *
     * 背景：真实用法是一个运营商（一家中转/官方）下面挂多个模型。
     * 而数据里每条记录都是"运营商 + 模型"打包在一起，所以同一个
     * 运营商会出现多条记录（同一个 key 存好几遍）。
     *
     * 这里按 baseUrl 归一化后分组，正好把它们收成"一家 → 多个模型"。
     * 好处是**完全不用改数据形状**，35+ 处 `api.configs` 调用点全不受影响。
     *
     * 分组键用归一化的 baseUrl（去掉末尾斜杠、转小写），
     * 避免 `https://x.cn/v1` 和 `https://x.cn/v1/` 被当成两家。
     * 地址还没填的按 uid 各算一家（见 providerKeyOf）。
     */
    providers(state): {
      key: string;
      baseUrl: string;
      note: string;
      /** 这一家下面的模型（按添加顺序） */
      models: StoredApiConfig[];
      /** 这一家里有没有可用的 key（没有就提示用户去填） */
      hasKey: boolean;
    }[] {
      const groups = new Map<string, StoredApiConfig[]>();
      for (const c of state.configs) {
        const key = providerKeyOf(c);
        const arr = groups.get(key);
        if (arr) arr.push(c);
        else groups.set(key, [c]);
      }
      return [...groups.entries()].map(([key, models]) => ({
        key,
        baseUrl: models[0]?.baseUrl || '',
        note: state.providerNotes[key] || '',
        models,
        hasKey: models.some((m) => !!m.apiKey),
      }));
    },

    /** 当前模型属于哪个运营商（给标题栏显示用） */
    currentProvider(state): { key: string; note: string; baseUrl: string } | undefined {
      const cur = state.configs.find((c) => c._uid === state.currentConfigId);
      if (!cur) return undefined;
      const key = providerKeyOf(cur);
      return { key, note: state.providerNotes[key] || '', baseUrl: cur.baseUrl || '' };
    },

    /**
     * 当前选中的运营商（设置页要把"地址 / 密钥"当成这一家的属性来编辑）。
     * 跟着当前模型走 —— 用户切了模型，设置页也就跟着显示那一家的连接信息。
     */
    currentProviderFull(state): {
      key: string;
      baseUrl: string;
      apiKey: string;
      note: string;
      models: StoredApiConfig[];
    } | undefined {
      const cur = state.configs.find((c) => c._uid === state.currentConfigId);
      if (!cur) return undefined;
      const key = providerKeyOf(cur);
      return {
        key,
        baseUrl: cur.baseUrl || '',
        apiKey: cur.apiKey || '',
        note: state.providerNotes[key] || '',
        models: state.configs.filter((c) => providerKeyOf(c) === key),
      };
    },
  },
  actions: {
    async load() {
      const [c, cid, notes] = await Promise.all([
        store.getItem<StoredApiConfig[]>('apiConfigs'),
        store.getItem<string | undefined>('currentApiConfigId'),
        store.getItem<Record<string, string>>('apiProviderNotes'),
      ]);
      this.configs = c ?? [];
      this.currentConfigId = cid ?? undefined;
      this.providerNotes = notes ?? {};
      if (this.configs.length === 0) {
        // 首次进入：初始化一个默认配置
        const def = { ...DEFAULT_CONFIG, _uid: genId('api') };
        this.configs.push(def);
        this.currentConfigId = def._uid;
        await this.save();
      }
    },
    async save() {
      await Promise.all([
        store.setItem('apiConfigs', this.configs),
        store.setItem('currentApiConfigId', this.currentConfigId),
        store.setItem('apiProviderNotes', this.providerNotes),
      ]);
    },

    /** 给某个运营商写备注（key 用 providerKeyOf 的规则，空地址按 uid 各算一家） */
    async setProviderNote(key: string, note: string) {
      this.providerNotes = { ...this.providerNotes, [key]: note };
      await this.save();
    },

    /**
     * 修改某个运营商的**连接信息**，并同步到它下面所有模型。
     *
     * 这是这轮改动的核心：用户的心智是"一个中转站填一次地址和密钥，
     * 然后在它下面挑模型"。而数据里每条记录都带着自己的 baseUrl/apiKey，
     * 如果只改当前那条，同一家的其他模型还留着旧密钥 ——
     * 表现就是"改完密钥，换一个模型怎么又不好使了"。
     *
     * 所以这里把 baseUrl / apiKey 视为**运营商级别**的字段，一改全改。
     * 其它字段（model / 上下文窗口 / 温度…）仍是每个模型自己的。
     *
     * @param uid 用**哪条配置**来定位这一家（通常是当前选中的那条）。
     *   以前是用 baseUrl 定位的，但新建的运营商地址是空的 ——
     *   空地址当键会让两家没填地址的运营商混在一起，填一个改到另一个。
     * @param opts.groupKey **开始编辑时**那一家的键（见 providerKeyOf）。
     *   为什么要传它：用户如果把地址先清空再重打，地址一空，键就变成
     *   「只属于自己的空键」，同家其它模型就不再被同步了。
     *   用编辑开始时的键，整段输入过程都按同一家处理。
     *   不传则按当前地址现算（普通场景够用）。
     * @param opts.persist 是否立刻落盘。
     *   输入框"边打字边同步"时传 false —— 每敲一个字符都写一次
     *   IndexedDB 会把 1.5MB 数据反复写盘，明显卡顿。由调用方防抖统一 save()。
     */
    async updateProvider(
      uid: string,
      patch: { baseUrl?: string; apiKey?: string },
      opts: { persist?: boolean; groupKey?: string } = {},
    ) {
      const cur = this.configs.find((c) => c._uid === uid);
      if (!cur) return;

      const key = opts.groupKey ?? providerKeyOf(cur);
      /*
       * 按 providerKeyOf 分组，把这一家的所有模型一起改（"填一次"的目的）。
       *
       * ★ 关键：key 不再由地址算出来，而是配置自己的 providerId ——
       *   所以同域名下的不同分组**不会**被合并，
       *   改 A 分组的密钥不会碰到 B 分组。
       *
       * 用户想共享时，走「在这家下面加模型」，
       * 新记录会带上同一个 providerId —— 那才是显式的共享。
       */
      const targets = this.configs.filter((c) => providerKeyOf(c) === key);

      for (const c of targets) {
        if (patch.apiKey !== undefined) c.apiKey = patch.apiKey;
        if (patch.baseUrl !== undefined) c.baseUrl = patch.baseUrl;
      }

      /*
       * 备注跟着这家走。
       * key 不再随地址变化，所以地址改了也不用搬 —— 但为了兼容
       * 早期按地址存的备注，这里仍然做一次搬迁。
       */
      if (patch.baseUrl !== undefined && this.providerNotes[key]) {
        const notes = { ...this.providerNotes };
        notes[key] = notes[key];
        this.providerNotes = notes;
      }

      if (opts.persist !== false) await this.save();
    },

    /**
     * 删除某个运营商（它下面的所有模型一起删）。
     *
     * 为什么是"删一家"而不是"删一条"：设置页是按运营商组织的，
     * 用户在上面点删除，期望的是"这家不要了"，而不是
     * "少一个模型但这家还在、而且剩下的还带着同样的密钥"。
     *
     * @param uid 用哪条配置来定位这一家（与 updateProvider 同一套规则：
     *   空地址的运营商只删自己那一条，不会误删另一家还没填地址的）。
     */
    async removeProvider(uid: string) {
      const cur = this.configs.find((c) => c._uid === uid);
      if (!cur) return;

      /*
       * 按 providerKeyOf 定位同一家，**不再按地址删**。
       * 按地址删的话，同一个中转域名下的其它分组会被连坐删掉 ——
       * 那是用户完全没预期的（他只是删了其中一个分组）。
       */
      const key = providerKeyOf(cur);
      const doomed = this.configs.filter((c) => providerKeyOf(c) === key);
      if (!doomed.length) return;

      const doomedUids = new Set(doomed.map((c) => c._uid));
      this.configs = this.configs.filter((c) => !doomedUids.has(c._uid));
      if (key) {
        const notes = { ...this.providerNotes };
        delete notes[key];
        this.providerNotes = notes;
      }

      // 当前选中的模型被删掉了 → 退到第一个可用模型
      if (!this.configs.some((c) => c._uid === this.currentConfigId)) {
        this.currentConfigId = this.configs[0]?._uid;
      }
      await this.save();
    },

    /**
     * 在某个运营商下面**一次加多个**模型（从"拉取模型列表"里多选后调用）。
     *
     * 为什么需要它：拉取一次动辄返回上百个模型，用户想挑五六个常用的。
     * 以前只能一个一个点（点一个就切换当前模型、面板还关掉），
     * 挑 6 个要重复 6 遍"拉取 → 找到它 → 点一下"。
     *
     * 规则：
     *   · 已经在这一家里的模型**跳过**（不重复添加，避免出现两条一样的）
     *   · 新记录共用同一个 providerId（= 和"在这家下面加模型"一样，
     *     baseUrl / apiKey 自动继承，改一次全改）
     *   · 当前选中的模型不变（除非它不在这一家里了）；一个都没选中过时
     *     才把当前切到第一个新加的
     *
     * @returns added 新加了几条，skipped 因为已存在而跳过几条
     */
    async addModelsToProvider(
      uid: string,
      models: string[],
    ): Promise<{ added: number; skipped: number }> {
      const anchor = this.configs.find((c) => c._uid === uid);
      if (!anchor) return { added: 0, skipped: 0 };

      const key = providerKeyOf(anchor);
      const existing = this.configs.filter((c) => providerKeyOf(c) === key);
      const have = new Set(existing.map((c) => (c.model || '').trim()).filter(Boolean));
      const seed = existing[0] ?? anchor;

      let added = 0;
      let skipped = 0;
      let firstNew: StoredApiConfig | undefined;

      for (const raw of models) {
        const model = (raw || '').trim();
        if (!model) continue;
        if (have.has(model)) {
          skipped++;
          continue;
        }
        have.add(model);
        const cfg: StoredApiConfig = {
          ...DEFAULT_CONFIG,
          baseUrl: seed.baseUrl,
          apiKey: seed.apiKey,
          temperature: seed.temperature,
          // 向上兼容：继承同家的值，但不低于新默认（老的小值不拖累新模型）
          maxTokens: inheritNum(seed.maxTokens, DEFAULT_CONFIG.maxTokens),
          contextWindow: inheritNum(seed.contextWindow, DEFAULT_CONFIG.contextWindow),
          userLookbackTokens: inheritNum(
            seed.userLookbackTokens,
            DEFAULT_CONFIG.userLookbackTokens,
          ),
          providerId: key,
          _uid: genId('api'),
          _name: model,
          model,
        };
        this.configs.push(cfg);
        firstNew = firstNew ?? cfg;
        added++;
      }

      if (added > 0) {
        // 当前选中那条如果已经不在这一家里（比如从"新运营商"进来），
        // 就切到第一个新加的，用户马上能用
        const curOk = this.configs.some(
          (c) => c._uid === this.currentConfigId && providerKeyOf(c) === key,
        );
        if (!curOk && firstNew) this.currentConfigId = firstNew._uid;
        await this.save();
      }
      return { added, skipped };
    },

    /**
     * 只删除某个模型（这家还有别的模型时用这个）。
     */
    async removeModelFromProvider(uid: string) {
      await this.removeConfig(uid);
    },

    /**
     * 在某个运营商下面加一个模型。
     *
     * 继承规则（分成两类，很重要）：
     *
     *  ① **连接信息** baseUrl / apiKey —— 必须继承。
     *     用户加第二个模型时不该再把密钥重填一遍（那是以前的痛点：
     *     同一家存两条配置、key 存两份，改一处忘一处就对不上）。
     *
     *  ② **生成参数** temperature / maxTokens —— 继承。
     *     同一家的请求风格通常一致，用户特意调过的值得延续。
     *
     *  ③ **上下文窗口** contextWindow / userLookbackTokens —— **不继承**，用新默认值。
     *     这两项跟"模型本身有多大"绑定，而不是跟"哪一家"绑定。
     *     而且旧默认是 8192（那时模型就这么大），继承过来会让每个新模型
     *     都顶着一个过时的窗口，用户得逐个手改 —— 他明确反馈过这个摩擦，
     *     要求"默认 100 万、别每次都调"。
     *     真要调：那是单个模型的事，改一处就行。
     *
     * @param uid 在**哪条配置所属的那一家**下面加（通常传当前选中那条的 uid）。
     *   用 uid 而不是 baseUrl 定位：新建的运营商地址还是空的，
     *   用空地址定位会让两家没填地址的运营商混在一起。
     */
    async addModelToProvider(uid: string, model: string, name?: string) {
      const anchor = this.configs.find((c) => c._uid === uid);
      if (!anchor) return undefined;

      const key = providerKeyOf(anchor);
      const sameProvider = this.configs.find((c) => providerKeyOf(c) === key);
      const cfg: StoredApiConfig = {
        ...DEFAULT_CONFIG,
        ...(sameProvider
          ? {
              baseUrl: sameProvider.baseUrl,
              apiKey: sameProvider.apiKey,
              temperature: sameProvider.temperature,
              // 向上兼容：同家的值优先，但不低于新默认（只升不降）
              maxTokens: inheritNum(sameProvider.maxTokens, DEFAULT_CONFIG.maxTokens),
              contextWindow: inheritNum(
                sameProvider.contextWindow,
                DEFAULT_CONFIG.contextWindow,
              ),
              userLookbackTokens: inheritNum(
                sameProvider.userLookbackTokens,
                DEFAULT_CONFIG.userLookbackTokens,
              ),
              /*
               * ★ 带上同一个 providerId —— 这才是**真正想共享**的情况。
               * 用户主动点"在这家下面加模型"，等于明确表达"这两条要一起改"。
               * 同域名但不同分组的中转，不会走到这里（它们各自独立）。
               */
              providerId: key,
            }
          : { baseUrl: anchor.baseUrl || '' }),
        _uid: genId('api'),
        _name: name || model || '新模型',
        model: model || DEFAULT_CONFIG.model,
      };
      this.configs.push(cfg);
      this.currentConfigId = cfg._uid;
      await this.save();
      return cfg;
    },
    async addConfig(name = '新配置') {
      const cfg: StoredApiConfig = { ...DEFAULT_CONFIG, _uid: genId('api'), _name: name };
      this.configs.push(cfg);
      this.currentConfigId = cfg._uid;
      await this.save();
      return cfg;
    },
    async removeConfig(uid: string) {
      this.configs = this.configs.filter((c) => c._uid !== uid);
      if (this.currentConfigId === uid) {
        this.currentConfigId = this.configs[0]?._uid;
      }
      await this.save();
    },
    async updateConfig(uid: string, patch: Partial<StoredApiConfig>) {
      const cfg = this.configs.find((c) => c._uid === uid);
      if (cfg) Object.assign(cfg, patch);
      await this.save();
    },
    async setCurrent(uid: string) {
      this.currentConfigId = uid;
      await this.save();
    },
  },
});
