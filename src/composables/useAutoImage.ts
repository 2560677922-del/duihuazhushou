// 自动配图：读取正文里的 [[IMG: ...]] 标记 → 生成图片 → 存为附件。
// ---------------------------------------------------------------------------
// 隔离原则：
//  - 标记只用来定位，生成的图片存进 images 附件数组，绝不写回正文；
//  - 正文里的标记由 assemblePrompt 统一剥掉，模型再也看不到它。
//    所以这段代码产生的任何东西都不会回流到大模型的上下文里。

import { ref } from 'vue';
import {
  parseImageMarkers,
  stripImageMarkers,
  type ImageAttachment,
} from '../engine/imageMarker';
import { generateImage } from '../engine/imageGen';
import { useImageStore } from '../stores/image';
import { useDnaStore } from '../stores/characterDna';
import { useAssetsStore } from '../stores/assets';
import { buildPresetBlock } from '../engine/presetContent';
import { resolveImageLlmConfig, buildImageMacroContext } from './imageLlm';
import { persistGeneratedImage } from './useImageStorage';

export type AttachmentSink = (attachment: ImageAttachment) => Promise<void>;

export interface AutoImageState {
  /** 正在生成的数量 */
  pending: number;
  /** 进度文字 */
  status: string;
  /** 最近一次的错误 */
  error: string;
}

