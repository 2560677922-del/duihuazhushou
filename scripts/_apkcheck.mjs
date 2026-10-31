// 真机（Android WebView）验收 —— 本轮四项改动。
//
// 连的是模拟器里那个已安装 APK 的 WebView（adb forward 到 9600），
// 所以跑的是**打包后的那份代码**（压缩过的），和浏览器里的 dev 版本不是同一份。
//
// store 怎么拿到：Vue 3 会在根元素上挂 __vue_app__，从它里面能拿到 pinia，
// 再用 store id 取（id 见 src/stores/*.ts 的 defineStore 第一个参数）。
// 这套 bundle 是压缩的，没法按路径 import 源码模块，所以只能走这条路。
const CDP = 'http://127.0.0.1:' + (process.argv[2] || '9600');

let fails = 0;
const check = (label, cond, extra = '') => {
  if (!cond) fails++;
  console.log(`  ${cond ? '✓' : '✗ FAIL'}  ${label}${extra ? '  ' + extra : ''}`);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const list = await (await fetch(`${CDP}/json/list`)).json();
const page = list.find((t) => t.type === 'page' && /dbpg\.local/.test(t.url || ''));
if (!page) {
  console.error('找不到已安装 APK 的页面，列表: ' + JSON.stringify(list.map((t) => t.url)));
  process.exit(1);
}
console.log('目标页面: ' + page.url);

const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 1;
const pend = new Map();
const errs = [];
ws.addEventListener('message', (e) => {
  const m = JSON.parse(e.data);
  if (m.method === 'Runtime.exceptionThrown')
    errs.push((m.params.exceptionDetails.exception?.description || '').slice(0, 160));
  if (m.id && pend.has(m.id)) {
    const h = pend.get(m.id);
    pend.delete(m.id);
    m.error ? h.rej(new Error(JSON.stringify(m.error))) : h.res(m.result);
  }
});
await new Promise((r, j) => { ws.addEventListener('open', r); ws.addEventListener('error', j); });
const send = (method, params = {}, timeout = 120000) =>
  new Promise((res, rej) => {
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

console.log('\n################ 真机 APK 验收 ################\n');

// —— 0. 页面是否真的活着
const alive = await ev(`
  const root = document.querySelector('#app');
  return JSON.stringify({
    标题: document.title,
    当前hash: location.hash,
    根节点有内容: !!root && root.innerHTML.length > 100,
    原生桥: !!(window.Capacitor && window.Capacitor.isNativePlatform && window.Capacitor.isNativePlatform()),
    有vue_app: !!(root && root.__vue_app__),
  });
`);
console.log('  运行状态: ' + alive);
const al = JSON.parse(alive);
check('APK 页面渲染正常', al.根节点有内容 === true);
check('运行在原生环境（Capacitor 桥生效）', al.原生桥 === true);
check('能拿到 Vue 应用实例（后续断言依赖它）', al.有vue_app === true);

// —— 1. 底部标签栏：没有「小说」
const nav = await ev(`
  const root = document.querySelector('#app');
  const pinia = root.__vue_app__.config.globalProperties.$pinia;
  const keys = Array.from(pinia._s.keys());
  const tabs = Array.from(document.querySelectorAll('nav a, nav button, .tabbar a, .tabbar button'))
    .map(function(t){ return t.textContent.replace(/\\s+/g,'').trim(); }).filter(Boolean);
  return JSON.stringify({ 标签: tabs, store列表: keys, 正文提到小说: document.body.innerText.indexOf('小说') >= 0 });
`);
console.log('  标签栏: ' + nav);
const nv = JSON.parse(nav);
check('★ 真机上底部没有「小说」入口', !nv.标签.some((t) => /小说/.test(t)), JSON.stringify(nv.标签));
check('「冒险」还在', nv.标签.some((t) => /冒险/.test(t)));
check('★ novel store 已不存在于运行时', !nv.store列表.includes('novel'), JSON.stringify(nv.store列表));

// —— 2. 旧书签兜底
await ev(`location.hash = '#/novel'; return 1;`);
await sleep(2500);
const rt = await ev(`return JSON.stringify({ hash: location.hash, 正文长度: (document.body.innerText||'').replace(/\\s+/g,'').length });`);
console.log('  访问 #/novel: ' + rt);
check('★ 真机上旧书签 #/novel 兜底到冒险页', /adventure/.test(JSON.parse(rt).hash), JSON.parse(rt).hash);

// —— 3. 一起做一次真实感的"生成中"流程（把 fetch 换成慢速假流）
const prep = await ev(`
  const root = document.querySelector('#app');
  const pinia = root.__vue_app__.config.globalProperties.$pinia;
  const adv = pinia._s.get('adventure');
  const api = pinia._s.get('apiConfig');
  const assets = pinia._s.get('assets');
  await Promise.all([adv.load(), api.load(), assets.load()]);

  const s = await adv.addSession('真机验收', '一间昏暗的屋子。');
  await adv.switchSession(s.id);

  let cfg = api.currentConfig;
  if (!cfg) cfg = await api.addConfig('真机测试');
  await api.updateConfig(cfg._uid, { apiKey: 'k', model: 'm', baseUrl: 'https://example.invalid/v1' });
  await api.setCurrent(cfg._uid);

  return JSON.stringify({
    会话id: s.id,
    预设数: (assets.presets || []).length,
    当前预设: (assets.getCurrentPreset && assets.getCurrentPreset()) ? 'ok' : 'none',
  });
`);
console.log('  准备: ' + prep);
const sd = JSON.parse(prep);

await ev(`location.hash = '#/adventure/' + ${JSON.stringify(sd.会话id)}; return 1;`);
await sleep(4000);

// 预设要**等进了聊天页再看**：Adventure.vue 的 onMounted 会在首次进入时
// 自动载入示例资产（含一个预设），在上面那步之前查必然是 0。
const pcheck = await ev(`
  const root = document.querySelector('#app');
  const pinia = root.__vue_app__.config.globalProperties.$pinia;
  const assets = pinia._s.get('assets');
  return JSON.stringify({ 预设数: (assets.presets||[]).length, 有当前预设: !!assets.getCurrentPreset() });
`);
console.log('  预设（进入聊天页后）: ' + pcheck);
check('有可用预设（否则真实场景下发不出请求）', JSON.parse(pcheck).有当前预设 === true, pcheck);

// 假 SSE：40 段 × 1 秒，够慢，能从容观察
await ev(`
  window.__aborted = false; window.__sent = 0;
  const realFetch = window.fetch;
  window.fetch = function(url, opts) {
    const u = String(url);
    if (u.indexOf('chat/completions') < 0) return realFetch.apply(this, arguments);
    const signal = opts && opts.signal;
    window.__hasSignal = !!signal;
    let i = 0, timer = null, closed = false;
    const enc = new TextEncoder();
    const stream = new ReadableStream({ start: function(controller) {
      const stop = function(){ closed = true; if (timer) clearInterval(timer); };
      timer = setInterval(function(){
        if (closed) return;
        if (i < 40) {
          controller.enqueue(enc.encode('data: ' + JSON.stringify({ choices: [{ delta: { content: '第'+(i+1)+'段。' } }] }) + '\\n\\n'));
          window.__sent++; i++;
        } else { controller.enqueue(enc.encode('data: [DONE]\\n\\n')); stop(); try{controller.close();}catch(e){} }
      }, 1000);
      const onAbort = function(){ stop(); window.__aborted = true; try{ controller.error(new DOMException('aborted','AbortError')); }catch(e){} };
      if (signal) { if (signal.aborted) onAbort(); else signal.addEventListener('abort', onAbort); }
    }});
    return Promise.resolve(new Response(stream, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }));
  };
  return 1;
`);

const sent = await ev(`
  const ta = document.querySelector('.input-area textarea');
  if (!ta) return JSON.stringify({ 找不到输入框: true });
  ta.value = '我环顾四周';
  ta.dispatchEvent(new Event('input', { bubbles: true }));
  await new Promise(function(r){ setTimeout(r, 400); });
  const b = Array.from(document.querySelectorAll('.input-area button')).find(function(x){ return /发送/.test(x.textContent); });
  if (!b) return JSON.stringify({ 找不到发送按钮: true, 按钮: Array.from(document.querySelectorAll('.input-area button')).map(function(x){return x.textContent.trim();}) });
  b.click();
  return JSON.stringify({ 已发送: true });
`);
console.log('  发送: ' + sent);
check('真机上点到了发送', JSON.parse(sent).已发送 === true);

await sleep(3000);
const mid = await ev(`
  const banner = document.querySelector('.gen-banner');
  const stopBtn = document.querySelector('.gen-stop');
  const inputStop = Array.from(document.querySelectorAll('.input-area button')).find(function(x){ return /停止/.test(x.textContent); });
  return JSON.stringify({
    有提示条: !!banner,
    提示文字: banner ? banner.textContent.replace(/\\s+/g,' ').trim() : '',
    有停止按钮: !!stopBtn,
    输入区有停止: !!inputStop,
    已收到: window.__sent,
    传了signal: window.__hasSignal === true,
  });
`);
const md = JSON.parse(mid);
console.log('  生成中: ' + mid);
check('★ 真机上出现「正在生成」提示条', md.有提示条 === true);
check('★ 真机上有停止按钮', md.有停止按钮 === true && md.输入区有停止 === true);
check('真机上流式内容正常到达', md.已收到 >= 2, '收到 ' + md.已收到 + ' 段');
check('真机上 signal 也传给了 fetch', md.传了signal === true);

// —— 4. 切走再回来
await ev(`location.hash = '#/assets'; return 1;`);
await sleep(2000);
const away = await ev(`return JSON.stringify({ 还在跑: window.__aborted === false, 已收到: window.__sent });`);
console.log('  切到资产页: ' + away);
check('★ 真机上离开页面请求仍在后台继续', JSON.parse(away).还在跑 === true);

await ev(`location.hash = '#/adventure/' + ${JSON.stringify(sd.会话id)}; return 1;`);
await sleep(2500);
const back = await ev(`
  const banner = document.querySelector('.gen-banner');
  const hint = document.querySelector('.gen-hint');
  return JSON.stringify({
    有提示条: !!banner,
    提示文字: banner ? banner.textContent.replace(/\\s+/g,' ').trim() : '',
    有继续提示: hint ? hint.textContent.trim() : '',
  });
`);
const bk = JSON.parse(back);
console.log('  切回聊天: ' + back);
check('★ 真机上回来仍显示「正在生成」', bk.有提示条 === true);
check('★ 真机上明确提示"离开时也没断"', /离开页面时也没断/.test(bk.有继续提示), bk.有继续提示);

// —— 5. 停止
await ev(`
  const b = document.querySelector('.gen-stop') || Array.from(document.querySelectorAll('.input-area button')).find(function(x){ return /停止/.test(x.textContent); });
  if (b) b.click();
  return 1;
`);
await sleep(3000);
const after = await ev(`
  const banner = document.querySelector('.gen-banner');
  const sendBtn = Array.from(document.querySelectorAll('.input-area button')).find(function(x){ return /发送/.test(x.textContent); });
  const ai = document.querySelectorAll('.message.ai .msg-content');
  return JSON.stringify({
    已中止: window.__aborted === true,
    提示条消失: !banner,
    恢复发送: !!sendBtn,
    有报错: !!document.querySelector('.error-bar'),
    最后回复: ai.length ? ai[ai.length-1].textContent.slice(0,40) : '',
  });
`);
const af = JSON.parse(after);
console.log('  停止后: ' + after);
check('★ 真机上停止真的掐断了请求', af.已中止 === true);
check('真机上停止后提示条消失、恢复发送', af.提示条消失 === true && af.恢复发送 === true);
check('真机上停止不算报错', af.有报错 === false);
check('真机上已生成部分被保留', af.最后回复.length > 0, af.最后回复);

// —— 5b. 重新生成不再"底下又来一套一模一样的"
//     （用户报的核心问题：原消息不动、底下又流出一份，看着像 bug）
console.log('\n  —— 真机：重新生成的就地替换 ——');
const dup = await ev(`
  const root = document.querySelector('#app');
  const pinia = root.__vue_app__.config.globalProperties.$pinia;
  const adv = pinia._s.get('adventure');
  const s = adv.currentSession;
  const act = s.actions.find(function(a){ return a.ai; });
  if (!act) return JSON.stringify({ 没有可用的消息: true });
  // 记下原文，稍后要验证它被"让位"了
  window.__oldAi = act.ai;
  // 让假流这一轮吐的是一段好认的新内容
  window.__round = 100;
  return JSON.stringify({ 动作id: act.id, 原文片段: act.ai.slice(0, 20) });
`);
console.log('  选中消息: ' + dup);
const dd = JSON.parse(dup);
check('真机上有可重新生成的消息', !dd.没有可用的消息);

// 换一个**更短**的假流（5 段 × 0.8 秒）：
// 上面那个是 40 段的，跑完要 40 秒，等不到"生成完"的状态。
await ev(`
  const realFetch = window.fetch;
  window.fetch = function(url, opts) {
    const u = String(url);
    if (u.indexOf('chat/completions') < 0) return realFetch.apply(this, arguments);
    window.__sent2 = 0;
    const chunks = ['重生成后的', '全新回答：', '一只猫', '在盯着你', '看。'];
    let i = 0, timer = null, closed = false;
    const enc = new TextEncoder();
    const stream = new ReadableStream({ start: function(controller) {
      const stop = function(){ closed = true; if (timer) clearInterval(timer); };
      timer = setInterval(function(){
        if (closed) return;
        if (i < chunks.length) {
          controller.enqueue(enc.encode('data: ' + JSON.stringify({ choices: [{ delta: { content: chunks[i] } }] }) + '\\n\\n'));
          window.__sent2++; i++;
        } else { controller.enqueue(enc.encode('data: [DONE]\\n\\n')); stop(); try{controller.close();}catch(e){} }
      }, 800);
      const sig = opts && opts.signal;
      if (sig) sig.addEventListener('abort', function(){ stop(); try{ controller.error(new DOMException('aborted','AbortError')); }catch(e){} });
    }});
    return Promise.resolve(new Response(stream, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }));
  };
  return 1;
`);

await ev(`
  const root = document.querySelector('#app');
  const pinia = root.__vue_app__.config.globalProperties.$pinia;
  const adv = pinia._s.get('adventure');
  const act = adv.currentSession.actions.find(function(a){ return a.id === ${JSON.stringify(dd.动作id)}; });
  // 直接点操作条上的「重新生成」
  const el = Array.from(document.querySelectorAll('.message.ai')).find(function(m){
    return m.textContent.indexOf(${JSON.stringify(dd.原文片段)}) >= 0;
  });
  if (!el) return 1;
  const box = el.querySelector('.msg-actions');
  if (box) {
    const b = Array.from(box.querySelectorAll('button')).find(function(x){ return /重新生成/.test(x.textContent); });
    if (b) b.click();
  }
  return 1;
`);
await sleep(2500);const dupMid = await ev(`
  const users = document.querySelectorAll('.message.user .msg-content').length;
  const ais = document.querySelectorAll('.message.ai .msg-content').length;
  const texts = Array.from(document.querySelectorAll('.message.ai .msg-content')).map(function(e){ return e.textContent; });
  return JSON.stringify({
    用户气泡数: users,
    AI气泡数: ais,
    还能看到旧正文: texts.some(function(t){ return t.indexOf(String(window.__oldAi).slice(0,20)) >= 0; }),
    有重新生成标签: !!document.querySelector('.regen-tag'),
  });
`);
console.log('  重新生成中: ' + dupMid);
const dm = JSON.parse(dupMid);
check('★ 真机上重新生成时没有多出第二套气泡', dm.AI气泡数 === 1 && dm.用户气泡数 === 1, dupMid);
check('★ 真机上旧正文已让位', dm.还能看到旧正文 === false);
check('真机上有「重新生成中…」标签', dm.有重新生成标签 === true);

// 等这一轮跑完，看版本切换器
await sleep(9000);
const dupDone = await ev(`
  const vs = document.querySelector('.version-switch');
  const users = document.querySelectorAll('.message.user .msg-content').length;
  const ais = document.querySelectorAll('.message.ai .msg-content').length;
  return JSON.stringify({
    版本切换器: vs ? vs.textContent.replace(/\\s+/g,' ').trim() : '',
    用户气泡数: users,
    AI气泡数: ais,
  });
`);
console.log('  生成完: ' + dupDone);
const dn = JSON.parse(dupDone);
check('★ 真机上出现了版本切换器（可以翻回原先那版）', /1\/[2-9]/.test(dn.版本切换器), dn.版本切换器);
check('真机上生成完仍然只有一套气泡', dn.AI气泡数 === 1 && dn.用户气泡数 === 1, dupDone);

// —— 6. 图片库不出现「小说」
await ev(`location.hash = '#/gallery'; return 1;`);
await sleep(4000);
const gal = await ev(`
  const btns = Array.from(document.querySelectorAll('button')).filter(function(b){ return ['全部','小说','冒险'].indexOf(b.textContent.trim()) >= 0; });
  return JSON.stringify({
    筛选: btns.map(function(b){ return b.textContent.trim(); }),
    正文提到小说: (document.body.innerText||'').indexOf('小说') >= 0,
  });
`);
console.log('  图片库: ' + gal);
const gu = JSON.parse(gal);
check('★ 真机图片库筛选没有「小说」', !gu.筛选.includes('小说'), JSON.stringify(gu.筛选));
check('真机图片库正文不再出现「小说」', gu.正文提到小说 === false);

console.log('\n  页面错误: ' + (errs.length ? [...new Set(errs)].slice(0, 5).join(' | ') : '无'));
console.log(`  失败项: ${fails}`);
ws.close();
process.exitCode = fails > 0 ? 1 : 0;
