// 把社区标签库的 SQL 转成应用可用的紧凑 JSON。
//
// 来源：chami_tavern-scene-plugin 的 data/*.sql（Danbooru 标签 + 中文分类 + 中文释义）
// 结构：tag_groups（一级分类）→ tag_subgroups（二级）→ tag_tags（标签本身）
//
// 输出：一个扁平数组 [{ tag, zh, group, sub, alt?[], also?[] }]
//
// 关于"跨分类重名"：
//   同一个标签经常归在多个分类下，而且两边的中文释义不一样 ——
//   比如 `maid` 在「身份」下是"女仆"、在「服饰/正装」下是"女仆装"；
//   `knight` 在「身份」和「二次元角色」下都是"骑士"。
//   早期版本对重名直接"保留第一条、丢掉其余"，等于白白扔掉一半信息。
//   现在改成合并：标签只出现一次（生图时不该重复），但把所有分类和
//   所有释义都收集起来，检索时都能命中。
import fs from 'node:fs';
import path from 'node:path';

const dir = process.argv[2];
const outFile = process.argv[3];
if (!dir || !outFile) {
  console.error('用法: node parse-tag-sql.mjs <sql目录> <输出json>');
  process.exit(1);
}

/** 从 INSERT 语句里提取括号内的一行值，按 SQL 规则处理引号与转义 */
function extractValues(line) {
  const start = line.indexOf('VALUES (');
  if (start === -1) return null;
  let s = line.slice(start + 'VALUES ('.length);
  const end = s.lastIndexOf(')');
  if (end === -1) return null;
  s = s.slice(0, end);

  const out = [];
  let cur = '';
  let inStr = false;
  for (let i = 0; i < s.length; i++) {
    const ch = s[i];
    if (inStr) {
      if (ch === "'" && s[i + 1] === "'") {
        cur += "'";
        i++;
      } else if (ch === "'") {
        inStr = false;
      } else {
        cur += ch;
      }
      continue;
    }
    if (ch === "'") {
      inStr = true;
      continue;
    }
    if (ch === ',') {
      out.push(cur.trim());
      cur = '';
      continue;
    }
    cur += ch;
  }
  out.push(cur.trim());
  return out;
}

function parseFile(file) {
  const text = fs.readFileSync(file, 'utf8');
  const groups = new Map();
  const subgroups = new Map();
  const tags = [];

  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line.startsWith('INSERT')) continue;

    const table = /INTO "([^"]+)"/.exec(line)?.[1];
    if (!table) continue;
    const v = extractValues(line);
    if (!v) continue;

    if (table === 'tag_groups') {
      groups.set(Number(v[0]), v[1]);
    } else if (table === 'tag_subgroups') {
      subgroups.set(Number(v[0]), { groupId: Number(v[1]), name: v[2] });
    } else if (table === 'tag_tags') {
      tags.push({ subgroupId: Number(v[1]), text: v[2], desc: v[3] });
    }
  }

  return { groups, subgroups, tags };
}

const files = fs
  .readdirSync(dir)
  .filter((f) => /^tags.*\.sql$/i.test(f))
  .sort();
console.log('解析文件:', files.join(', '));

const allGroups = new Map();
const allSubgroups = new Map();
const allTags = [];
let subOffset = 0;
let groupOffset = 0;

for (const f of files) {
  const { groups, subgroups, tags } = parseFile(path.join(dir, f));
  console.log(`  ${f}: 一级分类 ${groups.size} / 二级 ${subgroups.size} / 标签 ${tags.length}`);

  const gidMap = new Map();
  for (const [id, name] of groups) {
    const newId = id + groupOffset;
    gidMap.set(id, newId);
    if (!allGroups.has(newId)) allGroups.set(newId, name);
  }
  const sidMap = new Map();
  for (const [id, sg] of subgroups) {
    const newId = id + subOffset;
    sidMap.set(id, newId);
    allSubgroups.set(newId, {
      groupId: gidMap.get(sg.groupId) ?? sg.groupId + groupOffset,
      name: sg.name,
    });
  }
  for (const t of tags) {
    allTags.push({
      subgroupId: sidMap.get(t.subgroupId) ?? t.subgroupId + subOffset,
      text: t.text,
      desc: t.desc,
    });
  }
  groupOffset += groups.size + 1000;
  subOffset += subgroups.size + 10000;
}

