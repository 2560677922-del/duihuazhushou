// 验证「小说功能已删除，只留冒险主线」。
//
// 检查的是**用户真正看得见的东西**，不是源码里有没有残留字符串：
//   1. 底部标签栏没有「小说」了
//   2. 直接访问 #/novel、#/novel/xxx（旧书签）不会停在空白页
//   3. 图片库的筛选里没有「小说」按钮了
//   4. 老的那些 scene='novel' 图片仍然能在「全部」里看到（不能因为删功能把图弄丢）
import { withCleanData } from './_testdata.mjs';

const CDP = 'http://127.0.0.1:' + (process.argv[2] || '9500');
const BASE = 'http://127.0.0.1:5173';

let fails = 0;
const check = (label, cond, extra = '') => {
  if (!cond) fails++;
  console.log(`  ${cond ? '✓' : '✗ FAIL'}  ${label}${extra ? '  ' + extra : ''}`);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const PNG =
  'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';

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

  console.log('################ 小说功能是否已彻底移除 ################\n');

  // —— 1. 底部标签栏
  const nav = await ev(`
    const tabs = Array.from(document.querySelectorAll('nav a, nav button, .tabbar a, .tabbar button'));
    return JSON.stringify({
      标签: tabs.map(function(t){ return t.textContent.replace(/\\s+/g,'').trim(); }).filter(Boolean),
      页面里还有小说两个字: document.body.innerText.indexOf('小说') >= 0,
    });
  `);
  const nv = JSON.parse(nav);
  console.log('  底部标签: ' + JSON.stringify(nv.标签));
  check('底部标签栏里没有「小说」', !nv.标签.some((t) => /小说/.test(t)));
  check('还留着「冒险」（没有误伤）', nv.标签.some((t) => /冒险/.test(t)));

  // —— 2. 旧书签 #/novel 会不会停在空白页
  await ev(`location.hash = '#/novel'; return 1;`);
  await sleep(2500);
  const oldRoute = await ev(`
    return JSON.stringify({
      当前hash: location.hash,
      正文长度: (document.body.innerText || '').replace(/\\s+/g,'').length,
      有空白提示: /无法匹配|No match/i.test(document.body.innerText || ''),
    });
  `);
  const or = JSON.parse(oldRoute);
  console.log('  访问 #/novel: ' + oldRoute);
  check('★ 旧书签 #/novel 被兜底送到冒险页（不是空白）', /adventure/.test(or.当前hash), or.当前hash);
  check('页面上有内容（不是空白）', or.正文长度 > 20, `正文 ${or.正文长度} 字`);

  await ev(`location.hash = '#/novel/abc123'; return 1;`);
  await sleep(2000);
  const oldRoute2 = await ev(`
    return JSON.stringify({ 当前hash: location.hash, 正文长度: (document.body.innerText||'').replace(/\\s+/g,'').length });
  `);
  console.log('  访问 #/novel/abc123: ' + oldRoute2);
  check('★ 旧书签 #/novel/<id> 也被兜底', /adventure/.test(JSON.parse(oldRoute2).当前hash));

  // —— 3. 图片库筛选
  const gal = await ev(`
    const { addAsset } = await import('/src/lib/imageAssets.ts');
    // 造一张**旧数据**：scene 还是 'novel'，模拟小说时代留下的图
    await addAsset(${JSON.stringify(PNG)}, {
      prompt: '来自小说时代的图', provider: 't', model: 'm', description: '旧图',
      origin: 'auto', scene: 'novel', entityId: 'legacy',
    });
    location.hash = '#/gallery';
    return JSON.stringify({ 已造旧图: true });
  `);
  void gal;
  await sleep(6000);
  const galUi = await ev(`
    const btns = Array.from(document.querySelectorAll('button'));
    const seg = btns.filter(function(b){ return ['全部','小说','冒险','收藏'].indexOf(b.textContent.trim()) >= 0; });
    const cells = document.querySelectorAll('.cell');
    return JSON.stringify({
      筛选按钮: seg.map(function(b){ return b.textContent.trim(); }),
      格子数: cells.length,
      页面出现小说按钮: btns.some(function(b){ return b.textContent.trim() === '小说'; }),
      页面正文提到小说: (document.body.innerText||'').indexOf('小说') >= 0,
    });
  `);
  const gu = JSON.parse(galUi);
  console.log('  图片库: ' + galUi);
  check('★ 图片库筛选里没有「小说」按钮了', gu.页面出现小说按钮 === false, JSON.stringify(gu.筛选按钮));
  check('图片库页面正文也不再出现「小说」字样', gu.页面正文提到小说 === false);
  check('★ 老小说图仍然显示在「全部」里（没弄丢用户的图）', gu.格子数 >= 1, `格子 ${gu.格子数}`);

  console.log('\n  页面错误: ' + (errs.length ? [...new Set(errs)].slice(0, 4).join(' | ') : '无'));
  console.log(`  失败项: ${fails}`);
  ws.close();
  await fetch(`${CDP}/json/close/${page.id}`).catch(() => {});
  if (fails > 0) process.exitCode = 1;
});
