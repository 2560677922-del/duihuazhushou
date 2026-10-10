// SillyTavern 世界书 / 正则的可选项定义（与 ST 枚举值保持一致）
// 放在单独文件里，UI 与引擎共用同一份，避免两处对不上。

/** ST world_info_position */
export const ST_POSITIONS = [
  { value: 0, label: '角色定义之前（Before Char Defs）' },
  { value: 1, label: '角色定义之后（After Char Defs）' },
  { value: 2, label: '作者注释之前（Before AN）' },
  { value: 3, label: '作者注释之后（After AN）' },
  { value: 4, label: '指定深度（@ Depth）' },
  { value: 5, label: '示例消息之前（Before EM）' },
  { value: 6, label: '示例消息之后（After EM）' },
] as const;

/** ST selectiveLogic：副关键词的组合方式 */
export const SELECTIVE_LOGIC = [
  { value: 0, label: 'AND ANY（主+副命中任意一个）' },
  { value: 1, label: 'NOT ALL（主中且副不全部命中）' },
  { value: 2, label: 'NOT ANY（主中且副一个都不中）' },
  { value: 3, label: 'AND ALL（主与副全部命中）' },
] as const;

export function stPositionLabel(value: number): string {
  return ST_POSITIONS.find((p) => p.value === value)?.label ?? `位置 ${value}`;
}

export function selectiveLogicLabel(value: number): string {
  return SELECTIVE_LOGIC.find((l) => l.value === value)?.label ?? `逻辑 ${value}`;
}

/** ST regex_placement：正则生效的位置 */
export const ST_REGEX_PLACEMENTS = [
  { value: 1, label: '用户输入' },
  { value: 2, label: 'AI 输出' },
  { value: 3, label: 'SLASH 命令' },
  { value: 4, label: '世界书内容' },
  { value: 5, label: '推理过程' },
] as const;

export function regexPlacementLabel(value: number): string {
  return ST_REGEX_PLACEMENTS.find((p) => p.value === value)?.label ?? `位置 ${value}`;
}
