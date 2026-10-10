import type { ChatMessage } from './types';

// 示例资产：让用户无需导入即可点开调试面板跑通端到端流程。
// 数据格式贴近真实 SillyTavern 导出，覆盖关键路径：
//   - 角色卡 V2/V3 字段（name/description/personality/scenario/first_mes/mes_example）
//   - OpenAI 预设 prompts + prompt_order
//   - 世界书 独立格式 entries（含常驻 + 关键词 + selective）
//   - 正则 脚本（AI_OUTPUT placement）

export const sampleCharacterRaw = {
  name: '艾琳',
  description:
    '艾琳是一位 18 岁的魔法学院学生，性格活泼、好奇心强，正在图书馆研究一本古老的魔法书。她说话时常用感叹号，喜欢引用书中的句子。',
  personality: '活泼、好奇、勇敢、有点冒失',
  scenario: '魔法学院图书馆。艾琳在研究一本古老的魔法书《符文之书》，突然遇到访客。',
  first_mes:
    "【艾琳】*艾琳抬起头，眼睛亮晶晶地看着你*\n\n你也是来图书馆自习的吗？我正在研究这本《符文之书》呢，你知道吗，里面记载了失落的星辰魔法！",
  mes_example:
    '<START>\n{{user}}: 你好\n{{char}}: 嗨！我是艾琳！你也是学生吗？\n{{user}}: 是的\n{{char}}: 太好了！我们做朋友吧！',
  creator: 'sample-data',
  character_version: '1.0',
  tags: ['sample', 'magic', 'library'],
  creator_notes: '',
  group_only: false,
  extensions: { fav: false },
  create_date: 1700000000,
};

export const samplePresetRaw = {
  name: '示例叙事预设',
  temperature: 0.8,
  top_p: 1,
  max_context: 8192,
  max_tokens: 600,
  prompt_order: {
    '100001': [
      'enhanceDefinitions',
      'main',
      'world_info_after',
      'examples',
      'author_note',
      'history',
    ],
  },
  prompts: [
    {
      identifier: 'enhanceDefinitions',
      name: 'Char Description',
      role: 'system',
      content: '角色设定：{{description}}\n\n场景：{{scenario}}',
      enabled: true,
      display_index: 0,
    },
    {
      identifier: 'main',
      name: 'Main Prompt',
      role: 'system',
      content:
        "你是小说叙事 AI。用 {{char}} 的视角推进剧情，保持角色性格一致。不要替 {{user}} 说话或行动。每段回复控制在 2-4 段。",
      enabled: true,
      display_index: 1,
    },
    {
      identifier: 'world_info_after',
      name: 'World Info (after)',
      role: 'system',
      content: '',
      enabled: true,
      display_index: 2,
    },
    {
      identifier: 'examples',
      name: 'Examples',
      role: 'user',
      content: '示例对话：\n{{mes_example}}',
      enabled: true,
      display_index: 3,
    },
    {
      identifier: 'author_note',
      name: "Author's Note",
      role: 'user',
      content: '',
      enabled: false,
      display_index: 4,
    },
    {
      identifier: 'history',
      name: 'Chat History',
      role: 'user',
      content: '',
      enabled: true,
      display_index: 5,
    },
  ],
};

export const sampleWorldbookRaw = {
  name: '示例世界书 · 图书馆之夜',
  scan_depth: 10,
  search_depth: 4,
  delay_abbr: false,
  display_index: 0,
  entries: {
    '0': {
      key: ['图书馆', 'library'],
      content: '背景：魔法学院图书馆深夜静谧，空气中弥漫着旧纸张与墨水的气味，高耸的书架一直延伸到穹顶。',
      comment: 'Library Setting (常驻)',
      selective_logic: 0,
      position: 0,
      order: 5,
      depth: 0,
      role: 0,
      probability: 100,
      disable: false,
      uid: 1,
      constant: true,
      selective: false,
      extensions: {
        case_sensitive: false,
        whole_word: false,
        constant: true,
        selective: false,
        secondary_keys: [],
        position: 0,
        depth: 0,
        role: 0,
        probability: 100,
      },
    },
    '1': {
      key: ['符文之书', 'ancient tome', 'magic book'],
      content: '设定：《符文之书》是一本记载失落星辰魔法的古卷，封面刻有会随月相变化的银色纹路。',
      comment: 'Ancient Tome',
      selective_logic: 0,
      position: 4,
      order: 10,
      depth: 4,
      role: 0,
      probability: 100,
      disable: false,
      uid: 2,
      constant: false,
      selective: true,
      extensions: {
        case_sensitive: false,
        whole_word: true,
        constant: false,
        selective: true,
        secondary_keys: [],
        position: 4,
        depth: 4,
        role: 0,
        probability: 100,
      },
    },
  },
};

// 注意：parsecard 的 RegexScript 期望扁平结构（scriptName/findRegex/
// replaceString/placement 在顶层），而 SillyTavern 导出的是 {script:{...}}
// 嵌套格式。这里直接给扁平格式作为示例；importRegexScript 会处理 ST 格式。
export const sampleRegexRaw = {
  id: 'sample-bold',
  scriptName: 'Markdown 粗体转换',
  findRegex: '\\*\\*(.*?)\\*\\*',
  replaceString: '<b>$1</b>',
  placement: [2],
  markdownOnly: true,
  disabled: false,
};

export const sampleChatHistory: ChatMessage[] = [
  {
    role: 'assistant',
    content:
      '【艾琳】*艾琳的手指拂过泛黄的书页，眼中倒映着银色符文的光芒*\n\n你看！这段符文在动……《符文之书》真的记载着**星辰魔法**！',
  },
  {
    role: 'user',
    content: '真的假的？让我看看那本符文之书。',
  },
];
