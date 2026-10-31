// ② 图片库的**真实回归测试**：模拟"标签页生图"的完整路径。
//
// 原来的三个 bug：
//   1. loadAssets() 从来没有调用点 → 元数据表永远是空的
//   2. GalleryPanel 用 resolveCached(ia-xxx) 取图 → blobKeyFromRef 不认 ia- 前缀
//   3. gcOrphanBlobs 把 ia-xxx 的本体当垃圾删掉
//
// 所以这个测试必须做到：
//   a. 走 addAsset()（标签页直出的入口）登记一张图
//   b. **刷新页面**（清掉内存状态，逼着从存储里重新读）
//   c. 确认图片库里那条还在、并且真的渲染出了 <img>
//
// 数据安全：withCleanData 包裹。注意资产写在 IndexedDB（浏览器本地），
// 不在 data/app-data.json 里，所以不会污染用户的落盘数据。
import { withCleanData } from './_testdata.mjs';

const CDP = 'http://127.0.0.1:' + (process.argv[2] || '9500');
const BASE = 'http://127.0.0.1:5173';

// 1×1 红色 PNG
const PNG =
  'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';

let fails = 0;
const check = (label, cond, extra = '') => {
  if (!cond) fails++;
  console.log(`  ${cond ? '✓' : '✗ 失败'}       ${label}${extra ? '  ' + extra : ''}`);
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
  const errors = [];
  ws.addEventListener('message', (e) => {
    const m = JSON.parse(e.data);
    if (m.method === 'Runtime.exceptionThrown')
      errors.push((m.params.exceptionDetails.exception?.description || '').slice(0, 200));
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
  const ev = async (expr, a = 0) => {
    try {
      const r = await send('Runtime.evaluate', {
        expression: `(async()=>{${expr}})()`, awaitPromise: true, returnByValue: true,
      });
      if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description);
      return r.result.value;
    } catch (e) {
      if (a < 2 && /timeout/i.test(e.message)) { await new Promise((r) => setTimeout(r, 6000)); return ev(expr, a + 1); }
      throw e;
    }
  };
  await send('Runtime.enable');
  await send('Page.enable');
  await send('Emulation.setDeviceMetricsOverride', { width: 393, height: 852, deviceScaleFactor: 2, mobile: true });

  console.log('################ ② 图片库回归测试 ################');
  await send('Page.navigate', { url: BASE + '/#/tags' });
  await new Promise((r) => setTimeout(r, 16000));

  // a. 清空 + 走 addAsset（标签页直出的入口）
  const reg = await ev(`
    const { addAsset, loadAssets, listAssets } = await import('/src/lib/imageAssets.ts');
    await loadAssets();
    const before = listAssets().length;
    const id = await addAsset(${JSON.stringify(PNG)}, {
      prompt: '1girl, test',
      provider: 'test',
      model: 'test-model',
      description: '__regression__',
      origin: 'tagtool',
      scene: 'adventure',
    });
    // 等防抖落盘（scheduleSave 是 400ms）
    await new Promise(function(r){ setTimeout(r, 1500); });
    const after = listAssets().length;
    return JSON.stringify({ 登记前: before, 登记后: after, 新id: id });
  `);
  console.log('  登记: ' + reg);
  const rd = JSON.parse(reg);
  check('addAsset 后内存里多了一条', rd.登记后 === rd.登记前 + 1, `${rd.登记前} → ${rd.登记后}`);

  // b. 刷新页面（清内存），逼着从存储重新读
  await send('Page.navigate', { url: BASE + '/#/gallery' });
  await new Promise((r) => setTimeout(r, 6000));
  await send('Page.reload', { ignoreCache: true });
  await new Promise((r) => setTimeout(r, 16000));

  // c. 图片库里应该能看到它，而且真的渲染出 <img>
  const gal = await ev(`
    const cells = Array.from(document.querySelectorAll('.cell'));
    const imgs = Array.from(document.querySelectorAll('.cell img'));
    const missing = Array.from(document.querySelectorAll('.cell-missing'));
    const loading = Array.from(document.querySelectorAll('.cell-loading'));
    // 真实渲染出来的图（naturalWidth > 0 才算真的解码成功）
    const okImgs = imgs.filter(function(i){ return i.complete && i.naturalWidth > 0; });
    return JSON.stringify({
      格子数: cells.length,
      img数: imgs.length,
      真正解码成功: okImgs.length,
      已丢失: missing.length,
      载入中: loading.length,
      首图src前缀: imgs[0] ? String(imgs[0].src).slice(0, 24) : null,
    });
  `);
  console.log('  图片库: ' + gal);
  const gd = JSON.parse(gal);
  check('刷新后记录还在（索引没丢）', gd.格子数 > 0, `格子=${gd.格子数}`);
  check('图片真的渲染出来了（不是空白格子）', gd.真正解码成功 > 0, `解码成功=${gd.真正解码成功}`);
  check('没有被误判成"本地副本已丢失"', gd.已丢失 === 0, `已丢失=${gd.已丢失}`);

  // d. 顺带验证：GC 不能把这图删掉（这是原 bug 3）
  //    gcOrphanBlobs 是 store 上的方法（storage.ts 的 default 导出），不是独立函数。
  const gc = await ev(`
    const { listAssets, assetUrl } = await import('/src/lib/imageAssets.ts');
    const before = listAssets().length;
    const mod = await import('/src/lib/storage.ts');
    const store = mod.store || mod.default;
    let hasGc = false;
    let removed = null;
    if (store && typeof store.gcOrphanBlobs === 'function') {
      hasGc = true;
      const r = await store.gcOrphanBlobs();
      removed = r && r.removed;
    }
    await new Promise(function(r){ setTimeout(r, 800); });
    const after = listAssets().length;
    // 还要确认图本体还能取出来
    const firstId = listAssets()[0] ? listAssets()[0].id : null;
    const body = firstId ? await assetUrl(firstId) : null;
    return JSON.stringify({
      GC前: before, GC后: after, 有gc函数: hasGc, GC删了几个: removed,
      图本体还能取到: !!body,
    });
  `);
  console.log('  GC: ' + gc);
  const gd2 = JSON.parse(gc);
  check('找到 gcOrphanBlobs 并执行', gd2.有gc函数 === true);
  check('GC 不会误删图片库的记录', gd2.GC后 === gd2.GC前, `${gd2.GC前} → ${gd2.GC后}`);
  check('GC 之后图本体仍能取出', gd2.图本体还能取到 === true);

  console.log('\n  页面错误: ' + (errors.length ? [...new Set(errors)].slice(0, 4).join(' | ') : '无'));
  console.log(`  失败项: ${fails}`);
  ws.close();
  await fetch(`${CDP}/json/close/${page.id}`).catch(() => {});
  if (fails > 0) process.exitCode = 1;
});
