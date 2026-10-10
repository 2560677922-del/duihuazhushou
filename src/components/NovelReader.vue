<script setup lang="ts">
// 沉浸式阅读
//
// 定位：**小说编辑器的子功能**，不是独立页面。
//   必须先有内容才能读 —— 用 AI 写完几章，才谈得上"读一遍"。
//   所以入口在编辑器工具栏上，点开以**全屏层**盖住整页。
//
// 为什么用全屏层而不是路由页：
//   读完要能立刻回去接着写。全屏层关掉就回到编辑器，
//   原状态（选中章节、输入框里的草稿）全都在；跳路由则要重新加载。
//
// 交互（对齐常见阅读器）：
//   · 左右滑动 / 点屏幕左右边缘 → 上一章 / 下一章
//   · 点屏幕中间 → 显示或隐藏顶栏（沉浸 ↔ 带信息）
//   · 图片上只留一个「重新生图」按钮，其余一切照小说排版
//   · 可跳章（目录）、可导出 TXT
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useNovelStore } from '../stores/novel';
import { useAssetsStore } from '../stores/assets';
import { useStorageSettingsStore } from '../stores/storageSettings';
import { displayTextWith } from '../composables/useDisplayText';
import { imageBlobs } from '../lib/imageBlobDb';
import { resolveCached } from '../lib/lazyImage';
import { fetchGithubImageAsDataUrl } from '../engine/githubStorage';
import { buildContentBlocks, stripImageMarkers, type ImageAttachment } from '../engine/imageMarker';
import { useQuickImage } from '../composables/useQuickImage';
import { useImageStore } from '../stores/image';
import MarkdownRenderer from './MarkdownRenderer.vue';
import ImageViewer from './ImageViewer.vue';

const props = defineProps<{
  /** 从哪一章开始读（通常是编辑器当前那一章） */
  startChapterId?: string;
}>();

const emit = defineEmits<{ close: [] }>();

const novel = useNovelStore();
const assets = useAssetsStore();
const storageSettings = useStorageSettingsStore();
const quickImg = useQuickImage();
const imageStore = useImageStore();

/** 当前读到第几章 */
const index = ref(0);
/** 顶栏是否显示（点屏幕中间切换） */
const chromeVisible = ref(true);
/** 目录抽屉 */
const showToc = ref(false);
/** 字号（阅读器自己的设置，存本地，跟章节无关） */
const fontSize = ref(17);
/** 行高 */
const lineHeight = ref(1.9);
/** 放大看的图 */
const zoomed = ref<ImageAttachment | null>(null);
/** 重新生图进行中 */
const regenBusy = ref(false);
/** 提示条 */
const toast = ref('');

const chapters = computed(() => novel.chapters || []);
const chapter = computed(() => chapters.value[index.value]);

/**
 * 正文内容：优先用 content（成稿），没有就退回首条消息。
 * 有些人只在对话区写、没点"追加到正文"，那种情况也该能读。
 */
const rawText = computed(() => {
  const c = chapter.value;
  if (!c) return '';
  if (c.content && c.content.trim()) return c.content;
  const msgs = c.messages || [];
  return msgs
    .map((m) => (m.ai || '').trim())
    .filter(Boolean)
    .join('\n\n');
});

/** 跑完正则、剥完思维链之后真正显示的文本（和编辑器里看到的一致） */
const displayContent = computed(() =>
  displayTextWith(rawText.value, {
    hideReasoning: storageSettings.hideReasoning,
    cutMarker: storageSettings.reasoningCutMarker,
    scripts: assets.getCurrentRegexScripts(),
  }),
);

/** 按图片标记切块（图片插在标记位置） */
const blocks = computed(() =>
  buildContentBlocks(displayContent.value, chapter.value?.images || []),
);

/** 没有正文时的空状态 */
const isEmpty = computed(() => !displayContent.value.trim());

// ---------------- 图片地址解析（与 ContentWithImages 同一套兜底顺序） ----------------

const resolved = ref<Record<string, string>>({});
const failed = ref<Record<string, boolean>>({});

