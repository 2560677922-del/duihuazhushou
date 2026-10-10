#!/usr/bin/env node
/**
 * Danbooru 标签批量翻译
 * ============================================================================
 * 把 scripts/tag-translate-source.json 里的英文标签翻成中文，
 * 结果按分片存进 scripts/translations/，最后合并成 tag-translations.json。
 *
 * 特点：
 *   · **断点续跑** —— 已经翻过的标签会跳过，中断后重跑不会重复烧 token
 *   · **增量落盘** —— 每翻完一片就写文件，进程挂了也不丢进度
 *   · **并发 + 重试** —— 有限并发，失败自动退避重试
 *   · **自带质检** —— 空值 / 没翻 / 明显是英文的会被挑出来
 *
 * ---------------------------------------------------------------------------
 * 用法
 * ---------------------------------------------------------------------------
 *   # 1) 设置密钥（三个办法任选）
 *   setx DANDBOORU_KEY "sk-xxxx"                      # Windows 永久
 *   $env:DANDBOORU_KEY = "sk-xxxx"                     # 当前终端
 *   # 或者写进 .env-translate 文件（该文件已在 .gitignore 里）
 *
 *   # 2) 先小跑一批看看质量
 *   node scripts/translate-tags.mjs --limit 50
 *
 *   # 3) 满意了就全量跑（随时可以 Ctrl+C 中断，重跑会接着来）
 *   node scripts/translate-tags.mjs
 *
 *   常用参数
 *   --base-url https://api.xuancat.cn    接口地址
 *   --model gemini-3-pro-preview         模型
 *   --batch 200                          每次请求送多少条
 *   --concurrency 4                      同时几个请求
 *   --limit 50                           只翻前 N 条（试跑用）
 *   --only 服装                          只翻某一类
 *   --dry-run                            不调接口，只打印将要做什么
 *
 * ---------------------------------------------------------------------------
 * 翻译口径（和已经人工翻好的 batch-1 保持一致）
 * ---------------------------------------------------------------------------
 *   1. 简洁优先：black_jacket → 黑夹克（不是"黑色夹克外衣"）
 *   2. 保留组合感：颜色/材质/款式前缀要体现出来
 *      black_frilled_skirt → 黑荷叶裙
 *   3. 国内常见叫法：
 *      pantyhose 连裤袜 / thighhighs 过膝袜 / kneehighs 及膝袜 / legwarmer 腿套
 *      serafuku 水手服 / zettai_ryouiki 绝对领域 / hakama 袴 / kimono 和服
 *      hair_ornament 发饰 / scrunchie 发圈 / choker 颈圈 / garter 吊带袜
 *   4. 去掉冗余修饰：single_bare_shoulder → 单侧露肩（不是"只有一只裸露的肩膀"）
 *   5. NSFW 词条**照直译，不要回避**（这是给成年人用的标签库）
 *      nude 裸体 / topless 上衣不穿 / cum 精液 / orgasm 高潮 …
 *   6. 拿不准的保留英文原词，不要瞎编
 */

import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname).replace(/^\/([A-Za-z]:)/, '$1'), '..');
const P = {
  source: path.join(ROOT, 'scripts', 'tag-translate-source.json'),
  dir: path.join(ROOT, 'scripts', 'translations'),
  final: path.join(ROOT, 'scripts', 'tag-translations.json'),
};

/* ---------------- 参数 ---------------- */
const argv = process.argv.slice(2);
const arg = (k, d) => {
  const i = argv.indexOf('--' + k);
  return i >= 0 && argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[i + 1] : d;
};
const flag = (k) => argv.includes('--' + k);

const BASE_URL = arg('base-url', 'https://api.xuancat.cn').replace(/\/+$/, '');
const MODEL = arg('model', 'gemini-3-pro-preview');
const BATCH = Number(arg('batch', 200));
const CONCURRENCY = Number(arg('concurrency', 4));
const LIMIT = Number(arg('limit', 0));
const ONLY = arg('only', '');
const DRY = flag('dry-run');
const RETRIES = Number(arg('retries', 3));

