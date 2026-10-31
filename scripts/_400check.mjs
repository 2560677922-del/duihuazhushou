// 验证「第三方中转报 400 时自动降级成纯 OpenAI 字段」。
//
// 真实原因：这个 provider 的请求体里混了 NovelAI 专有字段
// （ucPreset / qualityToggle / noise_schedule / sampler…），
// 宽松中转会忽略、严格中转直接 400 —— 所以表现为"有时行有时 400"。
//
// 这里用两个假服务分别模拟"严格"和"宽松"两种中转，验证：
//   · 严格中转：第一次 400，第二次（纯 OpenAI 字段）成功
//   · 宽松中转：第一次就成功，不该多发请求
//   · 鉴权失败（401）：不该重试
import { withCleanData } from './_testdata.mjs';
import http from 'node:http';

const CDP = 'http://127.0.0.1:' + (process.argv[2] || '9500');
const BASE = 'http://127.0.0.1:5173';
/**
 * 三种"中转"用**三个端口**区分。
 *
 * 为什么不用路径（/strict、/lenient）：`resolveOpenAIImageUrls` 会把
 * baseUrl 里的路径段丢掉、只保留 origin 再去拼 `/v1/images/generations`。
 * 所以 127.0.0.1:9801/strict 和 …/lenient 最终打到的是同一个地址，
 * 没法区分。端口不会被丢，用它最稳。
 */
const PORT_STRICT = Number(process.argv[3] || 9811);
const PORT_LENIENT = PORT_STRICT + 1;
const PORT_AUTHFAIL = PORT_STRICT + 2;

let fails = 0;
const check = (label, cond, extra = '') => {
  if (!cond) fails++;
  console.log(`  ${cond ? '✓' : '✗ FAIL'}  ${label}${extra ? '  ' + extra : ''}`);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
/** NovelAI 专有字段 —— 严格网关会因为这些字段报 400 */
const NOVELAI_KEYS = ['ucPreset', 'qualityToggle', 'noise_schedule', 'sampler', 'scale', 'steps'];

/** 收到的请求体，按模式区分 */
const seen = { strict: [], lenient: [], authfail: [] };
/** 全部请求的原始记录，排查用 */
const allReqs = [];

/**
 * 每种模式起一个独立端口的服务。
 * @param mode 'strict' | 'lenient' | 'authfail'
 */
function makeServer(mode) {
  return http.createServer((req, res) => {
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'POST, GET, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', '*');

    if (req.method === 'OPTIONS') {
      res.writeHead(204);
      res.end();
      return;
    }

    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      let parsed = {};
      try { parsed = JSON.parse(body); } catch { /* 忽略 */ }
      allReqs.push({ mode, url: req.url, keys: Object.keys(parsed) });
      if (seen[mode]) seen[mode].push(parsed);

      if (mode === 'authfail') {
        res.writeHead(401, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: { message: 'invalid api key', type: 'authentication_error' } }));
        return;
      }

      if (mode === 'strict') {
        // 严格校验：出现任何 NovelAI 专有字段就 400
        const extras = NOVELAI_KEYS.filter((k) => k in parsed);
        if (extras.length) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({
            error: {
              message: `unknown parameter: ${extras.join(', ')}`,
              type: 'invalid_request_error',
            },
          }));
          return;
        }
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ data: [{ b64_json: PNG }] }));
        return;
      }

      // lenient：什么都收
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ data: [{ b64_json: PNG }] }));
    });
  });
}

const servers = [
  makeServer('strict'), makeServer('lenient'), makeServer('authfail'),
];
await Promise.all([
  new Promise((r) => servers[0].listen(PORT_STRICT, '127.0.0.1', r)),
  new Promise((r) => servers[1].listen(PORT_LENIENT, '127.0.0.1', r)),
  new Promise((r) => servers[2].listen(PORT_AUTHFAIL, '127.0.0.1', r)),
]);
console.log(`  假中转：strict=${PORT_STRICT} lenient=${PORT_LENIENT} authfail=${PORT_AUTHFAIL}`);

