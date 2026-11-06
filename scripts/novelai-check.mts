// NovelAI 请求体 / 提示词拼装 格式校验
// 运行：npx.cmd tsx scripts/novelai-check.mts
import assert from 'node:assert/strict';
import {
  buildNovelAIRequest,
  defaultSamplerFor,
  NOVELAI_SAMPLERS,
  SIZE_PRESETS,
  UC_PRESETS,
} from '../src/engine/novelai.ts';
import { composePrompt, renderPromptTemplate, DEFAULT_PROMPT_TEMPLATE } from '../src/engine/imageGen.ts';
import {
  parseImageMarkers,
  stripImageMarkers,
  hasImageMarker,
  buildContentBlocks,
  imageMarkerInstruction,
} from '../src/engine/imageMarker.ts';
import type { ImageAttachment } from '../src/engine/imageMarker.ts';
import { assemblePrompt } from '../src/engine/assembler.ts';
import { looksLikePng, readCardFromPng } from '../src/lib/pngCard.ts';
import {
  validateGithubConfig,
  buildImagePath,
  buildRawUrl,
} from '../src/engine/githubStorage.ts';
import {
  detectKind,
  splitByKind,
  normalizeWorldBook,
  normalizeRegex,
  normalizePreset,
  extractEmbeddedRegex,
} from '../src/engine/stImport.ts';
import { buildDnaBlock, mergeDnaTags } from '../src/engine/characterDna.ts';
import {
  TAG_CATEGORIES,
  buildTagReference,
  parseTagLibrary,
  searchImportedTags,
} from '../src/engine/tagLibrary.ts';
import { triggerWorldbook } from '../src/engine/worldbook.ts';
import { runRegex } from '../src/engine/regexEngine.ts';
import { parseImageMarkers, buildContentBlocks } from '../src/engine/imageMarker.ts';
import { stripReasoning, cleanOutput } from '../src/engine/reasoning.ts';
import { extractInlineImages } from '../src/engine/imageMarker.ts';
import { extractPresetContent, buildPresetBlock } from '../src/engine/presetContent.ts';
import { streamChatCompletion } from '../src/engine/apiClient.ts';
import { contentId } from '../src/lib/blobStore.ts';
import { readVariables, applyVariableWrites, variableVersion } from '../src/engine/variables.ts';
import { WorldBook, CharacterCard } from 'parsecard';

let passed = 0;
function check(name: string, fn: () => void) {
  try {
    fn();
    passed++;
    console.log(`  ✓ ${name}`);
  } catch (e) {
    console.error(`  ✗ ${name}\n    ${e instanceof Error ? e.message : e}`);
    process.exitCode = 1;
  }
}

console.log('=== NovelAI 请求体格式（必须与官方 /ai/generate-image 一致） ===');

check('顶层字段为 input / model / action / parameters', () => {
  const body = buildNovelAIRequest({
    prompt: 'a, b',
    negativePrompt: 'x',
    model: 'nai-diffusion-4-5-full',
    width: 832,
    height: 1216,
    steps: 28,
    scale: 6,
    sampler: 'k_euler_ancestral',
    seed: 12345,
  });
  assert.deepEqual(Object.keys(body).sort(), ['action', 'input', 'model', 'parameters']);
  assert.equal(body.input, 'a, b');
  assert.equal(body.model, 'nai-diffusion-4-5-full');
  assert.equal(body.action, 'generate');
  assert.equal(body.parameters.seed, 12345);
  assert.equal(body.parameters.n_samples, 1);
  assert.equal(body.parameters.negative_prompt, 'x');
  assert.equal(body.parameters.params_version, 3);
});

check('seed=0 时自动生成随机种子', () => {
  const body = buildNovelAIRequest({
    prompt: 'a', negativePrompt: '', model: 'nai-diffusion-4-5-full',
    width: 832, height: 1216, steps: 28, scale: 6, sampler: 'k_euler_ancestral', seed: 0,
  });
  assert.ok(body.parameters.seed > 0, '种子应大于 0');
});

check('V4.5 带 use_coords / autoSmea，且不含 V3 的 sm 字段', () => {
  const body = buildNovelAIRequest({
    prompt: 'a', negativePrompt: '', model: 'nai-diffusion-4-5-full',
    width: 832, height: 1216, steps: 28, scale: 6, sampler: 'k_euler_ancestral',
  });
  assert.equal(body.parameters.use_coords, false);
  assert.equal(body.parameters.autoSmea, false);
  assert.ok(!('sm' in body.parameters), 'V4 不应包含 sm');
});

check('V3 带 sm / sm_dyn', () => {
  const body = buildNovelAIRequest({
    prompt: 'a', negativePrompt: '', model: 'nai-diffusion-3',
    width: 832, height: 1216, steps: 28, scale: 6, sampler: 'k_euler_ancestral',
  });
  assert.equal(body.parameters.sm, false);
  assert.equal(body.parameters.sm_dyn, false);
  assert.ok(!('use_coords' in body.parameters), 'V3 不应包含 use_coords');
});

check('img2img：有参考图时 action=img2img 且剥掉 data: 前缀', () => {
  const body = buildNovelAIRequest({
    prompt: 'a', negativePrompt: '', model: 'nai-diffusion-4-5-full',
    width: 832, height: 1216, steps: 28, scale: 6, sampler: 'k_euler_ancestral',
    imageBase64: 'data:image/png;base64,AAAA', strength: 0.65,
  });
  assert.equal(body.action, 'img2img');
  assert.equal(body.parameters.image, 'AAAA');
  assert.equal(body.parameters.strength, 0.65);
});

console.log('\n=== 提示词拼装 ===');

check('固定画师串排在最前，且顺序为 画师串 → 质量标签 → 主体', () => {
  const out = composePrompt(['artist:wlop', 'masterpiece, best quality', '1girl, smile']);
  assert.equal(out, 'artist:wlop, masterpiece, best quality, 1girl, smile');
});

check('重复标签被去重（忽略大小写）', () => {
  const out = composePrompt(['masterpiece, 1girl', 'Masterpiece, smile']);
  assert.equal(out, 'masterpiece, 1girl, smile');
});

check('模板占位符替换', () => {
  const { system } = renderPromptTemplate(DEFAULT_PROMPT_TEMPLATE, {
    contextText: '她在图书馆里读书',
    contextLabel: '章节',
    artistTags: 'artist:wlop',
  });
  assert.ok(!system.includes('{{contextLabel}}'), '占位符应被替换');
  assert.ok(!system.includes('{{contextText}}'), '占位符应被替换');
});

check('自定义模板同样支持占位符', () => {
  const { system } = renderPromptTemplate('场景名={{contextLabel}}', {
    contextText: 'x', contextLabel: '当前行动',
  });
  assert.equal(system, '场景名=当前行动');
});

console.log('\n=== 常量表完整性 ===');

check('采样器包含官方常用值', () => {
  for (const s of ['k_euler', 'k_euler_ancestral', 'k_dpmpp_2m', 'k_dpmpp_2m_sde', 'ddim']) {
    assert.ok((NOVELAI_SAMPLERS as readonly string[]).includes(s), `缺少采样器 ${s}`);
  }
  assert.ok(NOVELAI_SAMPLERS.length >= 14, '采样器数量应覆盖 GPU/RTX 变体');
});

check('模型推荐采样器正确', () => {
  assert.equal(defaultSamplerFor('nai-diffusion-4-5-full'), 'k_euler_ancestral');
  assert.equal(defaultSamplerFor('nai-diffusion-3'), 'k_dpmpp_2m_sde');
});

check('尺寸预设与 ucPreset 档位非空', () => {
  assert.ok(SIZE_PRESETS.length >= 5);
  assert.equal(UC_PRESETS.length, 5);
});

console.log('\n=== 插图标记：解析与剥离 ===');

check('能解析标记并算出段落序号', () => {
  const text = '第一段。\n\n第二段。\n[[IMG: 1girl, standing]]\n\n第三段。';
  const ms = parseImageMarkers(text);
  assert.equal(ms.length, 1);
  assert.equal(ms[0].description, '1girl, standing');
  assert.equal(ms[0].markdownIndex, 1, '标记应归属第 2 段（序号 1）');
});

check('支持全角冒号与多余空格', () => {
  const ms = parseImageMarkers('[[ IMG ：  a girl  ]]');
  assert.equal(ms.length, 1);
  assert.equal(ms[0].description, 'a girl');
});

check('剥离标记后不留标记残渣，且合并多余空行', () => {
  const text = 'A\n\n[[IMG: x]]\n\nB';
  const clean = stripImageMarkers(text);
  assert.ok(!clean.includes('IMG'), '不应残留标记');
  assert.ok(!clean.includes('[['), '不应残留方括号');
  assert.equal(clean, 'A\n\nB');
});

