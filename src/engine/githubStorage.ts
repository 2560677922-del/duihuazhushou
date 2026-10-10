// GitHub 图片仓库：把生成的图片存到自己的 GitHub 仓库里。
//
// 为什么做这个：
//   图片存在浏览器或本机文件里，换设备就带不走。放进 GitHub 仓库后，
//   电脑和安卓都能拿到同一批图，也顺手当了一个可备份的图床。
//
// 接口用的是 GitHub 官方 Contents API（PUT /repos/{owner}/{repo}/contents/{path}），
// 用细粒度 PAT 即可，仓库地址只需 用户名 + 仓库名 + 令牌。
//
// 两个必须注意的 GitHub 限制（决定了下面的实现）：
//   1. Contents API 对单文件有体积上限（约 1MB，含 base64 膨胀），
//      而生图动辄 2~3MB，所以上传前必须压到限额以内。
//   2. 令牌绝不能进前端源码或 URL；<img> 也无法带 Authorization 头，
//      因此公开仓库直接用 raw 链接，私有仓库改为带令牌拉取后转 dataURL 显示。

export interface GithubStorageConfig {
  /** 是否启用：启用后新生成的图片会存到仓库 */
  enabled: boolean;
  /** 用户名或组织名 */
  owner: string;
  /** 仓库名 */
  repo: string;
  /** 细粒度 PAT（需要 Contents 读写权限） */
  token: string;
  /** 分支，默认 main */
  branch: string;
  /** 仓库内的目录前缀 */
  path: string;
  /** 图片格式：webp 体积最小，png 无损但大 */
  format: 'webp' | 'jpeg' | 'png';
  /** 压缩目标上限（KB）。GitHub Contents API 单文件约 1MB，留些余量 */
  maxKB: number;
  /** 仓库是否为私有（由「测试连接」自动探测，也可手动改） */
  isPrivate?: boolean;
  /** 提交信息前缀 */
  commitPrefix?: string;
}

export interface GithubUploadResult {
  /** 仓库内路径 */
  path: string;
  /** 可直接显示的链接（公开仓库为 raw 链接） */
  url: string;
  /** 文件字节数 */
  bytes: number;
  commitSha?: string;
}

const API = 'https://api.github.com';

