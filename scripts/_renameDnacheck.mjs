// 验证：① 会话改名  ② 角色 DNA 单开关的生效规则
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
  await send('Runtime.enable');
  await send('Page.navigate', { url: BASE + '/#/adventure' });
  await sleep(13000);

  console.log('########## 改名 / 角色 DNA ##########\n');

  // ===== ① 会话改名 =====
  console.log('—— ① 会话改名 ——');
  const rename = JSON.parse(await ev(`
    const { useAdventureStore } = await import('/src/stores/adventure.ts');
    const adv = useAdventureStore(); await adv.load();
    const a = await adv.addSession('新冒险', '');
    const b = await adv.addSession('新冒险', '');
    await adv.switchSession(a.id);

    // 改当前会话
    await adv.updateSessionTitle('  银月城的雨  ');
    const afterCurrent = adv.sessions.find(s => s.id === a.id).title;

    // 改**非当前**会话（列表页要能改别的）
    await adv.updateSessionTitle('第二章', b.id);
    const afterOther = adv.sessions.find(s => s.id === b.id).title;
    const stillA = adv.sessions.find(s => s.id === a.id).title;

    // 空名字不该清掉原名
    await adv.updateSessionTitle('   ', a.id);
    const afterEmpty = adv.sessions.find(s => s.id === a.id).title;

    // 持久化：重新 load 后还在
    adv.sessions = [];
    await adv.load();
    const reloaded = adv.sessions.find(s => s.id === a.id)?.title;

    // 改名不应改变排序依据（updatedAt 不变）
    const updatedA = adv.sessions.find(s => s.id === a.id).updatedAt;

    return JSON.stringify({
      改当前: afterCurrent,
      改非当前: afterOther,
      改B不影响A: stillA,
      空名被忽略: afterEmpty,
      重读后: reloaded,
      有updatedAt: typeof updatedA === 'number',
    });
  `));
  console.log('  ' + JSON.stringify(rename));
  check('★ 能改当前会话名字（并去掉首尾空格）', rename.改当前 === '银月城的雨', rename.改当前);
  check('★ 能改不是当前打开的会话（列表页场景）', rename.改非当前 === '第二章', rename.改非当前);
  check('★ 改别的会话不影响当前会话', rename.改B不影响A === '银月城的雨', rename.改B不影响A);
  check('★ 空名字被忽略，不会把标题清空', rename.空名被忽略 === '银月城的雨', rename.空名被忽略);
  check('★ 改名已持久化（重读还在）', rename.重读后 === '银月城的雨', rename.重读后);

  // 界面层面：列表里点标题能出输入框
  await ev(`location.hash = '#/adventure'; return 1;`);
  await sleep(3500);
  const listDom = JSON.parse(await ev(`
    const titles = Array.from(document.querySelectorAll('.chat-row .title'));
    if (!titles.length) return JSON.stringify({ 有标题: 0 });
    titles[0].click();
    await new Promise(r => setTimeout(r, 300));
    const input = document.querySelector('.rename-input');
    return JSON.stringify({
      有标题: titles.length,
      点后出现输入框: !!input,
      输入框有值: input ? input.value.length > 0 : false,
    });
  `));
  console.log('  ' + JSON.stringify(listDom));
  check('★ 列表里点标题会出现改名输入框', listDom.点后出现输入框 === true, JSON.stringify(listDom));
  check('输入框预填了当前标题', listDom.输入框有值 === true);

  // ===== ② 角色 DNA 单开关 =====
  console.log('\n—— ② 角色 DNA 生效规则 ——');
  const dna = JSON.parse(await ev(`
    const { useDnaStore } = await import('/src/stores/characterDna.ts');
    const dna = useDnaStore(); await dna.load();

    // 清空后造三条
    dna.items = [];
    const a = await dna.add('主角');
    const b = await dna.add('配角');
    const c = await dna.add('反派');
    await dna.update(a._uid, { dna: 'silver hair, red eyes' });
    await dna.update(b._uid, { dna: 'black hair, glasses' });
    await dna.update(c._uid, { dna: 'white hair, scar' });

    // 默认全开（createDna 默认 enabled:true）
    const defaultAllOn = dna.enabledItems.length;

    // 只留主角
    await dna.setEnabled(b._uid, false);
    await dna.setEnabled(c._uid, false);
    const onlyA = {
      条数: dna.enabledItems.length,
      block: dna.dnaBlock(),
      tags: dna.dnaTags(),
    };

    // 全关
    await dna.setEnabled(a._uid, false);
    const allOff = {
      条数: dna.enabledItems.length,
      block: dna.dnaBlock(),
      tags: dna.dnaTags(),
    };

    // 全开
    await dna.setEnabled(a._uid, true);
    await dna.setEnabled(b._uid, true);
    await dna.setEnabled(c._uid, true);
    const allOn = { 条数: dna.enabledItems.length, tags: dna.dnaTags() };

    // 持久化
    dna.items = [];
    await dna.load();
    const afterReload = dna.enabledItems.length;

    // 引擎侧：关掉的条目连"标签"都不该拼进去
    const { buildDnaBlock, mergeDnaTags } = await import('/src/engine/characterDna.ts');
    const mixed = [
      { name: 'A', dna: 'aaa_tag', enabled: true },
      { name: 'B', dna: 'bbb_tag', enabled: false },
    ];
    const engine = { block: buildDnaBlock(mixed), tags: mergeDnaTags(mixed) };

    return JSON.stringify({
      默认全开: defaultAllOn,
      只留主角: onlyA,
      全关: allOff,
      全开: allOn,
      重读后: afterReload,
      引擎过滤: engine,
    });
  `));
  console.log('  ' + JSON.stringify(dna, null, 0));
  check('★ 新建的 DNA 默认启用', dna.默认全开 === 3, String(dna.默认全开));
  check('★ 只勾一条时只用那一条', dna.只留主角.条数 === 1 && /silver hair/.test(dna.只留主角.block) && !/black hair/.test(dna.只留主角.block), JSON.stringify(dna.只留主角));
  check('★ 纯标签模式也只带勾选的', dna.只留主角.tags === 'silver hair, red eyes', dna.只留主角.tags);
  check('★ 全部取消勾选时一个都不带（没有隐藏兜底）', dna.全关.条数 === 0 && dna.全关.block === '' && dna.全关.tags === '', JSON.stringify(dna.全关));
  check('★ 全部勾上时三条都带', dna.全开.条数 === 3 && /silver hair/.test(dna.全开.tags) && /white hair/.test(dna.全开.tags), JSON.stringify(dna.全开));
  check('★ 开关已持久化', dna.重读后 === 3, String(dna.重读后));
  check('★ 引擎层仍然按 enabled 过滤（关掉的不进提示词）',
    !/bbb_tag/.test(dna.引擎过滤.block) && !/bbb_tag/.test(dna.引擎过滤.tags) && /aaa_tag/.test(dna.引擎过滤.tags),
    JSON.stringify(dna.引擎过滤));

  // 旧数据迁移：以前"勾选/常驻"要合并成 enabled
  const migrate = JSON.parse(await ev(`
    const { store } = await import('/src/lib/storage.ts');
    const { useDnaStore } = await import('/src/stores/characterDna.ts');

    // 造一份"老格式"数据：两个条目，选了第一条，常驻第二条
    await store.setItem('characterDnaList', [
      { _uid: 'old1', name: '老一', dna: 'old_one_tag' },
      { _uid: 'old2', name: '老二', dna: 'old_two_tag' },
    ]);
    await store.setItem('characterDnaSelected', ['old1']);
    await store.setItem('characterDnaPinned', 'old2');
    await store.setItem('characterDnaUnifiedSwitch', false);

    const dna = useDnaStore();
    dna.items = [];
    await dna.load();

    const enabledNow = dna.items.filter(d => d.enabled !== false).map(d => d._uid);

    // 第二种老数据：没勾选、只有常驻
    await store.setItem('characterDnaList', [
      { _uid: 'p1', name: 'A', dna: 'a' },
      { _uid: 'p2', name: 'B', dna: 'b' },
    ]);
    await store.setItem('characterDnaSelected', []);
    await store.setItem('characterDnaPinned', 'p2');
    await store.setItem('characterDnaUnifiedSwitch', false);
    dna.items = [];
    await dna.load();
    const enabledPinned = dna.items.filter(d => d.enabled !== false).map(d => d._uid);

    return JSON.stringify({ 勾选迁移: enabledNow, 常驻迁移: enabledPinned });
  `));
  console.log('  ' + JSON.stringify(migrate));
  check('★ 老数据的「勾选」能迁移成 enabled', JSON.stringify(migrate.勾选迁移) === JSON.stringify(['old1']), JSON.stringify(migrate.勾选迁移));
  check('★ 老数据的「常驻」能迁移成 enabled（以前它确实生效）', JSON.stringify(migrate.常驻迁移) === JSON.stringify(['p2']), JSON.stringify(migrate.常驻迁移));

  console.log('\n  页面错误: ' + (errs.length ? [...new Set(errs)].slice(0, 3).join(' | ') : '无'));
  console.log(`  失败项: ${fails}`);
  ws.close();
  await fetch(`${CDP}/json/close/${page.id}`).catch(() => {});
  if (fails > 0) process.exitCode = 1;
});