export function useAutoImage() {
  const imageStore = useImageStore();
  const dnaStore = useDnaStore();
  const assetsStore = useAssetsStore();

  const pending = ref(0);
  const status = ref('');
  const error = ref('');

  /**
   * 处理一段正文里的所有配图标记。
   *
   * @param text      AI 产出的原始正文（含标记）
   * @param sink      附件落库回调（写进 chapter.images 或 action.images）
   * @param contextLabel 场景名，供提示词优化使用
   */
  async function processMarkers(
    text: string,
    sink: AttachmentSink,
    contextLabel = '正文段落',
  ): Promise<ImageAttachment[]> {
    error.value = '';
    const created: ImageAttachment[] = [];

    if (!imageStore.autoImage.enabled) return created;

    const markers = parseImageMarkers(text);
    if (markers.length === 0) return created;

    const cfg = imageStore.currentConfig;
    if (!cfg) {
      error.value = '没有可用的生图配置，已跳过自动配图（可到设置页关闭自动配图以免每次提示）';
      return created;
    }
    const max = Math.max(1, imageStore.autoImage.maxPerReply || 1);
    const targets = markers.slice(0, max);

    pending.value = targets.length;
    try {
      for (let i = 0; i < targets.length; i++) {
        const m = targets[i];
        status.value = `🎨 正在生成配图 ${i + 1}/${targets.length}：${m.description.slice(0, 30)}…`;

        const attachment: ImageAttachment = {
          id: `att-${Date.now()}-${i}-${Math.random().toString(36).slice(2, 7)}`,
          markdownIndex: m.markdownIndex,
          description: m.description,
          configName: cfg.name,
          createdAt: Date.now(),
        };

        try {
          /*
           * 自动配图这里也要走共用的解析：
           * 生图接口里指定了 llmConfigId 就该用它，而不是无脑用当前对话模型。
           */
          const llmCfg = resolveImageLlmConfig();
          const useLlm =
            !imageStore.autoImage.skipOptimization && !!llmCfg?.apiKey && !!llmCfg?.baseUrl;

          const result = await generateImage(
            useLlm
              ? {
                  baseUrl: llmCfg!.baseUrl || '',
                  apiKey: llmCfg!.apiKey,
                  model: llmCfg!.model || '',
                  temperature: llmCfg!.temperature ?? 0.7,
                  maxTokens: 900,
                }
              : null,
            {
              provider: cfg.provider,
              novelaiToken: cfg.novelaiToken,
              novelaiModel: cfg.novelaiModel,
              host: cfg.host || undefined,
              baseUrl: cfg.baseUrl,
              apiKey: cfg.apiKey,
              endpoint: cfg.endpoint || undefined,
              model: cfg.model,
              ucPreset: cfg.ucPreset,
              qualityToggle: cfg.qualityToggle,
              cfgRescale: cfg.cfgRescale,
              noiseSchedule: cfg.noiseSchedule,
              sm: cfg.sm,
              smDyn: cfg.smDyn,
              autoSmea: cfg.autoSmea,
              dynamicThresholding: cfg.dynamicThresholding,
              sampler: cfg.sampler,
              steps: cfg.steps,
              width: cfg.width,
              height: cfg.height,
              cfgScale: cfg.cfgScale,
              seed: 0, // 自动配图每次都要新图
              artistTags: cfg.artistTags,
              qualityTags: cfg.qualityTags,
              negativePrompt: cfg.negativePrompt,
              promptTemplate: cfg.promptTemplate,
            },
            {
              contextMessages: [{ role: 'assistant', content: m.description }],
              contextWindow: 1,
              contextLabel,
              /*
               * 默认直接用模型给的画面描述（最省 token、也最贴合它选的位置）。
               *
               * 但开了"参考预设"就必须走一遍 LLM 优化 ——
               * 预设内容是在优化那一步拼进 system 的，不优化就无从生效。
               * 这里显式联动，避免出现"打开了开关却没反应"的困惑。
               */
              skipOptimization: !imageStore.autoImage.usePreset,
              manualPrompt: imageStore.autoImage.usePreset ? undefined : m.description,
              // 角色 DNA 直接拼进正向提示词，自动配图也能保持角色外观一致
              dnaTags: dnaStore.selectedDnaTags() || undefined,
              presetBlock: imageStore.autoImage.usePreset
                ? buildPresetBlock(
                    assetsStore.getCurrentPreset(),
                    imageStore.autoImage.presetMaxChars,
                    buildImageMacroContext(),
                  ) || undefined
                : undefined,
            },
          );

          attachment.dataUrl = result.dataUrl;
          // 记下完整提示词，供"重新生图"原样重发（description 只有截断版）
          attachment.prompt = result.prompt;

          // 按当前设置把图片落地：本地 dataURL（始终保留），另可上传到 GitHub 仓库
          const stored = await persistGeneratedImage(
            result.dataUrl,
            `冒险/小说配图 ${m.description.slice(0, 40)}`,
          );
          // dataUrl 用**本地**副本：远端链接在受限网络下加载不出来，
          // 只留链接就会变成占位符（raw.githubusercontent.com 常常解析不了）
          attachment.dataUrl = stored.displayUrl;
          attachment.remotePath = stored.remotePath;
          attachment.remoteUrl = stored.remoteUrl;
          attachment.storage = stored.uploaded ? 'github' : 'browser';
          if (stored.error) attachment.uploadError = stored.error;

          // 顺手存进图片库，方便统一管理和复用
          await imageStore.addImage({
            id: attachment.id,
            dataUrl: stored.displayUrl,
            prompt: result.prompt,
            negativePrompt: result.negativePrompt || '',
            provider: result.provider,
            model: result.model,
            seed: result.seed,
            timestamp: Date.now(),
            context: m.description.slice(0, 200),
            // 小说功能已移除，配图统一归到冒险；原来这里是按
            // "上下文标签里有没有『章』字"来猜，现在没必要猜了
            scene: 'adventure',
            entityId: attachment.id,
          });
        } catch (e) {
          attachment.error = e instanceof Error ? e.message : String(e);
          error.value = attachment.error;
        }

        await sink(attachment);
        created.push(attachment);
      }
    } finally {
      pending.value = 0;
      status.value = '';
    }

    return created;
  }

  return {
    pending,
    status,
    error,
    processMarkers,
    /** 供 UI 预览用：剥掉标记后的纯正文 */
    cleanText: stripImageMarkers,
  };
}