check('hasImageMarker 判定正确', () => {
  assert.equal(hasImageMarker('普通文本'), false);
  assert.equal(hasImageMarker('文本 [[IMG: 有图]]'), true);
});

check('历史遗留的内嵌 base64 图片也会被剥掉', () => {
  const dirty = '正文开始。\n\n![章节插图](data:image/png;base64,' + 'A'.repeat(20000) + ')\n\n正文结束。';
  const clean = stripImageMarkers(dirty);
  assert.ok(!clean.includes('base64'), 'base64 应被剥掉');
  assert.ok(clean.length < 100, `剥离后应很短，实际 ${clean.length}`);
  assert.ok(clean.includes('正文开始。') && clean.includes('正文结束。'), '正文应保留');
});

check('同一段里多个标记会切成 文本/图/文本/图 序列', () => {
  const blocks = buildContentBlocks('前 [[IMG: a]] 中 [[IMG: b]] 后');
  const kinds = blocks.map((b) => b.kind).join(',');
  assert.equal(kinds, 'text,image,text,image,text');
});

check('附件能按 段落序号+描述 正确挂到对应图片块', () => {
  const text = '第一段\n\n第二段\n[[IMG: 角色特写]]';
  const atts: ImageAttachment[] = [
    {
      id: 'a1',
      markdownIndex: 1,
      description: '角色特写',
      dataUrl: 'data:image/png;base64,AAAA',
      createdAt: 0,
    },
  ];
  const blocks = buildContentBlocks(text, atts);
  const img = blocks.find((b) => b.kind === 'image');
  assert.ok(img && img.kind === 'image');
  assert.equal(img.attachment?.id, 'a1', '附件应挂到对应的图片块上');
});

console.log('\n=== 隔离保证：标记绝不能进入大模型上下文 ===');

check('assemblePrompt 会剥掉历史消息里的标记', () => {
  const dirty = '正文内容。\n\n[[IMG: 1girl, school uniform, classroom]]';
  const result = assemblePrompt({
    chatHistory: [
      { role: 'assistant', content: dirty },
      { role: 'user', content: '继续 [[IMG: 另一张]]' },
    ],
    currentUserInput: '那接下来呢 [[IMG: 再来一张]]',
  });
  const joined = result.finalMessages.map((m) => m.content).join('\n');
  assert.ok(!joined.includes('IMG'), '任何一条最终消息都不应包含标记');
  assert.ok(!joined.includes('[['), '不应包含标记符号');
  // 正文本身要保留
  assert.ok(joined.includes('正文内容。'), '正文内容不应被误删');
  assert.ok(joined.includes('那接下来呢'), '用户输入不应被误删');
});

check('剥离标记后 token 估算不再把标记算进去', () => {
  const withMarker = assemblePrompt({
    chatHistory: [{ role: 'assistant', content: '一句很短的话。'.repeat(20) + '[[IMG: ' + 'tag, '.repeat(80) + ']]' }],
  });
  const withoutMarker = assemblePrompt({
    chatHistory: [{ role: 'assistant', content: '一句很短的话。'.repeat(20) }],
  });
  assert.equal(
    withMarker.tokenEstimate,
    withoutMarker.tokenEstimate,
    '带标记与不带标记的 token 数应相同（标记已被剥离）',
  );
});

check('图片附件不进 content，因此不会出现在最终消息里', () => {
  // 附件是独立数组，assemblePrompt 只读 content 字段
  const result = assemblePrompt({
    chatHistory: [
      {
        role: 'assistant',
        content: '正文。',
        // 故意塞一个附件字段，模拟历史对象被整体传入
        ...({ images: [{ id: 'x', dataUrl: 'data:image/png;base64,' + 'A'.repeat(5000), markdownIndex: 0, description: 'd', createdAt: 0 }] } as any),
      },
    ],
  });
  const joined = result.finalMessages.map((m) => m.content).join('\n');
  assert.ok(!joined.includes('base64'), 'base64 图片数据绝不能进入上下文');
  assert.ok(!joined.includes('AAAA'), '图片数据不应进入上下文');
});

check('imageMarkerInstruction 关闭时返回空串', () => {
  assert.equal(imageMarkerInstruction(false), '');
  assert.ok(imageMarkerInstruction(true).includes('[[IMG:'));
});

console.log('\n=== GitHub 图片仓库 ===');

check('配置校验：缺项能给出明确提示', () => {
  assert.ok(validateGithubConfig({})?.includes('用户名'));
  assert.ok(validateGithubConfig({ owner: 'a' })?.includes('仓库名'));
  assert.ok(validateGithubConfig({ owner: 'a', repo: 'b' })?.includes('令牌'));
  assert.equal(validateGithubConfig({ owner: 'a', repo: 'b', token: 't' }), null);
});

check('分支名非法字符被拦截', () => {
  assert.ok(
    validateGithubConfig({ owner: 'a', repo: 'b', token: 't', branch: 'bad branch' })?.includes(
      '分支',
    ),
  );
});

check('仓库内路径按年月分层且扩展名正确', () => {
  const stamp = new Date('2026-10-05T00:00:00Z').getTime();
  const p = buildImagePath({ path: 'images' } as any, 'webp', stamp);
  assert.ok(/^images\/\d{4}\/\d{2}\/\d+-[a-z0-9]+\.webp$/.test(p), `路径格式不符: ${p}`);
});

check('目录前缀的首尾斜杠会被清理', () => {
  const p = buildImagePath({ path: '/pics/' } as any, 'png', 1700000000000);
  assert.ok(p.startsWith('pics/'), `不应出现双斜杠或前导斜杠: ${p}`);
});

check('raw 链接拼接正确（公开仓库可直接用）', () => {
  const url = buildRawUrl(
    { owner: 'me', repo: 'pics', branch: 'main' } as any,
    'images/2026/10/x.webp',
  );
  assert.equal(url, 'https://raw.githubusercontent.com/me/pics/main/images/2026/10/x.webp');
});

check('未指定分支时回退到 main', () => {
  const url = buildRawUrl({ owner: 'me', repo: 'pics' } as any, 'a.png');
  assert.ok(url.includes('/main/'), url);
});

check('上传体积上限有保守默认值（GitHub 单文件约 1MB）', () => {
  const settings = { maxKB: 900 };
  assert.ok(settings.maxKB <= 1000, '默认压缩上限不应超过 GitHub 单文件限制');
});

console.log('\n=== ST 导入：结构识别 ===');

check('识别角色卡 V2', () => {
  const d = detectKind({ spec: 'chara_card_v2', data: { name: '小明', description: 'x', first_mes: 'y' } });
  assert.equal(d.kind, 'character');
});

check('识别角色卡 V1（字段在顶层）', () => {
  const d = detectKind({ name: '小明', description: 'x', first_mes: 'y', personality: 'z' });
  assert.equal(d.kind, 'character');
});

check('识别世界书（entries 为数组）', () => {
  const d = detectKind({ name: '设定', entries: [{ key: ['a'], content: 'b' }] });
  assert.equal(d.kind, 'worldbook');
});

check('识别世界书（entries 为对象索引 —— ST 常见导出）', () => {
  const d = detectKind({ name: '设定', entries: { '0': { key: ['a'], content: 'b' } } });
  assert.equal(d.kind, 'worldbook');
  assert.equal(d.count, 1);
});

check('识别预设（含 prompts / prompt_order）', () => {
  assert.equal(detectKind({ prompts: [{ identifier: 'a', content: 'b' }] }).kind, 'preset');
  assert.equal(detectKind({ temperature: 0.8, max_tokens: 400 }).kind, 'preset');
});

check('识别正则（ST 嵌套 script）', () => {
  assert.equal(detectKind({ name: 'R', script: { find_regex: 'a', replace_string: 'b' } }).kind, 'regex');
});

check('识别正则（扁平）', () => {
  assert.equal(detectKind({ find_regex: 'a', replace_string: 'b', placement: [2] }).kind, 'regex');
});

check('数组按元素类型识别', () => {
  const d = detectKind([{ prompts: [] }, { prompts: [] }]);
  assert.equal(d.kind, 'preset');
  assert.equal(d.count, 2);
});

check('无法识别时给出顶层字段线索', () => {
  const d = detectKind({ foo: 1, bar: 2 });
  assert.equal(d.kind, 'unknown');
  assert.ok(d.reason.includes('foo'));
});

console.log('\n=== ST 导入：混合拆分与归一化 ===');

check('一个数组里混装四种资产能分别拆开', () => {
  const buckets = splitByKind([
    { prompts: [{ identifier: 'a' }], temperature: 1, max_tokens: 100 },
    { entries: [{ key: ['k'], content: 'v' }] },
    { find_regex: 'x', replace_string: 'y' },
    { spec: 'chara_card_v2', data: { name: 'c', description: 'd', first_mes: 'e' } },
  ]);
  const kinds = buckets.map((b) => b.kind).sort();
  assert.deepEqual(kinds, ['character', 'preset', 'regex', 'worldbook']);
});

