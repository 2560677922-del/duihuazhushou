import { defineStore } from 'pinia';
import { store, genId } from '../lib/storage';
import type { ImageAttachment } from '../engine/imageMarker';
import { extractInlineImages } from '../engine/imageMarker';
import { dropInlineImages } from '../lib/blobStore';

// 小说章节 store
//
// 章节有两种呈现方式，用的是同一份数据：
//   messages —— 对话式的一轮轮记录（和冒险模式一致，便于操作每一条）
//   content  —— 由 messages 的正文拼接/编辑出来的成稿
// 之所以不二选一：AI 写作既需要"逐轮改、重生成、标注重点"这种操作粒度，
// 也需要一份能通读、能导出的连续正文。

/** 章节里的一条消息（一轮交互） */
export interface ChapterMessage {
  id: string;
  /** 用户这一轮的指令 / 提示 */
  user: string;
  /** AI 的输出 */
  ai: string;
  /** 重点标注：被标记的消息在上下文里会置顶强调，也便于自己回看 */
  pinned?: boolean;
  /** 标注文字（为什么标它，给自己看的） */
  note?: string;
  images?: ImageAttachment[];
  timestamp: number;
  /**
   * 历史版本（下标 0 = 最新）。
   * 重新生成 / 编辑时把旧内容留下，用消息上的「‹ ›」前后翻。
   */
  variants?: MessageVariant[];
  /** 当前显示第几版（对应 variants 下标，0 = 最新） */
  variantIndex?: number;
}

/** 一条消息的历史版本快照 */
export interface MessageVariant {
  user: string;
  ai: string;
  timestamp: number;
}

/** 同一条消息最多保留几个版本 */
export const MAX_VARIANTS = 20;

/**
 * 记录一个新版本。约定 variants[0] 是最新版，variantIndex 指向当前显示的。
 * user / ai 始终是 variants[variantIndex] 的副本，这样切换只要改下标。
 */
function pushVariant(m: ChapterMessage, next: MessageVariant) {
  const rest = (m.variants ?? []).filter(
    (v) => !(v.user === next.user && v.ai === next.ai),
  );
  m.variants = [next, ...rest].slice(0, MAX_VARIANTS);
  m.variantIndex = 0;
  m.user = next.user;
  m.ai = next.ai;
}

/** 一条摘要记录 */
export interface SummaryEntry {
  id: string;
  /** 摘要正文 */
  text: string;
  /** 手动还是自动生成的 */
  mode: 'manual' | 'auto';
  /** 生成时覆盖到第几条消息（含） */
  coveredUpTo: number;
  /** 生成时的目标字数 */
  targetWords?: number;
  createdAt: number;
}

export interface Chapter {
  id: string;
  title: string;
  content: string;
  /**
   * 配图附件。与 content 分开存放，永不进入送给大模型的上下文。
   * 每条记录标记了它插在正文的第几个段落。
   */
  images?: ImageAttachment[];
  /** 逐轮记录（聊天式 UI 用；正稿由 content 承载） */
  messages?: ChapterMessage[];
  /** 摘要记录（最新的一条在最前面） */
  summaries?: SummaryEntry[];
  /**
   * 上一次 AI 生成的结果（未插入正文的暂存）。
   *
   * 为什么需要：AI 输出只放在组件内存里的话，切窗口 / 刷新就整段没了 ——
   * 而在章节文本这种长内容上，白等一次续写的代价很高。
   * 存在章节上，切回来还能看到、还能插入正文。
   */
  aiDraft?: {
    text: string;
    /** 当时用的动作：续写 / 改写 / 扩写 / 总结 / 对话 */
    action?: string;
    updatedAt: number;
  };
  createdAt: number;
  updatedAt: number;
}

export interface NovelProject {
  id: string;
  name: string;
  description: string;
  createdAt: number;
  updatedAt: number;
  /** 这个项目的写作设置（摘要策略等） */
  settings?: NovelSettings;
}

/** 自动摘要策略 */
export interface NovelSettings {
  /** 是否开启自动摘要 */
  autoSummary: boolean;
  /** 触发方式：按消息条数，还是按累计字数 */
  trigger: 'messages' | 'chars';
  /** 累计多少条消息触发（trigger=messages） */
  everyMessages: number;
  /** 累计多少字触发（trigger=chars） */
  everyChars: number;
  /** 让模型总结成多少字 */
  targetWords: number;
}

