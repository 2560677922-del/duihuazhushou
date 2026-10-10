// 生成启动图标：Android 自适应图标前景 + 各密度 PNG 兜底 + Web/PWA 图标
//
// 不依赖 sharp / canvas：自己做一个很小的光栅化器（图标只由圆和圆角矩形组成，
// 逐像素判定就够），PNG 编码也自己写（zlib 来自 node 内置）。
//
// 所有输出共用同一份设计稿（DESIGN=512 坐标空间），所以 Android 桌面、
// 浏览器标签页、PWA 安装后的图标完全一致。
//
// 设计尺寸压在 108dp 自适应图标的「安全圆」里：108dp 里安全区直径是 66dp，
// 换算到 512 的坐标空间半径就是 66/108*512 ≈ 156.7。图案全部落在这个圆内，
// 系统把它裁成圆形 / 水滴形 / 圆角方形时都不会切到主体。
//
// 用法：在 dbpg 目录下执行  node tools\make-icons.cjs
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const DESIGN = 512;  // 设计稿坐标空间
const SS = 3;        // 超采样倍数，用来做抗锯齿

const RES_DIR = path.resolve(__dirname, '..', 'app', 'src', 'main', 'res');
const PUB_DIR = path.resolve(__dirname, '..', '..', 'public');

const DENSITIES = [
  ['mdpi', 48],
  ['hdpi', 72],
  ['xhdpi', 96],
  ['xxhdpi', 144],
  ['xxxhdpi', 192],
];

// ---------------------------------------------------------------- 配色
// 与 Web 版主题色一致（见 vite.config.ts 的 theme_color）
const C = {
  bg:     [0x0f, 0x11, 0x15, 255],   // 底色
  head:   [0xed, 0xf2, 0xfb, 255],   // 头
  accent: [0x6e, 0xa8, 0xfe, 255],   // 主色：天线、嘴
  eye:    [0x14, 0x18, 0x26, 255],   // 眼睛
  shine:  [0xff, 0xff, 0xff, 255],   // 眼神光
  cheek:  [0xff, 0x9d, 0xb8, 255],   // 腮红
};

// ---------------------------------------------------------------- 设计稿
// 小机器人。按「从后往前」的顺序排列：sample() 反过来查，最上层先命中。
//
//   天线球 ── 天线杆 ── 圆头 ── 两只眼 ── 眼神光 ── 腮红 ── 嘴
//
// 整体占位：y 102~410，x 132~380，中心 (256, 256)，四周都留了余量。
const SHAPES = [
  { t: 'circle', cx: 256, cy: 122, r: 20,        c: C.accent }, // 天线球
  { t: 'rrect',  x: 248,  y: 136, w: 16, h: 40, r: 8,  c: C.accent }, // 天线杆
  { t: 'circle', cx: 256, cy: 286, r: 124,       c: C.head },   // 头
  { t: 'circle', cx: 204, cy: 272, r: 36,        c: C.eye },    // 左眼
  { t: 'circle', cx: 308, cy: 272, r: 36,        c: C.eye },    // 右眼
  { t: 'circle', cx: 218, cy: 258, r: 13,        c: C.shine },  // 左眼神光
  { t: 'circle', cx: 322, cy: 258, r: 13,        c: C.shine },  // 右眼神光
  { t: 'circle', cx: 176, cy: 322, r: 22,        c: C.cheek },  // 左腮红
  { t: 'circle', cx: 336, cy: 322, r: 22,        c: C.cheek },  // 右腮红
  { t: 'rrect',  x: 222,  y: 330, w: 68, h: 20, r: 10, c: C.accent }, // 嘴
];

// ---------------------------------------------------------------- 几何判定
function inRRect(x, y, rx, ry, w, h, r) {
  if (x < rx || x > rx + w || y < ry || y > ry + h) return false;
  const cx = Math.max(rx + r, Math.min(x, rx + w - r));
  const cy = Math.max(ry + r, Math.min(y, ry + h - r));
  const dx = x - cx;
  const dy = y - cy;
  return dx * dx + dy * dy <= r * r;
}

function inCircle(x, y, cx, cy, r) {
  const dx = x - cx;
  const dy = y - cy;
  return dx * dx + dy * dy <= r * r;
}

// 命中上层优先；都不命中就落到 bgRadius 定义的底块里，再往外就是透明。
// bgRadius 是设计稿坐标下的底块圆角（0 = 正方形）。
function sample(x, y, k, bgRadiusK) {
  for (let i = SHAPES.length - 1; i >= 0; i--) {
    const s = SHAPES[i];
    if (s.t === 'circle') {
      if (inCircle(x, y, s.cx * k, s.cy * k, s.r * k)) return s.c;
    } else if (inRRect(x, y, s.x * k, s.y * k, s.w * k, s.h * k, s.r * k)) {
      return s.c;
    }
  }
  if (bgRadiusK > 0) {
    if (inRRect(x, y, 0, 0, DESIGN * k, DESIGN * k, bgRadiusK)) return C.bg;
  } else {
    return C.bg;
  }
  return null;
}

