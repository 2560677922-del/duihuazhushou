// 通用截图验证工具
//
// 用法: node scripts/_shot.mjs <端口> <场景名> <路由> [--notch] [--click=选择器] [--wait=毫秒] [--full]
//
// 例:
//   node scripts/_shot.mjs 9500 小说编辑器 /#/novel --notch --click=.chat-row --wait=3000
import fs from 'node:fs';
import path from 'node:path';
import { withCleanData } from './_testdata.mjs';

const CDP = 'http://127.0.0.1:' + (process.argv[2] || '9500');
const NAME = process.argv[3] || 'shot';
const HASH = process.argv[4] || '/#/adventure';
const args = process.argv.slice(5);
const NOTCH = args.includes('--notch');
const FULL = args.includes('--full');
const clickArg = args.find((a) => a.startsWith('--click='));
const waitArg = args.find((a) => a.startsWith('--wait='));
const jsArg = args.find((a) => a.startsWith('--jsfile='));
const CLICK = clickArg ? clickArg.slice(8) : null;
const WAIT = waitArg ? Number(waitArg.slice(7)) : 0;
// --jsfile=<路径>：截图前先跑文件里的 JS（避免命令行传参把代码弄坏）
const JS = jsArg ? fs.readFileSync(jsArg.slice(9), 'utf8') : null;
const BASE = 'http://localhost:5173';
const dir = path.join(process.env.TEMP, 'dsh-shots');
fs.mkdirSync(dir, { recursive: true });

/* --keep：造数据那一轮要跨两次运行，跑完不还原（否则数据被清掉，
   下一轮就测不到 blob 引用路径了）。默认仍然自动还原。 */
const KEEP = process.argv.includes('--keep');
const runner = KEEP ? (fn) => fn() : withCleanData;
await runner(async () => {
  const created = await (await fetch(`${CDP}/json/new?about:blank`, { method: 'PUT' })).json();
  let page;
  for (let i = 0; i < 40; i++) {
    const l = await (await fetch(`${CDP}/json/list`)).json();
    page = l.find((t) => t.id === created.id);
    if (page?.webSocketDebuggerUrl) break;
    await new Promise((r) => setTimeout(r, 300));
  }
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  let id = 1;
  const pend = new Map();
  const errors = [];
  ws.addEventListener('message', (e) => {
    const m = JSON.parse(e.data);
    if (m.method === 'Runtime.exceptionThrown') errors.push((m.params.exceptionDetails.exception?.description || '').slice(0, 160));
    if (m.id && pend.has(m.id)) { const h = pend.get(m.id); pend.delete(m.id); m.error ? h.rej(new Error(JSON.stringify(m.error))) : h.res(m.result); }
  });
  await new Promise((r, j) => { ws.addEventListener('open', r); ws.addEventListener('error', j); });
  const send = (method, params = {}, timeout = 150000) =>
    new Promise((res, rej) => {
      const i = id++;
      const tm = setTimeout(() => { pend.delete(i); rej(new Error('timeout')); }, timeout);
      pend.set(i, { res: (v) => { clearTimeout(tm); res(v); }, rej: (e) => { clearTimeout(tm); rej(e); } });
      ws.send(JSON.stringify({ id: i, method, params }));
    });
  const ev = async (expr, a = 0) => {
    try {
      const r = await send('Runtime.evaluate', { expression: `(async()=>{${expr}})()`, awaitPromise: true, returnByValue: true });
      if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description);
      return r.result.value;
    } catch (e) { if (a < 2 && /timeout/i.test(e.message)) { await new Promise((r) => setTimeout(r, 6000)); return ev(expr, a + 1); } throw e; }
  };
  await send('Runtime.enable');
  await send('Page.enable');
  await send('Emulation.setDeviceMetricsOverride', { width: 393, height: 852, deviceScaleFactor: 2, mobile: true });
  await send('Page.navigate', { url: BASE + HASH });
  await new Promise((r) => setTimeout(r, 14000));

  const notch = async () => {
    if (!NOTCH) return;
    await ev(`
      if (!document.getElementById('__notch')) {
        const st = document.createElement('style'); st.id = '__notch';
        st.textContent = ':root{--safe-top:44px;--safe-bottom:24px;}';
        document.head.appendChild(st);
      }
      await new Promise(function(r){ setTimeout(r, 500); });
      return 'ok';
    `);
  };
  await notch();

  if (JS) {
    const jsResult = await ev(`await new Promise(function(r){ setTimeout(r, 1500); }); ${JS}`);
    if (jsResult) console.log('  JS: ' + String(jsResult).slice(0, 2000));
    await notch();
    await new Promise((r) => setTimeout(r, 900));
  } else if (CLICK) {
    await ev(`for (let i=0;i<40;i++){ if(document.querySelector(${JSON.stringify(CLICK)})) break; await new Promise(function(r){setTimeout(r,300);}); }
      document.querySelector(${JSON.stringify(CLICK)}).click();
      await new Promise(function(r){ setTimeout(r, ${WAIT || 3000}); });
      return 'ok';`);
    await notch();
  } else if (WAIT) {
    await new Promise((r) => setTimeout(r, WAIT));
  }

  const s = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: FULL });
  const out = path.join(dir, NAME + '.png');
  fs.writeFileSync(out, Buffer.from(s.data, 'base64'));

  const info = await ev(
    'return JSON.stringify({' +
      ' 路径: location.hash,' +
      ' 文档高: document.documentElement.scrollHeight,' +
      ' 视口: window.innerWidth + "x" + window.innerHeight,' +
      ' 标签栏: (function(){ const s = document.querySelector(".sidebar"); return s ? getComputedStyle(s).display : "无"; })(),' +
      ' 溢出: (function(){ const vw = window.innerWidth; let n = 0;' +
      '   const inScroller = function(el){ let p = el.parentElement; while (p) { const c = getComputedStyle(p); if (c.overflowX === "auto" || c.overflowX === "scroll") return true; p = p.parentElement; } return false; };' +
      '   document.querySelectorAll("*").forEach(function(e){ const c = getComputedStyle(e);' +
      '     if (c.display === "none" || c.position === "fixed") return; if (inScroller(e)) return;' +
      '     const r = e.getBoundingClientRect(); if (r.width > 0 && (r.right > vw + 2 || r.left < -2)) n++; });' +
      '   return n; })(),' +
      ' 错误: ""' +
      '});',
  );
  console.log('  ' + NAME.padEnd(24) + info);
  if (errors.length) console.log('  页面错误: ' + [...new Set(errors)].slice(0, 3).join(' | '));
  console.log('  截图: ' + out);
  ws.close();
  await fetch(`${CDP}/json/close/${page.id}`).catch(() => {});
});
