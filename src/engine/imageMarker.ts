// 文本内插图标记：让大模型自己决定在哪里配图。
// ---------------------------------------------------------------------------
// 约定：模型在正文中插入 [[IMG: 画面描述]] 标记，应用据此在其位置生成图片。
//
// 关键设计——隔离：
//  1. assemblePrompt() 在把消息交给大模型之前，会剥掉所有标记。
//     所以模型写的标记永远不会被回喂给它自己，也不会占 token、不会干扰上下文。
//     这一点是硬保证，不依赖调用方是否记得清理。
//  2. 图片本身以附件形式单独存放（images 数组），从不进入 content，
//     因此导出/重新组装时都不会带上图片数据。
//  3. 渲染时按标记位置把图片插进正文；标记本身不显示。

export interface ImageAttachment {
  id: string;
  /** 在正文中的插入位置，对应 markdown 渲染后的段落序号 */
  markdownIndex: number;
  /** 模型给出的画面描述（可作为生图提示词） */
  description: string;
  /**
   * 生成这张图时**实际使用的完整提示词**。
   *
   * 为什么单独存一份：`description` 是给界面显示和正文标记用的，
   * 生成时会被截断到 120 字（见 useQuickImage）。而"重新生图"要的是
   * **原样把这条提示词再发一次** —— 拿截断过的去重出，画面就完全不是一张了。
   */
  prompt?: string;
  /**
   * 图片资产库的 id —— **新流程下会话里只存这个**。
   *
   * 图片本体在图片资产库（lib/imageAssets.ts）。会话数据会同步、会落盘、
   * 会进 GitHub 备份，里面塞图片会让体积随图量一路涨、读取越来越慢。
   * 只留引用就完全没这个问题。
   *
   * 老数据没有这个字段，会退回用 dataUrl（见 ContentWithImages 的解析顺序）。
   */
  assetId?: string;
  /**
   * 可显示的图片地址。
   * 新流程下基本为空（只有"刚生成、还没入资产库"的那一瞬间会有），
   * 老数据靠它显示。存 GitHub 时也用它放链接。
   */
  dataUrl?: string;
  /** 存到 GitHub 仓库时的仓库内路径，用于删除与私有仓库取图 */
  remotePath?: string;
  /**
   * 远端的可访问链接（备份位置）。
   *
   * 只作记录，**显示一律用 dataUrl** —— 远端域名可能被墙或 DNS 解析不了
   * （raw.githubusercontent.com 在部分网络下就是这样），
   * 拿它当显示地址会直接变成占位符。
   */
  remoteUrl?: string;
  /** 图片实际存放位置 */
  storage?: 'browser' | 'github';
  /** 上传到远端失败时的原因（此时仍保留本地图） */
  uploadError?: string;
  /** 生成失败的原因 */
  error?: string;
  /** 采用的生图配置名，便于追溯 */
  configName?: string;
  createdAt: number;

  /**
   * 历史版本（点「重新生图」之前的那些图）。
   *
   * 为什么要留：重新生图是"不满意再抽一次"，但抽卡有随机性 ——
   * 新的一张很可能**还不如旧的那张**（换了种子、换了模型、或提示词被
   * 大模型改写后走偏了）。之前是直接覆盖，旧图当场销毁，用户只能认了。
   *
   * 现在的语义：顶层字段（dataUrl / remotePath / prompt …）始终是
   * **当前显示的那一张**，切换版本就是把选中的版本换到顶层、旧的挪进这里。
   * 这样所有既有渲染逻辑（读 attachment.dataUrl）完全不用改。
   *
   * 顺序：最新的在前（和"最近生成的在最上面"的直觉一致）。
   * 上限见 MAX_IMAGE_VERSIONS，超了丢最老的，避免无限膨胀。
   */
  versions?: ImageVersion[];
  /** 当前显示的是哪一版（对应 versions[].id）；顶层即当前版时可以不填 */
  currentVersionId?: string;
}

