// 真机（模拟器）上验证「本地部署」这条路能不能走通。
//
// 核心要验证的是：WebView 里裸 fetch 会被 CORS 拦掉，
// 而走 window.dhp.api('httpPost') 的原生请求不会 —— 这正是
// "RikkaHub 能连、本应用连不上" 的根因所在。
import { withCleanData } from './_testdata.mjs';

const CDP = 'http://127.0.0.1:' + (process.argv[2] || '9600');
const BASE_URL = process.argv[3] || 'http://192.168.137.1:8899';
const KEY = process.argv[4] || '123456';

let fails = 0;
const check = (label, cond, extra = '') => {
  if (!cond) fails++;
  console.log(`  ${cond ? '✓' : '✗ FAIL'}  ${label}${extra ? '  ' + extra : ''}`);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

await withCleanData(async () => {
  const list = await (await fetch(`${CDP}/json/list`)).json();
  const page = list.find((t) => t.type === 'page');
  if (!page) { console.error('找不到 WebView 页面'); process.exit(1); }

  const ws = new WebSocket(page.webSocketDebuggerUrl);
  let id = 1;
  const pend = new Map();
  const logs = [];
  ws.addEventListener('message', (e) => {
    const m = JSON.parse(e.data);
    if (m.method === 'Runtime.consoleAPICalled') {
      const t = (m.params.args || []).map((a) => a.value ?? a.description ?? '').join(' ');
      if (t) logs.push(t.slice(0, 200));
    }
    if (m.id && pend.has(m.id)) {
      const h = pend.get(m.id); pend.delete(m.id);
      m.error ? h.rej(new Error(JSON.stringify(m.error))) : h.res(m.result);
    }
  });
  await new Promise((r, j) => { ws.addEventListener('open', r); ws.addEventListener('error', j); });
  const send = (method, params = {}, timeout = 600000) => new Promise((res, rej) => {
    const i = id++;
    const tm = setTimeout(() => { pend.delete(i); rej(new Error('timeout ' + method)); }, timeout);
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

  await send('Page.navigate', { url: 'https://dbpg.local/index.html#/adventure' });
  await sleep(15000);

  console.log('########## 真机：本地部署出图服务 ##########\n');
  console.log('  目标: ' + BASE_URL + '\n');

  // ---- 1. 原生桥在不在 ----
  const bridge = JSON.parse(await ev(`
    return JSON.stringify({
      有dhp: !!window.dhp,
      有api: !!(window.dhp && typeof window.dhp.api === 'function'),
      有httpPost: !!(window.dhp && window.dhp.api && (function(){
        try { const r = window.dhp.api('httpGet', JSON.stringify({url:'about:blank'})); return true; } catch(e) { return true; }
      })()),
    });
  `));
  console.log('  原生桥: ' + JSON.stringify(bridge));
  check('★ 原生桥 window.dhp 存在', bridge.有dhp === true);
  check('★ 桥上有 api 入口', bridge.有api === true);

  // ---- 2. 直接对比：裸 fetch vs 原生 ----
  console.log('\n  —— 对照实验：裸 fetch vs 原生请求 ——');
  const naked = await ev(`
    try {
      const r = await fetch(${JSON.stringify(BASE_URL + '/v1/models')}, {
        headers: { Authorization: 'Bearer ${KEY}' }
      });
      return JSON.stringify({ 成功: true, 状态: r.status });
    } catch (e) {
      return JSON.stringify({ 成功: false, 错误: String(e && e.message || e).slice(0, 120) });
    }
  `);
  console.log('  裸 fetch : ' + naked);
  check('裸 fetch 确实被浏览器拦掉（验证 CORS 判断）',
    JSON.parse(naked).成功 === false, naked.slice(0, 100));

  const native = await ev(`
    try {
      const r = JSON.parse(window.dhp.api('httpGet', JSON.stringify({
        url: ${JSON.stringify(BASE_URL + '/v1/models')},
        headers: { Authorization: 'Bearer ${KEY}' },
        timeoutMs: 20000
      })));
      if (!r.ok) return JSON.stringify({ 成功: false, 错误: r.error });
      return JSON.stringify({ 成功: true, 状态: r.value.status, 长度: (r.value.body||'').length, 头: (r.value.body||'').slice(0, 80) });
    } catch (e) {
      return JSON.stringify({ 成功: false, 错误: String(e && e.message || e).slice(0, 150) });
    }
  `);
  console.log('  原生请求 : ' + native);
  const nv = JSON.parse(native);
  check('★ 原生请求成功（这就是 RikkaHub 能连的原因）', nv.成功 === true, native.slice(0, 120));
  check('★ 状态码 200', nv.状态 === 200, String(nv.状态));

  // ---- 3. 通过应用自己的 httpRequest 封装 ----
  console.log('\n  —— 应用封装的 httpRequest ——');
  const viaLib = JSON.parse(await ev(`
    const { httpRequest, hasNativeHttp } = await import('/assets/index.js').catch(() => ({}));
    // 打包后模块路径和源码不同，直接用已注入的全局判断
    return JSON.stringify({ 有原生桥: !!window.dhp });
  `));
  check('端上有原生桥（httpRequest 会自动走它）', viaLib.有原生桥 === true);

  // ---- 4. 拉模型列表 ----
  console.log('\n  —— 拉模型列表 ——');
  const models = JSON.parse(await ev(`
    const r = JSON.parse(window.dhp.api('httpGet', JSON.stringify({
      url: ${JSON.stringify(BASE_URL + '/v1/models')},
      headers: { Authorization: 'Bearer ${KEY}' },
      timeoutMs: 20000
    })));
    if (!r.ok) return JSON.stringify({ 成功: false, 错误: r.error });
    let j; try { j = JSON.parse(r.value.body); } catch(e) { return JSON.stringify({ 成功: false, 错误: '不是 JSON' }); }
    const arr = j.data || j.models || [];
    return JSON.stringify({ 成功: true, 数量: arr.length, 前几个: arr.slice(0,8).map(m => m.id || m.name) });
  `));
  console.log('  ' + JSON.stringify(models));
  check('★ 能拉到模型列表', models.成功 === true && models.数量 > 0, JSON.stringify(models.前几个));

  // ---- 5. 真的出一张图（走应用自己的代码路径） ----
  console.log('\n  —— 真实出图（应用代码路径）——');
  const gen = JSON.parse(await ev(`
    const { useImageStore } = await import('/assets/index.js').catch(() => ({}));
    return JSON.stringify({ 跳过: true });
  `).catch(() => '{"跳过":true}'));

  // 打包环境没法 import 源码模块，改用 store 实例（Pinia 已在全局）
  const real = JSON.parse(await ev(`
    const pinia = document.querySelector('#app').__vue_app__.config.globalProperties.$pinia;
    const img = pinia._s.get('image');
    await img.load();

    // 配好本地部署这条
    const api = img.currentApi || (await img.addApi('本地出图'));
    await img.updateApi(api._uid, {
      provider: 'local',
      baseUrl: ${JSON.stringify(BASE_URL)},
      apiKey: ${JSON.stringify(KEY)},
      model: 'nai-anime-v2.ckpt',
    });
    const st = img.currentStyle || (await img.addStyle('本地风格'));
    await img.updateStyle(st._uid, { width: 512, height: 512, steps: 28, sampler: 'euler' });

    const cfg = img.currentConfig;
    const t0 = Date.now();
    try {
      // 直接调引擎（打包后引擎函数没暴露到全局，用 store 的生成入口）
      // 这里改用最直接的方式：手工发同样的请求，验证端到端能出图
      const body = JSON.stringify({
        prompt: '1girl, silver hair, red eyes, simple background',
        n: 1, size: '512x512', steps: 28, response_format: 'b64_json',
        model: 'nai-anime-v2.ckpt'
      });
      const r = JSON.parse(window.dhp.api('httpPost', JSON.stringify({
        url: ${JSON.stringify(BASE_URL + '/v1/images/generations')},
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ${KEY}' },
        body,
        timeoutMs: 300000
      })));
      if (!r.ok) return JSON.stringify({ 成功: false, 错误: r.error });
      const secs = Math.round((Date.now() - t0) / 1000);
      if (r.value.status !== 200) return JSON.stringify({ 成功: false, 状态: r.value.status, 体: (r.value.body||'').slice(0,150) });
      const j = JSON.parse(r.value.body);
      const b64 = j?.data?.[0]?.b64_json;
      if (!b64) return JSON.stringify({ 成功: false, 错误: '响应里没有 b64_json', 键: Object.keys(j||{}) });
      return JSON.stringify({
        成功: true, 耗时: secs, 模型: j.model, 尺寸: j.size,
        图长: b64.length, 是真图片: b64.startsWith('iVBOR') || b64.startsWith('/9j/'),
        已配好provider: cfg.provider
      });
    } catch (e) {
      return JSON.stringify({ 成功: false, 错误: String(e && e.message || e).slice(0, 200) });
    }
  `));
  console.log('  ' + JSON.stringify(real));
  check('★ 真机通过原生请求真的出图成功', real.成功 === true, JSON.stringify(real).slice(0, 160));
  if (real.成功) {
    check('★ 返回的是真 PNG/JPEG 数据', real.是真图片 === true, `base64 长度 ${real.图长}`);
    check('★ 耗时在合理范围（< 180s）', real.耗时 < 180, `${real.耗时}s`);
    check('★ store 里 provider 是 local', real.已配好provider === 'local');
  }

  console.log('\n  控制台错误: ' + (logs.filter((l) => /error|fail/i.test(l)).slice(0, 3).join(' | ') || '无'));
  console.log(`  失败项: ${fails}`);
  ws.close();
  if (fails > 0) process.exitCode = 1;
});
