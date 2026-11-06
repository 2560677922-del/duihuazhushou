// 正则兼容性自检：给下一个接手的说明
// ---------------------------------------------------------------------------
// 目的：导入一套 SillyTavern 正则之后，逐条确认「它到底会不会被我们的引擎执行」。
//
// 用法：
//   npx tsx scripts/regex-compat.mts                      # 用 data/app-data.json 里所有来源的正则组
//   npx tsx scripts/regex-compat.mts 我的正则.json        # 指定 ST 导出的正则组 / 脚本数组
//   npx tsx scripts/regex-compat.mts 我的正则.json 样本.txt
//
// 报告里最该看的是「完全静默」一栏：那些脚本在显示和生成两条路径都没跑，
// 表现就是用户说的「有的正则没匹配上」。常见原因：
//   · placement 不被支持（ST 的 0/4 等旧值）
//   · markdownOnly / promptOnly 的时机筛选
//   · minDepth / maxDepth 与消息深度对不上
//   · findRegex 编译失败（非法 flag、老引擎不支持 lookbehind 等）
//
// 这是**只读**工具，不改任何数据、不连网络。
import { readFileSync, existsSync } from 'node:fs';
import { runRegex } from '../src/engine/regexEngine.ts';

/** 覆盖 ST 生态常见标记的样本文本。命中数只用来判断"规则跑没跑"，不追求语义正确 */
const DEFAULT_SAMPLE = [
  '开场描述一段。',
  '<think>内部 <b>粗体</b> 思考</think>',
  '<disclaimer>免责声明</disclaimer>',
  '<Reference_Example_format>示例格式</Reference_Example_format>',
  '<summary>这是摘要</summary>',
  '<details><summary>已折叠摘要</summary></details>',
  '<SUOT>行动选项 A</SUOT>',
  '<style>.dream-parallel-event-ui__star { color: red; }</style>',
  '镬',
  '结尾一段。',
].join('\n\n');

type AnyScript = Record<string, any>;

/** 兼容 ST 的扁平格式、{script:{...}} 嵌套格式、以及 snake_case 字段 */
function scriptOf(s: AnyScript): AnyScript {
  const i = s?.script ? { ...s.script, id: s.script.id ?? s.id, scriptName: s.script.scriptName ?? s.name } : s;
  return {
    id: i.id,
    scriptName: i.scriptName ?? i.name ?? '(未命名)',
    findRegex: i.find_regex ?? i.findRegex ?? '',
    replaceString: i.replace_string ?? i.replaceString ?? '',
    trimStrings: i.trim_strings ?? i.trimStrings ?? [],
    placement: i.placement ?? [],
    disabled: i.disabled ?? i.isDisabled ?? false,
    markdownOnly: i.markdown_only ?? i.markdownOnly ?? false,
    promptOnly: i.prompt_only ?? i.promptOnly ?? false,
    runOnEdit: i.run_on_edit ?? i.runOnEdit ?? false,
    substituteRegex: i.substitute_regex ?? i.substituteRegex ?? 0,
    minDepth: i.min_depth ?? i.minDepth ?? null,
    maxDepth: i.max_depth ?? i.maxDepth ?? null,
  };
}

/** 按引擎同样的规则试编译，失败时把原因说清楚 */
function tryCompile(find: string): { ok: boolean; flags: string; reason?: string } {
  let pattern = find;
  let flags = 'g'; // 裸 pattern 默认全局（本项目行为；ST 不补 g，但只会少替换不会漏）
  const m = find.match(/^\/(.+)\/([a-z]*)$/si);
  if (m) { pattern = m[1]; flags = m[2]; }
  try {
    new RegExp(pattern, flags);
    return { ok: true, flags };
  } catch (e) {
    return { ok: false, flags, reason: e instanceof Error ? e.message : String(e) };
  }
}