/** 一张历史图 */
export interface ImageVersion {
  /** 稳定 id，切换时用来定位 */
  id: string;
  /**
   * 这一版在**图片资产库**里的 id。
   *
   * ★ 必须带着走：现在图片本体只存资产库，附件顶层的 dataUrl 在
   *   "已入资产库"这种情况下是**空的**（见 useQuickImage）。
   *   原来这里没有 assetId，于是 snapshotVersion 抓出来的历史版本
   *   是一张"没有 dataUrl、也没有 assetId"的空壳 ——
   *   切回旧版直接变成占位符，等于"重新生图之后旧图就废了"。
   */
  assetId?: string;
  dataUrl?: string;
  remotePath?: string;
  remoteUrl?: string;
  storage?: 'browser' | 'github';
  /** 该版本用的提示词（版本之间可能不同） */
  prompt?: string;
  configName?: string;
  createdAt: number;
}

/**
 * 一张图最多留几版。
 *
 * 每版都是一张完整图片（几百 KB～1MB）。留太多会让单条消息膨胀到
 * 十几 MB，手机存不下、导出也变慢。5 版够"抽几次挑一张"用了。
 */
export const MAX_IMAGE_VERSIONS = 5;

/** 从附件当前状态抽出"这一版"的快照 */
export function snapshotVersion(att: ImageAttachment): ImageVersion {
  return {
    id: att.currentVersionId || att.id + '-v' + att.createdAt,
    assetId: att.assetId,
    dataUrl: att.dataUrl,
    remotePath: att.remotePath,
    remoteUrl: att.remoteUrl,
    storage: att.storage,
    prompt: att.prompt,
    configName: att.configName,
    createdAt: att.createdAt,
  };
}

/**
 * 把新生成的一张并进附件：旧的那张进历史，新的成为当前显示。
 *
 * 不新增附件（那会让正文里多出一个标记），而是原地替换 + 保留旧版。
 */
export function pushImageVersion(
  att: ImageAttachment,
  fresh: ImageVersion,
): ImageAttachment {
  const current = snapshotVersion(att);
  // 旧的在前面（最新在前），新的排最前
  const history = [current, ...(att.versions || [])]
    // 去重：同一张图重复生成（比如内容完全一样）不该占两格
    .filter((v, i, arr) => arr.findIndex((x) => x.id === v.id) === i)
    .slice(0, MAX_IMAGE_VERSIONS);

  return {
    ...att,
    /*
     * assetId 必须跟着**新版本**走，不能靠 `...att` 带过来的旧值。
     * 旧代码就是漏了这一步：重新生图后顶层 assetId 还指着原来那张，
     * 而那张可能已经被删了 —— 刷新一次图就没了。
     */
    assetId: fresh.assetId ?? att.assetId,
    dataUrl: fresh.dataUrl,
    remotePath: fresh.remotePath,
    remoteUrl: fresh.remoteUrl,
    storage: fresh.storage,
    // 顶层提示词跟着当前版走（再次"重新生图"要基于当前这张的描述）
    prompt: fresh.prompt ?? att.prompt,
    configName: fresh.configName ?? att.configName,
    createdAt: fresh.createdAt,
    currentVersionId: fresh.id,
    versions: history,
  };
}

/**
 * 全部可选的版本，**按生成时间从新到旧固定排序**。
 *
 * 为什么要固定顺序（而不是"当前版排最前"）：
 *   如果当前版永远排第一，切换之后所有缩略图位置都会往前挪一格 ——
 *   用户刚看到的"第三张"变成了"第二张"，再点一下就点错了。
 *   实测反馈过这种错乱感。
 *
 *   按 createdAt 排就没有这个问题：每张缩略图的位置**永远不变**，
 *   只有高亮框在移动 —— 这也是相册/图片编辑器的通行做法。
 */
export function allVersionsOf(att: ImageAttachment): ImageVersion[] {
  const cur = snapshotVersion(att);
  const rest = (att.versions || []).filter((v) => v.id !== cur.id);
  return [cur, ...rest].sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
}

