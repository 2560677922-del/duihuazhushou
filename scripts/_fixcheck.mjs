// 验证这一轮的四项修复：
//   ① 资产页顶部应用栏不再压住分类标签栏
//   ② 聊天生图设置里选的「提示词模型」重开弹窗还在（不是又回到"跟随对话"）
//   ③ 出图风格的参考图重新打开后仍然显示（不是黑块）
//   ④ 本地部署 provider 存在，且能通过接口表单配置
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

  console.log('################ 本轮四项修复 ################\n');

  // ============ ① 资产页顶部遮挡 ============
  console.log('—— ① 资产页顶部应用栏 vs 分类标签栏 ——');
  // 用手机尺寸，这个 bug 只在窄屏（安全区生效）时出现
  await send('Emulation.setDeviceMetricsOverride', {
    width: 390, height: 844, deviceScaleFactor: 2, mobile: true,
  });
  await send('Page.navigate', { url: BASE + '/#/assets' });
  await sleep(16000);

  for (const [w, h, label] of [[390, 844, '手机 390x844'], [412, 915, '手机 412x915']]) {
    if (w !== 390) {
      await send('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 2, mobile: true });
      await sleep(2500);
    }
    const geo = JSON.parse(await ev(`
      const bar = document.querySelector('.app-bar');
      const tabs = document.querySelector('.cat-tabs');
      if (!bar || !tabs) return JSON.stringify({ 缺元素: true });
      const b = bar.getBoundingClientRect(), t = tabs.getBoundingClientRect();
      return JSON.stringify({
        应用栏底部: Math.round(b.bottom),
        分类栏顶部: Math.round(t.top),
        重叠: Math.round(b.bottom - t.top),
        应用栏可见高: Math.round(b.height),
      });
    `));
    console.log(`  ${label}: ${JSON.stringify(geo)}`);
    check(`${label} 应用栏不压住分类栏（重叠 ≤ 0）`, geo.重叠 <= 0, `重叠 ${geo.重叠}px`);
    check(`${label} 应用栏有正常高度`, geo.应用栏可见高 > 40, `${geo.应用栏可见高}px`);
  }

  // 滚动后也不能压住
  await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });
  await sleep(1500);
  await ev(`window.scrollTo(0, 300); return 1;`);
  await sleep(900);
  const scrolled = JSON.parse(await ev(`
    const bar = document.querySelector('.app-bar');
    const b = bar.getBoundingClientRect();
    return JSON.stringify({ 顶: Math.round(b.top), 底: Math.round(b.bottom), scrollY: Math.round(window.scrollY) });
  `));
  console.log('  滚动 300px 后: ' + JSON.stringify(scrolled));
  check('滚动后应用栏仍完整可见（没被顶到屏幕外）', scrolled.顶 >= 0 && scrolled.底 > 40);
  await ev(`window.scrollTo(0, 0); return 1;`);
  await sleep(600);

  // ============ ② 生图设置里的提示词模型要能保存 ============
  console.log('\n—— ② 聊天生图设置：提示词模型会被保存 ——');
  const saved = JSON.parse(await ev(`
    const { useImageStore } = await import('/src/stores/image.ts');
    const { useApiConfigStore } = await import('/src/stores/apiConfig.ts');
    const img = useImageStore(); await img.load();
    const api = useApiConfigStore(); await api.load();

    // 造两条对话模型配置，好让 ModelPicker 有得选
    // addConfig 只收名字，字段要用 updateConfig 补
    const a1 = await api.addConfig('测试甲');
    await api.updateConfig(a1._uid, { baseUrl: 'https://a.example.com/v1', apiKey: 'k1', model: 'model-a' });
    const a2 = await api.addConfig('测试乙');
    await api.updateConfig(a2._uid, { baseUrl: 'https://b.example.com/v1', apiKey: 'k2', model: 'model-b' });
    await api.save();

    // 生图接口也得有一条，才谈得上"给这套接口指定提示词模型"
    const imageApi = img.currentApi || (await img.addApi('测试生图'));
    const uid = imageApi._uid;

    // 模拟用户在弹窗里选了「测试乙」→ 这一步以前只改本地 ref，不落盘
    const target = api.configs.find(c => c.model === 'model-b');
    await img.updateApi(uid, { llmConfigId: target._uid });

    // 重新从存储读一遍（模拟关掉弹窗、甚至重开页面）
    img.styleConfigs = []; img.apiConfigs = [];
    await img.load();
    const back = img.apiConfigs.find(c => c._uid === uid);
    return JSON.stringify({
      写进去的: target._uid,
      读回来的: back ? back.llmConfigId : null,
      模型名: back && back.llmConfigId ? (api.configs.find(c => c._uid === back.llmConfigId)?.model || '') : '',
    });
  `));
  console.log('  ' + JSON.stringify(saved));
  check('★ 提示词模型选择被持久化（重读后还在）', saved.读回来的 === saved.写进去的);
  check('★ 读回来指向的正是选的那条模型', saved.模型名 === 'model-b', saved.模型名);

  // 选「跟随对话」要能真的清掉（空串而不是留下旧值）
  const cleared = JSON.parse(await ev(`
    const { useImageStore } = await import('/src/stores/image.ts');
    const img = useImageStore(); await img.load();
    const uid = img.currentApi._uid;
    await img.updateApi(uid, { llmConfigId: '' });
    img.apiConfigs = [];
    await img.load();
    const back = img.apiConfigs.find(c => c._uid === uid);
    return JSON.stringify({ llmConfigId: back.llmConfigId });
  `));
  console.log('  选回跟随: ' + JSON.stringify(cleared));
  check('★ 选「跟随对话」会清掉旧选择', !cleared.llmConfigId);

  // ============ ③ 风格参考图不再变黑 ============
  console.log('\n—— ③ 出图风格参考图：重开后仍然显示 ——');
  const thumb = JSON.parse(await ev(`
    const { useImageStore } = await import('/src/stores/image.ts');
    const img = useImageStore(); await img.load();
    const st = img.currentStyle || (await img.addStyle('测试风格'));

    // 造一张真的 PNG（4x4 纯色），当作用户上传的参考图
    const c = document.createElement('canvas');
    c.width = 8; c.height = 8;
    const ctx = c.getContext('2d');
    ctx.fillStyle = '#c0392b'; ctx.fillRect(0, 0, 8, 8);
    const dataUrl = c.toDataURL('image/png');

    await img.updateStyle(st._uid, { thumb: dataUrl });

    // 关键：重新从存储加载 —— 这一步会把 dataURL 变成 blob: 短引用
    img.styleConfigs = [];
    await img.load();
    const back = img.styleConfigs.find(s => s._uid === st._uid);
    return JSON.stringify({
      存的长度: dataUrl.length,
      存的前缀: dataUrl.slice(0, 22),
      读回来的前缀: back && back.thumb ? back.thumb.slice(0, 22) : null,
      读回来的长度: back && back.thumb ? back.thumb.length : 0,
    });
  `));
  console.log('  ' + JSON.stringify(thumb));
  check('★ 读回来的是 blob 短引用（这就是以前变黑的原因）',
    String(thumb.读回来的前缀 || '').startsWith('blob:'), thumb.读回来的前缀);

  // 现在去设置页看真实渲染出来的东西。
  // 设置页是网格导航：要先点进「出图风格」那个子页，卡片才在 DOM 里。
  await ev(`location.hash = '#/settings'; return 1;`);
  await sleep(4000);
  const entered = await ev(`
    const items = Array.from(document.querySelectorAll('button,.grid-item,.card'));
    const hit = items.find(e => /出图风格/.test(e.textContent || ''));
    if (hit) { hit.click(); return '已点进 出图风格'; }
    return '没找到入口';
  `);
  console.log('  进入子页: ' + entered);
  await sleep(3500);

  const rendered = JSON.parse(await ev(`
    const cards = Array.from(document.querySelectorAll('.sc-thumb'));
    const info = cards.map(function(c) {
      const bg = getComputedStyle(c).backgroundImage || '';
      return {
        背景: bg.slice(0, 40),
        是真图片: bg.includes('data:image'),
        是坏引用: bg.includes('blob:'),
        有占位符: !!c.querySelector('.sc-thumb-empty'),
      };
    });
    return JSON.stringify({ 卡片数: cards.length, 详情: info });
  `));
  console.log('  ' + JSON.stringify(rendered));
  check('设置页有风格卡片', rendered.卡片数 > 0, `共 ${rendered.卡片数}`);
  const withImg = (rendered.详情 || []).filter((x) => x.是真图片);
  check('★ 参考图渲染成了真 data URL（不是 blob 坏引用）', withImg.length > 0,
    JSON.stringify((rendered.详情 || []).slice(0, 3)));
  check('★ 没有卡片在用坏掉的 blob 引用', !(rendered.详情 || []).some((x) => x.是坏引用));

  // ============ ④ 本地部署 provider ============
  console.log('\n—— ④ 本地部署（自建服务）——');
  await ev(`location.hash = '#/settings'; return 1;`);
  await sleep(3000);
  const prov = JSON.parse(await ev(`
    const { useImageStore } = await import('/src/stores/image.ts');
    const img = useImageStore(); await img.load();
    // 连接类字段（provider/baseUrl/apiKey/model）归 API
    const api = img.currentApi || (await img.addApi('本地出图'));
    await img.updateApi(api._uid, {
      provider: 'local',
      baseUrl: 'http://192.168.137.1:8899',
      apiKey: '123456',
      model: 'nai-anime-v2.ckpt',
    });
    // 尺寸/步数属于**风格**那份，写 updateApi 会被丢掉
    const st = img.currentStyle || (await img.addStyle('本地风格'));
    await img.updateStyle(st._uid, { width: 512, height: 512, steps: 28, sampler: 'euler' });

    const back = img.currentApi;
    const bst = img.currentStyle;
    return JSON.stringify({
      provider: back.provider,
      baseUrl: back.baseUrl,
      model: back.model,
      宽高: bst.width + 'x' + bst.height,
      步数: bst.steps,
    });
  `));
  console.log('  ' + JSON.stringify(prov));
  check('★ 接口类型 local 可保存', prov.provider === 'local');
  check('★ 地址保存正确', prov.baseUrl === 'http://192.168.137.1:8899');
  check('★ 模型保存正确', prov.model === 'nai-anime-v2.ckpt');
  check('★ 尺寸/步数保存正确', prov.宽高 === '512x512' && prov.步数 === 28, JSON.stringify(prov));

  // 设置页的下拉里真的有这一项（要先点进「生图接口」子页）
  await ev(`location.hash = '#/settings'; return 1;`);
  await sleep(3500);
  await ev(`
    const items = Array.from(document.querySelectorAll('button,.grid-item,.card'));
    const hit = items.find(e => /生图接口/.test(e.textContent || ''));
    if (hit) hit.click();
    return 1;
  `);
  await sleep(3500);
  const opt = await ev(`
    const els = Array.from(document.querySelectorAll('option'));
    const hit = els.find(o => o.value === 'local');
    return hit ? hit.textContent.trim() : ('没找到；现有：' + els.slice(0,12).map(o=>o.value).join(','));
  `);
  console.log('  下拉项: ' + opt);
  check('★ 接口类型下拉里有「本地部署」这一项', /本地部署/.test(opt), opt);

  // ============ ⑤ 真的打一次本地服务 ============
  console.log('\n—— ⑤ 调用本地出图服务 ——');
  const real = JSON.parse(await ev(`
    const { generateImage } = await import('/src/engine/imageGen.ts');
    const { useImageStore } = await import('/src/stores/image.ts');
    const { hasNativeHttp } = await import('/src/lib/http.ts');
    const img = useImageStore(); await img.load();
    const cfg = img.currentConfig;
    const t0 = Date.now();
    try {
      const r = await generateImage(null, { ...cfg, referenceImage: '' }, {
        manualPrompt: '1girl, silver hair, red eyes, simple background',
        manualNegativePrompt: '',
        contextMessages: [],
      });
      return JSON.stringify({
        成功: true,
        有原生桥: hasNativeHttp(),
        耗时: Math.round((Date.now() - t0) / 1000),
        provider: r.provider,
        模型: r.model,
        图长: r.dataUrl.length,
        是真图片: r.dataUrl.startsWith('data:image'),
      });
    } catch (e) {
      return JSON.stringify({ 成功: false, 有原生桥: hasNativeHttp(), 错误: String(e && e.message || e).slice(0, 160) });
    }
  `));
  console.log('  ' + JSON.stringify(real));

  /*
   * 浏览器里连不上是**预期行为**，不是失败：
   * 自建服务不发 CORS 头，浏览器必然拦；只有 APK 走原生桥才不受限。
   * 这一条判断的是"在什么环境下该成功"。
   */
  if (real.有原生桥) {
    check('★ 有原生桥 → 本地服务出图成功', real.成功 === true, JSON.stringify(real).slice(0, 160));
    if (real.成功) {
      check('★ 返回真图片', real.是真图片 === true, `长度 ${real.图长}`);
      check('★ provider 标记为 local', real.provider === 'local');
    }
  } else {
    check('浏览器无原生桥 → 被 CORS 拦住（预期）', real.成功 === false);
    check('失败原因是跨域，且提示里讲清楚了',
      /跨域|CORS|Failed to fetch/.test(real.错误 || ''), (real.错误 || '').slice(0, 90));
    console.log('     （真机上的成功路径由 _localcheck.mjs 单独验证）');
  }

  console.log('\n  页面错误: ' + (errs.length ? [...new Set(errs)].slice(0, 4).join(' | ') : '无'));
  console.log(`  失败项: ${fails}`);
  ws.close();
  await fetch(`${CDP}/json/close/${page.id}`).catch(() => {});
  if (fails > 0) process.exitCode = 1;
});