async function resolveImage(a: ImageAttachment) {
  if (resolved.value[a.id] || failed.value[a.id]) return;
  /*
   * 存图时 dataURL 已被外置成 `blob:<id>` 短引用（见 lib/blobStore.ts），
   * 直接拿 a.dataUrl 去渲染会得到裂图 —— 必须先查库换回本体。
   *
   * resolveCached 顺带做了两层：
   *   · 是引用就去 IndexedDB 取（带 LRU 缓存，来回滚动不会反复读）
   *   · 取不到返回 null，让下面继续试远端
   */
  const local = await resolveCached(a.dataUrl);
  if (local) {
    resolved.value = { ...resolved.value, [a.id]: local };
    return;
  }
  // 2) 本地图片存储区（旧数据：键就是附件 id）
  if (!a.dataUrl) {
    try {
      const dataUrl = await imageBlobs.get(a.id);
      if (dataUrl) {
        resolved.value = { ...resolved.value, [a.id]: dataUrl };
        return;
      }
    } catch {
      /* 继续下一条 */
    }
  }
  // 3) 远端（GitHub 仓库）
  const gh = storageSettings.activeGithub();
  if (gh) {
    try {
      const url = await fetchGithubImageAsDataUrl(gh, a.id);
      if (url) {
        resolved.value = { ...resolved.value, [a.id]: url };
        return;
      }
    } catch {
      /* 落空 */
    }
  }
  failed.value = { ...failed.value, [a.id]: true };
}

// 章节切换时把解析结果清掉（不同章节的图不同）
watch(
  () => chapter.value?.id,
  () => {
    resolved.value = {};
    failed.value = {};
  },
);

// ---------------- 翻页 ----------------

function go(delta: number) {
  const next = index.value + delta;
  if (next < 0) return;
  if (next >= chapters.value.length) return;
  index.value = next;
  // 换章后回到顶部（长文阅读必须的）
  window.scrollTo({ top: 0 });
  saveProgress();
}

function jumpTo(i: number) {
  index.value = i;
  showToc.value = false;
  window.scrollTo({ top: 0 });
  saveProgress();
}

/**
 * 记住读到哪 —— 下次进来自动跳回去。
 *
 * 存 localStorage 而不是 store：这是**这台设备上的阅读进度**，
 * 没必要跟着配置同步到别的设备（在手机上读到第 5 章，
 * 不代表在电脑上也想从第 5 章开始）。
 */
function progressKey() {
  return 'reader-progress';
}
function saveProgress() {
  try {
    if (chapter.value) localStorage.setItem(progressKey(), chapter.value.id);
  } catch {
    /* 隐私模式下 localStorage 可能不可用 */
  }
}
function restoreProgress() {
  try {
    const lastId = localStorage.getItem(progressKey());
    if (!lastId) return;
    const i = chapters.value.findIndex((c) => c.id === lastId);
    if (i >= 0) index.value = i;
  } catch {
    /* 同上 */
  }
}

/** 阅读器自己的偏好（字号/行高），也存本地 */
function loadPrefs() {
  try {
    const f = localStorage.getItem('reader-font-size');
    if (f) fontSize.value = Math.min(28, Math.max(13, Number(f) || 17));
    const l = localStorage.getItem('reader-line-height');
    if (l) lineHeight.value = Math.min(2.6, Math.max(1.4, Number(l) || 1.9));
  } catch {
    /* 忽略 */
  }
}
function savePrefs() {
  try {
    localStorage.setItem('reader-font-size', String(fontSize.value));
    localStorage.setItem('reader-line-height', String(lineHeight.value));
  } catch {
    /* 忽略 */
  }
}
watch([fontSize, lineHeight], savePrefs);

// ---------------- 点屏幕：中间切顶栏，左右翻页 ----------------

let touchStartX = 0;
let touchStartY = 0;
/** 区分"点"和"滑"：位移超过这个像素就算滑动，不算点 */
const SWIPE_THRESHOLD = 40;

function onTouchStart(e: TouchEvent) {
  const t = e.touches[0];
  if (!t) return;
  touchStartX = t.clientX;
  touchStartY = t.clientY;
}
function onTouchEnd(e: TouchEvent) {
  const t = e.changedTouches[0];
  if (!t) return;
  const dx = t.clientX - touchStartX;
  const dy = t.clientY - touchStartY;

  // 竖向位移更大 → 是在滚动阅读，不当作翻页
  if (Math.abs(dy) > Math.abs(dx)) return;

  if (Math.abs(dx) > SWIPE_THRESHOLD) {
    go(dx < 0 ? 1 : -1);
    return;
  }

  // 视为"点"：按横坐标分区
  const w = window.innerWidth;
  const x = t.clientX;
  if (x < w * 0.28) {
    go(-1);
  } else if (x > w * 0.72) {
    go(1);
  } else {
    chromeVisible.value = !chromeVisible.value;
  }
}

