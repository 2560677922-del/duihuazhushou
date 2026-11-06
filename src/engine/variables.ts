// 变量引擎：SillyTavern 变量宏的**扁平子集**
// ===========================================================================
// 给下一个接手的（和其他 AI）说明，动手前务必读完：
//
// 【为什么存在】
// ST 的预设、世界书、正则里大量使用 {{getvar::xxx}} 读状态。
// 没有变量表时这些宏会被原样丢给大模型，或显示成一行乱码。
//
// 【兼容边界 —— 只做语法级，不做引擎级】
// 参考的是 SillyTavern release 分支的 Macros 文档。新版默认开启
// "Experimental Macro Engine"，它支持**嵌套宏**、scoped 标签、{{if}}、
// {{.x}} / {{$x}} 简写与 ++ / += / || 等操作符。
//
// ★ 本项目明确**不实现**以上任何一项（用户要求：不搞嵌套）。理由：
//   1. 嵌套解析必然要递归或循环展开，A→B→A 互相引用就会死循环。
//      presetContent.ts 里早有同款教训："两遍够用且安全"。
//   2. 显示路径在流式生成时每秒重渲染数次；带副作用的宏一旦允许
//      嵌套/重入，计数器会被重复累加，数值乱跳而且极难排查。
//
// 所以"适配傻酒馆"= 别人写 {{getvar::x}} / {{setvar::x::v}} 能读能写、
// 能导入导出；不是把 ST 的宏引擎整个搬进来。见 README 第十节。
//
// 【三条硬红线】
//   1. 存储扁平：只有 session / global 两张**平级**表，无作用域链、无继承。
//      同名时 session 优先由调用方传表顺序保证（getvar 只读 session）。
//   2. 解析单次：一次 String.replace，值**原样插入、不二次解析**。
//   3. 语法禁嵌套：VAR_RE 的参数部分写死 [^{}]，参数里出现任何花括号
//      整条宏就不匹配、原样保留 —— 从语法层面杜绝 {{getvar::{{char}}}}。
//
// 【读与写必须分开】
//   readVariables()        纯只读；遇到写宏抹成空串，**绝不改状态**。
//                          用于显示路径、提示词组装、正则替换串。
//   applyVariableWrites()  执行写宏并返回新表；只在两个入口各调用一次：
//                          用户发送时、AI 回复落库时。见 Adventure.vue。
//   绝不把写宏放进渲染路径。

export type VariableScope = 'session' | 'global';

/** 两张平级的扁平表。值一律是字符串，数值语义只在 add/inc/dec 里临时解释 */
export interface VariableTables {
  /** 当前会话（ST 的 local variable，存在 chat metadata） */
  session: Record<string, string>;
  /** 全局（ST 的 global variable，存在 settings.json） */
  global: Record<string, string>;
}

export interface VariableWriteResult {
  text: string;
  tables: VariableTables;
  /** 是否真的改了表（供调用方决定要不要存盘 / 让缓存失效） */
  changed: boolean;
}

export function emptyVariableTables(): VariableTables {
  return { session: {}, global: {} };
}

/**
 * 只认 `{{名字::参数}}` 这一种形式。
 *
 * [^{}] 是"不搞嵌套"的关键：参数里出现花括号就整条不匹配，
 * 也不会去吃别人的宏。名字限定字母，避免误伤 {{roll::1d20}} 之类
 * 由别的系统处理的宏 —— 不认识的宏一律原样保留。
 */
const VAR_RE = /\{\{\s*([a-z]+)\s*::\s*([^{}]*?)\s*\}\}/gi;

const READ_NAMES = new Set([
  'getvar', 'getglobalvar', 'hasvar', 'hasglobalvar',
]);
const WRITE_NAMES = new Set([
  'setvar', 'setglobalvar',
  'addvar', 'addglobalvar',
  'incvar', 'decvar', 'incglobalvar', 'decglobalvar',
  'deletevar', 'deleteglobalvar',
]);
const ALL_NAMES = new Set([...READ_NAMES, ...WRITE_NAMES]);

/** 给设置页语法速查用 */
export const VARIABLE_MACRO_HELP: { macro: string; desc: string }[] = [
  { macro: '{{getvar::名称}}', desc: '读会话变量，未定义为空串' },
  { macro: '{{getglobalvar::名称}}', desc: '读全局变量' },
  { macro: '{{hasvar::名称}}', desc: '是否存在，返回 true / false' },
  { macro: '{{setvar::名称::值}}', desc: '写会话变量（发送 / 落库时执行一次）' },
  { macro: '{{setglobalvar::名称::值}}', desc: '写全局变量' },
  { macro: '{{addvar::名称::增量}}', desc: '数字相加，否则字符串拼接' },
  { macro: '{{incvar::名称}}', desc: '加 1 并返回新值' },
  { macro: '{{decvar::名称}}', desc: '减 1 并返回新值' },
  { macro: '{{deletevar::名称}}', desc: '删除变量' },
];

