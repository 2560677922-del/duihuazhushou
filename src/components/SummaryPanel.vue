<script setup lang="ts">
// 摘要面板
//
// 摘要的用途是把前面发生过的事压缩成一段背景，附在后续请求的最前面 ——
// 这样长对话不会因为 token 裁剪而"失忆"，也不用把全文反复塞给模型。
//
// 两种生成方式：
//   手动 —— 用户点一下，可以指定目标字数和覆盖范围
//   自动 —— 达到设定的条数/字数阈值时后台生成（见 adventure store 的 summaryDue）
import { ref, computed } from 'vue';
import type { SummaryEntry } from '../stores/adventure';

const props = defineProps<{
  /**
   * 承载摘要的那个会话（冒险）。
   *
   * 这里刻意只声明需要的那一个字段，而不是直接绑死 `AdventureSession` ——
   * 这个组件原本是给「小说章节」和「冒险会话」共用的，
   * 现在小说功能已经删掉，但它依旧只关心 summaries，没必要跟着别的类型走。
   */
  chapter?: { summaries?: SummaryEntry[] };
  summarizing?: boolean;
  error?: string;
  targetWords: number;
  messageCount: number;
}>();

const emit = defineEmits<{
  summary: [targetWords: number, upTo: number];
  remove: [id: string];
  update: [id: string, text: string];
  close: [];
}>();

/** 手动摘要的覆盖范围：默认到最新一条 */
const upTo = ref(-1);
/** 手动摘要的目标字数 */
const words = ref(0);
/** 展开编辑的摘要 */
const editingId = ref<string | null>(null);
const editText = ref('');

const effectiveWords = computed(() => words.value || props.targetWords);
const effectiveUpTo = computed(() =>
  upTo.value >= 0 ? Math.min(upTo.value, props.messageCount - 1) : props.messageCount - 1,
);

const summaries = computed<SummaryEntry[]>(() => props.chapter?.summaries ?? []);

function startEdit(s: SummaryEntry) {
  editingId.value = s.id;
  editText.value = s.text;
}

function saveEdit() {
  if (editingId.value) emit('update', editingId.value, editText.value.trim());
  editingId.value = null;
}

function copy(s: SummaryEntry) {
  void navigator.clipboard.writeText(s.text);
}

function fmtTime(t: number) {
  const d = new Date(t);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getMonth() + 1}/${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
</script>

<template>
  <div class="summary-panel">
    <div class="sp-head">
      <span>📋 摘要</span>
      <button class="mini" @click="emit('close')">×</button>
    </div>

    <p class="sp-hint">
      摘要会作为「前情提要」附在每次请求最前面。长对话靠它保住记忆，
      不必把全文反复发给模型。
    </p>

    <!-- 手动生成 -->
    <div class="sp-card">
      <div class="sp-row">
        <label>目标字数</label>
        <input type="number" v-model.number="words" :placeholder="String(targetWords)" min="50" step="50" />
      </div>
      <div class="sp-row">
        <label>覆盖到第几条</label>
        <input type="number" v-model.number="upTo" :min="-1" :max="Math.max(0, messageCount - 1)"
          placeholder="最新" />
      </div>
      <div class="sp-row sp-info">
        共 {{ messageCount }} 轮 · 将总结第 1 ~ {{ effectiveUpTo + 1 }} 轮 · 约 {{ effectiveWords }} 字
      </div>
      <button class="primary sp-go" :disabled="summarizing || messageCount === 0"
        @click="emit('summary', effectiveWords, effectiveUpTo)">
        {{ summarizing ? '生成中…' : '生成摘要' }}
      </button>
      <p v-if="error" class="sp-error">{{ error }}</p>
    </div>

    <!-- 已有摘要 -->
    <div v-if="summaries.length" class="sp-list">
      <div v-for="s in summaries" :key="s.id" class="sp-item">
        <div class="sp-item-head">
          <span class="sp-badge" :class="s.mode">{{ s.mode === 'auto' ? '自动' : '手动' }}</span>
          <span class="sp-meta">到第 {{ s.coveredUpTo + 1 }} 轮 · {{ fmtTime(s.createdAt) }}</span>
          <div class="sp-item-acts">
            <button class="mini" @click="startEdit(s)">改</button>
            <button class="mini" @click="copy(s)">复制</button>
            <button class="mini danger" @click="emit('remove', s.id)">删</button>
          </div>
        </div>
        <textarea v-if="editingId === s.id" class="sp-edit" v-model="editText" rows="6"></textarea>
        <div v-if="editingId === s.id" class="sp-edit-acts">
          <button class="mini primary" @click="saveEdit">保存</button>
          <button class="mini" @click="editingId = null">取消</button>
        </div>
        <p v-else class="sp-text">{{ s.text }}</p>
      </div>
    </div>
    <p v-else class="sp-empty">还没有摘要。可以手动生成一条，或等到阈值后自动生成。</p>
  </div>
</template>

<style scoped>
.summary-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
}
.sp-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  font-weight: 600;
}
.sp-hint {
  margin: 0;
  font-size: 10.5px;
  line-height: 1.7;
  color: var(--text-dim);
}
.sp-card {
  display: flex;
  flex-direction: column;
  gap: 7px;
  padding: 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel-2);
}
.sp-row {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  color: var(--text-dim);
}
.sp-row label {
  white-space: nowrap;
}
.sp-row input {
  width: 78px;
  font-size: 11.5px;
  padding: 3px 7px;
}
.sp-info {
  font-size: 10px;
  color: var(--text-dim);
}
.sp-go {
  font-size: 12px;
  padding: 6px 12px;
}
.sp-error {
  margin: 0;
  font-size: 10.5px;
  color: var(--danger);
}
.sp-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.sp-item {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 9px 10px;
  background: var(--panel);
}
.sp-item-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}
.sp-badge {
  font-size: 9px;
  padding: 1px 6px;
  border-radius: 999px;
  border: 1px solid var(--border);
}
.sp-badge.auto {
  border-color: var(--accent);
  color: var(--accent);
}
.sp-badge.manual {
  border-color: var(--accent-2);
  color: var(--accent-2);
}
.sp-meta {
  font-size: 9.5px;
  color: var(--text-dim);
}
.sp-item-acts {
  margin-left: auto;
  display: flex;
  gap: 4px;
}
.mini {
  font-size: 10px;
  padding: 2px 7px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--panel);
  color: var(--text-dim);
  cursor: pointer;
}
.mini:hover {
  border-color: var(--accent);
  color: var(--text);
}
.mini.danger:hover {
  border-color: var(--danger);
  color: var(--danger);
}
.mini.primary {
  background: var(--accent);
  border-color: var(--accent);
  color: #0b1220;
}
.sp-text {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.75;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--text);
}
.sp-edit {
  width: 100%;
  box-sizing: border-box;
  font-size: 11.5px;
  line-height: 1.7;
  padding: 6px 8px;
  font-family: inherit;
}
.sp-edit-acts {
  display: flex;
  gap: 5px;
  margin-top: 5px;
}
.sp-empty {
  font-size: 11px;
  color: var(--text-dim);
  text-align: center;
  padding: 10px;
}
</style>
