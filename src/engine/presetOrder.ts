/**
 * 预设提示词排序（ST 兼容层的唯一权威）
 *
 * 为什么单独一个文件：
 *   - `prompts[]` 只是「提示词库」，数组顺序是历史插入顺序，**不代表发送顺序**。
 *   - 真正决定顺序与启停的是 `prompt_order[]`，它按 `character_id` 分组，
 *     每组里 `order[]` 才是有序表；不在表里的 prompt 在 ST 里显示为
 *     「未激活提示词」，**不参与生成**。
 *
 * 以前 assembler 走 parsecard 的 `listPromptEntries()`（默认把未激活项追加到末尾），
 * 资产页则直接遍历 `prompts[]`。前者会把「未激活但 enabled=true」的条目发出去，
 * 后者顺序与 ST 不一致 —— 这就是「导入后顺序变了 / 有些条目像被藏了」的来源。
 * 现在两边都从这里取，只有一套规则。
 */

/** ST 的全局排序组 id；与 parsecard 的 DEFAULT_PROMPT_ORDER_CHARACTER_ID 一致 */
export const GLOBAL_ORDER_CHARACTER_ID = 100001;

export interface RankedPrompt {
  /** 原始 prompt 对象 */
  prompt: any;
  identifier: string;
  /** 在 prompt_order 里：会按顺序参与生成 */
  ordered: boolean;
  /** 是否启用（有序区以 order[].enabled 为准；未激活恒为 false） */
  enabled: boolean;
  /** 在 prompts[] 里的下标（仅调试用，写回一律按 identifier） */
  index: number;
  /** 有序区里的序号，从 1 开始；未激活为 0 */
  position: number;
}

/** 兼容 parsecard 实例（.prompts / .promptOrder getter）与普通 JSON（.data.prompts） */
export function readPresetPrompts(asset: any): any[] {
  const list = asset?.prompts ?? asset?.data?.prompts;
  return Array.isArray(list) ? list : [];
}

function readOrderRoot(asset: any): any {
  return (
    asset?.promptOrder ??
    asset?.prompt_order ??
    asset?.data?.prompt_order ??
    asset?.data?.promptOrder ??
    null
  );
}

function orderId(entry: any): string {
  if (typeof entry === 'string') return entry;
  return String(entry?.identifier ?? entry?.id ?? '');
}

/**
 * 取「正在生效」的那一组 order 数组（**返回原引用**，可按序修改后保存）。
 *
 * 组选择规则，按优先级：
 *   1. character_id === 100001 的全局组（ST 推荐的预设形态）
 *   2. 第一个非空组
 *   3. 第一个组（全空时返回它，新建条目要往这里追加）
 *
 * 兼容三种历史形态：
 *   a. [{ character_id, order: [...] }]   ← ST 1.12+
 *   b. { '100001': [...] }                ← 部分分支
 *   c. { '100001': ['main', ...] }        ← 老预设，纯字符串数组
 */
export function getActivePromptOrder(asset: any): any[] | null {
  const root = readOrderRoot(asset);
  if (!root) return null;

  if (Array.isArray(root)) {
    const groups = root.filter((g: any) => g && Array.isArray(g.order));
    if (!groups.length) return null;
    const nonEmpty = groups.filter((g: any) => g.order.length > 0);
    const pool = nonEmpty.length ? nonEmpty : groups;
    const global = pool.find((g: any) => String(g.character_id) === String(GLOBAL_ORDER_CHARACTER_ID));
    return (global ?? pool[0]).order as any[];
  }

  if (typeof root === 'object') {
    const keys = Object.keys(root).filter((k) => Array.isArray((root as any)[k]));
    if (!keys.length) return null;
    const nonEmpty = keys.filter((k) => (root as any)[k].length > 0);
    const pool = nonEmpty.length ? nonEmpty : keys;
    const key = pool.find((k) => String(k) === String(GLOBAL_ORDER_CHARACTER_ID)) ?? pool[0];
    return (root as any)[key] as any[];
  }

  return null;
}

/**
 * 把预设摊平成「按发送顺序的列表」。
 *
 * 有序区在前（严格跟 prompt_order），未激活区在后（保持 prompts[] 里的相对顺序，
 * 与 ST / Tavo 的「未激活提示词」区一致）。调用方拿 `ordered` 决定怎么分区显示。
 */
export function listRankedPrompts(asset: any): RankedPrompt[] {
  const prompts = readPresetPrompts(asset);
  const byId = new Map<string, { prompt: any; index: number }>();
  prompts.forEach((p: any, i: number) => {
    const id = String(p?.identifier || `#${i}`);
    if (!byId.has(id)) byId.set(id, { prompt: p, index: i });
  });

  const order = getActivePromptOrder(asset);
  const out: RankedPrompt[] = [];
  const seen = new Set<string>();

  if (order) {
    for (const entry of order) {
      const id = orderId(entry);
      const hit = byId.get(id);
      if (!hit) continue;
      if (seen.has(id)) continue; // 排序表里重复出现只认第一条
      seen.add(id);
      // 有序区的启停以 order[].enabled 为准（ST 的唯一权威）；
      // 老格式纯字符串数组没有 enabled，回退到 prompt 自身的字段。
      const enabled =
        typeof entry === 'string'
          ? hit.prompt?.enabled !== false
          : entry.enabled !== false;
      out.push({
        prompt: hit.prompt,
        identifier: id,
        ordered: true,
        enabled,
        index: hit.index,
        position: out.length + 1,
      });
    }
  }

  for (const [id, hit] of byId) {
    if (seen.has(id)) continue;
    out.push({
      prompt: hit.prompt,
      identifier: id,
      ordered: false,
      // 未激活 = 不参与生成，无论 prompts[].enabled 写的是什么
      enabled: false,
      index: hit.index,
      position: 0,
    });
  }

  return out;
}

/**
 * 在排序表里把一条 prompt 上移/下移一格。
 * 返回 false 表示没动（找不到、已在边界、或预设没有排序表）。
 * 只交换 order 数组，绝不重排 prompts[] —— 后者是「库」，动它会丢原始顺序。
 */
export function movePromptInOrder(asset: any, identifier: string, delta: -1 | 1): boolean {
  const order = getActivePromptOrder(asset);
  if (!order) return false;
  const from = order.findIndex((e: any) => orderId(e) === identifier);
  if (from < 0) return false;
  const to = from + delta;
  if (to < 0 || to >= order.length) return false;
  const tmp = order[from];
  order[from] = order[to];
  order[to] = tmp;
  return true;
}

/**
 * 把「未激活」条目接进排序表（追加到末尾并启用）。
 * 对应 ST / Tavo 里把提示词从「未激活」区拖进主列表。
 */
export function activatePromptInOrder(asset: any, identifier: string): boolean {
  const order = getActivePromptOrder(asset);
  if (!order) return false;
  if (order.some((e: any) => orderId(e) === identifier)) {
    // 已在表里：只把它打开，位置不动
    const entry: any = order.find((e: any) => orderId(e) === identifier);
    if (typeof entry === 'string') return false;
    if (entry.enabled === true) return false;
    entry.enabled = true;
  } else if (order.length && typeof order[0] === 'string') {
    order.push(identifier);
  } else {
    order.push({ identifier, enabled: true });
  }
  // 两处同步：prompts[].enabled 是部分工具/旧版本的读取点
  const prompt = readPresetPrompts(asset).find(
    (p: any, i: number) => String(p?.identifier || `#${i}`) === identifier,
  );
  if (prompt && typeof prompt === 'object') prompt.enabled = true;
  return true;
}
