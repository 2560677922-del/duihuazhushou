<script setup lang="ts">
// 正文渲染器：把 [[IMG: ...]] 标记渲染成图片块。
//
// 与 MarkdownRenderer 的区别：这里会识别配图标记，在标记位置插入图片，
// 标记本身不显示。文字部分仍走 markdown 渲染，格式与原来完全一致。
//
// 注意：这个组件只负责「显示」。送给大模型的文本由 assemblePrompt 统一剥掉标记，
// 因此这里显示的图片不会进入上下文。
import { computed, ref, watch, onUnmounted } from 'vue';
import MarkdownRenderer from './MarkdownRenderer.vue';
import ImageViewer from './ImageViewer.vue';
import { buildContentBlocks, allVersionsOf, type ImageAttachment } from '../engine/imageMarker';
import { imageBlobs } from '../lib/imageBlobDb';
import { cachedImage, rememberImage } from '../lib/lazyImage';
import { assetUrl, assetUrlSync } from '../lib/imageAssets';
import { fetchGithubImageAsDataUrl } from '../engine/githubStorage';
import { useStorageSettingsStore } from '../stores/storageSettings';

const storageSettings = useStorageSettingsStore();

const props = defineProps<{
  content: string;
  attachments?: ImageAttachment[];
  /** 允许用户删除某张配图 */
  removable?: boolean;
  /** 允许点图重新生成 */
  regenerable?: boolean;
}>();

const emit = defineEmits<{
  removeImage: [id: string];
  /**
   * 请求重新生成这张图。
   *
   * 把**附件本身**传出去，而不是只传描述文字 —— 调用方要靠它的 id 和
   * markdownIndex 原地替换那张图（新增一张会让正文里多出一个标记）。
   */
  regenerate: [attachment?: ImageAttachment];
  /** 用户在大图里切到了另一版（attachmentId, versionId） */
  switchVersion: [attachmentId: string, versionId: string];
}>();

const blocks = computed(() => buildContentBlocks(props.content || '', props.attachments || []));

/**
 * 图片地址解析。
 *
 * 一条附件可能有三种"地址"，按可靠性排序依次尝试：
 *   1. 本地 dataURL  —— 最快最稳，直接给 <img>
 *   2. 本地图片存储区的引用（blob:xxx）—— 需要查 IndexedDB 才知道有没有
 *   3. 远端链接 / 仓库路径 —— 前两个都不行时，去 GitHub 拉回来
 *
 * 这样任何一条来源坏掉都能自动落到下一条，
 * 不会再出现"图明明存在却只显示占位符"的情况。
 */
const resolved = ref<Record<string, string>>({});
const loading = ref<Record<string, boolean>>({});
/** 已经彻底失败的图：记下来，避免反复重试刷屏 */
const failed = ref<Record<string, boolean>>({});

/** 判重：这个引用是不是本地图片存储区的键 */
function blobKeyOf(a: ImageAttachment): string | null {
  const u = a.dataUrl || '';
  if (u.startsWith('blob:')) return u.slice(5);
  if (u.startsWith('__PENDING__')) return u.slice(11);
  return null;
}

/** 从本地图片存储区取 */
async function fromLocalBlob(a: ImageAttachment): Promise<string | null> {
  const key = blobKeyOf(a);
  if (!key) return null;
  /* 先看 LRU 缓存：来回滚动时同一张图会被反复问到 */
  const hit = cachedImage(key);
  if (hit) return hit;
  try {
    const data = await imageBlobs.get(key);
    if (data && String(data).startsWith('data:')) {
      rememberImage(key, data);
      return data;
    }
    return null;
  } catch {
    return null;
  }
}

/** 从 GitHub 仓库取（需要 remotePath + 有效配置） */
async function fromGithub(a: ImageAttachment): Promise<string | null> {
  if (!a.remotePath) return null;
  const gh = storageSettings.activeGithub();
  if (!gh) return null;
  try {
    const dataUrl = await fetchGithubImageAsDataUrl(gh, a.remotePath);
    return dataUrl && dataUrl.startsWith('data:') ? dataUrl : null;
  } catch {
    return null;
  }
}

