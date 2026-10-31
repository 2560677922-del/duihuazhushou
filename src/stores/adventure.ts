import { defineStore } from 'pinia';
import { store, genId } from '../lib/storage';
import type { ImageAttachment } from '../engine/imageMarker';
import { extractInlineImages } from '../engine/imageMarker';
import { dropInlineImages } from '../lib/blobStore';

// 冒险会话 store
export interface Action {
  id: string;
  user: string;
  ai: string;
  /**
   * 配图附件。与 ai 正文分开存放，永不进入送给大模型的上下文。
   */
  images?: ImageAttachment[];
  timestamp: number;
  /**
   * 历史版本（新的在前）。
   *
   * 用来做「< >」前后切换：重新生成、编辑、以及用户改自己的指令，
   * 都把旧内容压进这里，而不是直接丢掉。
   * 这样试了几版之后还能翻回来看哪版最好，不用重来。
   *
   * 不存历史版本时该字段为空，行为和不支持时一致。
   */
  variants?: MessageVariant[];
  /** 当前显示的是第几个版本（对应 variants 的下标，0 = 最新） */
  variantIndex?: number;
}

/** 一条消息的历史版本快照 */
export interface MessageVariant {
  user: string;
  ai: string;
  timestamp: number;
}

/** 同一个消息最多保留几个历史版本，防止无限膨胀 */
export const MAX_VARIANTS = 20;

export interface AdventureSession {
  id: string;
  title: string;
  sceneDescription: string;
  actions: Action[];
  createdAt: number;
  updatedAt: number;
  /** 摘要记录（最新的一条在最前面） */
  summaries?: SummaryEntry[];
  /** 这个会话的摘要策略 */
  settings?: SessionSettings;
  /**
   * 正在生成中的草稿。
   *
   * 为什么要有这个：AI 流式输出的过程中，内容只存在于内存里。
   * 用户切窗口 / 锁屏 / 刷新，这段内容就没了 —— 而它可能已经生成了一大半，
   * 既浪费 token 又让人白等。所以边收边存，重进会话可以接着看。
   */
  draft?: {
    /** 用户这一次输入的指令 */
    user: string;
    /** 已经收到的部分输出 */
    ai: string;
    updatedAt: number;
  };
}

/** 一条摘要记录（和小说那边结构一致） */
export interface SummaryEntry {
  id: string;
  text: string;
  mode: 'manual' | 'auto';
  /** 生成时覆盖到第几条行动（含） */
  coveredUpTo: number;
  targetWords?: number;
  createdAt: number;
}

/** 自动摘要策略 */
export interface SessionSettings {
  autoSummary: boolean;
  trigger: 'messages' | 'chars';
  everyMessages: number;
  everyChars: number;
  targetWords: number;
}

export const DEFAULT_SESSION_SETTINGS: SessionSettings = {
  autoSummary: true,
  trigger: 'messages',
  everyMessages: 12,
  everyChars: 6000,
  targetWords: 300,
};

interface AdventureState {
  sessions: AdventureSession[];
  currentSessionId?: string;
}

/**
 * 记录一个新版本。
 *
 * 约定：`variants[0]` 永远是**最新**的版本，`variantIndex` 指向当前显示的
 * 那一个。act.user / act.ai 始终是 variants[variantIndex] 的副本，
 * 这样"重新生成"只要往前面插一个新版本即可，旧版本自然留在后面供翻看。
 *
 * 之所以把"当前版本"也放进数组（而不是单独放在外面再配一个历史列表），
 * 是为了让下标始终一致 —— 否则切换时要在"当前"和"历史"之间做偏移换算，
 * 很容易越界或错位。
 */