function collectFromData(): AnyScript[] {
  const p = 'data/app-data.json';
  if (!existsSync(p)) {
    console.error(`找不到 ${p}；也可以直接把 ST 正则 JSON 路径作为参数传进来。`);
    return [];
  }
  const d = JSON.parse(readFileSync(p, 'utf8'));
  const out: AnyScript[] = [];
  const collect = (groups: unknown) => {
    if (!Array.isArray(groups)) return;
    for (const g of groups as AnyScript[]) {
      for (const s of (g.scripts || []) as AnyScript[]) out.push(scriptOf(s));
    }
  };
  collect(d.regexGroups);
  for (const v of Object.values(d.origins || {}) as AnyScript[]) {
    collect((v?.data || v).regexGroups);
  }
  // 同一条脚本可能在多个来源里各存一份，按 名称+表达式 去重
  const seen = new Set<string>();
  return out.filter((s) => {
    const k = `${s.scriptName}|${s.findRegex}`;
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
}

function collectFromFile(path: string): AnyScript[] {
  const j = JSON.parse(readFileSync(path, 'utf8'));
  const raw: AnyScript[] = Array.isArray(j)
    ? j
    : Array.isArray(j.scripts)
      ? j.scripts
      : j.script
        ? [j]
        : [];
  return raw.map(scriptOf);
}

function run(s: AnyScript, sample: string, mode: 'display' | 'prompt', depth: number): number | null {
  const opt: AnyScript = mode === 'display'
    ? { placement: { ai: true, reasoning: true }, forDisplay: true, depthOverride: depth }
    : { placement: { ai: true, user: true, worldInfo: true }, depthOverride: depth };
  try {
    const r = runRegex([{ role: 'assistant', content: sample }], [s], opt);
    return r.hits[0]?.matchCount ?? null;
  } catch {
    return null;
  }
}

const file = process.argv[2];
const sampleFile = process.argv[3];
const sample = sampleFile && existsSync(sampleFile) ? readFileSync(sampleFile, 'utf8') : DEFAULT_SAMPLE;
const scripts = (file ? collectFromFile(file) : collectFromData()).filter((s) => !s.disabled);

console.log('=== SillyTavern 正则兼容性自检 ===');
console.log(`样本文本 ${sample.length} 字 | 启用脚本 ${scripts.length} 条 | 来源: ${file || 'data/app-data.json (含 origins)'}`);
console.log('说明：命中数只表示"规则被执行了"，0 表示跑了但样本里没有对应内容。\n');

let compiledFail = 0;
let silent = 0;
const silentNames: string[] = [];

for (const [i, s] of scripts.entries()) {
  const find = String(s.findRegex || '');
  const c = tryCompile(find);
  const pl = JSON.stringify(s.placement);
  const depth = `${s.minDepth ?? '*'}~${s.maxDepth ?? '*'}`;
  const timing = `${s.markdownOnly ? 'md' : '--'}/${s.promptOnly ? 'po' : '--'}`;
  const d0 = run(s, sample, 'display', 0);
  const d3 = run(s, sample, 'display', 3);
  const gen = run(s, sample, 'prompt', 12);
  const genFresh = run(s, sample, 'prompt', 0);
  const anyRun = d0 !== null || d3 !== null || gen !== null || genFresh !== null;

  if (!c.ok) compiledFail++;
  if (!anyRun) { silent++; silentNames.push(String(s.scriptName)); }

  console.log(
    `[${String(i).padStart(2)}] ${String(s.scriptName).slice(0, 24).padEnd(24)}`,
    `flags=${c.ok ? JSON.stringify(c.flags) : '编译失败'}`,
    `pl=${pl.padEnd(7)} 时机=${timing} 深度=${depth.padEnd(7)}`,
    `显示(新/旧)=${d0 ?? '跳'}/${d3 ?? '跳'}`,
    `生成=${gen ?? '跳'}/${genFresh ?? '跳'}`,
    !c.ok ? ` <== ${c.reason}` : (!anyRun ? ' <== 完全静默' : ''),
  );
}

console.log('\n=== 汇总 ===');
console.log(`启用脚本 ${scripts.length} 条 | 编译失败 ${compiledFail} 条 | 两条路径都不执行 ${silent} 条`);
if (silentNames.length) console.log('完全静默:', silentNames.join('、'));
console.log('\n提示：');
console.log('  · 「显示=跳」且时机是 --/po  → 正常，这条只作用于发给模型的文本');
console.log('  · 「生成=跳」且时机是 md/--  → 正常，这条只作用于界面显示');
console.log('  · 显示(新)=跳 但 显示(旧)有数 → minDepth 生效，属预期');
console.log('  · 两边全跳且不该跳 → 查 placement（ST 的 0/4 旧值）或 findRegex 编译');
console.log('  · 本项目 v-html 不执行 <script>，但脚本标签会作为 HTML 块保留，不影响正则命中');
