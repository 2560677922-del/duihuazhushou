// 图片落地：把生成结果按当前配置存到本地或 GitHub 仓库。
//
// 所有生图入口（手动生图弹窗、大模型自主配图）都走这里，
// 保证「存哪里」这件事只有一份判断逻辑。

import { useStorageSettingsStore } from '../stores/storageSettings';
import { uploadImageToGithub, fetchGithubImageAsDataUrl } from '../engine/githubStorage';

export interface StoredImage {
  /** 可直接用于 <img> 的本地地址（始终是 dataURL，一定加载得出来） */
  displayUrl: string;
  /** 仓库内路径（存到 GitHub 时才有） */
  remotePath?: string;
  /** 远端可访问的链接（存到 GitHub 时才有） */
  remoteUrl?: string;
  /** 是否成功存到了远端 */
  uploaded: boolean;
  /** 上传失败时的原因 */
  error?: string;
  bytes?: number;
}

/**
 * 保存一张生成的图片。
 *
 * 关键决策：**本地副本始终保留**。
 *
 * 以前这里有个坑：user 把 `imageKeepLocalCopy` 关掉后，上传 GitHub 成功就
 * 只返回远程链接、不留 dataURL。看着像省了空间，实际上：
 *   1. 界面每次显示都要现去远端拉 —— 慢，而且离线就看不到
 *   2. 一旦远端域名被墙 / DNS 挂掉（raw.githubusercontent.com 在有些网络下
 *      就是解析不了），图直接变成占位符，看起来就像"图丢了"
 *   3. 生成图本来就该随手能看，为了省几百 KB 把体验搭进去不划算
 *
 * 所以现在的规则是：
 *   成功上传 → dataURL 用于显示（快、稳），链接和路径存下来备用
 *   上传失败 → 照样有 dataURL，只是标记"仅本地"
 */
export async function persistGeneratedImage(
  dataUrl: string,
  commitHint?: string,
): Promise<StoredImage> {
  const settings = useStorageSettingsStore();
  const gh = settings.activeGithub();

  // 没配 GitHub：纯本地
  if (!gh) {
    return { displayUrl: dataUrl, uploaded: false };
  }

  try {
    const r = await uploadImageToGithub(
      gh,
      dataUrl,
      commitHint ? `${gh.commitPrefix || 'chore(images):'} ${commitHint}` : undefined,
    );
    return {
      // 显示一律用本地 dataURL —— 远端链接只作为"备份在哪"的记录
      displayUrl: dataUrl,
      remoteUrl: r.url,
      remotePath: r.path,
      uploaded: true,
      bytes: r.bytes,
    };
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (settings.fallbackToLocal) {
      return { displayUrl: dataUrl, uploaded: false, error: msg };
    }
    throw new Error(`上传到 GitHub 失败：${msg}`);
  }
}

/**
 * 把「显示地址」解析成浏览器真能加载的东西。
 *
 * 现在附件里的 dataUrl 基本都已经是本地 dataURL（persistGeneratedImage 保证），
 * 所以这个函数主要处理**历史数据**和**极小概率的远程地址**：
 *   - dataURL / 空 → 原样返回
 *   - 远程链接 → 先直接交给 <img>；加载不了时由组件回退到这里，
 *     再尝试带令牌通过 API 取回 base64
 *
 * 注意：不再无条件把链接换成 base64。以前对私有仓库每次都发一次 API 请求，
 * 又慢又容易在受限网络下失败；公开仓库的链接本来就能直接用。
 */
export async function resolveDisplayUrl(
  url: string,
  remotePath?: string,
): Promise<string> {
  if (!url || url.startsWith('data:')) return url;
  const settings = useStorageSettingsStore();
  const gh = settings.activeGithub();

  // 公开仓库：链接可以直接用，不用绕一圈
  if (gh && !gh.isPrivate) return url;

  // 私有仓库 / 链接加载失败：带令牌通过 API 取回来
  if (gh && remotePath) {
    try {
      return await fetchGithubImageAsDataUrl(gh, remotePath);
    } catch {
      // 取不到就退回原链接 —— 至少能显示成破图而不是一片空白
      return url;
    }
  }
  return url;
}
