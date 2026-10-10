// Token 计数器：可插拔设计。
// 默认采用字符启发式估算（CJK 1 token/字，拉丁文 ~4 字符/token），
// 与 GengBi 一致的方向；第四阶段再切换 tiktoken wasm 精确计数。

export interface TokenCounter {
  name: string;
  count(text: string): number;
}

function isCJK(codePoint: number): boolean {
  return (
    (codePoint >= 0x3400 && codePoint <= 0x4dbf) || // CJK 扩展 A
    (codePoint >= 0x4e00 && codePoint <= 0x9fff) || // CJK 基本区
    (codePoint >= 0xf900 && codePoint <= 0xfaff) || // 兼容表意文字
    (codePoint >= 0x3000 && codePoint <= 0x303f) || // CJK 符号和标点
    (codePoint >= 0xff00 && codePoint <= 0xffef) || // 全角符号
    (codePoint >= 0xac00 && codePoint <= 0xd7a3) // 韩文音节
  );
}

export const heuristicCounter: TokenCounter = {
  name: 'heuristic (CJK=1, Latin/4)',
  count(text: string): number {
    if (!text) return 0;
    let cjk = 0;
    let other = 0;
    for (const ch of text) {
      const cp = ch.codePointAt(0)!;
      if (isCJK(cp)) cjk++;
      else other++;
    }
    return cjk + Math.ceil(other / 4);
  },
};

export let activeCounter: TokenCounter = heuristicCounter;

export function setTokenCounter(counter: TokenCounter): void {
  activeCounter = counter;
}

export function countTokens(text: string): number {
  return activeCounter.count(text);
}