function pushVariant(act: Action, next: MessageVariant) {
  const cur = act.variants ?? [];
  /*
   * 只有**有内容**的版本才值得留。
   *
   * 踩过的坑（用户复现）：删除一条 AI 回复后重新生成，
   * replaceActionAi 会把"当前内容"先存成一个版本 ——
   * 而那时 act.ai 已经是空串了，于是历史里多出一个
   * 「有用户发言、没有 AI 正文」的幽灵版本。
   * 切过去整条消息就空了，还因为 v-if 把切换按钮一起藏了，再也切不回来。
   */
  const onlyReal = cur.filter((v) => (v.ai ?? '').trim() || (v.user ?? '').trim());
  // 去掉重复（模型两次返回同样内容时不堆垃圾）
  const rest = onlyReal.filter((v) => !(v.user === next.user && v.ai === next.ai));
  act.variants = [next, ...rest].slice(0, MAX_VARIANTS);
  act.variantIndex = 0;
  act.user = next.user;
  act.ai = next.ai;
}

export const useAdventureStore = defineStore('adventure', {
  state: (): AdventureState => ({
    sessions: [],
    currentSessionId: undefined,
  }),
  getters: {
    currentSession(): AdventureSession | undefined {
      return this.sessions.find((s) => s.id === this.currentSessionId);
    },
  },
  actions: {
    async load() {
      const [sessions, currentSessionId] = await Promise.all([
        store.getItem<AdventureSession[]>('adventureSessions'),
        store.getItem<string | undefined>('adventureCurrentSessionId'),
      ]);
      this.sessions = sessions ?? [];
      this.currentSessionId = currentSessionId ?? undefined;

      // 首次使用：建一个空会话，不预填任何剧情。
      //
      // 之前这里硬编码了"魔法学院的图书馆，深夜…"，等于替用户决定了开局。
      // 场景应该由用户自己写（或用世界书/角色卡提供），所以默认留空。
      if (this.sessions.length === 0) {
        await this.addSession('新冒险', '');
        return;
      }

      // 自愈：currenSessioId 缺失或指向一个不存在的会话时，
      // 退回最后一个会话。
      //
      // 这里曾经是个真 bug：刷新后 currentSessionId 没了，
      // 于是 currentSession 变成 undefined —— 界面上什么都看不到，
      // 而且草稿明明存在却挂不到"当前会话"上，看起来就像没保存。
      if (!this.currentSessionId || !this.sessions.some((s) => s.id === this.currentSessionId)) {
        this.currentSessionId = this.sessions[this.sessions.length - 1].id;
        await this.save();
      }
    },
    async save() {
      await Promise.all([
        store.setItem('adventureSessions', this.sessions),
        store.setItem('adventureCurrentSessionId', this.currentSessionId),
      ]);
    },
    async addSession(title: string, sceneDescription = '') {
      const s: AdventureSession = {
        id: genId('adv'),
        title,
        sceneDescription,
        actions: [],
        createdAt: Date.now(),
        updatedAt: Date.now(),
      };
      this.sessions.push(s);
      this.currentSessionId = s.id;
      await this.save();
      return s;
    },
    async switchSession(id: string) {
      this.currentSessionId = id;
      await this.save();
    },
    async removeSession(id: string) {
      this.sessions = this.sessions.filter((s) => s.id !== id);
      if (this.currentSessionId === id) {
        this.currentSessionId = this.sessions[0]?.id;
      }
      await this.save();
    },
    async addAction(user: string, ai: string) {
      const session = this.currentSession;
      if (!session) return undefined;

      /*
       * 粘进来的内容里可能带内嵌 base64 图片（从别处复制图片时很常见）。
       * 直接存会一张顶 1~2 MB —— 落盘文件会从 1.5 MB 涨到 5 MB。
       * 这里抽成真附件 + [[IMG:]] 标记：图照样显示，文本不再含 base64。
       */
      const userEx = extractInlineImages(user);
      const aiEx = extractInlineImages(ai);

      const act: Action = {
        id: genId('act'),
        user: userEx.text,
        ai: aiEx.text,
        timestamp: Date.now(),
      };

      const allImages = [...userEx.images, ...aiEx.images];
      if (allImages.length) {
        act.images = allImages.map((im, i) => ({
          id: genId('img'),
          markdownIndex: 0,
          description: im.description,
          dataUrl: im.dataUrl,
          storage: 'browser' as const,
          createdAt: Date.now() + i,
        }));
      }

      session.actions.push(act);
      session.updatedAt = Date.now();
      await this.save();
      return act;
    },
    /** 新增或替换某一步的配图附件（不影响 ai 正文，也不进入大模型上下文） */
    async upsertActionImage(actionId: string, attachment: ImageAttachment) {
      const session = this.currentSession;
      if (!session) return;
      const act = session.actions.find((a) => a.id === actionId);
      if (!act) return;
      const list = act.images ? [...act.images] : [];
      const i = list.findIndex((a) => a.id === attachment.id);
      if (i >= 0) list[i] = attachment;
      else list.push(attachment);
      act.images = list;
      session.updatedAt = Date.now();
      await this.save();
    },
    async removeActionImage(actionId: string, attachmentId: string) {
      const session = this.currentSession;
      const act = session?.actions.find((a) => a.id === actionId);
      if (!session || !act?.images) return;
      // 被删掉的那张图要真的从存储里清掉，否则只是界面看不见、空间还占着
      const gone = act.images.filter((a) => a.id === attachmentId);
      if (gone.length) await dropInlineImages(gone);
      act.images = act.images.filter((a) => a.id !== attachmentId);

      /*
       * ★ 连正文里的占位符一起删。
       *
       * 图片在正文里是靠 `[[IMG: 画面描述]]` 这一行占位的。
       * 只删附件、留着占位符的话，正文里就会剩一行 `[[IMG: ...]]` 的裸文本，
       * 界面上看起来像"删了图却留下一个奇怪的括号块"。
       */
      for (const img of gone) {
        if (!img.description) continue;
        const esc = String(img.description).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        const re = new RegExp(`^\\s*\\[\\[IMG:\\s*${esc}\\s*\\]\\]\\s*$`, 'gm');
        if (act.user) act.user = act.user.replace(re, '');
        if (act.ai) act.ai = act.ai.replace(re, '');
      }
      /* 整行删掉后可能留下连续空行，顺手收敛一下 */
      for (const key of ['user', 'ai'] as const) {
        if (typeof act[key] === 'string') {
          act[key] = (act[key] as string).replace(/\n{3,}/g, '\n\n').trim();
        }
      }

      session.updatedAt = Date.now();
      await this.save();
    },
    async removeAction(actionId: string) {
      const session = this.currentSession;
      if (!session) return;
      const act = session.actions.find((a) => a.id === actionId);
      // 连同这条的图片一起清掉，否则删了还占着存储
      if (act?.images?.length) await dropInlineImages(act.images);
      session.actions = session.actions.filter((a) => a.id !== actionId);
      await this.save();
    },

    /**
     * 只删这一条 AI 回复，**保留用户的发言**。
     *
     * 一轮对话在数据里是一个 Action（user + ai 成对）。以前界面上点"删除"
     * 直接 removeAction，把用户自己写的那句话也一起删了 —— 这不是用户想要的。
     * 现在分侧删除：删回复就只删回复，用户的话原样留着（可以再点"重新生成"）。
     */
    async removeActionAi(actionId: string) {
      const session = this.currentSession;
      if (!session) return;
      const act = session.actions.find((a) => a.id === actionId);
      if (!act) return;
      // 配图是为这段回复生成的，正文没了标记也就没了，一并清掉
      if (act.images?.length) {
        await dropInlineImages(act.images);
        act.images = undefined;
      }
      act.ai = '';
      act.variants = undefined;
      act.variantIndex = undefined;
      // 用户侧也空（或本来就没有）→ 这条没意义了，整条删掉
      if (!act.user?.trim()) {
        session.actions = session.actions.filter((a) => a.id !== actionId);
      }
      session.updatedAt = Date.now();
      await this.save();
    },

    /** 只删用户这一句，保留 AI 的回复 */
    async removeActionUser(actionId: string) {
      const session = this.currentSession;
      if (!session) return;
      const act = session.actions.find((a) => a.id === actionId);
      if (!act) return;
      act.user = '';
      // 两边都空了才整条删掉
      if (!act.ai?.trim() && !act.images?.length) {
        session.actions = session.actions.filter((a) => a.id !== actionId);
      }
      session.updatedAt = Date.now();
      await this.save();
    },

    // ---------------- 生成中的草稿 ----------------

    /**
     * 写入/更新当前会话的流式草稿。
     *
     * 由调用方节流（大约每秒一次），避免每来一个 token 就写一次盘。
     */
    async setDraft(user: string, ai: string) {
      const session = this.currentSession;
      if (!session) return;
      session.draft = { user, ai, updatedAt: Date.now() };
      await this.save();
    },

    /**
     * 收尾：把草稿转成正式的一条 action 并清掉草稿。
     * 如果正文为空（生成失败），草稿直接丢弃。
     */
    async commitDraft(aiOverride?: string) {
      const session = this.currentSession;
      if (!session?.draft) return undefined;
      const draft = session.draft;
      const ai = (aiOverride ?? draft.ai).trim();
      session.draft = undefined;
      if (!ai) {
        await this.save();
        return undefined;
      }
      const act: Action = {
        id: genId('act'),
        user: draft.user,
        ai,
        timestamp: Date.now(),
      };
      session.actions.push(act);
      session.updatedAt = Date.now();
      await this.save();
      return act;
    },

    /** 丢弃草稿（用户主动放弃这一次生成） */
    async clearDraft() {
      const session = this.currentSession;
      if (!session) return;
      session.draft = undefined;
      await this.save();
    },

    // ---------------- 消息编辑 / 分支 ----------------

    /** 修改某一条的用户指令或 AI 回复（保留旧版，可「< >」翻回） */
    async updateAction(actionId: string, patch: { user?: string; ai?: string }) {
      const session = this.currentSession;
      if (!session) return;
      const act = session.actions.find((a) => a.id === actionId);
      if (!act) return;
      pushVariant(act, {
        user: patch.user ?? act.user,
        ai: patch.ai ?? act.ai,
        timestamp: Date.now(),
      });
      session.updatedAt = Date.now();
      await this.save();
    },

    // ---------------- 版本切换 ----------------

    /**
     * 覆盖某一步的 AI 回复，但**保留旧版**。
     * 重新生成走这条路，于是生成几次就有几个版本可以前后翻。
     *
     * 注意要把**当前内容先存一次**：第一次重新生成时 variants 还是空的，
     * 如果直接写新版本，原来那条回复就永久丢了 —— 那就没法「‹ ›」翻回最初那版。
     */
    async replaceActionAi(actionId: string, ai: string, user?: string) {
      const session = this.currentSession;
      if (!session) return;
      const act = session.actions.find((a) => a.id === actionId);
      if (!act) return;

      /*
       * 先把当前内容保下来（若还没记录过）。
       *
       * ★ 但**只保真正有 AI 正文的那一份**。
       *   用户删掉回复后重新生成时，act.ai 是空串 ——
       *   以前这里会存出一个「只有用户发言、没有回复」的版本，
       *   切过去整条消息变空，而且再也切不回来（用户复现的那个 bug）。
       *   没有正文就没有"旧版本"可言，不该占一格。
       */
      const hasRealAi = !!(act.ai ?? '').trim();
      if (!act.variants?.length && hasRealAi) {
        act.variants = [
          { user: act.user, ai: act.ai, timestamp: act.timestamp || Date.now() },
        ];
        act.variantIndex = 0;
      }

      pushVariant(act, {
        user: user ?? act.user,
        ai,
        timestamp: Date.now(),
      });
      session.updatedAt = Date.now();
      await this.save();
    },

    /**
     * 重新生成前调用：如果这次要用的用户指令和当前版本不同
     * （用户在「编辑」里改了内容再点重新生成），
     * 就先记一个新的用户版本，这样指令本身也能前后翻。
     */
    async prepareUserVariant(actionId: string, nextUser: string) {
      const session = this.currentSession;
      const act = session?.actions.find((a) => a.id === actionId);
      if (!session || !act) return;
      if ((act.user ?? '') === (nextUser ?? '')) return;
      pushVariant(act, {
        user: nextUser,
        ai: act.ai,
        timestamp: Date.now(),
      });
      session.updatedAt = Date.now();
      await this.save();
    },

    /** 切换到第 n 个版本（0 = 最新） */
    async switchVariant(actionId: string, index: number) {
      const session = this.currentSession;
      const act = session?.actions.find((a) => a.id === actionId);
      if (!session || !act?.variants?.length) return;

      /*
       * 剔除「没有 AI 正文」的幽灵版本（history 里可能残留旧数据）。
       *
       * 用户复现：删了回复再重新生成，历史里多出一个空版本；
       * 切过去整条消息变空，还因为 v-if 把切换按钮一起藏了 ——
       * 再也切不回来，只能重新生成一遍把这条消息"救"出来。
       * 这里在切换前先把空版本清掉，从根上避免切进去。
       */
      const cleaned = act.variants.filter(
        (v) => (v.ai ?? '').trim() || (v.user ?? '').trim(),
      );
      if (cleaned.length !== act.variants.length) {
        act.variants = cleaned;
        // 下标可能越界，先夹回合法范围
        act.variantIndex = Math.min(act.variantIndex ?? 0, Math.max(0, cleaned.length - 1));
      }
      if (!act.variants.length) return;

      const i = Math.max(0, Math.min(index, act.variants.length - 1));
      act.variantIndex = i;
      const v = act.variants[i];
      act.user = v.user;
      act.ai = v.ai;
      session.updatedAt = Date.now();
      await this.save();
    },

    /**
     * 从某一步分支：复制出一个新会话。
     *
     * 按用户的要求，分支就是"复制对话重开一个" ——
     * 把截止到 keepUntil 的内容原样复制到新会话，原会话不动，
     * 这样可以在新会话里改指令重新生成，而不影响原来的那条线。
     */
    async branchFrom(actionId: string): Promise<AdventureSession | undefined> {
      const session = this.currentSession;
      if (!session) return undefined;
      const idx = session.actions.findIndex((a) => a.id === actionId);
      if (idx < 0) return undefined;

      // 复制到这一步**之前**为止？还是包含它？
      // 选择包含它：用户通常是想"从这个节点往下再走一条不同的路"，
      // 让他在新会话里改这一条的指令即可。
      const copied = session.actions.slice(0, idx + 1).map((a) => ({
        ...a,
        id: genId('act'),
        images: a.images ? a.images.map((im) => ({ ...im })) : undefined,
      }));

      const branch: AdventureSession = {
        id: genId('adv'),
        title: `${session.title} · 分支`,
        sceneDescription: session.sceneDescription,
        actions: copied,
        createdAt: Date.now(),
        updatedAt: Date.now(),
      };
      // 插在原会话后面，列表里能一眼看出是它的分支
      const at = this.sessions.findIndex((s) => s.id === session.id);
      this.sessions.splice(at + 1, 0, branch);
      this.currentSessionId = branch.id;
      await this.save();
      return branch;
    },

    /** 复制整个会话（不改内容，纯粹开一条并行线） */
    async duplicateSession(id: string): Promise<AdventureSession | undefined> {
      const src = this.sessions.find((s) => s.id === id);
      if (!src) return undefined;
      const copy: AdventureSession = {
        ...JSON.parse(JSON.stringify(src)),
        id: genId('adv'),
        title: `${src.title} · 副本`,
        createdAt: Date.now(),
        updatedAt: Date.now(),
      };
      copy.actions = (copy.actions || []).map((a) => ({ ...a, id: genId('act') }));
      this.sessions.push(copy);
      this.currentSessionId = copy.id;
      await this.save();
      return copy;
    },

    async updateSceneDescription(desc: string) {
      const session = this.currentSession;
      if (!session) return;
      // 同样是防止内嵌 base64 图片把存储撑爆
      const ex = extractInlineImages(desc);
      session.sceneDescription = ex.text;
      if (ex.images.length) {
        session.summaries = session.summaries ?? [];
        // 场景里的图没有消息可挂，先记到会话上（渲染时按标记显示）
        (session as any).sceneImages = [
          ...((session as any).sceneImages ?? []),
          ...ex.images.map((im, i) => ({
            id: genId('img'),
            markdownIndex: 0,
            description: im.description,
            dataUrl: im.dataUrl,
            storage: 'browser',
            createdAt: Date.now() + i,
          })),
        ];
      }
      session.updatedAt = Date.now();
      await this.save();
    },
    async updateSessionTitle(title: string) {
      const session = this.currentSession;
      if (!session) return;
      session.title = title;
      await this.save();
    },

    // ---------------- 摘要 ----------------
    //
    // 和小说那边同一套机制：把前面发生过的事压成一段「前情提要」，
    // 附在每次请求最前面。长对话靠它保住记忆，
    // 也不用把全文反复塞给模型（省 token，也避免被裁剪丢掉设定）。

    settingsOf(sessionId?: string): SessionSettings {
      const s = this.sessions.find((x) => x.id === (sessionId ?? this.currentSessionId));
      return { ...DEFAULT_SESSION_SETTINGS, ...(s?.settings ?? {}) };
    },

    async updateSettings(patch: Partial<SessionSettings>) {
      const s = this.currentSession;
      if (!s) return;
      s.settings = { ...DEFAULT_SESSION_SETTINGS, ...(s.settings ?? {}), ...patch };
      s.updatedAt = Date.now();
      await this.save();
    },

    async addSummary(entry: Omit<SummaryEntry, 'id' | 'createdAt'>) {
      const s = this.currentSession;
      if (!s) return undefined;
      const item: SummaryEntry = { ...entry, id: genId('sum'), createdAt: Date.now() };
      if (!s.summaries) s.summaries = [];
      s.summaries.unshift(item);
      await this.save();
      return item;
    },

    async updateSummary(summaryId: string, text: string) {
      const s = this.currentSession;
      const item = s?.summaries?.find((x) => x.id === summaryId);
      if (!s || !item) return;
      item.text = text;
      await this.save();
    },

    async removeSummary(summaryId: string) {
      const s = this.currentSession;
      if (!s?.summaries) return;
      s.summaries = s.summaries.filter((x) => x.id !== summaryId);
      await this.save();
    },

    /**
     * 是否到了该自动摘要的时机。
     * 只看「上次摘要之后」新增的内容，不会对同一批反复触发。
     */
    summaryDue(sessionId?: string): { coveredUpTo: number; count: number; chars: number } | null {
      const target = sessionId ?? this.currentSessionId;
      const s = this.sessions.find((x) => x.id === target);
      if (!s?.actions?.length) return null;
      const cfg = this.settingsOf(s.id);
      if (!cfg.autoSummary) return null;

      const lastCovered = s.summaries?.length
        ? Math.max(...s.summaries.map((x) => x.coveredUpTo ?? -1))
        : -1;
      const fresh = s.actions.slice(lastCovered + 1);
      if (fresh.length === 0) return null;

      const chars = fresh.reduce((n, a) => n + a.ai.length + a.user.length, 0);
      const due =
        cfg.trigger === 'messages' ? fresh.length >= cfg.everyMessages : chars >= cfg.everyChars;
      if (!due) return null;
      return { coveredUpTo: s.actions.length - 1, count: fresh.length, chars };
    },

    /** 摘要素材：上次摘要之后、到指定位置为止 */
    textForSummary(sessionId: string, coveredUpTo: number): string {
      const s = this.sessions.find((x) => x.id === sessionId);
      if (!s?.actions?.length) return '';
      const lastCovered = s.summaries?.length
        ? Math.max(...s.summaries.map((x) => x.coveredUpTo ?? -1))
        : -1;
      return s.actions
        .slice(lastCovered + 1, coveredUpTo + 1)
        .map((a) => (a.user ? `【行动】${a.user}\n${a.ai}` : a.ai))
        .join('\n\n');
    },
  },
});
