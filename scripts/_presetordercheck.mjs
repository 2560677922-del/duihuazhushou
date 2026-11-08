/**
 * 预设顺序 / 未激活分区 契约检查
 *
 * 用法：node scripts/_presetordercheck.mjs
 *
 * 背景：ST 预设的发送顺序只由 prompt_order 决定，prompts[] 数组顺序是
 * 历史插入顺序。以前资产页按 prompts[] 显示、assembler 又默认把未激活项
 * 追加进上下文，导致「导入后顺序变了」「有的条目像被藏了」。
 *
 * 这里用一份**故意把 prompts[] 打乱**的 fixture 验证：
 *   1. 显示/组装都按 prompt_order
 *   2. 不在 order 里的条目算「未激活」，不参与组装
 *   3. 上下移只动排序表，不动提示词库
 *   4. 「加入排序」追加到末尾并启用
 *   5. 三种历史 prompt_order 形态都能读
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const bundle = path.join(root, '.tmp-presetorder.mjs');

await build({
  stdin: {
    contents: `
      export { listRankedPrompts, movePromptInOrder, activatePromptInOrder, getActivePromptOrder } from './src/engine/presetOrder.ts';
      export { assemblePrompt } from './src/engine/assembler.ts';
    `,
    resolveDir: root,
    loader: 'ts',
  },
  bundle: true,
  format: 'esm',
  platform: 'node',
  outfile: bundle,
  external: ['parsecard', 'nekoai-js'],
  logLevel: 'silent',
});

const {
  listRankedPrompts,
  movePromptInOrder,
  activatePromptInOrder,
  getActivePromptOrder,
  assemblePrompt,
} = await import(`file://${bundle}?t=${Date.now()}`);

let pass = 0;
const fails = [];
function ok(name, cond, extra = '') {
  if (cond) {
    pass += 1;
    console.log(`  ✓ ${name}`);
  } else {
    fails.push(name + (extra ? ` — ${extra}` : ''));
    console.log(`  ✗ ${name}${extra ? ` — ${extra}` : ''}`);
  }
}

/** prompts[] 故意乱序：c, a, d(未激活), b, e(停用) */
function fixture() {
  return {
    name: '顺序测试预设',
    // 注意：数组顺序 ≠ 发送顺序
    prompts: [
      { identifier: 'c', name: 'C', content: '内容C', role: 'system', enabled: true },
      { identifier: 'a', name: 'A', content: '内容A', role: 'system', enabled: true },
      { identifier: 'ghost', name: 'Ghost', content: '不该出现', role: 'system', enabled: true },
      { identifier: 'b', name: 'B', content: '内容B', role: 'system', enabled: true },
      { identifier: 'off', name: 'Off', content: '已停用内容', role: 'system', enabled: true },
    ],
    prompt_order: [
      { character_id: 100000, order: [] },
      {
        character_id: 100001,
        order: [
          { identifier: 'a', enabled: true },
          { identifier: 'off', enabled: false },
          { identifier: 'b', enabled: true },
          { identifier: 'c', enabled: true },
        ],
      },
    ],
  };
}

console.log('1) 排序与分区');
{
  const p = fixture();
  const ranked = listRankedPrompts(p);
  ok('有序条目数 = 4', ranked.filter((e) => e.ordered).length === 4);
  ok(
    '顺序严格跟 prompt_order',
    ranked.filter((e) => e.ordered).map((e) => e.prompt.name).join(',') === 'A,Off,B,C',
    ranked.map((e) => e.prompt.name).join(','),
  );
  ok('未激活条目排在末尾', ranked[ranked.length - 1].identifier === 'ghost');
  ok('未激活条目 enabled=false', ranked[ranked.length - 1].enabled === false);
  ok('position 从 1 连续', ranked.filter((e) => e.ordered).map((e) => e.position).join(',') === '1,2,3,4');
  ok('停用条目仍在有序区且 enabled=false', ranked.find((e) => e.identifier === 'off').enabled === false);
}