try {
  await withCleanData(async () => {
    const list = await (await fetch(`${CDP}/json/list`)).json();
    const page = list.find((t) => t.type === 'page');
    if (!page) throw new Error('找不到页面');

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
    const send = (method, params = {}, timeout = 300000) => new Promise((res, rej) => {
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
    await send('Page.navigate', { url: BASE + '/#/adventure' });
    await sleep(15000);

    console.log('\n########## 第三方中转 400 自动降级 ##########\n');

    const run = (port) => `
      const { generateWithNovelAICompatible } = await import('/src/engine/imageGen.ts');
      try {
        const r = await generateWithNovelAICompatible('1girl, test', 'bad hands', {
          provider: 'novelai-openai',
          baseUrl: ${JSON.stringify('http://127.0.0.1:')} + ${port},
          apiKey: 'k',
          model: 'nai-diffusion-4-5-full',
          width: 832, height: 1216,
          artistTags: '(artist:x:1.0)',
          qualityTags: 'masterpiece',
          negativePrompt: 'lowres',
          steps: 28, cfgScale: 6, sampler: 'euler',
        });
        return JSON.stringify({ 成功: true, 图长: r.dataUrl.length });
      } catch (e) {
        return JSON.stringify({ 成功: false, 错误: String(e && e.message || e).slice(0, 200) });
      }
    `;

    // ---- A. 严格中转 ----
    console.log('—— A. 严格中转（遇到 NovelAI 字段就 400）——');
    seen.strict.length = 0;
    const strict = JSON.parse(await ev(run(PORT_STRICT)));
    console.log('  结果: ' + JSON.stringify(strict));
    console.log('  收到请求数: ' + seen.strict.length);
    seen.strict.forEach((b, i) => {
      console.log(`    [${i}] 字段: ${Object.keys(b).sort().join(', ')}`);
    });

    check('★ 严格中转最终出图成功（自动降级生效）', strict.成功 === true, JSON.stringify(strict));
    check('★ 一共发了 2 次（先全字段、后纯 OpenAI）', seen.strict.length === 2, `${seen.strict.length} 次`);
    if (seen.strict.length === 2) {
      const first = seen.strict[0];
      const second = seen.strict[1];
      check('第一次确实带了 NovelAI 专有字段（这是被 400 的原因）',
        NOVELAI_KEYS.some((k) => k in first), Object.keys(first).sort().join(','));
      check('★ 降级后的请求**不含**任何 NovelAI 专有字段',
        !NOVELAI_KEYS.some((k) => k in second), Object.keys(second).sort().join(','));
      check('★ 降级后仍保留必要字段（model/prompt/n/size）',
        !!second.model && !!second.prompt && second.n === 1 && !!second.size);
      check('降级后要求返回 base64', second.response_format === 'b64_json');
    }

    // ---- B. 宽松中转：不该重试 ----
    console.log('\n—— B. 宽松中转（什么字段都收）——');
    seen.lenient.length = 0;
    const lenient = JSON.parse(await ev(run(PORT_LENIENT)));
    console.log('  结果: ' + JSON.stringify(lenient));
    console.log('  收到请求数: ' + seen.lenient.length);
    check('★ 宽松中转过一次就成功', lenient.成功 === true);
    check('★ 没有多此一举地重试（只发 1 次）', seen.lenient.length === 1, `${seen.lenient.length} 次`);

    // ---- C. 鉴权失败：不该重试 ----
    console.log('\n—— C. 鉴权失败 401（不该重试）——');
    seen.authfail.length = 0;
    const auth = JSON.parse(await ev(run(PORT_AUTHFAIL)));
    console.log('  结果: ' + JSON.stringify(auth));
    console.log('  收到请求数: ' + seen.authfail.length);
    check('★ 401 时如实报错（不假装成功）', auth.成功 === false);
    check('★ 401 不触发降级重试（换字段也解决不了）',
      seen.authfail.length === 1, `${seen.authfail.length} 次`);
    check('错误信息里能看出是鉴权问题', /401|auth|key/i.test(auth.错误 || ''), (auth.错误 || '').slice(0, 80));

    console.log(`\n  失败项: ${fails}`);
    console.log('\n  === 服务端实际收到的请求（排查用）===');
    if (!allReqs.length) {
      console.log('    （一个都没收到 —— 请求没出浏览器）');
    }
    allReqs.forEach((r, i) => {
      console.log(`    [${i}] mode=${r.mode} url=${r.url} keys=${r.keys.sort().join(',')}`);
    });
    ws.close();
    if (fails > 0) process.exitCode = 1;
  });
} finally {
  servers.forEach(function(s) { s.close(); });
}
