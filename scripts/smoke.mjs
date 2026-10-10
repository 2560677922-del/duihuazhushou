// 引擎端到端冒烟测试：验证组装引擎在 Node 环境下能跑通。
// 运行：node scripts/smoke.mjs
import { OpenAIPreset, WorldBook, RegexScript, CharacterCard } from 'parsecard';
import { assemblePrompt } from '../src/engine/assembler.ts';
import { sampleCharacterRaw, samplePresetRaw, sampleWorldbookRaw, sampleRegexRaw, sampleChatHistory } from '../src/engine/sampleData.ts';

const preset = OpenAIPreset.fromJSON(samplePresetRaw);
const wb = WorldBook.fromJSON(sampleWorldbookRaw).toStandaloneJSON();
const card = CharacterCard.fromJSON(sampleCharacterRaw).toJSON();
const regex = RegexScript.fromJSON(sampleRegexRaw).toJSON();

const result = assemblePrompt({
  preset,
  characterCard: card,
  worldBooks: [wb],
  regexScripts: [regex],
  chatHistory: sampleChatHistory,
  currentUserInput: '我要研究符文之书，看看里面有什么魔法！',
  contextWindowTokens: 8192,
  userLookbackTokens: 2000,
});

console.log('=== finalMessages ===');
console.log(JSON.stringify(result.finalMessages, null, 2));
console.log('\n=== worldBookTriggered ===');
console.log(JSON.stringify(result.worldBookTriggered.map(h => ({comment:h.comment, reason:h.reason, position:h.position, depth:h.depth, matched:h.triggeredKeys})), null, 2));
console.log('\n=== regexApplied ===');
console.log(JSON.stringify(result.regexApplied.map(r => ({name:r.scriptName, matches:r.matchCount, placement:r.placement})), null, 2));
console.log('\n=== tokenEstimate ===', result.tokenEstimate);
console.log('=== warnings ===', result.warnings);
console.log('=== stages sizes ===', {
  raw: result.stages.raw.length,
  afterWorldBook: result.stages.afterWorldBook.length,
  afterMacro: result.stages.afterMacro.length,
  afterRegex: result.stages.afterRegex.length,
});
