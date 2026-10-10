// 重做外观：精简成 5 套主题 + 加星空/学校背景
import fs from 'node:fs';
const f = 'E:/duihuazhushou/src/stores/appearance.ts';
let c = fs.readFileSync(f, 'utf8');

/* ---------------- 背景预设 ---------------- */
const BG_NEW = `export const BACKGROUND_PRESETS: BackgroundPreset[] = [
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
];

/* 保留一个自定义背景占位（有自定义图时不显示这些预设） */
void 0;`;

/* 替换 BACKGROUND_PRESETS 整块 */
c = c.replace(
  /export const BACKGROUND_PRESETS: BackgroundPreset\[\] = \[[\s\S]*?\n\];/,
  BG_NEW,
);

/* ---------------- 主题预设：5 套 ---------------- */
const THEME_NEW = `export const THEME_PRESETS: ThemePreset[] = [
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
];`;

c = c.replace(/export const THEME_PRESETS: ThemePreset\[\] = \[[\s\S]*?\n\];/, THEME_NEW);

fs.writeFileSync(f, c, 'utf8');
console.log('  主题数: ' + (c.match(/id: '(sakura|white|cream|charcoal|deepviolet)'/g) || []).length);
console.log('  背景数: ' + (c.match(/id: '(paper|white|cream|charcoal|deepviolet|starry|school|solid)'/g) || []).length);