// ---------------------------------------------------------------- 渲染
function render(size, opts) {
  const { bgRadius = 0, circleMask = false } = opts || {};
  const S = size * SS;
  const k = S / DESIGN;
  const bgK = bgRadius * k;
  const buf = Buffer.alloc(S * S * 4);

  for (let py = 0; py < S; py++) {
    const y = py + 0.5;
    for (let px = 0; px < S; px++) {
      const c = sample(px + 0.5, y, k, bgK);
      const i = (py * S + px) * 4;
      buf[i] = c ? c[0] : 0;
      buf[i + 1] = c ? c[1] : 0;
      buf[i + 2] = c ? c[2] : 0;
      buf[i + 3] = c ? c[3] : 0;
    }
  }

  const out = downsample(buf, S, size);
  return circleMask ? applyCircleMask(out, size) : out;
}

// SS×SS 块平均，得到抗锯齿效果
function downsample(buf, S, size) {
  const out = Buffer.alloc(size * size * 4);
  const n = SS * SS;
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      let r = 0, g = 0, b = 0, a = 0;
      for (let j = 0; j < SS; j++) {
        for (let i = 0; i < SS; i++) {
          const p = ((y * SS + j) * S + (x * SS + i)) * 4;
          r += buf[p];
          g += buf[p + 1];
          b += buf[p + 2];
          a += buf[p + 3];
        }
      }
      const o = (y * size + x) * 4;
      out[o] = Math.round(r / n);
      out[o + 1] = Math.round(g / n);
      out[o + 2] = Math.round(b / n);
      out[o + 3] = Math.round(a / n);
    }
  }
  return out;
}

// 圆形遮罩，边缘留 1px 抗锯齿
function applyCircleMask(buf, size) {
  const R = size * 0.5;
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const dx = x + 0.5 - R;
      const dy = y + 0.5 - R;
      const d = Math.sqrt(dx * dx + dy * dy);
      const cov = d >= R - 0.5 ? 1 : Math.max(0, R - d - 0.5) / 0.5;
      if (cov >= 1) continue;
      const i = (y * size + x) * 4;
      buf[i] = Math.round(buf[i] * cov);
      buf[i + 1] = Math.round(buf[i + 1] * cov);
      buf[i + 2] = Math.round(buf[i + 2] * cov);
      buf[i + 3] = Math.round(buf[i + 3] * cov);
    }
  }
  return buf;
}

// ---------------------------------------------------------------- PNG 编码
const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let b = 0; b < 8; b++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length >>> 0);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

function encodePng(size, rgba) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8;   // 位深
  ihdr[9] = 6;   // 色彩类型：RGBA
  ihdr[10] = 0;
  ihdr[11] = 0;
  ihdr[12] = 0;

  // 每行前面加一个 filter 字节（这里统一用 0 = None）
  const stride = size * 4;
  const raw = Buffer.alloc((stride + 1) * size);
  for (let y = 0; y < size; y++) {
    raw[y * (stride + 1)] = 0;
    rgba.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  }

  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// ---------------------------------------------------------------- 矢量输出
const hex6 = ([r, g, b]) =>
  '#' + [r, g, b].map((v) => v.toString(16).padStart(2, '0')).join('').toUpperCase();
const hex8 = ([r, g, b, a]) =>
  '#' + [a, r, g, b].map((v) => v.toString(16).padStart(2, '0')).join('').toUpperCase();
const f = (v) => {
  const r = Math.round(v * 100) / 100;
  return String(r);
};

function circlePath(cx, cy, r) {
  return `M${f(cx - r)},${f(cy)} A${f(r)},${f(r)} 0 1 0 ${f(cx + r)},${f(cy)} ` +
    `A${f(r)},${f(r)} 0 1 0 ${f(cx - r)},${f(cy)} Z`;
}

function rrectPath(x, y, w, h, r) {
  const rr = Math.min(r, w / 2, h / 2);
  return [
    `M${f(x + rr)},${f(y)}`,
    `H${f(x + w - rr)}`,
    `A${f(rr)},${f(rr)} 0 0 1 ${f(x + w)},${f(y + rr)}`,
    `V${f(y + h - rr)}`,
    `A${f(rr)},${f(rr)} 0 0 1 ${f(x + w - rr)},${f(y + h)}`,
    `H${f(x + rr)}`,
    `A${f(rr)},${f(rr)} 0 0 1 ${f(x)},${f(y + h - rr)}`,
    `V${f(y + rr)}`,
    `A${f(rr)},${f(rr)} 0 0 1 ${f(x + rr)},${f(y)}`,
    'Z',
  ].join(' ');
}

// 把同色的形状合并成一个路径（眼睛、腮红、眼神光各是一对）
function groupedShapes() {
  const out = [];
  for (const s of SHAPES) {
    const last = out[out.length - 1];
    if (last && last.c === s.c && s.t === 'circle' && last.t === 'circle') {
      last.items.push(s);
    } else {
      out.push({ t: s.t, c: s.c, items: [s] });
    }
  }
  return out;
}