function numeric(s: string | undefined): number | null {
  if (s === undefined || s.trim() === '') return null;
  const n = Number(s);
  return Number.isFinite(n) ? n : null;
}

/** ST 语义：两边都能转数字就加减，否则拼接；未定义首次当 0 */
function addValue(cur: string | undefined, delta: string): string {
  const a = numeric(cur);
  const b = numeric(delta);
  if (a !== null && b !== null) return String(a + b);
  if (a === null && (cur === undefined || cur === '') && b !== null) return String(b);
  return (cur ?? '') + delta;
}

/** inc/dec：非数字内容保持原样，不像 add 那样拼接（和 ST 的警告行为一致） */
function bump(cur: string | undefined, by: number): string {
  const a = numeric(cur);
  if (a === null) return cur && cur.trim() !== '' ? cur : String(by);
  return String(a + by);
}

function splitArgs(body: string): string[] {
  // setvar::name::a::b —— 名字是第一段，后面全部并回值（允许值里带 ::）
  return body.split('::').map((s) => s.trim());
}

/**
 * 解析一条宏。
 *
 * allowWrite=false 时写宏一律返回空串且不碰表 —— 这是显示路径的安全阀。
 * allowWrite=true 时边扫边改 live（一份浅拷贝），所以同一段文本里
 * "先 set 再 get" 能拿到新值（ST 的稳定替换顺序），但仍是单次扫描。
 */
function resolveOne(
  full: string,
  rawName: string,
  body: string,
  live: VariableTables,
  allowWrite: boolean,
  markChanged: () => void,
): string {
  const name = rawName.toLowerCase();
  if (!ALL_NAMES.has(name)) return full; // 不是变量宏，原样还给文本

  const args = splitArgs(body);
  const key = args[0] ?? '';
  if (!key) return '';
  const isGlobal = name.includes('global');
  const table = isGlobal ? live.global : live.session;
  const value = args.slice(1).join('::');

  switch (name) {
    case 'getvar':
    case 'getglobalvar':
      return table[key] ?? '';
    case 'hasvar':
    case 'hasglobalvar':
      return Object.prototype.hasOwnProperty.call(table, key) ? 'true' : 'false';
  }

  if (!allowWrite) return ''; // 只读路径：写宏抹掉，绝不执行

  switch (name) {
    case 'setvar':
    case 'setglobalvar':
      table[key] = value;
      markChanged();
      return '';
    case 'deletevar':
    case 'deleteglobalvar':
      delete table[key];
      markChanged();
      return '';
    case 'addvar':
    case 'addglobalvar':
      table[key] = addValue(table[key], value);
      markChanged();
      return '';
    case 'incvar':
    case 'incglobalvar':
      table[key] = bump(table[key], 1);
      markChanged();
      return table[key];
    case 'decvar':
    case 'decglobalvar':
      table[key] = bump(table[key], -1);
      markChanged();
      return table[key];
  }
  return full;
}

/** 只读展开。写宏被抹成空串，不改表。显示 / 提示词 / 正则替换串都走这里 */
export function readVariables(text: string, tables: VariableTables): string {
  if (!text || !text.includes('{{')) return text;
  return text.replace(VAR_RE, (full, name: string, body: string) =>
    resolveOne(full, name, body, tables, false, () => {}),
  );
}

/**
 * 执行一次写宏，返回替换后的文本和新表。
 *
 * ★ 只允许在"用户发送"和"AI 回复落库"这两个一次性入口调用。
 * 调用方负责把返回的 tables 存回 store；本函数不碰任何全局状态。
 */
export function applyVariableWrites(text: string, tables: VariableTables): VariableWriteResult {
  const live: VariableTables = {
    session: { ...tables.session },
    global: { ...tables.global },
  };
  if (!text || !text.includes('{{')) return { text, tables: live, changed: false };
  let changed = false;
  const out = text.replace(VAR_RE, (full, name: string, body: string) =>
    resolveOne(full, name, body, live, true, () => { changed = true; }),
  );
  return { text: out, tables: live, changed };
}

/**
 * 短哈希，给显示缓存当版本号。
 *
 * 变量改了但缓存键没变的话，界面还按旧值渲染 —— 和之前
 * "改了正则没反应"是同一类 bug，所以版本串必须带上变量内容。
 */
export function variableVersion(tables: VariableTables): string {
  const raw = JSON.stringify([tables.session, tables.global]);
  let h = 5381;
  for (let i = 0; i < raw.length; i++) h = ((h << 5) + h + raw.charCodeAt(i)) | 0;
  return (h >>> 0).toString(36);
}
