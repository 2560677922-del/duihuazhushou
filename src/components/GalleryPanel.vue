<script setup lang="ts">
// 图片库面板（可嵌入）
//
// 抽成组件是为了**只有一份实现**：以前它在独立页面（/gallery），
// 后来为了给底部标签栏腾格子，挪进「资产」页当子 tab。
// 如果各写一份，改一处忘一处 —— 这种坑在这个项目里踩过几次了。
//
// 数据与逻辑与原来完全一致，只是去掉了"整页外壳"（标题栏），
// 由宿主页面提供。
// 图片库
//
// 为什么要单独一页：
//   生图结果 / 自动配图 / 标签页直出的图，之前只是散落在各个界面的消息里，
//   或者默默存在浏览器里 —— 用户根本不知道图去哪了，也没法集中管理。
//   这里把所有生成过的图集中列出来，可看、可下、可删、可复用。
import { ref, computed, onMounted } from 'vue';
import { useImageStore } from '../stores/image';
import { listAssets, removeAsset, removeAllAssets, assetUrl, assetCount } from '../lib/imageAssets';
import { imageBlobs } from '../lib/imageBlobDb';
import { store } from '../lib/storage';
import { useStorageSettingsStore } from '../stores/storageSettings';
import { resolveCached } from '../lib/lazyImage';

const imageStore = useImageStore();
const settings = useStorageSettingsStore();

const filter = ref<'all' | 'novel' | 'adventure'>('all');
const query = ref('');
const previewId = ref<string | null>(null);
const usageBytes = ref(0);
const busy = ref(false);
const message = ref('');

/* ---------------------------------------------------------------------------
   图片懒加载
   ---------------------------------------------------------------------------
   存图时 dataURL 被外置成 `blob:<id>` 短引用（几十字节），
   所以 src 不能直接用它 —— `<img src="blob:xxx">` 是裂图。

   做法：一张图滚进视口才去 IndexedDB 取本体。
   这正是图片库以前越用越卡的原因 —— 一进页面就把几十张图全读进内存了。
   --------------------------------------------------------------------------- */

/** id → 已解析好的 dataURL（只存已加载的，滚走也不清，省得来回滚时反复读） */
const thumbs = ref<Record<string, string>>({});

function thumbOf(id: string): string | undefined {
  return thumbs.value[id];
}

/** 网格里的图进视口时调用 */
async function loadThumb(img: { id: string; dataUrl?: string }): Promise<void> {
  if (thumbs.value[img.id]) return;
  const url = await resolveCached(img.id);
  if (url) thumbs.value = { ...thumbs.value, [img.id]: url };
}

