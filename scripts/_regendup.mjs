// 验证「重新生成 / 重新发送」不再在界面上出现两套一模一样的东西。
//
// 原毛病：点重新生成后，原来那条消息**原地不动**，底下又流出来一块
//   一模一样的（连"你"那句话都重复一遍），看着就像出了 bug。
// 正确行为：新内容**长在原位置**，旧内容让位，生成完用「‹ ›」翻回去。
//
// 断言全部基于真实 DOM 的**气泡个数和文字**，不看内部实现。
import { withCleanData } from './_testdata.mjs';

const CDP = 'http://127.0.0.1:' + (process.argv[2] || '9500');
const BASE = 'http://127.0.0.1:5173';

const OLD_AI = '原来的回答：你踩到了一块石头。';
const USER_TEXT = '我向前走一步';

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

  // 桌面尺寸：操作条本来就在，不用先点「⋯展开」
  await send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });

  await send('Page.navigate', { url: BASE + '/#/adventure' });
  await sleep(16000);

  console.log('################ 重新生成 / 重新发送 不再重复显示 ################\n');

  const prep = await ev(`
    const { useAdventureStore } = await import('/src/stores/adventure.ts');
    const { useApiConfigStore } = await import('/src/stores/apiConfig.ts');
    const adv = useAdventureStore(); await adv.load();
    const api = useApiConfigStore(); await api.load();
    const s = await adv.addSession('重复显示测试', '一条走廊。');
    await adv.switchSession(s.id);
    const cfg = api.currentConfig;
    await api.updateConfig(cfg._uid, { apiKey: 'k', model: 'm', baseUrl: 'https://example.invalid/v1' });
    await api.setCurrent(cfg._uid);
    const act = await adv.addAction(${JSON.stringify(USER_TEXT)}, ${JSON.stringify(OLD_AI)});
    return JSON.stringify({ 会话id: s.id, 动作id: act.id });
  `);
  console.log('  准备: ' + prep);
  const sd = JSON.parse(prep);

  await ev(`location.hash = '#/adventure/' + ${JSON.stringify(sd.会话id)}; return 1;`);
  await sleep(5000);

  // 慢速假流：分 5 段吐，方便在中途观察
  await ev(`
    window.__round = 0;
    const realFetch = window.fetch;
    window.fetch = function(url, opts) {
      const u = String(url);
      if (u.indexOf('chat/completions') < 0) return realFetch.apply(this, arguments);
      window.__round++;
      const n = window.__round;
      const chunks = ['第' + n + '版回答：', '你看见', '了一只', '很胖的猫。', '它盯着你。'];
      let i = 0, timer = null, closed = false;
      const enc = new TextEncoder();
      const stream = new ReadableStream({ start: function(controller) {
        const stop = function(){ closed = true; if (timer) clearInterval(timer); };
        timer = setInterval(function(){
          if (closed) return;
          if (i < chunks.length) {
            controller.enqueue(enc.encode('data: ' + JSON.stringify({ choices: [{ delta: { content: chunks[i] } }] }) + '\\n\\n'));
            i++;
          } else { controller.enqueue(enc.encode('data: [DONE]\\n\\n')); stop(); try{controller.close();}catch(e){} }
        }, 900);
        const sig = opts && opts.signal;
        if (sig) sig.addEventListener('abort', function(){ stop(); try{ controller.error(new DOMException('aborted','AbortError')); }catch(e){} });
      }});
      return Promise.resolve(new Response(stream, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }));
    };
    return 1;
  `);

  // 统一的"界面快照"：气泡个数 + 各自文字
  const snap = async () => {
    const raw = await ev(`
      const users = Array.from(document.querySelectorAll('.message.user .msg-content')).map(function(e){ return e.textContent.trim(); });
      const ais = Array.from(document.querySelectorAll('.message.ai .msg-content')).map(function(e){ return e.textContent.trim(); });
      const vs = document.querySelector('.version-switch');
      return JSON.stringify({
        用户气泡数: users.length,
        AI气泡数: ais.length,
        用户气泡: users,
        AI气泡: ais,
        版本切换器: vs ? vs.textContent.replace(/\\s+/g,' ').trim() : '',
        有重新生成标签: !!document.querySelector('.regen-tag'),
        有光标: !!document.querySelector('.cursor'),
      });
    `);
    return JSON.parse(raw);
  };

  // ---------- 第 0 步：初始状态 ----------
  let s0 = await snap();
  console.log('  初始: ' + JSON.stringify(s0));
  check('初始只有 1 个用户气泡', s0.用户气泡数 === 1);
  check('初始只有 1 个 AI 气泡', s0.AI气泡数 === 1);
  check('初始显示的是原回答', s0.AI气泡[0].includes('踩到了一块石头'));

  // ---------- 第 1 步：AI 侧的「重新生成」 ----------
  console.log('\n  —— AI 侧「🔄 重新生成」 ——');
  const clicked = await ev(`
    const box = document.querySelector('.message.ai .msg-actions');
    if (!box) return JSON.stringify({ 找不到操作条: true });
    const b = Array.from(box.querySelectorAll('button')).find(function(x){ return /重新生成/.test(x.textContent); });
    if (!b) return JSON.stringify({ 找不到按钮: true, 按钮: Array.from(box.querySelectorAll('button')).map(function(x){return x.textContent.trim();}) });
    b.click();
    return JSON.stringify({ 已点击: true });
  `);
  console.log('  点击: ' + clicked);
  check('点到了 AI 侧的重新生成', JSON.parse(clicked).已点击 === true);

  await sleep(2200);
  let sMid = await snap();
  console.log('  生成中: ' + JSON.stringify(sMid));
  check('★ 生成中仍然只有 1 个用户气泡（用户那句话没被重复）', sMid.用户气泡数 === 1, `实际 ${sMid.用户气泡数}`);
  check('★ 生成中仍然只有 1 个 AI 气泡（没有在底下又来一套）', sMid.AI气泡数 === 1, `实际 ${sMid.AI气泡数}`);
  check('★ 原正文已经被让位（看不到旧回答了）', !sMid.AI气泡.some((t) => t.includes('踩到了一块石头')));
  check('★ 新正文就地长在了那个位置', sMid.AI气泡.some((t) => t.includes('第1版回答')), JSON.stringify(sMid.AI气泡));
  check('有「重新生成中…」标签', sMid.有重新生成标签 === true);
  check('正在流式（有光标）', sMid.有光标 === true);

  // 等它跑完（5 段 × 0.9s + 收尾）
  await sleep(4500);
  let sDone = await snap();
  console.log('  生成完: ' + JSON.stringify(sDone));
  check('生成完还是 1 个用户气泡', sDone.用户气泡数 === 1);
  check('生成完还是 1 个 AI 气泡', sDone.AI气泡数 === 1);
  check('生成完显示的是新回答', sDone.AI气泡[0].includes('第1版回答') && sDone.AI气泡[0].includes('很胖的猫'));
  // 切换器的 textContent 会带上 ‹ › 两个箭头字符，所以用包含判断
  check('★ 出现了版本切换器「1/2」', /1\/2/.test(sDone.版本切换器), sDone.版本切换器);
  check('「重新生成中…」标签已消失', sDone.有重新生成标签 === false);

  // ---------- 第 2 步：翻回原先那一版 ----------
  console.log('\n  —— 用「‹」翻回原来的回答 ——');
  await ev(`
    const b = document.querySelector('.version-switch .vs-btn');
    if (b) b.click();
    return 1;
  `);
  await sleep(2500);
  let sBack = await snap();
  console.log('  翻回后: ' + JSON.stringify(sBack));
  check('★ 翻回了原先的回答', sBack.AI气泡[0].includes('踩到了一块石头'), sBack.AI气泡[0]);
  check('切换器显示「2/2」', /2\/2/.test(sBack.版本切换器), sBack.版本切换器);
  check('翻回后依然只有 1 个 AI 气泡', sBack.AI气泡数 === 1);

  // 再切回新版，为下一轮做准备
  await ev(`
    const btns = document.querySelectorAll('.version-switch .vs-btn');
    if (btns[1]) btns[1].click();
    return 1;
  `);
  await sleep(2200);

  // ---------- 第 3 步：用户侧的「重新生成」（= 重新发送） ----------
  console.log('\n  —— 用户侧「🔄 重新生成」（重新发送） ——');
  const clicked2 = await ev(`
    const box = document.querySelector('.message.user .msg-actions');
    if (!box) return JSON.stringify({ 找不到操作条: true });
    const b = Array.from(box.querySelectorAll('button')).find(function(x){ return /重新发送/.test(x.textContent); });
    if (!b) return JSON.stringify({ 找不到按钮: true, 按钮: Array.from(box.querySelectorAll('button')).map(function(x){return x.textContent.trim();}) });
    const label = b.textContent.trim();
    b.click();
    return JSON.stringify({ 已点击: true, 按钮文字: label });
  `);
  console.log('  点击: ' + clicked2);
  check('点到了用户侧的重新发送', JSON.parse(clicked2).已点击 === true);
  check('★ 用户侧按钮写的是「重新发送」（和用户的说法一致）', /重新发送/.test(JSON.parse(clicked2).按钮文字 || ''), JSON.parse(clicked2).按钮文字);

  await sleep(2200);
  let uMid = await snap();
  console.log('  生成中: ' + JSON.stringify(uMid));
  check('★ 重新发送时用户气泡仍只有 1 个', uMid.用户气泡数 === 1, `实际 ${uMid.用户气泡数}`);
  check('★ 重新发送时 AI 气泡仍只有 1 个', uMid.AI气泡数 === 1, `实际 ${uMid.AI气泡数}`);
  check('★ 重新发送时旧正文已让位', !uMid.AI气泡.some((t) => t.includes('第1版回答')));
  check('★ 重新发送的新正文就地显示', uMid.AI气泡.some((t) => t.includes('第2版回答')), JSON.stringify(uMid.AI气泡));

  await sleep(4500);
  let uDone = await snap();
  console.log('  生成完: ' + JSON.stringify(uDone));
  check('重新发送完只有 1 个用户气泡', uDone.用户气泡数 === 1);
  check('重新发送完只有 1 个 AI 气泡', uDone.AI气泡数 === 1);
  check('★ 现在有 3 个版本可翻', /1\/3/.test(uDone.版本切换器), uDone.版本切换器);

  // ---------- 第 4 步：中途停止后界面要恢复正常 ----------
  console.log('\n  —— 重新生成中途「停止」，界面不能留下重复 ——');
  await ev(`
    const box = document.querySelector('.message.ai .msg-actions');
    const b = Array.from(box.querySelectorAll('button')).find(function(x){ return /重新生成/.test(x.textContent); });
    b.click();
    return 1;
  `);
  await sleep(1400);
  await ev(`
    const b = document.querySelector('.gen-stop') || Array.from(document.querySelectorAll('.input-area button')).find(function(x){ return /停止/.test(x.textContent); });
    if (b) b.click();
    return 1;
  `);
  await sleep(3000);
  let sStop = await snap();
  console.log('  停止后: ' + JSON.stringify(sStop));
  check('停止后只有 1 个用户气泡', sStop.用户气泡数 === 1);
  check('停止后只有 1 个 AI 气泡', sStop.AI气泡数 === 1);
  check('停止后「重新生成中…」标签消失', sStop.有重新生成标签 === false);

  console.log('\n  页面错误: ' + (errs.length ? [...new Set(errs)].slice(0, 4).join(' | ') : '无'));
  console.log(`  失败项: ${fails}`);
  ws.close();
  await fetch(`${CDP}/json/close/${page.id}`).catch(() => {});
  if (fails > 0) process.exitCode = 1;
});
