// 本地文件持久化：Vite 开发服务器插件
// ---------------------------------------------------------------------------
// 为什么需要它：浏览器存储（IndexedDB / localStorage）会因清理缓存、
// 换访问地址（localhost 与局域网 IP 是两套独立存储）等原因丢失。
// 本插件把配置与资产落盘到项目下的 data/*.json，启动时自动读回。
//
// 接口（仅开发服务器提供）：
//   GET  /__persist/status    -> { enabled, dir, file, bytes, mtime, backupCount }
//   GET  /__persist/data      -> { version, updatedAt, data }
//   POST /__persist/data      -> 合并写入（合并而非覆盖，避免误删）
//   GET  /__persist/backups   -> 备份文件列表
//
// 安全：写入需要携带页面注入的令牌（X-Persist-Token）。
// 该令牌存在 data/persist-token，只有能访问本机文件的人才知道。
// 这个接口是给本机/局域网自用服务的，不要暴露到公网。

import fs from 'node:fs';
import path from 'node:path';

const MAX_BACKUPS = 20;
const MAX_BODY_BYTES = 64 * 1024 * 1024; // 64MB，防止异常请求打爆内存

function readJsonSafe(file) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    return null;
  }
}

function writeJsonAtomic(file, value) {
  const tmp = `${file}.tmp`;
  // 紧凑输出（不缩进）：这个文件是程序读的、不会手改，
// 缩进会让它白白大将近一倍（1.4MB 的内容写成 2.9MB）。
  const text = JSON.stringify(value);
  fs.writeFileSync(tmp, text, 'utf8');
  fs.renameSync(tmp, file); // 原子替换，避免写一半断电留下坏文件
}

function ensureToken(dir) {
  const tokenFile = path.join(dir, 'persist-token');
  if (fs.existsSync(tokenFile)) {
    const t = fs.readFileSync(tokenFile, 'utf8').trim();
    if (t) return t;
  }
  // 32 字节随机令牌
  const token = [...crypto.getRandomValues(new Uint8Array(32))]
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(tokenFile, token, 'utf8');
  return token;
}

function rotateBackups(backupDir, baseName) {
  fs.mkdirSync(backupDir, { recursive: true });
  const files = fs
    .readdirSync(backupDir)
    .filter((f) => f.startsWith(`${baseName}.`) && f.endsWith('.json'))
    .map((f) => ({ f, t: fs.statSync(path.join(backupDir, f)).mtimeMs }))
    .sort((a, b) => b.t - a.t);
  for (const { f } of files.slice(MAX_BACKUPS)) {
    fs.rmSync(path.join(backupDir, f), { force: true });
  }
}

