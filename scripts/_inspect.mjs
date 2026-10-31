// 用真实数据看看消息长什么样，判断"历史消息配图乱配"的真实成因。
const CDP = 'http://127.0.0.1:' + (process.env.WV_PORT || '9500');
const list = await (await fetch(`${CDP}/json/list`)).json();
const page = list.find((t) => t.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 1; const pend = new Map();
ws.addEventListener('message', (e) => {
  const m = JSON.parse(e.data);
  if (m.id && pend.has(m.id)) { const h = pend.get(m.id); pend.delete(m.id); m.error ? h.rej(new Error(JSON.stringify(m.error))) : h.res(m.result); }
});
await new Promise((r, j) => { ws.addEventListener('open', r); ws.addEventListener('error', j); });
const send = (method, params = {}, timeout = 120000) => new Promise((res, rej) => {
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
await send('Page.enable');

// 先确保停在应用页面上
await send('Page.navigate', { url: 'http://127.0.0.1:5173/#/adventure' });
await new Promise((r) => setTimeout(r, 15000));

const out = await ev(`
  const root = document.getElementById('app');
  const pinia = root.__vue_app__.config.globalProperties['$pinia'];
  const adv = pinia._s.get('adventure');
  const img = pinia._s.get('image');
  const dna = pinia._s.get('characterDna');
  await adv.load();
  const rows = [];
  for (const s of adv.sessions.slice(0, 4)) {
    for (const a of (s.actions || []).slice(0, 5)) {
      if (!a.ai) continue;
      rows.push({
        会话: s.title,
        长度: a.ai.length,
        前50: a.ai.slice(0, 50).replace(/\\s+/g, ' '),
        图数: (a.images || []).length,
        有标记: /\\[\\[IMG/.test(a.ai),
      });
    }
  }
  return JSON.stringify({
    消息: rows,
    风格: img.currentStyle ? {
      画师串: (img.currentStyle.artistTags || '').slice(0, 120),
      质量标签: (img.currentStyle.qualityTags || '').slice(0, 120),
      反向: (img.currentStyle.negativePrompt || '').slice(0, 80),
    } : null,
    模板前200字: (img.currentStyle?.promptTemplate || '(用默认)').slice(0, 200),
    DNA选中数: (dna.selectedDnaBlock ? (dna.selectedDnaBlock() || '').length : 0),
    DNA块: (dna.selectedDnaBlock ? (dna.selectedDnaBlock() || '') : '').slice(0, 200),
  });
`);

const d = JSON.parse(out);
console.log('########## 真实数据形态 ##########\n');
console.log('消息样例：');
(d.消息 || []).slice(0, 8).forEach((m) => {
  console.log(`  [${m.长度}字][图${m.图数}${m.有标记 ? ' 有标记' : ''}] ${m.前50}`);
});
console.log('\n风格配置：');
console.log('  画师串: ' + (d.风格?.画师串 || '(空)'));
console.log('  质量标签: ' + (d.风格?.质量标签 || '(空)'));
console.log('  反向: ' + (d.风格?.反向 || '(空)'));
console.log('\n提示词模板（前200字）:');
console.log('  ' + d.模板前200字.replace(/\n/g, '\n  '));
console.log('\n角色DNA：选中块长度 ' + d.DNA选中数);
if (d.DNA块) console.log('  ' + d.DNA块.replace(/\n/g, '\n  '));

ws.close();
