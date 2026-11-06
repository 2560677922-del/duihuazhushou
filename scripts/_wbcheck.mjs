// 复现「世界书/预设的小对号没勾选却仍生效」
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
  ws.addEventListener('message', (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pend.has(m.id)) {
      const h = pend.get(m.id); pend.delete(m.id);
      m.error ? h.rej(new Error(JSON.stringify(m.error))) : h.res(m.result);
    }
  });
  await new Promise((r, j) => { ws.addEventListener('open', r); ws.addEventListener('error', j); });
  const send = (method, params = {}, timeout = 120000) => new Promise((res, rej) => {
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
  await send('Page.navigate', { url: BASE + '/#/assets' });
  await sleep(12000);

  console.log('########## 世界书 / 预设 开关复现 ##########\n');

  const wb = JSON.parse(await ev(`
    const { triggerWorldbook } = await import('/src/engine/worldbook.ts');
    const txt = '我在说魔法这件事';

    function run(entry, extraBook) {
      const book = { name: 'B', entries: [entry] };
      const r = triggerWorldbook([book], [], txt, { rng: () => 0 });
      return r.hits.length;
    }

    return JSON.stringify({
      // 各种"关闭"写法
      未关:        run({ uid: '1', keys: ['魔法'], content: 'X' }),
      enabled_false: run({ uid: '1', keys: ['魔法'], content: 'X', enabled: false }),
      disable_true:  run({ uid: '1', keys: ['魔法'], content: 'X', disable: true }),
      disabled_true: run({ uid: '1', keys: ['魔法'], content: 'X', disabled: true }),
      disabled_1:    run({ uid: '1', keys: ['魔法'], content: 'X', disabled: 1 }),
      // 常驻
      常驻:          run({ uid: '1', keys: [], content: 'X', constant: true }),
      常驻但关掉:    run({ uid: '1', keys: [], content: 'X', constant: true, disabled: true }),
    });
  `));
  console.log('  世界书条目触发结果：' + JSON.stringify(wb));

  check('正常条目应触发', wb.未关 === 1);
  check('enabled=false 应不触发', wb.enabled_false === 0, `实际 ${wb.enabled_false}`);
  check('disable=true 应不触发', wb.disable_true === 0, `实际 ${wb.disable_true}`);
  check('★ disabled=true（UI 勾选框写的字段）应不触发', wb.disabled_true === 0, `实际 ${wb.disabled_true}`);
  check('★ disabled=1（数字写法）应不触发', wb.disabled_1 === 0, `实际 ${wb.disabled_1}`);
  check('常驻条目应触发', wb.常驻 === 1);
  check('★ 常驻但被勾掉应不触发', wb.常驻但关掉 === 0, `实际 ${wb.常驻但关掉}`);

  // ===== 预设 =====
  const preset = JSON.parse(await ev(`
    const { OpenAIPreset } = await import('/node_modules/.vite/deps/parsecard.js');
    const { assemblePrompt } = await import('/src/engine/assembler.ts');

    function build(orderEnabled) {
      return {
        prompts: [
          { identifier: 'main', name: 'Main', role: 'system', content: 'MAIN_PROMPT_TEXT' },
          { identifier: 'jail', name: 'Jail', role: 'system', content: 'JAIL_PROMPT_TEXT' },
        ],
        prompt_order: [{
          character_id: 100001,
          order: [
            { identifier: 'main', enabled: true },
            { identifier: 'jail', enabled: orderEnabled },
          ],
        }],
      };
    }

    function texts(enabled) {
      const p = OpenAIPreset.fromJSON(build(enabled));
      const r = assemblePrompt({ preset: p, chatHistory: [{ role: 'user', content: 'hi' }] });
      return r.finalMessages.map(m => m.content).join('\\n');
    }

    // 模拟 UI 把一个原本启用的 prompt 关掉：改 prompt_order[0]
    const raw = build(true);
    raw.prompt_order[0].order[1].enabled = false;
    const p2 = OpenAIPreset.fromJSON(raw);
    const r2 = assemblePrompt({ preset: p2, chatHistory: [{ role: 'user', content: 'hi' }] });

    return JSON.stringify({
      都开:      texts(true).includes('JAIL_PROMPT_TEXT'),
      关了jail:  texts(false).includes('JAIL_PROMPT_TEXT'),
      改order后: r2.finalMessages.map(m => m.content).join('\\n').includes('JAIL_PROMPT_TEXT'),
      listEntries: OpenAIPreset.fromJSON(build(false)).listPromptEntries().map(e => ({ id: e.identifier, on: e.enabled })),
    });
  `));
  console.log('  预设结果：' + JSON.stringify(preset));

  check('都开时 jail 在提示词里', preset.都开 === true);
  check('★ prompt_order 里 enabled=false 的预设条目不应注入', preset.关了jail === false, `实际 ${preset.关了jail}`);
  check('★ 关掉后再组装也不应注入', preset.改order后 === false, `实际 ${preset.改order后}`);
  check('listPromptEntries 能反映 enabled', Array.isArray(preset.listEntries) && preset.listEntries.some((e) => e.on === false), JSON.stringify(preset.listEntries));

  // ===== 预设：边界情况 =====
  const presetEdge = JSON.parse(await ev(`
    const { OpenAIPreset } = await import('/node_modules/.vite/deps/parsecard.js');
    const { assemblePrompt } = await import('/src/engine/assembler.ts');

    function textsOf(raw) {
      const p = OpenAIPreset.fromJSON(raw);
      const r = assemblePrompt({ preset: p, chatHistory: [{ role: 'user', content: 'hi' }] });
      return r.finalMessages.map(m => m.content).join('\\n');
    }

    // ① order 条目没有 enabled 字段（不少 ST 预设导出时省略）
    const noEnabled = {
      prompts: [
        { identifier: 'a', role: 'system', content: 'AAA_TEXT' },
        { identifier: 'b', role: 'system', content: 'BBB_TEXT' },
      ],
      prompt_order: [{ character_id: 100001, order: [{ identifier: 'a' }, { identifier: 'b' }] }],
    };
    const t1 = textsOf(noEnabled);

    // ② 两个 order 分组：UI 改的是 po[0]，引擎要用同一个
    const twoGroups = {
      prompts: [
        { identifier: 'a', role: 'system', content: 'AAA_TEXT' },
        { identifier: 'b', role: 'system', content: 'BBB_TEXT' },
      ],
      prompt_order: [
        { character_id: 100001, order: [{ identifier: 'a', enabled: true }, { identifier: 'b', enabled: true }] },
        { character_id: 100002, order: [{ identifier: 'a', enabled: true }, { identifier: 'b', enabled: true }] },
      ],
    };
    // 模拟 UI：关掉 po[0] 里的 b
    const g2 = JSON.parse(JSON.stringify(twoGroups));
    g2.prompt_order[0].order[1].enabled = false;
    const t2 = textsOf(g2);

    // ③ 只有一个非默认分组
    const oneOddGroup = {
      prompts: [
        { identifier: 'a', role: 'system', content: 'AAA_TEXT' },
        { identifier: 'b', role: 'system', content: 'BBB_TEXT' },
      ],
      prompt_order: [{ character_id: 424242, order: [{ identifier: 'a', enabled: true }, { identifier: 'b', enabled: true }] }],
    };
    const g3 = JSON.parse(JSON.stringify(oneOddGroup));
    g3.prompt_order[0].order[1].enabled = false;
    const t3 = textsOf(g3);

    return JSON.stringify({
      缺enabled_有A: t1.includes('AAA_TEXT'),
      缺enabled_有B: t1.includes('BBB_TEXT'),
      两组_关了b: t2.includes('BBB_TEXT'),
      两组_还有a: t2.includes('AAA_TEXT'),
      单奇组_关了b: t3.includes('BBB_TEXT'),
      单奇组_还有a: t3.includes('AAA_TEXT'),
    });
  `));
  console.log('  预设边界：' + JSON.stringify(presetEdge));

  check('★ order 里没写 enabled 时默认应启用（不能被静默丢掉）',
    presetEdge.缺enabled_有A === true && presetEdge.缺enabled_有B === true,
    JSON.stringify(presetEdge));
  check('★ 多分组时关掉 po[0] 的条目应生效', presetEdge.两组_关了b === false && presetEdge.两组_还有a === true,
    JSON.stringify(presetEdge));
  check('★ 分组 character_id 非默认时关掉也应生效', presetEdge.单奇组_关了b === false && presetEdge.单奇组_还有a === true,
    JSON.stringify(presetEdge));

  // ===== 端到端：真实 store → 组装提示词 =====
  const e2e = JSON.parse(await ev(`
    const { useAssetsStore } = await import('/src/stores/assets.ts');
    const { assemblePrompt } = await import('/src/engine/assembler.ts');
    const assets = useAssetsStore();
    await assets.load();

    // 造两本世界书，只选第一本
    assets.worldBooks = [
      { _uid: 'wb1', name: '书1', entries: [
        { uid: 1, keys: ['魔法'], content: 'WB1_ENTRY_TEXT', enabled: true },
      ]},
      { _uid: 'wb2', name: '书2', entries: [
        { uid: 2, keys: ['魔法'], content: 'WB2_ENTRY_TEXT', enabled: true },
      ]},
    ];
    assets.currentWorldBookIds = ['wb1'];

    const P = { prompts: [{ identifier: 'p', role: 'system', content: 'SYS' }],
                prompt_order: [{ character_id: 100001, order: [{ identifier: 'p', enabled: true }] }] };
    const { OpenAIPreset } = await import('/node_modules/.vite/deps/parsecard.js');

    function assemble() {
      const wbs = assets.getCurrentWorldBooks().map(w => w);
      const r = assemblePrompt({
        preset: OpenAIPreset.fromJSON(JSON.parse(JSON.stringify(P))),
        worldBooks: wbs,
        chatHistory: [],
        currentUserInput: '我在说魔法',
      });
      return r.finalMessages.map(m => m.content).join('\\n');
    }

    // 1. 只有书1被选中
    const onlyWb1 = assemble();

    // 2. 选上书2，但把书2的条目**取消勾选**（UI 写 disabled）
    assets.currentWorldBookIds = ['wb1', 'wb2'];
    assets.worldBooks[1].entries[0].disabled = true;
    assets.worldBooks[1].entries[0].enabled = false;
    const wb2Off = assemble();

    // 3. 再把书2整个取消选中
    assets.currentWorldBookIds = ['wb1'];
    const wb2Unselected = assemble();

    // 4. 书1的条目也取消勾选
    assets.worldBooks[0].entries[0].disabled = true;
    assets.worldBooks[0].entries[0].enabled = false;
    const allOff = assemble();

    return JSON.stringify({
      只选书1_有1: onlyWb1.includes('WB1_ENTRY_TEXT'),
      只选书1_含2: onlyWb1.includes('WB2_ENTRY_TEXT'),
      书2条目关_含2: wb2Off.includes('WB2_ENTRY_TEXT'),
      书2条目关_还有1: wb2Off.includes('WB1_ENTRY_TEXT'),
      书2未选_含2: wb2Unselected.includes('WB2_ENTRY_TEXT'),
      全关_无任何条目: !allOff.includes('WB1_ENTRY_TEXT') && !allOff.includes('WB2_ENTRY_TEXT'),
      全关_预设还在: allOff.includes('SYS'),
    });
  `));
  console.log('  端到端：' + JSON.stringify(e2e));

  check('★ 只选中的世界书才注入',
    e2e.只选书1_有1 === true && e2e.只选书1_含2 === false, JSON.stringify(e2e));
  check('★ 取消勾选条目后不再注入（书仍在选中状态）',
    e2e.书2条目关_含2 === false && e2e.书2条目关_还有1 === true, JSON.stringify(e2e));
  check('★ 取消选中整本世界书后不再注入', e2e.书2未选_含2 === false, JSON.stringify(e2e));
  check('★ 全部关掉后一个条目都不注入，但预设本身还在',
    e2e.全关_无任何条目 === true && e2e.全关_预设还在 === true, JSON.stringify(e2e));

  console.log(`\n  失败项: ${fails}`);
  ws.close();
  await fetch(`${CDP}/json/close/${page.id}`).catch(() => {});
  if (fails > 0) process.exitCode = 1;
});