/** 桌面端点鼠标也能翻页 */
function onScreenClick(e: MouseEvent) {
  // 点在控件上不处理（按钮自己有 @click）
  const el = e.target as HTMLElement;
  if (el.closest('button, a, input, select, .reader-toc, .reader-bar')) return;
  const w = window.innerWidth;
  const x = e.clientX;
  if (x < w * 0.28) go(-1);
  else if (x > w * 0.72) go(1);
  else chromeVisible.value = !chromeVisible.value;
}

// ---------------- 重新生图（沉浸阅读里唯一的按钮） ----------------

async function regenImage(a: ImageAttachment) {
  if (regenBusy.value) return;
  const ch = chapter.value;
  if (!ch) return;
  regenBusy.value = true;
  toast.value = '正在重新生成…';
  try {
    const fresh = await quickImg.regenerateFromAttachment(a, '小说插图');
    if (!fresh) {
      toast.value = '没拿到结果，检查一下生图配置';
    } else {
      // 原地替换，不能新增（新增会让正文多出一个标记）
      const imgs = (ch.images || []).map((x: ImageAttachment) =>
        x.id === a.id ? { ...fresh, id: a.id, markdownIndex: x.markdownIndex } : x,
      );
      await novel.updateChapter(ch.id, { images: imgs });
      // 解析缓存里那张也要更新，否则界面还显示旧图
      if (fresh.dataUrl) resolved.value = { ...resolved.value, [a.id]: fresh.dataUrl };
      toast.value = '已重新生成';
    }
  } catch (e) {
    toast.value = '生成失败：' + (e instanceof Error ? e.message : String(e));
  } finally {
    regenBusy.value = false;
    setTimeout(() => (toast.value = ''), 2600);
  }
}

// ---------------- 导出 TXT ----------------

/**
 * 导出成通用 TXT。
 *
 * 关于"正则替换后的文本能不能正确导出"这个问题：
 *   能，因为这里用的是 **displayTextWith** —— 和界面上显示走的是同一个函数。
 *   导出的是"你看到的样子"，不是原始输出（不含思维链、不含正则没处理掉的东西）。
 *   所以不需要额外做一遍正则，也不会两边不一致。
 *
 * 格式上按常见小说站的习惯：书名 → 每章「章节名」+ 正文，空行分隔。
 * 编码用 UTF-8 并加 BOM，这样 Windows 记事本打开不会乱码。
 */
function buildTxt(): string {
  const out: string[] = [];
  const bookName = novel.currentProject?.name || '未命名作品';
  out.push(bookName);
  out.push('');

  for (const c of chapters.value) {
    const source =
      (c.content && c.content.trim()) ||
      (c.messages || [])
        .map((m) => (m.ai || '').trim())
        .filter(Boolean)
        .join('\n\n');
    /*
     * exportRaw 打开时导出**原文**（不剥思维链、不跑正则），
     * 适合"换个平台继续用" —— 那边可能认得 <details> 这类标记。
     * 默认仍导出"你看到的样子"。
     */
    const text = storageSettings.exportRaw
      ? source
      : displayTextWith(source, {
          hideReasoning: storageSettings.hideReasoning,
          cutMarker: storageSettings.reasoningCutMarker,
          scripts: assets.getCurrentRegexScripts(),
        });
    out.push(c.title || '未命名章节');
    out.push('');

    /*
     * 图片标记在纯文本里没有意义，必须去掉。
     *
     * 但直接删标记会**留下空段**：正文是 `段落A\n\n[[IMG: x]]\n\n段落B`，
     * 删掉标记后成了 `段落A\n\n\n\n段落B` —— 就是两个空行，
     * 阅读时看着像排版坏了（实测出现过）。
     *
     * 所以删完还要把"连续 3 个以上换行"压回 2 个（即一个空行），
     * 顺手去掉行尾空格（有些阅读器会把带空格的空行显示得特别大）。
     */
    const cleaned = stripImageMarkers(text)
      .replace(/[ \t]+$/gm, '')
      .replace(/\n{3,}/g, '\n\n')
      .trim();

    out.push(cleaned);
    out.push('');
  }

  // 章节之间空一行即可；去掉收尾多余的换行
  return out.join('\r\n').replace(/(\r\n){3,}/g, '\r\n\r\n').trimEnd() + '\r\n';
}