/** 进视口才加载的自定义指令 */
const vLazyImg = {
  mounted(el: HTMLElement, binding: { value: { id: string; dataUrl?: string } }) {
    void loadThumb(binding.value);
    if (typeof IntersectionObserver === 'undefined') return;
    const io = new IntersectionObserver(
      (entries) => {
        for (const e of entries) {
          if (!e.isIntersecting) continue;
          void loadThumb(binding.value);
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

/** 点开大图：先把本体取回来再显示 */
async function openPreview(id: string) {
  const url = await assetUrl(id);
  if (!url) {
    message.value = '这张图的本地副本已经不在了';
    setTimeout(() => (message.value = ''), 2500);
    return;
  }
  thumbs.value = { ...thumbs.value, [id]: url };
  previewId.value = id;
}

const list = computed(() => {
  let arr = listAssets();
  if (filter.value !== 'all') arr = arr.filter((x) => x.scene === filter.value);
  const q = query.value.trim().toLowerCase();
  if (q) {
    arr = arr.filter(
      (x) =>
        x.prompt.toLowerCase().includes(q) ||
        (x.origin || '').toLowerCase().includes(q) ||
        (x.model || '').toLowerCase().includes(q),
    );
  }
  return arr;
});

const preview = computed(() => list.value.find((x) => x.id === previewId.value) ?? null);

async function refreshUsage() {
  usageBytes.value = await imageBlobs.usage();
}

onMounted(async () => {
  await Promise.all([imageStore.load(), settings.load()]);
  await refreshUsage();
});

function fmtSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function fmtTime(t: number): string {
  const d = new Date(t);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getMonth() + 1}/${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/**
 * 下载 / 预览前先把地址解析成能用的东西。
 *
 * 存图时 dataURL 被外置成了 `blob:<id>` 短引用，直接拿给 <a href> 下载
 * 会存下一个坏文件。这里按需取回本体。
 */
async function urlOf(id: string): Promise<string | null> {
  return assetUrl(id);
}

async function download(img: { id: string; timestamp: number }) {
  const url = await urlOf(img.id);
  if (!url) {
    message.value = '这张图的本地副本已经不在了';
    setTimeout(() => (message.value = ''), 2500);
    return;
  }
  const a = document.createElement('a');
  a.href = url;
  a.download = `配图-${img.timestamp}.png`;
  a.click();
}

async function copyPrompt(img: { prompt: string }) {
  try {
    await navigator.clipboard.writeText(img.prompt);
    message.value = '提示词已复制';
  } catch {
    message.value = '复制失败，请手动选中';
  }
  setTimeout(() => (message.value = ''), 2500);
}

async function removeOne(id: string) {
  await removeAsset(id);
  if (previewId.value === id) previewId.value = null;
  /* 缓存里那份也丢掉，否则删完再翻会拿到已经没了的图 */
  const next = { ...thumbs.value };
  delete next[id];
  thumbs.value = next;
  await refreshUsage();
}

async function clearAll() {
  if (!assetCount.value) return;
  if (!window.confirm(`删除全部 ${assetCount.value} 张图？此操作不可撤销。`)) return;
  await removeAllAssets();
  previewId.value = null;
  await refreshUsage();
}

/** 显示一下图片库里实际有多少对象，便于判断有没有残留 */
async function checkBlobs() {
  busy.value = true;
  message.value = '';
  try {
    const keys = await imageBlobs.keys();
    message.value = `当前有 ${assetCount.value} 条记录，图片库 ${keys.length} 个对象`;
  } finally {
    busy.value = false;
    setTimeout(() => (message.value = ''), 5000);
  }
}

/**
 * 清理无人引用的图片。
 *
 * 老版本的保存逻辑有个 bug：每保存一次就把所有图重写一份（id 是随机的），
 * 旧的那份没人引用也永远不会删。手机里可能已经堆了几百 MB。
 * 这里手动触发一次回收，把那些清掉。
 */
async function cleanOrphans() {
  busy.value = true;
  message.value = '正在清理…';
  try {
    const before = await imageBlobs.usage();
    const r = await store.gcOrphanBlobs();
    const after = await imageBlobs.usage();
    usageBytes.value = after;
    if (r.removed === 0) {
      message.value = '没有可清理的残留，已经是干净的';
    } else {
      message.value =
        `已清理 ${r.removed} 个无人引用的图片对象，` +
        `占用从 ${fmtSize(before)} 降到 ${fmtSize(after)}`;
    }
  } catch (e) {
    message.value = `清理失败：${e instanceof Error ? e.message : String(e)}`;
  } finally {
    busy.value = false;
    setTimeout(() => (message.value = ''), 8000);
  }
}
</script>

<template>
  <div class="gallery-panel">
    <!-- 已裁剪：原为 187 字的界面说明（只对开发者有意义） -->

    <div class="toolbar">
      <div class="seg">
        <button :class="{ active: filter === 'all' }" @click="filter = 'all'">全部</button>
        <button :class="{ active: filter === 'novel' }" @click="filter = 'novel'">小说</button>
        <button :class="{ active: filter === 'adventure' }" @click="filter = 'adventure'">冒险</button>
      </div>
      <input type="text" v-model="query" placeholder="搜索提示词 / 模型…" class="search" />
    </div>

    <!--
      存储维护：容量 + 三个操作。
      （上一版这里有一段 180 多字的说明，已删；操作本身留着。）
    -->
    <div class="storage-row">
      <span class="dim">
        占用 {{ usageBytes ? (usageBytes / 1048576).toFixed(1) + ' MB' : '—' }}
        <template v-if="message"> · {{ message }}</template>
      </span>
      <span class="spacer"></span>
      <button class="mini" :disabled="busy" @click="checkBlobs">检查占用</button>
      <button class="mini" :disabled="busy" @click="cleanOrphans">清理残留</button>
      <button class="mini danger" :disabled="busy || !assetCount" @click="clearAll">清空全部</button>
    </div>

    <div v-if="!list.length" class="empty">
      <p v-if="!assetCount">还没有生成过图片。</p>
      <p v-else>没有符合条件的图片。</p>
      <p class="hint-sm">
        在小说/冒险页点 🎨 生图，或到「标签库」用标签直出，图都会出现在这里。
      </p>
    </div>

    <div v-else class="grid">
      <div v-for="img in list" :key="img.id" class="cell">
        <!--
          缩略图懒加载：进视口才去取图。
          `loading="lazy"` 只挡住**网络请求**，挡不住 IndexedDB 读取 ——
          存图时 dataURL 已被外置成 blob: 引用，必须自己解析才能给 src。
        -->
        <img
          v-lazy-img="img"
          :src="thumbOf(img.id)"
          alt="生成图"
          @click="openPreview(img.id)"
        />
        <div class="cell-meta">
          <span class="scene">{{ img.scene === 'novel' ? '小说' : '冒险' }}</span>
          <span class="time">{{ fmtTime(img.timestamp) }}</span>
        </div>
        <div class="cell-actions">
          <button class="mini" @click="openPreview(img.id)">查看</button>
          <button class="mini" @click="download(img)">下载</button>
          <button class="mini" @click="copyPrompt(img)">复制提示词</button>
          <button class="mini danger" @click="removeOne(img.id)">删除</button>
        </div>
      </div>
    </div>

    <!-- 大图查看：手机上点缩略图看原图 -->
    <div v-if="preview" class="viewer" @click.self="previewId = null">
      <div class="viewer-inner">
        <img :src="thumbOf(preview.id)" alt="预览" />
        <div class="viewer-info">
          <div class="vi-row"><span>模型</span><em>{{ preview.model || '-' }}</em></div>
          <div class="vi-row"><span>种子</span><em>{{ preview.seed ?? '-' }}</em></div>
          <div class="vi-row"><span>时间</span><em>{{ fmtTime(preview.timestamp) }}</em></div>
          <div class="vi-row vi-prompt"><span>提示词</span><em>{{ preview.prompt }}</em></div>
        </div>
        <div class="viewer-actions">
          <button class="mini" @click="download(preview!)">下载</button>
          <button class="mini" @click="copyPrompt(preview!)">复制提示词</button>
          <button class="mini danger" @click="removeOne(preview!.id)">删除</button>
          <button class="mini" @click="previewId = null">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.gallery-panel {
  /* 嵌在「资产」页里，外壳已经有 padding 了，这里不再加 */
  max-width: var(--content-max, 1100px);
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.page-head h2 {
  margin: 0;
  font-size: 20px;
}
.head-actions {
  display: flex;
  gap: 8px;
}
button.danger {
  border-color: var(--danger);
  color: var(--danger);
}
.hint {
  font-size: 11.5px;
  color: var(--text-dim);
  line-height: 1.8;
  margin: 0;
}
.msg {
  margin-left: 8px;
  color: var(--accent);
}
.mini {
  font-size: 10.5px;
  padding: 2px 9px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--panel);
  color: var(--text-dim);
  cursor: pointer;
}
.mini:hover {
  border-color: var(--accent);
  color: var(--text);
}
.mini.danger:hover {
  border-color: var(--danger);
  color: var(--danger);
}
.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}
/* 存储维护那一行 */
.storage-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 14px 0;
  font-size: 11px;
  flex-wrap: wrap;
}
.storage-row .spacer {
  flex: 1 1 auto;
}
.storage-row button {
  font-size: 11px;
  padding: 3px 9px;
}

.seg {
  display: flex;
  gap: 6px;
}
.seg button {
  font-size: 11.5px;
  padding: 4px 12px;
}
.seg button.active {
  background: var(--accent);
  color: #0b1220;
  border-color: var(--accent);
  font-weight: 600;
}
.search {
  flex: 1;
  min-width: 160px;
  font-size: 12px;
  padding: 6px 10px;
}
.empty {
  text-align: center;
  color: var(--text-dim);
  padding: 40px 20px;
  font-size: 13px;
}
.hint-sm {
  font-size: 11px;
  margin-top: 6px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
  gap: 12px;
}
.cell {
  display: flex;
  flex-direction: column;
  gap: 6px;
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 8px;
  background: var(--panel);
}
.cell img {
  width: 100%;
  border-radius: 7px;
  background: var(--code-bg);
  display: block;
  cursor: pointer;
}
.cell-meta {
  display: flex;
  justify-content: space-between;
  font-size: 10px;
  color: var(--text-dim);
}
.cell-actions {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}
/* 大图查看 */
.viewer {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.82);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
  padding: 16px;
}
.viewer-inner {
  max-width: 900px;
  width: 100%;
  max-height: 92vh;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
  background: var(--panel);
  border-radius: 12px;
  padding: 12px;
}
.viewer-inner img {
  width: 100%;
  border-radius: 8px;
  background: var(--code-bg);
}
.viewer-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 11px;
}
.vi-row {
  display: flex;
  gap: 8px;
}
.vi-row span {
  color: var(--text-dim);
  flex-shrink: 0;
  min-width: 48px;
}
.vi-row em {
  font-style: normal;
  color: var(--text);
  word-break: break-word;
}
.vi-prompt em {
  font-family: ui-monospace, monospace;
  font-size: 10.5px;
}
.viewer-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

@media (max-width: 768px) {
  .gallery-panel {
    padding: 14px;
  }
  .grid {
    grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  }
}

</style>