export const DEFAULT_NOVEL_SETTINGS: NovelSettings = {
  autoSummary: true,
  trigger: 'messages',
  everyMessages: 12,
  everyChars: 6000,
  targetWords: 300,
};

interface NovelState {
  projects: NovelProject[];
  currentProjectId?: string;
  chapters: Chapter[];
  currentChapterId?: string;
}

export const useNovelStore = defineStore('novel', {
  state: (): NovelState => ({
    projects: [],
    currentProjectId: undefined,
    chapters: [],
    currentChapterId: undefined,
  }),
  getters: {
    currentProject(): NovelProject | undefined {
      return this.projects.find((p) => p.id === this.currentProjectId);
    },
    currentChapter(): Chapter | undefined {
      return this.chapters.find((c) => c.id === this.currentChapterId);
    },
  },
  actions: {
    async load() {
      const [projects, currentProjectId, chapters, currentChapterId] = await Promise.all([
        store.getItem<NovelProject[]>('novelProjects'),
        store.getItem<string | undefined>('novelCurrentProjectId'),
        store.getItem<Chapter[]>('novelChapters'),
        store.getItem<string | undefined>('novelCurrentChapterId'),
      ]);
      this.projects = projects ?? [];
      this.currentProjectId = currentProjectId ?? undefined;
      this.chapters = chapters ?? [];
      this.currentChapterId = currentChapterId ?? undefined;

      // 首次使用：创建默认项目和章节
      if (this.projects.length === 0) {
        await this.addProject('我的小说', '一个使用 AI 辅助创作的故事');
        if (this.chapters.length === 0) {
          await this.addChapter('第一章 序章', '');
        }
        return;
      }

      // 自愈：指向已不存在的项目/章节时退回最后一个。
      // 和冒险 store 同样的问题 —— 刷新后 id 丢失会导致"什么都没了"，
      // 而 AI 暂存内容也正是挂在章节上的。
      if (!this.currentProjectId || !this.projects.some((p) => p.id === this.currentProjectId)) {
        this.currentProjectId = this.projects[this.projects.length - 1].id;
        await this.save();
      }
      if (!this.currentChapterId || !this.chapters.some((c) => c.id === this.currentChapterId)) {
        this.currentChapterId = this.chapters[this.chapters.length - 1]?.id;
        await this.save();
      }
    },
    async save() {
      await Promise.all([
        store.setItem('novelProjects', this.projects),
        store.setItem('novelCurrentProjectId', this.currentProjectId),
        store.setItem('novelChapters', this.chapters),
        store.setItem('novelCurrentChapterId', this.currentChapterId),
      ]);
    },
    async addProject(name: string, description = '') {
      const p: NovelProject = {
        id: genId('proj'),
        name,
        description,
        createdAt: Date.now(),
        updatedAt: Date.now(),
      };
      this.projects.push(p);
      this.currentProjectId = p.id;
      await this.save();
      return p;
    },
    async switchProject(id: string) {
      this.currentProjectId = id;
      // 切换项目时重新加载章节
      const chapters = await store.getItem<Chapter[] | null>(`novelChapters_${id}`);
      this.chapters = chapters ?? [];
      this.currentChapterId = this.chapters[0]?.id;
      await this.save();
    },
    async addChapter(title: string, content = '') {
      const c: Chapter = {
        id: genId('ch'),
        title,
        content,
        createdAt: Date.now(),
        updatedAt: Date.now(),
      };
      this.chapters.push(c);
      this.currentChapterId = c.id;
      if (this.currentProjectId) {
        await store.setItem(`novelChapters_${this.currentProjectId}`, this.chapters);
      }
      await this.save();
      return c;
    },
    async updateChapter(id: string, patch: Partial<Pick<Chapter, 'title' | 'content' | 'images'>>) {
      const c = this.chapters.find((x) => x.id === id);
      if (!c) return;
      Object.assign(c, patch, { updatedAt: Date.now() });
      if (this.currentProjectId) {
        await store.setItem(`novelChapters_${this.currentProjectId}`, this.chapters);
      }
      await this.save();
    },
    /** 新增或替换一条配图附件（不影响正文，也不进入大模型上下文） */
    async upsertChapterImage(chapterId: string, attachment: ImageAttachment) {
      const c = this.chapters.find((x) => x.id === chapterId);
      if (!c) return;
      const list = c.images ? [...c.images] : [];
      const i = list.findIndex((a) => a.id === attachment.id);
      if (i >= 0) list[i] = attachment;
      else list.push(attachment);
      await this.updateChapter(chapterId, { images: list });
    },
    async removeChapterImage(chapterId: string, attachmentId: string) {
      const c = this.chapters.find((x) => x.id === chapterId);
      if (!c?.images) return;
      const gone = c.images.filter((a) => a.id === attachmentId);
      if (gone.length) await dropInlineImages(gone);
      await this.updateChapter(chapterId, {
        images: c.images.filter((a) => a.id !== attachmentId),
      });
    },

    // ---------------- 章节消息（聊天式 UI） ----------------

    /** 追加一轮消息 */
    async addMessage(chapterId: string, user: string, ai: string) {
      const c = this.chapters.find((x) => x.id === chapterId);
      if (!c) return undefined;
      // 抽掉粘进来的内嵌 base64 图片，换成附件 + 标记
      const userEx = extractInlineImages(user);
      const aiEx = extractInlineImages(ai);
      const m: ChapterMessage = {
        id: genId('cmsg'),
        user: userEx.text,
        ai: aiEx.text,
        timestamp: Date.now(),
      };
      const allImages = [...userEx.images, ...aiEx.images];
      if (allImages.length) {
        m.images = allImages.map((im, i) => ({
          id: genId('img'),
          markdownIndex: 0,
          description: im.description,
          dataUrl: im.dataUrl,
          storage: 'browser' as const,
          createdAt: Date.now() + i,
        }));
      }
      if (!c.messages) c.messages = [];
      c.messages.push(m);
      c.updatedAt = Date.now();
      await this.persistChapters();
      return m;
    },

    async updateMessage(
      chapterId: string,
      messageId: string,
      patch: Partial<Pick<ChapterMessage, 'user' | 'ai' | 'pinned' | 'note'>>,
    ) {
      const c = this.chapters.find((x) => x.id === chapterId);
      const m = c?.messages?.find((x) => x.id === messageId);
      if (!c || !m) return;
      // 改内容时留下旧版，可「‹ ›」翻回；只改标注不必留版本
      if (patch.user !== undefined || patch.ai !== undefined) {
        pushVariant(m, {
          user: patch.user ?? m.user,
          ai: patch.ai ?? m.ai,
          timestamp: Date.now(),
        });
      }
      if (patch.pinned !== undefined) m.pinned = patch.pinned;
      if (patch.note !== undefined) m.note = patch.note;
      c.updatedAt = Date.now();
      await this.persistChapters();
    },

    /** 覆盖某轮的 AI 回复但保留旧版（重新生成走这条） */
    async replaceMessageAi(chapterId: string, messageId: string, ai: string, user?: string) {
      const c = this.chapters.find((x) => x.id === chapterId);
      const m = c?.messages?.find((x) => x.id === messageId);
      if (!c || !m) return;
      // 首次覆盖时先把当前内容存一次，否则最初那版永久丢失
      if (!m.variants?.length && (m.ai || m.user)) {
        m.variants = [{ user: m.user, ai: m.ai, timestamp: m.timestamp || Date.now() }];
        m.variantIndex = 0;
      }
      pushVariant(m, { user: user ?? m.user, ai, timestamp: Date.now() });
      c.updatedAt = Date.now();
      await this.persistChapters();
    },

    /** 切换到第 n 个版本（0 = 最新） */
    async switchMessageVariant(chapterId: string, messageId: string, index: number) {
      const c = this.chapters.find((x) => x.id === chapterId);
      const m = c?.messages?.find((x) => x.id === messageId);
      if (!c || !m?.variants?.length) return;
      const i = Math.max(0, Math.min(index, m.variants.length - 1));
      m.variantIndex = i;
      m.user = m.variants[i].user;
      m.ai = m.variants[i].ai;
      c.updatedAt = Date.now();
      await this.persistChapters();
    },

    /** 重新生成前记录新的用户指令版本（改了指令再重生成时用） */
    async prepareUserVariant(chapterId: string, messageId: string, nextUser: string) {
      const c = this.chapters.find((x) => x.id === chapterId);
      const m = c?.messages?.find((x) => x.id === messageId);
      if (!c || !m) return;
      if ((m.user ?? '') === (nextUser ?? '')) return;
      pushVariant(m, { user: nextUser, ai: m.ai, timestamp: Date.now() });
      c.updatedAt = Date.now();
      await this.persistChapters();
    },

    async removeMessage(chapterId: string, messageId: string) {
      const c = this.chapters.find((x) => x.id === chapterId);
      if (!c?.messages) return;
      // 这一轮带的图要真的从存储里清掉（只删数组元素的话空间还占着）
      const m = c.messages.find((x) => x.id === messageId);
      if (m?.images?.length) await dropInlineImages(m.images);
      c.messages = c.messages.filter((x) => x.id !== messageId);
      c.updatedAt = Date.now();
      await this.persistChapters();
    },

    /**
     * 只删这一条 AI 回复，**保留用户写的那句**。
     *
     * 一轮消息在数据里是 { user, ai } 成对的，以前的"删除"会把用户的话
     * 也一起删掉。现在分侧处理：删回复只清回复及其配图，
     * 用户那句留着，可以再点"重新生成"。
     */
    async removeMessageAi(chapterId: string, messageId: string) {
      const c = this.chapters.find((x) => x.id === chapterId);
      const m = c?.messages?.find((x) => x.id === messageId);
      if (!c || !m) return;
      if (m.images?.length) {
        await dropInlineImages(m.images);
        m.images = undefined;
      }
      m.ai = '';
      m.variants = undefined;
      m.variantIndex = undefined;
      // 用户那句也空 → 整条没意义了
      if (!m.user?.trim()) {
        c.messages = (c.messages ?? []).filter((x) => x.id !== messageId);
      }
      c.updatedAt = Date.now();
      await this.persistChapters();
    },

    /** 只删用户那一句，保留 AI 的回复 */
    async removeMessageUser(chapterId: string, messageId: string) {
      const c = this.chapters.find((x) => x.id === chapterId);
      const m = c?.messages?.find((x) => x.id === messageId);
      if (!c || !m) return;
      m.user = '';
      if (!m.ai?.trim() && !m.images?.length) {
        c.messages = (c.messages ?? []).filter((x) => x.id !== messageId);
      }
      c.updatedAt = Date.now();
      await this.persistChapters();
    },

    /**
     * 从某条消息分支：复制到这一步为止，生成一个新章节。
     * 和冒险模式的分支一致 —— 原章节不受影响。
     */
    async branchChapter(chapterId: string, messageId: string) {
      const c = this.chapters.find((x) => x.id === chapterId);
      if (!c?.messages) return undefined;
      const idx = c.messages.findIndex((x) => x.id === messageId);
      if (idx < 0) return undefined;
      const copied = c.messages.slice(0, idx + 1).map((m) => ({
        ...m,
        id: genId('cmsg'),
        images: m.images ? m.images.map((i) => ({ ...i })) : undefined,
      }));
      const nc: Chapter = {
        id: genId('chap'),
        title: `${c.title} · 分支`,
        content: '',
        messages: copied,
        createdAt: Date.now(),
        updatedAt: Date.now(),
      };
      const at = this.chapters.findIndex((x) => x.id === chapterId);
      this.chapters.splice(at + 1, 0, nc);
      this.currentChapterId = nc.id;
      await this.persistChapters();
      return nc;
    },

    /** 把消息里的正文拼成一份连续文本（导出 / 覆盖正稿用） */
    composeFromMessages(chapterId: string, upTo?: number): string {
      const c = this.chapters.find((x) => x.id === chapterId);
      if (!c?.messages) return '';
      const list = typeof upTo === 'number' ? c.messages.slice(0, upTo + 1) : c.messages;
      return list
        .map((m) => m.ai.trim())
        .filter(Boolean)
        .join('\n\n');
    },

    // ---------------- 摘要 ----------------

    async addSummary(chapterId: string, entry: Omit<SummaryEntry, 'id' | 'createdAt'>) {
      const c = this.chapters.find((x) => x.id === chapterId);
      if (!c) return undefined;
      const s: SummaryEntry = { ...entry, id: genId('sum'), createdAt: Date.now() };
      if (!c.summaries) c.summaries = [];
      c.summaries.unshift(s);
      await this.persistChapters();
      return s;
    },

    async updateSummary(chapterId: string, summaryId: string, text: string) {
      const c = this.chapters.find((x) => x.id === chapterId);
      const s = c?.summaries?.find((x) => x.id === summaryId);
      if (!c || !s) return;
      s.text = text;
      await this.persistChapters();
    },

    async removeSummary(chapterId: string, summaryId: string) {
      const c = this.chapters.find((x) => x.id === chapterId);
      if (!c?.summaries) return;
      c.summaries = c.summaries.filter((x) => x.id !== summaryId);
      await this.persistChapters();
    },

    /**
     * 把章节数据落到当前项目那一片。
     * 章节相关的写操作都走这里，避免各写各的漏掉某一处。
     */
    async persistChapters() {
      if (this.currentProjectId) {
        await store.setItem(`novelChapters_${this.currentProjectId}`, this.chapters);
      }
      await this.save();
    },

    // ---------------- 写作设置 / 自动摘要 ----------------

    /** 当前项目的设置（带默认值兜底） */
    settingsOf(projectId?: string): NovelSettings {
      const p = this.projects.find((x) => x.id === (projectId ?? this.currentProjectId));
      return { ...DEFAULT_NOVEL_SETTINGS, ...(p?.settings ?? {}) };
    },

    async updateSettings(patch: Partial<NovelSettings>) {
      const p = this.projects.find((x) => x.id === this.currentProjectId);
      if (!p) return;
      p.settings = { ...DEFAULT_NOVEL_SETTINGS, ...(p.settings ?? {}), ...patch };
      p.updatedAt = Date.now();
      await this.save();
    },

    /**
     * 判断某章是否需要自动摘要。
     *
     * 只看「上次摘要覆盖到哪儿之后」新增的内容，所以摘要过一次就不会
     * 被同一批内容反复触发。返回 null 表示还没到阈值。
     */
    summaryDue(chapterId: string): { coveredUpTo: number; count: number; chars: number } | null {
      const c = this.chapters.find((x) => x.id === chapterId);
      if (!c?.messages?.length) return null;
      const s = this.settingsOf();
      if (!s.autoSummary) return null;

      const lastCovered = c.summaries?.length
        ? Math.max(...c.summaries.map((x) => x.coveredUpTo ?? -1))
        : -1;
      const fresh = c.messages.slice(lastCovered + 1);
      if (fresh.length === 0) return null;

      const chars = fresh.reduce((n, m) => n + m.ai.length + m.user.length, 0);
      const due =
        s.trigger === 'messages' ? fresh.length >= s.everyMessages : chars >= s.everyChars;
      if (!due) return null;

      return { coveredUpTo: c.messages.length - 1, count: fresh.length, chars };
    },

    /** 供摘要用的原文：上次摘要之后、到指定位置为止 */
    textForSummary(chapterId: string, coveredUpTo: number): string {
      const c = this.chapters.find((x) => x.id === chapterId);
      if (!c?.messages?.length) return '';
      const lastCovered = c.summaries?.length
        ? Math.max(...c.summaries.map((x) => x.coveredUpTo ?? -1))
        : -1;
      return c.messages
        .slice(lastCovered + 1, coveredUpTo + 1)
        .map((m) => (m.user ? `【指令】${m.user}\n${m.ai}` : m.ai))
        .join('\n\n');
    },

    /** 被标注为重点的消息（会额外送进上下文） */
    pinnedMessages(chapterId: string): ChapterMessage[] {
      const c = this.chapters.find((x) => x.id === chapterId);
      return (c?.messages ?? []).filter((m) => m.pinned);
    },

    // ---------------- AI 生成结果的暂存 ----------------

    /**
     * 存下正在生成 / 刚生成完的 AI 文本。
     * 调用方节流到约每秒一次，避免每来一个 token 写一次盘。
     */
    async setAiDraft(chapterId: string, text: string, action?: string) {
      const c = this.chapters.find((x) => x.id === chapterId);
      if (!c) return;
      c.aiDraft = { text, action, updatedAt: Date.now() };
      if (this.currentProjectId) {
        await store.setItem(`novelChapters_${this.currentProjectId}`, this.chapters);
      }
      await this.save();
    },

    async clearAiDraft(chapterId: string) {
      const c = this.chapters.find((x) => x.id === chapterId);
      if (!c) return;
      c.aiDraft = undefined;
      if (this.currentProjectId) {
        await store.setItem(`novelChapters_${this.currentProjectId}`, this.chapters);
      }
      await this.save();
    },
    async removeChapter(id: string) {
      this.chapters = this.chapters.filter((c) => c.id !== id);
      if (this.currentChapterId === id) {
        this.currentChapterId = this.chapters[0]?.id;
      }
      if (this.currentProjectId) {
        await store.setItem(`novelChapters_${this.currentProjectId}`, this.chapters);
      }
      await this.save();
    },
  },
});