/**
 * 解析一个附件。
 *
 * 分两步走，关键在 `allowRemote`：
 *   - 本地（现成 dataURL / IndexedDB 里的 blob）**随时可查**，便宜、不联网
 *   - GitHub 拉取**只在这个附件滚进视口时才做**
 *
 * 为什么这么设计：以前是挂载时把**所有**附件一起解析，屏幕外的也去 GitHub
 * 拉一遍。一屏几十张图的长会话，进页面就会同时发几十个请求 ——
 * 手机上又慢又费流量，而大部分图用户根本没看到。
 */
async function resolveOne(a: ImageAttachment, allowRemote: boolean) {
  if (!a) return;
  if (resolved.value[a.id] !== undefined) return;
  if (loading.value[a.id]) return;

  loading.value = { ...loading.value, [a.id]: true };
  try {
    /*
     * 0) 图片资产库（新版流程）。
     *    会话里只存 assetId，图片本体在资产库里 ——
     *    所以对话数据不含图片，聊天页再也不会因为图多而变慢。
     */
    if (a.assetId) {
      const fromAsset = await assetUrl(a.assetId);
      if (fromAsset) {
        resolved.value = { ...resolved.value, [a.id]: fromAsset };
        return;
      }
    }
    /* 老数据没有 assetId：内存里的可能已经是完整 dataURL */
    if (a.dataUrl?.startsWith('data:')) {
      resolved.value = { ...resolved.value, [a.id]: a.dataUrl };
      return;
    }
    // 1) 本地图片存储区
    const local = await fromLocalBlob(a);
    if (local) {
      resolved.value = { ...resolved.value, [a.id]: local };
      return;
    }
    // 还没进视口就先不联网，等它滚进来再说
    if (!allowRemote) return;
    // 2) 远端仓库
    const remote = await fromGithub(a);
    if (remote) {
      resolved.value = { ...resolved.value, [a.id]: remote };
      return;
    }
    // 3) 实在没有：dataUrl 本身是个可用链接就交给 <img>
    const u = a.dataUrl || '';
    if (u && !u.startsWith('blob:') && !u.startsWith('__PENDING__')) {
      resolved.value = { ...resolved.value, [a.id]: u };
    } else {
      failed.value = { ...failed.value, [a.id]: true };
    }
  } finally {
    loading.value = { ...loading.value, [a.id]: false };
  }
}

/** id → 附件，供观察器回调查找 */
const pendingById = computed(() => {
  const m = new Map<string, ImageAttachment>();
  for (const a of props.attachments || []) if (a?.id) m.set(a.id, a);
  return m;
});

/**
 * 懒加载观察器：附件滚到视口附近才去取图。
 *
 * rootMargin 给 300px —— 提前一点开始取，滚到跟前时基本已经好了。
 * 等它真正露头再取会有明显白块。
 */
const observer =
  typeof IntersectionObserver !== 'undefined'
    ? new IntersectionObserver(
        (entries) => {
          for (const e of entries) {
            if (!e.isIntersecting) continue;
            const id = (e.target as HTMLElement).dataset.attId;
            const a = id ? pendingById.value.get(id) : undefined;
            if (a) void resolveOne(a, true);
            observer?.unobserve(e.target);
          }
        },
        { rootMargin: '300px 0px' },
      )
    : null;

/**
 * 挂在图片容器上的指令：登记附件 id，
 * 最新几张立刻解析，其余等滚到视口。
 */
const vLazyImg = {
  mounted(el: HTMLElement, binding: { value?: ImageAttachment }) {
    const a = binding.value;
    if (!a) return;
    el.dataset.attId = a.id;
    /* 现成的 dataURL 不用观察（内存里刚生成的） */
    if (a.dataUrl?.startsWith('data:')) return;
    /*
     * 先立刻把**本地**那张取出来，不联网。
     *
     * 为什么不能只靠观察器：聊天区是内部滚动容器，观察器在这个布局里
     * 不可靠（实测滚了 12 屏一张都没触发）。少了这一步，图片会一直
     * 卡在"图片不可用"。宁可多读一点本地库，也不能让图显示不出来 ——
     * 真正的大头（base64 常驻内存）已经在存储层解决了：
     * 这里取的只是一张，不是全部。
     */
    void resolveOne(a, false);
    /* 远端的那一份仍然交给观察器，等进视口再联网 */
    observer?.observe(el);
  },
  unmounted(el: HTMLElement) {
    observer?.unobserve(el);
  },
};