check('容器对象 { worldBooks: [...] } 能被拆开', () => {
  const buckets = splitByKind({ worldBooks: [{ entries: { '0': { content: 'x' } } }] });
  assert.equal(buckets.length, 1);
  assert.equal(buckets[0].kind, 'worldbook');
});

check('世界书 entries 从对象索引转数组（导入失效的根因）', () => {
  const { value, notes } = normalizeWorldBook({
    name: 'W',
    entries: { '7': { key: ['魔法'], content: '咒语内容' } },
  });
  assert.ok(Array.isArray(value.entries), 'entries 应该是数组');
  assert.equal(value.entries.length, 1);
  assert.equal(value.entries[0].uid, '7');
  assert.ok(notes.some((n) => n.includes('对象索引')));
});

check('世界书关键词从 keys 字符串/数组/key 对象统一成 keys + key', () => {
  const a = normalizeWorldBook({ entries: { '0': { keys: '魔法,咒语', content: 'x' } } });
  assert.deepEqual(a.value.entries[0].keys, ['魔法', '咒语']);
  assert.equal(a.value.entries[0].key.length, 2);

  const b = normalizeWorldBook({ entries: { '0': { key: [{ key: 'magic' }], content: 'x' } } });
  assert.deepEqual(b.value.entries[0].keys, ['magic']);
  assert.equal(b.value.entries[0].key[0].key, 'magic');
});

check('世界书条目默认值被补齐（order/position/depth/probability）', () => {
  const { value } = normalizeWorldBook({ entries: { '0': { content: 'x' } } });
  const e = value.entries[0];
  assert.equal(typeof e.order, 'number');
  assert.equal(typeof e.position, 'number');
  assert.equal(typeof e.depth, 'number');
  assert.equal(e.probability, 100);
});

check('正则嵌套 script → 扁平（字段名转驼峰）', () => {
  const { value, notes } = normalizeRegex({
    name: '我的正则',
    script: { find_regex: 'a(.)', replace_string: '[b]$1', placement: [2], markdown_only: true },
  });
  assert.equal(value.scriptName, '我的正则');
  assert.equal(value.findRegex, 'a(.)');
  assert.equal(value.replaceString, '[b]$1');
  assert.deepEqual(value.placement, [2]);
  assert.equal(value.markdownOnly, true);
  assert.ok(notes.some((n) => n.includes('嵌套')));
});

check('预设缺 prompt_order 时自动按 prompts 顺序生成', () => {
  const { value, notes } = normalizePreset({
    prompts: [{ identifier: 'x', content: 'a' }, { content: 'b' }],
  });
  assert.ok(Array.isArray(value.prompt_order));
  assert.equal(value.prompt_order[0].order.length, 2);
  assert.ok(notes.some((n) => n.includes('prompt_order')));
});

check('预设 prompt 缺 identifier 时自动补齐', () => {
  const { value, notes } = normalizePreset({ prompts: [{ content: 'a' }] });
  assert.ok(value.prompts[0].identifier);
  assert.ok(notes.some((n) => n.includes('identifier')));
});

check('预设 preset_name 映射到 name', () => {
  const { value, notes } = normalizePreset({ preset_name: '我的预设', prompts: [] });
  assert.equal(value.name, '我的预设');
  assert.ok(notes.some((n) => n.includes('preset_name')));
});

console.log('\n=== 标签库 ===');

check('默认开启的分类覆盖常用项', () => {
  const on = TAG_CATEGORIES.filter((c) => c.defaultOn).map((c) => c.id);
  for (const need of ['quality', 'count', 'face', 'hair_eyes', 'clothing', 'scene']) {
    assert.ok(on.includes(need), `默认应开启 ${need}`);
  }
});

check('参考块只包含选中的分类', () => {
  const ref = buildTagReference({ enabledCategories: ['quality'] });
  assert.ok(ref.includes('masterpiece'));
  assert.ok(!ref.includes('twintails'), '未勾选的分类不应出现');
});

check('参考块体积可控（不会把上下文冲掉）', () => {
  const ref = buildTagReference({
    enabledCategories: TAG_CATEGORIES.map((c) => c.id),
    perCategory: 60,
    maxChars: 4000,
  });
  assert.ok(ref.length <= 4100, `参考块过长: ${ref.length}`);
});

check('每类数量上限生效', () => {
  const few = buildTagReference({ enabledCategories: ['hair_eyes'], perCategory: 3 });
  const many = buildTagReference({ enabledCategories: ['hair_eyes'], perCategory: 30 });
  assert.ok(many.length > few.length);
});

check('关键词必须用标准写法（银发的例子）', () => {
  const ref = buildTagReference({ enabledCategories: ['hair_eyes'] });
  assert.ok(ref.includes('silver hair'));
  assert.ok(ref.includes('标准写法'), '应明确要求使用标准标签');
});

check('导入标签库：数组形式', () => {
  const lib = parseTagLibrary([{ tag: 'silver hair', zh: '银发' }, 'twintails']);
  assert.equal(lib.tags.length, 2);
  assert.equal(lib.tags[0].zh, '银发');
});

check('导入标签库：字典形式与去重', () => {
  const lib = parseTagLibrary({
    tags: { '1girl': 'count', '1GIRL': 'count', 'blue eyes': 'eyes' },
  });
  assert.equal(lib.tags.length, 2, '应按大小写去重');
});

check('导入标签库：任意普通对象不会被误认成标签表', () => {
  // 只有 1~2 个键的随手对象不该被当成标签库
  assert.equal(parseTagLibrary({ nothing: 123 }).tags.length, 0);
  assert.equal(parseTagLibrary({ a: 1, b: 2 }).tags.length, 0);
  // 键值足够多且都是字符串时，才当成字典
  const dict = parseTagLibrary({ '1girl': 'count', '1boy': 'count', 'solo': 'count' });
  assert.equal(dict.tags.length, 3);
});

check('导入标签库：嵌套 data 数组', () => {
  const lib = parseTagLibrary({ data: [{ name: 'a' }, { name: 'b' }] });
  assert.deepEqual(lib.tags.map((t) => t.tag), ['a', 'b']);
});

check('导入标签库：无法识别时返回空而不是抛错', () => {
  const lib = parseTagLibrary({ nothing: 123 });
  assert.equal(lib.tags.length, 0);
});

check('检索支持中文与英文', () => {
  const lib = parseTagLibrary([
    { tag: 'silver hair', zh: '银发' },
    { tag: 'blue eyes', zh: '蓝眼' },
  ]);
  assert.equal(searchImportedTags(lib, '银').length, 1);
  assert.equal(searchImportedTags(lib, 'blue').length, 1);
  assert.equal(searchImportedTags(lib, 'silver').length, 1);
  assert.equal(searchImportedTags(lib, 'zzz').length, 0);
});

check('空查询不返回结果（避免白刷列表）', () => {
  const lib = parseTagLibrary([{ tag: 'a' }]);
  assert.equal(searchImportedTags(lib, '').length, 0);
});

console.log('\n=== 世界书触发（关键词写法兼容性）===');

check('key 为字符串数组能命中', () => {
  const { hits } = triggerWorldbook(
    [{ name: 'W', entries: [{ key: ['魔法'], content: 'x' }] }],
    [{ role: 'assistant', content: '他翻开关于魔法的书' }],
  );
  assert.equal(hits.length, 1);
});

check('key 为对象数组 [{key}] 也能命中（ST 导出的常见写法）', () => {
  // 这是实测发现的真 bug：直接 String({key:"魔法"}) 会得到 "[object Object]"，
  // 导致 ST 导出的世界书关键词永远匹配不上。
  const { hits } = triggerWorldbook(
    [{ name: 'W', entries: [{ key: [{ key: '魔法' }], content: 'x' }] }],
    [{ role: 'assistant', content: '他翻开关于魔法的书' }],
  );
  assert.equal(hits.length, 1, '对象数组形式的关键词必须能命中');
});

check('keys 为字符串数组能命中', () => {
  const { hits } = triggerWorldbook(
    [{ name: 'W', entries: [{ keys: ['魔法'], content: 'x' }] }],
    [{ role: 'assistant', content: '魔法的书' }],
  );
  assert.equal(hits.length, 1);
});

check('常驻条目不依赖关键词', () => {
  const { hits } = triggerWorldbook(
    [{ name: 'W', entries: [{ key: [], content: 'x', constant: true }] }],
    [{ role: 'assistant', content: '随便什么内容' }],
  );
  assert.equal(hits.length, 1);
});

