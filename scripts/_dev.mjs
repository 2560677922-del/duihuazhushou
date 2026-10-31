// 模拟器 WebView 调试小工具：导航到某个 hash、截屏、或执行一段 JS。
//
// 用法：
//   node scripts/_dev.mjs shot <hash> <输出png>          导航并截屏
//   node scripts/_dev.mjs eval <hash> <js表达式>         导航并求值，打印结果
//   node scripts/_dev.mjs scroll <hash> <y> <输出png>    导航、滚到 y、截屏
import fs from 'node:fs';
import { setTimeout as sleep } from 'node:timers/promises';

const PORT = process.env.WV_PORT || '9600';
const [mode, hash, arg3, arg4] = process.argv.slice(2);

const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
const page = list.find((t) => t.type === 'page');
if (!page) { console.error('没找到 WebView 页面，先确认 adb forward 还在'); process.exit(1); }

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
const send = (method, params = {}, timeout = 60000) => new Promise((res, rej) => {
  const i = id++;
  const tm = setTimeout(() => { pend.delete(i); rej(new Error('timeout ' + method)); }, timeout);
  pend.set(i, { res: (v) => { clearTimeout(tm); res(v); }, rej: (e) => { clearTimeout(tm); rej(e); } });
  ws.send(JSON.stringify({ id: i, method, params }));
});

const ev = async (expr) => {
  const r = await send('Runtime.evaluate', { expression: `(async()=>{${expr}})()`, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || 'eval 失败');
  return r.result.value;
};

if (mode === 'shot' || mode === 'scroll') {
  await ev(`location.hash = ${JSON.stringify(hash)}; return 1;`);
  await sleep(3500);
  if (mode === 'scroll') {
    await ev(`window.scrollTo(0, ${Number(arg3) || 0}); return 1;`);
    await sleep(800);
  }
  const out = mode === 'shot' ? arg3 : arg4;
  const { data } = await send('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(out, Buffer.from(data, 'base64'));
  console.log('已保存 ' + out);
} else if (mode === 'eval') {
  await ev(`location.hash = ${JSON.stringify(hash)}; return 1;`);
  await sleep(3500);
  console.log(await ev(arg3));
} else {
  console.error('未知模式');
}
ws.close();