onUnmounted(() => observer?.disconnect());

/*
 * 附件变化时（新生成了一张图）**不**批量解析。
 *
 * 新图带的是内存里的 dataURL，渲染时 srcOf 能直接用；
 * 老图是 blob: 引用，等用户滚到它时观察器自然会去取。
 */
watch(
  () => (props.attachments || []).map((a) => `${a.id}:${a.dataUrl || ''}`).join('|'),
  () => {
    /* 新出现的图如果在视口内，观察器会补上；不在视口就等滚动再说 */
  },
);

/** 图片实际使用的地址 */
function srcOf(a?: ImageAttachment): string {
  if (!a) return '';
  // 解析结果优先（可能来自本地存储区或远端）
  const r = resolved.value[a.id];
  if (r) return r;
  if (a.dataUrl?.startsWith('data:')) return a.dataUrl;
  // 资产库里已经取过图的话，直接给（不用等异步）
  if (a.assetId) {
    const hit = assetUrlSync(a.assetId);
    if (hit) return hit;
  }
  // blob / __PENDING__ 是内部引用，不能直接给 <img>
  return '';
}

/** 点击放大查看的图片（null 表示没在看） */
const viewerSrc = ref('');
const viewerDesc = ref('');
/** 正在查看的那张附件（切换版本要用它的 id 和 versions） */
const viewerAtt = ref<ImageAttachment | null>(null);
/** 历史版本的地址缓存（key = version.id），缩略图和切换都要用 */
const versionSrcs = ref<Record<string, string>>({});

function openViewer(a?: ImageAttachment) {
  const src = srcOf(a);
  if (!src) return;
  viewerSrc.value = src;
  viewerDesc.value = a?.description || '';
  viewerAtt.value = a || null;
  void resolveVersions(a);
}

/**
 * 解析历史版本的图片地址。
 *
 * 顶层那张走的是现有的 srcOf（含懒加载 + GitHub 兜底）；
 * 历史版本只在这里按需解析 —— 它们平时不显示，没必要在渲染阶段就解析，
 * 否则一条消息里 5 个版本 × N 张图会让解析压力翻几倍。
 */
async function resolveVersions(a?: ImageAttachment) {
  if (!a?.versions?.length) return;
  const out: Record<string, string> = {};
  // 当前版直接用已解析好的地址
  const cur = srcOf(a);
  if (cur && a.currentVersionId) out[a.currentVersionId] = cur;

  for (const v of a.versions) {
    if (out[v.id]) continue;
    // 1) 版本自带 dataURL（内存里刚生成的）
    if (v.dataUrl) {
      out[v.id] = v.dataUrl;
      continue;
    }
    // 2) 本地图片库（按版本 id 存过的话）
    try {
      const d = await imageBlobs.get(v.id);
      if (d) {
        out[v.id] = d;
        continue;
      }
    } catch {
      /* 继续 */
    }
    // 3) 远端链接兜底
    if (v.remoteUrl) out[v.id] = v.remoteUrl;
  }
  versionSrcs.value = { ...versionSrcs.value, ...out };
}

/** 版本切换：交给上层改数据，本组件只负责把事件传出去 */
function onSwitchVersion(versionId: string) {
  const a = viewerAtt.value;
  if (!a) return;
  emit('switchVersion', a.id, versionId);
  // 立刻把大图换成选中那版（不等上层回写完成，手感更跟手）
  const next = versionSrcs.value[versionId];
  if (next) viewerSrc.value = next;
}

/**
 * 外部把数据改好了 → 同步正在看的那张大图。
 *
 * 上层（Adventure / NovelEditor）改完 attachment 后会传新的 attachments 进来，
 * 这里跟着更新，避免"切了版本但大图没变"。
 */
watch(
  () => props.attachments,
  (list) => {
    const a = viewerAtt.value;
    if (!a || !list) return;
    const fresh = list.find((x) => x.id === a.id);
    if (!fresh) return;
    viewerAtt.value = fresh;
    const src = srcOf(fresh);
    if (src) viewerSrc.value = src;
    void resolveVersions(fresh);
  },
  { deep: true },
);

