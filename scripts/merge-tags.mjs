// 合并成最终标签库
//   旧库 4,121 条（旧分类） + 新翻译 18,495 条（新分类）
//   → public/tags/novelai-tags.json（统一字段 tag/zh/group/sub）
//
// 做的事：
//   1. 旧库的 12 个分类映射进新分类体系（"裸露"→"NSFW"，用户要求改名）
//   2. 新库里的 cosplay / 制服类单独抽成「角色扮演」
//   3. 排掉和旧库重复的（source 已经排过，这里再兜一层）
import fs from 'node:fs';

const OLD = JSON.parse(fs.readFileSync('E:/duihuazhushou/public/tags/novelai-tags.json', 'utf8'));
const NEW = JSON.parse(fs.readFileSync('E:/duihuazhushou/scripts/tag-translations.json', 'utf8'));

/* ---------- 旧分类 → 新分类 ---------- */
const OLD_MAP = {
  服饰: '服装', 汉服: '服装',
  人物: '外表',
  表情动作: '动作', 镜头: '动作',
  场景: '场景', 环境: '场景', 物品: '场景',
  画面: '风格', 魔法系: '风格', 质量与风格: '风格',
  性相关: 'NSFW', 裸露: 'NSFW',
};

/* ---------- cosplay / 制服 → 角色扮演 ---------- */
const COSTUME = [/\(cosplay\)$/i, /\([a-z0-9_ -]+\)_\(cosplay\)$/i, /_uniform$/i, /^cosplay|_cosplay\b/i];

const isCostume = (tag) => COSTUME.some((re) => re.test(tag));

/* ---------- 二级分类中文名 ---------- */
const SUB_ZH = {
  outfit: '整装', accents: '配饰', headwear: '头饰', top: '上装', bottom: '下装',
  footwear: '鞋履', legwear: '腿袜', eyewear: '眼镜', underwear: '内衣',
  anatomy: '身体', hair: '发型', eyes: '眼睛', demographics: '人口属性',
  physiological: '生理反应', interaction: '互动', gesture: '手势',
  posture: '姿态', expression: '表情', camera: '镜头', kinetic: '动态',
  combat: '战斗', props: '道具', setting: '场景', atmosphere: '氛围',
  animal: '动物', style: '画风', layout: '构图', handheld: '手持物',
  weapon: '武器', misc: '其他',
};
/* 旧库的 sub 是中文，直接当显示名用 */

const out = {};
let dup = 0;

/* 1) 旧库先进（保证老标签一定在） */
for (const t of OLD) {
  const g = OLD_MAP[t.group] || t.group || '其他';
  out[t.tag] = { zh: t.zh, group: g, sub: t.sub || '' };
}

/* 2) 新翻译库覆盖进来 */
for (const [tag, v] of Object.entries(NEW.tags)) {
  if (out[tag]) {
    dup++;
    continue;
  }
  const group = isCostume(tag) ? '角色扮演' : v.group;
  out[tag] = { zh: v.zh, group, sub: group === '角色扮演' ? 'cosplay' : v.sub || '' };
}

/* ---------- 统计 ---------- */
const byGroup = {};
const bySub = {};
for (const t of Object.values(out)) {
  byGroup[t.group] = (byGroup[t.group] || 0) + 1;
  const k = t.group + '›' + t.sub;
  bySub[k] = (bySub[k] || 0) + 1;
}

console.log('旧库 ' + OLD.length + ' + 新翻译 ' + Object.keys(NEW.tags).length + ' = ' + Object.keys(out).length + ' 条');
console.log('（重复被旧库占用 ' + dup + ' 条）');
console.log('');
console.log('一级分类:');
const order = ['服装', '角色扮演', '外表', '动作', '场景', '动物', '风格', 'NSFW'];
for (const k of order) console.log('  ' + k.padEnd(10) + (byGroup[k] || 0) + ' 条');
const extra = Object.keys(byGroup).filter((k) => !order.includes(k));
if (extra.length) console.log('  其它: ' + extra.map((k) => k + '=' + byGroup[k]).join(', '));
console.log('');
console.log('二级分类数: ' + Object.keys(bySub).length);

/* 写文件 */
const ORDER = order.filter((k) => byGroup[k]);
const array = ORDER.flatMap((g) =>
  Object.entries(out)
    .filter(([, v]) => v.group === g)
    .sort((a, b) => b[1].zh.localeCompare(a[1].zh, 'zh'))
    .map(([tag, v]) => ({ tag, zh: v.zh, group: v.group, sub: v.sub })),
);

fs.writeFileSync(
  'E:/duihuazhushou/public/tags/novelai-tags.json',
  JSON.stringify(array),
);
const size = fs.statSync('E:/duihuazhushou/public/tags/novelai-tags.json').size;
console.log('');
console.log('写入 public/tags/novelai-tags.json  ' + (size / 1048576).toFixed(2) + ' MB  ' + array.length + ' 条');

/* 把二级分类中文名导出给界面用 */
fs.writeFileSync(
  'E:/duihuazhushou/public/tags/meta.json',
  JSON.stringify({
    groups: ORDER,
    subZh: SUB_ZH,
    counts: byGroup,
  }),
);
console.log('写入 public/tags/meta.json（分类中文名 + 计数）');

/* 各分类的二级明细，供界面折叠用 */
console.log('');
for (const g of ORDER) {
  const subs = Object.entries(bySub)
    .filter(([k]) => k.startsWith(g + '›'))
    .map(([k, v]) => [k.split('›')[1] || '', v])
    .sort((a, b) => b[1] - a[1]);
  const named = subs.filter(([s]) => s && SUB_ZH[s]);
  console.log('【' + g + '】' + subs.length + ' 个二级: ' + named.slice(0, 8).map(([s, v]) => (SUB_ZH[s] || s) + '=' + v).join(' '));
}