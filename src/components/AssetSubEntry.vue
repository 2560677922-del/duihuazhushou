<script setup lang="ts">
// 子条目行：带开关、可展开单独改。
// 总条目展开后，每条子条目都能单独操作（开关 + 展开编辑），
// 这就是用户要的「打开一个总选项，然后每个子条目可以单独改」。
import { ref } from 'vue';
import {
  ST_POSITIONS,
  SELECTIVE_LOGIC,
  regexPlacementLabel,
  ST_REGEX_PLACEMENTS,
} from '../engine/worldbookOptions';

const props = defineProps<{
  kind: 'worldbook' | 'preset' | 'regex' | 'character';
  item: any;
  index: number;
}>();

const emit = defineEmits<{ changed: [] }>();

const open = ref(false);

// ---------- 世界书：关键词 ----------
function entryKeys(e: any): string[] {
  const raw = e?.keys ?? e?.key ?? [];
  return (Array.isArray(raw) ? raw : [raw])
    .map((k: any) => (typeof k === 'string' ? k : (k?.key ?? '')))
    .map((k: any) => String(k).trim())
    .filter(Boolean);
}
function setEntryKeys(e: any, value: string) {
  const list = value
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
  e.keys = list;
  e.key = list.map((k: string) => ({
    key: k,
    secondary_keys: [],
    match_case: false,
    whole_word: false,
    recursive: false,
  }));
}
function entryDisabled(e: any): boolean {
  // 三种写法都要认（本应用写 disabled，ST 用 enabled/disable）
  const truthy = (v: any) => v === true || v === 1 || v === 'true' || v === '1';
  const falsy = (v: any) => v === false || v === 0 || v === 'false' || v === '0';
  return truthy(e?.disabled) || truthy(e?.disable) || falsy(e?.enabled);
}
function setEntryDisabled(e: any, off: boolean) {
  /*
   * 三处都写，保持自洽。
   *
   * 之前只写 `disabled`，而引擎读的是 `disable`/`enabled` ——
   * 取消勾选完全没效果（用户复现）。现在写入时把标准字段一起同步，
   * 以后不管谁读哪个字段，看到的都是同一个状态。
   */
  e.disabled = off;
  e.enabled = !off;
  if ('disable' in e) e.disable = off;
  emit('changed');
}

// ---------- 正则 ----------
function placements(r: any): number[] {
  const raw = r?.placement;
  if (Array.isArray(raw)) return raw.map((v) => Number(v)).filter((v) => !Number.isNaN(v));
  if (raw === undefined || raw === null || raw === '') return [];
  const n = Number(raw);
  return Number.isNaN(n) ? [] : [n];
}
function togglePlacement(r: any, v: number) {
  const list = placements(r);
  r.placement = list.includes(v) ? list.filter((x) => x !== v) : [...list, v];
  emit('changed');
}
function trimStrings(r: any): string[] {
  const raw = r?.trimStrings ?? r?.trim_strings;
  return Array.isArray(raw) ? raw : [];
}
function setTrimStrings(r: any, value: string) {
  r.trimStrings = value
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean);
  emit('changed');
}

// ---------- 预设 ----------
function roleLabel(role: any): string {
  const r = String(role ?? '').toLowerCase();
  if (r === 'system' || r === '0') return '系统';
  if (r === 'user' || r === '1') return '用户';
  if (r === 'assistant' || r === '2') return 'AI';
  return '未设置';
}
function presetPrompts(p: any): any[] {
  const raw = p?.prompts;
  if (Array.isArray(raw)) return raw;
  if (raw && typeof raw === 'object') return Object.values(raw);
  return [];
}
function promptObj(p: any, i: number): any {
  return presetPrompts(p)[i] ?? {};
}
/** prompt_order 列表；没有就按 prompts 顺序补一份 */
function orderList(p: any): any[] {
  const po = p?.prompt_order ?? p?.promptOrder;
  if (!Array.isArray(po) || po.length === 0) {
    const order = presetPrompts(p).map((x: any, k: number) => ({
      identifier: String(x?.identifier ?? x?.id ?? `p${k}`),
      enabled: x?.enabled !== false,
    }));
    p.prompt_order = [{ character_id: 100001, order }];
    return order;
  }
  return po[0]?.order ?? po[0]?.prompts ?? [];
}
function promptEnabled(p: any, i: number): boolean {
  const pr = promptObj(p, i);
  const list = orderList(p);
  const id = String(pr?.identifier ?? pr?.id ?? `p${i}`);
  const found = list.find((x: any) => String(x?.identifier ?? x?.id) === id);
  return found ? found.enabled !== false : pr?.enabled !== false;
}
function togglePrompt(p: any, i: number, on: boolean) {
  const pr = promptObj(p, i);
  const list = orderList(p);
  const id = String(pr?.identifier ?? pr?.id ?? `p${i}`);
  const found = list.find((x: any) => String(x?.identifier ?? x?.id) === id);
  if (found) found.enabled = on;
  else list.push({ identifier: id, enabled: on });
  emit('changed');
}
</script>