// 合并同名标签：标签只留一条，但收集所有分类与释义
//
// 源数据是 Danbooru 的 `multiple_girls` 写法，而 NovelAI 用 `multiple girls`，
// 所以统一转成空格写法（更通用），另存 alt 保留原写法方便按原样搜。
const merged = new Map();
for (const t of allTags) {
  const rawTag = String(t.text || '').trim();
  if (!rawTag) continue;
  const normalized = rawTag.replace(/_/g, ' ').replace(/\s+/g, ' ').trim();
  const key = normalized.toLowerCase();
  // 中文释义里混着 `发_带` 这种下划线（原本用于分词），展示时去掉
  const zh = String(t.desc || '').replace(/_/g, '').trim();

  const sg = allSubgroups.get(t.subgroupId);
  const group = sg ? allGroups.get(sg.groupId) || '' : '';
  const sub = sg ? sg.name : '';

  let e = merged.get(key);
  if (!e) {
    e = { tag: normalized, zh, group, sub, alt: [], also: [] };
    if (rawTag !== normalized) e.alt.push(rawTag);
    merged.set(key, e);
  }
  // 补充释义：同一条标签在不同分类下释义不同时都留下
  if (zh && !e.zh.includes(zh)) {
    // 主释义保持最短的那个（通常最通用，"女仆"优于"女仆装"）
    if (zh.length < e.zh.length) {
      e.also.push(e.zh);
      e.zh = zh;
    } else {
      e.also.push(zh);
    }
  }
  // 补充分类
  if (group && sub) {
    const label = `${group}/${sub}`;
    const cur = `${e.group}/${e.sub}`;
    if (label !== cur && !e.also.includes(label)) e.also.push(label);
  }
  if (rawTag !== normalized && !e.alt.includes(rawTag)) e.alt.push(rawTag);
}

const flat = [...merged.values()].map((e) => {
  const o = { tag: e.tag, zh: e.zh, group: e.group, sub: e.sub };
  if (e.alt.length) o.alt = e.alt;
  if (e.also.length) o.also = e.also;
  return o;
});

fs.mkdirSync(path.dirname(outFile), { recursive: true });
fs.writeFileSync(outFile, JSON.stringify(flat), 'utf8');

const withAlt = flat.filter((t) => t.alt).length;
const withAlso = flat.filter((t) => t.also).length;
console.log(`\n合并结果：`);
console.log(`  一级分类 ${allGroups.size} 个: ${[...allGroups.values()].join(', ')}`);
console.log(`  二级分类 ${allSubgroups.size} 个`);
console.log(`  源标签行 ${allTags.length} → 合并后 ${flat.length} 个`);
console.log(`  带备用写法的 ${withAlt} 个，带多个释义/分类的 ${withAlso} 个`);
console.log(`  输出 ${outFile}（${(fs.statSync(outFile).size / 1024).toFixed(1)} KB）`);

console.log('\n抽样：');
for (const t of flat.slice(0, 10)) {
  const extra = t.also ? ` 另: ${t.also.join(' / ')}` : '';
  console.log(`  ${t.tag.padEnd(22)} ${t.zh.padEnd(12)} [${t.group}/${t.sub}]${extra}`);
}
console.log('\n跨分类标签样例：');
for (const t of flat.filter((x) => x.also).slice(0, 6)) {
  console.log(`  ${t.tag.padEnd(18)} ${t.zh.padEnd(10)} 分类 ${t.group}/${t.sub} 另: ${t.also.join(' / ')}`);
}
