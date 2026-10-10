// 「显示文本」的统一实现
//
// 为什么抽出来：
//   正文在界面上显示的文本，和**送给大模型的文本**不是一回事 ——
//   显示时还要剥掉思维链、跑一遍「仅显示」类正则脚本。
//   这个逻辑原本只写在 NovelEditor 里（Adventure 也有一份），
//   现在阅读器和 TXT 导出都要用同一套结果。
//
//   导出的 TXT 必须是"用户看到的样子"，所以**必须复用这个函数**，
//   不能在导出逻辑里另写一遍处理 —— 那样两边迟早会不一致。
import { computed, ref } from 'vue';
import { useAssetsStore } from '../stores/assets';
import { useStorageSettingsStore } from '../stores/storageSettings';
import { runRegex } from '../engine/regexEngine';
import { cleanOutput } from '../engine/reasoning';

/** 缓存上限：够覆盖一部长篇的显示文本，超了整体清空 */
const CACHE_MAX = 600;

export function useDisplayText() {
  const assets = useAssetsStore();
  const storageSettings = useStorageSettingsStore();

  const cache = new Map<string, string>();

  /**
   * 影响显示结果的全部外部状态，拼成一个版本串。
   * 正则增删改、思维链设置变化时，这个串会变 → 缓存自然失效。
   */
  const version = computed(() =>
    [
      storageSettings.hideReasoning ? '1' : '0',
      storageSettings.reasoningCutMarker || '',
      assets.currentRegexGroupIds.join(','),
      assets.regexGroups
        .map((g: any) => `${g._uid}:${(g.scripts || []).length}:${g.enabled === false ? 0 : 1}`)
        .join('|'),
    ].join('~'),
  );

  /**
   * 把原始输出转成"该显示给用户的文本"。
   *
   * 记忆化是必要的：模板里 `:content="displayText(m.ai)"` 每次重渲染都会调用，
   * 而内部要跑正则引擎。几十轮对话 + 流式每秒重渲染数次 → 手机上必然卡。
   */
  function displayText(text: string): string {
    if (!text) return '';
    const key = version.value + '\u0000' + text;
    const hit = cache.get(key);
    if (hit !== undefined) return hit;

    try {
      let out = text;
      // 1) 剥思维链：推理模型会把"草稿纸"和正文一起返回
      if (storageSettings.hideReasoning) {
        out = cleanOutput(out, storageSettings.reasoningCutMarker);
      }
      // 2) 跑「仅显示」类正则（forDisplay: true 只影响展示，不改发给模型的内容）
      const scripts = assets.getCurrentRegexScripts();
      if (scripts.length) {
        const { messages: msgs } = runRegex(
          [{ role: 'assistant', content: out } as any],
          scripts,
          { placement: { ai: true, reasoning: true }, forDisplay: true },
        );
        out = msgs[0]?.content ?? out;
      }
      if (cache.size >= CACHE_MAX) cache.clear();
      cache.set(key, out);
      return out;
    } catch {
      // 正则写错不该让整条消息消失，退回原文
      return text;
    }
  }

  function clearDisplayCache() {
    cache.clear();
  }

  return { displayText, clearDisplayCache, version };
}

/**
 * 导出用的纯函数版本（不依赖 Vue 响应式）。
 *
 * 阅读器/导出不在组件里时也要能处理，所以提供一个"给一批脚本"的写法。
 * 逻辑和上面那份**保持一致**，只是参数显式传入。
 */
export function displayTextWith(
  text: string,
  opts: { hideReasoning: boolean; cutMarker?: string; scripts: any[] },
): string {
  if (!text) return '';
  try {
    let out = text;
    if (opts.hideReasoning) out = cleanOutput(out, opts.cutMarker);
    if (opts.scripts.length) {
      const { messages: msgs } = runRegex(
        [{ role: 'assistant', content: out } as any],
        opts.scripts,
        { placement: { ai: true, reasoning: true }, forDisplay: true },
      );
      out = msgs[0]?.content ?? out;
    }
    return out;
  } catch {
    return text;
  }
}

// 供非响应式场景使用的空 ref（避免调用方处理 undefined）
export const noopRef = ref(undefined);
