/**
 * 导出聊天记录（冒险对话 / 小说编辑器共用）。
 *
 * 两个模式，由设置里的 `exportRaw` 决定：
 *
 *   关（默认）→ 导出**你看到的样子**：
 *     走 `displayTextWith`（和界面、阅读器同一个函数），
 *     剥掉思维链、跑一遍「仅显示」类正则。
 *     适合"我自己留着看 / 存起来"。
 *
 *   开 → 导出**原始输出**：
 *     完全不处理，正则换出来的 <details>、状态栏标签、
 *     思维链等都原样保留。
 *     适合"换个平台继续用" —— 那边可能认得这些标记，
 *     替换掉反而把信息弄丢了。
 *
 * 图片标记（[[IMG: ...]]）在任何模式下都删掉：
 * 它在纯文本里没有意义，留着只是噪音。
 * 删完会把"连续 3 个以上换行"压回 2 个，避免留下大片空段。
 */
import { useStorageSettingsStore } from '../stores/storageSettings';
import { useAssetsStore } from '../stores/assets';
import { displayTextWith } from '../composables/useDisplayText';
import { useVariablesStore } from '../stores/variables';
import { stripImageMarkers } from '../engine/imageMarker';
import { downloadTxt } from './download';

export interface ChatLogEntry {
  /** 谁的发言：user = 我，ai = 模型 */
  role: 'user' | 'ai';
  text: string;
}

/**
 * 把一批对话条目拼成纯文本。
 *
 * @param title 文件标题（作品名 / 会话名）
 * @param entries 对话条目（按时间顺序）
 * @returns 拼好的文本
 */
export function buildChatLogText(title: string, entries: ChatLogEntry[]): string {
  const settings = useStorageSettingsStore();
  const assets = useAssetsStore();
  const variables = useVariablesStore();
  const raw = settings.exportRaw;
  const scripts = assets.getCurrentRegexScripts();
  // 导出的 TXT 必须和界面看到的一样，变量宏也要展开（只读）
  const tables = variables.snapshot();

  /** 单条文本的处理：原文模式直接返回，否则走显示语义 */
  const process = (text: string): string => {
    if (!text) return '';
    const out = raw
      ? text
      : displayTextWith(text, {
          hideReasoning: settings.hideReasoning,
          cutMarker: settings.reasoningCutMarker,
          scripts,
          variables: tables,
        });
    return stripImageMarkers(out)
      .replace(/[ \t]+$/gm, '')
      .replace(/\n{3,}/g, '\n\n')
      .trim();
  };

  const lines: string[] = [];
  if (title) {
    lines.push(title);
    lines.push('');
  }
  for (const e of entries) {
    const body = process(e.text);
    if (!body) continue;
    lines.push(e.role === 'user' ? '我：' : '——');
    lines.push(body);
    lines.push('');
  }
  return lines.join('\n').replace(/\n{3,}/g, '\n\n').trim() + '\n';
}

/** 拼好并直接下载 */
export function exportChatLog(title: string, entries: ChatLogEntry[]): void {
  const text = buildChatLogText(title, entries);
  const suffix = useStorageSettingsStore().exportRaw ? '-原文' : '';
  downloadTxt(text, `${title || '对话记录'}${suffix}`);
}
