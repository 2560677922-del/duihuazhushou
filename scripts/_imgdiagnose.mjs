// 诊断：历史消息配图为什么"乱配"。
// 假设：useQuickImage 传给 LLM 的是 text.slice(-3000)，
//       长消息只剩结尾 3000 字，画面主体（人物/场景）在前面就被切掉了。
// 这里用真实长度的文本量一下，看切片到底丢了多少。
import { withCleanData } from './_testdata.mjs';

const CDP = 'http://127.0.0.1:' + (process.argv[2] || '9500');
const BASE = 'http://127.0.0.1:5173';

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
    const tm = setTimeout(() => { pend.delete(i); rej(new Error('timeout')); }, timeout);
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

  const out = await ev(`
    const { extractContext } = await import('/src/engine/imageGen.ts');

    // 造一段典型的冒险长文：开头是画面主体，结尾是对话/收束
    const head = '银发少女艾琳推开石门，剑光照亮潮湿的石壁，她穿着一件破损的深蓝色斗篷，腰间挂着一盏铜灯。';
    const middle = '她沿着通道向前走了大约二十步，空气越来越冷，远处传来水声。地上散落着断裂的石柱和干涸的血迹。'.repeat(12);
    const tail = '艾琳低声说：“这地方不对劲。”她把灯举高了一些，继续往深处走。';

    const long = head + middle + tail;
    const short = head + tail;

    // useQuickImage 的做法
    const sentLong = long.slice(-3000);
    const sentShort = short.slice(-3000);

    return JSON.stringify({
      长消息_全文长度: long.length,
      长消息_实际发给LLM: sentLong.length,
      长消息_画面主体是否还在: sentLong.includes('银发少女艾琳推开石门'),
      短消息_全文长度: short.length,
      短消息_实际发给LLM: sentShort.length,
      短消息_画面主体是否还在: sentShort.includes('银发少女艾琳推开石门'),
      长消息_发给LLM的开头: sentLong.slice(0, 60),
      // 顺便验证 extractContext 在 contextWindow=1 时的行为
      上下文窗口1的结果长度: extractContext([{ role: 'assistant', content: long.slice(-3000) }], 1).length,
    });
  `);

  console.log('########## 历史消息配图诊断 ##########\n');
  console.log(JSON.stringify(JSON.parse(out), null, 2));

  const d = JSON.parse(out);
  console.log('\n结论：');
  if (!d.长消息_画面主体是否还在 && d.短消息_画面主体是否还在) {
    console.log('  ✓ 假设成立 —— 长消息的画面主体被 slice(-3000) 切掉了，短消息不会。');
    console.log('    这就是"最新一条正常、历史消息乱配"的原因。');
  } else {
    console.log('  ✗ 假设不成立，需要另找原因');
  }

  ws.close();
  await fetch(`${CDP}/json/close/${page.id}`).catch(() => {});
});
