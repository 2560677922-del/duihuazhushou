import { defineStore } from 'pinia';
import { ref } from 'vue';
import { store } from '../lib/storage';
import type { GithubStorageConfig } from '../engine/githubStorage';
import { validateGithubConfig } from '../engine/githubStorage';
import { TAG_CATEGORIES } from '../engine/tagLibrary';
import type { ImportedTagLibrary } from '../engine/tagLibrary';

// 图片来源（图床）配置。
// 目前支持两种最终落地方式：
//  - browser：图片以 dataURL 存在本地（浏览器 + 本地落盘文件）
//  - github ：图片上传到自己的 GitHub 仓库，返回可直接引用的链接
export type ImageStorageBackend = 'browser' | 'github';

export interface GithubEntry extends GithubStorageConfig {
  _uid: string;
  name: string;
}

const DEFAULT_GITHUB: Omit<GithubEntry, '_uid' | 'name'> = {
  enabled: false,
  owner: '',
  repo: '',
  token: '',
  branch: 'main',
  path: 'images',
  format: 'webp',
  maxKB: 900,
  isPrivate: undefined,
  commitPrefix: 'chore(images):',
};

export const useStorageSettingsStore = defineStore('storageSettings', () => {
  const backend = ref<ImageStorageBackend>('browser');
  const githubConfigs = ref<GithubEntry[]>([]);
  const currentGithubId = ref<string | undefined>(undefined);
  /** 是否再保留一份 dataURL 到本地（离线也能看） */
  const keepLocalCopy = ref(true);
  /** 上传失败时是否回退到本地存储 */
  const fallbackToLocal = ref(true);

  // ---- 标签库 ----
  /** 是否在优化提示词时附上标签参考 */
  const tagReferenceEnabled = ref(true);
  /** 启用哪些标签分类 */
  const tagCategories = ref<string[]>(
    TAG_CATEGORIES.filter((c) => c.defaultOn).map((c) => c.id),
  );
  /** 每个分类取多少个标签 */
  const tagPerCategory = ref(24);
  /** 用户导入的完整标签库（可选） */
  const importedTagLib = ref<ImportedTagLibrary | null>(null);
  /** 用户在标签库里手挑的标签，会一起附加到参考块 */
  const pickedTags = ref<string[]>([]);

  // ---- 思维链处理 ----
  /**
   * 界面是否隐藏思维链。默认隐藏 —— 用户要的是故事，不是模型的草稿纸。
   */
  const hideReasoning = ref(true);
  /**
   * 思维链分界标记（可选，一行）。
   *
   * 有些中转把推理和正文**混在同一个字段且不带标记**，
   * 这种情况没法自动区分，只能由用户指定一个"这行之后才是正文"的标记。
   * 例如填 `---正文---` 或 `思考完毕`。
   *
   * 留空时只按常见标记（<thinking>…</thinking>、</think> 等）剥离。
   */
  const reasoningCutMarker = ref('');

  // ---- 正则作用范围（两个可选项，见设置页）----
  /**
   * 发给大模型的上下文里，用**原文**还是**跑过正则的文本**。
   *
   * 默认 false = 跑正则（保持一直以来的行为）：
   *   正则里带 `promptOnly` 的规则会被应用，带 `markdownOnly` 的会跳过 ——
   *   也就是"只改显示的美化规则不会污染上下文"。
   *
   * 打开后 = 完全跳过正则，原文直发。
   *   什么情况下需要它：用户想拿原文（比如自己导入了一套会改语义的正则，
   *   但希望这一轮交给模型的是没被动过的原始文本）；
   *   或者排查"到底是正则改坏了还是模型本来就胡说"。
   *
   * 注意它**只影响发给模型的文本**，界面显示始终走显示语义，不受影响。
   */
  const llmContextRaw = ref(false);

  /**
   * 导出聊天记录 / TXT 时用**原文**还是**跑过正则的显示文本**。
   *
   * 默认 false = 导出"你看到的样子"（含正则美化效果）。
   * 打开后 = 导出原始输出（带 <details> 之类标记的原文），
   *   适合"换个平台继续用"—— 那边可能认得这些标记，替换掉反而丢失信息。
   */
  const exportRaw = ref(false);

  /** 手挑标签组成的参考片断 */
  function pickedTagReferenceText(): string {
    if (pickedTags.value.length === 0) return '';
    return (
      '【我挑好的标签】' +
      pickedTags.value.join(', ') +
      '\n\n写提示词时请优先从上面这些标签里选，保持写法完全一致。'
    );
  }

  const currentGithub = () => githubConfigs.value.find((c) => c._uid === currentGithubId.value);

  /** 当前生效的 GitHub 配置（已启用且填写完整才返回） */
  function activeGithub(): GithubEntry | undefined {
    if (backend.value !== 'github') return undefined;
    const c = currentGithub();
    if (!c || !c.enabled) return undefined;
    if (validateGithubConfig(c)) return undefined;
    return c;
  }

  async function load() {
    const [b, cfgs, cur, fallback, tagOn, tagCats, tagPer, tagLib, picked, hideRea, cutMark, lcRaw, expRaw] =
      await Promise.all([
        store.getItem<ImageStorageBackend>('imageStorageBackend'),
        store.getItem<GithubEntry[]>('githubStorageConfigs'),
        store.getItem<string | undefined>('currentGithubStorageId'),
        store.getItem<boolean>('imageFallbackToLocal'),
        store.getItem<boolean>('tagReferenceEnabled'),
        store.getItem<string[]>('tagCategories'),
        store.getItem<number>('tagPerCategory'),
        store.getItem<ImportedTagLibrary | null>('importedTagLibrary'),
        store.getItem<string[]>('pickedTags'),
        store.getItem<boolean>('hideReasoning'),
        store.getItem<string>('reasoningCutMarker'),
        store.getItem<boolean>('llmContextRaw'),
        store.getItem<boolean>('exportRaw'),
      ]);
    pickedTags.value = picked ?? [];
    hideReasoning.value = hideRea ?? true;
    reasoningCutMarker.value = cutMark ?? '';
    llmContextRaw.value = lcRaw ?? false;
    exportRaw.value = expRaw ?? false;
    backend.value = b ?? 'browser';
    // 本地副本固定开启：远端链接在部分网络下解析不了，
    // 只存链接会让图片变成占位符（曾经真的发生过）
    keepLocalCopy.value = true;
    fallbackToLocal.value = fallback ?? true;
    tagReferenceEnabled.value = tagOn ?? true;
    tagCategories.value =
      tagCats && tagCats.length
        ? tagCats
        : TAG_CATEGORIES.filter((c) => c.defaultOn).map((c) => c.id);
    tagPerCategory.value = tagPer ?? 24;
    importedTagLib.value = tagLib ?? null;

    if (!cfgs || cfgs.length === 0) {
      const def: GithubEntry = {
        ...DEFAULT_GITHUB,
        _uid: `gh-${Math.random().toString(36).slice(2, 10)}`,
        name: '我的图片仓库',
      };
      githubConfigs.value = [def];
      currentGithubId.value = def._uid;
      await save();
    } else {
      githubConfigs.value = cfgs;
      currentGithubId.value =
        cur && cfgs.some((c) => c._uid === cur) ? cur : cfgs[0]._uid;
    }
  }

  async function save() {
    await Promise.all([
      store.setItem('imageStorageBackend', backend.value),
      store.setItem('githubStorageConfigs', githubConfigs.value),
      store.setItem('currentGithubStorageId', currentGithubId.value),
      store.setItem('imageKeepLocalCopy', keepLocalCopy.value),
      store.setItem('imageFallbackToLocal', fallbackToLocal.value),
      store.setItem('tagReferenceEnabled', tagReferenceEnabled.value),
      store.setItem('tagCategories', tagCategories.value),
      store.setItem('tagPerCategory', tagPerCategory.value),
      store.setItem('importedTagLibrary', importedTagLib.value),
      store.setItem('pickedTags', pickedTags.value),
    store.setItem('hideReasoning', hideReasoning.value),
    store.setItem('reasoningCutMarker', reasoningCutMarker.value),
    store.setItem('llmContextRaw', llmContextRaw.value),
    store.setItem('exportRaw', exportRaw.value),
    ]);
  }

  async function toggleTagCategory(id: string, on: boolean) {
    tagCategories.value = on
      ? [...new Set([...tagCategories.value, id])]
      : tagCategories.value.filter((x) => x !== id);
    await save();
  }

  async function setImportedTagLib(lib: ImportedTagLibrary | null) {
    importedTagLib.value = lib;
    await save();
  }

  async function setBackend(v: ImageStorageBackend) {
    backend.value = v;
    await save();
  }

  async function updateGithub(uid: string, patch: Partial<GithubEntry>) {
    const c = githubConfigs.value.find((x) => x._uid === uid);
    if (c) Object.assign(c, patch);
    await save();
  }

  async function addGithub(name = '新仓库') {
    const cfg: GithubEntry = {
      ...DEFAULT_GITHUB,
      _uid: `gh-${Math.random().toString(36).slice(2, 10)}`,
      name,
    };
    githubConfigs.value.push(cfg);
    currentGithubId.value = cfg._uid;
    await save();
    return cfg;
  }

  async function removeGithub(uid: string) {
    githubConfigs.value = githubConfigs.value.filter((c) => c._uid !== uid);
    if (currentGithubId.value === uid) currentGithubId.value = githubConfigs.value[0]?._uid;
    await save();
  }

  async function setCurrentGithub(uid: string) {
    currentGithubId.value = uid;
    await save();
  }

  return {
    backend,
    githubConfigs,
    currentGithubId,
    keepLocalCopy,
    fallbackToLocal,
    tagReferenceEnabled,
    tagCategories,
    tagPerCategory,
    importedTagLib,
    pickedTags,
    hideReasoning,
    reasoningCutMarker,
    llmContextRaw,
    exportRaw,
    pickedTagReferenceText,
    load,
    save,
    setBackend,
    updateGithub,
    addGithub,
    removeGithub,
    setCurrentGithub,
    currentGithub,
    activeGithub,
    toggleTagCategory,
    setImportedTagLib,
  };
});
