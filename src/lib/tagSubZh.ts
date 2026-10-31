/*
 * 标签库「二级分类」的中文显示名。
 * ---------------------------------------------------------------------------
 * 为什么单独放一个文件：
 *   标签库 JSON 里的 sub 是英文代号（outfit / hair_color …），界面上要显示中文。
 *   这个映射以前直接写在 Tags.vue 里，于是每次改标签库分类都得记得去改 UI，
 *   漏改就会在界面上露出英文代号（之前 hair_style、pattern 就是这样）。
 *
 *   现在这里是**唯一的一份**，`scripts/rebuild-tags.mjs` 生成 public/tags/meta.json
 *   时用的是同一张表。加新分类请两边一起改（或直接以这里为准）。
 *
 * 一级分类顺序也是固定写死的 —— 不按数量排。
 *   按数量排的话，用户每刷新一次页面，分类名的位置就跳一次，很难点。
 */

/** 一级分类的显示顺序（NSFW 单独处理，见 Tags.vue） */
export const TAG_GROUP_ORDER = ['服装', '角色扮演', '外表', '动作', '场景', '动物', '风格'];

/** 二级分类代号 → 中文 */
export const TAG_SUB_ZH: Record<string, string> = {
  // 服装
  outfit: '整装', top: '上装', bottom: '下装', headwear: '头饰', accents: '配饰',
  legwear: '腿袜', footwear: '鞋履', eyewear: '眼镜', underwear: '内衣',
  material: '材质', pattern: '花纹',
  // 角色扮演
  cosplay: 'cosplay',
  // 外表
  hair_style: '发型', hair_color: '发色', eye_color: '瞳色', eye_features: '眼睛',
  face: '面部', body: '身体', skin: '皮肤', ears: '耳朵', tail: '尾巴',
  wings: '翅膀', horns: '角', demographics: '人口属性', identity: '身份', character: '角色',
  // 动作
  pose: '姿态', gesture: '手势', interaction: '互动', expression: '表情',
  camera: '镜头', motion: '动态', combat: '战斗', lewd: '性感',
  physiological: '生理反应', holding: '手持物', effect: '画面效果',
  // 场景
  setting: '场景', indoor: '室内', outdoor: '室外', nature: '自然', props: '道具',
  furniture: '家具', food: '食物', weapon: '武器', sky: '天空', weather: '天气',
  atmosphere: '氛围',
  // 动物
  animal: '动物', creature: '生物',
  // 风格
  quality: '画质', style: '画风', layout: '构图', color: '颜色', lighting: '光照',
  background: '背景', medium: '媒介', preset: '提示词预设',
  // NSFW 与通用
  anatomy: '身体', hair: '毛发', eyes: '眼睛',
  'SM/BDSM': 'SM/BDSM', misc: '其它',
};

/**
 * 二级分类的显示名。
 * 老库里有少量子类本来就是中文（历史遗留），直接原样显示。
 */
export function tagSubLabel(sub: string): string {
  if (!sub) return '';
  if (/[\u4e00-\u9fff]/.test(sub)) return sub;
  return TAG_SUB_ZH[sub] || sub;
}