/** 切到某一版：把它换到顶层，其余进历史 */
export function switchToVersion(
  att: ImageAttachment,
  versionId: string,
): ImageAttachment {
  const all = allVersionsOf(att);
  const target = all.find((v) => v.id === versionId);
  if (!target) return att;
  const rest = all.filter((v) => v.id !== versionId).slice(0, MAX_IMAGE_VERSIONS - 1);

  return {
    ...att,
    assetId: target.assetId ?? att.assetId,
    dataUrl: target.dataUrl,
    remotePath: target.remotePath,
    remoteUrl: target.remoteUrl,
    storage: target.storage,
    prompt: target.prompt ?? att.prompt,
    configName: target.configName ?? att.configName,
    createdAt: target.createdAt,
    currentVersionId: target.id,
    versions: rest,
  };
}

/** 匹配 [[IMG: 描述]] / ［［IMG: 描述］］（含全角方括号） */
export const IMAGE_MARKER_RE = /\[\[\s*IMG\s*[:：]\s*([^\]]+?)\s*\]\]/gi;

/** 供提示词里展示给模型的写法说明 */
export const IMAGE_MARKER_SYNTAX = '[[IMG: 画面描述]]';

export interface ParsedMarker {
  description: string;
  /** 该标记位于第几个段落（用于渲染时定位） */
  markdownIndex: number;
  /** 在原文中的字符起点 */
  index: number;
}

/**
 * 找出正文里所有插图标记，并算出它们所在的段落序号。
 *
 * 关键：段落序号必须和**渲染时**的编号一致，否则插图会错位。
 * 渲染时（buildContentBlocks）把文本按空行切段，其中"去掉标记后是空白"
 * 的段会被整个丢掉、不占编号。所以这里的规则是：
 *
 *   段落编号 = 在它之前有多少个"去了标记后仍有内容"的段落
 *
 * 之前是按原文直接数空行，把"只有标记的那一段"也算了一个编号，
 * 于是标记后面的所有插图都比实际位置多 1 —— 表现就是插图总是插到下一段。
 */
export function parseImageMarkers(text: string): ParsedMarker[] {
  const out: ParsedMarker[] = [];
  if (!text) return out;

  // 按空行切段，同时记住每段在原文里的起止偏移
  const paras: { start: number; end: number; text: string }[] = [];
  const re = /\n\s*\n/g;
  let last = 0;
  let mm: RegExpExecArray | null;
  while ((mm = re.exec(text)) !== null) {
    paras.push({ start: last, end: mm.index, text: text.slice(last, mm.index) });
    last = mm.index + mm[0].length;
  }
  paras.push({ start: last, end: text.length, text: text.slice(last) });

  /** 每一段去掉标记后是否还有实质内容 */
  const hasContent = paras.map((p) => stripImageMarkers(p.text).trim().length > 0);

  /** 段的渲染编号（只数有内容的段） */
  const renderNo: number[] = [];
  let counter = -1;
  for (let i = 0; i < paras.length; i++) {
    if (hasContent[i]) counter++;
    renderNo.push(counter);
  }

  IMAGE_MARKER_RE.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = IMAGE_MARKER_RE.exec(text)) !== null) {
    // 标记落在哪一段
    let pi = paras.findIndex((p) => m!.index >= p.start && m!.index < p.end);
    if (pi < 0) pi = paras.length - 1;
    // 用"前一段"的编号：标记若是单独成段，它跟在哪个内容段之后就属于那个编号；
    // 若标记和文字同段，就用本段编号。
    let idx = renderNo[pi];
    if (!hasContent[pi]) {
      // 落在纯标记段：归到它前面最近的一个内容段
      for (let i = pi; i >= 0; i--) {
        if (hasContent[i]) {
          idx = renderNo[i];
          break;
        }
      }
      if (idx < 0) idx = 0;
    }
    out.push({
      description: m[1].trim(),
      markdownIndex: Math.max(0, idx),
      index: m.index,
    });
  }
  return out;
}

