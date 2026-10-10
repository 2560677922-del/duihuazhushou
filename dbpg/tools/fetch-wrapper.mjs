// 拉取 gradle-wrapper.jar 并校验（PK 魔数 + zip 目录里应含 GradleWrapperMain）
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const outDir = resolve(dirname(fileURLToPath(import.meta.url)), '..', 'gradle', 'wrapper') + '/';
mkdirSync(outDir, { recursive: true });

const candidates = [
  'https://gitee.com/mirrors/gradle/raw/master/gradle/wrapper/gradle-wrapper.jar',
  'https://gitee.com/mirrors/gradle/raw/v8.7/gradle/wrapper/gradle-wrapper.jar',
];

for (const u of candidates) {
  try {
    const r = await fetch(u, { signal: AbortSignal.timeout(60000) });
    console.log(`${u} -> ${r.status} type=${r.headers.get('content-type')} len=${r.headers.get('content-length')}`);
    if (!r.ok) continue;
    const buf = Buffer.from(await r.arrayBuffer());
    const isZip = buf.length > 4 && buf[0] === 0x50 && buf[1] === 0x4b;
    console.log(`  bytes=${buf.length} zipMagic=${isZip}`);
    if (!isZip) continue;
    // 扫 central directory 找 GradleWrapperMain
    let found = [];
    for (let i = buf.length - 22; i > Math.max(0, buf.length - 500000); i--) {
      if (buf.readUInt32LE(i) === 0x06054b50) {
        const count = buf.readUInt16LE(i + 10);
        const start = buf.readUInt32LE(i + 16);
        let p = start;
        for (let n = 0; n < count; n++) {
          if (buf.readUInt32LE(p) !== 0x02014b50) break;
          const nmLen = buf.readUInt16LE(p + 28);
          const exLen = buf.readUInt16LE(p + 30);
          const cmLen = buf.readUInt16LE(p + 32);
          const name = buf.toString('utf8', p + 46, p + 46 + nmLen);
          if (name.includes('Wrapper')) found.push(`${name} (${buf.readUInt32LE(p + 20)}B)`);
          p += 46 + nmLen + exLen + cmLen;
        }
        break;
      }
    }
    console.log('  wrapper classes:', found.slice(0, 8).join('\n               '));
    if (found.some((f) => f.includes('GradleWrapperMain.class'))) {
      writeFileSync(outDir + 'gradle-wrapper.jar', buf);
      console.log(`OK 已写出 ${outDir}gradle-wrapper.jar (${buf.length} B)`);
      break;
    }
  } catch (e) {
    console.log(`${u} ERR ${String(e).slice(0, 100)}`);
  }
}