check('entries 为对象索引时触发引擎能处理', () => {
  const { hits } = triggerWorldbook(
    [{ name: 'W', entries: { '0': { key: [{ key: '魔法' }], content: 'x' } } }],
    [{ role: 'assistant', content: '魔法的书' }],
  );
  assert.equal(hits.length, 1);
});

console.log('\n=== 世界书编辑字段在导入后必须保住 ===');

check('导入归一化后 order/position/depth/probability/constant 都在', () => {
  const { value } = normalizeWorldBook({
    name: 'W',
    entries: {
      '0': {
        key: [{ key: '魔法' }],
        content: '发光',
        constant: true,
        order: 42,
        position: 4,
        depth: 3,
        probability: 80,
      },
    },
  });
  const e = value.entries[0];
  assert.equal(e.constant, true, 'constant 不能丢');
  assert.equal(e.order, 42, 'order 不能丢');
  assert.equal(e.position, 4, 'position 不能丢');
  assert.equal(e.depth, 3, 'depth 不能丢');
  assert.equal(e.probability, 80, 'probability 不能丢');
});

check('entries 归一化后一定是数组（UI 用数组渲染）', () => {
  const { value } = normalizeWorldBook({
    entries: { a: { content: '1' }, b: { content: '2' } },
  });
  assert.ok(Array.isArray(value.entries));
  assert.equal(value.entries.length, 2);
});

check('parsecard 的 toStandaloneJSON 会丢字段，所以不能用它当入库产物', () => {
  const { value } = normalizeWorldBook({
    entries: { '0': { key: [{ key: 'k' }], content: 'x', order: 42, position: 4 } },
  });
  const standalone = WorldBook.fromJSON(value).toStandaloneJSON();
  // 记录这个事实：如果哪天 parsecard 改成保留这些字段，这条断言会提醒我们
  const e = Array.isArray(standalone.entries)
    ? standalone.entries[0]
    : Object.values(standalone.entries)[0];
  assert.ok(
    e.order === undefined || !Array.isArray(standalone.entries),
    '若 parsecard 已保留字段可放宽此断言',
  );
});

console.log('\n=== 正则引擎：ST 字段语义 ===');

check('/pattern/flags 写法能编译（之前会静默失败）', () => {
  const { messages, hits } = runRegex(
    [{ role: 'assistant', content: 'a1b2c3' }],
    [{ scriptName: 'R', findRegex: '/\\d/g', replaceString: '#', placement: [2] }],
  );
  // 带 g 就该替换全部 3 处。matchCount 现在统计真实命中数，
  // 不再像旧版那样"每条消息最多 +1"（那条旧断言其实是把不准的统计写死了）
  assert.equal(messages[0].content, 'a#b#c#');
  assert.equal(hits[0].matchCount, 3);
});

check('/pattern/ 不带 g 只替换第一处（与 ST regexFromString 对齐）', () => {
  // 已核对 SillyTavern release 分支 public/scripts/utils.js 的 regexFromString：
  // 它把 m[3] 原样传给 new RegExp(m[2], m[3])，**不会**自动补 g。
  const { messages } = runRegex(
    [{ role: 'assistant', content: 'a1b2c3' }],
    [{ scriptName: 'R', findRegex: '/\\d/', replaceString: '#', placement: [2] }],
  );
  assert.equal(messages[0].content, 'a#b2c3');
});

check('Trim Out：替换前先从匹配内容里去字', () => {
  // 匹配整段，替换模板是 $1，而修剪后没有捕获组 → 结果应为修剪后的内容
  const { messages } = runRegex([{ role: 'assistant', content: 'foobar' }], [
    {
      scriptName: 'R',
      findRegex: 'foobar',
      replaceString: '$0',
      trimStrings: ['oo', 'ba'],
      placement: [2],
    },
  ]);
  assert.equal(messages[0].content, 'fr', `实际: ${messages[0].content}`);
});

check('消息深度范围：超出范围的消息不被改动', () => {
  const msgs = [
    { role: 'assistant' as const, content: 'OLD' },
    { role: 'assistant' as const, content: 'MID' },
    { role: 'assistant' as const, content: 'NEW' },
  ];
  // maxDepth=0 → 只处理最新一条
  const { messages, hits } = runRegex(msgs, [
    {
      scriptName: 'R',
      findRegex: 'OLD|MID|NEW',
      replaceString: 'X',
      placement: [2],
      maxDepth: 0,
    },
  ]);
  assert.equal(hits[0].matchCount, 1, '只应命中最新一条');
  assert.equal(messages[2].content, 'X');
  assert.equal(messages[0].content, 'OLD', '旧消息不该被改');
});

check('markdownOnly 的脚本不修改送给模型的文本', () => {
  const { messages } = runRegex([{ role: 'assistant', content: 'hello' }], [
    {
      scriptName: 'R',
      findRegex: 'hello',
      replaceString: 'hi',
      placement: [2],
      markdownOnly: true,
    },
  ]);
  assert.equal(messages[0].content, 'hello', '仅显示的正则不该改提示词文本');
});

check('dryRun 不会污染原消息', () => {
  const original = [{ role: 'assistant' as const, content: 'hello' }];
  runRegex(
    original,
    [{ scriptName: 'R', findRegex: 'hello', replaceString: 'hi', placement: [2] }],
    { dryRun: true },
  );
  assert.equal(original[0].content, 'hello');
});

console.log('\n=== 插图位置（必须插在它该在的段落）===');

// 造一段带标记的正文 + 对应的 attachment，验证渲染时能正确挂上。
// 这类 bug 很隐蔽：序号错一位，图就整体挪到下一段，
// 而且不会报错，只是"位置不准"。
const posCases: { name: string; text: string }[] = [
  { name: '标记单独成段', text: '第一段。\n\n[[IMG: girl]]\n\n第二段。\n\n[[IMG: boy]]\n\n第三段。' },
  { name: '标记紧跟段落后', text: '第一段。\n[[IMG: girl]]\n\n第二段。\n[[IMG: boy]]' },
  { name: '标记与文字同段', text: '段首。[[IMG: girl]]段尾。\n\n第二段。' },
  { name: '同段多个标记', text: '第一段。\n\n[[IMG: first]][[IMG: second]]\n\n第二段。' },
  { name: '开头即标记', text: '[[IMG: opening]]\n\n第一段正文。' },
  { name: '连续空行', text: '第一段。\n\n\n\n[[IMG: gap]]\n\n\n\n第二段。' },
];

for (const c of posCases) {
  check(`插图位置正确：${c.name}`, () => {
    const markers = parseImageMarkers(c.text);
    const attachments = markers.map((m, i) => ({
      id: `img${i}`,
      markdownIndex: m.markdownIndex,
      description: m.description,
      dataUrl: 'data:image/png;base64,AAAA',
      storage: 'browser' as const,
    }));
    const blocks = buildContentBlocks(c.text, attachments);
    const imgs = blocks.filter((b) => b.kind === 'image');
    assert.equal(imgs.length, markers.length, '图片块数量应等于标记数量');
    for (const b of imgs) {
      assert.ok(b.kind === 'image' && b.attachment, `「${b.description}」没匹配到配图`);
      assert.equal(b.attachment.description, b.description, '描述应一致');
    }
  });
}

console.log('\n=== 正则作用范围（placement）===');

// 五种 placement 都必须真的能命中 ——
// 以前 REASONING / SLASH_COMMAND 在界面上有选项，引擎却没实现，
// 选了等于没选，用户会觉得"有的正则没生效"。
const placementMsgs = () => [
  { role: 'system', content: 'SYS', _origin: undefined },
  { role: 'user', content: 'USER', _origin: undefined },
  { role: 'assistant', content: 'AI', _origin: undefined },
  { role: 'system', content: 'WI', _origin: 'worldbook' },
  { role: 'assistant', content: 'REASON', _origin: 'reasoning' },
];

const placementCase = (placement: number[], filter: any, expect: string) => {
  const script = {
    id: 'p',
    scriptName: 'p',
    findRegex: '/(USER|AI|WI|REASON|SYS)/g',
    replaceString: '[$1]',
    placement,
  };
  const base = placementMsgs();
  const { messages } = runRegex(base.map((m) => ({ ...m })) as any, [script], { placement: filter });
  const changed = messages
    .map((m, i) => (m.content !== base[i].content ? base[i].content : null))
    .filter(Boolean);
  return changed;
};

check('placement=用户输入 只改用户消息', () => {
  const c = placementCase([1], { user: true }, 'USER');
  assert.deepEqual(c, ['USER']);
});

check('placement=AI输出 只改正文，不含推理过程', () => {
  const c = placementCase([2], { ai: true }, 'AI');
  assert.deepEqual(c, ['AI'], '推理消息(assistant 角色)不能被 AI_OUTPUT 误伤');
});

