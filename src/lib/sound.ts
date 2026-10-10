// 点击音效 —— 用 WebAudio **现场合成**，不打包任何音频文件
// ---------------------------------------------------------------------------
// 为什么不用 mp3/wav：
//   · 每加一个音效就是几十 KB，一个包五个就上百 KB
//   · 合成音色只有几行代码，改一个参数就变
//   · 永远不会有"文件丢了/格式不对"的问题
//
// 音色设计（都走短促的正弦/三角波，带一点包络，听着干净不刺耳）：
//   tap   —— 轻点：短促小音，像手指敲在木头上
//   send  —— 发送：上行两个音，有"出去了"的感觉
//   nav   —— 切页：低一点的中音，稳重不抢戏
//
// 音量都压在 0.05~0.12 —— 音效的作用是"确认点到了"，
// 不是让人听见。太吵反而烦，而且连续点时会叠在一起很难受，
// 所以这里加了 40ms 的节流。

import { ref } from 'vue';

export type SoundName = 'tap' | 'send' | 'nav';

const enabled = ref(true);
const volume = ref(0.6);

let ctx: AudioContext | null = null;
let lastAt = 0;

function ac(): AudioContext | null {
  if (typeof window === 'undefined') return null;
  const AC = (window as any).AudioContext || (window as any).webkitAudioContext;
  if (!AC) return null;
  if (!ctx) {
    try {
      ctx = new AC();
    } catch {
      return null;
    }
  }
  /* 安卓/Chrome：页面还没交互过时会挂起，解锁一下 */
  if (ctx && ctx.state === 'suspended') void ctx.resume();
  return ctx;
}

/** 单个音符 */
function blip(freq: number, at: number, dur: number, gain: number, type: OscillatorType = 'sine') {
  const c = ac();
  if (!c) return;
  const osc = c.createOscillator();
  const g = c.createGain();
  osc.type = type;
  osc.frequency.setValueAtTime(freq, at);
  /* 包络：快起、指数衰减 —— 去掉突然的"啪"声 */
  g.gain.setValueAtTime(0, at);
  g.gain.linearRampToValueAtTime(gain, at + 0.006);
  g.gain.exponentialRampToValueAtTime(0.0001, at + dur);
  osc.connect(g).connect(c.destination);
  osc.start(at);
  osc.stop(at + dur + 0.02);
}

/** 播放一个音效名 */
export function playSound(name: SoundName) {
  if (!enabled.value) return;
  const c = ac();
  if (!c) return;

  /* 节流：连续点击时不叠成一串噪音 */
  const now = c.currentTime;
  if (now - lastAt < 0.04) return;
  lastAt = now;

  const v = volume.value;
  switch (name) {
    case 'tap':
      blip(1180, now, 0.055, 0.045 * v, 'sine');
      break;
    case 'send':
      /* 上行两音：轻微上扬 = "发出去了" */
      blip(660, now, 0.07, 0.05 * v, 'triangle');
      blip(990, now + 0.055, 0.09, 0.045 * v, 'triangle');
      break;
    case 'nav':
      blip(520, now, 0.07, 0.04 * v, 'sine');
      break;
  }
}

/** 用户第一次点屏幕时解锁音频（浏览器自动播放策略要求） */
export function unlockAudio() {
  const c = ac();
  if (c && c.state === 'suspended') void c.resume();
}

export const soundPrefs = {
  get enabled() {
    return enabled.value;
  },
  set enabled(v: boolean) {
    enabled.value = v;
  },
  get volume() {
    return volume.value;
  },
  set volume(v: number) {
    volume.value = Math.max(0, Math.min(1, v));
  },
};

/**
 * 全局挂一个点击代理。
 *
 * 为什么不每处都写 @click="playSound()"：
 *   · 界面上几百个按钮，逐个加太脏、也容易漏
 *   · 代理监听一次就够，以后新加的按钮自动有音效
 * 判定"是不是按钮"用 element.closest('button, a, [role=button]')，
 * 兼容以后可能加的自定义控件。
 */
let bound = false;
export function installSoundFeedback() {
  if (bound || typeof document === 'undefined') return;
  bound = true;

  document.addEventListener(
    'pointerdown',
    (e) => {
      unlockAudio();
      const t = e.target as HTMLElement | null;
      if (!t || !t.closest) return;
      /* 开关类控件不响（自己会播），避免双重 */
      if (t.closest('[data-nosound]')) return;
      if (!t.closest('button, a, [role="button"], .chip, .tag-cell')) return;
      /* 输入框相关不响 */
      if (t.closest('input, textarea, select')) return;
      playSound('tap');
    },
    { passive: true, capture: true },
  );
}