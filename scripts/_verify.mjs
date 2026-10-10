// 最终验证（带基线保护）
//
// 这个脚本同时验证两件事：
//   1. 功能：界面正常 + 配置真的生效
//   2. 防污染机制：测试把数据改乱之后，withCleanData 能把它还原回去
import fs from 'node:fs';
import path from 'node:path';
import { withCleanData, DATA } from './_testdata.mjs';

const CDP = 'http://127.0.0.1:' + (process.argv[2] || '9420');
const BASE = 'http://192.168.31.45:5173';

await withCleanData(async () => {
  const created = await (await fetch(`${CDP}/json/new?about:blank`, { method: 'PUT' })).json();
  let page;
  for (let i = 0; i < 40; i++) {
    const list = await (await fetch(`${CDP}/json/list`)).json();
    page = list.find((t) => t.id === created.id);
    if (page?.webSocketDebuggerUrl) break;
    await new Promise((r) => setTimeout(r, 300));
  }
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  let id = 1;
  const pend = new Map();
  const errors = [];
  const netFails = [];
  ws.addEventListener('message', (e) => {
    const m = JSON.parse(e.data);
    if (m.method === 'Runtime.exceptionThrown') errors.push((m.params.exceptionDetails.exception?.description || '').slice(0, 170));
    if (m.method === 'Network.loadingFailed') netFails.push(String(m.params.errorText || '').slice(0, 40));
    if (m.id && pend.has(m.id)) { const h = pend.get(m.id); pend.delete(m.id); m.error ? h.rej(new Error(JSON.stringify(m.error))) : h.res(m.result); }
  });
  await new Promise((r, j) => { ws.addEventListener('open', r); ws.addEventListener('error', j); });
  function send(method, params = {}, timeout = 180000) {
    return new Promise((res, rej) => {
      const i = id++;
      const tm = setTimeout(() => { pend.delete(i); rej(new Error('timeout')); }, timeout);
      pend.set(i, { res: (v) => { clearTimeout(tm); res(v); }, rej: (e) => { clearTimeout(tm); rej(e); } });
      ws.send(JSON.stringify({ id: i, method, params }));
    });
  }
  async function ev(expr, attempt = 0) {
    try {
      const r = await send('Runtime.evaluate', { expression: `(async()=>{${expr}})()`, awaitPromise: true, returnByValue: true });
      if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description);
      return r.result.value;
    } catch (e) {
      if (attempt < 2 && /timeout/i.test(e.message)) { await new Promise((r) => setTimeout(r, 6000)); return ev(expr, attempt + 1); }
      throw e;
    }
  }
  await send('Runtime.enable');
  await send('Page.enable');
  await send('Network.enable');
  await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });

  const HEALTH = `
    const vw = window.innerWidth;
    const inScroller = function(el){ let p = el.parentElement; while (p) { const c = getComputedStyle(p); if (c.overflowX === 'auto' || c.overflowX === 'scroll') return true; p = p.parentElement; } return false; };
    let over = 0;
    document.querySelectorAll('*').forEach(function(e){
      const c = getComputedStyle(e);
      if (c.display === 'none' || c.position === 'fixed') return;
      if (inScroller(e)) return;
      const r = e.getBoundingClientRect();
      if (r.width > 0 && (r.right > vw + 2 || r.left < -2)) over++;
    });
    const imgs = Array.from(document.querySelectorAll('img'));
    return JSON.stringify({
      溢出: over,
      坏图: imgs.filter(function(i){ return i.complete && i.naturalWidth === 0; }).length,
      空链接: Array.from(document.querySelectorAll('a')).filter(function(a){ const h = a.getAttribute('href'); return !h || h === '#'; }).length,
    });
  `;

  console.log('################ 一、每个界面 ################');
  for (const [n, h] of [['冒险列表', '/#/adventure'], ['小说列表', '/#/novel'], ['资产', '/#/assets'], ['设置', '/#/settings'], ['生图标签', '/#/tags'], ['图片库', '/#/gallery']]) {
    await send('Page.navigate', { url: BASE + h });
    await new Promise((r) => setTimeout(r, 10500));
    console.log('  ' + n.padEnd(6) + ' ' + (await ev(HEALTH)));
  }

  console.log('\n################ 二、冒险：二级 + 抽屉 ################');
  await send('Page.navigate', { url: BASE + '/#/adventure' });
  await new Promise((r) => setTimeout(r, 10500));
  const hasRow = await ev("const x = document.querySelector('.chat-row'); if (x) { x.click(); await new Promise(function(r){ setTimeout(r, 3000); }); return 'ok'; } return 'no-session';");
  if (hasRow !== 'ok') { console.log('（干净基线里没有会话，跳过抽屉检查）'); } else
  await ev("document.querySelector('.chat-row').click(); await new Promise(function(r){ setTimeout(r, 3000); });");
  for (const [label, match] of [['资产', '资产'], ['摘要', '摘要']]) {
    const r = await ev(
      'const ex = document.querySelector(".extra-btns");' +
        'const t = document.querySelector(".more-toggle");' +
        'if (!ex) return JSON.stringify({ 没找到: true, 原因: "没有折叠区" });' +
        'if (getComputedStyle(ex).display === "none" && t) { t.click(); await new Promise(function(r){ setTimeout(r, 700); }); }' +
        'const b = Array.from(document.querySelectorAll(".adventure-header button")).find(function(x){ return x.textContent.indexOf("' + match + '") >= 0; });' +
        'if (!b) return JSON.stringify({ 没找到: true });' +
        'b.click(); await new Promise(function(r){ setTimeout(r, 1300); });' +
        'const el = document.querySelector(".asset-aside") || document.querySelector(".session-list");' +
        'const nav = document.querySelector(".sidebar");' +
        'if (!el) return JSON.stringify({ 没出现: true });' +
        'const r0 = el.getBoundingClientRect(); const nr = nav ? nav.getBoundingClientRect() : null;' +
        'return JSON.stringify({ 在屏内: r0.left >= -2 && r0.right <= window.innerWidth + 2, 不压标签栏: nr ? r0.bottom <= nr.top + 2 : null });',
    );
    console.log('  ' + label.padEnd(8) + ' ' + r);
    await ev('const m = document.querySelector(".sheet-backdrop"); if (m) m.click(); await new Promise(function(r){ setTimeout(r, 900); });');
  }

  console.log('\n################ 三、配置生效（请求体实证）################');
  await ev(
    'window.__cap = [];' +
      'const realFetch = window.fetch;' +
      'const NL = String.fromCharCode(10);' +
      'window.fetch = function(u, o) {' +
      '  const url = typeof u === "string" ? u : (u && u.url) || "";' +
      '  if (url.indexOf("192.168.31.45") >= 0 || url.indexOf("/__persist") >= 0) return realFetch(u, o);' +
      '  try { window.__cap.push({ url: url.split("?")[0], body: JSON.parse(String(o.body)) }); } catch (e) {}' +
      '  const enc = new TextEncoder();' +
      '  const payload = "data: " + JSON.stringify({ choices: [{ delta: { content: "ok" } }] }) + NL + NL + "data: [DONE]" + NL + NL;' +
      '  return Promise.resolve(new Response(new ReadableStream({ start: function(c){ c.enqueue(enc.encode(payload)); c.close(); } }), { status: 200 }));' +
      '};' +
      'return "ok";',
  );
  const probe = async (label, text) => {
    const r = await ev(
      'window.__cap = [];' +
        'const ta = document.querySelector(".input-area textarea");' +
        'ta.value = ' + JSON.stringify(text) + ';' +
        'ta.dispatchEvent(new Event("input", { bubbles: true }));' +
        'await new Promise(function(r){ setTimeout(r, 400); });' +
        'document.querySelector(".send-btn").click();' +
        'for (let i = 0; i < 60; i++) { await new Promise(function(r){ setTimeout(r, 250); }); if (!document.querySelector(".cursor")) break; }' +
        'await new Promise(function(r){ setTimeout(r, 1500); });' +
        'const c = window.__cap[0];' +
        'if (!c) return JSON.stringify({ 没抓到: true });' +
        'const b = c.body || {};' +
        'const all = (b.messages || []).map(function(m){ return m.content || ""; }).join("");' +
        'return JSON.stringify({ 模型: b.model, 温度: b.temperature, 最大: b.max_tokens, KB: Math.round(JSON.stringify(b).length/1024), 含世界书: all.indexOf("铜原") >= 0, 含角色卡: all.indexOf("艾琳") >= 0 });',
    );
    console.log('  ' + label.padEnd(12) + r);
  };
  await probe('基线', '最终验证');
  await ev(
    'const { useAssetsStore } = await import("/src/stores/assets.ts");' +
      'const a = useAssetsStore(); await a.load();' +
      'for (const w of a.worldBooks) { if (a.currentWorldBookIds.indexOf(w._uid) >= 0) await a.toggleWorldBook(w._uid, false); }' +
      'await new Promise(function(r){ setTimeout(r, 900); }); return "ok";',
  );
  await probe('世界书全关', '关世界书');

  console.log('\n################ 四、汇总 ################');
  console.log('  页面错误: ' + (errors.length ? [...new Set(errors)].slice(0, 5).join(' | ') : '无'));
  console.log('  网络失败: ' + (netFails.length ? [...new Set(netFails)].slice(0, 5).join(' | ') : '无'));

  ws.close();
  await fetch(`${CDP}/json/close/${page.id}`).catch(() => {});
});

// ---- 验证防污染机制 ----
console.log('\n################ 五、验证防污染机制 ################');
const j = JSON.parse(fs.readFileSync(DATA, 'utf8'));
const d = j.origins['192.168.31.45:5173'].data;
const m = (d.apiConfigs || []).find((c) => c._uid === d.currentApiConfigId);
const preset = (d.presets || []).find((x) => x._uid === d.currentPresetId);
console.log('  测试（含故意改乱世界书）已跑完，数据应已自动还原：');
console.log('    当前模型: ' + (m ? m.model : '(无)'));
console.log('    当前预设: ' + (preset ? preset.name : '(无)'));
console.log('    世界书启用: ' + (d.currentWorldBookIds || []).length + (d.currentWorldBookIds.length === 2 ? '  ✓ 已还原' : '  ✗ 未还原！'));
console.log('    带密钥配置: ' + (d.apiConfigs || []).filter((c) => c.apiKey).length);
