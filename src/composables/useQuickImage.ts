// 一键生图
// ---------------------------------------------------------------------------
// 用户的要求很明确：
//   在对话界面点一下就出图，LLM 自动优化提示词，生成完**自动插进这条消息**，
//   不需要审核、不需要展开面板、不需要点第二个按钮。
//   不满意就自己删掉或重新生成。
//
// 和「自动配图」的区别：
//   自动配图 —— AI 自己决定哪儿配图，靠 [[IMG: 描述]] 标记
//   一键生图 —— 用户觉得"这段话值得配张图"，点一下立刻出
// 两者都走同一套生成与存储，只是触发的发起方不同。

import { ref } from 'vue';
import { generateImage } from '../engine/imageGen';
import { useImageStore } from '../stores/image';
import { useDnaStore } from '../stores/characterDna';
import { useStorageSettingsStore } from '../stores/storageSettings';
import { persistGeneratedImage } from './useImageStorage';
import { addAsset, assetOf } from '../lib/imageAssets';
import { buildTagReference } from '../engine/tagLibrary';
import type { ImageAttachment } from '../engine/imageMarker';
import { buildPresetBlock } from '../engine/presetContent';
import { useAssetsStore } from '../stores/assets';
import { resolveImageLlmConfig, buildImageMacroContext } from './imageLlm';
import { cleanSceneText } from '../engine/imageGen';

/**
 * 超长文本截断：**保住开头**（画面主体）+ 结尾（情绪/收束），砍中间。
 *
 * 为什么不用 slice(-N)：正文里"这画面长什么样"的信息集中在开头，
 * 只留尾巴等于把最该给模型的砍掉了（历史消息配图乱配的根因之一）。
 *
 * @param text 原文
 * @param limit 总字符上限
 * @param headRatio 开头占的比例（0.7 = 头七尾三）
 */
function headTail(text: string, limit = 6000, headRatio = 0.7): string {
  const t = text || '';
  if (t.length <= limit) return t;
  const headLen = Math.floor(limit * headRatio);
  const tailLen = limit - headLen - 20; // 20 留给分隔说明
  const head = t.slice(0, headLen);
  const tail = t.slice(-Math.max(0, tailLen));
  return `${head}\n\n……（中间省略）……\n\n${tail}`;
}