/* ---------------- 密钥 ---------------- */
function readKey() {
  if (process.env.DANDBOORU_KEY) return process.env.DANDBOORU_KEY.trim();
  const dotenv = path.join(ROOT, '.env-translate');
  if (fs.existsSync(dotenv)) {
    for (const line of fs.readFileSync(dotenv, 'utf8').split(/\r?\n/)) {
      const m = line.match(/^\s*(?:DANDBOORU_KEY|KEY)\s*=\s*(.+?)\s*$/);
      if (m) return m[1].replace(/^["']|["']$/g, '');
    }
  }
  return '';
}

/* ---------------- 载入源数据 ---------------- */
if (!fs.existsSync(P.source)) {
  console.error('找不到源文件：' + P.source);
  console.error('先跑 node scripts/_prepsource.mjs 生成');
  process.exit(1);
}
const src = JSON.parse(fs.readFileSync(P.source, 'utf8'));
let items = src.items;
if (ONLY) items = items.filter((t) => t.group === ONLY);
if (LIMIT > 0) items = items.slice(0, LIMIT);

/* ---------------- 已翻过的（断点续跑） ---------------- */
fs.mkdirSync(P.dir, { recursive: true });
const done = new Map(); // tag -> zh
for (const f of fs.readdirSync(P.dir)) {
  if (!f.endsWith('.json')) continue;
  try {
    const j = JSON.parse(fs.readFileSync(path.join(P.dir, f), 'utf8'));
    for (const [k, v] of Object.entries(j)) {
      if (k.startsWith('_')) continue;
      if (typeof v === 'string' && v.trim()) done.set(k, v.trim());
    }
  } catch {
    /* 坏分片跳过，最后质检会报 */
  }
}

const todo = items.filter((t) => !done.has(t.tag));
const chunks = [];
for (let i = 0; i < todo.length; i += BATCH) chunks.push(todo.slice(i, i + BATCH));

console.log('源文件   ' + P.source);
console.log('标签总数 ' + items.length + (ONLY ? `（只翻「${ONLY}」）` : ''));
console.log('已翻     ' + items.filter((t) => done.has(t.tag)).length + '  ← 续跑会跳过这些');
console.log('待翻     ' + todo.length + `  （${chunks.length} 片 × ${BATCH}）`);
console.log('接口     ' + BASE_URL + '  模型 ' + MODEL);
console.log('');

if (DRY) {
  console.log('--- dry-run，将要翻译的前 10 条 ---');
  for (const t of todo.slice(0, 10)) console.log('  ' + t.tag + '  [' + t.group + ']');
  process.exit(0);
}

const KEY = readKey();
if (!KEY) {
  console.error('没有密钥。三种办法任选一种：');
  console.error('  setx DANDBOORU_KEY "sk-xxxx"        （Windows 永久生效，要重开终端）');
  console.error('  $env:DANDBOORU_KEY = "sk-xxxx"       （当前终端有效）');
  console.error('  在项目根目录建 .env-translate，写一行：KEY=sk-xxxx');
  process.exit(1);
}

/* ---------------- 提示词 ---------------- */
const SYSTEM = `你是 Danbooru / NovelAI 标签库的中文译者。给你一批英文标签，输出 JSON 对象 { 英文标签: 中文 }。

翻译口径：
1. 简洁优先。black_jacket → "黑夹克"，不要"黑色夹克外衣"这种啰嗦说法。
2. 保留组合感：颜色/材质/款式前缀要体现。black_frilled_skirt → "黑荷叶裙"，white_sailor_collar → "白色水手领"。
3. 用国内常见叫法：pantyhose=连裤袜, thighhighs=过膝袜, kneehighs=及膝袜, legwarmer=腿套, serafuku=水手服, zettai_ryouiki=绝对领域, hakama=袴, kimono=和服, choker=颈圈, garter=吊带袜, scrunchie=发圈, hair_ornament=发饰, maid_headdress=女仆头饰。
4. 去掉冗余修饰：single_bare_shoulder → "单侧露肩"，不要"只有一只裸露的肩膀"。
5. NSFW 词条**照直译、不要回避**（这是给成年人使用的标签库）：
   nude=裸体, topless=上衣不穿, bottomless=下装不穿, cleavage=乳沟, nipples=乳头,
   cum=精液, orgasm=高潮, penetration=插入, erection=勃起, spread_legs=张开双腿 等。
6. 拆解复合词时按从外到内的顺序：black_shorts → 黑色 短裤 → "黑短裤"。
7. 拿不准的保留英文原词，不要瞎编一个词。
8. 输出必须是合法 JSON，不要加任何解释文字、不要用 markdown 代码块。
9. key 必须是原样的英文标签（不要改动大小写或下划线）。`;

async function callApi(list) {
  const user = [
    '请翻译下面这些标签，输出 JSON：',
    '',
    ...list.map((t) => `${t.tag}\t[${t.group}${t.sub ? '/' + t.sub : ''}]`),
  ].join('\n');

  const body = {
    model: MODEL,
    messages: [
      { role: 'system', content: SYSTEM },
      { role: 'user', content: user },
    ],
    temperature: 0.2,
    max_tokens: Math.max(2000, Math.ceil(list.length * 14)),
    stream: false,
  };

  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), 180000);
  try {
    const r = await fetch(`${BASE_URL}/chat/completions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${KEY}` },
      body: JSON.stringify(body),
      signal: ctl.signal,
    });
    if (!r.ok) {
      const t = await r.text().catch(() => '');
      throw new Error(`HTTP ${r.status} ${t.slice(0, 160)}`);
    }
    const j = await r.json();
    const content = j?.choices?.[0]?.message?.content ?? '';
    return parseJson(content);
  } finally {
    clearTimeout(timer);
  }
}

