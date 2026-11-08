// 资产改名的**跨语言契约测试**。
//
// 为什么单独测这个：改名横跨三层 —— 原生长按菜单 → 桥命令（JSON 键名）→
// web 命令分支（按 _uid 找对象、写哪一层字段）。三层任何一处键名不一致，
// 表现都是「点了保存但名字没变」，而编译完全过得去。
//
// 做法：
//   1. 静态契约：桥命令名 / 参数键 / 查找字段 / 保存调用 必须成对出现；
//   2. 运行时行为：把源码里的 setAssetName 抽出来真跑一遍，
//      覆盖 ST 的三种落盘形态（扁平 / data 包装 / preset_name）。
import fs from 'node:fs';
import { transformSync } from 'esbuild';

const ROOT = new URL('..', import.meta.url).pathname;
const WEB = fs.readFileSync(`${ROOT}src/bridge/generateService.ts`, 'utf8');
const NATIVE = fs.readFileSync(
  `${ROOT}dbpg/app/src/main/java/com/dbpg/duihuazhushou/assets/AssetsHomeActivity.kt`,
  'utf8',
);

let fails = 0;
const check = (label, cond, extra = '') => {
  if (!cond) fails++;
  console.log(`  ${cond ? '✓' : '✗ FAIL'}  ${label}${extra ? '  ' + extra : ''}`);
};

console.log('################ 资产改名契约 ################\n');

// ---- 1. web 侧三条命令：参数键 → 查找字段 → 保存 ----
const cases = [
  { cmd: 'presetRename', key: 'presetId', find: 'x._uid === req.presetId' },
  { cmd: 'regexRename', key: 'groupId', find: 'x._uid === req.groupId' },
  { cmd: 'wbRename', key: 'bookId', find: 'w._uid === req.bookId' },
];

for (const c of cases) {
  const block = WEB.match(new RegExp(`case '${c.cmd}': \\{[\\s\\S]*?\\n      \\}`));
  check(`web 有 case '${c.cmd}'`, !!block);
  if (!block) continue;
  const body = block[0];
  check(`  读 req.${c.key}`, body.includes(`req.${c.key}`), '');
  check(`  按 _uid 查找（${c.find}）`, body.includes(c.find));
  check('  调用 setAssetName', body.includes('setAssetName('));
  check('  落盘 a.save()', body.includes('await a.save()'));
  check('  拒绝空名', body.includes("if (!name) throw"));
}

// ---- 2. 原生侧：命令名与参数键必须和 web 对上 ----
const nativeCalls = [
  { cmd: 'presetRename', key: 'presetId', ui: '重命名预设' },
  { cmd: 'regexRename', key: 'groupId', ui: '重命名正则组' },
  { cmd: 'wbRename', key: 'bookId', ui: '重命名世界书' },
];
for (const c of nativeCalls) {
  check(`原生发 ${c.cmd}`, NATIVE.includes(`"cmd", "${c.cmd}"`));
  // 命令与参数键要在同一段 put 链里（renameDialog 内）
  const idx = NATIVE.indexOf(`"cmd", "${c.cmd}"`);
  const near = idx >= 0 ? NATIVE.slice(idx, idx + 220) : '';
  check(`  带 ${c.key}`, near.includes(`put("${c.key}", id)`));
  check(`  入口文案「${c.ui}」`, NATIVE.includes(c.ui));
}

// ---- 3. 结果回调要能触发重载，否则改完列表不变 ----
check(
  'onApiResult 监听三条改名命令',
  ['presetRename', 'regexRename', 'wbRename'].every((c) => {
    const seg = NATIVE.slice(NATIVE.indexOf('private fun onApiResult'));
    return seg.includes(`"${c}"`);
  }),
);

// ---- 4. 运行时行为：抽出 setAssetName 真跑 ----
const fnMatch = WEB.match(/function setAssetName\(obj: any, name: string\): void \{[\s\S]*?\n\}/);
check('能抽出 setAssetName 源码', !!fnMatch);
if (fnMatch) {
  const js = transformSync(fnMatch[0], { loader: 'ts' }).code;
  const body = js.replace(/^function setAssetName\(obj, name\) \{/, '').replace(/\}\s*$/, '');
  const run = (obj, name) => new Function('obj', 'name', body)(obj, name);

  // ST 预设常见形态：{ name, preset_name, prompts }（parsecard toJSON）
  const preset = { name: '旧预设', preset_name: '旧预设', prompts: [] };
  run(preset, '新预设');
  check('预设：name 与 preset_name 同步', preset.name === '新预设' && preset.preset_name === '新预设');

  // 世界书老形态：{ data: { name, entries } }
  const book = { data: { name: '旧书', entries: [] } };
  run(book, '新书');
  check('世界书 data 包装：两层同步', book.name === '新书' && book.data.name === '新书');

  // 正则组：只有 name，不该凭空长出 preset_name / scriptName
  const group = { name: '旧组', scripts: [] };
  run(group, '新组');
  check(
    '正则组：只写存在的字段',
    group.name === '新组' && !('preset_name' in group) && !('scriptName' in group),
  );

  // 空名在命令层就被拦了，函数本身不做兜底 —— 确认不会写出空串
  const guard = WEB.match(new RegExp(`case 'presetRename': \\{[\\s\\S]*?\\n      \\}`))?.[0] || '';
  check('空名分支在写库之前 return', guard.indexOf('throw') < guard.indexOf('setAssetName('));
}

console.log(`\n${fails === 0 ? '全部通过' : `${fails} 项失败`}`);
process.exit(fails === 0 ? 0 : 1);
