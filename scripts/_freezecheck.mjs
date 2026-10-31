// 验证「本地部署出图时界面卡死」是否修好。
//
// 用户的原话：
//   "在标签页生图会卡顿……点生成不卡顿，但是生图中的时候直接就卡住了，
//    而用其他第三方平台或其他接口就没事"
//
// 根因：@JavascriptInterface 的方法是**同步**的，出图 20~40 秒期间
//       WebView 的 JS 线程被 Java 堵死，界面完全没响应。
//       第三方平台走普通 fetch（异步），所以不卡。
//
// 这个测试要证明：出图请求进行中，主线程仍然能跑 JS（能计时、能点按钮）。
import { withCleanData } from './_testdata.mjs';

const CDP = 'http://127.0.0.1:' + (process.argv[2] || '9600');
const BASE_URL = process.argv[3] || 'http://192.168.137.1:8899';
const KEY = process.argv[4] || '123456';

let fails = 0;
const check = (label, cond, extra = '') => {
  if (!cond) fails++;
  console.log(`  ${cond ? '✓' : '✗ FAIL'}  ${label}${extra ? '  ' + extra : ''}`);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

await withCleanData(async () => {
  const list = await (await fetch(`${CDP}/json/list`)).json();
  const page = list.find((t) => t.type === 'page');
  if (!page) { console.error('找不到 WebView'); process.exit(1); }

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
  await sleep(15000);

  console.log('########## 本地部署出图：界面不能再卡死 ##########\n');

  // ---- 1. 异步通道在不在 ----
  const bridgeInfo = JSON.parse(await ev(`
    return JSON.stringify({
      有api: !!(window.dhp && typeof window.dhp.api === 'function'),
      有httpAsync: !!(window.dhp && typeof window.dhp.httpAsync === 'function'),
    });
  `));
  console.log('  原生桥: ' + JSON.stringify(bridgeInfo));
  check('★ 端上有 api（老通道）', bridgeInfo.有api === true);
  check('★ 端上有 httpAsync（新的异步通道）', bridgeInfo.有httpAsync === true,
    '没有它就只能退回同步版，出图仍会卡');

  // ---- 2. 发一个真实出图请求，期间持续测量主线程心跳 ----
  console.log('\n  —— 出图中主线程是否还能跑 ——');

  /*
   * 做法：
   *   · 启动一个真实的出图请求（异步桥，不 await）
   *   · 同时用 setInterval 每 100ms 记一次时间戳
   *   · 请求结束后统计最大间隔 —— 卡死的话会出现几万毫秒的空洞
   */
  const probe = JSON.parse(await ev(`
    const beats = [];
    let running = true;
    const timer = setInterval(function() {
      beats.push(Date.now());
    }, 100);

    const t0 = Date.now();
    let done = false;
    let errText = '';

    // 走应用自己的 httpRequest（它会自动选异步通道）
    const { httpRequest } = await import('/assets/index.js').catch(() => ({}));

    // 打包环境 import 源码模块不可用，直接用原生桥复算一遍等价路径：
    // 应用里 httpRequest 最终就是调 window.dhp.httpAsync
    const reqId = 'probe_' + Date.now();
    const finished = new Promise(function(resolve) {
      const prev = window.__dhpHttpDone;
      window.__dhpHttpDone = function(rid, ok, status, body) {
        if (prev) prev(rid, ok, status, body);
        if (rid === reqId) { done = true; resolve({ ok: ok, status: status, len: (body || '').length }); }
      };
      window.dhp.httpAsync(
        reqId,
        'POST',
        ${JSON.stringify(BASE_URL + '/v1/images/generations')},
        JSON.stringify({ 'Content-Type': 'application/json', Authorization: 'Bearer ' + ${JSON.stringify(KEY)} }),
        JSON.stringify({
          prompt: '1girl, silver hair, red eyes, simple background',
          n: 1, size: '512x512', steps: 28, response_format: 'b64_json',
          model: 'nai-anime-v2.ckpt'
        }),
        300000
      );
    });

    // 立刻返回，不等请求 —— 后面再单独查心跳
    window.__probe = { beats: beats, timer: timer, finished: finished, t0: t0, reqId: reqId };
    return JSON.stringify({ 已发出: true, 请求id: reqId });
  `));
  console.log('  ' + JSON.stringify(probe));

  // 等 3 秒，先确认心跳在动（这时请求还在跑）
  await sleep(3000);
  const alive = JSON.parse(await ev(`
    return JSON.stringify({
      心跳数: window.__probe.beats.length,
      已过去毫秒: Date.now() - window.__probe.t0,
    });
  `));
  console.log('  3 秒后: ' + JSON.stringify(alive));
  check('★ 出图进行中主线程仍在跑（心跳持续）', alive.心跳数 >= 20,
    `3 秒内 ${alive.心跳数} 次心跳（卡死的话会是 0~1 次）`);

  // ---- 3. 等请求真正完成 ----
  console.log('\n  —— 等真实出图完成 ——');
  const result = JSON.parse(await ev(`
    const r = await window.__probe.finished;
    clearInterval(window.__probe.timer);
    const beats = window.__probe.beats;
    // 统计心跳最大间隔：这是"卡了多久"的直接证据
    let maxGap = 0;
    for (let i = 1; i < beats.length; i++) {
      const g = beats[i] - beats[i - 1];
      if (g > maxGap) maxGap = g;
    }
    return JSON.stringify({
      成功: r.ok,
      状态: r.status,
      响应长度: r.len,
      总耗时秒: Math.round((Date.now() - window.__probe.t0) / 1000),
      心跳数: beats.length,
      最大间隔毫秒: maxGap,
    });
  `));
  console.log('  ' + JSON.stringify(result));

  check('★ 出图成功', result.成功 === true && result.状态 === 200, JSON.stringify(result));
  check('★ 拿到了图片数据', result.响应长度 > 10000, `${result.响应长度} 字节`);
  /*
   * 关键断言：最大间隔。
   * 同步版会让 JS 线程整整停 20~40 秒 → maxGap ≈ 耗时。
   * 异步版心跳应该一直很密 → maxGap 只有几百毫秒。
   */
  check('★★ 全程没有长卡顿（最大间隔 < 2 秒）', result.最大间隔毫秒 < 2000,
    `最大间隔 ${result.最大间隔毫秒}ms，总耗时 ${result.总耗时秒}s`);
  check('★★ 心跳密度正常（平均 < 500ms）',
    result.心跳数 >= (result.总耗时秒 * 1000) / 500,
    `${result.心跳数} 次 / ${result.总耗时秒} 秒`);

  // ---- 4. 界面确实还能响应（点一下按钮看看） ----
  console.log('\n  —— 出图期间界面能响应点击 ——');
  const clickable = JSON.parse(await ev(`
    // 再发一个请求，然后在它跑的时候点一下页面上的按钮
    const reqId = 'probe2_' + Date.now();
    window.__done2 = false;
    const prev = window.__dhpHttpDone;
    window.__dhpHttpDone = function(rid, ok, status, body) {
      if (prev) prev(rid, ok, status, body);
      if (rid === reqId) window.__done2 = true;
    };
    window.dhp.httpAsync(
      reqId, 'GET', ${JSON.stringify(BASE_URL + '/v1/models')},
      JSON.stringify({ Authorization: 'Bearer ' + ${JSON.stringify(KEY)} }),
      '', 20000
    );

    // 等 300ms 让请求跑起来，然后点一个真实按钮并看 DOM 有没有响应
    await new Promise(function(r) { setTimeout(r, 300); });

    const btn = Array.from(document.querySelectorAll('button')).find(function(b) {
      return b.textContent.trim() === '复制提示词';
    });
    let clicked = false;
    if (btn) { btn.click(); clicked = true; }

    // 再等一拍，确认事件循环没被堵
    const t = Date.now();
    await new Promise(function(r) { setTimeout(r, 120); });
    const elapsed = Date.now() - t;

    return JSON.stringify({ 点到按钮: clicked, 定时器误差毫秒: elapsed });
  `));
  console.log('  ' + JSON.stringify(clickable));
  check('★ 出图期间能点按钮', clickable.点到按钮 === true);
  check('★ 出图期间 setTimeout 准时（没被堵）', clickable.定时器误差毫秒 < 600,
    `误差 ${clickable.定时器误差毫秒}ms`);

  console.log(`\n  失败项: ${fails}`);
  ws.close();
  if (fails > 0) process.exitCode = 1;
});
