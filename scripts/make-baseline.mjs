// 建立/更新测试数据基线
//
// ⚠️ 只在**确认当前数据是干净的**时候运行！
//    这个脚本会把 data/app-data.json 原样快照成 data/.clean-baseline.json，
//    之后所有测试都从基线恢复。如果在一个已被污染的状态下运行，
//    基线就被污染了 —— 以后每次测试都会"还原"成脏数据。
//
// 判断数据是否干净：模型名/预设/世界书启用数是不是你预期的值。
// 正常使用流程：跑这个脚本一次 → 之后跑 _verify.mjs 都会自动还原到此刻的状态。
import fs from 'node:fs';
import { snapshotBaseline, hasBaseline, BASELINE } from './_testdata.mjs';

snapshotBaseline();
console.log('  基线已建立/更新: ' + BASELINE);
console.log('  大小: ' + Math.round(fs.statSync(BASELINE).size / 1024) + 'KB');

const j = JSON.parse(fs.readFileSync(BASELINE, 'utf8'));
const d = j.origins['192.168.31.45:5173'].data;
const m = (d.apiConfigs || []).find((c) => c._uid === d.currentApiConfigId);
const preset = (d.presets || []).find((x) => x._uid === d.currentPresetId);
console.log('\n确认这份基线是你想要的状态：');
console.log('  当前模型: ' + (m ? m.model : '(无)'));
console.log('  当前预设: ' + (preset ? preset.name : '(无)'));
console.log('  世界书启用: ' + (d.currentWorldBookIds || []).length);
console.log('  会话 ' + (d.adventureSessions || []).length + ' / 章节 ' + (d.novelChapters || []).length);
console.log('  带密钥配置: ' + (d.apiConfigs || []).filter((c) => c.apiKey).length);
console.log('  键总数: ' + Object.keys(d).length);
console.log('\n如果上面不对，立刻用 data/backups/ 或旧备份覆盖 app-data.json，再重跑本脚本。');
