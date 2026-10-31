<script setup lang="ts">
// 消息操作条：编辑 / 重新生成 / 分支 / 删除
//
// 冒险对话和（已移除的）小说编辑器原本共用这一套操作，所以抽成组件，
// 避免两边各写一遍、以后改一处忘一处。现在只剩冒险在用。
import { ref, nextTick } from 'vue';

const props = defineProps<{
  /** 这条消息的正文，编辑时预填 */
  content: string;
  /** 是否正在生成（生成中禁用重生成，避免并发请求） */
  streaming?: boolean;
  /**
   * 删除按钮的文字。
   *
   * 一轮对话在数据里是 user + ai 成对的，删除是**分侧**的：
   * 在 AI 那条上点删除只删回复、保留你写的那句，反之亦然。
   * 所以按钮要说清删的是哪一边，不能只写"删除这条"让人猜。
   */
  removeLabel?: string;
  /** 二次确认时的提示文字 */
  confirmLabel?: string;
  /**
   * 重生成按钮的文字。
   *
   * 同一件事在两侧的说法不一样：
   *   在 AI 那条上点 = 「重新生成」（换个回答）
   *   在你自己那条上点 = 「重新发送」（拿这句重发一次）
   * 行为完全一样（都是重做这一轮），但按用户的说法写出来更好认。
   */
  regenerateLabel?: string;
}>();

const emit = defineEmits<{
  save: [text: string];
  regenerate: [];
  branch: [];
  remove: [];
}>();

const editing = ref(false);
const draft = ref('');
const box = ref<HTMLTextAreaElement | null>(null);
const confirmingDelete = ref(false);
/**
 * 手机上操作条默认收起，点「⋯」才展开。
 *
 * 为什么：一条消息带 4 个按钮，几十条就是一排排按钮墙，
 * 既挤掉正文空间又增加排版/绘制量。收起后手机界面清爽很多。
 * 桌面端不受影响（CSS 只在 <=768px 时隐藏）。
 */
const actionsOpen = ref(false);

async function startEdit() {
  draft.value = props.content;
  editing.value = true;
  await nextTick();
  box.value?.focus();
}

function cancelEdit() {
  editing.value = false;
  confirmingDelete.value = false;
}

function saveEdit() {
  const text = draft.value.trim();
  editing.value = false;
  if (text && text !== props.content) emit('save', text);
}

function onKeydown(e: KeyboardEvent) {
  // Ctrl/Cmd + Enter 保存；Esc 取消
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
    e.preventDefault();
    saveEdit();
  } else if (e.key === 'Escape') {
    e.preventDefault();
    cancelEdit();
  }
}

function askDelete() {
  // 两步确认，不用 window.confirm —— 手机上原生的 confirm 很难看，
  // 而且这个操作不可撤销
  if (!confirmingDelete.value) {
    confirmingDelete.value = true;
    setTimeout(() => (confirmingDelete.value = false), 4000);
    return;
  }
  confirmingDelete.value = false;
  emit('remove');
}
</script>

<template>
  <div class="msg-actions" :class="{ open: actionsOpen }">
    <template v-if="!editing">
      <!-- 手机上先用这个按钮把操作条展开；桌面端它不显示 -->
      <button class="ma ma-more" :class="{ on: actionsOpen }" @click="actionsOpen = !actionsOpen"
        :title="actionsOpen ? '收起操作' : '展开操作（编辑 / 重新生成 / 分支 / 删除）'">
        {{ actionsOpen ? '收起' : '⋯ 操作' }}
      </button>
      <button class="ma" @click="startEdit" title="编辑这条内容">✏️ 编辑</button>
      <button class="ma" :disabled="streaming" @click="emit('regenerate')"
        :title="regenerateLabel ? '把这一轮重做一次' : '用同样的指令重新生成'">
        🔄 {{ regenerateLabel || '重新生成' }}
      </button>
      <button class="ma" @click="emit('branch')" title="从这一步复制出一个新会话">🌿 分支</button>
      <button class="ma danger" :class="{ confirm: confirmingDelete }" @click="askDelete"
        :title="confirmingDelete ? '再点一次确认删除' : `删除这条${removeLabel ? '（' + removeLabel + '）' : ''}，不影响另一侧`">
        {{ confirmingDelete ? (confirmLabel || '确认删除？') : `🗑️ ${removeLabel || '删除'}` }}
      </button>
    </template>
    <template v-else>
      <textarea
        ref="box"
        class="ma-edit"
        v-model="draft"
        rows="4"
        @keydown="onKeydown"
      ></textarea>
      <div class="ma-edit-btns">
        <button class="ma primary" @click="saveEdit">保存</button>
        <button class="ma" @click="cancelEdit">取消</button>
        <span class="ma-hint">Ctrl+Enter 保存 · Esc 取消</span>
      </div>
    </template>
  </div>
</template>

<style scoped>
.msg-actions {
  display: flex;
  gap: 5px;
  flex-wrap: wrap;
  margin-top: 6px;
  align-items: center;
}
.ma {
  font-size: 10.5px;
  padding: 3px 10px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--panel);
  color: var(--text-dim);
  cursor: pointer;
  white-space: nowrap;
}
.ma:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--text);
}
.ma:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.ma.danger:hover {
  border-color: var(--danger);
  color: var(--danger);
}
.ma.danger.confirm {
  border-color: var(--danger);
  color: var(--danger);
  font-weight: 600;
}
.ma.primary {
  background: var(--accent);
  border-color: var(--accent);
  color: #0b1220;
  font-weight: 600;
}
.ma-edit {
  width: 100%;
  font-size: 12.5px;
  line-height: 1.6;
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid var(--accent);
  background: var(--panel);
  color: var(--text);
  resize: vertical;
  font-family: inherit;
  box-sizing: border-box;
}
.ma-edit-btns {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 5px;
  flex-wrap: wrap;
}
.ma-hint {
  font-size: 9.5px;
  color: var(--text-dim);
}

/* 「⋯ 操作」按钮：只在手机端出现 */
.ma-more {
  display: none;
}
.ma-more.on {
  border-color: var(--accent);
  color: var(--accent);
}

/* ---------------------------------------------------------------------------
 * 手机端：操作条默认收起，点「⋯」展开
 *
 * 一条消息 4 个按钮，几十条排下来就是一面按钮墙 —— 既占正文空间，
 * 也增加排版和绘制量。收起后只留一个「⋯」，点开才显示全部。
 * 桌面端不受影响（这条规则在媒体查询里）。
 * --------------------------------------------------------------------------- */
@media (max-width: 768px) {
  .ma-more {
    display: inline-block;
  }
  /* 收起状态：除「⋯」以外的按钮一律不参与布局和绘制 */
  .msg-actions:not(.open) .ma:not(.ma-more) {
    display: none;
  }
  /* 展开后按钮更小一点，一行尽量排得下 */
  .ma {
    font-size: 10px;
    padding: 4px 9px;
  }
}
</style>
