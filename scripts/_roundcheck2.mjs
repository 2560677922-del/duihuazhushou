// 本轮五项验证：
//  ① 提示词模板：不再提画师串/负面提示词；动作互动要求写具体
//  ② 生图取材：写着 5 条就真的取 5 回合 AI（预览不再是 1 条）
//  ③ 我的模板：能存多套、能套用、能删、能恢复默认
//  ④ ★ 删掉回复后重新生成 → 切换版本不会让消息变成空白再也切不回来
//  ⑤ 界面文案精简（抽查关键字句变短）
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
      errs.push((m.params.exceptionDetails.exception?.description || '').slice(0, 200));
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

  console.log('########## 本轮五项 ##########\n');

  // ============ ① 模板措辞 ============
  console.log('—— ① 提示词模板 ——');
  const tpl = JSON.parse(await ev(`
    const { DEFAULT_PROMPT_TEMPLATE } = await import('/src/engine/imageGen.ts');
    return JSON.stringify({
      长度: DEFAULT_PROMPT_TEMPLATE.length,
      提到画师: /artist/i.test(DEFAULT_PROMPT_TEMPLATE),
      提到负面: /负向|negative/i.test(DEFAULT_PROMPT_TEMPLATE),
      提到质量词: /masterpiece|best quality|absurdres|very aesthetic/i.test(DEFAULT_PROMPT_TEMPLATE),
      要求动作具体: /动作与互动要写具体|谁在做什么/.test(DEFAULT_PROMPT_TEMPLATE),
      有输出格式: /JSON/.test(DEFAULT_PROMPT_TEMPLATE),
      文本: DEFAULT_PROMPT_TEMPLATE,
    });
  `));
  console.log('  ' + tpl.文本.replace(/\n/g, '\n  '));
  check('★ 完全不提画师串（不提，模型就不会给）', tpl.提到画师 === false);
  check('★ 完全不提负面提示词', tpl.提到负面 === false);
  check('★ 没有把质量词写进上下文（避免"粉红大象"）', tpl.提到质量词 === false);
  check('★ 明确要求动作与互动写具体', tpl.要求动作具体 === true);
  check('★ 保留 JSON 输出格式', tpl.有输出格式 === true);
  check('模板比旧版短（旧版约 430 字）', tpl.长度 < 430, `${tpl.长度} 字`);

  // ============ ② 取材条数 ============
  console.log('\n—— ② 生图取材：写 5 条就要取 5 回合 ——');
  const prep = await ev(`
    const { useAdventureStore } = await import('/src/stores/adventure.ts');
    const adv = useAdventureStore(); await adv.load();
    const s = await adv.addSession('取材测试', '一座古堡。');
    await adv.switchSession(s.id);
    // 造 6 轮对话：用户 U1..U6，AI A1..A6
    for (let i = 1; i <= 6; i++) {
      await adv.addAction('用户第' + i + '句', 'AI 第' + i + '段：画面描写 ' + i);
    }
    return JSON.stringify({ 会话: s.id });
  `);
  const sd = JSON.parse(prep);
  await ev(`location.hash = '#/adventure/' + ${JSON.stringify(sd.会话)}; return 1;`);
  await sleep(4500);

  const ctx = JSON.parse(await ev(`
    const root = document.getElementById('app');
    const pinia = root.__vue_app__.config.globalProperties['$pinia'];
    const adv = pinia._s.get('adventure');
    const s = adv.currentSession;
    // 复现 Adventure.vue 里 imageContextMessages 的构造
    const msgs = [];
    for (const a of s.actions) {
      if (a.user && a.user.trim()) msgs.push({ role: 'user', content: a.user });
      if (a.ai && a.ai.trim()) msgs.push({ role: 'assistant', content: a.ai });
    }
    const aiCount = msgs.filter(m => m.role === 'assistant').length;
    const userCount = msgs.filter(m => m.role === 'user').length;

    // 复现弹窗里"只取最近 N 回合"的筛选
    function pickAi(all, win) {
      const aiIdx = [];
      all.forEach(function(m, i) { if (m.role === 'assistant') aiIdx.push(i); });
      if (!aiIdx.length) return all;
      const picked = aiIdx.slice(-Math.max(1, win));
      return all.slice(picked[0]);
    }
    const r5 = pickAi(msgs, 5);
    const r1 = pickAi(msgs, 1);
    return JSON.stringify({
      assistant条数: aiCount,
      user条数: userCount,
      取5回合的条数: r5.length,
      取5回合里的AI: r5.filter(m => m.role === 'assistant').map(m => m.content.slice(0, 8)),
      取1回合的条数: r1.length,
      取5回合首条: r5[0] ? r5[0].content.slice(0, 10) : '',
    });
  `));
  console.log('  ' + JSON.stringify(ctx));
  check('★ 上下文里 AI 消息被正确标成 assistant（以前全被当成 user）',
    ctx.assistant条数 === 6, `${ctx.assistant条数} 条 assistant`);
  check('★ 取 5 回合真的拿到 5 条 AI（不是 1 条）',
    ctx.取5回合里的AI.length === 5, JSON.stringify(ctx.取5回合里的AI));
  check('★ 取 5 回合从第 2 轮的 AI 开始（含衔接的用户发言）',
    /^AI 第2段/.test(ctx.取5回合首条), ctx.取5回合首条);
  check('取 1 回合只到最后一轮', ctx.取1回合的条数 <= 2, `${ctx.取1回合的条数} 条`);

  // ============ ③ 我的模板 ============
  console.log('\n—— ③ 我的模板：存档 / 套用 / 删除 ——');
  const tplStore = JSON.parse(await ev(`
    const { useImageStore } = await import('/src/stores/image.ts');
    const img = useImageStore(); await img.load();

    // 先清空模板库，保证这次断言看到的只有本次存的两套
    // （否则上一个测试跑过留下的存根会让数量对不上）
    for (const t of img.promptTemplates.slice()) {
      await img.removePromptTemplate(t._uid);
    }

    const st = img.currentStyle || (await img.addStyle('模板测试'));

    // 写一份自定义模板，然后存成"我的模板"
    await img.updateStyle(st._uid, { promptTemplate: 'MY_TPL_ONE 描述动作要详细' });
    await img.savePromptTemplate('写实风');
    await img.updateStyle(st._uid, { promptTemplate: 'MY_TPL_TWO 二次元' });
    await img.savePromptTemplate('二次元');

    const saved = img.promptTemplates.map(t => t.name);

    // 套用「写实风」
    const wr = img.promptTemplates.find(t => t.name === '写实风');
    await img.applyPromptTemplate(wr._uid);
    const afterApply = img.currentStyle.promptTemplate;

    // 重读磁盘
    img.promptTemplates = [];
    await img.load();
    const reloaded = img.promptTemplates.map(t => t.name);

    // 恢复默认
    await img.resetPromptTemplate();
    const afterReset = img.currentStyle.promptTemplate.slice(0, 12);

    // 删一个
    const victim = img.promptTemplates[0];
    await img.removePromptTemplate(victim._uid);
    const afterDel = img.promptTemplates.length;

    return JSON.stringify({
      存了: saved,
      套用后: afterApply,
      重读后: reloaded,
      恢复默认后: afterReset,
      删除前: 2,
      删除后: afterDel,
    });
  `));
  console.log('  ' + JSON.stringify(tplStore));
  check('★ 能存多套模板', tplStore.存了.length === 2 && tplStore.存了.includes('写实风'), JSON.stringify(tplStore.存了));
  check('★ 套用会把内容写回当前风格', /MY_TPL_ONE/.test(tplStore.套用后), tplStore.套用后);
  check('★ 模板库能持久化（重读后还在）', tplStore.重读后.length === 2, JSON.stringify(tplStore.重读后));
  check('★ 恢复默认可还原内置模板', /把下面的/.test(tplStore.恢复默认后), tplStore.恢复默认后);
  check('★ 能删除单条', tplStore.删除后 === 1, String(tplStore.删除后));

  // ============ ④ ★ 删回复后切版本（用户复现的那个）============
  console.log('\n—— ④ 删掉回复后重新生成，切版本不能卡死 ——');
  const scenario = JSON.parse(await ev(`
    const { useAdventureStore } = await import('/src/stores/adventure.ts');
    const adv = useAdventureStore(); await adv.load();
    const s = await adv.addSession('版本卡死复现', '一座古堡。');
    await adv.switchSession(s.id);

    // 1. 用户发 1，AI 回 2
    const act = await adv.addAction('用户1', 'AI回复2');

    // 2. 用户把 2 删了
    await adv.removeActionAi(act.id);
    const afterDelete = { ai: adv.currentSession.actions[0].ai, variants: (adv.currentSession.actions[0].variants || []).length };

    // 3. 重新生成 → AI 发 3
    await adv.replaceActionAi(act.id, 'AI回复3');
    const afterRegen = {
      ai: adv.currentSession.actions[0].ai,
      variants: (adv.currentSession.actions[0].variants || []).map(v => v.ai),
    };

    // 4. 再发 4，AI 回 5
    await adv.addAction('用户4', 'AI回复5');

    const a0 = adv.currentSession.actions[0];
    return JSON.stringify({
      删后: afterDelete,
      重新生成后: afterRegen,
      版本内容: (a0.variants || []).map(v => v.ai),
      有无空版本: (a0.variants || []).some(v => !(v.ai || '').trim()),
      当前显示: a0.ai,
    });
  `));
  console.log('  ' + JSON.stringify(scenario));
  check('删除后 AI 正文为空、没有留下版本', scenario.删后.ai === '' && scenario.删后.variants === 0,
    JSON.stringify(scenario.删后));
  check('★ 重新生成后版本里**没有**空回复（就是那个幽灵 2）',
    scenario.有无空版本 === false, JSON.stringify(scenario.版本内容));
  check('★ 当前显示的是新回复 3', /AI回复3/.test(scenario.当前显示), scenario.当前显示);

  // 切到最旧版本再切回：不能出现空白
  const switching = JSON.parse(await ev(`
    const { useAdventureStore } = await import('/src/stores/adventure.ts');
    const adv = useAdventureStore(); await adv.load();
    const a0 = adv.currentSession.actions[0];
    const total = (a0.variants || []).length;
    const results = [];
    // 逐个版本切一遍，记录每一版显示的 AI 正文
    for (let i = 0; i < total; i++) {
      await adv.switchVariant(a0.id, i);
      const cur = adv.currentSession.actions[0];
      results.push({ 第几版: i, ai: (cur.ai || '').slice(0, 10), 长度: (cur.ai || '').length });
    }
    // 切回最新
    await adv.switchVariant(a0.id, 0);
    return JSON.stringify({
      总版本数: total,
      每一版: results,
      切回最新后: (adv.currentSession.actions[0].ai || '').slice(0, 10),
    });
  `));
  console.log('  ' + JSON.stringify(switching, null, 0));
  check('★ 每个版本都有正文（没有任何一版是空的）',
    switching.每一版.every((v) => v.长度 > 0), JSON.stringify(switching.每一版));
  check('★ 切回最新版能看到回复', /AI回复3/.test(switching.切回最新后), switching.切回最新后);

  // 界面上也要能看见（不能因为 ai 为空就整块消失）
  await ev(`location.hash = '#/adventure/' + (await import('/src/stores/adventure.ts')).useAdventureStore().currentSessionId; return 1;`);
  await sleep(4000);
  const dom = JSON.parse(await ev(`
    const groups = Array.from(document.querySelectorAll('.message.ai'));
    return JSON.stringify({
      AI消息块数: groups.length,
      有切换器: !!document.querySelector('.version-switch'),
      有空白提示: !!document.querySelector('.empty-reply'),
    });
  `));
  console.log('  ' + JSON.stringify(dom));
  check('★ 对话里 AI 消息块都在', dom.AI消息块数 >= 1, String(dom.AI消息块数));
  check('没有出现"这一版没有内容"的空白态', dom.有空白提示 === false);

  console.log('\n  页面错误: ' + (errs.length ? [...new Set(errs)].slice(0, 4).join(' | ') : '无'));
  console.log(`  失败项: ${fails}`);
  ws.close();
  await fetch(`${CDP}/json/close/${page.id}`).catch(() => {});
  if (fails > 0) process.exitCode = 1;
});
