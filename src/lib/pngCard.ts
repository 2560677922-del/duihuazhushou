/**
 * 从 PNG 图片里读出角色卡 / 世界书。
 *
 * 背景：SillyTavern 生态里，角色卡经常是**一张 PNG 图片**，
 * 卡的数据藏在图片的 tEXt chunk 里（base64 之后塞进去的）。
 * 这样预览图和卡是一体的，分享时不会"图文走散"。
 * 我们之前只认 JSON 文本，用户丢一张这种 PNG 进来就解析失败。
 *
 * 标准（按 character-card-spec-v3 + ST 的实现）：
 *   · V1 / V2 → tEXt chunk，关键字 `chara`
 *   · V3      → tEXt chunk，关键字 `ccv3`（同时通常也带一份 `chara` 兼容旧工具）
 * 数据都是 "utf-8 → base64" 的 JSON 字符串。
 *
 * 解析本身交给 parsecard（它已经实现了完整的 chunk 扫描 / 版本识别 /
 * V2→V3 归一化），这里只负责"把图片变成它认识的东西"，
 * 以及**顺带把卡里内嵌的世界书也取出来**。
 */
import { CharacterCard, readJsonFromPNG, hasCharaInPNG } from 'parsecard';

export interface PngCardReadResult {
  ok: boolean;
  /** 角色卡原始 JSON 对象（已归一化到 V2/V3 形状，可直接喂 importCharacter） */
  card?: unknown;
  /**
   * 卡里内嵌的世界书（`character_book`）。
   *
   * 很多卡把世界观设定放在这里而不是独立文件 —— 只导角色卡会丢掉设定，
   * 所以识别到就一并交给调用方（当成一个世界书存进去）。
   */
  characterBook?: unknown;
  /** 卡的名字，用于给内嵌世界书起名 */
  name?: string;
  /** 失败原因（ok=false 时给出人话） */
  error?: string;
}

/** 这张图看起来是不是 PNG？（按文件头判断，不看扩展名 —— 扩展名经常是错的） */
export function looksLikePng(bytes: Uint8Array): boolean {
  if (bytes.length < 8) return false;
  const sig = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  return sig.every((b, i) => bytes[i] === b);
}

/**
 * 从 PNG 字节里读角色卡。
 *
 * @param input 图片字节（ArrayBuffer 或 Uint8Array）
 */
export function readCardFromPng(input: Uint8Array | ArrayBuffer): PngCardReadResult {
  const bytes = input instanceof Uint8Array ? input : new Uint8Array(input);

  if (!looksLikePng(bytes)) {
    return { ok: false, error: '这不是 PNG 文件（文件头不对）' };
  }

  try {
    /*
     * 先问一句"有没有卡" —— 没有的话给出更准确的提示。
     * 直接 fromPNG 也会返回 null，但分不清是"没卡"还是"卡坏了"。
     */
    if (!hasCharaInPNG(bytes)) {
      return {
        ok: false,
        error: '这张 PNG 里没有角色卡数据（缺 chara / ccv3 块）—— 它可能只是一张普通的图',
      };
    }

    const card = CharacterCard.fromPNG(bytes);
    if (!card) {
      return { ok: false, error: 'PNG 里有卡数据，但解析失败（可能是加密或非标准导出）' };
    }

    const raw = card.toJSON() as Record<string, unknown> & {
      data?: Record<string, unknown>;
    };
    /* character_book 在 V2/V3 里位于 data 下 */
    const data = (raw?.data ?? {}) as Record<string, unknown>;
    const book = data.character_book ?? (raw as Record<string, unknown>).character_book;
    const name = String(data.name ?? raw?.name ?? card.name ?? '').trim();

    return { ok: true, card: raw, characterBook: book || undefined, name };
  } catch (e) {
    return { ok: false, error: `解析 PNG 角色卡出错：${e instanceof Error ? e.message : String(e)}` };
  }
}

/**
 * 兜底读法：直接用 parsecard 的底层函数取 JSON 字符串。
 *
 * `CharacterCard.fromPNG` 会做完整归一化，但遇到**非标准导出**
 * （比如只有 ccv3、或者 JSON 结构怪）时可能返回 null。
 * 这时把原始 JSON 字符串捞出来自己 JSON.parse，
 * 能救回一批"卡其实在、只是解析器不认"的情况。
 */
export function readRawJsonFromPng(input: Uint8Array | ArrayBuffer): unknown | null {
  try {
    const text = readJsonFromPNG(input);
    if (!text) return null;
    return JSON.parse(text);
  } catch {
    return null;
  }
}

/** 把文件读成字节（PNG 是二进制，不能用 file.text()） */
export function readFileAsBytes(file: File): Promise<Uint8Array> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(new Uint8Array(reader.result as ArrayBuffer));
    reader.onerror = () => reject(new Error('读取文件失败'));
    reader.readAsArrayBuffer(file);
  });
}