/**
 * <img> 加载失败时的最后一搏：去 GitHub 取。
 *
 * 走到这里说明解析阶段拿到的地址（可能是远端 raw 链接）
 * 在浏览器里加载不出来 —— 比如 raw.githubusercontent.com
 * 在部分网络下解析不了。这时用 API 带令牌取回 base64。
 */
async function onImgError(a?: ImageAttachment) {
  if (!a || failed.value[a.id] || !a.remotePath) return;
  failed.value = { ...failed.value, [a.id]: true };
  loading.value = { ...loading.value, [a.id]: true };
  try {
    const dataUrl = await fromGithub(a);
    if (dataUrl) {
      resolved.value = { ...resolved.value, [a.id]: dataUrl };
      failed.value = { ...failed.value, [a.id]: false };
    }
  } finally {
    loading.value = { ...loading.value, [a.id]: false };
  }
}
</script>

<template>
  <div class="content-with-images">
    <template v-for="(b, i) in blocks" :key="i">
      <MarkdownRenderer v-if="b.kind === 'text'" :content="b.text" />
      <figure v-else class="inline-image">
        <!--
          v-lazy-img 负责：本地先找一遍；找不到时等这张图滚进视口才去 GitHub 取。
          （见脚本里的 resolveOne / observer）
        -->
        <div v-lazy-img="b.attachment" class="img-wrap"
          :class="{ clickable: regenerable && srcOf(b.attachment) }">
          <img v-if="srcOf(b.attachment)" :src="srcOf(b.attachment)" :alt="b.description" loading="lazy"
            decoding="async"
            :title="regenerable ? '点一下放大看全图' : '点一下放大看全图'"
            @click="openViewer(b.attachment)"
            @error="onImgError(b.attachment)" />
          <!--
            点一下直接重新生图（不弹配置面板）。
            拿同样的画面描述再出一张，原地替换这一张 —— 不满意可以再点。
          -->
          <button v-if="regenerable && srcOf(b.attachment)" class="regen-overlay"
            title="用同样的描述再出一张，直接替换这张"
            @click="emit('regenerate', b.attachment)">
            🔄 重新生图
          </button>
        </div>
        <div v-if="!srcOf(b.attachment) && b.attachment && loading[b.attachment.id]" class="img-fallback">
          🖼️ 正在从仓库加载图片…
        </div>
        <div v-else-if="!srcOf(b.attachment) && b.attachment?.error" class="img-fallback error">
          ⚠ 配图生成失败：{{ b.attachment.error }}
        </div>
        <div v-else-if="!srcOf(b.attachment) && b.attachment?.uploadError" class="img-fallback error">
          ⚠ 配图上传失败：{{ b.attachment.uploadError }}
        </div>
        <div v-else-if="!srcOf(b.attachment)" class="img-fallback">🖼️ 配图位置（图片不可用）</div>
        <figcaption>
          <span class="desc">
            {{ b.description }}
            <span v-if="b.attachment?.storage === 'github'" class="badge">已存入仓库</span>
            <span v-else-if="b.attachment?.uploadError" class="badge warn">仅存本地</span>
          </span>
          <button
            v-if="removable && b.attachment"
            class="mini-btn"
            @click="emit('removeImage', b.attachment.id)"
          >移除</button>
        </figcaption>
      </figure>
    </template>

    <!-- 点图放大看全图（多版本时可在这里切换 + 保存到相册） -->
    <ImageViewer
      v-if="viewerSrc"
      :src="viewerSrc"
      :description="viewerDesc"
      :versions="viewerAtt ? allVersionsOf(viewerAtt) : []"
      :current-id="viewerAtt?.currentVersionId || viewerAtt?.id"
      :resolved-map="versionSrcs"
      @switch-version="onSwitchVersion"
      @close="viewerSrc = ''; viewerAtt = null"
    />
  </div>
</template>

