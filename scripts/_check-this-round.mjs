// 本轮改动的端到端验证（②③④⑤）
//
// 用法: node scripts/_check-this-round.mjs [CDP端口]
// 数据安全：全程 withCleanData 包裹，从不可变基线恢复，finally 里还原。
import { withCleanData } from './_testdata.mjs';

const CDP = 'http://127.0.0.1:' + (process.argv[2] || '9500');
const BASE = 'http://127.0.0.1:5173';

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
  await new Promise((r, j) => {
    ws.addEventListener('open', r);
    ws.addEventListener('error', j);
  });
  const send = (method, params = {}, timeout = 180000) =>
    new Promise((res, rej) => {
      const i = id++;
      const tm = setTimeout(() => {
        pend.delete(i);
        rej(new Error('timeout: ' + method));
      }, timeout);
      pend.set(i, {
        res: (v) => { clearTimeout(tm); res(v); },
        rej: (e) => { clearTimeout(tm); rej(e); },
      });
      ws.send(JSON.stringify({ id: i, method, params }));
    });
  const ev = async (expr, a = 0) => {
    try {
      const r = await send('Runtime.evaluate', {
        expression: `(async()=>{${expr}})()`,
        awaitPromise: true,
        returnByValue: true,
      });
      if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description);
      return r.result.value;
    } catch (e) {
      if (a < 2 && /timeout/i.test(e.message)) {
        await new Promise((r) => setTimeout(r, 6000));
        return ev(expr, a + 1);
      }
      throw e;
    }
  };
  await send('Runtime.enable');
  await send('Page.enable');

  const ok = (b) => (b ? '✓' : '✗ 失败');
  let fails = 0;
  const check = (label, cond, extra = '') => {
    if (!cond) fails++;
    console.log(`  ${ok(cond).padEnd(8)} ${label}${extra ? '  ' + extra : ''}`);
  };

  // ======================================================================
  // ⑤ 标签页：分类顺序、头发拆分、常用置顶
  // ======================================================================
  console.log('################ ⑤ 标签页 ################');
  await send('Emulation.setDeviceMetricsOverride', { width: 393, height: 852, deviceScaleFactor: 2, mobile: true });
  await send('Page.navigate', { url: BASE + '/#/tags' });
  await new Promise((r) => setTimeout(r, 16000));

  const g1 = await ev(`
    const chips = Array.from(document.querySelectorAll('.chip-row.lv1 .chip'));
    return JSON.stringify(chips.map(function(c){ return c.textContent.trim(); }));
  `);
  const groups = JSON.parse(g1);
  console.log('  一级分类: ' + groups.join(' | '));
  check('一级分类顺序正确（服装打头）', groups[0] && groups[0].indexOf('服装') === 0);
  check('NSFW 默认隐藏', !groups.some((g) => g.indexOf('NSFW') >= 0));

  // 点「外表」看二级
  const subs = JSON.parse(await ev(`
    const chips = Array.from(document.querySelectorAll('.chip-row.lv1 .chip'));
    const t = chips.find(function(c){ return c.textContent.indexOf('外表') >= 0; });
    if (!t) return JSON.stringify([]);
    t.click();
    await new Promise(function(r){ setTimeout(r, 1200); });
    const c2 = Array.from(document.querySelectorAll('.chip-row.lv2 .chip'));
    return JSON.stringify(c2.map(function(c){ return c.textContent.trim(); }));
  `));
  console.log('  外表二级: ' + subs.join(' | '));
  check('头发已拆成「发型」和「发色」', subs.some((s) => s.indexOf('发型') >= 0) && subs.some((s) => s.indexOf('发色') >= 0));
  check('眼睛已拆成「瞳色」和「眼睛」', subs.some((s) => s.indexOf('瞳色') >= 0) && subs.some((s) => s.indexOf('眼睛') >= 0));
  check('没有裸露的英文代号', !subs.some((s) => /[a-z_]{3,}/.test(s.replace(/cosplay/i, ''))));

  // 点「发型」看常用是否置顶
  const hair = JSON.parse(await ev(`
    const c2 = Array.from(document.querySelectorAll('.chip-row.lv2 .chip'));
    const t = c2.find(function(c){ return c.textContent.indexOf('发型') >= 0; });
    if (!t) return JSON.stringify([]);
    t.click();
    await new Promise(function(r){ setTimeout(r, 1500); });
    const cells = Array.from(document.querySelectorAll('.tag-cell .t'));
    return JSON.stringify(cells.slice(0, 10).map(function(c){ return c.textContent.trim(); }));
  `));
  console.log('  发型前10: ' + hair.join(', '));
  check('常用发型排在最前（long hair）', hair[0] === 'long hair', `实际第一=${hair[0] || '(空)'}`);

  // 发色
  const color = JSON.parse(await ev(`
    const c2 = Array.from(document.querySelectorAll('.chip-row.lv2 .chip'));
    const t = c2.find(function(c){ return c.textContent.indexOf('发色') >= 0; });
    if (!t) return JSON.stringify([]);
    t.click();
    await new Promise(function(r){ setTimeout(r, 1500); });
    const cells = Array.from(document.querySelectorAll('.tag-cell .t'));
    return JSON.stringify(cells.slice(0, 8).map(function(c){ return c.textContent.trim(); }));
  `));
  console.log('  发色前8: ' + color.join(', '));
  check('常用发色排在最前（black hair）', color[0] === 'black hair', `实际第一=${color[0] || '(空)'}`);

  // 搜索下划线原写法仍然能搜到。
  // ★ 两个坑：
  //   1. 搜索是**按当前打开的一级分类过滤**的（results 传了 openGroup），
  //      上面刚点开了「外表」，直接搜服装的标签会被过滤掉。
  //   2. Page.navigate 到**同一个 hash** 不会重新加载页面（Vue 状态还在）。
  //      所以先跳到别的路由，再跳回来，强制重建组件状态。
  await send('Page.navigate', { url: BASE + '/#/gallery' });
  await new Promise((r) => setTimeout(r, 6000));
  await send('Page.navigate', { url: BASE + '/#/tags' });
  await new Promise((r) => setTimeout(r, 15000));
  const searched = JSON.parse(await ev(`
    const inp = document.querySelector('.search-row input');
    if (!inp) return JSON.stringify({ 没找到: true });
    inp.value = 'combat_boots';
    inp.dispatchEvent(new Event('input', { bubbles: true }));
    await new Promise(function(r){ setTimeout(r, 2500); });
    const cells = Array.from(document.querySelectorAll('.tag-cell .t'));
    return JSON.stringify(cells.slice(0, 5).map(function(c){ return c.textContent.trim(); }));
  `));
  console.log('  搜 combat_boots: ' + JSON.stringify(searched));
  check('下划线原写法仍可搜到（alt 保留）', !searched.没找到 && searched.some && searched.some((s) => s === 'combat boots'));

  // 中文检索也要能命中（合并进来的 also 释义）
  const zhSearch = JSON.parse(await ev(`
    const inp = document.querySelector('.search-row input');
    inp.value = '作战靴';
    inp.dispatchEvent(new Event('input', { bubbles: true }));
    await new Promise(function(r){ setTimeout(r, 2500); });
    const cells = Array.from(document.querySelectorAll('.tag-cell .t'));
    return JSON.stringify(cells.slice(0, 5).map(function(c){ return c.textContent.trim(); }));
  `));
  console.log('  搜「作战靴」: ' + JSON.stringify(zhSearch));
  check('中文检索命中', zhSearch.some && zhSearch.some((s) => s === 'combat boots'));

  // 重复检查：界面上不该出现同名的两个格子
  const dupCheck = JSON.parse(await ev(`
    const inp = document.querySelector('.search-row input');
    inp.value = '';
    inp.dispatchEvent(new Event('input', { bubbles: true }));
    await new Promise(function(r){ setTimeout(r, 1200); });
    const cells = Array.from(document.querySelectorAll('.chip-row.lv1 .chip'));
    const t = cells.find(function(c){ return c.textContent.indexOf('服装') >= 0; });
    t.click();
    await new Promise(function(r){ setTimeout(r, 1500); });
    const c2 = Array.from(document.querySelectorAll('.chip-row.lv2 .chip'));
    const top = c2.find(function(c){ return c.textContent.indexOf('上装') >= 0; });
    top.click();
    await new Promise(function(r){ setTimeout(r, 1800); });
    const tags = Array.from(document.querySelectorAll('.tag-cell .t')).map(function(c){ return c.textContent.trim(); });
    const seen = {}; const dups = [];
    tags.forEach(function(t){ if (seen[t]) dups.push(t); seen[t] = 1; });
    return JSON.stringify({ 条数: tags.length, 重复: dups.slice(0, 5), 前5: tags.slice(0, 5) });
  `));
  console.log('  服装/上装: ' + JSON.stringify(dupCheck));
  check('同一分类内无重复标签', dupCheck.重复.length === 0, `重复=${JSON.stringify(dupCheck.重复)}`);
  check('上装常用标签置顶（shirt）', dupCheck.前5[0] === 'shirt', `实际第一=${dupCheck.前5[0]}`);

  // ======================================================================
  // ③ 聊天界面工具栏：刘海机型下滚动不跑掉
  // ======================================================================
  console.log('\n################ ③ 聊天工具栏 ################');
  await send('Page.navigate', { url: BASE + '/#/adventure' });
  await new Promise((r) => setTimeout(r, 14000));
  const rowOk = await ev(`
    const x = document.querySelector('.chat-row');
    if (!x) return 'no-session';
    x.click();
    await new Promise(function(r){ setTimeout(r, 4000); });
    return 'ok';
  `);
  if (rowOk !== 'ok') {
    console.log('  （干净基线里没有会话，跳过）');
  } else {
    const r = await ev(`
      // 注入刘海安全区
      if (!document.getElementById('__notch')) {
        const st = document.createElement('style'); st.id = '__notch';
        st.textContent = ':root{--safe-top:44px;--safe-bottom:24px;}';
        document.head.appendChild(st);
      }
      await new Promise(function(r){ setTimeout(r, 800); });
      const bar = document.querySelector('.adventure-header');
      if (!bar) return JSON.stringify({ 没找到: true });
      const before = bar.getBoundingClientRect().top;
      window.scrollTo(0, 1200);
      await new Promise(function(r){ setTimeout(r, 900); });
      const after = bar.getBoundingClientRect().top;
      const st = getComputedStyle(bar);
      return JSON.stringify({
        滚动前顶边: Math.round(before),
        滚动后顶边: Math.round(after),
        安全区: getComputedStyle(document.documentElement).getPropertyValue('--safe-top').trim(),
        position: st.position,
        top: st.top,
        视口高: window.innerHeight,
      });
    `);
    console.log('  ' + r);
    const d = JSON.parse(r);
    check('滚动后工具栏仍在安全区下方', !d.没找到 && d.滚动后顶边 >= 40, `顶边=${d.滚动后顶边}`);
    check('滚动后工具栏没跑出屏幕', !d.没找到 && d.滚动后顶边 < d.视口高 / 2);
  }

  // ======================================================================
  // ② 图片库：不再空白 / 有明确状态
  // ======================================================================
  console.log('\n################ ② 图片库 ################');
  await send('Page.navigate', { url: BASE + '/#/gallery' });
  await new Promise((r) => setTimeout(r, 14000));
  const g = await ev(`
    const cells = Array.from(document.querySelectorAll('.cell'));
    const imgs = Array.from(document.querySelectorAll('.cell img'));
    const missing = document.querySelectorAll('.cell-missing');
    const loading = document.querySelectorAll('.cell-loading');
    return JSON.stringify({
      格子数: cells.length,
      有图: imgs.length,
      已丢失: missing.length,
      载入中: loading.length,
      空提示: !!document.querySelector('.empty, .empty-state'),
    });
  `);
  console.log('  ' + g);
  const gd = JSON.parse(g);
  check('图片库有明确的空/有图状态（非静默空白）', gd.格子数 > 0 || gd.空提示, `格子=${gd.格子数} 空提示=${gd.空提示}`);
  if (gd.格子数 > 0) check('格子里的图真的渲染出来了', gd.有图 > 0 || gd.已丢失 > 0 || gd.载入中 > 0);

  // ======================================================================
  // ④ LLM 设置：无硬编码地址 + 批量加入模型
  // ======================================================================
  console.log('\n################ ④ LLM 设置 ################');
  await send('Page.navigate', { url: BASE + '/#/settings' });
  await new Promise((r) => setTimeout(r, 14000));

  // 直接验证 store。
  // ★ 不能拿 api.configs[0] 当"默认值"——那是**用户已有的数据**（基线里本来
  //   就存着 openai 地址的老配置）。要验默认值，得新建一条：addConfig 直接用
  //   DEFAULT_CONFIG，才能证明源码里不再硬编码。
  const s = await ev(`
    const { useApiConfigStore } = await import('/src/stores/apiConfig.ts');
    const api = useApiConfigStore();
    await api.load();

    // ① 新建一条 = DEFAULT_CONFIG 的真身
    const fresh = await api.addConfig('__probe_default__');

    // ② 批量加入：第一个参数要传 **config 的 _uid**（不是 provider.key）
    const uid = api.providers[0].models[0]._uid;
    const before = api.configs.length;
    const r = await api.addModelsToProvider(uid, ['test-model-a', 'test-model-b', 'test-model-a']);
    const after = api.configs.length;
    const owner = api.providers.find(function(p){
      return p.models.some(function(m){ return m._uid === uid; });
    });
    return JSON.stringify({
      新建配置地址: fresh.baseUrl,
      新建配置模型: fresh.model,
      加入结果: r,
      新增条数: after - before,
      这家里的测试模型: owner.models.map(function(m){ return m.model; })
        .filter(function(m){ return m.indexOf('test-model') === 0; }),
    });
  `);
  console.log('  ' + s);
  const sd = JSON.parse(s);
  check('新建配置不带硬编码官方地址', sd.新建配置地址 === '', `地址=${JSON.stringify(sd.新建配置地址)}`);
  check('新建配置不带硬编码模型名', sd.新建配置模型 === '', `模型=${JSON.stringify(sd.新建配置模型)}`);
  check('批量加入去重（3 个含 1 个重复 → 加 2 个）', sd.加入结果 && sd.加入结果.added === 2 && sd.加入结果.skipped === 1, JSON.stringify(sd.加入结果));
  check('重复的模型没有重复入库', sd.这家里的测试模型 && sd.这家里的测试模型.length === 2, JSON.stringify(sd.这家里的测试模型));

  // 界面上不该出现 openai 官方地址
  const pageVals = await ev(`
    const vals = Array.from(document.querySelectorAll('input')).map(function(i){ return i.value; });
    return JSON.stringify(vals.filter(function(v){ return /api\\.openai\\.com/.test(v); }));
  `);
  check('界面上没有 openai 官方地址', JSON.parse(pageVals).length === 0, pageVals);

  // ======================================================================
  console.log('\n################ 汇总 ################');
  console.log('  页面错误: ' + (errors.length ? [...new Set(errors)].slice(0, 5).join(' | ') : '无'));
  console.log(`  失败项: ${fails}`);
  ws.close();
  await fetch(`${CDP}/json/close/${page.id}`).catch(() => {});
  if (fails > 0) process.exitCode = 1;
});
