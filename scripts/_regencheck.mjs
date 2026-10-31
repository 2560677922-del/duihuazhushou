// 验证「对话里重新生图 → 图会跟着换」。
//
// 复现的原 bug：
//   ContentWithImages 的解析缓存 resolved 以**附件 id** 为键，
//   而"重新生图"是原地替换（附件 id 不变，只有 currentVersionId/dataUrl 变），
//   于是缓存命中旧地址 —— 点了重新生成，图纹丝不动。
//
// 这个测试不看内部实现，只看**真实 DOM 里那张 <img> 的 src 有没有变**：
//   1. 造一条带图的 AI 消息（图来自资产库，assetId）
//   2. 等它渲染出来，记下 src
//   3. 走 pushImageVersion 造一次"重新生图"（新 assetId + 新图）
//   4. 再记一次 src —— 必须变了，而且新图要真的能解码
import { withCleanData } from './_testdata.mjs';

const CDP = 'http://127.0.0.1:' + (process.argv[2] || '9500');
const BASE = 'http://127.0.0.1:5173';

// 两张字节不同的 1×1 PNG（红 / 蓝），用来区分"换没换"
const PNG_A =
  'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
const PNG_B =
  'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==';

let fails = 0;
const check = (label, cond, extra = '') => {
  if (!cond) fails++;
  console.log(`  ${cond ? '✓' : '✗ FAIL'}  ${label}${extra ? '  ' + extra : ''}`);
};

