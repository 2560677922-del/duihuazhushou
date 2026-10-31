// 统一的 HTTP 请求出口：能走原生就走原生，否则退回普通 fetch。
//
// 为什么需要这一层（用户报的问题）：
//   "本地部署的模型，无法通过软件连接；但 RikkaHub 就能连上。"
//
//   根因是**浏览器的同源策略 / CORS**，不是网络不通：
//   自建的局域网出图服务（FastAPI + ComfyUI 那类）通常
//     · 不发 Access-Control-Allow-Origin
//     · 不实现 OPTIONS 预检（实测直接 405）
//   于是网页里的 fetch 必然报 "Failed to fetch"。
//   而 127.0.0.1 / localhost 在 Chrome 眼里是"可信来源"，
//   本地跑的服务若带了宽松 CORS 就碰巧能用 —— 这正好对上
//   "本地的没问题、热点里那台连不上、第三方 https 平台也正常"。
//
//   原生 App（RikkaHub）没有"源"这个概念，所以它从来不受这个限制。
//
// 解法：
//   APK 里走 FileBridge 的 httpPost/httpGet（MainActivity 注入的 window.dhp），
//   由 Java 的 HttpURLConnection 真正发请求，既不经过 CORS，
//   也不受"https 页面不能请求 http"的混合内容限制。
//   浏览器（开发/桌面版）没有这个桥，就照旧用 fetch —— 
//   那种情况需要用户的服务自己开 CORS，或经由开发服务器代理。

/** 桥的响应形状（与 FileBridge.okValue 对应） */
interface BridgeResult {
  ok: boolean;
  error?: string;
  value?: { status: number; body: string };
}

/**
 * 原生桥的形状。
 *
 * `api` 是**同步**的（文件操作、以及老版本）——
 * 出图这种几十秒的请求走它会堵死 JS 线程、整页卡死。
 * `httpAsync` 是异步的，出图必须走它。
 * 老版本 APK 没有 httpAsync，所以标成可选（走不了就退回同步版）。
 */
interface DhpBridge {
  api: (m: string, p: string) => string;
  httpAsync?: (
    reqId: string,
    method: string,
    url: string,
    headersJson: string,
    body: string,
    timeoutMs: number,
  ) => void;
}

/** 拿到原生桥（没有就是浏览器环境） */
function bridge(): DhpBridge | null {
  const dhp = (globalThis as any).dhp;
  if (dhp && typeof dhp.api === 'function') return dhp as DhpBridge;
  return null;
}

/** 当前环境是否能走原生 HTTP */
export function hasNativeHttp(): boolean {
  return bridge() !== null;
}

/* ------------------------------------------------------------------ *
 * 异步原生请求
 *
 * 为什么必须有这条路（用户报的"本地部署生图在标签页会卡死"）：
 *   `@JavascriptInterface` 的方法是**同步**的 —— Java 返回之前，
 *   WebView 的 JS 线程一直被占着。文件操作几十毫秒看不出来，
 *   但出图要 20~40 秒：这期间界面完全停摆（转圈不动、按钮点不了）。
 *   第三方平台走普通 fetch，天生异步，所以从来不卡。
 *
 *   新的原生方法 `httpAsync(reqId, ...)` 立刻返回，
 *   真正的请求在 Java 线程池里跑，跑完调
 *   `window.__dhpHttpDone(id, ok, status, body)` 回填。
 *   这里把它包成 Promise，调用方照常 await。
 * ------------------------------------------------------------------ */

/** 等待中的请求：id → 兑现回调 */
const pendingAsync = new Map<string, (r: { ok: boolean; status: number; body: string }) => void>();
let asyncSeq = 0;

/** 挂上原生的回调入口（只挂一次） */
function ensureAsyncHook(): void {
  const g = globalThis as any;
  if (g.__dhpHttpDone) return;
  g.__dhpHttpDone = (reqId: string, ok: boolean, status: number, body: string) => {
    const done = pendingAsync.get(reqId);
    if (done) {
      pendingAsync.delete(reqId);
      done({ ok, status, body });
    }
  };
}

/** 端上是否具备**异步**通道（老版本 APK 只有同步的 api()） */
export function hasNativeAsyncHttp(): boolean {
  const b = bridge();
  return !!b && typeof b.httpAsync === 'function';
}

