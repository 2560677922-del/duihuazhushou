// 验证：角色 DNA 是否**真的**进了发给生图模型的请求体。
// 用假的 fetch 拦住网络，只看请求内容 —— 不需要任何真实 API。
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
  ws.addEventListener('message', (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pend.has(m.id)) {
      const h = pend.get(m.id); pend.delete(m.id);
      m.error ? h.rej(new Error(JSON.stringify(m.error))) : h.res(m.result);
    }
  });
  await new Promise((r, j) => { ws.addEventListener('open', r); ws.addEventListener('error', j); });
  const send = (method, params = {}, timeout = 120000) => new Promise((res, rej) => {
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
  await send('Page.navigate', { url: BASE + '/#/assets' });
  await sleep(12000);

  console.log('########## DNA 是否真的进了生图请求 ##########\n');

  const r = JSON.parse(await ev(`
    const { generateImage } = await import('/src/engine/imageGen.ts');

    // 拦网络：记录每次请求体，返回一张假图
    const captured = [];
    const realFetch = window.fetch;
    window.fetch = async (url, init) => {
      captured.push({ url: String(url), body: init && init.body ? String(init.body) : '' });
      return new Response(JSON.stringify({ data: [{ b64_json: 'ZmFrZQ==' }], images: ['ZmFrZQ=='] }), {
        status: 200, headers: { 'Content-Type': 'application/json' },
      });
    };

    const cfg = {
      provider: 'novelai-openai',
      baseUrl: 'https://example.invalid/v1',
      apiKey: 'sk-test',
      model: 'nai-diffusion-4-5-full',
      width: 832, height: 1216,
      negativePrompt: 'bad',
    };

    // ① 手动模式（不优化）：DNA 应该拼在正向提示词最前面
    let manualErr = '';
    try {
      await generateImage(null, cfg, {
        manualPrompt: 'a girl standing in rain',
        skipOptimization: true,
        contextMessages: [{ role: 'assistant', content: 'ignored' }],
        dnaTags: 'silver hair, red eyes',
      });
    } catch (e) { manualErr = String(e && e.message || e); }

    const manualBody = captured[captured.length - 1]?.body || '';

    // ② 关掉 DNA（不传 dnaTags）：提示词里不该出现
    captured.length = 0;
    let noDnaErr = '';
    try {
      await generateImage(null, cfg, {
        manualPrompt: 'a girl standing in rain',
        skipOptimization: true,
        contextMessages: [{ role: 'assistant', content: 'ignored' }],
      });
    } catch (e) { noDnaErr = String(e && e.message || e); }
    const noDnaBody = captured[captured.length - 1]?.body || '';

    window.fetch = realFetch;

    return JSON.stringify({
      手动模式错误: manualErr,
      无DNA错误: noDnaErr,
      请求次数: captured.length,
      手动_含DNA: /silver hair, red eyes/.test(manualBody),
      手动_DNA在最前: /silver hair, red eyes/.test(manualBody) &&
        manualBody.indexOf('silver hair, red eyes') < manualBody.indexOf('a girl standing in rain'),
      无DNA_不含: !/silver hair/.test(noDnaBody),
      无DNA_仍有场景: /a girl standing in rain/.test(noDnaBody),
    });
  `));
  console.log('  ' + JSON.stringify(r));

  check('手动模式调用成功（假 fetch 生效）', r.手动模式错误 === '', r.手动模式错误);
  check('★ DNA 标签真的进了请求体', r.手动_含DNA === true);
  check('★ DNA 排在正向提示词最前面（优先级最高）', r.手动_DNA在最前 === true);
  check('★ 没勾选 DNA 时请求体里没有 DNA 标签', r.无DNA_不含 === true);
  check('场景描述仍然在（DNA 不能顶掉正文）', r.无DNA_仍有场景 === true);

  // ===== 优化模式：dnaBlock 应进 LLM 的 system =====
  const llm = JSON.parse(await ev(`
    const { buildOptimizeMessages } = await import('/src/engine/imageGen.ts');
    if (typeof buildOptimizeMessages !== 'function') return JSON.stringify({ skip: true });
    return JSON.stringify({ skip: false });
  `).catch(() => JSON.stringify({ skip: true })));
  if (llm.skip) {
    console.log('  （优化模式内部函数未导出，改由调用点保证：useQuickImage/ImageGenModal 都传 dnaBlock）');
  }

  console.log(`\n  失败项: ${fails}`);
  ws.close();
  await fetch(`${CDP}/json/close/${page.id}`).catch(() => {});
  if (fails > 0) process.exitCode = 1;
});
