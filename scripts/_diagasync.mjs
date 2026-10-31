// 看 httpAsync 返回的那 95 字节到底是什么错。
const CDP = 'http://127.0.0.1:' + (process.env.WV_PORT || '9600');
const BASE_URL = process.env.LOCAL_URL || 'http://192.168.137.1:8899';
const KEY = process.env.LOCAL_KEY || '123456';

const list = await (await fetch(`${CDP}/json/list`)).json();
const page = list.find((t) => t.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 1; const pend = new Map();
ws.addEventListener('message', (e) => {
  const m = JSON.parse(e.data);
  if (m.id && pend.has(m.id)) { const h = pend.get(m.id); pend.delete(m.id); m.error ? h.rej(new Error(JSON.stringify(m.error))) : h.res(m.result); }
});
await new Promise((r, j) => { ws.addEventListener('open', r); ws.addEventListener('error', j); });
const send = (method, params = {}, timeout = 300000) => new Promise((res, rej) => {
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

const out = await ev(`
  const reqId = 'diag_' + Date.now();
  const fin = new Promise(function(resolve) {
    window.__dhpHttpDone = function(rid, ok, status, body) {
      if (rid === reqId) resolve({ ok: ok, status: status, body: body });
    };
    window.dhp.httpAsync(
      reqId, 'POST', ${JSON.stringify(BASE_URL + '/v1/images/generations')},
      JSON.stringify({ 'Content-Type': 'application/json', Authorization: 'Bearer ' + ${JSON.stringify(KEY)} }),
      JSON.stringify({
        prompt: '1girl, silver hair, red eyes, simple background',
        n: 1, size: '512x512', steps: 28, response_format: 'b64_json',
        model: 'nai-anime-v2.ckpt'
      }),
      300000
    );
  });
  const r = await fin;
  return JSON.stringify({
    ok: r.ok,
    status: r.status,
    长度: (r.body || '').length,
    内容: String(r.body || '').slice(0, 400),
  });
`);
console.log('httpAsync 结果:');
console.log(JSON.stringify(JSON.parse(out), null, 2));

// 看看同步版（老通道）对同一个请求是成功还是同样失败
const cmp = await ev(`
  try {
    const r = JSON.parse(window.dhp.api('httpPost', JSON.stringify({
      url: ${JSON.stringify(BASE_URL + '/v1/images/generations')},
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + ${JSON.stringify(KEY)} },
      body: JSON.stringify({
        prompt: '1girl, silver hair, red eyes, simple background',
        n: 1, size: '512x512', steps: 28, response_format: 'b64_json',
        model: 'nai-anime-v2.ckpt'
      }),
      timeoutMs: 300000
    })));
    return JSON.stringify({
      同步版ok: r.ok,
      状态: r.value ? r.value.status : null,
      长度: r.value ? String(r.value.body || '').length : 0,
      错误: r.error || '',
      片段: r.value ? String(r.value.body || '').slice(0, 200) : '',
    });
  } catch (e) {
    return JSON.stringify({ 同步版异常: String(e && e.message || e).slice(0, 200) });
  }
`);
console.log('\n同步版（老通道）对照:');
console.log(JSON.stringify(JSON.parse(cmp), null, 2));

ws.close();