/** 模型有时会包 markdown、有时会前后带话，尽量捞出 JSON */
function parseJson(s) {
  if (!s) return {};
  let t = String(s).trim();
  const m = t.match(/```(?:json)?\s*([\s\S]*?)```/i);
  if (m) t = m[1].trim();
  const a = t.indexOf('{');
  const b = t.lastIndexOf('}');
  if (a >= 0 && b > a) t = t.slice(a, b + 1);
  try {
    const j = JSON.parse(t);
    return j && typeof j === 'object' ? j : {};
  } catch {
    return {};
  }
}

const isAcceptable = (v, tag) => {
  if (typeof v !== 'string') return false;
  const s = v.trim();
  if (!s) return false;
  if (s.length > 24) return false;
  /* 整条都是 ASCII 字母 → 基本等于没翻（允许 O环 / EVA 这类混排） */
  const hasCJK = /[\u4e00-\u9fff]/.test(s);
  return hasCJK;
};

let okCount = 0;
let failCount = 0;
const problems = [];
let chunkIdx = 0;

async function worker() {
  while (true) {
    const i = chunkIdx++;
    if (i >= chunks.length) return;
    const list = chunks[i];

    let merged = {};
    for (let a = 0; a < RETRIES; a++) {
      try {
        merged = await callApi(list);
        break;
      } catch (e) {
        const msg = e && e.message ? e.message : String(e);
        if (a === RETRIES - 1) {
          console.log(`  [片 ${i + 1}] 失败：${msg.slice(0, 110)}`);
          failCount += list.length;
          for (const t of list) problems.push(t.tag + ' :: ' + msg.slice(0, 60));
          continue;
        }
        await sleep(1500 * 2 ** a);
      }
    }
    if (Object.keys(merged).length === 0 && list.length) continue;

    /* 按原 tag 取值（模型可能改了 key，容错匹配） */
    const norm = new Map();
    for (const [k, v] of Object.entries(merged)) {
      norm.set(String(k).trim().toLowerCase().replace(/\s+/g, '_'), v);
    }
    const got = {};
    for (const t of list) {
      const v = norm.get(t.tag.toLowerCase()) ?? merged[t.tag];
      if (isAcceptable(v, t.tag)) {
        got[t.tag] = String(v).trim();
        okCount++;
      } else {
        problems.push(t.tag + ' :: ' + (typeof v === 'string' ? v : '(缺/非中文)'));
        failCount++;
      }
    }

    /* 立刻落盘 —— 中断也不丢 */
    const name = `auto-${String(i + 1).padStart(3, '0')}.json`;
    fs.writeFileSync(path.join(P.dir, name), JSON.stringify(got, null, 0));

    const pct = ((okCount + failCount) / Math.max(1, todo.length) * 100).toFixed(1);
    console.log(
      `[${String(i + 1).padStart(3)}/${chunks.length}] +${Object.keys(got).length}  ` +
      `累计 ${okCount + failCount}/${todo.length} (${pct}%)  待补 ${failCount}`,
    );
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ---------------- 跑 ---------------- */
console.log('开始（Ctrl+C 可中断，重跑会接着来）');
console.log('');
const t0 = Date.now();
const pool = Array.from({ length: Math.max(1, CONCURRENCY) }, () => worker());
await Promise.all(pool);

await saveFinal();

/* ---------------- 合并 + 质检 ---------------- */
async function saveFinal() {
  const all = new Map();
  for (const f of fs.readdirSync(P.dir)) {
    if (!f.endsWith('.json')) continue;
    try {
      const j = JSON.parse(fs.readFileSync(path.join(P.dir, f), 'utf8'));
      for (const [k, v] of Object.entries(j)) {
        if (k.startsWith('_')) continue;
        if (typeof v === 'string' && v.trim()) all.set(k, v.trim());
      }
    } catch {}
  }

  /* 按源数据顺序输出，带上分类 */
  const byTag = new Map();
  for (const it of src.items) {
    if (!all.has(it.tag)) continue;
    byTag.set(it.tag, { zh: all.get(it.tag), group: it.group, sub: it.sub });
  }

  fs.writeFileSync(
    P.final,
    JSON.stringify(
      {
        meta: {
          note: 'Danbooru 标签中英对照。tag=标准英文写法（发给模型用），zh=中文（仅界面显示）',
          total: byTag.size,
          generatedAt: new Date().toISOString(),
          groups: src.meta.byGroup,
          subZh: src.meta.subZh,
        },
        tags: Object.fromEntries(byTag),
      },
      null,
      0,
    ),
  );

  const missing = src.items.filter((t) => !byTag.has(t.tag));
  console.log('');
  console.log('════ 结果 ════');
  console.log('成功   ' + byTag.size + ' / ' + src.items.length);
  console.log('输出   ' + P.final);
  if (missing.length) {
    console.log('未完成 ' + missing.length + ' 条（重跑同一个命令会只补这些）');
    fs.writeFileSync(path.join(P.dir, '_missing.json'), JSON.stringify(missing, null, 0));
  } else {
    console.log('全部完成 ✓');
  }
  if (problems.length) {
    console.log('');
    console.log('需要人工看一眼的（前 25 条）：');
    for (const p of problems.slice(0, 25)) console.log('  ' + p);
    console.log('  共 ' + problems.length + ' 条，完整清单见 ' + path.join(P.dir, '_problems.txt'));
    fs.writeFileSync(path.join(P.dir, '_problems.txt'), problems.join('\n'));
  }
  console.log('');
  console.log('耗时 ' + ((Date.now() - t0) / 1000 / 60).toFixed(1) + ' 分钟');
}