await withCleanData(async () => {
  const created = await (await fetch(`${CDP}/json/new?about:blank`, { method: 'PUT' })).json();
  let page;
  for (let i = 0; i < 40; i++) {
    const l = await (await fetch(`${CDP}/json/list`)).json();
    page = l.find((t) => t.id === created.id);
    if (page?.webSocketDebuggerUrl) break;
    await new Promise((r) => setTimeout(r, 300));
  }
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  let id = 1;
  const pend = new Map();
  const errs = [];
  ws.addEventListener('message', (e) => {
    const m = JSON.parse(e.data);
    if (m.method === 'Runtime.exceptionThrown')
      errs.push((m.params.exceptionDetails.exception?.description || '').slice(0, 200));
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

  // 先打开应用，让模块都加载好
  await send('Page.navigate', { url: BASE + '/#/adventure' });
  await new Promise((r) => setTimeout(r, 16000));

  console.log('################ 重新生图后图片会不会换 ################\n');

  // 1) 造会话 + 一条带图的 AI 消息
  const setup = await ev(`
    const { useAdventureStore } = await import('/src/stores/adventure.ts');
    const { addAsset } = await import('/src/lib/imageAssets.ts');
    const adv = useAdventureStore();
    await adv.load();

    // 两张图分别进资产库
    const assetA = await addAsset(${JSON.stringify(PNG_A)}, {
      prompt: 'A', provider: 't', model: 'm', description: '图A',
      origin: 'auto', scene: 'adventure', entityId: 'x',
    });
    const assetB = await addAsset(${JSON.stringify(PNG_B)}, {
      prompt: 'B', provider: 't', model: 'm', description: '图B',
      origin: 'auto', scene: 'adventure', entityId: 'x',
    });

    // 新会话
    const s = await adv.addSession('__regen_test__', '');
    await adv.switchSession(s.id);

    // 一条 AI 消息，正文里放一个图片标记，附件指向资产 A
    const act = await adv.addAction('测试指令', '正文内容\\n\\n[[IMG: 场景图]]\\n');
    await adv.upsertActionImage(act.id, {
      id: 'att-regen-1',
      markdownIndex: 0,
      description: '场景图',
      prompt: 'A',
      assetId: assetA,
      createdAt: Date.now() - 10000,
      currentVersionId: 'att-regen-1-v1',
    });

    return JSON.stringify({
      会话id: s.id,
      动作id: act.id,
      资产A: assetA,
      资产B: assetB,
    });
  `);
  console.log('  准备: ' + setup);
  const sd = JSON.parse(setup);

  // 2) 进对话页，等图渲染出来
  await send('Page.navigate', { url: `${BASE}/#/adventure/${sd.会话id}` });
  await new Promise((r) => setTimeout(r, 16000));

  const readSrc = async () => {
    const raw = await ev(`
      const fig = document.querySelector('.inline-image img');
      return JSON.stringify({
        找到图: !!fig,
        src长度: fig ? String(fig.src).length : 0,
        src头: fig ? String(fig.src).slice(-24) : '',
        解码成功: fig ? (fig.complete && fig.naturalWidth > 0) : false,
        是图A: fig ? String(fig.src).indexOf('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQ') >= 0 : false,
        是图B: fig ? String(fig.src).indexOf('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kg') >= 0 : false,
      });
    `);
    return JSON.parse(raw);
  };

  const before = await readSrc();
  console.log('  换之前: ' + JSON.stringify(before));
  check('旧图渲染出来了', before.找到图 === true);
  check('换之前显示的是图 A', before.是图A === true);
  check('旧图解码成功', before.解码成功 === true);

  // 3) 模拟"重新生图"：原地替换成资产 B
  const did = await ev(`
    const { useAdventureStore } = await import('/src/stores/adventure.ts');
    const { pushImageVersion } = await import('/src/engine/imageMarker.ts');
    const adv = useAdventureStore();
    const s = adv.currentSession;
    const act = s.actions.find(function(a){ return a.id === ${JSON.stringify(sd.动作id)}; });
    const att = act.images[0];

    const merged = pushImageVersion(att, {
      id: 'att-regen-1-v2',
      assetId: ${JSON.stringify(sd.资产B)},
      dataUrl: undefined,           // 只给 assetId，逼它去资产库取 —— 最接近真实情况
      prompt: 'B',
      createdAt: Date.now(),
    });
    await adv.upsertActionImage(act.id, merged);
    return JSON.stringify({
      新assetId: merged.assetId,
      新版本id: merged.currentVersionId,
      历史版本数: (merged.versions || []).length,
      历史里有assetA: (merged.versions || []).some(function(v){ return v.assetId === ${JSON.stringify(sd.资产A)}; }),
    });
  `);
  console.log('  重新生图: ' + did);
  const dd = JSON.parse(did);
  check('顶层 assetId 换成了新资产', dd.新assetId === sd.资产B);
  check('旧资产进了历史版本（没被丢掉）', dd.历史里有assetA === true);

  await new Promise((r) => setTimeout(r, 4000));
  const after = await readSrc();
  console.log('  换之后: ' + JSON.stringify(after));
  check('★ 重新生图后 <img> 换成了图 B（原 bug 就是卡在这里）', after.是图B === true, `是图A=${after.是图A}`);
  check('新图真的能解码', after.解码成功 === true);

  // 4) 切回旧版 —— 历史版本也得能显示出来
  const back = await ev(`
    const { useAdventureStore } = await import('/src/stores/adventure.ts');
    const { switchToVersion } = await import('/src/engine/imageMarker.ts');
    const adv = useAdventureStore();
    const act = adv.currentSession.actions.find(function(a){ return a.id === ${JSON.stringify(sd.动作id)}; });
    const att = act.images[0];
    const old = (att.versions || []).find(function(v){ return v.assetId === ${JSON.stringify(sd.资产A)}; });
    if (!old) return JSON.stringify({ 找不到旧版: true });
    await adv.upsertActionImage(act.id, switchToVersion(att, old.id));
    return JSON.stringify({ 切回的版本: old.id, 顶层assetId: switchToVersion(att, old.id).assetId });
  `);
  console.log('  切回旧版: ' + back);
  await new Promise((r) => setTimeout(r, 4000));
  const backSrc = await readSrc();
  console.log('  切回后: ' + JSON.stringify(backSrc));
  check('★ 切回旧版能重新显示图 A（历史版本没坏）', backSrc.是图A === true, `是图B=${backSrc.是图B}`);
  check('切回的图能解码', backSrc.解码成功 === true);

  console.log('\n  页面错误: ' + (errs.length ? [...new Set(errs)].slice(0, 4).join(' | ') : '无'));
  console.log(`  失败项: ${fails}`);
  ws.close();
  await fetch(`${CDP}/json/close/${page.id}`).catch(() => {});
  if (fails > 0) process.exitCode = 1;
});
