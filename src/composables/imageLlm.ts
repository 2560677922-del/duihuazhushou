// 生图时"帮我写提示词"用哪个对话模型
// ---------------------------------------------------------------------------
// 这是个容易出错的地方：生图接口配置里有一个 llmConfigId，
// 意思是"用这个模型把正文转成标签"，它**可以不是**当前对话用的模型
// （比如对话用 Gemini 写小说，提示词想用另一个更会写 Danbooru 标签的模型）。
//
// 之前只有「生图弹窗」按这个规则解析，输入框旁边的生图、自动配图
// 都直接写死用 `api.currentConfig` —— 于是用户配了别的模型却毫无效果。
// 这里把规则抽出来，三条路径共用，避免以后再各写一遍。
import { useImageStore } from '../stores/image';
import { useApiConfigStore } from '../stores/apiConfig';
import { useAssetsStore } from '../stores/assets';
import type { MacroContext } from '../engine/macro';

/**
 * 解析提示词优化该用哪份对话模型配置。
 *
 * 优先级：生图接口里指定的 `llmConfigId` → 当前对话模型。
 * 明确指定了就直接用它（哪怕没填 Key —— 由调用方按 apiKey 判断要不要走优化）。
 */
export function resolveImageLlmConfig() {
  const imageStore = useImageStore();
  const api = useApiConfigStore();

  const pickedId = imageStore.currentConfig?.llmConfigId;
  if (pickedId) {
    const picked = api.configs.find((c) => (c as { _uid?: string })._uid === pickedId);
    if (picked) return picked;
  }
  return api.currentConfig;
}

/**
 * 给"生图时参考预设"用的宏上下文。
 *
 * 预设里大量条目长这样：`角色设定：{{description}}`。
 * 聊天路径由 assembler 统一做宏替换，**生图这条路没有** ——
 * 不换的话 `{{description}}` 会被原样丢给大模型（实测踩到过）。
 *
 * 这里和 assembler 用同一套取值来源（角色卡 → 预设设置 → 空），
 * 保证两条路径替换出来的内容一致。
 */
export function buildImageMacroContext(): MacroContext {
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
