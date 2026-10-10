<script setup lang="ts">
// 全屏看图 + 多版本切换 + 保存到相册。
//
// 三个职责（都在这里，因为它们共用同一个"大图预览"场景）：
//   1. 看全图 —— 对话里的插图必须限高，否则一张竖图撑满两屏；
//      点一下进来就能看全。
//   2. **切换版本** —— 「重新生图」是抽卡，新的未必更好。
//      上面一排缩略图可以来回切，选中的那版就是正式显示的那版。
//   3. 保存到手机相册 —— 手机浏览器里 <a download> 基本不生效
//      （尤其安卓 WebView / iOS Safari），所以这里给三条路，按可用性依次尝试。
//
// 关闭方式给了三种：点背景、点 ×、按 Esc。手机上尤其需要点背景就能关。
import { computed, onMounted, onUnmounted, ref } from 'vue';
import type { ImageVersion } from '../engine/imageMarker';

const props = defineProps<{
  src: string;
  description?: string;
  /** 可选的历史版本（最新在前）。传了才显示切换条 */
  versions?: ImageVersion[];
  /** 当前显示的那一版 id */
  currentId?: string;
  /** 每个版本的图片地址（由调用方解析好，key = version.id） */
  resolvedMap?: Record<string, string>;
}>();

const emit = defineEmits<{
  close: [];
  /** 切换到某一版 */
  switchVersion: [versionId: string];
}>();

const hint = ref('');
let hintTimer: ReturnType<typeof setTimeout> | undefined;
function flash(t: string) {
  hint.value = t;
  if (hintTimer) clearTimeout(hintTimer);
  hintTimer = setTimeout(() => (hint.value = ''), 3200);
}

/** 有多个版本才显示切换条 */
const hasVersions = computed(() => (props.versions?.length || 0) > 1);

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') emit('close');
}

onMounted(() => {
  window.addEventListener('keydown', onKey);
  // 看图时锁住背后滚动，避免手指滑动时页面跟着跑
  document.body.style.overflow = 'hidden';
});

onUnmounted(() => {
  window.removeEventListener('keydown', onKey);
  document.body.style.overflow = '';
});

/** 版本缩略图该用哪个地址 */
function thumbSrc(v: ImageVersion): string {
  return props.resolvedMap?.[v.id] || v.dataUrl || v.remoteUrl || '';
}

/**
 * 保存到设备。
 *
 * ⚠️ 手机端的现实：
 *   `<a download>` 在桌面浏览器有效，但在**手机浏览器和 App 内嵌 WebView 里
 *   基本被忽略** —— 要么什么都不做，要么在新标签打开图片。用户反馈过
 *   "手机端下载不正常"就是这个原因。
 *
 * 所以按可用性依次尝试四条路：
 *   0. **原生桥接**（APK 内）→ 写进系统相册，最可靠
 *   1. 系统分享面板（navigator.share + files）→ 用户可选"存到相册"
 *      这是 iOS/Android 浏览器上**唯一可靠**的保存方式
 *   2. 新标签打开图片 → 用户长按选"保存图片"（安卓常见做法）
 *   3. `<a download>` 兜底（桌面浏览器走这条最好）
 */
