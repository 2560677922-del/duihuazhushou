// 在 APK 的真机 WebView 里验证「标签页生图 → 自动进图片库」。
// 这是唯一覆盖原生桥接的测试：浏览器走 IndexedDB，位置参数写法在那里无症状。
const CDP = 'http://127.0.0.1:' + (process.argv[2] || '9600');
const PNG =
  'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';

let fails = 0;
const check = (label, cond, extra = '') => {
  if (!cond) fails++;
  console.log(`  ${cond ? '✓' : '✗ FAIL'}  ${label}${extra ? '  ' + extra : ''}`);
};

const list = await (await fetch(`${CDP}/json/list`)).json();
const page = list.find((t) => t.type === 'page' && !t.url.startsWith('devtools'));
console.log('目标页面: ' + page.url + '\n');

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
    logs.push('EXC: ' + (m.params.exceptionDetails.exception?.description || '').slice(0, 300));
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

console.log('################ 真机（APK）图片库回路 ################\n');

const env = await ev(`
  const cap = window.Capacitor;
  return JSON.stringify({
    isNativePlatform: cap && cap.isNativePlatform ? cap.isNativePlatform() : null,
    platform: cap && cap.getPlatform ? cap.getPlatform() : null,
    有dhp: !!window.dhp,
  });
`);
console.log('  环境: ' + env);
check('确实跑在原生环境', JSON.parse(env).isNativePlatform === true);

// ★ 关键：直接在页面里调 dhp.api，模拟 FileBridge 的真实调用。
//   这正是 imageStorage 走的通道。
console.log('\n  直连原生桥接（dhp.api）：');
const direct = await ev(`
  const call = function (method, payload) {
    try { return { ok: true, raw: window.dhp.api(method, JSON.stringify(payload)) }; }
    catch (e) { return { ok: false, err: String(e && e.message || e) }; }
  };
  const out = {};

  // 旧的**位置参数**写法：把字符串当 payload —— 应该失败
  out.旧写法 = call('writeFile', 'images/ia-probe.txt');

  // 正确的**对象**写法 —— 应该成功
  out.建目录 = call('mkdir', { path: 'images', directory: 'DATA', recursive: true });
  const payload = { path: 'images/ia-probe.txt', data: ${JSON.stringify(PNG)}, directory: 'DATA', recursive: true };
  out.写文件 = call('writeFile', payload);
  out.读回来 = call('readFile', { path: 'images/ia-probe.txt', directory: 'DATA' });
  out.大小 = call('stat', { path: 'images/ia-probe.txt', directory: 'DATA' });
  out.列目录 = call('readdir', { path: 'images', directory: 'DATA' });

  // 读回的内容对不对
  let len = 0, isPng = false;
  try {
    const v = JSON.parse(out.读回来.raw);
    const d = v && v.value && v.value.data;
    len = d ? String(d).length : 0;
    isPng = d ? String(d).indexOf('data:image') === 0 : false;
  } catch (e) {}
  out.读回长度 = len;
  out.读回是dataURL = isPng;

  // 清理
  out.删除 = call('deleteFile', { path: 'images/ia-probe.txt', directory: 'DATA' });
  return JSON.stringify(out, null, 1);
`);
console.log(direct);
const dd = JSON.parse(direct);
check('旧的位置参数写法在真机上失败（复现原故障）', dd.旧写法.ok === false);
check('对象写法：写文件成功', dd.写文件.ok === true, dd.写文件.ok ? '' : dd.写文件.err);
check('对象写法：读文件成功', dd.读回来.ok === true);
check('读回的内容就是写进去的 dataURL', dd.读回是dataURL === true && dd.读回长度 > 100, `长度=${dd.读回长度}`);
check('stat / readdir 都正常', dd.大小.ok === true && dd.列目录.ok === true);

// ---- 走应用的真实 API：registerAsset（Tags.vue 存库用的就是它） ----
console.log('\n  走应用真实 API（imageAssets）：');
const app = await ev(`
  const m = await import('/assets/imageAssets-Dej_A7kl.js');
  const exports = Object.keys(m);
  let err = null, before = null, after = null;
  try {
    if (typeof m.loadAssets === 'function') { await m.loadAssets(); before = m.listAssets().length; }
    await m.registerAsset(${JSON.stringify(PNG)}, {
      prompt: 'native round-trip', provider: 't', model: 'm',
      description: '__probe__', origin: 'tagtool', scene: 'novel', entityId: 'tags',
    });
    await new Promise(function(r){ setTimeout(r, 1500); });
    after = m.listAssets().length;
  } catch (e) { err = String(e && e.message || e); }
  return JSON.stringify({ exports: exports, before: before, after: after, err: err });
`);
console.log('  ' + app);
const ad = JSON.parse(app);
check('registerAsset 没抛错', ad.err === null, ad.err ? ad.err : '');
check('资产表条数 +1', ad.after === ad.before + 1, `${ad.before} → ${ad.after}`);

// ---- 关键：落盘之后，原生文件里真的有这张图 ----
console.log('\n  落盘核验（直接查原生文件系统）：');
const onDisk = await ev(`
  const raw = window.dhp.api('readdir', JSON.stringify({ path: 'images', directory: 'DATA' }));
  const v = JSON.parse(raw);
  const files = (v && v.value && v.value.files) || [];
  const imgs = files.filter(function(f){ return f.name.indexOf('ia-') === 0; });
  // 顺带把索引读出来，确认元数据也落盘了
  let idxCount = -1;
  try {
    const ir = JSON.parse(window.dhp.api('readFile', JSON.stringify({ path: 'images/index.json', directory: 'DATA' })));
    const txt = ir && ir.value && ir.value.data;
    idxCount = txt ? JSON.parse(txt).length : -1;
  } catch (e) { idxCount = 'err:' + String(e && e.message || e); }
  return JSON.stringify({
    目录里的图: imgs.map(function(f){ return f.name + ' (' + f.size + 'B)'; }),
    索引里的条数: idxCount,
  });
`);
console.log('  ' + onDisk);
const od = JSON.parse(onDisk);
check('原生目录里真的有图文件落盘', od.目录里的图.length > 0, od.目录里的图.join(', '));
check('元数据索引也落盘了', typeof od.索引里的条数 === 'number' && od.索引里的条数 > 0, `索引条数=${od.索引里的条数}`);

console.log('\n  控制台: ' + (logs.length ? [...new Set(logs)].slice(0, 8).join(' | ') : '无'));
console.log(`  失败项: ${fails}`);
ws.close();
process.exitCode = fails > 0 ? 1 : 0;