function exportTxt() {
  try {
    const text = buildTxt();
    // \uFEFF = BOM，让记事本认得出 UTF-8
    const blob = new Blob(['\uFEFF' + text], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = (novel.currentProject?.name || '小说') + '.txt';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    // 立刻 revoke 有些浏览器会来不及下载，延后一点
    setTimeout(() => URL.revokeObjectURL(url), 4000);
    toast.value = '已导出 TXT';
    setTimeout(() => (toast.value = ''), 2600);
  } catch (e) {
    toast.value = '导出失败：' + (e instanceof Error ? e.message : String(e));
  }
}

// ---------------- 键盘（桌面端） ----------------

function onKey(e: KeyboardEvent) {
  if (e.key === 'ArrowRight' || e.key === 'PageDown') go(1);
  else if (e.key === 'ArrowLeft' || e.key === 'PageUp') go(-1);
  else if (e.key === 'Escape') emit('close');
}

onMounted(async () => {
  await Promise.all([novel.load(), assets.load(), imageStore.load()]);
  loadPrefs();
  // 进来先定位到"正在编辑的那一章"，没记录才用第一章
  const wantId = props.startChapterId || novel.currentChapterId;
  const i = chapters.value.findIndex((c) => c.id === wantId);
  if (i >= 0) index.value = i;
  else restoreProgress();
  /*
   * 图片**不**在这里批量解析。
   *
   * 一章里几十张图全读进 IndexedDB → base64，手机上翻书直接卡住。
   * 改成：图片元素滚进视口时才去取（见模板里的 v-lazy-img 指令）。
   * 首屏那几张会在挂载后由观察器立刻补上，所以看不出延迟。
   */
  window.addEventListener('keydown', onKey);
});

onUnmounted(() => {
  window.removeEventListener('keydown', onKey);
});

/** 滚进视口才解析的图片（和图片库同一个做法） */
const vLazyImg = {
  mounted(el: HTMLElement, binding: { value: ImageAttachment | undefined }) {
    const a = binding.value;
    if (!a) return;
    if (resolved.value[a.id]) return;
    if (typeof IntersectionObserver === 'undefined') {
      void resolveImage(a);
      return;
    }
    const io = new IntersectionObserver(
      (entries) => {
        for (const e of entries) {
          if (!e.isIntersecting) continue;
          void resolveImage(a);
          io.disconnect();
        }
      },
      { rootMargin: '400px 0px' },
    );
    io.observe(el);
    (el as any).__lazyIo = io;
  },
  unmounted(el: HTMLElement) {
    (el as any).__lazyIo?.disconnect?.();
  },
};

/** 章节标题（没有就用序号） */
function titleOf(i: number) {
  const c = chapters.value[i];
  return c?.title || `第 ${i + 1} 章`;
}
</script>

<template>
  <div
    class="reader"
    :class="{ 'chrome-hidden': !chromeVisible }"
    @touchstart.passive="onTouchStart"
    @touchend.passive="onTouchEnd"
    @click="onScreenClick"
  >
    <!-- 顶部栏：点屏幕中间可以收起来，收起后就是纯正文 -->
    <header class="reader-bar top" v-show="chromeVisible">
      <button class="rb-btn" @click.stop="emit('close')" title="回到编辑器">
        ✎
      </button>
      <span class="rb-title">{{ novel.currentProject?.name || '小说' }}</span>
      <button class="rb-btn" @click.stop="showToc = true" title="目录">☰</button>
    </header>

    <!-- 正文 -->
    <main class="reader-page">
      <h1 v-if="chapter" class="chapter-heading">{{ chapter.title || `第 ${index + 1} 章` }}</h1>

      <div v-if="isEmpty" class="reader-empty">
        <p>这一章还没有内容。</p>
        <button class="rb-btn primary" @click.stop="emit('close')">
          去写
        </button>
      </div>

      <template v-else>
        <template v-for="(b, i) in blocks" :key="i">
          <!-- 图片：只留一个「重新生图」按钮，其余一切照小说排版 -->
          <figure v-if="b.kind === 'image' && b.attachment" v-lazy-img="b.attachment" class="reader-figure">
            <img
              v-if="resolved[b.attachment.id]"
              :src="resolved[b.attachment.id]"
              :alt="b.attachment.description || '插图'"
              decoding="async"
              @click.stop="zoomed = b.attachment"
            />
            <div v-else-if="failed[b.attachment.id]" class="fig-failed">图片加载失败</div>
            <div v-else class="fig-loading">图片加载中…</div>

            <button
              class="fig-regen"
              :disabled="regenBusy"
              @click.stop="regenImage(b.attachment)"
              title="用同样的描述重新生成这一张"
            >
              🔄 重新生图
            </button>
          </figure>

          <!-- 文字：走和编辑器一致的 markdown 渲染 -->
          <MarkdownRenderer
            v-else-if="b.kind === 'text'"
            class="reader-text"
            :content="b.text"
            :style="{ fontSize: fontSize + 'px', lineHeight: lineHeight }"
          />
        </template>
      </template>
    </main>

    <!-- 底部栏：进度 + 翻页 + 字号 -->
    <footer class="reader-bar bottom" v-show="chromeVisible">
      <button class="rb-btn" :disabled="index <= 0" @click.stop="go(-1)" title="上一章">‹</button>
      <span class="rb-progress">{{ index + 1 }} / {{ chapters.length }}</span>
      <button class="rb-btn" :disabled="index >= chapters.length - 1" @click.stop="go(1)" title="下一章">›</button>
      <span class="rb-gap"></span>
      <button class="rb-btn" @click.stop="fontSize = Math.max(13, fontSize - 1)" title="减小字号">A-</button>
      <button class="rb-btn" @click.stop="fontSize = Math.min(28, fontSize + 1)" title="增大字号">A+</button>
      <button class="rb-btn" @click.stop="exportTxt" title="导出 TXT">⤓</button>
    </footer>

    <!-- 目录 -->
    <div v-if="showToc" class="reader-mask" @click.stop="showToc = false"></div>
    <aside v-if="showToc" class="reader-toc" @click.stop>
      <div class="toc-head">
        <span>目录 · {{ chapters.length }} 章</span>
        <button class="rb-btn" @click="showToc = false">✕</button>
      </div>
      <button
        v-for="(c, i) in chapters"
        :key="c.id"
        class="toc-item"
        :class="{ active: i === index }"
        @click="jumpTo(i)"
      >
        <span class="toc-idx">{{ i + 1 }}</span>
        <span class="toc-name">{{ titleOf(i) }}</span>
        <span class="toc-len">{{ (c.content || '').length }} 字</span>
      </button>
    </aside>

    <!-- 提示 -->
    <div v-if="toast" class="reader-toast">{{ toast }}</div>

    <ImageViewer
      v-if="zoomed"
      :src="resolved[zoomed.id] || ''"
      :description="zoomed.description"
      @close="zoomed = null"
    />
  </div>
</template>

<style scoped>
.reader {
  position: fixed;
  inset: 0;
  z-index: 200;
  background: var(--bg);
  overflow-y: auto;
  /* 阅读时不需要底部标签栏的让位，这里自己管全屏 */
  overscroll-behavior: contain;
}

/* 顶栏/底栏：半透明玻璃，浮在正文之上 */
.reader-bar {
  position: fixed;
  left: 0;
  right: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: var(--glass, var(--panel));
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  transition: transform 0.2s, opacity 0.2s;
}
.reader-bar.top {
  top: 0;
  padding-top: calc(8px + env(safe-area-inset-top, 0px));
  border-bottom: 1px solid var(--hairline, var(--border));
}
.reader-bar.bottom {
  bottom: 0;
  padding-bottom: calc(8px + env(safe-area-inset-bottom, 0px));
  border-top: 1px solid var(--hairline, var(--border));
}
.chrome-hidden .reader-bar.top {
  transform: translateY(-110%);
  opacity: 0;
}
.chrome-hidden .reader-bar.bottom {
  transform: translateY(110%);
  opacity: 0;
}

.rb-btn {
  padding: 6px 10px;
  font-size: 13px;
  border-radius: var(--radius-sm);
  background: var(--panel-2);
  border: 1px solid var(--border);
  box-shadow: none;
  color: var(--text);
}
.rb-btn.primary {
  background: var(--accent-grad, var(--accent));
  color: #fff;
  border-color: transparent;
}
.rb-btn:disabled {
  opacity: 0.4;
}
.rb-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 600;
}
.rb-progress {
  font-size: 12px;
  color: var(--text-dim);
  min-width: 56px;
  text-align: center;
}
.rb-gap {
  flex: 1;
}