async function saveImage() {
  const url = props.src;
  if (!url) {
    flash('这张图还没加载出来，稍等一下再试');
    return;
  }

  const fileName = `${(props.description || '图片').slice(0, 40)}.png`;

  // ---- 方案 0：APK 原生桥接（写进系统相册）----
  const native = (window as unknown as { dhpSaveImage?: (d: string, n: string) => Promise<{ ok: boolean; where?: string; error?: string }> }).dhpSaveImage;
  if (typeof native === 'function') {
    try {
      const r = await native(url, fileName);
      if (r.ok) {
        flash(`已保存到${r.where || '相册'} ✓`);
        return;
      }
      // 原生失败就继续往下试，不直接报错
      console.warn('[保存] 原生保存失败：', r.error);
    } catch (e) {
      console.warn('[保存] 原生桥接异常：', e);
    }
  }

  // ---- 方案 1：系统分享面板（手机浏览器首选）----
  try {
    // 必须先把 dataURL 变成 File 才能交给分享面板
    const blob = await (await fetch(url)).blob();
    const file = new File([blob], fileName, { type: blob.type || 'image/png' });
    const nav = navigator as Navigator & {
      canShare?: (d: { files?: File[] }) => boolean;
    };
    if (typeof nav.share === 'function' && nav.canShare?.({ files: [file] })) {
      await nav.share({ files: [file], title: props.description || '插图' });
      flash('已打开分享面板，选「存储图像」即可保存到相册');
      return;
    }
  } catch (e) {
    // 用户主动取消分享面板也会走到这里（AbortError），不算失败，直接返回
    if (e instanceof Error && e.name === 'AbortError') return;
    /* 其余错误继续往下试 */
  }

  // ---- 方案 2：新标签打开，长按保存（安卓常用）----
  const isMobile = /Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent);
  if (isMobile) {
    try {
      const w = window.open('', '_blank');
      if (w) {
        w.document.write(
          `<title>保存图片</title>` +
            `<body style="margin:0;background:#111;display:flex;align-items:center;justify-content:center;min-height:100vh">` +
            `<img src="${url}" style="max-width:100%;max-height:100vh" />` +
            `<div style="position:fixed;bottom:16px;left:0;right:0;text-align:center;color:#bbb;font:13px -apple-system,sans-serif">` +
            `长按图片 → 选择「保存图片 / 存储图像」</div></body>`,
        );
        w.document.close();
        flash('已在新页面打开，长按图片即可保存到相册');
        return;
      }
    } catch {
      /* 弹窗被拦，继续兜底 */
    }
  }

  // ---- 方案 3：<a download>（桌面浏览器最好用）----
  try {
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    flash('已开始下载');
  } catch {
    flash('这个环境不支持直接保存，可尝试长按图片');
  }
}
</script>

<template>
  <!--
    Teleport 到 body：**必须的**，不是可选优化。

    消息气泡（.message-group）上有 `content-visibility: auto`（长对话的性能优化，
    让屏幕外的消息跳过渲染）。但按 CSS 规范，content-visibility 非 visible 的
    元素会成为其 `position: fixed` 后代的**包含块** —— 于是这个本该铺满全屏的
    浮层被关进气泡里：实测它的盒子 = 气泡的盒子（16,252,361,645），
    视口是 393x852。结果就是"点开图只看到一个半透明黑窗，图还挤扁了"。

    Teleport 把它送回 body，所有祖先的包含块规则都不再适用。
    （scoped 样式照样生效 —— scope id 是加在元素自己身上的。）
  -->
  <Teleport to="body">
    <div class="img-viewer" @click.self="emit('close')">
    <div class="iv-top">
      <span class="iv-desc truncate">{{ description || '' }}</span>
      <div class="iv-btns">
        <button class="iv-btn" @click="saveImage" title="保存到设备 / 相册">保存</button>
        <button class="iv-btn" @click="emit('close')" title="关闭（Esc）">✕</button>
      </div>
    </div>

    <!--
      版本切换条：重新生图后可以在这里来回挑。
      缩略图横排、当前那版高亮 —— 一眼能看出"现在看的是第几张"。
    -->
    <div v-if="hasVersions" class="iv-versions">
      <span class="iv-vlabel">版本</span>
      <button
        v-for="(v, i) in versions"
        :key="v.id"
        class="iv-thumb"
        :class="{ active: v.id === currentId }"
        :title="`第 ${versions!.length - i} 版`"
        @click.stop="emit('switchVersion', v.id)"
      >
        <img v-if="thumbSrc(v)" :src="thumbSrc(v)" alt="" />
        <span v-else class="iv-thumb-empty">…</span>
      </button>
      <span class="iv-vhint">
        {{ (versions || []).findIndex((v) => v.id === currentId) === 0 ? '最新' : '历史' }}
      </span>
    </div>

    <img :src="src" :alt="description || ''" class="iv-img" @click.stop />

    <p v-if="hint" class="iv-flash">{{ hint }}</p>
    <p v-else class="iv-hint">
      点背景或按 Esc 关闭<template v-if="hasVersions"> · 上方可切换重新生成前的旧图</template>
    </p>
    </div>
  </Teleport>