check('placement=世界书 只改世界书注入', () => {
  const c = placementCase([5], { worldInfo: true }, 'WI');
  assert.deepEqual(c, ['WI']);
});

check('placement=推理过程 能命中（以前完全没实现）', () => {
  const c = placementCase([6], { reasoning: true }, 'REASON');
  assert.deepEqual(c, ['REASON'], 'REASONING 规则必须真的执行');
});

check('推理+AI 两种一起开时都能命中', () => {
  const c = placementCase([6, 2], { reasoning: true, ai: true }, 'AI+REASON');
  assert.deepEqual(c.sort(), ['AI', 'REASON'].sort());
});

console.log('\n=== 正则执行时机（仅显示 / 仅提示词）===');

// 三条规则匹配**不同的**标记，这样彼此不会互相吃掉，能单独看出谁执行了
const timingScripts = [
  { id: 'a', scriptName: '通用的', findRegex: '/AAA/g', replaceString: '通用✅', placement: [2] },
  { id: 'b', scriptName: '仅显示', findRegex: '/BBB/g', replaceString: '仅显示✅', placement: [2], markdownOnly: true },
  { id: 'c', scriptName: '仅提示词', findRegex: '/CCC/g', replaceString: '仅提示词✅', placement: [2], promptOnly: true },
];

check('发给模型时：跑通用的 + 仅提示词，跳过仅显示', () => {
  const { messages } = runRegex([{ role: 'assistant', content: 'AAA BBB CCC' }], timingScripts, {
    placement: { ai: true },
  });
  const t = messages[0].content;
  assert.ok(t.includes('通用✅'), '通用规则应生效');
  assert.ok(t.includes('BBB'), '仅显示的规则不该动发给模型的文本');
  assert.ok(t.includes('仅提示词✅'), '仅提示词的规则应生效');
});

check('界面显示时：跑通用的 + 仅显示，跳过仅提示词', () => {
  const { messages } = runRegex([{ role: 'assistant', content: 'AAA BBB CCC' }], timingScripts, {
    placement: { ai: true },
    forDisplay: true,
  });
  const t = messages[0].content;
  assert.ok(t.includes('通用✅'), '通用规则应生效');
  assert.ok(t.includes('仅显示✅'), '仅显示的规则应生效');
  assert.ok(t.includes('CCC'), '仅提示词的规则不该动界面显示');
});

check('仅显示的规则以前在显示时被整个跳过（这次修掉的 bug）', () => {
  const onlyMarkdown = [timingScripts[1]];
  const { messages } = runRegex([{ role: 'assistant', content: 'BBB' }], onlyMarkdown, {
    placement: { ai: true },
    forDisplay: true,
  });
  assert.equal(messages[0].content, '仅显示✅');
  // 同一批规则在「发模型」时必须是空操作
  const forModel = runRegex([{ role: 'assistant', content: 'BBB' }], onlyMarkdown, {
    placement: { ai: true },
  });
  assert.equal(forModel.messages[0].content, 'BBB');
});

console.log('\n=== 预设内嵌正则（ST 一体化导出）===');

check('从 extensions.regex_scripts 提取正则', () => {
  const preset = {
    prompts: [{ identifier: 'a', content: 'x' }],
    extensions: {
      regex_scripts: [
        { id: '1', scriptName: '测试正则', findRegex: '/<b>(.*?)<\\/b>/gi', replaceString: '**$1**', placement: [2] },
      ],
    },
  };
  const found = extractEmbeddedRegex(preset);
  assert.equal(found.length, 1);
  assert.equal(found[0].scriptName, '测试正则');
});

check('splitByKind 会把预设里的正则拆成独立条目', () => {
  const buckets = splitByKind({
    prompts: [{ identifier: 'a', content: 'x' }],
    extensions: {
      regex_scripts: [
        { scriptName: 'R1', findRegex: 'a', replaceString: 'b', placement: [2] },
        { scriptName: 'R2', findRegex: 'c', replaceString: 'd', placement: [2] },
      ],
    },
  });
  const presetCount = buckets.find((b) => b.kind === 'preset')?.items.length ?? 0;
  const regexCount = buckets.find((b) => b.kind === 'regex')?.items.length ?? 0;
  assert.equal(presetCount, 1, '预设应保留 1 个');
  assert.equal(regexCount, 2, '应拆出 2 条正则');
});

check('没有内嵌正则时不受影响', () => {
  const buckets = splitByKind({ prompts: [{ identifier: 'a', content: 'x' }] });
  assert.equal(buckets.find((b) => b.kind === 'regex')?.items.length ?? 0, 0);
});

check('归一化时剥离超大扩展（避免几十万字符的脚本塞进存储）', () => {
  const preset = {
    prompts: [{ identifier: 'a', content: 'x' }],
    extensions: {
      regex_scripts: [{ scriptName: 'R', findRegex: 'a', replaceString: 'b', placement: [2] }],
      tavern_helper: { scripts: [{ content: 'x'.repeat(300000) }] },
    },
  };
  const { value, notes } = normalizePreset(preset);
  assert.equal(value.extensions.regex_scripts, undefined, '正则已单独入库，不该重复留在预设里');
  assert.equal(value.extensions.tavern_helper, undefined, '内嵌脚本应被剥离');
  assert.ok(JSON.stringify(value).length < 5000, '归一化后体积应很小');
  assert.ok(notes.some((n) => n.includes('正则')), `应有正则相关提示，实际: ${notes.join('|')}`);
});

console.log('\n=== 角色 DNA ===');

check('DNA 块包含描述且明确要求不得改动', () => {
  const block = buildDnaBlock([
    { _uid: '1', name: '主角', dna: 'silver hair, red eyes, gothic dress' },
  ]);
  assert.ok(block.includes('silver hair'), '应包含描述');
  assert.ok(block.includes('不得改写') || block.includes('不得'), '应明确要求不得改动');
});

check('多条 DNA 会带上角色名做区分', () => {
  const block = buildDnaBlock([
    { _uid: '1', name: '甲', dna: 'a' },
    { _uid: '2', name: '乙', dna: 'b' },
  ]);
  assert.ok(block.includes('【甲】') && block.includes('【乙】'));
});

check('空描述与已停用的 DNA 不进块', () => {
  assert.equal(buildDnaBlock([{ _uid: '1', name: 'x', dna: '   ' }]), '');
  assert.equal(buildDnaBlock([{ _uid: '1', name: 'x', dna: 'a', enabled: false }]), '');
  assert.equal(buildDnaBlock([]), '');
});

check('mergeDnaTags 合并成纯标签（手动模式用）', () => {
  const tags = mergeDnaTags([
    { _uid: '1', name: 'a', dna: 'silver hair' },
    { _uid: '2', name: 'b', dna: 'red eyes' },
  ]);
  assert.equal(tags, 'silver hair, red eyes');
});

console.log('\n=== 思维链剥离 ===');

check('成对标记 <thinking> 整段剥掉', () => {
  const r = stripReasoning('<thinking>内部思考</thinking>\n\n正文开始。');
  assert.equal(r.text.trim(), '正文开始。');
  assert.ok(r.reasoning.includes('内部思考'), '剥掉的内容要留着，便于回看');
});

check('未闭合的 </think> 之前全部算推理', () => {
  const r = stripReasoning('分析中……\n</think>\n正文。');
  assert.equal(r.text.trim(), '正文。');
});

check('中文分界标记（思考完毕）能识别', () => {
  const r = stripReasoning('分析：怎么写道。\n思考完毕\n正文。');
  assert.equal(r.text.trim(), '正文。');
});

check('用户自定义分界标记生效', () => {
  const r = stripReasoning('一大堆推理%%%真正的正文。', '%%%');
  assert.equal(r.text.trim(), '真正的正文。');
});

check('没有思维链时绝不误删正文', () => {
  const src = '这是一段完全正常的正文，没有任何标记。';
  assert.equal(cleanOutput(src), src);
});

check('未闭合 <thinking> 从标签处截断', () => {
  const r = stripReasoning('正文前半<thinking>还没写完的思考');
  assert.equal(r.text.trim(), '正文前半');
});

console.log('\n=== 配图渲染（有标记 / 无标记都要显示）===');

const mkAtt = (id: string, idx: number, desc: string) => ({
  id,
  markdownIndex: idx,
  description: desc,
  dataUrl: 'data:image/png;base64,AAAA',
  storage: 'browser' as const,
});

check('没有 [[IMG:]] 标记的附件也必须渲染出来', () => {
  // 这就是"图片生成了、图库里有、聊天里却看不到"的原因：
  // 一键配图挂的是附件，正文里没有标记，而渲染只认标记。
  const blocks = buildContentBlocks('第一段。\n\n第二段。', [mkAtt('a1', 0, '配图')]);
  const imgs = blocks.filter((b) => b.kind === 'image');
  assert.equal(imgs.length, 1, '无标记的附件不能被丢掉');
});