/* ---------------- 正文排版：这是阅读器的核心 ---------------- */

.reader-page {
  /* 上下留出工具栏的高度，正文不被压住 */
  padding: 68px 22px 84px;
  max-width: 760px;
  margin: 0 auto;
}
.chrome-hidden .reader-page {
  padding-top: 34px;
  padding-bottom: 40px;
}

.chapter-heading {
  font-size: 19px;
  font-weight: 700;
  margin: 0 0 22px;
  text-align: center;
  color: var(--text);
}

.reader-text {
  color: var(--text);
  /* 首行缩进两个字符：中文小说的习惯 */
  text-indent: 2em;
  margin: 0 0 0.4em;
  letter-spacing: 0.02em;
  word-break: break-word;
}
/* markdown 块级元素的缩进处理：标题/列表/引用不缩进 */
.reader-text :deep(h1),
.reader-text :deep(h2),
.reader-text :deep(h3),
.reader-text :deep(ul),
.reader-text :deep(ol),
.reader-text :deep(blockquote),
.reader-text :deep(pre) {
  text-indent: 0;
}
.reader-text :deep(p) {
  text-indent: 2em;
  margin: 0 0 0.7em;
}
.reader-text :deep(hr) {
  border: none;
  border-top: 1px solid var(--border);
  margin: 1.4em 2em;
}

