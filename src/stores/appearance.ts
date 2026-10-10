import { defineStore } from 'pinia';
import { ref, watch } from 'vue';
import { store } from '../lib/storage';

// 界面外观设置：背景图、主色、面板不透明度。
//
// 实现方式：把背景写成 CSS 变量，由 main.css 里的 body 规则消费。
// 这样切换背景不需要重新渲染任何组件，也不会闪一下。

export interface BackgroundPreset {
  id: string;
  name: string;
  /** CSS 背景值（渐变/纯色）。图片背景时这里是兜底色 */
  css: string;
  /** 缩略图预览用的 CSS */
  preview: string;
  /**
   * 内置插画的地址（相对路径）。
   *
   * 有这个值时走图片背景：url() + cover + 一层压暗遮罩，
   * 这样正文压在图上依然清楚。来源见「来源与授权.md」。
   */
  image?: string;
  /** 图片背景的压暗程度（0~0.8），越大越暗 */
  dim?: number;
}

/**
 * 内置背景。这里刻意全部用 CSS 渐变实现：
 * 不占体积、不掉帧、任何分辨率都不糊，也不用担心版权。
 * 想要照片质感就用下面的「自定义图片」。
 */
export const BACKGROUND_PRESETS: BackgroundPreset[] = [
  /*
   * ★ 2026-10 重做：原来 10 套（默认深色/午夜蓝/极光/暗紫/墨绿/暖褐/网格/暖纸…）
   * 看着就像"装饰品"—— 名字一堆、每套差别细微，用户挑不出来、
   * 也说不出区别。精简成 5 套**能一眼分辨**的：
   *   樱粉 / 纯白 / 乳黄 / 灰黑 / 深紫
   *
   * 全部用 CSS 渐变实现，不占体积、任何分辨率都不糊、没有版权问题。
   */

  {
    id: 'paper',
    name: '暖纸',
    css: 'linear-gradient(168deg, #fbf7f2 0%, #f3ece2 60%, #ece2d5 100%)',
    preview: 'linear-gradient(160deg, #fbf7f2, #ece2d5)',
  },
  {
    id: 'white',
    name: '纯白',
    css: 'linear-gradient(170deg, #ffffff 0%, #f6f8fb 55%, #eef1f6 100%)',
    preview: 'linear-gradient(160deg, #ffffff, #eef1f6)',
  },
  {
    id: 'cream',
    name: '乳黄',
    css: 'linear-gradient(165deg, #fdf6e3 0%, #f7ead0 55%, #efdfc0 100%)',
    preview: 'linear-gradient(160deg, #fdf6e3, #efdfc0)',
  },
  {
    id: 'charcoal',
    name: '灰黑',
    css: 'linear-gradient(168deg, #1b1d22 0%, #14161a 55%, #0d0f12 100%)',
    preview: 'linear-gradient(160deg, #1b1d22, #0d0f12)',
  },
  {
    id: 'deepviolet',
    name: '深紫',
    css: 'linear-gradient(168deg, #241b3d 0%, #1a1430 55%, #100c1e 100%)',
    preview: 'linear-gradient(160deg, #241b3d, #100c1e)',
  },

  /*
   * ★ 新增背景：星空
   * 用多层 radial-gradient 画星星 —— 纯 CSS，零体积、零版权，
   * 而且比贴图更清晰（任意 DPI 都锐利）。
   * 四层星点用不同大小和不透明度，模拟远近层次。
   */
  {
    id: 'starry',
    name: '星空',
    css:
      'radial-gradient(1.4px 1.4px at 18% 24%, rgba(255,255,255,0.95), transparent), ' +
      'radial-gradient(1.2px 1.2px at 72% 16%, rgba(255,255,255,0.85), transparent), ' +
      'radial-gradient(1.6px 1.6px at 42% 68%, rgba(255,255,255,0.9), transparent), ' +
      'radial-gradient(1.1px 1.1px at 86% 52%, rgba(200,220,255,0.8), transparent), ' +
      'radial-gradient(1.3px 1.3px at 28% 82%, rgba(255,240,220,0.75), transparent), ' +
      'radial-gradient(1px 1px at 58% 38%, rgba(255,255,255,0.7), transparent), ' +
      'radial-gradient(1.5px 1.5px at 12% 58%, rgba(190,210,255,0.7), transparent), ' +
      'radial-gradient(1px 1px at 92% 84%, rgba(255,255,255,0.65), transparent), ' +
      'radial-gradient(2.2px 2.2px at 64% 8%, rgba(255,255,255,0.6), transparent), ' +
      'radial-gradient(1200px 700px at 20% 0%, #16204a 0%, #0d1230 45%, #060814 100%)',
    preview:
      'radial-gradient(circle at 25% 30%, rgba(255,255,255,0.9), transparent 40%), ' +
      'radial-gradient(circle at 70% 20%, rgba(255,255,255,0.6), transparent 40%), ' +
      'linear-gradient(160deg, #16204a, #060814)',
  },

  /*
   * ★ 新增背景：学校
   * 黑板 + 木质桌面 + 窗外天光，用渐变和线条画出来。
   * 描边刻意做得很淡（0.06~0.12 透明度）—— 作为背景它只该"暗示"，
   * 太实了会跟正文抢注意力。
   */
  {
    id: 'school',
    name: '学校',
    css:
      /* 天光 */       'linear-gradient(180deg, #cfe3f5 0%, #e8f0f8 38%, transparent 38%), ' +
      /* 窗框 */       'linear-gradient(90deg, transparent 46%, rgba(255,255,255,0.35) 46%, rgba(255,255,255,0.35) 54%, transparent 54%), ' +
      /* 黑板 */       'linear-gradient(178deg, #2f4a3f 0%, #26392f 100%)',
    preview:
      'linear-gradient(180deg, #cfe3f5 0%, #e8f0f8 40%, transparent 40%),' +
      'linear-gradient(178deg, #2f4a3f, #26392f)',
  },

  /* 纯色（自定义图片之外的最后兜底） */
  {
    id: 'solid',
    name: '纯色',
    css: '#f6f7f9',
    preview: '#f6f7f9',
  },

  /* ------------------------------------------------------------------
   ★ 二次元插画背景（1080×1920 竖屏，JPEG q80，每张约 130~540 KB）

   来源：wallhaven.cc（CC0，检索时限定 purity=100 只取 SFW）
   具体文件 / 作者 / 来源页见「来源与授权.md」和
   public/bg-candidates/meta.json。
   ------------------------------------------------------------------ */
  {
    id: 'art-citynight',
    name: '雨夜都市',
    css: '#0a1018',
    preview: 'linear-gradient(160deg, #1a3550, #060a10)',
    image: '/bg/citynight-ogjkm9.jpg',
    dim: 0.42,
  },
  {
    id: 'art-moonlight',
    name: '月夜提灯',
    css: '#0b1020',
    preview: 'linear-gradient(160deg, #2a3a66, #080c18)',
    image: '/bg/starry-6lwmy7.jpg',
    // 这张整体偏亮（有大月亮），压得暗一点才看得清正文
    dim: 0.5,
  },
  {
    id: 'art-rooftop',
    name: '天台少女',
    css: '#0a0e14',
    preview: 'linear-gradient(160deg, #2b3648, #06090e)',
    image: '/bg/citynight-pomg5p.jpg',
    dim: 0.4,
  },
  {
    id: 'art-sakura',
    name: '樱花巫女',
    css: '#160f18',
    preview: 'linear-gradient(160deg, #5a3a5e, #140d16)',
    image: '/bg/sakura-gwdjdl.jpg',
    dim: 0.44,
  },
];