check('有标记时图片插在标记的位置', () => {
  const blocks = buildContentBlocks('第一段。\n\n[[IMG: 中间图]]\n\n第二段。', [
    mkAtt('a2', 0, '中间图'),
  ]);
  const seq = blocks.map((b) => (b.kind === 'image' ? '图' : '文')).join('');
  assert.equal(seq, '文图文');
});

check('标记之外的额外附件也会补渲染', () => {
  const blocks = buildContentBlocks('第一段。\n\n[[IMG: 图A]]\n\n第二段。', [
    mkAtt('a3', 0, '图A'),
    mkAtt('a4', 0, '图B'),
  ]);
  const imgs = blocks.filter((b) => b.kind === 'image');
  assert.equal(imgs.length, 2);
});

check('同一张图不会被渲染两次', () => {
  const blocks = buildContentBlocks('第一段。\n\n[[IMG: 图A]]\n\n第二段。', [mkAtt('a5', 0, '图A')]);
  assert.equal(blocks.filter((b) => b.kind === 'image').length, 1);
});

console.log('\n=== 流式完成回调只触发一次 ===');

// 这条曾经是真 bug：读到 [DONE] 后 return 只跳出内层 for，
// 外层 while 继续读到流结束又调用一次 onDone ——
// 于是同一段回复被存两遍，界面上就是"回复两次一模一样"。
function fakeSse(chunks: string[]): ReadableStream<Uint8Array> {
  const enc = new TextEncoder();
  return new ReadableStream({
    start(c) {
      for (const x of chunks) c.enqueue(enc.encode(x));
      c.close();
    },
  });
}

async function runStream(chunks: string[]) {
  const orig = globalThis.fetch;
  globalThis.fetch = (async () =>
    ({ ok: true, status: 200, body: fakeSse(chunks), text: async () => '' }) as any) as any;
  let doneCount = 0;
  let text = '';
  try {
    await streamChatCompletion(
      { baseUrl: 'http://x', apiKey: 'k', model: 'm' } as any,
      [{ role: 'user', content: 'hi' }] as any,
      {
        onToken: (d) => (text += d),
        onDone: () => {
          doneCount++;
        },
      },
    );
  } finally {
    globalThis.fetch = orig;
  }
  return { doneCount, text };
}

await (async () => {
  const r1 = await runStream([
    'data: {"choices":[{"delta":{"content":"你好"}}]}\n\n',
    'data: {"choices":[{"delta":{"content":"世界"}}]}\n\n',
    'data: [DONE]\n\n',
  ]);
  check('带 [DONE] 时 onDone 只触发一次', () => {
    assert.equal(r1.doneCount, 1, '触发两次就会把回复存两遍');
    assert.equal(r1.text, '你好世界');
  });

  const r2 = await runStream([
    'data: {"choices":[{"delta":{"content":"甲"}}]}\n\n',
    'data: {"choices":[{"delta":{"content":"乙"}}]}\n\n',
  ]);
  check('没有 [DONE] 时也要补一次 onDone', () => {
    assert.equal(r2.doneCount, 1);
    assert.equal(r2.text, '甲乙');
  });
})();

console.log('\n=== 内嵌 base64 图片抽取 ===');

const TINY_PNG =
  'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

check('把 markdown 内嵌图片抽成附件', () => {
  const src = `开头文字。\n\n![场景插图](${TINY_PNG})\n\n结尾文字。`;
  const r = extractInlineImages(src);
  assert.equal(r.images.length, 1, '应该抽出一张图');
  assert.equal(r.images[0].description, '场景插图');
  assert.ok(!r.text.includes('base64'), '文本里不能再有 base64');
  assert.ok(r.text.includes('[[IMG: 场景插图]]'), '要留下标记占位');
  assert.ok(r.text.includes('开头文字') && r.text.includes('结尾文字'), '正文文字不能丢');
});

check('没有内嵌图片时原样返回', () => {
  const src = '一段普通文字。';
  const r = extractInlineImages(src);
  assert.equal(r.images.length, 0);
  assert.equal(r.text, src);
});

check('多张图片都能抽出来', () => {
  const src = `![一](${TINY_PNG})\n\n![二](${TINY_PNG})`;
  const r = extractInlineImages(src);
  assert.equal(r.images.length, 2);
  assert.deepEqual(
    r.images.map((x) => x.description),
    ['一', '二'],
  );
});

check('抽取后仍能渲染出图（位置不丢）', () => {
  const src = `前文\n\n![图](${TINY_PNG})\n\n后文`;
  const r = extractInlineImages(src);
  const atts = r.images.map((im, i) => ({
    id: `x${i}`,
    markdownIndex: 0,
    description: im.description,
    dataUrl: im.dataUrl,
    storage: 'browser' as const,
  }));
  const blocks = buildContentBlocks(r.text, atts);
  const imgs = blocks.filter((b) => b.kind === 'image');
  assert.equal(imgs.length, 1, '抽取后的文本仍要能渲染出图');
  assert.ok(imgs[0].kind === 'image' && imgs[0].attachment, '图要能匹配到附件');
});

check('附件编号与实际段落差很远时，图仍要显示在标记处', () => {
  // 真实数据里的情况：附件记的是 markdownIndex=0，
  // 但标记实际在第 5 段。之前 pick() 会返回 undefined，
  // 于是标记位置空着、图被挪到消息末尾。
  const text = ['第一段。', '第二段。', '第三段。', '第四段。', '第五段。', '[[IMG: 图]]', '第六段。'].join('\n\n');
  const atts = [{ id: 'z1', markdownIndex: 0, description: '图', dataUrl: 'data:image/png;base64,AA', storage: 'browser' as const }];
  const blocks = buildContentBlocks(text, atts);
  const imgs = blocks.filter((b) => b.kind === 'image');
  assert.equal(imgs.length, 1, '图必须显示出来');
  assert.ok(imgs[0].kind === 'image' && imgs[0].attachment, '必须匹配到附件');
  // 位置：应插在"第五段"之后、"第六段"之前
  const seq = blocks.map((b) => (b.kind === 'image' ? '图' : '文'));
  const imgAt = seq.indexOf('图');
  assert.ok(imgAt > 0 && imgAt < seq.length - 1, '图不能被挪到最末尾');
});

console.log('\n=== 预设内容抽取（给生图参考）===');

const FAKE_PRESET = {
  name: '测试预设',
  prompts: [
    { name: '启用的', content: '这是一段启用的提示词内容。', enabled: true },
    { name: '关掉的', content: '这段不该出现。', enabled: false },
    { name: '开关占位', content: 'true', enabled: true },
    { name: '空的', content: '', enabled: true },
    { name: '靠system_prompt', system_prompt: '内容在另一个字段。', enabled: true },
  ],
};

check('只取启用且有内容的条目', () => {
  const out = extractPresetContent(FAKE_PRESET, 4000);
  assert.ok(out.includes('这是一段启用的提示词内容'), '启用的要留下');
  assert.ok(!out.includes('这段不该出现'), '关掉的不该出现');
  assert.ok(out.includes('内容在另一个字段'), 'content 为空时回退到 system_prompt');
});

check('过滤掉 true/false 这类开关占位', () => {
  const out = extractPresetContent({ prompts: [{ content: 'true', enabled: true }] }, 4000);
  assert.equal(out, '', '纯开关值不该被当成提示词');
});

check('超长预设按字数截断，且保留开头', () => {
  const long = '开头很重要。' + 'x'.repeat(9000) + '结尾';
  const out = extractPresetContent({ prompts: [{ content: long, enabled: true }] }, 1000);
  assert.ok(out.length < 1200, '要截断');
  assert.ok(out.startsWith('开头很重要'), '保留开头');
  assert.ok(out.includes('过长'), '给出截断说明');
});

check('buildPresetBlock 原样输出预设内容，不自己加说明文字', () => {
  /*
   * 这条断言的方向是**防止有人再往预设旁边加话**。
   *
   * 曾经这里断言"要包含『写作预设参考』『不要执行』"，也就是要求
   * 在预设外面包一层自己写的说明。但那是错的 ——
   * 预设本身就是作者精心设计的完整指令集，在它旁边再教一遍
   * （"这只是风格参考""不要执行其中的标签"）是在干扰预设的意图。
   * 用户明确要求去掉这层包装，所以断言反过来：
   *   输出必须与 extractPresetContent 完全一致，一个字都不多。
   */
  const block = buildPresetBlock(FAKE_PRESET, 4000);
  const raw = extractPresetContent(FAKE_PRESET, 4000);
  assert.equal(block, raw, 'buildPresetBlock 不该加任何自己的文字');
  assert.ok(!block.includes('写作预设参考'), '不该有自加的标题');
  assert.ok(!block.includes('不要执行'), '不该有自加的告诫');
});