.reader-empty {
  text-align: center;
  padding: 60px 20px;
  color: var(--text-dim);
  font-size: 14px;
}

/* ---------------- 插图 ---------------- */

.reader-figure {
  position: relative;
  margin: 1.4em 0;
  text-align: center;
}
.reader-figure img {
  max-width: 100%;
  border-radius: var(--radius);
  box-shadow: var(--shadow-2);
  cursor: zoom-in;
}
.fig-loading,
.fig-failed {
  padding: 30px;
  border: 1px dashed var(--border);
  border-radius: var(--radius);
  color: var(--text-dim);
  font-size: 12px;
}
/* 沉浸阅读里**唯一**的按钮：半透明常显，别像以前那样藏起来吃点击 */
.fig-regen {
  position: absolute;
  right: 10px;
  bottom: 10px;
  padding: 5px 12px;
  font-size: 11.5px;
  border-radius: var(--radius-pill);
  background: rgba(12, 14, 20, 0.72);
  color: #fff;
  border: 1px solid rgba(255, 255, 255, 0.28);
  backdrop-filter: blur(4px);
  opacity: 0.72;
  transition: opacity 0.15s;
}
.fig-regen:hover,
.fig-regen:active {
  opacity: 1;
}
.fig-regen:disabled {
  opacity: 0.5;
}

/* ---------------- 目录 ---------------- */

.reader-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.42);
  z-index: 10;
}
.reader-toc {
  position: fixed;
  top: 0;
  bottom: 0;
  right: 0;
  width: min(78vw, 320px);
  z-index: 11;
  background: var(--panel);
  border-left: 1px solid var(--border);
  box-shadow: var(--shadow-3);
  overflow-y: auto;
  padding-bottom: env(safe-area-inset-bottom, 0px);
}
.toc-head {
  position: sticky;
  top: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 12px 14px;
  background: var(--panel);
  border-bottom: 1px solid var(--border);
  font-size: 13px;
  font-weight: 600;
}
.toc-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 11px 14px;
  text-align: left;
  background: transparent;
  border: none;
  border-bottom: 1px solid var(--hairline, var(--border));
  box-shadow: none;
  color: var(--text);
  font-size: 13px;
}
.toc-item.active {
  background: color-mix(in srgb, var(--accent) 12%, transparent);
  color: var(--accent);
  font-weight: 700;
}
.toc-idx {
  flex-shrink: 0;
  width: 22px;
  font-size: 11px;
  color: var(--text-dim);
}
.toc-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.toc-len {
  flex-shrink: 0;
  font-size: 10.5px;
  color: var(--text-dim);
}

/* ---------------- 提示 ---------------- */
.reader-toast {
  position: fixed;
  left: 50%;
  bottom: 96px;
  transform: translateX(-50%);
  z-index: 12;
  padding: 9px 16px;
  border-radius: var(--radius-pill);
  background: rgba(12, 14, 20, 0.86);
  color: #fff;
  font-size: 12.5px;
  backdrop-filter: blur(6px);
  max-width: 80vw;
  text-align: center;
}

@media (max-width: 768px) {
  .reader-page {
    padding: 62px 18px 78px;
  }
  .chapter-heading {
    font-size: 17.5px;
  }
}
</style>
