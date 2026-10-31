// 验证：
//  ① 默认只带基础分类（质量/场景/光线），其他按需勾
//  ② 自定义标签能加、能删、能落盘、会带给模型
//  ③ 标签页「复制提示词」含自定义标签，且不含说明书
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

  console.log('########## 默认分类 + 自定义标签 ##########\n');

  // ============ ① 默认分类收窄 ============
  console.log('—— ① 默认只带基础分类 ——');
  const defaults = JSON.parse(await ev(`
    const { TAG_CATEGORIES, buildTagReference } = await import('/src/engine/tagLibrary.ts');
    const { useStorageSettingsStore } = await import('/src/stores/storageSettings.ts');
    const s = useStorageSettingsStore(); await s.load();
    const on = TAG_CATEGORIES.filter(c => c.defaultOn).map(c => c.id);
    return JSON.stringify({
      分类总数: TAG_CATEGORIES.length,
      默认开启: on,
      默认开启数: on.length,
      // 全新的库（没存过）时的实际取值
      实际默认参考块长度: buildTagReference({
        enabledCategories: on,
        perCategory: s.tagPerCategory,
      }).length,
      全选参考块长度: buildTagReference({
        enabledCategories: TAG_CATEGORIES.map(c => c.id),
        perCategory: s.tagPerCategory,
      }).length,
    });
  `));
  console.log('  ' + JSON.stringify(defaults, null, 0));
  check('★ 默认开启的分类 <= 4 个（不再一股脑全带）', defaults.默认开启数 <= 4, `${defaults.默认开启数} 个`);
  check('★ 默认里包含「光线与氛围」', defaults.默认开启.includes('lighting'));
  check('★ 默认里包含「质量与风格」', defaults.默认开启.includes('quality'));
  check('★ 默认里包含「场景与背景」', defaults.默认开启.includes('scene'));
  check('★ 具体外观类（服装/发色）默认不带',
    !defaults.默认开启.includes('clothing') && !defaults.默认开启.includes('hair_eyes'));
  check('★ 默认参考块明显短于全选',
    defaults.实际默认参考块长度 < defaults.全选参考块长度 * 0.6,
    `${defaults.实际默认参考块长度} vs ${defaults.全选参考块长度}`);

  // ============ ② 自定义标签 ============
  console.log('\n—— ② 自定义标签 ——');
  const custom = JSON.parse(await ev(`
    const { useStorageSettingsStore } = await import('/src/stores/storageSettings.ts');
    const s = useStorageSettingsStore(); await s.load();

    // 加两个（其中一个故意带逗号，测一次贴一串）
    await s.addCustomTag('scar on left eye');
    await s.addCustomTag('black choker, my oc');

    const after = s.customTags.slice();

    // 重复添加要被去重
    await s.addCustomTag('SCAR ON LEFT EYE');
    const afterDup = s.customTags.slice();

    // 重读磁盘
    s.customTags = [];
    await s.load();
    const reloaded = s.customTags.slice();

    // 参考块里要带上
    const refText = s.pickedTagReferenceText();

    // 删一个
    await s.removeCustomTag('black choker');

    return JSON.stringify({
      加完: after,
      重复添加后数量: afterDup.length,
      重读后: reloaded,
      参考块含自定义: /scar on left eye/.test(refText),
      参考块含必须带上: /必须带上的自定义标签/.test(refText),
      删除后: s.customTags.slice(),
    });
  `));
  console.log('  加完: ' + JSON.stringify(custom.加完));
  console.log('  重读: ' + JSON.stringify(custom.重读后));
  console.log('  删后: ' + JSON.stringify(custom.删除后));

  check('★ 自定义标签能加（逗号一次加多个）', custom.加完.length === 3, JSON.stringify(custom.加完));
  check('★ 重复标签被去重（大小写不敏感）', custom.重复添加后数量 === 3, String(custom.重复添加后数量));
  check('★ 自定义标签会落盘（重读后还在）', custom.重读后.length === 3);
  check('★ 会一起带给模型', custom.参考块含自定义 === true && custom.参考块含必须带上 === true);
  check('★ 能单个删除', custom.删除后.length === 2 && !custom.删除后.includes('black choker'));

  // ============ ③ 标签页复制提示词含自定义 ============
  console.log('\n—— ③ 标签页：复制提示词包含自定义标签 ——');
  await ev(`location.hash = '#/adventure'; return 1;`);
  await sleep(1000);
  await ev(`location.hash = '#/tags'; return 1;`);
  await sleep(4000);

  const ui = JSON.parse(await ev(`
    const hasInput = !!document.querySelector('.custom-tag-row input');
    const customPills = document.querySelectorAll('.picked-tag.custom').length;
    const copyBtn = Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '复制提示词');
    return JSON.stringify({
      有自定义输入框: hasInput,
      自定义标签气泡数: customPills,
      有复制提示词按钮: !!copyBtn,
    });
  `));
  console.log('  ' + JSON.stringify(ui));
  check('★ 标签页有自定义标签输入框', ui.有自定义输入框 === true);
  check('★ 自定义标签以气泡显示', ui.自定义标签气泡数 >= 2, String(ui.自定义标签气泡数));

  const copied = JSON.parse(await ev(`
    // 先挑两个库里的标签，凑成"手挑 + 自定义"混合
    const { useStorageSettingsStore } = await import('/src/stores/storageSettings.ts');
    const s = useStorageSettingsStore(); await s.load();
    s.pickedTags = ['1girl', 'silver hair'];
    await s.save();

    let captured = '';
    const realWrite = navigator.clipboard.writeText.bind(navigator.clipboard);
    navigator.clipboard.writeText = function(t) { captured = t; return Promise.resolve(); };
    const btn = Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '复制提示词');
    btn.click();
    await new Promise(r => setTimeout(r, 500));
    navigator.clipboard.writeText = realWrite;
    return JSON.stringify({ 内容: captured, 长度: captured.length });
  `));
  console.log('  复制结果: ' + JSON.stringify(copied.内容));
  check('★ 复制内容含手挑的标签', /1girl/.test(copied.内容) && /silver hair/.test(copied.内容));
  check('★ 复制内容含自定义标签', /scar on left eye/.test(copied.内容), copied.内容);
  check('★ 复制内容不含说明书文字', !/【|写提示词时请优先/.test(copied.内容));
  check('★ 复制内容不含候选标签表', copied.长度 < 300, `${copied.长度} 字符`);

  console.log('\n  页面错误: ' + (errs.length ? [...new Set(errs)].slice(0, 4).join(' | ') : '无'));
  console.log(`  失败项: ${fails}`);
  ws.close();
  await fetch(`${CDP}/json/close/${page.id}`).catch(() => {});
  if (fails > 0) process.exitCode = 1;
});
