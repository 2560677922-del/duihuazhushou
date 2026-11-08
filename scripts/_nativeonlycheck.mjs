// 「仓库里只有引擎，没有界面」的守卫测试。
//
// 界面层已经物理删除（commit d7badda 之后的清理），但最怕**以后又长回来**：
// 某天想「临时加个页面」，把 views/ 和 router 拖回来，构建照样成功，
// 只是又回到「两套 UI 各改一遍」的老路。这个脚本从源码和产物两侧卡住：
//
//   1. 界面目录/入口文件不允许再出现（views / components / layouts / router / App.vue / main.ts / index.html）；
//   2. 引擎入口不许碰 .vue / router / 样式；
//   3. 底层（bridge/stores/engine/lib）不许反向 import 界面层；
//   4. 产物只有一个入口脚本，不含背景图等纯界面资源。
import fs from 'node:fs';
import path from 'node:path';

const ROOT = new URL('..', import.meta.url).pathname;
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');

let fails = 0;
const check = (label, cond, extra = '') => {
  if (!cond) fails++;
  console.log(`  ${cond ? '✓' : '✗ FAIL'}  ${label}${extra ? '  ' + extra : ''}`);
};

console.log('################ 引擎-only 守卫 ################\n');

// ---- 0. 界面层不允许复活 ----
const GHOSTS = [
  'src/views',
  'src/components',
  'src/layouts',
  'src/router',
  'src/styles',
  'src/composables',
  'src/App.vue',
  'src/main.ts',
  'index.html',
];
const revived = GHOSTS.filter((p) => fs.existsSync(path.join(ROOT, p)));
check('界面层不存在', revived.length === 0, revived.join(', '));

// ---- 1. 引擎入口的 import 面 ----
const entry = read('src/native/main.ts');
const imports = [...entry.matchAll(/from\s+'([^']+)'/g)].map((m) => m[1]);
check('入口存在', entry.length > 0);
check(
  '入口不 import .vue',
  !imports.some((i) => i.endsWith('.vue')),
  imports.filter((i) => i.endsWith('.vue')).join(','),
);
check('入口不 import router', !imports.some((i) => i.includes('/router')));
check('入口不 import 样式', !imports.some((i) => i.endsWith('.css')));
check('入口不 import App.vue', !imports.some((i) => i.includes('App.vue')));

// ---- 2. 底层不许反向依赖界面层 ----
const LOWER = ['src/bridge', 'src/stores', 'src/engine', 'src/lib', 'src/native'];
const offenders = [];
function walk(dir) {
  const abs = path.join(ROOT, dir);
  if (!fs.existsSync(abs)) return;
  for (const name of fs.readdirSync(abs)) {
    const rel = path.join(dir, name);
    const st = fs.statSync(path.join(ROOT, rel));
    if (st.isDirectory()) walk(rel);
    else if (/\.(ts|vue)$/.test(name)) {
      const src = read(rel);
      for (const m of src.matchAll(/from\s+'([^']*(?:views|components|layouts)\/[^']*)'/g)) {
        offenders.push(`${rel} → ${m[1]}`);
      }
    }
  }
}
for (const d of LOWER) walk(d);
check('底层不反向 import 界面层', offenders.length === 0, offenders.slice(0, 3).join(' | '));

// ---- 3. 产物侧（构建过才检查） ----
const dist = path.join(ROOT, 'dist');
if (!fs.existsSync(dist)) {
  console.log('  ·  跳过产物检查（还没跑 npm run build）');
} else {
  const html = fs.readFileSync(path.join(dist, 'native.html'), 'utf8');
  const scripts = [...html.matchAll(/<script[^>]*src="([^"]+)"/g)].map((m) => m[1]);
  check('native.html 只有一个入口脚本', scripts.length === 1, scripts.join(', '));
  check('入口脚本指向 assets/', scripts[0]?.startsWith('/assets/') || false, scripts[0]);

  const jsFiles = [];
  (function collect(d) {
    for (const n of fs.readdirSync(d)) {
      const p = path.join(d, n);
      if (fs.statSync(p).isDirectory()) collect(p);
      else if (n.endsWith('.js')) jsFiles.push(p);
    }
  })(path.join(dist, 'assets'));
  const totalKB = Math.round(jsFiles.reduce((s, f) => s + fs.statSync(f).size, 0) / 1024);
  // 超过 600KB 通常意味着把界面/大依赖又拖回来了（当前 ~400KB）
  check('引擎 JS 总量 < 600KB', totalKB < 600, `${totalKB}KB / ${jsFiles.length} 个文件`);

  const allJs = jsFiles.map((f) => fs.readFileSync(f, 'utf8')).join('\n');
  // 生产构建会压缩，但路由的路径字符串与组件名通常还在
  check('产物不含 vue-router 痕迹', !/createRouter|RouterView/.test(allJs));
  check('产物不含 view 层资源', !/bg\/|splash-girl/.test(allJs));
  check('产物保留标签库', fs.existsSync(path.join(dist, 'tags/novelai-tags.json')));
  check('产物不含背景图', !fs.existsSync(path.join(dist, 'bg')));
}

console.log(`\n${fails === 0 ? '全部通过' : `${fails} 项失败`}`);
process.exit(fails === 0 ? 0 : 1);
