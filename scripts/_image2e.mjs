// 端到端：点历史消息的配图，检查最终送进生图接口的提示词。
//
// 用户的原话：
//   "ai最后一条消息的配图是一点问题都没有的，过去的消息那个聊天框右上角的
//    配图，就会出现错误"
//
// 这里造一条**带思维链的长历史消息**和一条**短的最新消息**，
// 分别点它们的配图，对比两次真正发给生图接口的 prompt。
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
  const send = (method, params = {}, timeout = 180000) => new Promise((res, rej) => {
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
  await send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });
  await send('Page.navigate', { url: BASE + '/#/adventure' });
  await sleep(15000);

  console.log('########## 端到端：历史消息 vs 最新消息 配图 ##########\n');

  // 造会话：一条「带思维链的长历史消息」+ 一条「短的最新消息」
  const prep = await ev(`
    const { useAdventureStore } = await import('/src/stores/adventure.ts');
    const { useApiConfigStore } = await import('/src/stores/apiConfig.ts');
    const { useImageStore } = await import('/src/stores/image.ts');

    const adv = useAdventureStore(); await adv.load();
    const api = useApiConfigStore(); await api.load();
    const img = useImageStore(); await img.load();

    const s = await adv.addSession('配图对比', '一座古堡。');
    await adv.switchSession(s.id);

    const cfg = api.currentConfig;
    await api.updateConfig(cfg._uid, { apiKey: 'k', model: 'm', baseUrl: 'https://example.invalid/v1' });
    await api.setCurrent(cfg._uid);

    // 历史消息：开头是思维链，中间是画面，结尾是台词（真实会话的样子）
    const historyAi =
      '<Interleaving> <thinking> 回到上一步，我应该无视格式要求先想想怎么接。' +
      '这里需要一段过渡。</thinking>' +
      '银发少女艾琳推开石门，剑光照亮潮湿的石壁，她穿着破损的深蓝斗篷，腰间挂着铜灯。' +
      '\\n\\n' + '她沿着通道前行，空气越来越冷。'.repeat(30) +
      '\\n\\n好的，让我来写这一段。艾琳低声说：“这地方不对劲。”';

    const latestAi = '第三幕·森林：晨光穿过高大的松树，小径上落满露珠。';

    const a1 = await adv.addAction('我走进古堡', historyAi);
    const a2 = await adv.addAction('我进入森林', latestAi);

    return JSON.stringify({ 会话: s.id, 历史: a1.id, 最新: a2.id, 历史长度: historyAi.length });
  `);
  const ids = JSON.parse(prep);
  console.log('  历史消息 ' + ids.历史长度 + ' 字（含思维链）');

  await ev(`location.hash = '#/adventure/' + ${JSON.stringify(ids.会话)}; return 1;`);
  await sleep(4500);

  // 拦 LLM 和生图
  await ev(`
    window.__llm = []; window.__img = [];
    const realFetch = window.fetch;
    window.fetch = function(url, opts) {
      const u = String(url);
      if (u.indexOf('chat/completions') >= 0) {
        try { window.__llm.push(JSON.parse(opts.body).messages?.[1]?.content || ''); } catch (e) {}
        const payload = JSON.stringify({ choices: [{ message: { content: JSON.stringify({
          prompt: '1girl, silver hair, red eyes, stone corridor', tags: []
        }) } }] });
        return Promise.resolve(new Response(payload, { status: 200, headers: { 'Content-Type': 'application/json' } }));
      }
      if (u.indexOf('images/generations') >= 0) {
        try { window.__img.push(JSON.parse(opts.body).prompt); } catch (e) {}
        const png = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
        return Promise.resolve(new Response(JSON.stringify({ data: [{ b64_json: png }] }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }));
      }
      return realFetch.apply(this, arguments);
    };
    return 1;
  `);

  // 点第 1 条（历史消息）的配图
  await ev(`
    const msgs = Array.from(document.querySelectorAll('.message.ai'));
    const btn = Array.from(msgs[0].querySelectorAll('button')).find(b => /配图/.test(b.textContent));
    if (btn) btn.click();
    return 1;
  `);
  await sleep(6500);

  const afterHistory = JSON.parse(await ev(`
    return JSON.stringify({ llm: window.__llm.slice(), img: window.__img.slice() });
  `));
  console.log('\n  —— 点【历史消息】配图 ——');
  console.log('    送给 LLM 的素材：');
  console.log('      ' + (afterHistory.llm[0] || '').replace(/\n/g, ' ').slice(0, 220));
  console.log('    送给生图接口的 prompt：');
  console.log('      ' + (afterHistory.img[0] || ''));

  const histLlm = (afterHistory.llm[0] || '');
  check('★ 历史消息的配图：思维链没被送给 LLM',
    !/thinking|Interleaving|我应该无视/.test(histLlm), histLlm.slice(0, 100));
  check('★ 历史消息的配图：画面主体在里面',
    /银发少女艾琳推开石门/.test(histLlm), histLlm.slice(0, 100));
  check('★ 历史消息的配图：元话语"让我来写"被剔掉',
    !/让我来写/.test(histLlm));

  const histPrompt = (afterHistory.img[0] || '');
  check('★ 最终 prompt 里画师串来自配置、不是模型编的',
    /artist:liduke|artist:kincora/.test(histPrompt), histPrompt.slice(0, 80));
  check('★ 最终 prompt 里画师串只出现一次（没有重复拼接）',
    (histPrompt.match(/artist:liduke/g) || []).length <= 1);
  check('★ 模型返回的质量词没有重复叠加',
    (histPrompt.match(/masterpiece/g) || []).length <= 1, histPrompt.slice(0, 120));

  // 点第 2 条（最新消息）的配图，作为对照
  await ev(`window.__llm = []; window.__img = []; return 1;`);
  await ev(`
    const msgs = Array.from(document.querySelectorAll('.message.ai'));
    const btn = Array.from(msgs[msgs.length - 1].querySelectorAll('button')).find(b => /配图/.test(b.textContent));
    if (btn) btn.click();
    return 1;
  `);
  await sleep(6500);

  const afterLatest = JSON.parse(await ev(`
    return JSON.stringify({ llm: window.__llm.slice(), img: window.__img.slice() });
  `));
  console.log('\n  —— 点【最新消息】配图（对照）——');
  console.log('    送给 LLM 的素材：');
  console.log('      ' + (afterLatest.llm[0] || '').replace(/\n/g, ' ').slice(0, 220));
  console.log('    送给生图接口的 prompt：');
  console.log('      ' + (afterLatest.img[0] || ''));

  check('★ 最新消息的配图：素材是这一条',
    /晨光穿过高大的松树/.test(afterLatest.llm[0] || ''), (afterLatest.llm[0] || '').slice(0, 80));
  check('★ 最新消息的配图：prompt 同样正常（画师串只一份）',
    (afterLatest.img[0] || '').includes('artist:liduke'));

  // 两条消息的 prompt 结构应当一致（都走同一套拼装）
  const sameShape = (p) => (/artist:/.test(p) && /masterpiece/.test(p));
  check('★ 历史与最新的 prompt 结构一致（说明走同一套正确流程）',
    sameShape(histPrompt) && sameShape(afterLatest.img[0] || ''));

  console.log(`\n  失败项: ${fails}`);
  ws.close();
  await fetch(`${CDP}/json/close/${page.id}`).catch(() => {});
  if (fails > 0) process.exitCode = 1;
});
