// 滚动容器工具：给下一个接手的说明
// ---------------------------------------------------------------------------
// 这个应用里「谁在滚」是随屏幕宽度变的：
//   · 桌面端：聊天区 .chat-area 自己 overflow-y:auto
//   · 手机端：整页在滚，.chat-area 反而是 overflow:visible
//
// 以前 Adventure.vue 里写死 `getElementById('chat-area').scrollTop = scrollHeight`，
// 手机上那行完全无效（那个元素根本不滚）——表现就是「进会话停在最上面」。
// ScrollFabs 里另写了一份「向上找可滚祖先」，两边逻辑重复、还容易改歪。
// 所以抽到这里，两个调用方共用同一套判定。
//
// 另一个坑：消息气泡带 content-visibility:auto + contain-intrinsic-size，
// 屏幕外的块先按 220px 占位，真实排版后 scrollHeight 还会再变一次。
// 所以「回到底部」必须在滚之前**重新读一次高度**，必要时再补一帧。

export type Scroller = HTMLElement | Window;

/** 这个元素的 CSS 允许纵向滚动吗（不管当前内容有没有超出） */
function overflowScrolls(el: HTMLElement): boolean {
  return /(auto|scroll|overlay)/.test(getComputedStyle(el).overflowY);
}

/** 真的需要显示「回顶/回底」按钮吗：能滚且内容确实超了一屏 */
export function isScrollable(el: HTMLElement): boolean {
  return overflowScrolls(el) && el.scrollHeight > el.clientHeight + 4;
}

/**
 * 从 anchor 向上找第一个「声明了可滚动」的祖先。
 *
 * 注意用 overflowScrolls 而不是 isScrollable：内容还没铺开时
 * scrollHeight 可能等于 clientHeight，此时也不能误判成「整页在滚」，
 * 否则桌面端会突然跑去滚 window。
 */
export function findScroller(anchor: HTMLElement | null | undefined): Scroller {
  let el: HTMLElement | null = anchor ?? null;
  while (el) {
    if (overflowScrolls(el)) return el;
    el = el.parentElement;
  }
  return window;
}

export function scrollerMetrics(s: Scroller): { top: number; height: number; total: number } {
  if (s === window) {
    const doc = document.documentElement;
    return {
      top: window.scrollY || doc.scrollTop || 0,
      height: doc.clientHeight,
      total: doc.scrollHeight,
    };
  }
  // s === window 不是 TS 的类型守卫（window 是值不是类型），需要显式断言
  const el = s as HTMLElement;
  return { top: el.scrollTop, height: el.clientHeight, total: el.scrollHeight };
}

/**
 * 到最底部。
 *
 * behavior 默认 **auto（瞬时）**：用户点「回到底部」要的是立刻看见最新一条，
 * 不是看一段几秒的平滑动画。长会话里 smooth 还会被 content-visibility
 * 的高度重算打断，停在半路（用户反馈的「快速向下不到底」就是它）。
 */
export function scrollToEnd(s: Scroller, behavior: ScrollBehavior = 'auto'): void {
  if (s === window) {
    window.scrollTo({ top: document.documentElement.scrollHeight, behavior });
  } else {
    const el = s as HTMLElement;
    el.scrollTo({ top: el.scrollHeight, behavior });
  }
}

export function scrollToStart(s: Scroller, behavior: ScrollBehavior = 'smooth'): void {
  if (s === window) window.scrollTo({ top: 0, behavior });
  else (s as HTMLElement).scrollTo({ top: 0, behavior });
}
