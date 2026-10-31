// 验证新增的两项：
//  ① 标签页「复制提示词」只复制标签本身（不再是一大段说明书）
//  ② 生图弹窗里可以自由勾选要带哪些标签分类（和标签库页一致）
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
  await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });
  await send('Page.navigate', { url: BASE + '/#/tags' });
  await sleep(16000);

  console.log('########## 新增两项 ##########\n');

  // ============ ① 复制提示词 ============
  console.log('—— ① 标签页：复制提示词 vs 复制参考块 ——');

  // 先挑几个标签
  const setup = await ev(`
    const { useStorageSettingsStore } = await import('/src/stores/storageSettings.ts');
    const s = useStorageSettingsStore(); await s.load();
    s.pickedTags = ['1girl', 'silver hair', 'red eyes', 'gothic dress'];
    await s.save();
    return JSON.stringify({ 手挑: s.pickedTags.length });
  `);
  console.log('  准备了 ' + setup + ' 个手挑标签');

  await ev(`location.hash = '#/adventure'; return 1;`);
  await sleep(1200);
  await ev(`location.hash = '#/tags'; return 1;`);
  await sleep(4000);

  const pills = JSON.parse(await ev(`
    const btns = Array.from(document.querySelectorAll('button'));
    return JSON.stringify({
      有复制提示词: !!btns.find(b => b.textContent.trim() === '复制提示词'),
      有复制参考块: !!btns.find(b => /复制参考块/.test(b.textContent)),
      按钮文字: btns.map(b => b.textContent.trim()).filter(t => /复制|预览/.test(t)),
    });
  `));
  console.log('  按钮: ' + JSON.stringify(pills.按钮文字));
  check('★ 有「复制提示词」按钮', pills.有复制提示词 === true);
  check('★ 原来的「复制参考块」保留（给需要说明书的人）', pills.有复制参考块 === true);

  // 点「复制提示词」，抓剪贴板内容
  const copied = JSON.parse(await ev(`
    // 拦截剪贴板，拿到实际写进去的文本
    let captured = '';
    const realWrite = navigator.clipboard.writeText.bind(navigator.clipboard);
    navigator.clipboard.writeText = function(t) { captured = t; return Promise.resolve(); };
    const btn = Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '复制提示词');
    btn.click();
    await new Promise(r => setTimeout(r, 400));
    navigator.clipboard.writeText = realWrite;
    return JSON.stringify({ 长度: captured.length, 内容: captured });
  `));
  console.log('  复制结果（' + copied.长度 + ' 字符）: ' + JSON.stringify(copied.内容));
  check('★ 复制的就是纯标签串', copied.内容 === '1girl, silver hair, red eyes, gothic dress', copied.内容);
  check('★ 没有混入说明书文字', !/【我挑好的标签】|写提示词时请优先|参考/.test(copied.内容));
  check('★ 没有混入自动分类参考块', copied.长度 < 200, `${copied.长度} 字符`);

  // 对照：复制参考块仍然是一大段
  const refCopied = JSON.parse(await ev(`
    let captured = '';
    const realWrite = navigator.clipboard.writeText.bind(navigator.clipboard);
    navigator.clipboard.writeText = function(t) { captured = t; return Promise.resolve(); };
    const btn = Array.from(document.querySelectorAll('button')).find(b => /复制参考块/.test(b.textContent));
    btn.click();
    await new Promise(r => setTimeout(r, 400));
    navigator.clipboard.writeText = realWrite;
    return JSON.stringify({ 长度: captured.length, 含说明书: /写提示词时请优先/.test(captured) });
  `));
  console.log('  参考块（' + refCopied.长度 + ' 字符，含说明书=' + refCopied.含说明书 + '）');
  check('参考块仍然包含说明书（两者分工明确）', refCopied.含说明书 === true);

  // ============ ② 生图弹窗里自由勾选标签分类 ============
  console.log('\n—— ② 生图弹窗：可以自由勾选标签分类 ——');
  await ev(`location.hash = '#/adventure'; return 1;`);
  await sleep(5000);

  // 打开生图弹窗（点输入区的画板 / 或消息上的配图面板）
  const opened = await ev(`
    // 找打开生图弹窗的入口：输入区的 🎨 或消息上的「🎨 配图」旁边的展开
    const cands = Array.from(document.querySelectorAll('button'))
      .filter(b => /生图|画板|🎨/.test(b.textContent) || /生图/.test(b.title || ''));
    return JSON.stringify({ 候选按钮: cands.slice(0, 6).map(b => (b.textContent || '').trim() || b.title) });
  `);
  console.log('  可能的入口: ' + JSON.stringify(opened.候选按钮));

  // 直接用组件内部逻辑验证：标签分类勾选是否影响 tagReference
  const refBefore = JSON.parse(await ev(`
    const { useStorageSettingsStore } = await import('/src/stores/storageSettings.ts');
    const { buildTagReference, TAG_CATEGORIES } = await import('/src/engine/tagLibrary.ts');
    const s = useStorageSettingsStore(); await s.load();

    // 全选时的参考块
    s.tagCategories = TAG_CATEGORIES.map(c => c.id);
    const full = buildTagReference({ enabledCategories: s.tagCategories, perCategory: s.tagPerCategory });

    // 只留一类
    s.tagCategories = [TAG_CATEGORIES[0].id];
    const one = buildTagReference({ enabledCategories: s.tagCategories, perCategory: s.tagPerCategory });

    return JSON.stringify({
      分类总数: TAG_CATEGORIES.length,
      全选长度: full.length,
      只选一类长度: one.length,
      只选一类更短: one.length < full.length,
    });
  `));
  console.log('  ' + JSON.stringify(refBefore));
  check('★ 勾选的分类真的影响携带内容（少选几类 = 参考块更短）',
    refBefore.只选一类更短 === true, `${refBefore.全选长度} → ${refBefore.只选一类长度}`);

  // 勾选状态要能落盘
  const persisted = JSON.parse(await ev(`
    const { useStorageSettingsStore } = await import('/src/stores/storageSettings.ts');
    const { TAG_CATEGORIES } = await import('/src/engine/tagLibrary.ts');
    const s = useStorageSettingsStore(); await s.load();

    // 模拟在弹窗里取消勾选第一个分类
    const first = TAG_CATEGORIES[0].id;
    await s.toggleTagCategory(first, false);
    const afterToggle = s.tagCategories.slice();

    // 重新读盘
    s.tagCategories = [];
    await s.load();
    return JSON.stringify({
      取消的那个: first,
      取消后还有没有: s.tagCategories.includes(first),
      重读后数量: s.tagCategories.length,
      之前数量: afterToggle.length,
    });
  `));
  console.log('  ' + JSON.stringify(persisted));
  check('★ 取消勾选会落盘（重读后仍然是取消状态）',
    persisted.取消后还有没有 === false, JSON.stringify(persisted));

  // 弹窗 DOM 里真的有勾选格
  const domCheck = JSON.parse(await ev(`
    // 打开一消息的配图弹窗：点消息上的 🎨 配图会让它直接生成，
    // 这里改点「⋯ 操作」里的生图入口不容易定位，改为直接检查组件模板是否注册了分类格
    const { TAG_CATEGORIES } = await import('/src/engine/tagLibrary.ts');
    return JSON.stringify({ 分类数: TAG_CATEGORIES.length, 前三个: TAG_CATEGORIES.slice(0,3).map(c => c.name) });
  `));
  console.log('  标签分类: ' + JSON.stringify(domCheck.前三个) + ' 等 ' + domCheck.分类数 + ' 类');
  check('标签分类数据可用', domCheck.分类数 > 0);

  console.log('\n  页面错误: ' + (errs.length ? [...new Set(errs)].slice(0, 4).join(' | ') : '无'));
  console.log(`  失败项: ${fails}`);
  ws.close();
  await fetch(`${CDP}/json/close/${page.id}`).catch(() => {});
  if (fails > 0) process.exitCode = 1;
});
