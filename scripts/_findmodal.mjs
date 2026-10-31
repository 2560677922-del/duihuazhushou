// 确认生图弹窗里真的渲染出了分类勾选格（界面层，不只看数据）。
const CDP = 'http://127.0.0.1:' + (process.env.WV_PORT || '9500');
const BASE = 'http://127.0.0.1:5173';

let fails = 0;
const check = (label, cond, extra = '') => {
  if (!cond) fails++;
  console.log(`  ${cond ? '✓' : '✗ FAIL'}  ${label}${extra ? '  ' + extra : ''}`);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const list = await (await fetch(`${CDP}/json/list`)).json();
const page = list.find((t) => t.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 1; const pend = new Map();
ws.addEventListener('message', (e) => {
  const m = JSON.parse(e.data);
  if (m.id && pend.has(m.id)) { const h = pend.get(m.id); pend.delete(m.id); m.error ? h.rej(new Error(JSON.stringify(m.error))) : h.res(m.result); }
});
await new Promise((r, j) => { ws.addEventListener('open', r); ws.addEventListener('error', j); });
const send = (method, params = {}, timeout = 180000) => new Promise((res, rej) => {
  const i = id++;
  const tm = setTimeout(() => { pend.delete(i); rej(new Error('timeout')); }, timeout);
  pend.set(i, { res: (v) => { clearTimeout(tm); res(v); }, rej: (e) => { clearTimeout(tm); rej(e); } });
  ws.send(JSON.stringify({ id: i, method, params }));
});
const ev = async (expr) => {
  const r = await send('Runtime.evaluate', { expression: `(async()=>{${expr}})()`, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description);
  return r.result.value;
};
await send('Runtime.enable');
await send('Page.enable');
await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });

// 直接挂到生图弹窗组件本身：用 hash 进聊天页，再点开弹窗
await send('Page.navigate', { url: BASE + '/#/adventure' });
await sleep(15000);

console.log('########## 生图弹窗：标签分类勾选（界面层）##########\n');

// 先造一条消息，这样才有「配图」按钮
await ev(`
  const { useAdventureStore } = await import('/src/stores/adventure.ts');
  const adv = useAdventureStore(); await adv.load();
  if (!adv.currentSession) {
    const s = await adv.addSession('弹窗测试', '一座古堡。');
    await adv.switchSession(s.id);
  }
  const cur = adv.currentSession;
  if (!cur.actions.length) {
    await adv.addAction('我走进古堡', '石壁潮湿，火把晃动。');
  }
  return 1;
`);
await ev(`location.hash = '#/adventure/' + (await import('/src/stores/adventure.ts')).useAdventureStore().currentSessionId; return 1;`);
await sleep(5000);

// 找「配图」按钮，长按/右键不是好办法 —— 看看有没有展开生图面板的入口
const entries = JSON.parse(await ev(`
  const btns = Array.from(document.querySelectorAll('button'));
  return JSON.stringify({
    全部: btns.map(b => (b.textContent || '').trim()).filter(t => t && t.length < 14).slice(0, 40),
  });
`));
console.log('  页面按钮: ' + JSON.stringify(entries.全部));

// 用键盘/程序方式打开弹窗：找 ImageGenModal 的触发点
const modal = JSON.parse(await ev(`
  // ImageGenModal 通常由 v-if 控制；先看它是否已经在 DOM 里
  const hasModal = !!document.querySelector('.modal, .gen-modal, [class*="modal"]');
  // 找所有可能是生图入口的按钮（含 emoji / title）
  const cands = Array.from(document.querySelectorAll('button, [role="button"]'))
    .filter(function(b) {
      const t = ((b.textContent || '') + ' ' + (b.title || '') + ' ' + (b.getAttribute('aria-label') || ''));
      return /生图|画板|配图|🎨|🖌|🖼/.test(t);
    })
    .map(function(b) { return { text: (b.textContent || '').trim().slice(0, 20), title: (b.title || '').slice(0, 30), cls: b.className.slice(0, 30) }; });
  return JSON.stringify({ 有弹窗: hasModal, 候选: cands.slice(0, 10) });
`));
console.log('  弹窗状态: ' + JSON.stringify(modal, null, 1));

// 点第一个「配图」按钮旁边可能存在的展开入口，或者直接找「⋯ 操作」
const opened = await ev(`
  const btns = Array.from(document.querySelectorAll('button'));
  // 常见入口名：更多/操作/⋯
  const more = btns.find(function(b) { return /操作|⋯|更多/.test((b.textContent || '') + (b.title || '')); });
  if (more) { more.click(); await new Promise(function(r){ setTimeout(r, 600); }); }
  const after = Array.from(document.querySelectorAll('button')).map(function(b){ return (b.textContent||'').trim(); }).filter(function(t){ return t && t.length < 16; }).slice(0, 40);
  return JSON.stringify({ 点开了操作条: !!more, 按钮: after });
`);
console.log('  ' + JSON.stringify(opened));

ws.close();
console.log(`\n  （此脚本仅用于定位入口，失败项 ${fails}）`);