console.log('2) 组装：只发有序 + 已启用，顺序不变');
{
  const p = fixture();
  const result = assemblePrompt({
    preset: p,
    chatHistory: [],
    currentUserInput: '你好',
  });
  const names = result.finalMessages.filter((m) => m._origin === 'preset').map((m) => m._name);
  ok('A 在最前', names[0] === 'A', names.join(','));
  ok('顺序 A→B→C', names.join(',') === 'A,B,C', names.join(','));
  ok('停用项未发送', !names.includes('Off'));
  ok('未激活项未发送', !names.includes('Ghost'));
  ok(
    '未激活有告警提示',
    result.warnings.some((w) => w.includes('未激活')),
    result.warnings.join(' | '),
  );
}

console.log('3) 上下移只动排序表');
{
  const p = fixture();
  const before = p.prompts.map((x) => x.identifier).join(',');
  ok('上移成功', movePromptInOrder(p, 'b', -1) === true);
  ok('B 到了 Off 前面', getActivePromptOrder(p).map((e) => e.identifier).join(',') === 'a,b,off,c');
  ok('提示词库顺序未变', p.prompts.map((x) => x.identifier).join(',') === before);
  ok('已在顶部时不动', movePromptInOrder(p, 'a', -1) === false);
  ok('已在底部时不动', movePromptInOrder(p, 'c', 1) === false);
}

console.log('4) 加入排序');
{
  const p = fixture();
  ok('激活成功', activatePromptInOrder(p, 'ghost') === true);
  const ranked = listRankedPrompts(p);
  ok('未激活区已空', ranked.every((e) => e.ordered));
  ok('追加到末尾', ranked[ranked.length - 1].identifier === 'ghost');
  ok('追加后为启用', ranked[ranked.length - 1].enabled === true);
  ok('prompts[].enabled 同步', p.prompts.find((x) => x.identifier === 'ghost').enabled === true);
  ok('重复激活返回 false', activatePromptInOrder(p, 'ghost') === false);
}

console.log('5) 三种历史 prompt_order 形态');
{
  const base = fixture().prompts;
  const asMap = { prompts: base, prompt_order: { 100001: [{ identifier: 'c', enabled: true }, { identifier: 'a', enabled: true }] } };
  ok('对象映射形态', listRankedPrompts(asMap).filter((e) => e.ordered).length === 2);
  const asString = { prompts: base, prompt_order: { 100001: ['b', 'a'] } };
  ok('字符串数组形态', listRankedPrompts(asString).filter((e) => e.ordered).map((e) => e.prompt.name).join(',') === 'B,A');
  const noOrder = { prompts: base };
  ok('无排序表时全部按未激活（由 normalizePreset 兜底）', listRankedPrompts(noOrder).every((e) => !e.ordered));
}

console.log('6) 真实预设（可选）');
{
  const real = '/root/uploads/1794148206574334701-Meowssiah_1.9.12.json';
  if (!fs.existsSync(real)) {
    console.log('  - 跳过：本地没有上传文件');
  } else {
    const raw = JSON.parse(fs.readFileSync(real, 'utf8'));
    const ranked = listRankedPrompts(raw);
    const ordered = ranked.filter((e) => e.ordered);
    const inactive = ranked.filter((e) => !e.ordered);
    ok('有序 52 条', ordered.length === 52, String(ordered.length));
    ok('未激活 2 条', inactive.length === 2, inactive.map((e) => e.name).join('|'));
    ok(
      '前三条与 ST/Tavo 一致',
      ordered.slice(0, 3).map((e) => e.prompt.name).join('|') ===
        '（不要关）token重定义+初始化|（必看）免责声明|用户角色',
      ordered.slice(0, 3).map((e) => e.prompt.name).join('|'),
    );
    ok('未激活是版本信息 + SPreset配置', inactive.every((e) => !e.enabled));
  }
}

fs.rmSync(bundle, { force: true });

console.log('');
if (fails.length) {
  console.log(`✗ ${fails.length} 项失败 / ${pass} 项通过`);
  for (const f of fails) console.log('  - ' + f);
  process.exit(1);
}
console.log(`✓ 全部 ${pass} 项通过`);
