// 「显示文本」的统一实现
//
// 为什么抽出来：
//   正文在界面上显示的文本，和**送给大模型的文本**不是一回事 ——
//   显示时还要剥掉思维链、跑一遍「仅显示」类正则脚本。
//   这个逻辑原本散在两处（Adventure 里一份、已移除的小说编辑器里一份），
//   现在冒险对话要用同一套结果。
//
//   导出的 TXT 必须是"用户看到的样子"，所以**必须复用这个函数**，
//   不能在导出逻辑里另写一遍处理 —— 那样两边迟早会不一致。
import { computed, ref } from 'vue';
import { useAssetsStore } from '../stores/assets';
import { useStorageSettingsStore } from '../stores/storageSettings';
import { runRegex } from '../engine/regexEngine';
import { cleanOutput } from '../engine/reasoning';
import { buildMacroContext } from './macroContext';

/** 缓存上限：够覆盖一部长篇的显示文本，超了整体清空 */
const CACHE_MAX = 600;

/**
 * 把一段字符串压成短哈希，用作缓存版本号。
 *
 * 直接用 JSON 原文当版本串也行，但它会被拼进**每一条**消息的缓存键里
 * （几百条消息 × 几 KB 版本串），手机上白占内存。哈希到几十字节就够了。
 */
function hashStr(input: string): string {
  let h = 5381;
  for (let i = 0; i < input.length; i++) h = ((h << 5) + h + input.charCodeAt(i)) | 0;
  return (h >>> 0).toString(36);
}

/**
 * 正则分组的**内容**哈希，供所有显示缓存共用。
 *
 * 冒险页和本文件各维护了一套显示缓存，两边必须用同一个版本算法。
 * 只数"有几条、开没开"是不够的：用户改掉某条规则的替换串之后条数没变，
 * 缓存全部命中，界面还是按旧规则渲染 —— 表现就是"我明明改了正则，
 * 怎么没反应 / 有的内容没替换掉"。
 */
export function hashRegexGroups(groups: any[]): string {
  return hashStr(
    JSON.stringify(
      groups.map((g: any) => [
        g._uid,
        g.enabled !== false,
        (g.scripts || []).map((s: any) => [
          s.id,
          s.disabled,
          s.findRegex,
          s.replaceString,
          s.placement,
          s.trimStrings,
          s.markdownOnly,
          s.promptOnly,
          s.substituteRegex,
          s.minDepth,
          s.maxDepth,
        ]),
      ]),
    ),
  );
}

export function useDisplayText() {
  const assets = useAssetsStore();
  const storageSettings = useStorageSettingsStore();

  const cache = new Map<string, string>();

  /**
   * 影响显示结果的全部外部状态，拼成一个版本串。
   * 正则增删改、思维链设置变化时，这个串会变 → 缓存自然失效。
   *
   * ★ 必须带上脚本**内容**，不能只带条数。
   *
   *   以前这里是 `${g._uid}:${scripts.length}:${enabled}` ——
   *   用户把某条规则的替换串改掉之后，条数没变、版本串也没变，
   *   于是缓存全部命中，界面上还是按旧规则渲染的样子。
   *   表现就是"我明明改了正则，怎么没反应"。
   */
  const version = computed(() =>
    [
      storageSettings.hideReasoning ? '1' : '0',
      storageSettings.reasoningCutMarker || '',
      assets.currentRegexGroupIds.join(','),
      hashRegexGroups(assets.regexGroups),
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
        {
          placement: { ai: true, reasoning: true },
          forDisplay: true,
          /*
           * ST 在替换串末尾会跑一遍 substituteParams，美化类正则常写
           * {{char}} / {{user}}。不传的话这些宏会原样留在正文里，
           * 看起来就是"这条正则没替换干净"。
           */
          macroContext: buildMacroContext(),
        },
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
