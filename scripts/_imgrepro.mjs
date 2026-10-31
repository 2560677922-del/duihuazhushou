// 复现「点历史消息的配图，图跑到别处 / 内容对不上」
//
// 关键怀疑点：quickImageFor 里的
//     await adventure.updateAction(action.id, { ai: action.ai + marker });
// action 是 v-for 里拿到的**快照**。如果这条消息之前已经被改过（比如
// 配过一次图、或重新生成过），闭包里的 action.ai 就是**旧文本**，
// 这一写会把新内容覆盖回去。
//
// 另一个怀疑点：标记是追加到末尾的，而 buildContentBlocks 是按
// 「标记出现顺序 ↔ attachments 顺序」配对。如果这条消息本来就有
// 自动配图留下的标记，新追加的标记会排在最末，配对就错位。
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
    const tm = setTimeout(() => { pend.delete(i); rej(new Error('timeout')); }, timeout);
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

  console.log('########## 历史消息配图：定位与内容 ##########\n');

  // 造三条消息，每条内容都不同，便于分辨
  const prep = await ev(`
    const { useAdventureStore } = await import('/src/stores/adventure.ts');
    const { useApiConfigStore } = await import('/src/stores/apiConfig.ts');
    const adv = useAdventureStore(); await adv.load();
    const api = useApiConfigStore(); await api.load();
    const s = await adv.addSession('历史配图测试', '一座古堡。');
    await adv.switchSession(s.id);
    const cfg = api.currentConfig;
    await api.updateConfig(cfg._uid, { apiKey: 'k', model: 'm', baseUrl: 'https://example.invalid/v1' });
    await api.setCurrent(cfg._uid);
    // 三条：第一条是"冰原"，第二条"火炉"，第三条（最新）"森林"
    const a1 = await adv.addAction('我走进冰原', '第一幕·冰原：暴风雪中一头白色巨狼站在冰川上，雪花飞扬。');
    const a2 = await adv.addAction('我靠近火炉', '第二幕·火炉：一间温暖的石屋里炉火熊熊，木桌上放着热汤。');
    const a3 = await adv.addAction('我进入森林', '第三幕·森林：晨光穿过高大的松树，小径上落满露珠。');
    return JSON.stringify({ 会话: s.id, 第一条: a1.id, 第二条: a2.id, 第三条: a3.id });
  `);
  const ids = JSON.parse(prep);
  console.log('  三条消息: ' + prep);

  await ev(`location.hash = '#/adventure/' + ${JSON.stringify(ids.会话)}; return 1;`);
  await sleep(4500);

  /*
   * 关键：拦下发给 LLM 的请求，看看**实际送给模型的上下文是什么**。
   * 只要不是在说"冰原"，就证明内容取错了。
   */
  await ev(`
    window.__llmCalls = [];
    const realFetch = window.fetch;
    window.fetch = function(url, opts) {
      const u = String(url);
      if (u.indexOf('chat/completions') >= 0) {
        try {
          const body = JSON.parse(opts.body);
          window.__llmCalls.push({
            system: String(body.messages?.[0]?.content || '').slice(0, 300),
            user: String(body.messages?.[1]?.content || '').slice(0, 400),
          });
        } catch (e) {}
        // 回一个固定格式的"优化结果"
        const payload = JSON.stringify({ choices: [{ message: { content: JSON.stringify({
          prompt: 'A_TAG_FROM_LLM', negativePrompt: '', tags: []
        }) } }] });
        return Promise.resolve(new Response(payload, { status: 200, headers: { 'Content-Type': 'application/json' } }));
      }
      // 生图接口：记录送来的 prompt，然后回一张假图
      if (u.indexOf('images/generations') >= 0) {
        try {
          const body = JSON.parse(opts.body);
          window.__imgCalls = window.__imgCalls || [];
          window.__imgCalls.push({ url: u, prompt: body.prompt, negative: body.negative_prompt });
        } catch (e) {}
        const png = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
        const payload = JSON.stringify({ data: [{ b64_json: png.replace('data:image/png;base64,', '') }] });
        return Promise.resolve(new Response(payload, { status: 200, headers: { 'Content-Type': 'application/json' } }));
      }
      return realFetch.apply(this, arguments);
    };
    return 1;
  `);

  // 点**第一条**（历史消息）的配图
  const clicked = await ev(`
    const msgs = Array.from(document.querySelectorAll('.message.ai'));
    if (msgs.length < 3) return JSON.stringify({ 消息数不足: msgs.length });
    const first = msgs[0];
    const btn = Array.from(first.querySelectorAll('button')).find(b => /配图/.test(b.textContent));
    if (!btn) return JSON.stringify({ 找不到配图按钮: true, 现有按钮: Array.from(first.querySelectorAll('button')).map(b=>b.textContent.trim()) });
    btn.click();
    return JSON.stringify({ 已点击第几条: '第1条（冰原）', 消息数: msgs.length });
  `);
  console.log('  点击: ' + clicked);
  await sleep(6000);

  const seen = JSON.parse(await ev(`
    return JSON.stringify({
      LLM调用: (window.__llmCalls || []).map(c => ({ system头: c.system.slice(0,80), user: c.user })),
      生图调用: (window.__imgCalls || []).map(c => c.prompt),
    });
  `));
  console.log('\n  实际送给 LLM 的上下文：');
  (seen.LLM调用 || []).forEach((c, i) => console.log(`    [${i}] user = ${JSON.stringify(c.user.slice(0, 200))}`));
  console.log('  实际送给生图接口的 prompt：');
  (seen.生图调用 || []).forEach((p, i) => console.log(`    [${i}] ${p}`));

  const userTexts = (seen.LLM调用 || []).map((c) => c.user).join(' ');
  check('★ 给历史消息配图时，送进 LLM 的是**这一条**的内容', /冰原/.test(userTexts), userTexts.slice(0, 80));
  check('没有把别的消息内容混进来', !/森林|火炉/.test(userTexts), userTexts.slice(0, 120));

  // 检查渲染位置：图片应该出现在第 1 条消息里，而不是别的消息
  const where = JSON.parse(await ev(`
    const groups = Array.from(document.querySelectorAll('.message.ai'));
    return JSON.stringify(groups.map(function(g, i) {
      const imgs = g.querySelectorAll('img, .img-fallback').length;
      const text = (g.querySelector('.msg-content')?.textContent || '').slice(0, 24);
      return { 第几条: i + 1, 文字: text, 图数: imgs };
    }));
  `));
  console.log('\n  渲染结果：');
  where.forEach((w) => console.log(`    第${w.第几条}条 [图${w.图数}] ${w.文字}`));

  const okPos = where[0] && where[0].图数 >= 1;
  check('★ 图挂在第 1 条（被点的那条）上', okPos, JSON.stringify(where[0]));
  const others = where.slice(1).filter((w) => w.图数 > 0);
  check('没有跑到别的消息上', others.length === 0, JSON.stringify(others));

  // 再确认正文没被覆盖（这一条的文字还是冰原）
  const textOk = JSON.parse(await ev(`
    const g = document.querySelectorAll('.message.ai')[0];
    return JSON.stringify({ 文字: g ? g.querySelector('.msg-content')?.textContent || '' : '' });
  `));
  check('★ 被点的那条正文没被覆盖掉（还是冰原那段）', /冰原/.test(textOk.文字), textOk.文字.slice(0, 60));

  console.log(`\n  失败项: ${fails}`);
  ws.close();
  await fetch(`${CDP}/json/close/${page.id}`).catch(() => {});
  if (fails > 0) process.exitCode = 1;
});