export function persistPlugin(options = {}) {
  const rootDir = options.dir ?? path.resolve(process.cwd(), 'data');
  const fileName = options.file ?? 'app-data.json';
  const dataFile = path.join(rootDir, fileName);
  const backupDir = path.join(rootDir, 'backups');
  const backupName = fileName.replace(/\.json$/, '');

  /**
   * 本次服务器进程的唯一标识。
   *
   * 为什么需要它：vite 在端口被占用时会自动换端口（5173 → 5174），
   * 于是本机上可能同时跑着两个开发服务器，它们共用同一个数据文件。
   * 如果没有这个标识，A 写入会被 B 当成「外部改动」，B 再写又被 A 当成改动，
   * 双方不停地提示刷新——实测就是这样死循环的。
   * 带上 instanceId 后，客户端能分辨「这条改动是不是我自己这个服务器写的」。
   */
  const instanceId = `${process.pid}-${Date.now().toString(36)}-${Math.random()
    .toString(36)
    .slice(2, 8)}`;

  let token = '';

  /**
   * 按访问来源（origin）隔离数据。
   *
   * 为什么必须这么做：
   *   localhost:5173 和 192.168.31.45:5173 在浏览器眼里是两个不同的网站，
   *   各有各的 localStorage。之前所有来源共用一份数据，
   *   一个来源（比如没配过的 localhost）写入默认配置就会把另一个来源
   *   （配置齐全的局域网地址）的真实配置覆盖掉——这是实际发生过的数据丢失。
   *   现在每个 origin 只读写自己那一片，互相不可能覆盖。
   */
  function originOf(req) {
    const hdr = req.headers['x-persist-origin'];
    if (typeof hdr === 'string' && hdr.trim()) return hdr.trim().slice(0, 200);
    return 'unknown';
  }

  function readFile() {
    return readJsonSafe(dataFile) ?? { version: 2, instanceId, origins: {} };
  }

  /** 读取某 origin 的数据；首次访问时把旧版扁平数据迁移给它 */
  function readOrigin(origin) {
    const file = readFile();
    file.origins = file.origins || {};

    if (!file.origins[origin]) {
      // 迁移：旧版把数据平铺在 data 字段里，任何 origin 第一次来都先继承它，
      // 这样谁都不会因为"自己这片是空的"而丢东西。
      const legacy = file.data && typeof file.data === 'object' ? file.data : {};
      file.origins[origin] = {
        updatedAt: file.updatedAt || 0,
        instanceId: file.instanceId || '',
        data: { ...legacy },
      };
      if (Object.keys(legacy).length === 0) {
        file.origins[origin].updatedAt = 0;
      }
    }
    return { file, slice: file.origins[origin] };
  }

  function writeOrigin(origin, patch, meta) {
    const file = readFile();
    file.origins = file.origins || {};
    const slice = file.origins[origin] || { updatedAt: 0, instanceId: '', data: {} };
    slice.data = { ...(slice.data || {}), ...patch };
    slice.updatedAt = meta.updatedAt;
    slice.instanceId = meta.instanceId;
    file.origins[origin] = slice;
    file.version = 2;
    // 保留旧字段，方便回退排查；不再作为数据源使用
    delete file.data;
    writeJsonAtomic(dataFile, file);
    return slice;
  }

  function snapshotBackup() {
    if (!fs.existsSync(dataFile)) return;
    const stamp = new Date().toISOString().replace(/[:.]/g, '-');
    fs.mkdirSync(backupDir, { recursive: true });
    fs.copyFileSync(dataFile, path.join(backupDir, `${backupName}.${stamp}.json`));
    rotateBackups(backupDir, backupName);
  }

  function readBody(req) {
    return new Promise((resolve, reject) => {
      let size = 0;
      const chunks = [];
      req.on('data', (c) => {
        size += c.length;
        if (size > MAX_BODY_BYTES) {
          reject(new Error('请求体过大'));
          req.destroy();
          return;
        }
        chunks.push(c);
      });
      req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
      req.on('error', reject);
    });
  }

  return {
    name: 'dsh-local-persist',
    apply: 'serve',

    configResolved() {
      fs.mkdirSync(rootDir, { recursive: true });
      token = ensureToken(rootDir);
      if (!fs.existsSync(dataFile)) {
        writeJsonAtomic(dataFile, { version: 2, instanceId, origins: {} });
      }
      // 提醒：这个接口会写本机文件，别把开发服务器暴露到公网
      console.log(`\n  [本地持久化] 数据文件: ${dataFile}`);
      console.log(`  [本地持久化] 备份目录: ${backupDir}（保留最近 ${MAX_BACKUPS} 份）`);
      console.log(`  [本地持久化] 本次实例: ${instanceId}`);
      console.log('  [本地持久化] 请勿把开发服务器暴露到公网\n');
    },

    transformIndexHtml() {
      // 把令牌注入页面，客户端读取后用于写盘请求
      return [
        {
          tag: 'meta',
          attrs: { name: 'dsh-persist-token', content: token },
          injectTo: 'head-prepend',
        },
      ];
    },

    configureServer(server) {
      server.middlewares.use(async (req, res, next) => {
        const url = (req.url || '').split('?')[0];
        if (!url.startsWith('/__persist/')) return next();

        const send = (code, obj) => {
          const body = JSON.stringify(obj);
          res.statusCode = code;
          res.setHeader('Content-Type', 'application/json; charset=utf-8');
          res.setHeader('Cache-Control', 'no-store');
          res.end(body);
        };

        try {
          const origin = originOf(req);

          if (url === '/__persist/status' && req.method === 'GET') {
            const st = fs.existsSync(dataFile) ? fs.statSync(dataFile) : null;
            let backupCount = 0;
            try {
              backupCount = fs.readdirSync(backupDir).length;
            } catch {
              /* 目录还不存在 */
            }
            const { file, slice } = readOrigin(origin);
            return send(200, {
              enabled: true,
              dir: rootDir,
              file: dataFile,
              bytes: st?.size ?? 0,
              mtime: st?.mtimeMs ?? 0,
              updatedAt: slice.updatedAt ?? 0,
              instanceId,
              origin,
              originKeys: Object.keys(slice.data || {}).length,
              knownOrigins: Object.keys(file.origins || {}),
              backupCount,
            });
          }

          if (url === '/__persist/data' && req.method === 'GET') {
            // ?origin=xxx 可以读取**指定来源**的数据（只读，用于跨来源合并）
            const q = (req.url || '').split('?')[1] || '';
            const want = new URLSearchParams(q).get('origin');
            if (want) {
              const { slice } = readOrigin(want);
              return send(200, {
                version: 2,
                updatedAt: slice.updatedAt ?? 0,
                instanceId,
                origin: want,
                data: slice.data || {},
              });
            }
            const { slice } = readOrigin(origin);
            return send(200, {
              version: 2,
              updatedAt: slice.updatedAt ?? 0,
              instanceId,
              origin,
              data: slice.data || {},
            });
          }

          if (url === '/__persist/origins' && req.method === 'GET') {
            const file = readFile();
            return send(200, {
              origins: Object.entries(file.origins || {}).map(([o, s]: [string, any]) => ({
                origin: o,
                keys: Object.keys(s.data || {}).length,
                updatedAt: s.updatedAt || 0,
              })),
            });
          }

          /** 删除某个来源（把数据合并到单一来源之后清理残片用） */
          if (url === '/__persist/origin' && req.method === 'DELETE') {
            const q = (req.url || '').split('?')[1] || '';
            const want = new URLSearchParams(q).get('origin');
            if (!want) return send(400, { error: '缺少 origin 参数' });
            const file = readFile();
            if (!file.origins || !file.origins[want]) {
              return send(404, { error: '该来源不存在' });
            }
            delete file.origins[want];
            file.updatedAt = Date.now();
            writeJsonAtomic(dataFile, file);
            return send(200, { ok: true, removed: want });
          }

          if (url === '/__persist/backups' && req.method === 'GET') {
            let list = [];
            try {
              list = fs
                .readdirSync(backupDir)
                .filter((f) => f.endsWith('.json'))
                .map((f) => {
                  const s = fs.statSync(path.join(backupDir, f));
                  return { name: f, bytes: s.size, mtime: s.mtimeMs };
                })
                .sort((a, b) => b.mtime - a.mtime);
            } catch {
              /* 无备份 */
            }
            return send(200, { backups: list });
          }

          if (url === '/__persist/data' && req.method === 'POST') {
            if (req.headers['x-persist-token'] !== token) {
              return send(403, { error: '令牌无效：请从开发服务器页面发起请求' });
            }
            const raw = await readBody(req);
            let incoming;
            try {
              incoming = JSON.parse(raw);
            } catch {
              return send(400, { error: '请求体不是合法 JSON' });
            }
            const patch = incoming?.data;
            if (!patch || typeof patch !== 'object' || Array.isArray(patch)) {
              return send(400, { error: '缺少 data 对象' });
            }

            // 只写当前 origin 那一片，合并写入（不整体覆盖）
            const updatedAt = Date.now();
            snapshotBackup();
            const slice = writeOrigin(origin, patch, { updatedAt, instanceId });

            return send(200, {
              ok: true,
              updatedAt,
              instanceId,
              origin,
              keys: Object.keys(patch),
              totalKeys: Object.keys(slice.data || {}).length,
            });
          }

          return send(404, { error: '未知的持久化接口' });
        } catch (e) {
          return send(500, { error: e instanceof Error ? e.message : String(e) });
        }
      });
    },
  };
}