<style scoped>
.content-with-images {
  display: flex;
  flex-direction: column;
}
.inline-image {
  margin: 10px 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.inline-image img {
  width: 100%;
  /*
   * 限高很重要：生成图常见 832×1216 这种竖图，
   * 不限高的话一张图就占满两个屏幕，正文全被挤走。
   * 46vh 大约半屏，正文和配图能同时看到；想看全图点一下放大。
   */
  max-height: 46vh;
  object-fit: contain;
  border-radius: 10px;
  border: 1px solid var(--border);
  background: var(--code-bg);
  display: block;
  cursor: zoom-in;
}
/* 手机上屏幕更小，再收紧一点，别让图把输入框顶出屏幕 */
@media (max-width: 700px) {
  .inline-image img {
    max-height: 34vh;
  }
}
/* ---- 点图重新生成 ---- */
.img-wrap {
  position: relative;
  display: block;
  /*
   * 懒加载必须先有高度，否则整套机制会失效。
   *
   * 实测踩过的坑：图片没加载出来时 <img> 是 0 高的，于是 40 张图全挤在
   * 文档最上面那几百像素里 —— 观察器一看"全都在视口带内"，
   * 一次性把 40 张全解析了。懒加载等于没做。
   *
   * 这里按生图常用比例 832:1216 预留一块高度（46vh 上限）。
   * 顺带的好处：图片出现时不会把正文往下顶（没有布局抖动）。
   */
  min-height: 30vh;
  background: var(--surface-1, var(--panel-2));
  border-radius: 8px;
}
.img-wrap img {
  /* 位置固定到左上，图片没加载出来时露出的是下面那层占位底色 */
  display: block;
}
.img-wrap.clickable img {
  cursor: pointer;
}
.regen-overlay {
  position: absolute;
  right: 8px;
  bottom: 8px;
  font-size: 11px;
  padding: 5px 12px;
  border-radius: 999px;
  border: 1px solid var(--accent);
  background: rgba(10, 12, 18, 0.82);
  color: var(--accent);
  cursor: pointer;
  /*
   * 默认**半透明可见**，不是完全隐藏。
   *
   * 这里踩过一个大坑（用户反复反馈"点图会弹配置页、生的图还不对"）：
   *   原先写的是 opacity: 0，只有 hover 或 @media (hover: none) 才显示。
   *   但**安卓 WebView 经常不报告 hover: none**（它认为自己能 hover），
   *   于是按钮一直是透明的，**却仍然可点**。
   *   用户在图片上随手一点 → 其实点到了这个看不见的按钮 → 走了重新生图。
   *   而点在别处又可能命中「🎨 生图」→ 弹出配置页。
   *   两个症状同一个根因：按钮看不见但吃点击。
   *
   * 现在默认 0.55 隐约可见（知道这儿有个按钮），hover 时变实。
   * 触屏上没有 hover 也无所谓 —— 本来就看得见。
   */
  opacity: 0.55;
  transition: opacity 0.15s;
  backdrop-filter: blur(3px);
}
.img-wrap.clickable:hover .regen-overlay,
.regen-overlay:focus,
.regen-overlay:active {
  opacity: 1;
}
/* 真正没有 hover 能力的设备（绝大多数触屏）：直接常显 */
@media (hover: none) {
  .regen-overlay {
    opacity: 0.85;
  }
}
.img-fallback {
  padding: 18px;
  text-align: center;
  font-size: 12px;
  color: var(--text-dim);
  border: 1px dashed var(--border);
  border-radius: 10px;
  background: var(--panel-2);
}
.img-fallback.error {
  color: var(--danger);
  border-color: rgba(248, 113, 113, 0.4);
}
figcaption {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 10px;
  color: var(--text-dim);
}
figcaption .desc {
  line-height: 1.5;
  word-break: break-word;
}
.badge {
  display: inline-block;
  margin-left: 6px;
  padding: 0 6px;
  border-radius: 999px;
  font-size: 9px;
  background: var(--code-bg);
  border: 1px solid var(--border);
  color: var(--text-dim);
}
.badge.warn {
  color: #facc15;
  border-color: rgba(250, 204, 21, 0.4);
}
.mini-btn {
  flex: 0 0 auto;
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--panel);
  border: 1px solid var(--border);
  color: var(--text-dim);
  cursor: pointer;
}
.mini-btn:hover {
  color: var(--danger);
  border-color: var(--danger);
}
</style>