function ghHeaders(token: string): Record<string, string> {
  return {
    Accept: 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
}

/** 把配置校验一遍，返回错误信息或 null */
export function validateGithubConfig(cfg: Partial<GithubStorageConfig>): string | null {
  if (!cfg.owner?.trim()) return '请填写 GitHub 用户名（或组织名）';
  if (!cfg.repo?.trim()) return '请填写仓库名';
  if (!cfg.token?.trim()) return '请填写访问令牌（PAT）';
  if (cfg.branch !== undefined && cfg.branch !== '' && /[\s~^:?*[\\]/.test(cfg.branch)) {
    return '分支名包含非法字符';
  }
  return null;
}

/** 探测仓库是否存在、令牌是否有权限、以及是否私有 */
export async function testGithubConnection(cfg: GithubStorageConfig): Promise<{
  ok: boolean;
  message: string;
  isPrivate?: boolean;
  defaultBranch?: string;
  canWrite?: boolean;
}> {
  const invalid = validateGithubConfig(cfg);
  if (invalid) return { ok: false, message: invalid };

  try {
    const r = await fetch(`${API}/repos/${cfg.owner.trim()}/${cfg.repo.trim()}`, {
      headers: ghHeaders(cfg.token),
    });

    if (r.status === 401) {
      return { ok: false, message: '401：令牌无效或已过期，请重新生成一个细粒度 PAT' };
    }
    if (r.status === 404) {
      return {
        ok: false,
        message:
          '404：仓库不存在，或令牌没有该仓库的权限。\n' +
          '请在 GitHub → Settings → Developer settings → Personal access tokens 里\n' +
          '生成细粒度令牌，并在 Repository access 中选中这个仓库，\n' +
          '权限至少勾选 Contents: Read and write。',
      };
    }
    if (!r.ok) {
      const t = await r.text().catch(() => '');
      return { ok: false, message: `HTTP ${r.status} ${t.slice(0, 200)}` };
    }

    const repo = await r.json();
    const isPrivate = Boolean(repo.private);

    // 权限：看返回的 permissions 字段（细粒度令牌会带上）
    const canWrite =
      repo.permissions?.push === true ||
      repo.permissions?.admin === true ||
      repo.permissions?.maintain === true;

    const note = canWrite
      ? ''
      : '\n⚠ 未能确认写权限（仓库接口没有返回 push 权限）。上传若失败，请检查令牌的 Contents: Read and write。';

    return {
      ok: true,
      message:
        `连接成功：${repo.full_name}\n` +
        `可见性：${isPrivate ? '私有（显示图片需带令牌拉取）' : '公开（可直接用链接显示）'}\n` +
        `默认分支：${repo.default_branch}` +
        note,
      isPrivate,
      defaultBranch: repo.default_branch,
      canWrite,
    };
  } catch (e) {
    return {
      ok: false,
      message: `无法连接 GitHub：${e instanceof Error ? e.message : String(e)}`,
    };
  }
}

/** 上传时的文件名：按年月分目录，避免单目录文件过多 */
export function buildImagePath(cfg: GithubStorageConfig, ext: string, stamp = Date.now()): string {
  const prefix = (cfg.path || 'images').replace(/^\/+|\/+$/g, '');
  const d = new Date(stamp);
  const yyyy = d.getFullYear();
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const rand = Math.random().toString(36).slice(2, 8);
  return `${prefix}/${yyyy}/${mm}/${stamp}-${rand}.${ext}`;
}

export interface FitResult {
  dataUrl: string;
  bytes: number;
  width: number;
  height: number;
  format: string;
}

/**
 * 把图片压到目标体积以内。
 * 策略：先按较长边缩放到合理分辨率，再逐步降质量；仍超限就继续缩尺寸。
 */
export async function fitImageUnderLimit(
  dataUrl: string,
  maxKB: number,
  format: 'webp' | 'jpeg' | 'png' = 'webp',
): Promise<FitResult> {
  const img = await loadImage(dataUrl);
  const maxBytes = Math.max(64, maxKB) * 1024;

  // 分辨率档位：够用就好，避免上传巨大的图
  const sizes = [1536, 1280, 1024, 832, 640, 512];
  const qualities = format === 'png' ? [1] : [0.92, 0.85, 0.78, 0.7, 0.62, 0.55];

  let last: FitResult | null = null;

  for (const maxSide of sizes) {
    const scale = Math.min(1, maxSide / Math.max(img.width, img.height));
    const w = Math.max(1, Math.round(img.width * scale));
    const h = Math.max(1, Math.round(img.height * scale));
    const canvas = document.createElement('canvas');
    canvas.width = w;
    canvas.height = h;
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new Error('无法创建画布上下文');
    // jpeg 没有透明通道，先铺白底避免透明变黑
    if (format === 'jpeg') {
      ctx.fillStyle = '#ffffff';
      ctx.fillRect(0, 0, w, h);
    }
    ctx.drawImage(img, 0, 0, w, h);

    for (const q of qualities) {
      const out = canvas.toDataURL(`image/${format}`, q);
      const bytes = Math.round(((out.length - out.indexOf(',') - 1) * 3) / 4);
      last = { dataUrl: out, bytes, width: w, height: h, format };
      if (bytes <= maxBytes) return last;
    }
  }

  if (!last) throw new Error('图片压缩失败');
  return last;
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error('图片加载失败，可能不是有效的图片数据'));
    img.src = src;
  });
}

/** dataURL → 纯 base64 */
function toBase64(dataUrl: string): string {
  const i = dataUrl.indexOf(',');
  return i >= 0 ? dataUrl.slice(i + 1) : dataUrl;
}

/** dataURL 里实际有多少字节（base64 长度换算） */
function dataUrlBytes(dataUrl: string): number {
  const i = dataUrl.indexOf(',');
  if (i < 0) return dataUrl.length;
  const b64 = dataUrl.length - i - 1;
  return Math.round((b64 * 3) / 4);
}

/** 从 dataURL 头里认出格式，认不出就当 png */
function guessFormat(dataUrl: string): 'png' | 'jpeg' | 'webp' | 'gif' {
  const m = /^data:image\/([a-z+]+)/i.exec(dataUrl);
  const t = (m?.[1] || 'png').toLowerCase();
  if (t === 'jpeg' || t === 'jpg') return 'jpeg';
  if (t === 'webp') return 'webp';
  if (t === 'gif') return 'gif';
  return 'png';
}

/**
 * 上传一张图片到仓库。
 *
 * 默认**原图直传**：不缩放、不转格式、不重编码。
 *
 * 为什么改：以前固定压成 webp、长边不超过 1536、质量 0.92 —— 三重损失。
 * 画质被削一层，而且**元数据（生成参数、seed、模型）全被重编码抹掉**，
 * 以后想从图里反推提示词就没戏了。
 *
 * 现在只在用户**显式**设了 maxKB 且原图确实超过时才压，并且尽量保 PNG。
 * 想完全禁止压缩就把 maxKB 设成 0 或留空。
 */