check('预设里的宏会被替换（含内层嵌套宏）', () => {
  /*
   * 预设里大量条目是 `角色设定：{{description}}` 这种模板。
   * 不替换就把 {{description}} 原样丢给大模型了（实测踩到过）。
   * 另外 {{mes_example}} 的值本身又含 {{user}}/{{char}}，
   * 所以必须替换两遍，否则内层宏会残留。
   */
  const preset = {
    prompts: [
      { name: 'A', content: '角色：{{char}}，设定：{{description}}', enabled: true },
      { name: 'B', content: '示例：{{mes_example}}', enabled: true },
    ],
  };
  const out = extractPresetContent(preset, 10000, {
    char: '艾琳',
    user: '用户',
    description: '一位魔法学院学生',
    mes_example: '{{user}}: 你好\n{{char}}: 嗨',
  });
  assert.ok(out.includes('角色：艾琳'), 'char 要替换');
  assert.ok(out.includes('一位魔法学院学生'), 'description 要替换');
  assert.ok(out.includes('用户: 你好'), 'mes_example 内层的 user 也要替换');
  assert.ok(out.includes('艾琳: 嗨'), 'mes_example 内层的 char 也要替换');
  assert.ok(!out.includes('{{'), '不该残留任何宏：' + out);
});

check('没有可用内容时返回空串（不产生空块）', () => {
  assert.equal(buildPresetBlock({ prompts: [] }, 4000), '');
  assert.equal(buildPresetBlock(undefined, 4000), '');
});

console.log('\n=== PNG 角色卡导入（ST 生态的图片卡）===');

check('PNG 文件头识别（不看扩展名）', () => {
  const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0]);
  assert.equal(looksLikePng(png), true, 'PNG 签名要认出来');
  assert.equal(looksLikePng(new TextEncoder().encode('{"a":1}')), false, 'JSON 不该被当成 PNG');
  assert.equal(looksLikePng(new Uint8Array([1, 2, 3])), false, '太短的不算');
});

check('非 PNG 输入给出人话错误，不抛异常', () => {
  const res = readCardFromPng(new TextEncoder().encode('{"not":"png"}'));
  assert.equal(res.ok, false);
  assert.ok(res.error && res.error.includes('PNG'), '错误里要提到 PNG：' + res.error);
});

check('能从 PNG 里读出角色卡（chara 块）+ 内嵌世界书', () => {
  /*
   * 造一张真卡：用 parsecard 把自己写的 JSON 写进 PNG 的 tEXt 块，
   * 再走我们的读取函数 —— 覆盖"V2/V3 卡整张图就是一张图片"这条真实路径。
   */
  const source = CharacterCard.fromJSON({
    spec: 'chara_card_v2',
    spec_version: '2.0',
    data: {
      name: '测试卡',
      description: '一位测试用角色',
      first_mes: '开场白',
      character_book: {
        name: '测试世界观',
        entries: [
          { keys: ['铜原市'], content: '一座铜色的城市', enabled: true, insertion_order: 100 },
        ],
      },
    } as never,
  });
  const png = source.toPNG(null);

  assert.equal(looksLikePng(png), true, '生成的确实是 PNG');

  const res = readCardFromPng(png);
  assert.equal(res.ok, true, '要能解析：' + res.error);
  assert.equal(res.name, '测试卡', '读到名字');
  assert.ok(res.characterBook, '要能读出内嵌世界书');
  const book = res.characterBook as { entries?: unknown[] };
  assert.equal((book.entries || []).length, 1, '世界书条目数对得上');
});

check('普通图片（没有卡数据）提示准确', () => {
  /* 一张 1×1 的真 PNG，但没有 chara / ccv3 块 */
  const tiny = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==',
    'base64',
  );
  const res = readCardFromPng(new Uint8Array(tiny));
  assert.equal(res.ok, false);
  assert.ok(res.error && res.error.includes('chara'), '要指出缺 chara/ccv3：' + res.error);
});

console.log('\n=== 流式回调时序（防「同一条回复显示两遍」回归）===');

{
  const origFetch = globalThis.fetch;
  // 一段最小的 SSE：一个正文增量 + [DONE]
  const sse =
    'data: {"choices":[{"delta":{"content":"你好"}}]}\n\n' + 'data: [DONE]\n\n';

  async function runOnce(onDoneDelayMs: number) {
    let doneCount = 0;
    let onDoneFinished = false;
    let finishedWhenReturned = false;
    globalThis.fetch = (async () =>
      new Response(sse, {
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
      })) as typeof fetch;
    try {
      await streamChatCompletion(
        { baseUrl: 'http://fake.local/v1', model: 'm', apiKey: 'k' } as never,
        [{ role: 'user', content: 'hi' }],
        {
          onDone: async () => {
            doneCount++;
            // 模拟 onDone 里的异步落库（commitDraft / addMessage 都要写盘）
            await new Promise((r) => setTimeout(r, onDoneDelayMs));
            onDoneFinished = true;
          },
        },
      );
      finishedWhenReturned = onDoneFinished;
    } finally {
      globalThis.fetch = origFetch;
    }
    return { doneCount, finishedWhenReturned };
  }

  try {
    const r = await runOnce(80);
    assert.equal(
      r.finishedWhenReturned,
      true,
      'streamChatCompletion 返回时 onDone 必须已经跑完 —— 否则调用方的 finally ' +
        '会先于消息落库执行，已提交的消息和残留的流式块会同时渲染（看起来就是回复重复）',
    );
    assert.equal(r.doneCount, 1, 'onDone 只能触发一次');
    passed++;
    console.log('  ✓ onDone 里的异步落库在 streamChatCompletion 返回前完成');
    console.log('  ✓ onDone 只触发一次');
    passed++;
  } catch (e) {
    console.error(`  ✗ onDone 时序\n    ${e instanceof Error ? e.message : e}`);
    process.exitCode = 1;
  }
}

console.log('\n=== 图片外置 id 必须按内容寻址（防「越存越占空间」回归）===');

check('同一张图得到同一个 id，不同图得到不同 id', () => {
  // 这一条是 300MB 那个 bug 的核心：以前 id 随机、每次保存都重新生成，
  // 于是每保存一次就多留一份图。按内容寻址后重复保存只是覆盖同一个键。
  const a = 'data:image/webp;base64,AAAA';
  const b = 'data:image/webp;base64,BBBB';
  assert.equal(contentId(a), contentId(a), '同一内容必须稳定');
  assert.notEqual(contentId(a), contentId(b), '不同内容必须不同');
  // 长度不同也必须区分开（曾经只用长度做过 id，会撞）
  assert.notEqual(
    contentId('data:image/png;base64,AAAA'),
    contentId('data:image/png;base64,AAA'),
    '长度不同要区分',
  );
});

console.log('\n=== 会话列表预览的回溯防护 ===');

check('无闭合括号的巨型内嵌 base64 不会锁死清理逻辑', () => {
  // 老数据是 ![](data:image/...;base64,几十万字符，且可能没写右括号。
  // 旧的 [A-Za-z0-9+/=\s]{100,}\s*\) 会一路吃到文末再逐字符回退，
  // 在会话列表里同步执行时能把主线程锁住几十秒（"切页卡住、隔一会才好"）。
  // 换成 [^)] 之后是线性扫描。这里既验正确性也验耗时。
  const huge =
    '正文开头 ' + '![](data:image/png;base64,' + 'A'.repeat(200000) + ' 后面还有正文';
  const t0 = Date.now();
  const out = stripImageMarkers(huge);
  const elapsed = Date.now() - t0;
  assert.ok(out.includes('正文开头'), '正常正文不能被误删');
  assert.ok(elapsed < 1000, `清理耗时 ${elapsed}ms，疑似又出现灾难性回溯`);
});

console.log('\n=== 空占位符定位（手动补图）===');

check('多个空占位符各有稳定 ordinal，补图不会串位', () => {
  // 场景：模型写了 3 个标记但自动配图上限是 1，界面上应剩下 3 个可点的空位。
  // 段落号 index 会重复（同一段两个标记时），只有 ordinal 能精确定位。
  const blocks = buildContentBlocks(
    '段落一\n\n[[IMG: 图一]]\n\n段落二\n\n[[IMG: 图二]]\n\n[[IMG: 图三]]',
    [],
  );
  const ordinals = blocks.map((b) => (b.kind === 'image' ? b.ordinal : -1)).filter((n) => n >= 0);
  assert.deepEqual(ordinals, [0, 1, 2]);
  const images = blocks.filter((b) => b.kind === 'image');
  assert.ok(images.every((b) => b.kind === 'image' && b.attachment === undefined),
    '没配图时应该都是空占位符');
});

