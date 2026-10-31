// 真机路径的**契约测试**：模拟 APK 里的原生桥接垫片 + FileBridge。
//
// 为什么必须测这个：浏览器里图片走 IndexedDB，位置参数写法看不出任何问题
// （这正是"我上次测过了"却没发现的原因）。只有把原生垫片的行为搬过来，
// 才能暴露 imageStorage.ts 曾经用位置参数调用 Capacitor 的问题。
//
// 做法：复刻 FileBridge.java 的 handle() —— 它只认一个 JSON 对象，
// 从里面读 path / data / directory。凡是把字符串当 payload 传进来的，直接抛错。
import fs from 'node:fs';

const SRC = fs.readFileSync('E:/duihuazhushou/src/lib/imageStorage.ts', 'utf8');

let fails = 0;
const check = (label, cond, extra = '') => {
  if (!cond) fails++;
  console.log(`  ${cond ? '✓' : '✗ FAIL'}  ${label}${extra ? '  ' + extra : ''}`);
};

console.log('################ 真机桥接契约测试 ################\n');

// ---- 1. 复刻 FileBridge.java 的入口：只接受 JSON 对象 ----
// 对应 Java 的 new JSONObject(payloadJson) + optString(p, "path"/"data"/"directory")
function nativeHandle(method, payloadJson) {
  let p;
  try {
    p = JSON.parse(payloadJson == null || payloadJson === '' ? '{}' : payloadJson);
  } catch (e) {
    // Java 抛的是 "Value ... of type java.lang.String cannot be converted to JSONObject"
    throw new Error('JSONObject 解析失败: ' + e.message);
  }
  // ★ 关键：顶层必须是对象。字符串/数字会让 Java 侧的类型转换炸掉。
  if (typeof p !== 'object' || p === null || Array.isArray(p)) {
    throw new Error('Value ' + JSON.stringify(p) + ' of type ' + typeof p + ' cannot be converted to JSONObject');
  }
  const path = typeof p.path === 'string' ? p.path : null;
  const directory = typeof p.directory === 'string' ? p.directory : 'DATA';
  switch (method) {
    case 'writeFile': {
      if (path == null) throw new Error('path 为空');
      const data = typeof p.data === 'string' ? p.data : '';
      return { ok: true, value: {} };
    }
    case 'readFile':
      if (path == null) throw new Error('path 为空');
      return { ok: true, value: { data: '__stored__' } };
    case 'mkdir':
      return { ok: true, value: {} };
    case 'readdir':
      return { ok: true, value: { files: [] } };
    case 'stat':
      return { ok: true, value: { size: 100 } };
    case 'deleteFile':
      if (path == null) throw new Error('path 为空');
      return { ok: true, value: {} };
    default:
      throw new Error('未知方法 ' + method);
  }
}

// ---- 2. 复刻垫片：把**第一个参数**原样 JSON.stringify 交给原生 ----
function makeShim() {
  const call = (method, opts) =>
    new Promise((resolve, reject) => {
      try {
        const out = nativeHandle(method, JSON.stringify(opts === undefined ? {} : opts));
        out.ok ? resolve(out.value) : reject(new Error(out.error));
      } catch (e) {
        reject(e);
      }
    });
  return {
    mkdir: (o) => call('mkdir', o),
    writeFile: (o) => call('writeFile', o),
    readFile: (o) => call('readFile', o),
    readdir: (o) => call('readdir', o),
    stat: (o) => call('stat', o),
    deleteFile: (o) => call('deleteFile', o),
    remove: (o) => call('deleteFile', o),
  };
}

const fs_ = makeShim();

// ---- 3. 先证明"旧写法"确实会崩（防止我误判） ----
console.log('先复现旧写法的故障（位置参数）：');
let oldFailed = false;
try {
  await fs_.writeFile('images/ia-1.txt', 'data:image/png;base64,AAAA', { directory: 'DATA' });
} catch (e) {
  oldFailed = true;
  console.log('  旧写法抛出: ' + e.message.slice(0, 96));
}
check('旧的位置参数写法在真机上确实会失败', oldFailed);

// ---- 4. 再证明"新写法"能过 ----
console.log('\n新的对象写法：');
let newOk = true;
const calls = [
  ['writeFile 存图本体', () => fs_.writeFile({ path: 'images/ia-1.txt', data: 'data:image/png;base64,AAAA', directory: 'DATA', recursive: true })],
  ['readFile 取图本体', () => fs_.readFile({ path: 'images/ia-1.txt', directory: 'DATA' })],
  ['writeFile 写索引', () => fs_.writeFile({ path: 'images/index.json', data: '[]', directory: 'DATA', recursive: true })],
  ['readFile 读索引', () => fs_.readFile({ path: 'images/index.json', directory: 'DATA' })],
  ['mkdir 建目录', () => fs_.mkdir({ path: 'images', directory: 'DATA', recursive: true })],
  ['readdir 列目录', () => fs_.readdir({ path: 'images', directory: 'DATA' })],
  ['stat 取大小', () => fs_.stat({ path: 'images/ia-1.txt', directory: 'DATA' })],
  ['deleteFile 删图', () => fs_.deleteFile({ path: 'images/ia-1.txt', directory: 'DATA' })],
];
for (const [label, fn] of calls) {
  try {
    await fn();
    console.log(`  ✓ ${label}`);
  } catch (e) {
    newOk = false;
    console.log(`  ✗ ${label} → ${e.message}`);
  }
}
check('新的对象写法 8 个调用全部通过', newOk);

// ---- 5. 静态核验：源码里不能再出现位置参数调用 ----
console.log('\n源码静态核验（src/lib/imageStorage.ts）：');
const positional = [
  /fs\.writeFile\(\s*`/,
  /fs\.readFile\(\s*`/,
  /fs\.deleteFile\(\s*`/,
  /fs\.readdir\(\s*(?!\{)/,
  /fs\.stat\(\s*`/,
  /Filesystem\.(writeFile|readFile|deleteFile|readdir|stat|mkdir)\(\s*`/,
];
let hits = 0;
for (const re of positional) if (re.test(SRC)) hits++;
check('没有残留的位置参数调用', hits === 0, hits ? `命中 ${hits} 处` : '');

// 每个 fs.xxx( 后面第一个字符应该是 {
const callSites = [...SRC.matchAll(/fs\.(writeFile|readFile|deleteFile|readdir|stat|mkdir)\(\s*([^\s)])/g)];
const bad = callSites.filter((m) => m[2] !== '{');
check('所有 fs.* 调用第一个实参都是对象', bad.length === 0, bad.length ? bad.map((b) => b[0]).join(' | ') : `${callSites.length} 处全部正确`);

// directUrl 不能返回半成品地址
check(
  'directUrl 在拿不到真实目录时返回 null（不拼坏地址）',
  /if \(!base\) return null;/.test(SRC),
);

console.log(`\n失败项: ${fails}`);
process.exitCode = fails > 0 ? 1 : 0;
