// 共享类型定义（引擎核心，不依赖 Vue）

import type { VariableTables } from './variables';

export type Role = 'system' | 'user' | 'assistant';

export interface ChatMessage {
  role: Role;
  content: string;
  /** SillyTavern prompt identifier，用于定位锚点 */
  _promptId?: string;
  _name?: string;
  /**
   * 消息来源。
   *
   * 正则引擎靠它区分作用范围（placement）：
   * 推理过程和斜杠命令虽然也是 assistant/user 角色，但来源不同，
   * 不区分的话「只对推理过程」的规则会把正文一起改掉。
   */
  _origin?: 'preset' | 'worldbook' | 'history' | 'author_note' | 'reasoning' | 'slash';
  _index?: number;
}

export interface AssemblyOptions {
  /** parsecard OpenAIPreset 实例（或其 toJSON 输出） */
  preset?: any;
  /** parsecard WorldBook 实例数组 */
  worldBooks?: any[];
  /** parsecard RegexScript 实例数组 */
  regexScripts?: any[];
  /** parsecard CharacterCard 实例（可选，作为主角/NPC） */
  characterCard?: any;
  /**
   * 用户的角色（资产页「我的角色」）。
   *
   * 只影响两件事：`{{user}}` 的名字、`{{persona}}` / 用户人设块的内容。
   * 不注入角色卡，也不改预设 —— 它是"你是谁"，不是"AI 演谁"。
   */
  userPersona?: { name?: string; description?: string };
  /**
   * 群聊旁白设定。
   *
   * 旁白不是角色卡：不占成员位、不参与发言轮转，只在这一轮作为
   * 一条 system 设定注入，让模型用"叙述者"的视角写环境与事件。
   */
  narrator?: string;
  chatHistory: ChatMessage[];
  currentUserInput?: string;
  /** 作者注释，作为 user 消息按 depth 回溯注入 */
  authorNote?: string;
  authorNoteDepth?: number;
  /** 世界书扫描深度：最近 N 条消息（默认 10） */
  worldBookScanDepth?: number;
  /** 模型上下文窗口 */
  contextWindowTokens?: number;
  /** 用户回溯范围（保留给历史消息的预算） */
  userLookbackTokens?: number;
  /**
   * 变量表（ST 变量宏）。不传时 {{getvar::...}} 原样保留。
   * 只读：写宏在这里一律抹成空串，绝不改状态。
   */
  variables?: VariableTables;
}

export interface WorldbookHit {
  uid: string;
  bookName: string;
  comment: string;
  keys: string[];
  content: string;
  /** SillyTavern WorldBookEntryPosition 枚举值 */
  position: number;
  depth: number;
  /** SillyTavern EntryRole：0=system,1=user,2=assistant */
  role: number;
  reason: 'constant' | 'keyword';
  triggeredKeys: string[];
  matchText: string;
}

export interface RegexHit {
  id: string;
  scriptName: string;
  /** SillyTavern RegexPlacement 枚举值数组 */
  placement: number[];
  findRegex: string;
  replaceString: string;
  matchCount: number;
  sample: { before: string; after: string } | null;
}

export interface AssemblyResult {
  finalMessages: ChatMessage[];
  stages: {
    raw: ChatMessage[];
    afterWorldBook: ChatMessage[];
    afterMacro: ChatMessage[];
    afterRegex: ChatMessage[];
  };
  worldBookTriggered: WorldbookHit[];
  regexApplied: RegexHit[];
  tokenEstimate: number;
  warnings: string[];
}
