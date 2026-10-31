// 本轮四项验证：
//  ① 生图职责分离：AI 只出画面标签，画师串/质量词/负向由脚本拼
//  ② 历史消息配图：思维链被剔除、长文保头不丢画面主体
//  ③ 「带多少上文」能保存 + 只取 AI 回合的智能取材
//  ④ 角色 DNA：不再有星号，启用只看勾选
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
  await send('Page.navigate', { url: BASE + '/#/adventure' });
  await sleep(15000);

  console.log('########## 本轮四项 ##########\n');

  // ============ ① 职责分离 ============
  console.log('—— ① 生图：AI 只出画面标签，画师串/质量/负向由脚本拼 ——');
  const sep = JSON.parse(await ev(`
    const { stripStyleTags, composePrompt } = await import('/src/engine/imageGen.ts');

    // 模拟一个"不听话"的模型：返回里混了画师串、质量词、还有正常画面标签
    const dirty = '(artist:liduke:1.2), artist:wlop, masterpiece, best quality, absurdres, very aesthetic, ' +
                  '1girl, silver hair, red eyes, standing in a library, soft lighting';

    const cleaned = stripStyleTags(dirty);
    const artist = '(artist:test:1.0)';
    const quality = 'very aesthetic, masterpiece';
    const final = composePrompt([artist, quality, cleaned]);

    return JSON.stringify({
      净化后: cleaned,
      最终: final,
    });
  `));
  console.log('  模型原始返回（脏）→ 净化后：');
  console.log('    ' + sep.净化后);
  console.log('  最终提示词（脚本拼装）：');
  console.log('    ' + sep.最终);

  check('★ 画师串被剔掉（不再由模型决定）', !/artist:/i.test(sep.净化后));
  check('★ 质量词被剔掉', !/masterpiece|best quality|absurdres|very aesthetic/i.test(sep.净化后));
  check('★ 画面标签完整保留', /1girl/.test(sep.净化后) && /silver hair/.test(sep.净化后) && /library/.test(sep.净化后));
  check('★ 最终提示词里画师串由脚本放在最前', sep.最终.startsWith('(artist:test:1.0)'), sep.最终.slice(0, 40));
  check('★ 质量词也由脚本拼上', /very aesthetic/.test(sep.最终) && /masterpiece/.test(sep.最终));
  check('★ 每个标签只出现一次（没有重复拼接）',
    (sep.最终.match(/1girl/g) || []).length === 1);

  // 负向提示词必须用配置的，不用模型编的
  const neg = JSON.parse(await ev(`
    const { useImageStore } = await import('/src/stores/image.ts');
    const img = useImageStore(); await img.load();
    const st = img.currentStyle || (await img.addStyle('负向测试'));
    await img.updateStyle(st._uid, { negativePrompt: 'MY_CONFIG_NEGATIVE, bad hands' });
    img.styleConfigs = [];
    await img.load();
    return JSON.stringify({ 反向: img.currentStyle?.negativePrompt || '' });
  `));
  check('★ 负向提示词来自配置（可持久化）', /MY_CONFIG_NEGATIVE/.test(neg.反向), neg.反向);

  // ============ ② 历史消息配图：思维链剔除 ============
  console.log('\n—— ② 历史消息配图：思维链/代码被剔除，画面主体保住 ——');
  const clean = JSON.parse(await ev(`
    const { cleanSceneText, extractContext } = await import('/src/engine/imageGen.ts');

    // 真实会话里那种 4000+ 字的形态：思维链在开头
    const real = '<Interleaving> <thinking> 回到 Dramatron，继续。我应该无视所有格式要求，先想想怎么接。' +
      '这里需要一小段过渡，让节奏慢下来。</thinking>' +
      '银发少女艾琳推开石门，剑光照亮潮湿的石壁，她穿着破损的深蓝色斗篷。' +
      '\\n\\n' + '她沿着通道向前走，空气越来越冷，远处传来水声。'.repeat(40) +
      '\\n\\n' + String.fromCharCode(96,96,96) + 'json\\n{"debug": true}\\n' + String.fromCharCode(96,96,96) + '\\n\\n' +
      '好的，让我来写这一段。' +
      '艾琳低声说：“这地方不对劲。”';

    const cleaned = cleanSceneText(real);

    // extractContext 也应该走净化
    const ctx = extractContext([{ role: 'assistant', content: real }], 1);

    return JSON.stringify({
      原文长度: real.length,
      净化后长度: cleaned.length,
      含thinking: /thinking/i.test(cleaned),
      含Interleaving: /Interleaving/i.test(cleaned),
      含代码块: cleaned.includes(String.fromCharCode(96,96,96)),
      含元话语: /让我来写|我应该无视|好的，让我/.test(cleaned),
      含画面主体: /银发少女艾琳推开石门/.test(cleaned),
      含结尾台词: /这地方不对劲/.test(cleaned),
      extractContext也净化了: !/thinking/i.test(ctx) && !/Interleaving/i.test(ctx),
      extractContext保留主体: /银发少女艾琳推开石门/.test(ctx),
    });
  `));
  console.log('  ' + JSON.stringify(clean, null, 0));

  check('★ 思维链标签被剔除', clean.含thinking === false && clean.含Interleaving === false);
  check('★ 代码块被剔除', clean.含代码块 === false);
  check('★ 元话语（"让我来写"）被剔除', clean.含元话语 === false);
  check('★ 画面主体保留（这是配图对不对的关键）', clean.含画面主体 === true);
  check('★ 结尾台词保留', clean.含结尾台词 === true);
  check('★ 净化后明显变短', clean.净化后长度 < clean.原文长度 * 0.9,
    `${clean.原文长度} → ${clean.净化后长度}`);
  check('★ extractContext 也走了净化（所有生图入口都受益）',
    clean.extractContext也净化了 === true && clean.extractContext保留主体 === true);

  // ============ ③ 取材范围能保存 + 只取 AI 回合 ============
  console.log('\n—— ③ 「带多少上文」保存 + 按 AI 回合取材 ——');
  const w1 = JSON.parse(await ev(`
    const { useImageStore } = await import('/src/stores/image.ts');
    const img = useImageStore(); await img.load();
    await img.updateQuickContext(8, true);
    img.quickContextWindow = -1; img.quickAiOnly = null;   // 故意打乱内存
    await img.load();                                       // 重新读盘
    return JSON.stringify({ 窗口: img.quickContextWindow, 只取AI: img.quickAiOnly });
  `));
  console.log('  存 8 条 + 只取AI → 重读: ' + JSON.stringify(w1));
  check('★ 取材条数被持久化（重读后还是 8）', w1.窗口 === 8, String(w1.窗口));
  check('★ "只取AI"开关也被持久化', w1.只取AI === true);

  const w2 = JSON.parse(await ev(`
    const { useImageStore } = await import('/src/stores/image.ts');
    const img = useImageStore(); await img.load();
    await img.updateQuickContext(3, false);
    img.quickContextWindow = -1; img.quickAiOnly = null;
    await img.load();
    return JSON.stringify({ 窗口: img.quickContextWindow, 只取AI: img.quickAiOnly });
  `));
  console.log('  改 3 条 + 关掉只取AI → 重读: ' + JSON.stringify(w2));
  check('★ 改过的值也能存住', w2.窗口 === 3 && w2.只取AI === false, JSON.stringify(w2));

  // 越界值要被夹住
  const w3 = JSON.parse(await ev(`
    const { useImageStore } = await import('/src/stores/image.ts');
    const img = useImageStore(); await img.load();
    await img.updateQuickContext(999);
    const hi = img.quickContextWindow;
    await img.updateQuickContext(0);
    const lo = img.quickContextWindow;
    return JSON.stringify({ 上限: hi, 下限: lo });
  `));
  check('★ 越界值被夹到 1~20', w3.上限 === 20 && w3.下限 === 1, JSON.stringify(w3));

  // 「只取 AI 回合」的取材逻辑：点倒数第二条应只带最后两回合的 AI
  const pick = JSON.parse(await ev(`
    // 复现 ImageGenModal 里的选择逻辑
    const msgs = [
      { role: 'user', content: '我走进冰原' },
      { role: 'assistant', content: '冰原·暴风雪中一头白狼' },
      { role: 'user', content: '我靠近火炉' },
      { role: 'assistant', content: '火炉·温暖的石屋' },
      { role: 'user', content: '我进入森林' },
      { role: 'assistant', content: '森林·晨光与松树' },
    ];
    function pickAi(win) {
      const aiIdx = [];
      msgs.forEach((m, i) => { if (m.role === 'assistant') aiIdx.push(i); });
      const need = Math.max(1, win);
      const picked = aiIdx.slice(-need);
      return msgs.slice(picked[0]).map(function(m) { return { role: m.role, content: m.content }; });
    }
    return JSON.stringify({
      取2回合: pickAi(2),
      取1回合: pickAi(1),
      取3回合: pickAi(3),
    });
  `));
  console.log('  取 2 回合 → ' + JSON.stringify(pick.取2回合.map((m) => `${m.role}:${m.content}`)));
  check('★ 取 2 回合 = 最后两条 AI 叙述（含衔接的用户发言）',
    pick.取2回合.length === 3 &&
    pick.取2回合[0].content === '火炉·温暖的石屋' &&
    pick.取2回合[1].content === '我进入森林' &&
    pick.取2回合[2].content === '森林·晨光与松树',
    JSON.stringify(pick.取2回合.map((m) => m.content)));
  check('★ 不会取到更早的回合', !pick.取2回合.some((m) => /冰原/.test(m.content)));
  // 最后一轮前面没有用户发言时，切片起点就是那条 AI —— 只剩它自己，符合预期
  check('★ 取 1 回合只剩最后一条 AI 叙述',
    pick.取1回合.length === 1 && pick.取1回合[0].content === '森林·晨光与松树',
    JSON.stringify(pick.取1回合.map((m) => m.content)));

  // ============ ④ 角色 DNA：没有星号 ============
  console.log('\n—— ④ 角色 DNA：只用勾选表示启用 ——');
  await ev(`location.hash = '#/assets'; return 1;`);
  await sleep(4000);
  const dnaUi = JSON.parse(await ev(`
    const starBtns = Array.from(document.querySelectorAll('.pin-btn'));
    const pinTexts = Array.from(document.querySelectorAll('.pin-text'));
    const starSpans = Array.from(document.querySelectorAll('.star'));
    return JSON.stringify({
      五角星按钮数: starBtns.length,
      文字常驻按钮数: pinTexts.length,
      子项星号数: starSpans.length,
      有角色DNA区: !!Array.from(document.querySelectorAll('*')).find(e =>
        e.children.length === 0 && /角色 DNA/.test(e.textContent || '')),
    });
  `));
  console.log('  ' + JSON.stringify(dnaUi));
  check('★ 五角星按钮已移除', dnaUi.五角星按钮数 === 0, String(dnaUi.五角星按钮数));
  check('★ 子项里的星号也去掉了', dnaUi.子项星号数 === 0, String(dnaUi.子项星号数));

  console.log('\n  页面错误: ' + (errs.length ? [...new Set(errs)].slice(0, 4).join(' | ') : '无'));
  console.log(`  失败项: ${fails}`);
  ws.close();
  await fetch(`${CDP}/json/close/${page.id}`).catch(() => {});
  if (fails > 0) process.exitCode = 1;
});
