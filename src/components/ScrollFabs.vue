<script setup lang="ts">
// 快速回到顶部 / 底部
//
// 长对话翻起来很累，尤其是手机上。这两个按钮浮在聊天区右下角。
//
// 关于"滚哪里"：这个应用里滚动的容器**随屏幕宽度变化** ——
// 桌面端滚的是 .chat-area 自己，手机端整页在滚（.chat-area 反而
// overflow:visible）。所以不能写死一个元素，改成挂载后向上找
// 第一个"真的能滚"的祖先，并在窗口尺寸变化时重算。
import { ref, onMounted, onUnmounted } from 'vue';
import {
  findScroller,
  scrollerMetrics,
  scrollToEnd,
  scrollToStart,
  type Scroller,
} from '../lib/scroller';

const props = defineProps<{
  /** 从这里开始向上找可滚动祖先 */
  anchor: HTMLElement | null | undefined;
}>();

const atTop = ref(true);
const atBottom = ref(true);
const visible = ref(false);

let scroller: Scroller | null = null;
let ro: ResizeObserver | null = null;

function update() {
  if (!scroller) return;
  const m = scrollerMetrics(scroller);
  const canScroll = m.total > m.height + 8;
  visible.value = canScroll;
  atTop.value = m.top <= 8;
  atBottom.value = m.top + m.height >= m.total - 24;
}

function toTop() {
  if (scroller) scrollToStart(scroller, 'smooth');
}

function toBottom() {
  /*
   * 点一下就该到底：瞬时滚，不给 smooth 动画。
   *
   * 长会话里平滑滚要好几秒，中途 content-visibility 的真实排版还会把
   * scrollHeight 改掉，结果停在半路 —— 用户反馈的「快速向下不会直接到最下方」。
   * 每次都重新解析一次容器（桌面↔手机布局可能已经变了），
   * 再补一帧兜住高度变化，确保真的贴底。
   */
  const s = findScroller(props.anchor);
  if (s !== scroller) attach(s);
  scrollToEnd(s, 'auto');
  requestAnimationFrame(() => scrollToEnd(findScroller(props.anchor), 'auto'));
}

function detach() {
  if (scroller === window) window.removeEventListener('scroll', update);
  else (scroller as HTMLElement | null)?.removeEventListener('scroll', update);
  scroller = null;
}

function attach(next: Scroller = findScroller(props.anchor)) {
  detach();
  scroller = next;
  if (scroller === window) window.addEventListener('scroll', update, { passive: true });
  else (scroller as HTMLElement).addEventListener('scroll', update, { passive: true });
  update();
}

onMounted(() => {
  attach();
  ro = new ResizeObserver(() => {
    update();
    // 尺寸变化可能让"该滚谁"变了（桌面↔手机布局切换）
    const next = findScroller(props.anchor);
    if (next !== scroller) attach(next);
  });
  if (props.anchor) ro.observe(props.anchor);
});

onUnmounted(() => {
  detach();
  ro?.disconnect();
});

defineExpose({ update });
</script>

<template>
  <div v-if="visible" class="scroll-fabs">
    <button v-if="!atTop" class="fab" @click="toTop" title="回到顶部">↑</button>
    <button v-if="!atBottom" class="fab" @click="toBottom" title="回到底部">↓</button>
  </div>
</template>

<style scoped>
.scroll-fabs {
  position: fixed;
  right: 16px;
  bottom: 96px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  z-index: 40;
}
.fab {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  border: 1px solid var(--border);
  background: var(--panel-2);
  color: var(--text);
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.4);
  opacity: 0.92;
}
.fab:hover {
  border-color: var(--accent);
  color: var(--accent);
  opacity: 1;
}
@media (max-width: 768px) {
  .scroll-fabs {
    right: 10px;
    /* 手机上输入区更高，往上让开 */
    bottom: 150px;
  }
  .fab {
    width: 36px;
    height: 36px;
  }
}
</style>
