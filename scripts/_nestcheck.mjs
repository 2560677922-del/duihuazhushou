// 检查「嵌套过深」的地方 —— 便于以后改动和定位 bug。
//
// 检查三类：
//   1. 函数内的 if/for/try 缩进层数（超过 4 层就该拆）
//   2. Vue 模板里的缩进层数（超过 10 层基本没法读）
//   3. 单文件函数过多 / 超长函数（超过 120 行）
import fs from 'node:fs';
import path from 'node:path';

const ROOT = process.cwd();
const MAX_FN_INDENT = 4;      // 函数体内最多 4 层缩进
const MAX_TPL_INDENT = 10;    // 模板最多 10 层
const MAX_FN_LINES = 120;     // 单函数最多 120 行

const targets = [];
function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) {
      if (['node_modules', 'dist', 'dbpg', '.git', 'data', 'scripts'].includes(e.name)) continue;
      walk(p);
    } else if (/\.(ts|vue)$/.test(e.name)) {
      targets.push(p);
    }
  }
}
walk(path.join(ROOT, 'src'));

const problems = { deepCode: [], deepTpl: [], longFn: [] };

for (const file of targets) {
  const rel = path.relative(ROOT, file).replace(/\\/g, '/');
  const lines = fs.readFileSync(file, 'utf8').split('\n');
  const isVue = file.endsWith('.vue');

  // 找 template / script 区间
  let inTpl = false, inStyle = false;
  let tplStart = 0;
  for (let i = 0; i < lines.length; i++) {
    const t = lines[i];
    if (/^<template/.test(t)) { inTpl = true; tplStart = i; continue; }
    if (/^<\/template>/.test(t)) { inTpl = false; continue; }
    if (/^<style/.test(t)) { inStyle = true; continue; }
    if (/^<\/style>/.test(t)) { inStyle = false; continue; }

    if (!inTpl || inStyle) continue;
    if (!t.trim()) continue;

    // 模板缩进层数（每 2 空格算一层）
    const lead = t.match(/^(\s*)/)[1].length;
    const depth = Math.floor(lead / 2) - Math.floor(
      (lines[tplStart].match(/^(\s*)/)[1].length) / 2,
    );
    if (depth > MAX_TPL_INDENT) {
      problems.deepTpl.push({ file: rel, line: i + 1, depth, text: t.trim().slice(0, 70) });
    }
  }

  // 代码块：按大括号层级粗略判断缩进
  const codeLines = [];
  let inScript = !isVue;
  for (let i = 0; i < lines.length; i++) {
    const t = lines[i];
    if (/^<script/.test(t)) { inScript = true; continue; }
    if (/^<\/script>/.test(t)) { inScript = false; continue; }
    if (/^<template|^<style/.test(t)) inScript = false;
    if (inScript) codeLines.push({ n: i + 1, text: t });
  }

  for (const { n, text } of codeLines) {
    if (!text.trim() || text.trim().startsWith('//') || text.trim().startsWith('*')) continue;
    const lead = text.match(/^(\s*)/)[1].length;
    const depth = Math.floor(lead / 2);
    // 只报"控制流"过深（if/for/while/try/switch），普通对象字面量不算
    const ctrl = /^\s*(if|else|for|while|switch|try|catch|\}\s*(else|catch))/.test(text);
    if (ctrl && depth > 8) {
      problems.deepCode.push({ file: rel, line: n, depth, text: text.trim().slice(0, 70) });
    }
  }

  // 函数长度：从 function / => { 开始算到同级 }
  const src = lines.join('\n');
  const fnRe = /^\s*(?:export\s+)?(?:async\s+)?function\s+(\w+)|^\s*const\s+(\w+)\s*=\s*(?:async\s*)?\(/gm;
  let m;
  while ((m = fnRe.exec(src)) !== null) {
    const startLine = src.slice(0, m.index).split('\n').length;
    const name = m[1] || m[2] || '(匿名)';
    const indent = (m[0].match(/^(\s*)/) || ['', ''])[1].length;
    let end = startLine;
    for (let i = startLine; i < lines.length; i++) {
      const t = lines[i];
      if (!t.trim()) continue;
      const lead = t.match(/^(\s*)/)[1].length;
      // 回到同级或更浅、且不是续行 → 函数结束
      if (lead <= indent && i > startLine && /^[\s]*[\}\]]/.test(t)) { end = i + 1; break; }
      end = i + 1;
    }
    const len = end - startLine;
    if (len > MAX_FN_LINES) {
      problems.longFn.push({ file: rel, line: startLine, name, len });
    }
  }
}

const title = (s) => `\n${'='.repeat(64)}\n${s}\n${'='.repeat(64)}`;

console.log(title(`嵌套过深（模板 > ${MAX_TPL_INDENT} 层）`));
if (!problems.deepTpl.length) console.log('  没有');
else {
  const byFile = {};
  for (const p of problems.deepTpl) (byFile[p.file] ||= []).push(p);
  for (const [f, arr] of Object.entries(byFile)) {
    console.log(`  ${f}  （${arr.length} 处，最深 ${Math.max(...arr.map((x) => x.depth))} 层）`);
    arr.slice(0, 4).forEach((x) => console.log(`      行${x.line} [${x.depth}层] ${x.text}`));
  }
}

console.log(title(`控制流嵌套过深（> 8 层）`));
if (!problems.deepCode.length) console.log('  没有');
else {
  const byFile = {};
  for (const p of problems.deepCode) (byFile[p.file] ||= []).push(p);
  for (const [f, arr] of Object.entries(byFile)) {
    console.log(`  ${f}  （${arr.length} 处）`);
    arr.slice(0, 5).forEach((x) => console.log(`      行${x.line} [${x.depth}层] ${x.text}`));
  }
}

console.log(title(`超长函数（> ${MAX_FN_LINES} 行）`));
if (!problems.longFn.length) console.log('  没有');
else {
  problems.longFn.sort((a, b) => b.len - a.len);
  problems.longFn.slice(0, 15).forEach((x) =>
    console.log(`  ${x.len.toString().padStart(4)} 行  ${x.file}:${x.line}  ${x.name}`));
}

console.log(title('文件规模（前 12）'));
const sizes = targets
  .map((f) => ({ f: path.relative(ROOT, f).replace(/\\/g, '/'), n: fs.readFileSync(f, 'utf8').split('\n').length }))
  .sort((a, b) => b.n - a.n);
sizes.slice(0, 12).forEach((x) => console.log(`  ${x.n.toString().padStart(5)} 行  ${x.f}`));