/* 保留一个自定义背景占位（有自定义图时不显示这些预设） */
void 0;

/** 背景是浅色时需要切到浅色主题，否则文字看不清 */
export const LIGHT_PRESETS = new Set(['paper']);

export const ACCENT_PRESETS = [
  { id: 'blue', name: '天蓝', value: '#6ea8fe' },
  { id: 'violet', name: '紫罗兰', value: '#a78bfa' },
  { id: 'green', name: '薄荷', value: '#86efac' },
  { id: 'amber', name: '琥珀', value: '#fbbf24' },
  { id: 'rose', name: '玫瑰', value: '#fb7185' },
  { id: 'cyan', name: '青', value: '#22d3ee' },
];

/**
 * 整套外观预设 —— 一次把「背景 + 主色 + 面板透明度」三样一起换成一套。
 *
 * 为什么需要它：单换主色其实看不出多大差别，真正决定观感的是
 * 背景（明暗/材质）× 面板透明度的组合。用户要的是"换个风格"，
 * 而不是自己凑这三个值 —— 那是调参，不是选风格。
 *
 * 注意：应用预设**只填这三个值**，不锁定其他项。
 * 用户点完预设仍可继续单独微调（预设是起点，不是牢笼）。
 */
export interface ThemePreset {
  id: string;
  name: string;
  desc: string;
  /** 对应 BACKGROUND_PRESETS 里的 id */
  background: string;
  accent: string;
  panelOpacity: number;
  /** 卡片上的预览（直接复用背景预设的 preview 观感） */
  preview: string;
}