/** 走异步原生桥发请求 */
function nativeAsync(
  method: string,
  url: string,
  headers: Record<string, string>,
  body: string | undefined,
  timeoutMs: number,
): Promise<{ ok: boolean; status: number; body: string }> {
  const b = bridge();
  // 先取成局部变量：TS 在下面的闭包里会丢掉收窄，直接写 b.httpAsync 会报可能为 undefined
  const post = b?.httpAsync;
  if (!post) {
    return Promise.reject(new Error('原生异步通道不可用'));
  }
  ensureAsyncHook();
  const reqId = `r${++asyncSeq}_${Date.now()}`;
  return new Promise((resolve, reject) => {
    /*
     * 兜底超时：原生那边自己有连接/读取超时，正常会先完成。
     * 这里多留 15 秒，只为防"回调因 WebView 重建而永远不来"
     * 导致 Promise 悬挂 —— 那会让 loading 永久转圈。
     */
    const guard = setTimeout(() => {
      if (pendingAsync.delete(reqId)) reject(new Error('原生请求超时（没有收到回调）'));
    }, timeoutMs + 15000);

    pendingAsync.set(reqId, (r) => {
      clearTimeout(guard);
      resolve(r);
    });
    try {
      // 用 call 绑定回去，别丢掉 this（Java 对象作为 receiver 更稳妥）
      post.call(b, reqId, method, url, JSON.stringify(headers), body ?? '', timeoutMs);
    } catch (e) {
      clearTimeout(guard);
      pendingAsync.delete(reqId);
      reject(new Error(`原生请求桥调用失败：${e instanceof Error ? e.message : String(e)}`));
    }
  });
}

export interface HttpResult {
  status: number;
  /** 原始响应文本 */
  body: string;
  ok: boolean;
  /** 走的是哪条路，便于报错时说明 */
  via: 'native' | 'fetch';
}

/**
 * 发一个请求。
 *
 * 返回 Response 风格的轻量对象（不包装成真正的 Response —— 调用方
 * 大多只关心 status 和 text/json）。这里刻意**不抛异常**表示 HTTP 错误码，
 * 4xx/5xx 也照常返回，由调用方按业务判断，和 fetch 的行为一致。
 */
export async function httpRequest(
  url: string,
  init: {
    method?: string;
    headers?: Record<string, string>;
    body?: string;
    /** 读超时，出图要等很久，默认 3 分钟 */
    timeoutMs?: number;
  } = {},
): Promise<HttpResult> {
  const method = (init.method || 'GET').toUpperCase();
  const timeoutMs = init.timeoutMs ?? 180000;
  const b = bridge();

  /*
   * ★ 端上优先走**异步**通道。
   *
   * 以前只有同步的 api()：出图二三十秒里 JS 线程被 Java 堵死，
   * 界面完全卡住（用户报的"标签页生图时直接卡住"）。
   * 现在只要 APK 里带了 httpAsync 就走它，走不了才退回同步版
   * （老包兼容，功能仍然正确，只是会卡）。
   */
  if (b && typeof b.httpAsync === 'function') {
    const r = await nativeAsync(method, url, init.headers || {}, init.body, timeoutMs);
    return {
      status: r.status,
      body: r.body,
      ok: r.status >= 200 && r.status < 300,
      via: 'native',
    };
  }

  if (b) {
    let r: BridgeResult;
    try {
      r = JSON.parse(
        b.api(method === 'GET' ? 'httpGet' : 'httpPost', JSON.stringify({
          url,
          headers: init.headers || {},
          body: init.body ?? null,
          timeoutMs,
        })),
      );
    } catch (e) {
      throw new Error(`原生请求桥调用失败：${e instanceof Error ? e.message : String(e)}`);
    }
    if (!r.ok) throw new Error(r.error || '原生请求失败');
    const status = r.value?.status ?? 0;
    return { status, body: r.value?.body ?? '', ok: status >= 200 && status < 300, via: 'native' };
  }

  // ---- 浏览器：普通 fetch ----
  const ctrl = new AbortController();
  const timer = setTimeout(
    () => ctrl.abort(),
    // 浏览器 fetch 没有独立的读超时，用整体超时代替
    timeoutMs,
  );
  try {
    const resp = await fetch(url, {
      method,
      headers: init.headers,
      body: init.body,
      signal: ctrl.signal,
    });
    const body = await resp.text();
    return { status: resp.status, body, ok: resp.ok, via: 'fetch' };
  } catch (e) {
    /*
     * 把最容易被误判成"网络坏了"的那一种单独说清楚。
     *
     * fetch 在 CORS 被拒时只抛一个 TypeError("Failed to fetch")，
     * 消息里完全看不出是跨域问题，用户会以为是地址填错或服务没开。
     */
    const msg = e instanceof Error ? e.message : String(e);
    if (/failed to fetch|networkerror|load failed/i.test(msg)) {
      throw new Error(
        `连不上 ${url}（${msg}）。\n` +
          '常见原因：① 地址/端口不对；② 那台机器防火墙没放行；' +
          '③ 它是自建服务、没有开启跨域（CORS）—— 浏览器会直接拦下，' +
          '这种情况请用手机 App 连接，App 走原生请求不受此限制。',
      );
    }
    throw e;
  } finally {
    clearTimeout(timer);
  }
}
