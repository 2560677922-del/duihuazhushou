// 桥命令契约：原生发出去的每一条 cmd，必须有人接。
//
// 这类 bug 最恶心：原生按钮点下去毫无反应，logcat 干净（命令发出去了），
// 编译也过 —— 只是 web 侧 switch 里没有这个 case，或者名字拼错一个字母。
// 判据只有一条：原生 put("cmd", "x") 的 x，要么在 generateService 的 case 里，
// 要么在 ConnectionStore.LOCAL_COMMANDS（原生本地执行，不过桥）。
//
// 反向差集（web 有、原生没发）只提示 —— 网页版可能还在用。
import fs from 'node:fs';
import path from 'node:path';

const ROOT = new URL('..', import.meta.url).pathname;
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');

let fails = 0;
const check = (label, cond, extra = '') => {
  if (!cond) fails++;
  console.log(`  ${cond ? '✓' : '✗ FAIL'}  ${label}${extra ? '  ' + extra : ''}`);
};

console.log('################ 桥命令契约 ################\n');

// ---- web 侧 case ----
const gen = read('src/bridge/generateService.ts');
const webCmds = new Set([...gen.matchAll(/case '([a-zA-Z][\w]*)':/g)].map((m) => m[1]));

// ---- 原生本地命令 ----
const store = read('dbpg/app/src/main/java/com/dbpg/duihuazhushou/settings/ConnectionStore.kt');
const block = store.match(/LOCAL_COMMANDS = setOf\(([\s\S]*?)\)/);
const localCmds = new Set(block ? [...block[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]) : []);
check('解析到本地命令清单', localCmds.size > 20, `${localCmds.size} 条`);

// ---- 原生实际发出的命令（字面量） ----
const nativeCmds = new Map(); // cmd -> 首次出现的文件
function walk(dir) {
  const abs = path.join(ROOT, dir);
  if (!fs.existsSync(abs)) return;
  for (const n of fs.readdirSync(abs)) {
    const rel = path.join(dir, n);
    if (fs.statSync(path.join(ROOT, rel)).isDirectory()) walk(rel);
    else if (/\.(kt|java)$/.test(n)) {
      const src = read(rel);
      for (const m of src.matchAll(/put\(\s*"cmd"\s*,\s*"([^"]+)"\s*\)/g)) {
        if (!nativeCmds.has(m[1])) nativeCmds.set(m[1], rel);
      }
    }
  }
}
walk('dbpg/app/src/main/java');

console.log(`  web case: ${webCmds.size} 条 / 原生本地: ${localCmds.size} 条 / 原生发出: ${nativeCmds.size} 条\n`);

// ---- 1. 原生发出的必须有去处 ----
const missing = [];
for (const [cmd, file] of nativeCmds) {
  if (!webCmds.has(cmd) && !localCmds.has(cmd)) missing.push(`${cmd} (${path.basename(file)})`);
}
check('原生命令全部有去处', missing.length === 0, missing.join(', '));

// ---- 2. 本地命令必须真的被 ConnectionStore 实现 ----
// 只登记在 LOCAL_COMMANDS、apply 里没有分支的话，会回一个 ok:true 但什么都没做 ——
// 表现同样是「点了没反应」，而且比走桥更难查（至少在 Kotlin 里，log 都没有）。
const applyBody = read('dbpg/app/src/main/java/com/dbpg/duihuazhushou/settings/ConnectionStore.kt');
const applySrc = applyBody.slice(applyBody.indexOf('fun apply('));
const localImpl = new Set();
for (const m of applySrc.matchAll(/^\s{12}((?:"[^"]+"(?:\s*,\s*)?)+)\s*->/gm)) {
  for (const c of m[1].matchAll(/"([^"]+)"/g)) localImpl.add(c[1]);
}
const unimplemented = [...localCmds].filter((c) => !localImpl.has(c));
check('本地命令都有实现分支', unimplemented.length === 0, unimplemented.join(', '));

// ---- 3. 本地命令与 web case 重叠：允许（网页版要用），但记一笔 ----
// 这是「同一语义两份实现」，改其中一份时要想到另一份。
const both = [...localCmds].filter((c) => webCmds.has(c));
console.log(`  ·  本地/web 双实现 ${both.length} 条（网页版走 web，App 走本地，改语义要同步）`);

// ---- 3. 反向：web 有但原生从不发（仅提示，不算失败） ----
const unused = [...webCmds].filter((c) => !nativeCmds.has(c) && !localCmds.has(c));
if (unused.length) console.log(`  ·  仅网页版使用：${unused.join(', ')}`);

console.log(`\n${fails === 0 ? '全部通过' : `${fails} 项失败`}`);
process.exit(fails === 0 ? 0 : 1);