/**
 * 剥掉所有插图标记。
 * 必须用于任何要送给大模型、或要统计 token 的文本。
 *
 * 另外兜一层：早期版本会把图片以 `![](data:image/png;base64,...)` 的形式
 * 写进正文，那也是几十万字符。这里一并把它们去掉，
 * 避免历史数据里残留的内嵌图片把上下文撑爆。
 */
export function stripImageMarkers(text: string): string {
  if (!text) return text;
  return text
    .replace(IMAGE_MARKER_RE, '')
    // 内嵌 base64 图片的 markdown：整段删掉
    .replace(/!\[[^\]]*\]\(\s*data:image\/[a-z+]+;base64,[A-Za-z0-9+/=\s]{100,}\s*\)/gi, '')
    .replace(/[ \t]+\n/g, '\n') // 清掉标记留下的行尾空格
    .replace(/\n{3,}/g, '\n\n'); // 合并多余空行
}

/** 文本里是否含有插图标记 */
export function hasImageMarker(text: string): boolean {
  if (!text) return false;
  IMAGE_MARKER_RE.lastIndex = 0;
  return IMAGE_MARKER_RE.test(text);
}

/**
 * 把正文切成「段落块」，并在标记所在位置插入图片占位。
 * 返回的块序列供渲染层逐块渲染，图片块与文本块交替。
 */
export type ContentBlock =
  | { kind: 'text'; text: string; index: number }
  | { kind: 'image'; attachment?: ImageAttachment; description: string; index: number };

export function buildContentBlocks(
  text: string,
  attachments: ImageAttachment[] = [],
): ContentBlock[] {
  const blocks: ContentBlock[] = [];
  if (!text) return blocks;

  const parts = text.split(/\n\s*\n/);
  let index = 0;
  /** 已经用掉的配图 id，保证一张图不会被挂到两个标记上 */
  const used = new Set<string>();
  /** 按出现顺序逐个配图：这是主匹配方式，见下 */
  const queue = [...attachments];
  let consumed = 0;

  /**
   * 给一个标记挑配图。
   *
   * 主方式是**按顺序**配对：parseImageMarkers 和这里都以同样的从左到右
   * 顺序遍历标记，所以第 n 个标记就该配第 n 张图。
   *
   * 关键：只要还有没用过的配图，就**一定要返回一张**。
   * 之前这里在"编号差太远"时会走到 return undefined，
   * 结果标记位置空着、图被兜底逻辑挪到消息末尾 ——
   * 表现就是"插图位置不对"。宁可位置略有偏差，也不能不显示。
   *
   * markdownIndex 只作为"更精确匹配"的加分项，不作为否决条件。
   */
  function pick(desc: string, atIndex: number): ImageAttachment | undefined {
    if (consumed >= queue.length) {
      // 顺序队列用完了，看看还有没有漏网的
      const spare = attachments.find((a) => !used.has(a.id));
      if (spare) {
        used.add(spare.id);
        return spare;
      }
      return undefined;
    }

    // 先看顺序上的那一张
    while (consumed < queue.length && used.has(queue[consumed].id)) consumed++;
    const byOrder = consumed < queue.length ? queue[consumed] : undefined;
    if (!byOrder) return undefined;

    // 编号 + 描述都吻合的（更可靠的匹配），优先用它
    const exact = attachments.find(
      (a) => !used.has(a.id) && a.markdownIndex === atIndex && a.description === desc,
    );
    if (exact) {
      used.add(exact.id);
      // 如果顺位那位就是它，指针也往前走
      if (byOrder.id === exact.id) consumed++;
      return exact;
    }

    // 否则就用顺序上的那一张 —— 位置可能差一点，但一定要显示出来
    used.add(byOrder.id);
    consumed++;
    return byOrder;
  }

  for (const part of parts) {
    const markers = parseImageMarkers(part);
    if (markers.length === 0) {
      // 编号只给**有内容**的段落
      if (part.trim()) {
        blocks.push({ kind: 'text', text: part, index });
        index++;
      }
      continue;
    }

    // 同一段里可能有多个标记：按标记把文本切开，交替插入图片块
    let cursor = 0;
    IMAGE_MARKER_RE.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = IMAGE_MARKER_RE.exec(part)) !== null) {
      const before = part.slice(cursor, m.index);
      if (before.trim()) blocks.push({ kind: 'text', text: before, index });
      const description = m[1].trim();
      blocks.push({ kind: 'image', attachment: pick(description, index), description, index });
      cursor = m.index + m[0].length;
    }
    const rest = part.slice(cursor);
    if (rest.trim()) blocks.push({ kind: 'text', text: rest, index });
    index++;
  }

  /*
   * 兜底：把没被任何标记用掉的配图补在末尾。
   *
   * 为什么需要：图片有两条来源 ——
   *   1. AI 自己插的 [[IMG: 描述]] 标记（自动配图）
   *   2. 用户点「🎨 配图」直接生成（没有标记）
   * 第 2 种如果只挂在 attachments 上、正文里没有标记，
   * 就会"图片生成了、图库里有、聊天里却看不到" —— 这里补渲染出来。
   */
  for (const a of attachments) {
    if (used.has(a.id)) continue;
    used.add(a.id);
    blocks.push({
      kind: 'image',
      attachment: a,
      description: a.description || '配图',
      index: Math.max(0, index - 1),
    });
  }

  return blocks;
}

