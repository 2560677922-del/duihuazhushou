// 验证「慢请求期间界面卡死」是否修好 —— **不依赖用户那台服务**。
//
// 用户在标签页生图时界面卡死，根因是 @JavascriptInterface 同步调用
// 把 JS 线程堵住了。这跟"哪台服务器"无关，是**调用方式**的问题。
//
// 所以在开发机上起一个假服务，故意慢 8 秒才回，用它对照：
//   · 同步通道（老 api()）→ 这 8 秒里主线程完全停摆
//   · 异步通道（httpAsync）→ 心跳照常，界面能点
//
// 模拟器用 `adb reverse tcp:9701 tcp:9701` 把 127.0.0.1:9701 转到开发机。
import { withCleanData } from './_testdata.mjs';
import http from 'node:http';

const CDP = 'http://127.0.0.1:' + (process.argv[2] || '9600');
const PORT = Number(process.argv[3] || 9701);
const SLOW_MS = 8000;

let fails = 0;
const check = (label, cond, extra = '') => {
  if (!cond) fails++;
  console.log(`  ${cond ? '✓' : '✗ FAIL'}  ${label}${extra ? '  ' + extra : ''}`);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---- 假服务：故意慢，模拟出图 8 秒 ----
const server = http.createServer((req, res) => {
  if (req.url && req.url.includes('/v1/images/generations')) {
    setTimeout(() => {
      const png = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ data: [{ b64_json: png }] }));
    }, SLOW_MS);
    return;
  }
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ object: 'list', data: [{ id: 'fake-model' }] }));
});

await new Promise((r) => server.listen(PORT, '127.0.0.1', r));
console.log(`  假服务：http://127.0.0.1:${PORT}（出图故意慢 ${SLOW_MS}ms）`);
console.log('  前置：adb reverse tcp:9701 tcp:9701\n');

