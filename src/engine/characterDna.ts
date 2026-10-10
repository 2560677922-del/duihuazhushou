// 角色 DNA：锚定角色外观特征，生图时随提示词一起发给大模型。
//
// 为什么单独做一个配置：
//   世界书/预设是"叙事层"的东西，而角色外观是"视觉层"的固定参数。
//   把它混进世界书里，会导致每次生图都从叙事文本里重新推断长相，
//   同一个角色每张图都不一样。单独抽出来锚定，才能保证一致性。
//
// 用法：DNA 里写英文外观描述（发色瞳色、体型、服装、标志物等），
// 生图时选中哪几条，就把它们拼进提示词优化的 system 里，
// 并明确要求"不要改变这些特征"。

export interface CharacterDna {
  _uid: string;
  /** 名称，便于在多套 DNA 之间切换 */
  name: string;
  /**
   * 英文外观描述。建议写成逗号分隔的 Danbooru 标签，
   * 例如：1girl, silver hair, red eyes, gothic dress, black choker
   */
  dna: string;
  /** 备注，给自己看 */
  note?: string;
  /** 是否启用（可临时关掉对比效果） */
  enabled?: boolean;
  createdAt?: number;
  updatedAt?: number;
}

/** DNA 描述块在提示词里的写法 */
export function buildDnaBlock(list: CharacterDna[]): string {
  const active = list.filter((d) => d?.dna?.trim() && d.enabled !== false);
  if (active.length === 0) return '';

  const parts = active.map((d) => {
    const body = d.dna.trim();
    return active.length === 1 ? body : `【${d.name || '角色'}】${body}`;
  });

  return (
    '【角色外观锚定 · 必须严格遵守】\n' +
    parts.join('\n') +
    '\n\n这些是角色的固定外观特征，上面列出的每一项都必须在提示词中体现，' +
    '并且不得改写、替换或省略（发色、瞳色、体型、服装、标志物尤其重要）。' +
    '如果场景描述与这些特征冲突，以这些特征为准。'
  );
}

/** 把多条 DNA 合并成一段纯标签文本（用于手动提示词模式） */
export function mergeDnaTags(list: CharacterDna[]): string {
  return list
    .filter((d) => d?.dna?.trim() && d.enabled !== false)
    .map((d) => d.dna.trim())
    .join(', ');
}

/** 新建时的空白 DNA */
export function createDna(name = '新角色'): CharacterDna {
  return {
    _uid: `dna-${Math.random().toString(36).slice(2, 10)}`,
    name,
    dna: '',
    note: '',
    enabled: true,
    createdAt: Date.now(),
    updatedAt: Date.now(),
  };
}
