// 测试数据基线 —— 防止测试污染用户的真实数据
//
// 事故记录（为什么需要这个文件）：
//   之前的测试脚本是"开头备份 → 结尾还原"：
//     fs.copyFileSync(DATA, BACKUP)   // 开头
//     ...跑测试...
//     fs.copyFileSync(BACKUP, DATA)   // 结尾
//   问题：脚本一旦中途抛错，**结尾那行就不会执行**，污染就留在真实数据里了。
//   更糟的是，下一个测试的"开头备份"备份到的其实是**已被污染的状态**，
//   于是污染一层层累积 —— 最终用户的当前模型、预设、温度都被改掉了。
//
// 正确做法（这个文件提供的）：
//   1. 从 `data/.clean-baseline.json` 这个**不可变基线**恢复（不是从"当时的状态"）；
//   2. 用 try/finally 保证无论成功失败都会还原；
//   3. 基线本身只在明确知道数据是干净的时候才更新。
//
// 用法：
//   import { withCleanData } from './_testdata.mjs';
//   await withCleanData(async () => {
//     ...你的测试...
//   });
import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'E:\\duihuazhushou';
export const DATA = path.join(ROOT, 'data', 'app-data.json');
export const BASELINE = path.join(ROOT, 'data', '.clean-baseline.json');

/** 基线是否存在 */
export function hasBaseline() {
  return fs.existsSync(BASELINE);
}

/**
 * 建立基线：把当前数据快照成"以后测试都从这里恢复"的版本。
 * **只在确认当前数据是干净的时候调用**。
 */
export function snapshotBaseline() {
  fs.copyFileSync(DATA, BASELINE);
  return BASELINE;
}

/** 从基线恢复（如果基线不存在则不动） */
export function restoreFromBaseline() {
  if (!hasBaseline()) {
    console.warn('[测试数据] 没有基线，跳过还原 —— 这次测试可能污染真实数据');
    return false;
  }
  fs.copyFileSync(BASELINE, DATA);
  return true;
}

/**
 * 包住一段测试：先恢复到基线，跑完（无论成功失败）再恢复。
 *
 * 这样即使测试中途抛错，真实数据也会被还原 ——
 * 而且因为每次都是从**同一个基线**出发，污染不会累积。
 */
export async function withCleanData(fn) {
  if (!hasBaseline()) {
    console.warn('[测试数据] 没有基线！请先运行 snapshotBaseline()');
  } else {
    restoreFromBaseline();
  }
  try {
    return await fn();
  } finally {
    // finally：无论测试成功、失败还是抛异常，都还原
    restoreFromBaseline();
  }
}