try {
  await withCleanData(async () => {
    const list = await (await fetch(`${CDP}/json/list`)).json();
    const page = list.find((t) => t.type === 'page');
    if (!page) throw new Error('找不到 WebView');

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
    const send = (method, params = {}, timeout = 600000) => new Promise((res, rej) => {
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
    await send('Page.enable');
    await send('Page.navigate', { url: 'https://dbpg.local/index.html#/tags' });
    await sleep(14000);

    console.log('########## 慢请求期间界面是否卡死 ##########\n');

    const target = `http://127.0.0.1:${PORT}/v1/images/generations`;

    // ============ A. 异步通道（应用现在走这条）============
    console.log('—— A. 异步通道 httpAsync（应用现在走这条）——');
    const asyncProbe = JSON.parse(await ev(`
      const beats = [];
      const timer = setInterval(function() { beats.push(Date.now()); }, 100);
      const t0 = Date.now();

      const reqId = 'slowA_' + Date.now();
      const fin = new Promise(function(resolve) {
        window.__dhpHttpDone = function(rid, ok, status, body) {
          if (rid === reqId) resolve({ ok: ok, status: status, len: (body || '').length });
        };
        window.dhp.httpAsync(
          reqId, 'POST', ${JSON.stringify(target)},
          JSON.stringify({ 'Content-Type': 'application/json' }),
          JSON.stringify({ prompt: 'test', n: 1, size: '512x512' }),
          60000
        );
      });

      // 请求进行中：连点页面上的按钮、跑几次 setTimeout，看有没有被堵
      await new Promise(function(r) { setTimeout(r, 500); });
      const anyBtn = document.querySelector('button');
      let clicks = 0;
      for (let i = 0; i < 5; i++) { if (anyBtn) { anyBtn.click(); clicks++; } }

      let maxTimerErr = 0;
      for (let i = 0; i < 5; i++) {
        const s = Date.now();
        await new Promise(function(r) { setTimeout(r, 100); });
        const e = Date.now() - s - 100;
        if (e > maxTimerErr) maxTimerErr = e;
      }

      const r = await fin;
      clearInterval(timer);
      let maxGap = 0;
      for (let i = 1; i < beats.length; i++) {
        const g = beats[i] - beats[i - 1];
        if (g > maxGap) maxGap = g;
      }
      return JSON.stringify({
        成功: r.ok, 状态: r.status, 响应长度: r.len,
        总耗时秒: Math.round((Date.now() - t0) / 1000),
        心跳数: beats.length,
        最大间隔毫秒: maxGap,
        点到按钮次数: clicks,
        定时器最大误差毫秒: Math.round(maxTimerErr),
      });
    `));
    console.log('  ' + JSON.stringify(asyncProbe));

    check('★ 异步通道能拿到结果', asyncProbe.成功 === true && asyncProbe.状态 === 200, JSON.stringify(asyncProbe));
    check('★★ 慢请求期间主线程没有停摆（最大心跳间隔 < 1 秒）',
      asyncProbe.最大间隔毫秒 < 1000, `${asyncProbe.最大间隔毫秒}ms（总耗时 ${asyncProbe.总耗时秒}s）`);
    check('★★ 慢请求期间能点按钮', asyncProbe.点到按钮次数 === 5, `${asyncProbe.点到按钮次数} 次`);
    check('★★ setTimeout 没有积压（误差 < 500ms）',
      asyncProbe.定时器最大误差毫秒 < 500, `${asyncProbe.定时器最大误差毫秒}ms`);
    check('心跳密度正常（约每 100ms 一次）', asyncProbe.心跳数 >= asyncProbe.总耗时秒 * 5,
      `${asyncProbe.心跳数} 次 / ${asyncProbe.总耗时秒} 秒`);

    // ============ B. 同步通道对照（老版本的行为）============
    console.log('\n—— B. 同步通道 api()（老版本，对照）——');
    const syncProbe = JSON.parse(await ev(`
      const beats = [];
      const timer = setInterval(function() { beats.push(Date.now()); }, 100);
      const t0 = Date.now();

      let res = null;
      try {
        res = JSON.parse(window.dhp.api('httpPost', JSON.stringify({
          url: ${JSON.stringify(target)},
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ prompt: 'test', n: 1, size: '512x512' }),
          timeoutMs: 60000
        })));
      } catch (e) { res = { ok: false, error: String(e) }; }

      clearInterval(timer);
      const elapsed = Date.now() - t0;
      /*
       * 关键：被堵死时 setInterval 根本来不及跑，beats 里只有 0~1 条。
       * 所以别算"间隔"（算不出来），直接看**这 8 秒里心跳跑了几次**。
       * 正常应该 ~80 次；被堵死就是 0~1 次。
       */
      return JSON.stringify({
        ok: res.ok,
        总耗时秒: Math.round(elapsed / 1000),
        心跳数: beats.length,
        应有心跳数: Math.round(elapsed / 100),
      });
    `));
    console.log('  ' + JSON.stringify(syncProbe));

    console.log('\n  对照结论：');
    console.log(`    异步通道：${asyncProbe.总耗时秒}s 里心跳 ${asyncProbe.心跳数} 次  ← 界面一直能动`);
    console.log(`    同步通道：${syncProbe.总耗时秒}s 里心跳 ${syncProbe.心跳数} 次  ← 这段时间界面是死的`);

    check('★★ 同步通道确实会卡死（证明找到了真原因）',
      syncProbe.心跳数 <= 2, `只有 ${syncProbe.心跳数} 次心跳（正常应与 ${syncProbe.应有心跳数} 次相当）`);
    check('★★ 异步通道心跳正常',
      asyncProbe.心跳数 > syncProbe.心跳数 * 10,
      `${asyncProbe.心跳数} 次 vs ${syncProbe.心跳数} 次`);

    console.log(`\n  失败项: ${fails}`);
    ws.close();
    if (fails > 0) process.exitCode = 1;
  });
} finally {
  server.close();
}