<template>
  <!-- ================= 世界书条目 ================= -->
  <div v-if="props.kind === 'worldbook'" class="sub-wrap" :class="{ off: entryDisabled(props.item) }">
    <div class="sub-head" @click="open = !open">
      <span class="caret">{{ open ? '▾' : '▸' }}</span>
      <!--
        这里以前还有个「✱」星号表示启用状态，右侧又有一个勾选框 ——
        同一个信息画两遍，用户反馈"都有勾选了，就不要搞那个五角星了"。
        状态只由右侧勾选框表达，星号去掉。
      -->
      <span class="title">
        {{ props.item.comment || entryKeys(props.item)[0] || `条目 ${props.index + 1}` }}
      </span>
      <span class="meta">
        <template v-if="props.item.constant">常驻</template>
        <template v-else-if="entryKeys(props.item).length">
          {{ entryKeys(props.item).slice(0, 3).join(' / ') }}<em v-if="entryKeys(props.item).length > 3"> +{{ entryKeys(props.item).length - 3 }}</em>
        </template>
        <template v-else><em class="warn">无关键词</em></template>
      </span>
      <span class="order">{{ props.item.order ?? '-' }}</span>
      <label class="sw" @click.stop>
        <input type="checkbox" :checked="!entryDisabled(props.item)"
          @change="setEntryDisabled(props.item, !($event.target as HTMLInputElement).checked)" />
      </label>
    </div>

    <div v-if="open" class="sub-body">
      <div class="f">
        <label>条目名称</label>
        <input type="text" v-model="props.item.comment" @change="emit('changed')" />
      </div>
      <div class="f">
        <label>触发方式</label>
        <div class="seg">
          <button :class="{ active: !props.item.constant }"
            @click="props.item.constant = false; emit('changed')">关键词触发</button>
          <button :class="{ active: props.item.constant }"
            @click="props.item.constant = true; emit('changed')">常驻触发</button>
        </div>
      </div>
      <div v-if="!props.item.constant" class="f">
        <label>主关键词（英文逗号分隔，命中任意一个即触发）</label>
        <input type="text" :value="entryKeys(props.item).join(', ')" placeholder="魔法, 咒语"
          @change="setEntryKeys(props.item, ($event.target as HTMLInputElement).value); emit('changed')" />
      </div>
      <div v-if="!props.item.constant" class="two">
        <div class="f">
          <label>副关键词</label>
          <input type="text" :value="(props.item.keysecondary || []).join(', ')" placeholder="留空即可"
            @change="props.item.keysecondary = ($event.target as HTMLInputElement).value.split(',').map(s => s.trim()).filter(Boolean); emit('changed')" />
        </div>
        <div class="f">
          <label>匹配逻辑</label>
          <select :value="props.item.selective ? (props.item.selectiveLogic ?? 0) : -1"
            @change="(ev) => { const v = Number((ev.target as HTMLSelectElement).value); props.item.selective = v >= 0; props.item.selectiveLogic = v >= 0 ? v : 0; emit('changed'); }">
            <option :value="-1">命中主关键词即触发</option>
            <option v-for="l in SELECTIVE_LOGIC" :key="l.value" :value="l.value">{{ l.label }}</option>
          </select>
        </div>
      </div>
      <div class="two">
        <div class="f">
          <label>优先级 order（越大越先插入）</label>
          <input type="number" v-model.number="props.item.order" @change="emit('changed')" />
        </div>
        <div class="f">
          <label>触发概率 %</label>
          <input type="number" min="0" max="100" v-model.number="props.item.probability" @change="emit('changed')" />
        </div>
      </div>
      <div class="two">
        <div class="f">
          <label>注入位置</label>
          <select v-model.number="props.item.position" @change="emit('changed')">
            <option v-for="p in ST_POSITIONS" :key="p.value" :value="p.value">{{ p.label }}</option>
          </select>
        </div>
        <div v-if="props.item.position === 4" class="f">
          <label>指定深度 @ Depth</label>
          <input type="number" min="0" max="50" v-model.number="props.item.depth" @change="emit('changed')" />
        </div>
      </div>
      <div class="f">
        <label>内容（注入到提示词里的正文）</label>
        <textarea rows="4" v-model="props.item.content" @change="emit('changed')"></textarea>
      </div>
    </div>
  </div>

  <!-- ================= 预设提示词 ================= -->
  <div v-else-if="props.kind === 'preset'" class="sub-wrap"
    :class="{ off: !promptEnabled(props.item, props.index) }">
    <div class="sub-head" @click="open = !open">
      <span class="caret">{{ open ? '▾' : '▸' }}</span>
      <span class="title">
        {{ promptObj(props.item, props.index).name || promptObj(props.item, props.index).identifier || `prompt ${props.index + 1}` }}
      </span>
      <span class="meta">{{ roleLabel(promptObj(props.item, props.index).role) }}</span>
      <span class="order">{{ props.index + 1 }}</span>
      <label class="sw" @click.stop>
        <input type="checkbox" :checked="promptEnabled(props.item, props.index)"
          @change="togglePrompt(props.item, props.index, ($event.target as HTMLInputElement).checked)" />
      </label>
    </div>

    <div v-if="open" class="sub-body">
      <div class="f">
        <label>名称</label>
        <input type="text" :value="promptObj(props.item, props.index).name"
          @change="promptObj(props.item, props.index).name = ($event.target as HTMLInputElement).value; emit('changed')" />
      </div>
      <div class="two">
        <div class="f">
          <label>角色</label>
          <select :value="promptObj(props.item, props.index).role"
            @change="promptObj(props.item, props.index).role = ($event.target as HTMLSelectElement).value; emit('changed')">
            <option value="system">系统</option>
            <option value="user">用户</option>
            <option value="assistant">AI</option>
          </select>
        </div>
        <div class="f">
          <label>标识符 identifier</label>
          <input type="text" :value="promptObj(props.item, props.index).identifier"
            @change="promptObj(props.item, props.index).identifier = ($event.target as HTMLInputElement).value; emit('changed')" />
        </div>
      </div>
      <div class="f">
        <label>内容</label>
        <textarea rows="4" :value="promptObj(props.item, props.index).content"
          @change="promptObj(props.item, props.index).content = ($event.target as HTMLTextAreaElement).value; emit('changed')"></textarea>
      </div>
    </div>
  </div>

  <!-- ================= 正则 ================= -->
  <div v-else-if="props.kind === 'regex'" class="sub-wrap">
    <div class="sub-head" @click="open = !open">
      <span class="caret">{{ open ? '▾' : '▸' }}</span>
      <span class="title">{{ props.item.scriptName || '(未命名正则)' }}</span>
      <span class="meta mono">{{ props.item.findRegex || '(空)' }}</span>
      <label class="sw" @click.stop>
        <input type="checkbox" :checked="!props.item.disabled"
          @change="props.item.disabled = !($event.target as HTMLInputElement).checked; emit('changed')" />
      </label>
    </div>

    <div v-if="open" class="sub-body">
      <div class="f">
        <label>名字</label>
        <input type="text" v-model="props.item.scriptName" @change="emit('changed')" />
      </div>
      <div class="f">
        <label>正则表达式 Find Regex · 支持 /pattern/flags</label>
        <input type="text" class="mono" v-model="props.item.findRegex" @change="emit('changed')" />
      </div>
      <div class="f">
        <label>替换内容 Replace With · 可用 $1 引用捕获组</label>
        <textarea rows="3" class="mono" v-model="props.item.replaceString" @change="emit('changed')"></textarea>
      </div>
      <div class="f">
        <label>替换前修剪 Trim Out · 每行一个</label>
        <textarea rows="2" class="mono" :value="trimStrings(props.item).join('\n')"
          @change="setTrimStrings(props.item, ($event.target as HTMLTextAreaElement).value)"></textarea>
      </div>
      <div class="f">
        <label>作用范围 Placement</label>
        <div class="chips">
          <button v-for="p in ST_REGEX_PLACEMENTS" :key="p.value" class="chip"
            :class="{ active: placements(props.item).includes(p.value) }"
            @click="togglePlacement(props.item, p.value)">{{ p.label }}</button>
        </div>
      </div>
      <div class="f">
        <label>执行时机 Timing</label>
        <div class="seg">
          <button :class="{ active: !props.item.markdownOnly && !props.item.promptOnly }"
            @click="props.item.markdownOnly = false; props.item.promptOnly = false; emit('changed')">发送时</button>
          <button :class="{ active: props.item.markdownOnly }"
            @click="props.item.markdownOnly = true; props.item.promptOnly = false; emit('changed')">仅显示</button>
          <button :class="{ active: props.item.promptOnly }"
            @click="props.item.promptOnly = true; props.item.markdownOnly = false; emit('changed')">仅提示词</button>
        </div>
      </div>
      <div class="two">
        <div class="f">
          <label>参数替换</label>
          <select v-model.number="props.item.substituteRegex" @change="emit('changed')">
            <option :value="0">不替换</option>
            <option :value="1">替换（宏生效）</option>
            <option :value="2">替换（原始）</option>
          </select>
        </div>
        <div class="f">
          <label>消息深度（留空=不限）</label>
          <div class="two">
            <input type="number" placeholder="最小" v-model.number="props.item.minDepth" @change="emit('changed')" />
            <input type="number" placeholder="最大" v-model.number="props.item.maxDepth" @change="emit('changed')" />
          </div>
        </div>
      </div>
      <div class="f">
        <label>生效位置</label>
        <div class="preview">
          {{ placements(props.item).map((p) => regexPlacementLabel(p)).join('、') || '未设置范围（不会生效）' }}
        </div>
      </div>
    </div>
  </div>

  <!-- ================= 角色卡字段 ================= -->
  <div v-else class="sub-wrap">
    <div class="sub-head" @click="open = !open">
      <span class="caret">{{ open ? '▾' : '▸' }}</span>
      <!-- 这一项没有启用开关，星号只表示"填没填"，容易和"启用"混淆 —— 去掉 -->
      <span class="title">{{ props.item.label }}</span>
      <span class="meta">{{ (props.item.value || '').slice(0, 50) || '(空)' }}</span>
    </div>
    <div v-if="open" class="sub-body">
      <textarea rows="5" v-model="props.item.value" @change="emit('changed')"></textarea>
    </div>
  </div>