</template>

<style scoped>
.img-viewer {
  position: fixed;
  inset: 0;
  z-index: 3000;
  background: rgba(0, 0, 0, 0.9);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  /* 上边让开顶部栏（含刘海），下边让开系统手势条 —— 否则提示会压在下面 */
  padding: 16px;
  padding-top: calc(54px + env(safe-area-inset-top, 0px));
  padding-bottom: calc(16px + env(safe-area-inset-bottom, 0px));
  gap: 10px;
}
.iv-top {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: calc(10px + env(safe-area-inset-top, 0px)) 14px 10px;
  background: linear-gradient(to bottom, rgba(0, 0, 0, 0.68), transparent);
  z-index: 2;
}
.iv-desc {
  flex: 1;
  min-width: 0;
  font-size: 11.5px;
  color: rgba(255, 255, 255, 0.75);
}
.iv-btns {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}
.iv-btn {
  font-size: 11.5px;
  padding: 6px 14px;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.3);
  background: rgba(0, 0, 0, 0.45);
  color: #fff;
  cursor: pointer;
}
.iv-btn:hover {
  background: rgba(255, 255, 255, 0.16);
}

/* ---- 版本切换条 ---- */
.iv-versions {
  position: absolute;
  top: calc(52px + env(safe-area-inset-top, 0px));
  left: 0;
  right: 0;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  background: linear-gradient(to bottom, rgba(0, 0, 0, 0.6), transparent);
  overflow-x: auto;
}
.iv-vlabel {
  flex-shrink: 0;
  font-size: 10.5px;
  color: rgba(255, 255, 255, 0.5);
}
.iv-thumb {
  flex-shrink: 0;
  width: 46px;
  height: 46px;
  padding: 0;
  border-radius: 7px;
  overflow: hidden;
  border: 2px solid rgba(255, 255, 255, 0.22);
  background: rgba(255, 255, 255, 0.06);
  cursor: pointer;
  display: grid;
  place-items: center;
}
.iv-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.iv-thumb.active {
  border-color: #6ea8fe;
  box-shadow: 0 0 0 2px rgba(110, 168, 254, 0.4);
}
.iv-thumb-empty {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.4);
}
.iv-vhint {
  flex-shrink: 0;
  font-size: 10.5px;
  color: rgba(255, 255, 255, 0.5);
  margin-left: 2px;
}

.iv-img {
  /*
   * 高度交给 flex 分配（`flex: 1 1 auto` + `min-height: 0`），
   * 不要再写死 `max-height: calc(100vh - 130px)`。
   *
   * 写死的问题是：图片按视口算高，再加上下两行文字和 padding，
   * 总高度会超出容器 —— flex 是居中排布的，超出就往上往下各溢出一半，
   * 底部提示会**压在输入框上面**（手机上尤其明显）。
   * 让 flex 自己分配就永远装得下，`object-fit: contain` 负责保持比例。
   */
  flex: 1 1 auto;
  min-height: 0;
  max-width: 100%;
  object-fit: contain;
  border-radius: 8px;
  /* 点图本身不要关掉，方便长按保存 */
  cursor: default;
}
/* 底部提示：留出安全区，别贴到系统手势条上 */
.iv-hint {
  margin: 0;
  font-size: 10.5px;
  color: rgba(255, 255, 255, 0.4);
  text-align: center;
  padding: 0 20px;
  flex-shrink: 0;
}
/* 操作反馈（保存提示等） */
.iv-flash {
  margin: 0;
  padding: 7px 16px;
  border-radius: 999px;
  background: rgba(110, 168, 254, 0.2);
  border: 1px solid rgba(110, 168, 254, 0.45);
  color: #cfe1ff;
  font-size: 11.5px;
  text-align: center;
  max-width: 88vw;
}
.truncate {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