/**
 * 把文本里内嵌的 base64 图片抽出来。
 *
 * 为什么需要：用户从别处复制图片时，粘进来的常常是整段
 * `![alt](data:image/png;base64,...)` —— 一张就 1~2 MB。后果：
 *   1. 落盘文件从 1.5 MB 涨到 5 MB
 *   2. 每次渲染都要解析几百万字符的字符串
 *   3. 虽然 stripImageMarkers 会把它剥掉（不会发给模型），但它白占存储
 *
 * 这里换成真正的附件 + [[IMG: 描述]] 标记：图仍显示在原位置，
 * 文本不再含 base64，图也交给图片存储区（IndexedDB）。
 */
export interface ExtractedImage {
  description: string;
  dataUrl: string;
}

export interface ExtractionResult {
  text: string;
  images: ExtractedImage[];
}

const MD_IMAGE_RE = /!\[([^\]]*)\]\((data:image\/[a-z+]+;base64,[A-Za-z0-9+/=]+)\)/gi;

export function extractInlineImages(text: string): ExtractionResult {
  if (!text || !text.includes('data:image')) return { text, images: [] };
  const images: ExtractedImage[] = [];
  const out = text.replace(MD_IMAGE_RE, (_whole, alt, dataUrl) => {
    const desc = String(alt || '配图').trim() || '配图';
    images.push({ description: desc, dataUrl: String(dataUrl) });
    // 留一个标记占位，渲染时在这个位置出图
    return `\n\n[[IMG: ${desc}]]\n\n`;
  });
  return { text: out.replace(/\n{3,}/g, '\n\n').trim(), images };
}

/** 给提示词用的一段说明，告诉模型可以按需插图 */
export function imageMarkerInstruction(enabled: boolean): string {  if (!enabled) return '';
  return (
    '\n\n【配图指引】你可以在正文合适的位置自行插入配图标记，写法是 ' +
    IMAGE_MARKER_SYNTAX +
    '。标记要单独成行、放在需要配图的那个段落之后。' +
    '\n- 描述用英文 Danbooru 风格标签，写清画面主体、动作、环境、光线与镜头。' +
    '\n- 只在画面感强、值得配图的位置插入，一段最多一个，一次回复不超过 3 个。' +
    '\n- 不需要配图时就完全不要输出任何标记。' +
    '\n- 标记只是给你自己定位用的，读者看不到它，请不要在正文里解释它。'
  );
}