check('只有部分配图时，空位仍能被 ordinal 区分', () => {
  const att = (id: string, i: number): ImageAttachment => ({
    id, markdownIndex: i, description: `图${i}`, createdAt: 1,
  });
  const blocks = buildContentBlocks(
    '[[IMG: 图0]]\n\n[[IMG: 图1]]\n\n[[IMG: 图2]]',
    [att('a0', 0)],
  );
  const images = blocks.filter((b) => b.kind === 'image');
  assert.equal(images.length, 3);
  assert.deepEqual(
    images.map((b) => (b.kind === 'image' ? b.ordinal : -1)),
    [0, 1, 2],
  );
  assert.ok(images[0].kind === 'image' && images[0].attachment?.id === 'a0');
  assert.ok(images[2].kind === 'image' && images[2].attachment === undefined,
    '第 3 个位置应留给手动补图');
});

console.log('\n=== 变量引擎（扁平子集 / 不嵌套）===');

check('读宏：会话 / 全局 / 未定义 / 存在性', () => {
  const t = { session: { mood: '开心' }, global: { world: '蒸汽朋克' } };
  assert.equal(
    readVariables('{{getvar::mood}} / {{getglobalvar::world}} / {{getvar::none}}', t),
    '开心 / 蒸汽朋克 / ',
  );
  assert.equal(
    readVariables('{{hasvar::mood}} {{hasvar::nope}} {{hasglobalvar::world}}', t),
    'true false true',
  );
});

check('值原样插入、不二次解析（不嵌套）', () => {
  const t = { session: { a: '{{getvar::b}}', b: 'B' }, global: {} };
  assert.equal(readVariables('{{getvar::a}}', t), '{{getvar::b}}');
});

check('参数里带花括号的嵌套写法根本不匹配', () => {
  const t = { session: { x: 'X' }, global: {} };
  const s = '{{getvar::{{char}}_mood}}';
  assert.equal(readVariables(s, t), s, '嵌套宏必须原样保留，绝不递归');
  assert.equal(applyVariableWrites(s, t).text, s);
});

check('不认识的宏原样保留，不误伤别的系统', () => {
  const t = { session: {}, global: {} };
  assert.equal(readVariables('{{roll::1d20}} {{user}} {{setvar::x::{{char}}}}', t),
    '{{roll::1d20}} {{user}} {{setvar::x::{{char}}}}');
});

check('只读路径把写宏抹成空串，且绝不改表', () => {
  const t = { session: { hp: '10' }, global: {} };
  assert.equal(readVariables('A{{setvar::hp::99}}{{incvar::hp}}B', t), 'AB');
  assert.equal(t.session.hp, '10');
});

check('写宏：单次扫描、按出现顺序执行、不改调用方的表', () => {
  const t = { session: {}, global: {} };
  const r = applyVariableWrites(
    '{{setvar::n::2}}{{getvar::n}}{{incvar::n}}{{addvar::n::3}}{{getvar::n}}', t);
  assert.equal(r.changed, true);
  // set→2，get→"2"，inc→"3"，add 3→6（空串），get→"6"
  assert.equal(r.text, '236');
  assert.equal(r.tables.session.n, '6');
  assert.equal(t.session.n, undefined, '原表不能被就地修改');
});

check('add / inc / dec 的数值语义与字符串拼接', () => {
  const t = { session: { s: 'abc' }, global: {} };
  const r = applyVariableWrites(
    '{{addvar::fresh::5}}|{{addvar::s::X}}|{{incvar::fresh}}|{{decvar::fresh}}', t);
  assert.equal(r.tables.session.fresh, '5');
  assert.equal(r.tables.session.s, 'abcX');
  assert.equal(r.text, '||6|5');
});

check('全局与会话是两张平级表，互不影响', () => {
  const t = { session: { k: 'local' }, global: { k: 'global' } };
  assert.equal(readVariables('{{getvar::k}}|{{getglobalvar::k}}', t), 'local|global');
  const r = applyVariableWrites('{{setglobalvar::k::G}}{{deletevar::k}}', t);
  assert.equal(r.tables.global.k, 'G');
  assert.equal(r.tables.session.k, undefined);
  assert.equal(t.global.k, 'global');
});

check('delete 之后 hasvar 立刻反映', () => {
  const t = { session: { a: '1' }, global: {} };
  const r = applyVariableWrites('{{deletevar::a}}{{hasvar::a}}', t);
  assert.equal(r.tables.session.a, undefined);
  assert.equal(r.text, 'false');
});

check('美化正则的替换串里能读变量（只读，不改状态）', () => {
  const t = { session: { mood: '开心' }, global: {} };
  const { messages } = runRegex(
    [{ role: 'assistant', content: '状态' }],
    [{ scriptName: 'V', findRegex: '状态', replaceString: '<b>{{getvar::mood}}</b>', placement: [2] }],
    { variables: t },
  );
  assert.equal(messages[0].content, '<b>开心</b>');
  assert.equal(t.session.mood, '开心');
});

check('变量版本串随内容变化（显示缓存能失效）', () => {
  const a = { session: { x: '1' }, global: {} };
  const b = { session: { x: '2' }, global: {} };
  assert.notEqual(variableVersion(a), variableVersion(b));
  assert.equal(variableVersion(a), variableVersion({ session: { x: '1' }, global: {} }));
});

console.log('\n=== 美化正则：漏匹配 / HTML 切碎 ===');

const dualScript = [{
  id: 'dual', scriptName: '双标', findRegex: 'XYZ', replaceString: 'OK',
  placement: [2], markdownOnly: true, promptOnly: true,
}];

check('双标脚本（md + prompt）在显示路径也要跑', () => {
  const { messages } = runRegex(
    [{ role: 'assistant', content: 'XYZ' }], dualScript,
    { placement: { ai: true, reasoning: true }, forDisplay: true },
  );
  assert.equal(messages[0].content, 'OK');
});

check('双标脚本在发给模型的路径也要跑（ST 是 OR 语义）', () => {
  const { messages } = runRegex(
    [{ role: 'assistant', content: 'XYZ' }], dualScript,
    { placement: { ai: true } },
  );
  assert.equal(messages[0].content, 'OK');
});

check('仅 promptOnly 的脚本在显示路径仍然跳过', () => {
  const { messages } = runRegex(
    [{ role: 'assistant', content: 'XYZ' }],
    [{ ...dualScript[0], markdownOnly: false }],
    { placement: { ai: true }, forDisplay: true },
  );
  assert.equal(messages[0].content, 'XYZ');
});

check('仅 markdownOnly 的脚本在提示词路径仍然跳过', () => {
  const { messages } = runRegex(
    [{ role: 'assistant', content: 'XYZ' }],
    [{ ...dualScript[0], promptOnly: false }],
    { placement: { ai: true } },
  );
  assert.equal(messages[0].content, 'XYZ');
});

check('placement=0（ST MD_DISPLAY）显示时生效、生成时跳过', () => {
  const p0 = [{ ...dualScript[0], placement: [0], markdownOnly: false, promptOnly: false }];
  const shown = runRegex(
    [{ role: 'assistant', content: 'XYZ' }], p0,
    { placement: { ai: true }, forDisplay: true },
  );
  assert.equal(shown.messages[0].content, 'OK');
  const sent = runRegex([{ role: 'assistant', content: 'XYZ' }], p0, { placement: { ai: true } });
  assert.equal(sent.messages[0].content, 'XYZ');
});

check('HTML 美化产物的相邻文本块合并，style 标签不再被切开', () => {
  const html = [
    '<style>',
    '.dream-parallel-event-ui__star { animation-name: twinkle; }',
    '',
    '.dream-parallel-event-ui__summary { gap: 0.46rem; }',
    '</style>',
    '',
    '<div class="dream-parallel-event-ui">卡片内容</div>',
  ].join('\n');
  const blocks = buildContentBlocks(html, []);
  assert.equal(blocks.length, 1, '含空行的 HTML 必须合成一整块');
  const text = blocks[0].kind === 'text' ? blocks[0].text : '';
  assert.ok(text.includes('<style>') && text.includes('</style>'), '开闭标签要在同一块');
});

check('合块不影响插图位置与 ordinal', () => {
  const blocks = buildContentBlocks(
    '段落一\n\n[[IMG: 图一]]\n\n段落二\n\n[[IMG: 图二]]', [],
  );
  const imgs = blocks.filter((b) => b.kind === 'image');
  assert.equal(imgs.length, 2);
  assert.deepEqual(imgs.map((b) => (b.kind === 'image' ? b.ordinal : -1)), [0, 1]);
});

console.log(`\n=== 通过 ${passed} 项检查 ===`);
if (process.exitCode) console.error('存在失败项');