const circleOnly = (g) => g.items.every((s) => s.t === 'circle');

function pathData(g) {
  if (g.t === 'circle' && circleOnly(g)) {
    return g.items.map((s) => circlePath(s.cx, s.cy, s.r)).join(' ');
  }
  const s = g.items[0];
  return s.t === 'circle' ? circlePath(s.cx, s.cy, s.r) : rrectPath(s.x, s.y, s.w, s.h, s.r);
}

// 自适应图标的前景层：108dp，坐标系用 512，这样和光栅化器共用一份设计稿。
function writeVectorXml(outPath) {
  const ns = 'http://schemas.android.com/apk/res/android';
  const lines = [
    '<?xml version="1.0" encoding="utf-8"?>',
    '<!-- 自适应图标的前景层。Android 8.0+ 用；系统会把它裁成圆形/水滴形等形状。 -->',
    '<!-- width/height 是 108dp，但 viewport 用 512，好和 tools/make-icons.cjs 里的',
    '     设计稿共用同一套坐标。图案全部落在 108dp 的「安全圆」(直径 66dp) 内，',
    '     换算到 512 是半径 156.7，所以怎么裁都不会切到主体。 -->',
    `<vector xmlns:android="${ns}"`,
    '    android:width="108dp"',
    '    android:height="108dp"',
    `    android:viewportWidth="${DESIGN}"`,
    `    android:viewportHeight="${DESIGN}">`,
    '',
  ];
  for (const g of groupedShapes()) {
    lines.push('    <path');
    lines.push(`        android:fillColor="${hex6(g.c)}"`);
    lines.push(`        android:pathData="${pathData(g)}" />`);
    lines.push('');
  }
  lines.push('</vector>');
  fs.writeFileSync(outPath, lines.join('\n') + '\n');
  console.log(`  drawable/ic_launcher_foreground.xml  ${lines.length} 行`);
}

// Web 用 SVG：和 Android 矢量版同一个设计，多一块圆角底色
function writeSvg(outPath) {
  const R = 96;
  const g = groupedShapes();
  const parts = [
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${DESIGN} ${DESIGN}">`,
    `  <rect width="${DESIGN}" height="${DESIGN}" rx="${R}" fill="${hex6(C.bg)}"/>`,
  ];
  for (const grp of g) {
    const fill = `fill="${hex6(grp.c)}"`;
    const pd = pathData(grp);
    parts.push(`  <path d="${pd}" ${fill}/>`);
  }
  parts.push('</svg>');
  fs.writeFileSync(outPath, parts.join('\n') + '\n');
  console.log(`  public/icon.svg  ${fs.statSync(outPath).size}B`);
}

// ---------------------------------------------------------------- 主流程
let total = 0;

// 1) Android 自适应图标前景（矢量）
writeVectorXml(path.join(RES_DIR, 'drawable', 'ic_launcher_foreground.xml'));

// 2) Android 旧版兜底 PNG（7.0~7.1；8.0+ 走上面的 XML）
//    方形版铺满实心底（旧启动器会自己再裁形状），圆形版直接按圆裁 alpha
for (const [dpi, size] of DENSITIES) {
  const dir = path.join(RES_DIR, `mipmap-${dpi}`);
  fs.mkdirSync(dir, { recursive: true });

  const square = encodePng(size, render(size, {}));
  const round = encodePng(size, render(size, { circleMask: true }));
  fs.writeFileSync(path.join(dir, 'ic_launcher.png'), square);
  fs.writeFileSync(path.join(dir, 'ic_launcher_round.png'), round);
  total += 2;
  console.log(`  mipmap-${dpi.padEnd(7)} ${String(size).padStart(3)}px  ` +
    `ic_launcher.png ${square.length}B   ic_launcher_round.png ${round.length}B`);
}

// 3) Web / PWA 图标
fs.mkdirSync(PUB_DIR, { recursive: true });
writeSvg(path.join(PUB_DIR, 'icon.svg'));

const pwa192 = encodePng(192, render(192, { bgRadius: 96 }));
const pwa512 = encodePng(512, render(512, { bgRadius: 96 }));
const apple = encodePng(180, render(180, {}));   // iOS 自己会裁圆角，给实底方块
fs.writeFileSync(path.join(PUB_DIR, 'pwa-192.png'), pwa192);
fs.writeFileSync(path.join(PUB_DIR, 'pwa-512.png'), pwa512);
fs.writeFileSync(path.join(PUB_DIR, 'apple-touch-icon.png'), apple);
total += 3;
console.log(`  public/pwa-192.png ${pwa192.length}B   pwa-512.png ${pwa512.length}B   ` +
  `apple-touch-icon.png ${apple.length}B`);

console.log(`完成：生成 ${total} 个图标文件（+2 个矢量/SVG）`);