export function useQuickImage() {
  const imageStore = useImageStore();
  const dnaStore = useDnaStore();
  const assetsStore = useAssetsStore();
  const storageSettings = useStorageSettingsStore();

  /** 正在为哪条消息出图（消息 id），空表示空闲 */
  const busyFor = ref<string | null>(null);
  const status = ref('');
  const error = ref('');

  /**
   * 为一段正文生成一张图。
   *
   * @param text 这段正文（作为 LLM 优化的上下文）
   * @param contextLabel 上下文说明
   * @param index 插到第几段（附件定位用）
   * @param override 可选覆盖项，用于"原地重新生图"
   */
  async function generateFor(
    text: string,
    contextLabel: string,
    index: number,
    override?: {
      /**
       * 直接拿这段文字当提示词，**不让 LLM 改写**。
       *
       * "重新生图"要的是同一张画面换个种子，而不是让模型重新发挥一遍 ——
       * 改写之后就不是同一张图了（生图弹窗里也是这个考虑，所以进来会切成手动模式）。
       */
      rawPrompt?: string;
      /** 复用这个附件的 id 与位置，实现原地替换而不是新增一张 */
      reuse?: ImageAttachment;
    },
  ): Promise<ImageAttachment | null> {
    const cfg = imageStore.currentConfig;
    if (!cfg) {
      error.value = '还没有生图配置，请先到「设置 → 生图配置」填好';
      return null;
    }
    if (!text.trim()) {
      error.value = '这条消息没有内容可用来生成配图';
      return null;
    }

    busyFor.value = 'pending';
    status.value = '正在生成配图…';
    error.value = '';

    /*
     * 先把"不是画面"的东西剔掉，再按"头重脚轻"的方式截断。
     * 见下面 contextMessages 处的说明。
     */
    const sceneText = headTail(cleanSceneText(text), 6000, 0.7);

    try {
      /*
       * 提示词优化用的模型：优先生图接口里指定的 llmConfigId，
       * 没指定才用当前对话模型（见 composables/imageLlm.ts 的说明）。
       */
      const llm = resolveImageLlmConfig();
      // 指定了 rawPrompt 就走手动模式，不让 LLM 改写
      const manual = override?.rawPrompt?.trim();
      const useLlm = !manual && !!llm?.apiKey;
      const result = await generateImage(
        useLlm ? llm : null,
        {
          ...cfg,
          // 用生图配置里的尺寸/步数等，不额外覆盖
        } as any,
        {
          /*
           * ★ 上下文怎么给，决定了"配图贴不贴这段剧情"。
           *
           * 以前是 `text.slice(-3000)` —— 只取**末尾** 3000 字。
           * 对短消息没事，但真实会话里动辄三四千字，开头那段
           * （"银发少女推开石门…"）正好是**画面主体最集中的地方**，
           * 被切掉之后模型只剩结尾的对话和过渡，只能瞎猜——
           * 这就是"点历史消息的配图会乱配、而最新一条正常"的原因。
           *
           * 现在的做法：
           *   1. 先 cleanSceneText 去掉思维链/代码/元话语（那类内容占比很大）；
           *   2. 净化后仍然超长时，**保留开头 70% + 结尾 30%** ——
           *      画面描写通常在开头，情绪/收束在结尾，两头都留住；
           *      中间那些重复的过程叙述丢掉的代价最小。
           */
          contextMessages: [{ role: 'assistant', content: sceneText }],
          contextWindow: 1,
          contextLabel,
          /*
           * 关键：这里**不跳过** LLM 优化 —— 用户要的就是
           * "llm 自动优化提示词"。用标记描述直出的话画质和贴合度都差一截。
           * 只有在没配 LLM（或明确要求手动）时才退回直接使用文本。
           */
          skipOptimization: !useLlm,
          manualPrompt: useLlm ? undefined : manual || text.slice(-500),
          tagReference: storageSettings.tagReferenceEnabled
            ? buildTagReference({
                enabledCategories: storageSettings.tagCategories,
                perCategory: storageSettings.tagPerCategory,
              })
            : undefined,
          dnaBlock: dnaStore.selectedDnaBlock() || undefined,
          // 预设参考：默认关闭，开关在「设置 → 提示词处理」旁边
          presetBlock: imageStore.autoImage.usePreset
            ? buildPresetBlock(assetsStore.getCurrentPreset(), imageStore.autoImage.presetMaxChars, buildImageMacroContext()) || undefined
            : undefined,
          dnaTags: dnaStore.selectedDnaTags() || undefined,
        },
      );

      // 落地：按设置存本地或上传 GitHub
      const stored = await persistGeneratedImage(result.dataUrl, `配图 ${contextLabel}`);

      /*
       * 图片本体进**图片资产库**，会话里只留一个 assetId 引用。
       *
       * 为什么这么做：会话数据要同步、要落盘、要进 GitHub 备份。
       * 里面塞图片的话，体积随图量一路涨，聊天页越来越慢 ——
       * 这正是"越用越卡"的一半原因。
       */
      /*
       * 存图片资产库。**失败不能抛** ——
       * 图已经生成出来了，存不进去也该让用户看到图（退回存 dataUrl 兜底）。
       * 之前是裸 await，存库一失败整次配图就报错，用户以为生成失败。
       */
      let assetId = '';
      try {
        assetId = await addAsset(result.dataUrl, {
          prompt: result.prompt,
          negativePrompt: result.negativePrompt,
          provider: result.provider,
          model: result.model,
          seed: result.seed,
        description: result.prompt.slice(0, 120),
          origin: 'auto',
          scene: 'adventure',
          entityId: 'manual',
        });
      } catch (e) {
        console.warn('[配图] 存图片库失败，退回内联方式：', e);
      }

      const attachment: ImageAttachment = {
        // 重新生图时复用原 id，updater 就能原地替换而不是多插一张
        id: override?.reuse?.id ?? `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        markdownIndex: override?.reuse?.markdownIndex ?? index,
        // description 供界面显示和正文标记，所以截断；完整提示词另存 prompt
        description: result.prompt.slice(0, 120),
        prompt: result.prompt,
        // ★ 会话里只存引用，不存图片本体
        // 存库失败（assetId 为空）就退回内联 dataUrl，至少这轮能显示
        assetId: assetId || undefined,
        dataUrl: assetId ? undefined : stored.displayUrl || result.dataUrl,
        remotePath: stored.remotePath,
        remoteUrl: stored.remoteUrl,
        storage: stored.uploaded ? 'github' : 'browser',
        uploadError: stored.error,
        configName: cfg.name,
        createdAt: Date.now(),
      };

      status.value = '配图已生成';
      setTimeout(() => (status.value = ''), 2500);
      return attachment;
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e);
      return null;
    } finally {
      busyFor.value = null;
    }
  }

  /**
   * 按**已有提示词**直接重新出一张图 —— 不走 LLM，也不改提示词。
   *
   * "重新生图"的语义就该是这样：提示词是现成的，直接把这条原样发给生图接口，
   * 换一个种子重新出。以前是拿附件里那条**被截断到 120 字**的 description
   * 去重出，所以画面根本不是同一张 —— 用户看到的"生的图不对"就是这个。
   *
   * @param att 原附件（用它的 prompt 全文 + 复用它的 id/位置）
   * @param contextLabel 用于状态提示与图片库归类
   */
  async function regenerateFromAttachment(
    att: ImageAttachment,
    contextLabel: string,
  ): Promise<ImageAttachment | null> {
    const cfg = imageStore.currentConfig;
    if (!cfg) {
      error.value = '还没有生图配置，请先到「设置 → 生图配置」填好';
      return null;
    }
    /*
     * 提示词从哪来（按可靠性排序）：
     *   1. 附件自己存的完整 prompt —— 新数据都有
     *   2. 图片库里同 id 的那条记录 —— 老附件没存 prompt，但库里可能还留着
     *   3. description（只有 120 字）—— 最后的兜底，画面会跟原来不太一样
     */
    const fromGallery = assetOf(att.assetId || att.id)?.prompt;
    const fullPrompt = (att.prompt || fromGallery || att.description || '').trim();
    if (!fullPrompt) {
      error.value = '这张图没有留下提示词，无法重新生成';
      return null;
    }

    busyFor.value = 'pending';
    status.value = '正在重新生成…';
    error.value = '';

    try {
      /*
       * 用 `rawFullPrompt` 而不是 `manualPrompt`：
       *
       * manualPrompt 只是"主体提示词"，provider 层还会再拼一遍
       * `artistTags + qualityTags`。而这里拿到的是**上一次已经拼好的整段成品**，
       * 再拼一次画师串就会重复，出来的画风就变了 —— 这正是之前
       * "重新生图出来的图不对"的原因。
       *
       * rawFullPrompt 走的是整段直发：不拼画师串、不过 LLM，原样发给生图接口。
       *
       * seed 强制给 0：引擎里 `seed > 0 ? seed : randomSeed()`，
       * 0 就等于"随机"。否则风格里若固定了种子，重生成会得到一模一样的图。
       */
      const result = await generateImage(
        null,
        { ...cfg, seed: 0 } as any,
        {
          contextMessages: [],
          contextWindow: 0,
          contextLabel,
          // 整段直发：不拼画师串、不走 LLM
          rawFullPrompt: fullPrompt,
        },
      );

      const stored = await persistGeneratedImage(result.dataUrl, `重生成 ${contextLabel}`);

      /*
       * 新图也要进**图片资产库** —— 和 generateFor 保持一致。
       *
       * ★ 这里原来漏了 addAsset，却还在下面把**旧资产删掉**：
       *     旧图从库里消失、新图从来没进过库，
       *   结果每点一次"重新生图"，图片库就少一张，
       *   而且附件拿不到新图的 assetId，刷新后这张图就找不回来了。
       */
      let assetId = '';
      try {
        assetId = await addAsset(result.dataUrl, {
          prompt: result.prompt,
          negativePrompt: result.negativePrompt,
          provider: result.provider,
          model: result.model,
          seed: result.seed,
          description: result.prompt.slice(0, 120),
          origin: 'auto',
          scene: 'adventure',
          entityId: 'manual',
        });
      } catch (e) {
        console.warn('[重生成] 存图片库失败，退回内联方式：', e);
      }

      const attachment: ImageAttachment = {
        // 复用原 id 与位置 → 原地替换，正文里不会多出一个标记
        id: att.id,
        markdownIndex: att.markdownIndex,
        description: result.prompt.slice(0, 120),
        prompt: result.prompt,
        assetId: assetId || undefined,
        dataUrl: assetId ? undefined : stored.displayUrl || result.dataUrl,
        remotePath: stored.remotePath,
        remoteUrl: stored.remoteUrl,
        storage: stored.uploaded ? 'github' : 'browser',
        uploadError: stored.error,
        configName: cfg.name,
        createdAt: Date.now(),
      };

      /*
       * ★ 不删旧资产。
       *
       * 旧图会被 pushImageVersion 存成**历史版本**，用户随时可能切回去看。
       * 之前这里 removeAsset(att.assetId) 把旧图本体删了 ——
       * 于是"翻回上一版"永远是一张裂图，版本功能等于白做。
       * 旧版本在历史上限（MAX_IMAGE_VERSIONS）内就留着，超出后由图片库自己清理。
       */

      status.value = '已重新生成';
      setTimeout(() => (status.value = ''), 2500);
      return attachment;
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e);
      return null;
    } finally {
      busyFor.value = null;
    }
  }

  return { busyFor, status, error, generateFor, regenerateFromAttachment };
}