</template>

<style scoped>
.sub-wrap {
  border-top: 1px solid var(--border);
}
.sub-wrap.off {
  opacity: 0.55;
}
.sub-head {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 6px 10px;
  font-size: 11.5px;
  cursor: pointer;
}
.sub-head:hover {
  background: var(--panel-2);
}
.caret {
  color: var(--text-dim);
  font-size: 9px;
  width: 10px;
  flex: 0 0 auto;
}
.star {
  color: var(--border);
  font-size: 12px;
  flex: 0 0 auto;
}
.star.on {
  color: var(--accent);
}
.title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text);
}
.meta {
  font-size: 10px;
  color: var(--text-dim);
  max-width: 38%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.meta em {
  font-style: normal;
  opacity: 0.7;
}
.meta em.warn {
  color: var(--warn);
  opacity: 1;
}
.meta.mono {
  font-family: ui-monospace, monospace;
}
.order {
  font-size: 10px;
  color: var(--text-dim);
  min-width: 22px;
  text-align: right;
}
.sw {
  display: inline-flex;
  align-items: center;
  cursor: pointer;
}
.sw input {
  width: auto;
  margin: 0;
}
.sub-body {
  padding: 8px 12px 12px 30px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: var(--panel-2);
}
.f {
  display: flex;
  flex-direction: column;
  gap: 3px;
  flex: 1;
  min-width: 0;
}
.f label {
  font-size: 10px;
  color: var(--text-dim);
}
.f input,
.f select,
.f textarea {
  font-size: 12px;
  padding: 5px 7px;
  width: 100%;
  box-sizing: border-box;
}
.f textarea {
  font-family: inherit;
  resize: vertical;
}
.mono {
  font-family: ui-monospace, monospace;
}
.two {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.two > * {
  flex: 1;
  min-width: 100px;
}
.seg {
  display: flex;
  border: 1px solid var(--border);
  border-radius: 6px;
  overflow: hidden;
}
.seg button {
  border: none;
  border-radius: 0;
  background: var(--panel);
  font-size: 11px;
  padding: 5px 10px;
}
.seg button.active {
  background: var(--accent);
  color: #0b0e16;
  font-weight: 600;
}
.chips {
  display: flex;
  gap: 5px;
  flex-wrap: wrap;
}
.chip {
  font-size: 10px;
  padding: 3px 9px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--panel);
  color: var(--text-dim);
  cursor: pointer;
}
.chip.active {
  border-color: var(--accent);
  color: var(--accent);
  font-weight: 600;
}
.preview {
  font-size: 10px;
  color: var(--text-dim);
  background: var(--code-bg);
  padding: 5px 8px;
  border-radius: 5px;
}
</style>