export async function uploadImageToGithub(
  cfg: GithubStorageConfig,
  dataUrl: string,
  commitMessage?: string,
): Promise<GithubUploadResult> {
  const invalid = validateGithubConfig(cfg);
  if (invalid) throw new Error(invalid);

  const limit = Number(cfg.maxKB ?? 0);
  // 只有明确设了上限才考虑压缩；否则原样上传
  let fitted: { dataUrl: string; format: string } = {
    dataUrl,
    format: guessFormat(dataUrl),
  };
  if (limit > 0) {
    const originalKB = dataUrlBytes(dataUrl) / 1024;
    // 没超限就一个字都不动
    if (originalKB > limit) {
      const r = await fitImageUnderLimit(dataUrl, limit, cfg.format || 'png');
      fitted = { dataUrl: r.dataUrl, format: r.format };
    }
  }

  const ext = fitted.format === 'jpeg' ? 'jpg' : fitted.format;
  const path = buildImagePath(cfg, ext);
  const branch = cfg.branch?.trim() || 'main';

  const body = {
    message: commitMessage || `${cfg.commitPrefix || 'chore(images):'} 添加配图 ${path}`,
    content: toBase64(fitted.dataUrl),
    branch,
  };

  const r = await fetch(
    `${API}/repos/${cfg.owner.trim()}/${cfg.repo.trim()}/contents/${encodeURIComponent(path).replace(/%2F/g, '/')}`,
    {
      method: 'PUT',
      headers: { ...ghHeaders(cfg.token), 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    },
  );

  if (!r.ok) {
    const t = await r.text().catch(() => '');
    let hint = '';
    if (r.status === 401) hint = '\n💡 令牌无效或过期，请重新生成 PAT。';
    else if (r.status === 403) hint = '\n💡 权限不足或触发了速率限制。请确认令牌有 Contents: Read and write。';
    else if (r.status === 404) hint = '\n💡 仓库或分支不存在（分支名默认是 main，也可能是 master）。';
    else if (r.status === 422) hint = '\n💡 文件可能过大，或分支不存在。可把「压缩上限」调小一些。';
    throw new Error(`上传失败：HTTP ${r.status} ${t.slice(0, 250)}${hint}`);
  }

  const json = await r.json();
  const url = json.content?.download_url || buildRawUrl(cfg, path, branch);

  return {
    path,
    url,
    bytes: dataUrlBytes(fitted.dataUrl),
    commitSha: json.commit?.sha,
  };
}

/** 拼 raw 链接（公开仓库可直接访问） */
export function buildRawUrl(cfg: GithubStorageConfig, path: string, branch?: string): string {
  const b = branch || cfg.branch?.trim() || 'main';
  return `https://raw.githubusercontent.com/${cfg.owner.trim()}/${cfg.repo.trim()}/${b}/${path}`;
}

/**
 * 拉取私有仓库里的图片并转成 dataURL。
 * <img> 无法带 Authorization 头，所以私有仓库必须走这一步。
 */
export async function fetchGithubImageAsDataUrl(
  cfg: GithubStorageConfig,
  path: string,
): Promise<string> {
  const branch = cfg.branch?.trim() || 'main';
  const r = await fetch(
    `${API}/repos/${cfg.owner.trim()}/${cfg.repo.trim()}/contents/${encodeURIComponent(path).replace(/%2F/g, '/')}?ref=${encodeURIComponent(branch)}`,
    { headers: ghHeaders(cfg.token) },
  );
  if (!r.ok) {
    throw new Error(`拉取图片失败：HTTP ${r.status}`);
  }
  const j = await r.json();
  const b64 = (j.content || '').replace(/\s+/g, '');
  if (!b64) throw new Error('仓库返回的内容为空');
  const ext = (path.split('.').pop() || 'png').toLowerCase();
  const mime = ext === 'jpg' || ext === 'jpeg' ? 'image/jpeg' : `image/${ext}`;
  return `data:${mime};base64,${b64}`;
}

/** 删除仓库里的图片（需要 Contents 写权限） */
export async function deleteGithubImage(
  cfg: GithubStorageConfig,
  path: string,
): Promise<void> {
  const branch = cfg.branch?.trim() || 'main';
  const url = `${API}/repos/${cfg.owner.trim()}/${cfg.repo.trim()}/contents/${encodeURIComponent(path).replace(/%2F/g, '/')}?ref=${encodeURIComponent(branch)}`;

  const head = await fetch(url, { headers: ghHeaders(cfg.token) });
  if (!head.ok) throw new Error(`读取文件信息失败：HTTP ${head.status}`);
  const info = await head.json();

  const r = await fetch(url.split('?')[0], {
    method: 'DELETE',
    headers: { ...ghHeaders(cfg.token), 'Content-Type': 'application/json' },
    body: JSON.stringify({
      message: `${cfg.commitPrefix || 'chore(images):'} 删除配图 ${path}`,
      sha: info.sha,
      branch,
    }),
  });
  if (!r.ok) {
    const t = await r.text().catch(() => '');
    throw new Error(`删除失败：HTTP ${r.status} ${t.slice(0, 200)}`);
  }
}
