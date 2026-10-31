// 打开「资产 → 图片库」标签，确认格子在真机上真的渲染出来。
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
ws.addEventListener('message', (e) => {
  const m = JSON.parse(e.data);
  if (m.id && pend.has(m.id)) {
    const h = pend.get(m.id); pend.delete(m.id);
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
  const r = await send('Runtime.evaluate', { expression: `(async()=>{${expr}})()`, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description);
  return r.result.value;
};
await send('Runtime.enable');

console.log('################ 真机：图片库页面渲染 ################\n');

// 先确保磁盘上有图（上一轮可能被清掉）
await ev(`
  const png = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
  window.dhp.api('mkdir', JSON.stringify({ path: 'images', directory: 'DATA', recursive: true }));
  for (let i = 0; i < 2; i++) {
    window.dhp.api('writeFile', JSON.stringify({ path: 'images/ia-gal-' + i + '.txt', data: png, directory: 'DATA', recursive: true }));
  }
  const idx = [0,1].map(function(i){
    return { id: 'ia-gal-' + i, prompt: 'gallery test ' + i, negativePrompt: '', provider: 't', model: 'm',
             description: 'g', timestamp: Date.now() - i * 1000, origin: 'tagtool', scene: 'novel', entityId: 'tags' };
  });
  window.dhp.api('writeFile', JSON.stringify({ path: 'images/index.json', data: JSON.stringify(idx), directory: 'DATA', recursive: true }));
  return 'ok';
`);
await ev(`location.hash = '#/assets'; location.reload(); return 'ok';`);
await new Promise((r) => setTimeout(r, 15000));

// 点开「图片库」这个子标签
const clicked = await ev(`
  const btns = Array.from(document.querySelectorAll('button'));
  const t = btns.find(function(b){ return b.textContent.trim().indexOf('图片库') >= 0; });
  if (!t) return JSON.stringify({ 找到: false, 所有按钮: btns.map(function(b){ return b.textContent.trim(); }).slice(0, 20) });
  t.click();
  return JSON.stringify({ 找到: true, 点了: t.textContent.trim() });
`);
console.log('  切换标签: ' + clicked);
const cd = JSON.parse(clicked);
check('资产页里有「图片库」标签', cd.找到 === true);
await new Promise((r) => setTimeout(r, 6000));

const gal = await ev(`
  const cells = Array.from(document.querySelectorAll('.cell'));
  const imgs = Array.from(document.querySelectorAll('.cell img'));
  const okImgs = imgs.filter(function(i){ return i.complete && i.naturalWidth > 0; });
  const missing = document.querySelectorAll('.cell-missing').length;
  return JSON.stringify({
    格子数: cells.length,
    img数: imgs.length,
    真正解码成功: okImgs.length,
    已丢失: missing,
    首图src前缀: imgs[0] ? String(imgs[0].src).slice(0, 32) : null,
    空提示: !!document.querySelector('.empty'),
    页面文字片段: document.body.innerText.slice(0, 160).replace(/\\n+/g, ' | '),
  });
`);
console.log('  图片库: ' + gal);
const g = JSON.parse(gal);
check('图片库渲染出了格子', g.格子数 > 0, `格子=${g.格子数}`);
check('图片真的解码成功（不是裂图）', g.真正解码成功 > 0, `解码=${g.真正解码成功}`);
check('没有被误判成"本地副本丢失"', g.已丢失 === 0, `已丢失=${g.已丢失}`);

console.log(`\n  失败项: ${fails}`);
ws.close();
process.exitCode = fails > 0 ? 1 : 0;