export const THEME_PRESETS: ThemePreset[] = [
  {
    id: 'sakura',
    name: '樱粉',
    desc: '暖白底 + 樱花粉，点缀淡粉',
    background: 'paper',
    accent: '#f2789f',
    panelOpacity: 0.95,
    preview: 'linear-gradient(160deg, #fffaf8, #fbe4ec)',
  },
  {
    id: 'white',
    name: '纯白',
    desc: '干净的冷白 + 天蓝',
    background: 'white',
    accent: '#4a9eff',
    panelOpacity: 0.97,
    preview: 'linear-gradient(160deg, #ffffff, #eaf0f8)',
  },
  {
    id: 'cream',
    name: '乳黄',
    desc: '奶油底 + 蜜橘色',
    background: 'cream',
    accent: '#e8963c',
    panelOpacity: 0.96,
    preview: 'linear-gradient(160deg, #fdf7e8, #f2dfbd)',
  },
  {
    id: 'charcoal',
    name: '灰黑',
    desc: '中性深灰 + 亮蓝（对比最舒服的一套）',
    background: 'charcoal',
    accent: '#5b9dfb',
    panelOpacity: 0.93,
    preview: 'linear-gradient(160deg, #1e2025, #0e1013)',
  },
  {
    id: 'deepviolet',
    name: '深紫',
    desc: '深紫底 + 薰衣草紫',
    background: 'deepviolet',
    accent: '#b18cf5',
    panelOpacity: 0.93,
    preview: 'linear-gradient(160deg, #271e42, #120d22)',
  },
];


