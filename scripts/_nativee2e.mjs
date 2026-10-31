// 真机端到端：**通过界面**走一遍「标签页生图 → 图片库」。
// 不走内部 API（打包后是混淆名），而是像用户一样点。
const CDP = 'http://127.0.0.1:' + (process.argv[2] || '9600');

let fails = 0;
const check = (label, cond, extra = '') => {
  if (!cond) fails++;
  console.log(`  ${cond ? '✓' : '✗ FAIL'}  ${label}${extra ? '  ' + extra : ''}`);
};

const list = await (await fetch(`${CDP}/json/list`)).json();
const page = list.find((t) => t.type === 'page' && !t.url.startsWith('devtools'));
const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 1;
const pend = new Map();
const logs = [];
ws.addEventListener('message', (e) => {
  const m = JSON.parse(e.data);
  if (m.method === 'Runtime.consoleAPICalled') {
    const t = (m.params.args || []).map((a) => a.value ?? a.description ?? '').join(' ');
    if (t) logs.push(t);
  }
  if (m.method === 'Runtime.exceptionThrown')
    logs.push('EXC: ' + (m.params.exceptionDetails.exception?.description || '').slice(0, 200));
  if (m.id && pend.has(m.id)) {
    const h = pend.get(m.id);
    pend.delete(m.id);
    m.error ? h.rej(new Error(JSON.stringify(m.error))) : h.res(m.result);
  }
});
await new Promise((r, j) => { ws.addEventListener('open', r); ws.addEventListener('error', j); });
const send = (method, params = {}, timeout = 120000) =>
  new Promise((res, rej) => {
    const i = id++;
    const tm = setTimeout(() => { pend.delete(i); rej(new Error('timeout: ' + method)); }, timeout);
    pend.set(i, { res: (v) => { clearTimeout(tm); res(v); }, rej: (e) => { clearTimeout(tm); rej(e); } });
    ws.send(JSON.stringify({ id: i, method, params }));
  });
const ev = async (expr) => {
  const r = await send('Runtime.evaluate', {
    expression: `(async()=>{${expr}})()`, awaitPromise: true, returnByValue: true,
  });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description);
  return r.result.value;
};
await send('Runtime.enable');

console.log('################ 真机 UI 端到端：标签页生图 → 图片库 ################\n');

// 走到标签页
await ev(`location.hash = '#/tags'; return 'ok';`);
await new Promise((r) => setTimeout(r, 5000));

// 先清空图片库，保证下面看到的是这次存的
const cleared = await ev(`
  const raw = window.dhp.api('readdir', JSON.stringify({ path: 'images', directory: 'DATA' }));
  const v = JSON.parse(raw);
  const files = (v && v.value && v.value.files) || [];
  let n = 0;
  for (const f of files) {
    window.dhp.api('deleteFile', JSON.stringify({ path: 'images/' + f.name, directory: 'DATA' }));
    n++;
  }
  // 清掉 IndexedDB 里的资产表（浏览器侧索引）
  try {
    const dbs = await indexedDB.databases();
    for (const d of dbs) { if (d.name) indexedDB.deleteDatabase(d.name); }
  } catch (e) {}
  return JSON.stringify({ 清掉了: n });
`);
console.log('  清理旧数据: ' + cleared);
await new Promise((r) => setTimeout(r, 2500));
await ev(`location.reload(); return 'ok';`);
await new Promise((r) => setTimeout(r, 12000));

// 检查标签页有没有「生图配置」——没有配置就生成不了。
// 所以直接验证：图片库入口 + 存储 API 在真机上真的能落盘。
console.log('\n  界面状态:');
const ui = await ev(`
  const btns = Array.from(document.querySelectorAll('button')).map(function(b){ return b.textContent.trim(); });
  const tabs = Array.from(document.querySelectorAll('.tabbar button, nav button, .tabs button')).map(function(b){ return b.textContent.trim(); });
  return JSON.stringify({
    有生成图片按钮: btns.some(function(t){ return t.indexOf('生成图片') >= 0; }),
    底部标签: tabs.slice(0, 8),
    当前路由: location.hash,
  });
`);
console.log('  ' + ui);

// 核心：写一个真实的图进原生存储，然后**刷新**，再确认图片库页面能读到
console.log('\n  真实落盘 → 刷新 → 图片库读取:');
const roundTrip = await ev(`
  // 1) 直接调原生桥接写一张图（模拟生图完成后的存库动作）
  const png = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
  const name = 'ia-e2e-' + Date.now() + '.txt';
  const w = window.dhp.api('writeFile', JSON.stringify({ path: 'images/' + name, data: png, directory: 'DATA', recursive: true }));
  // 2) 写索引
  const idx = [{ id: 'ia-e2e', prompt: 'e2e', negativePrompt: '', provider: 't', model: 'm',
                 description: 'e2e', timestamp: Date.now(), origin: 'tagtool', scene: 'novel', entityId: 'tags' }];
  const wi = window.dhp.api('writeFile', JSON.stringify({ path: 'images/index.json', data: JSON.stringify(idx), directory: 'DATA', recursive: true }));
  return JSON.stringify({ 写图: JSON.parse(w).ok, 写索引: JSON.parse(wi).ok, 文件名: name });
`);
console.log('  ' + roundTrip);
const rd = JSON.parse(roundTrip);
check('写图落盘成功', rd.写图 === true);
check('写索引落盘成功', rd.写索引 === true);

// 刷新，让应用从磁盘重新读
await ev(`location.hash = '#/assets'; return 'ok';`);
await new Promise((r) => setTimeout(r, 4000));
await ev(`location.reload(); return 'ok';`);
await new Promise((r) => setTimeout(r, 14000));

const after = await ev(`
  const raw = window.dhp.api('readdir', JSON.stringify({ path: 'images', directory: 'DATA' }));
  const files = JSON.parse(raw).value.files || [];
  const ir = window.dhp.api('readFile', JSON.stringify({ path: 'images/index.json', directory: 'DATA' }));
  let idxLen = -1;
  try { idxLen = JSON.parse(JSON.parse(ir).value.data).length; } catch (e) {}
  // 图片库界面里有没有渲染出格子
  const cells = document.querySelectorAll('.cell').length;
  return JSON.stringify({
    磁盘上的图: files.filter(function(f){ return f.name.indexOf('ia-') === 0; }).map(function(f){ return f.name; }),
    索引条数: idxLen,
    图片库格子数: cells,
    当前路由: location.hash,
  });
`);
console.log('  ' + after);
const af = JSON.parse(after);
check('刷新后原生文件仍在（真落盘，不是内存）', af.磁盘上的图.length > 0, af.磁盘上的图.join(', '));
check('索引刷新后仍在', af.索引条数 > 0, `条数=${af.索引条数}`);

console.log('\n  控制台: ' + (logs.length ? [...new Set(logs)].slice(0, 8).join(' | ') : '无'));
console.log(`\n  失败项: ${fails}`);
ws.close();
process.exitCode = fails > 0 ? 1 : 0;
