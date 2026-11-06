// 宏上下文：从当前角色卡 + 预设设置拼出 {{char}} / {{user}} / {{description}} …
//
// 为什么单独一个文件：
//   这套取值来源原本写在 imageLlm.ts 里（给"生图参考预设"用）。
//   但显示正文的正则替换串里同样会出现 {{char}}（美化类规则常用），
//   两边各写一份迟早不一致 —— 所以抽出来，谁需要谁调用。
//
// 取值优先级和 assembler 保持一致：角色卡字段 → 预设设置 → 空。
import { useAssetsStore } from '../stores/assets';
import type { MacroContext } from '../engine/macro';

export function buildMacroContext(): MacroContext {
  const assets = useAssetsStore();
  const card = assets.getCurrentCharacter() as
    | {
        name?: string;
        description?: string;
        scenario?: string;
        personality?: string;
        persona?: string;
        first_mes?: string;
        firstMes?: string;
        mes_example?: string;
        mesExample?: string;
      }
    | undefined;
  const preset = assets.getCurrentPreset() as
    | {
        getSetting?: (k: string) => string | undefined;
        prompts?: { content?: string }[];
      }
    | undefined;

  return {
    char: card?.name ?? preset?.getSetting?.('name_char') ?? '',
    user: preset?.getSetting?.('name_user') ?? '用户',
    description: card?.description ?? '',
    scenario: card?.scenario ?? '',
    persona: card?.persona ?? '',
    original: card?.first_mes ?? card?.firstMes ?? '',
    mes_example: card?.mes_example ?? card?.mesExample ?? '',
  };
}