export const useAppearanceStore = defineStore('appearance', () => {
  /** 背景预设 id，或 'custom'（使用 customImage） */
  const background = ref('default');
  /** 自定义背景图（dataURL），直接存在本地/落盘文件里 */
  const customImage = ref('');
  /** 自定义背景图的不透明度，避免盖住界面 */
  const customImageDim = ref(0.28);
  /** 面板不透明度：背景图复杂时降低面板透明感，保证可读性 */
  const panelOpacity = ref(0.92);
  /** 主色 */
  const accent = ref('#6ea8fe');
  /** 正文宽度上限（阅读舒适度） */
  const contentWidth = ref(1200);
  /**
   * 当前用的是哪套外观预设。
   * 用户手动改了背景或主色就清空（表示"自定义"），
   * 这样卡片上的选中态才不会骗人。
   */
  const themeId = ref('');

  function apply() {
    if (typeof document === 'undefined') return;
    const root = document.documentElement;

    const preset = BACKGROUND_PRESETS.find((p) => p.id === background.value);

    // 1. 背景
    /*
     * 两种背景形态：
     *   custom   → 用户自己传的图片（存 dataURL）
     *   预设     → preset.css，可能是一段渐变，也可能是 url(...) 指到内置插画
     *
     * 插画背景要盖一层压暗遮罩，否则正文压在亮部上会看不清 ——
     * 用户还能自己调深浅（customImageDim）。
     */
    if (background.value === 'custom' && customImage.value) {
      root.style.setProperty('--app-bg', `url("${customImage.value}")`);
      root.style.setProperty('--app-bg-size', 'cover');
      root.style.setProperty('--app-bg-repeat', 'no-repeat');
      root.style.setProperty('--app-bg-position', 'center center');
      // 用一层遮罩压暗，保证前景可读
      root.style.setProperty(
        '--app-bg-overlay',
        `linear-gradient(rgba(8,10,16,${customImageDim.value}), rgba(8,10,16,${Math.min(0.9, customImageDim.value + 0.12)}))`,
      );
    } else if (preset && preset.image) {
      /* 内置插画背景 */
      root.style.setProperty('--app-bg', `url("${preset.image}")`);
      root.style.setProperty('--app-bg-size', 'cover');
      root.style.setProperty('--app-bg-repeat', 'no-repeat');
      root.style.setProperty('--app-bg-position', 'center center');
      /* 竖屏图当背景：底部正文区要更暗才好读 */
      const d = preset.dim ?? 0.4;
      root.style.setProperty(
        '--app-bg-overlay',
        `linear-gradient(rgba(8,10,16,${d}), rgba(8,10,16,${Math.min(0.92, d + 0.14)}))`,
      );
    } else {
      root.style.setProperty('--app-bg', preset?.css || '#0f1115');
      root.style.setProperty('--app-bg-size', 'auto');
      root.style.setProperty('--app-bg-repeat', 'no-repeat');
      root.style.setProperty('--app-bg-position', 'center center');
      root.style.setProperty('--app-bg-overlay', 'none');
    }

    // 2. 浅色背景切换文字与面板配色。
    //    注意必须在这里显式写全部颜色：:root 上的内联自定义属性优先级高于
    //    样式表里的同名声明，所以不能指望 CSS 里的 [data-theme] 规则生效。
    const isLight = LIGHT_PRESETS.has(background.value) && background.value !== 'custom';
    root.dataset.theme = isLight ? 'light' : 'dark';

    if (isLight) {
      root.style.setProperty('--bg', '#f3ede2');
      root.style.setProperty('--text', '#23201c');
      root.style.setProperty('--text-dim', '#6b6357');
      root.style.setProperty('--border', '#d9cfbe');
      root.style.setProperty('--code-bg', '#efe7d9');
      /*
       * 不透明面板色 —— 专给"浮在别的内容之上"的弹层用（模型选择面板等）。
       *
       * --panel 是半透明的（那是为了玻璃质感），弹层用它会**透出背后的按钮**，
       * 看着像两行文字叠在一起（实测踩到）。弹层需要挡住底下内容。
       */
      root.style.setProperty('--panel-solid', '#fdfbf7');
    } else {
      root.style.setProperty('--bg', '#0f1115');
      root.style.setProperty('--text', '#e6e8ef');
      root.style.setProperty('--text-dim', '#9aa0b4');
      root.style.setProperty('--border', '#262a3a');
      root.style.setProperty('--code-bg', '#0a0c12');
      root.style.setProperty('--panel-solid', '#161922');
    }

    /*
     * 2b. 材质类令牌也必须跟着主题走。
     *
     * 这几个是后加的（毛玻璃、表面渐变、高光、投影），
     * 一开始在 main.css 的 :root 里写死了**深色**值 ——
     * 结果浅色主题下顶部栏/输入区/标签栏变成"深灰底 + 深色文字"，
     * 整个看不清。凡是与明暗相关的颜色，都不能写死在一处。
     */
    if (isLight) {
      /*
       * 毛玻璃的不透明度要够高。
       *
       * 一开始给了 0.86，结果正文从输入区/标签栏后面透出来 ——
       * 黑字隐约浮在栏后面，看起来非常乱。商业级 App 的固定栏
       * 基本是"接近不透明"，模糊只是让边缘柔和一点，
       * 不是拿来当"滤镜"的。所以这里给到 0.97。
       */
      root.style.setProperty('--glass', 'rgba(250, 248, 244, 0.97)');
      root.style.setProperty('--hairline', 'rgba(255, 255, 255, 0.6)');
      root.style.setProperty('--hairline-strong', 'rgba(255, 255, 255, 0.9)');
      root.style.setProperty('--surface-1', 'linear-gradient(180deg, rgba(255,255,255,0.55), rgba(255,255,255,0.2))');
      root.style.setProperty('--surface-2', 'linear-gradient(180deg, rgba(255,255,255,0.7), rgba(255,255,255,0.3))');
      root.style.setProperty('--shadow-1', '0 1px 2px rgba(90, 78, 58, 0.1), 0 2px 6px rgba(90, 78, 58, 0.07)');
      root.style.setProperty('--shadow-2', '0 2px 6px rgba(90, 78, 58, 0.12), 0 10px 26px rgba(90, 78, 58, 0.1)');
      root.style.setProperty('--shadow-3', '0 10px 24px rgba(90, 78, 58, 0.16), 0 24px 56px rgba(90, 78, 58, 0.14)');
      root.style.setProperty('--shadow-up', '0 -6px 22px rgba(90, 78, 58, 0.13)');
    } else {
      root.style.setProperty('--glass', 'rgba(18, 21, 29, 0.94)');
      root.style.setProperty('--hairline', 'rgba(255, 255, 255, 0.07)');
      root.style.setProperty('--hairline-strong', 'rgba(255, 255, 255, 0.12)');
      root.style.setProperty('--surface-1', 'linear-gradient(180deg, rgba(255,255,255,0.05), rgba(255,255,255,0.014))');
      root.style.setProperty('--surface-2', 'linear-gradient(180deg, rgba(255,255,255,0.075), rgba(255,255,255,0.022))');
      root.style.setProperty('--shadow-1', '0 1px 2px rgba(0, 0, 0, 0.35), 0 2px 6px rgba(0, 0, 0, 0.22)');
      root.style.setProperty('--shadow-2', '0 2px 6px rgba(0, 0, 0, 0.38), 0 10px 26px rgba(0, 0, 0, 0.32)');
      root.style.setProperty('--shadow-3', '0 10px 24px rgba(0, 0, 0, 0.46), 0 24px 56px rgba(0, 0, 0, 0.42)');
      root.style.setProperty('--shadow-up', '0 -6px 22px rgba(0, 0, 0, 0.34)');
    }

    // 3. 主色
    /*
     * 关键决策：`--accent` 到底给"亮色"还是"暗色"？
     *
     * 全项目有 85 处把 `var(--accent)` 当**文字色**用（标题、标签、
     * 链接、激活态…），只有少数几处是当**背景/渐变**用。
     * 所以 `--accent` 定义为"**能当文字读**的那个色"：
     *   浅色主题 → 压暗（亮蓝配白底对比度只有 2，看不清）
     *   深色主题 → 原色（亮色在暗底上正好，且是设计意图）
     * 需要"亮版"的地方（渐变按钮、光晕）用 `--accent-bright`，它永远是原色。
     *
     * 这样 85 处调用点一行都不用改，主题切换自动都对。
     */
    if (isLight) {
      root.style.setProperty('--accent', `color-mix(in srgb, ${accent.value} 62%, #1a1712)`);
    } else {
      root.style.setProperty('--accent', accent.value);
    }
    // 亮版永远保持用户选的原色，供渐变/光晕/进度条等使用
    root.style.setProperty('--accent-bright', accent.value);
    // 兼容旧写法
    root.style.setProperty('--accent-text', isLight
      ? `color-mix(in srgb, ${accent.value} 62%, #1a1712)`
      : accent.value);

    // 4. 面板不透明度：把 panel 颜色转成带透明度的写法
    const base = isLight ? '255,255,255' : '22,25,34';
    const base2 = isLight ? '246,242,235' : '28,32,48';
    root.style.setProperty('--panel', `rgba(${base}, ${panelOpacity.value})`);
    root.style.setProperty('--panel-2', `rgba(${base2}, ${panelOpacity.value})`);

    // 5. 内容宽度
    root.style.setProperty('--content-max', `${contentWidth.value}px`);
  }

  async function load() {
    const [bg, img, dim, po, ac, cw, th] = await Promise.all([
      store.getItem<string>('appearanceBackground'),
      store.getItem<string>('appearanceCustomImage'),
      store.getItem<number>('appearanceCustomDim'),
      store.getItem<number>('appearancePanelOpacity'),
      store.getItem<string>('appearanceAccent'),
      store.getItem<number>('appearanceContentWidth'),
      store.getItem<string>('appearanceThemeId'),
    ]);
    background.value = bg ?? 'default';
    customImage.value = img ?? '';
    customImageDim.value = dim ?? 0.28;
    panelOpacity.value = po ?? 0.92;
    accent.value = ac ?? '#6ea8fe';
    contentWidth.value = cw ?? 1200;
    themeId.value = th ?? '';
    apply();
  }

  async function save() {
    apply();
    await Promise.all([
      store.setItem('appearanceBackground', background.value),
      store.setItem('appearanceCustomImage', customImage.value),
      store.setItem('appearanceCustomDim', customImageDim.value),
      store.setItem('appearancePanelOpacity', panelOpacity.value),
      store.setItem('appearanceAccent', accent.value),
      store.setItem('appearanceContentWidth', contentWidth.value),
      store.setItem('appearanceThemeId', themeId.value),
    ]);
  }

  /**
   * 套用一整套外观预设。
   *
   * 只改「背景 / 主色 / 面板不透明度」三个值 —— 正文宽度、自定义背景图、
   * 背景压暗都保持用户原来的设置（那些和"风格"无关）。
   */
  async function applyTheme(id: string) {
    const t = THEME_PRESETS.find((p) => p.id === id);
    if (!t) return;
    background.value = t.background;
    accent.value = t.accent;
    panelOpacity.value = t.panelOpacity;
    themeId.value = t.id;
    await save();
  }

  /** 读取用户选的图片文件并压到合理体积（背景图不需要原图分辨率） */
  async function setCustomImageFile(file: File): Promise<void> {
    const dataUrl = await new Promise<string>((res, rej) => {
      const r = new FileReader();
      r.onload = () => res(r.result as string);
      r.onerror = () => rej(new Error('读取图片失败'));
      r.readAsDataURL(file);
    });

    // 压到较长边 1920，避免把几十 MB 的原图塞进落盘文件
    const img = await new Promise<HTMLImageElement>((res, rej) => {
      const i = new Image();
      i.onload = () => res(i);
      i.onerror = () => rej(new Error('图片解码失败'));
      i.src = dataUrl;
    });
    const maxSide = 1920;
    const scale = Math.min(1, maxSide / Math.max(img.width, img.height));
    const w = Math.max(1, Math.round(img.width * scale));
    const h = Math.max(1, Math.round(img.height * scale));
    const canvas = document.createElement('canvas');
    canvas.width = w;
    canvas.height = h;
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new Error('无法创建画布');
    ctx.drawImage(img, 0, 0, w, h);
    const isPng = /image\/png/i.test(file.type);
    const out = canvas.toDataURL(isPng ? 'image/png' : 'image/jpeg', 0.85);

    customImage.value = out;
    background.value = 'custom';
    await save();
  }

  async function clearCustomImage() {
    customImage.value = '';
    if (background.value === 'custom') background.value = 'default';
    await save();
  }

  // 任何一项变化都立即生效
  watch([background, customImage, customImageDim, panelOpacity, accent, contentWidth], () => {
    apply();
  });

  /*
   * 用户手动改了「背景 / 主色 / 面板透明度」→ 取消预设的选中态。
   *
   * 否则会出现"卡片显示选了樱色，但主色其实是别的"这种骗人的状态。
   * 只监听这三个，是因为预设只控制这三个 —— 改正文宽度不该影响预设归属。
   *
   * 关键：必须 **flush: 'sync'**（同步执行）。
   *   这些设置项的改法是 `xxx.value = v; await save()`，
   *   watcher 默认是异步的（下一 tick 才跑），于是 save() 写盘时
   *   themeId 还没被清空 —— 磁盘上留着旧预设 id，
   *   刷新后卡片又"选中"了，但配色其实已经是手动改的（实测踩到过）。
   *   同步执行就没有这个时间差。
   */
  watch(
    [background, accent, panelOpacity],
    () => {
      const t = THEME_PRESETS.find((p) => p.id === themeId.value);
      if (!t) return;
      if (
        t.background !== background.value ||
        t.accent !== accent.value ||
        t.panelOpacity !== panelOpacity.value
      ) {
        themeId.value = '';
      }
    },
    { flush: 'sync' },
  );

  return {
    background,
    customImage,
    customImageDim,
    panelOpacity,
    accent,
    contentWidth,
    themeId,
    load,
    save,
    apply,
    applyTheme,
    setCustomImageFile,
    clearCustomImage,
  };
});
