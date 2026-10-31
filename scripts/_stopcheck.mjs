// 验证「正常聊天能停止」+「离开聊天页再回来会提示还在生成中」。
//
// 做法：把 window.fetch 换成一个**我们自己控制的慢速 SSE 流**
//   （每秒吐一段，共 5 段），这样就有足够时间去做"切页面 / 点停止"。
// 注意：切页面用的是 hash 路由（location.hash），**不会重新加载文档** ——
//   所以这个 fetch 替身能一直活着，正好用来观察后台请求有没有继续跑。
import { withCleanData } from './_testdata.mjs';

const CDP = 'http://127.0.0.1:' + (process.argv[2] || '9500');
const BASE = 'http://127.0.0.1:5173';

let fails = 0;
const check = (label, cond, extra = '') => {
  if (!cond) fails++;
  console.log(`  ${cond ? '✓' : '✗ FAIL'}  ${label}${extra ? '  ' + extra : ''}`);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

await withCleanData(async () => {
  const created = await (await fetch(`${CDP}/json/new?about:blank`, { method: 'PUT' })).json();
  let page;
  for (let i = 0; i < 40; i++) {
    const l = await (await fetch(`${CDP}/json/list`)).json();
    page = l.find((t) => t.id === created.id);
    if (page?.webSocketDebuggerUrl) break;
    await sleep(300);
  }
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  let id = 1;
  const pend = new Map();
  const errs = [];
  ws.addEventListener('message', (e) => {
    const m = JSON.parse(e.data);
    if (m.method === 'Runtime.exceptionThrown')
      errs.push((m.params.exceptionDetails.exception?.description || '').slice(0, 160));
    if (m.id && pend.has(m.id)) {
      const h = pend.get(m.id);
      pend.delete(m.id);
      m.error ? h.rej(new Error(JSON.stringify(m.error))) : h.res(m.result);
    }
  });
  await new Promise((r, j) => { ws.addEventListener('open', r); ws.addEventListener('error', j); });
  const send = (method, params = {}, timeout = 180000) =>
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
  await send('Page.enable');
  await send('Emulation.setDeviceMetricsOverride', { width: 393, height: 852, deviceScaleFactor: 2, mobile: true });

  await send('Page.navigate', { url: BASE + '/#/adventure' });
  await sleep(16000);

  console.log('################ 停止按钮 + 回来还在生成中的提示 ################\n');

  // 造一个会话，并把 API 配置填成能用（baseUrl 是假的，反正 fetch 被我们接管了）
  const prep = await ev(`
    const { useAdventureStore } = await import('/src/stores/adventure.ts');
    const { useApiConfigStore } = await import('/src/stores/apiConfig.ts');
    const { useAssetsStore } = await import('/src/stores/assets.ts');
    const adv = useAdventureStore(); await adv.load();
    const api = useApiConfigStore(); await api.load();
    const assets = useAssetsStore(); await assets.load();
    if (!assets.presets || assets.presets.length === 0) {
      try { const m = await import('/src/engine/sampleData.ts'); assets.loadSamples && assets.loadSamples(); } catch(e) {}
    }
    const s = await adv.addSession('__stop_test__', '一片漆黑的洞穴。');
    await adv.switchSession(s.id);
    const cfg = api.currentConfig || (await api.addConfig('测试'));
    await api.updateConfig(cfg._uid, {
      apiKey: 'test-key', model: 'test-model', baseUrl: 'https://example.invalid/v1',
    });
    await api.setCurrent(cfg._uid);
    return JSON.stringify({ 会话id: s.id, 配置: cfg._uid, 预设数: (assets.presets||[]).length });
  `);
  console.log('  准备: ' + prep);
  const sd = JSON.parse(prep);

  // 进对话页
  await ev(`location.hash = '#/adventure/' + ${JSON.stringify(sd.会话id)}; return 1;`);
  await sleep(6000);

  // 装慢速 SSE 替身
  const stub = await ev(`
    window.__aborted = false;
    window.__sent = 0;
    const realFetch = window.fetch;
    window.fetch = function(url, opts) {
      const u = String(url);
      if (u.indexOf('chat/completions') < 0) return realFetch.apply(this, arguments);
      const signal = opts && opts.signal;
      window.__hasSignal = !!signal;
      // 40 段 × 1 秒 —— 必须够长，否则"切走再回来"时流已经正常结束了，
      // 提示条消失是**正确行为**，却会被误判成 bug。（上一版就踩了这个坑）
      const words = Array.from({ length: 40 }, (_, k) => '第' + (k + 1) + '段。');
      let i = 0, timer = null, closed = false;
      const enc = new TextEncoder();
      const stream = new ReadableStream({
        start(controller) {
          const stop = () => {
            closed = true;
            if (timer) clearInterval(timer);
          };
          timer = setInterval(() => {
            if (closed) return;
            if (i < words.length) {
              controller.enqueue(enc.encode('data: ' + JSON.stringify({ choices: [{ delta: { content: words[i] } }] }) + '\\n\\n'));
              window.__sent++;
              i++;
            } else {
              controller.enqueue(enc.encode('data: [DONE]\\n\\n'));
              stop();
              try { controller.close(); } catch(e) {}
            }
          }, 1000);
          const onAbort = () => { stop(); window.__aborted = true; try { controller.error(new DOMException('aborted','AbortError')); } catch(e) {} };
          if (signal) { if (signal.aborted) onAbort(); else signal.addEventListener('abort', onAbort); }
        },
      });
      return Promise.resolve(new Response(stream, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }));
    };
    return 1;
  `);
  void stub;

  // 发一条消息
  const sent = await ev(`
    const ta = document.querySelector('.input-area textarea');
    if (!ta) return JSON.stringify({ 找不到输入框: true });
    ta.value = '我向前走一步';
    ta.dispatchEvent(new Event('input', { bubbles: true }));
    await new Promise(function(r){ setTimeout(r, 300); });
    const b = Array.from(document.querySelectorAll('.input-area button')).find(function(x){ return /发送/.test(x.textContent); });
    if (!b) return JSON.stringify({ 找不到发送按钮: true, 按钮: Array.from(document.querySelectorAll('.input-area button')).map(function(x){return x.textContent.trim();}) });
    b.click();
    return JSON.stringify({ 已点发送: true });
  `);
  console.log('  发送: ' + sent);
  check('点到了发送按钮', JSON.parse(sent).已点发送 === true);

  await sleep(2600);

  // —— 检查 1：生成中，停止按钮在
  const mid = await ev(`
    const banner = document.querySelector('.gen-banner');
    const stopBtn = document.querySelector('.gen-stop');
    const inputStop = Array.from(document.querySelectorAll('.input-area button')).find(function(x){ return /停止/.test(x.textContent); });
    const sendBtn = Array.from(document.querySelectorAll('.input-area button')).find(function(x){ return /发送/.test(x.textContent); });
    const streamed = document.querySelector('.message-group .msg-content');
    return JSON.stringify({
      有提示条: !!banner,
      提示条文字: banner ? banner.textContent.replace(/\\s+/g,' ').trim() : '',
      有停止按钮: !!stopBtn,
      输入区有停止按钮: !!inputStop,
      输入区还有发送按钮: !!sendBtn,
      已收到段落: window.__sent,
      传了signal给fetch: window.__hasSignal === true,
      正文片段: streamed ? streamed.textContent.slice(0, 40) : '',
    });
  `);
  const md = JSON.parse(mid);
  console.log('  生成中: ' + mid);
  check('显示了「正在生成」提示条', md.有提示条 === true);
  check('提示条里有停止按钮', md.有停止按钮 === true);
  check('输入区把「发送」换成了「停止」', md.输入区有停止按钮 === true && md.输入区还有发送按钮 === false);
  check('确实收到了流式内容', md.已收到段落 >= 2, `收到 ${md.已收到段落} 段`);
  check('★ 把 AbortSignal 传给了 fetch（停止才有可能生效）', md.传了signal给fetch === true);

  // —— 检查 2：切走再回来
  await ev(`location.hash = '#/assets'; return 1;`);
  await sleep(2000);
  const away = await ev(`
    return JSON.stringify({
      还在跑: window.__aborted === false,
      又收到几段: window.__sent,
    });
  `);
  console.log('  切到资产页: ' + away);
  const aw = JSON.parse(away);
  check('★ 离开页面时请求没有被掐断（还在后台继续）', aw.还在跑 === true);

  await ev(`location.hash = '#/adventure/' + ${JSON.stringify(sd.会话id)}; return 1;`);
  await sleep(2000);
  const back = await ev(`
    const banner = document.querySelector('.gen-banner');
    const hint = document.querySelector('.gen-hint');
    const stopBtn = document.querySelector('.gen-stop');
    return JSON.stringify({
      有提示条: !!banner,
      提示条文字: banner ? banner.textContent.replace(/\\s+/g,' ').trim() : '',
      有继续提示: hint ? hint.textContent.trim() : '',
      有停止按钮: !!stopBtn,
    });
  `);
  const bk = JSON.parse(back);
  console.log('  切回来: ' + back);
  check('★ 回到聊天页仍然显示「正在生成」', bk.有提示条 === true);
  check('★ 并且明确说明"离开时也没断"', /离开页面时也没断/.test(bk.有继续提示), bk.有继续提示);
  check('回来之后停止按钮照样能用', bk.有停止按钮 === true);

  // —— 检查 3：点停止
  //   先确保至少已经流出一段，这样走的是"保留已生成部分"的分支
  const stopped = await ev(`
    const b = document.querySelector('.gen-stop') || Array.from(document.querySelectorAll('.input-area button')).find(function(x){ return /停止/.test(x.textContent); });
    if (!b) return JSON.stringify({ 找不到停止按钮: true });
    b.click();
    return JSON.stringify({ 已点停止: true, 点停止前收到: window.__sent });
  `);
  console.log('  点停止: ' + stopped);
  check('点到了停止按钮', JSON.parse(stopped).已点停止 === true);

  await sleep(2500);
  const after = await ev(`
    const banner = document.querySelector('.gen-banner');
    const sendBtn = Array.from(document.querySelectorAll('.input-area button')).find(function(x){ return /发送/.test(x.textContent); });
    const bubbles = document.querySelectorAll('.message.ai .msg-content');
    const last = bubbles.length ? bubbles[bubbles.length-1].textContent : '';
    const errBar = document.querySelector('.error-bar');
    return JSON.stringify({
      fetch被中止: window.__aborted === true,
      提示条消失: !banner,
      输入区恢复发送: !!sendBtn,
      最后一条AI: last.slice(0, 50),
      有报错条: !!errBar,
      报错文字: errBar ? errBar.textContent.replace(/\\s+/g,' ').trim() : '',
    });
  `);
  const af = JSON.parse(after);
  console.log('  停止后: ' + after);
  check('★ 底层的 fetch 确实被中止了', af.fetch被中止 === true);
  check('停止后提示条消失', af.提示条消失 === true);
  check('停止后输入区恢复成「发送」', af.输入区恢复发送 === true);
  check('停止后没有弹出报错（中止不算错误）', af.有报错条 === false, af.报错文字);
  check('已经生成的部分被保留成了正式回复', af.最后一条AI.length > 0, af.最后一条AI);

  // —— 检查 4：马上停止（一个字都没出来）→ 内容应该还给输入框
  await ev(`
    window.__sent = 0;
    const ta = document.querySelector('.input-area textarea');
    ta.value = '打错字了';
    ta.dispatchEvent(new Event('input', { bubbles: true }));
    await new Promise(function(r){ setTimeout(r, 200); });
    const b = Array.from(document.querySelectorAll('.input-area button')).find(function(x){ return /发送/.test(x.textContent); });
    b.click();
    return 1;
  `);
  await sleep(250);
  await ev(`
    const b = document.querySelector('.gen-stop') || Array.from(document.querySelectorAll('.input-area button')).find(function(x){ return /停止/.test(x.textContent); });
    if (b) b.click();
    return 1;
  `);
  await sleep(2000);
  const early = await ev(`
    const ta = document.querySelector('.input-area textarea');
    return JSON.stringify({
      输入框内容: ta ? ta.value : '',
      提示条消失: !document.querySelector('.gen-banner'),
    });
  `);
  const ea = JSON.parse(early);
  console.log('  立刻停止: ' + early);
  check('★ 立刻停止时内容还回输入框（"输入错了"的场景）', ea.输入框内容 === '打错字了', `实际="${ea.输入框内容}"`);
  check('立刻停止后提示条也消失', ea.提示条消失 === true);

  console.log('\n  页面错误: ' + (errs.length ? [...new Set(errs)].slice(0, 4).join(' | ') : '无'));
  console.log(`  失败项: ${fails}`);
  ws.close();
  await fetch(`${CDP}/json/close/${page.id}`).catch(() => {});
  if (fails > 0) process.exitCode = 1;
});
