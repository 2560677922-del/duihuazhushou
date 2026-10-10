// 生成 PWA 图标（简单的 emoji-based PNG）
const fs = require('fs');
const path = require('path');

const PUBLIC_DIR = path.resolve(__dirname, '..', 'public');

// 创建一个简单的 SVG 图标（聊天图标）
const svgIcon = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">
  <rect width="512" height="512" rx="96" fill="#0f1115"/>
  <rect x="40" y="40" width="432" height="432" rx="80" fill="#161922"/>
  <circle cx="160" cy="200" r="32" fill="#6ea8fe"/>
  <circle cx="256" cy="200" r="32" fill="#6ea8fe"/>
  <circle cx="352" cy="200" r="32" fill="#6ea8fe"/>
  <rect x="100" y="280" width="312" height="80" rx="40" fill="#1c2030" stroke="#6ea8fe" stroke-width="4"/>
  <text x="256" y="230" font-family="Arial" font-size="48" font-weight="bold" fill="#6ea8fe" text-anchor="middle">AI</text>
</svg>`;

// 写入 SVG 图标
fs.writeFileSync(path.join(PUBLIC_DIR, 'icon.svg'), svgIcon);

// 使用 sharp 或 canvas 生成 PNG（如果没有，直接复制 SVG 作为 fallback）
try {
  const sharp = require('sharp');
  const svgBuffer = Buffer.from(svgIcon);

  async function generatePNG(size, filename) {
    await sharp(svgBuffer, { density: 300 })
      .resize(size, size)
      .png()
      .toFile(path.join(PUBLIC_DIR, filename));
  }

  (async () => {
    await generatePNG(192, 'pwa-192.png');
    await generatePNG(512, 'pwa-512.png');
    await generatePNG(180, 'apple-touch-icon.png');
    console.log('PWA icons generated successfully');
  })().catch(e => console.error('sharp not available:', e.message));
} catch (e) {
  console.log('sharp not available, using SVG fallback');
  // 复制 SVG 作为 fallback
  fs.copyFileSync(path.join(PUBLIC_DIR, 'icon.svg'), path.join(PUBLIC_DIR, 'pwa-192.png'));
  fs.copyFileSync(path.join(PUBLIC_DIR, 'icon.svg'), path.join(PUBLIC_DIR, 'pwa-512.png'));
  fs.copyFileSync(path.join(PUBLIC_DIR, 'icon.svg'), path.join(PUBLIC_DIR, 'apple-touch-icon.png'));
}
