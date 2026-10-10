<script setup lang="ts">
// 版本切换器「< 2/3 >」
//
// 重新生成、编辑之后，旧内容不会丢 —— 都压在 variants 里。
// 用这个控件前后翻，像 SillyTavern 那样挑一版满意的。
//
// 约定：index 0 = 最新，数字越大越旧。所以「<」往旧走（index+1），
// 「>」往新走（index-1）。显示成「第 n / 共 m 版」，n 从 1 开始数、
// 1 是**最新**那版。
import { computed } from 'vue';

const props = defineProps<{
  /** 总共有几版 */
  total: number;
  /** 当前是第几版（0 = 最新） */
  index: number;
}>();

const emit = defineEmits<{
  switch: [index: number];
}>();

/** 界面上显示成「共 total 版中的第几个」，1 表示最新 */
const displayNo = computed(() => (props.total ? props.index + 1 : 0));
const canOlder = computed(() => props.index < props.total - 1);
const canNewer = computed(() => props.index > 0);

function goOlder() {
  if (canOlder.value) emit('switch', props.index + 1);
}
function goNewer() {
  if (canNewer.value) emit('switch', props.index - 1);
}
</script>

<template>
  <div v-if="total > 1" class="version-switch" :title="`共 ${total} 个版本，当前第 ${displayNo} 个（1 为最新）`">
    <button class="vs-btn" :disabled="!canOlder" @click="goOlder" title="看上一个版本（更旧）">‹</button>
    <span class="vs-no">{{ displayNo }}/{{ total }}</span>
    <button class="vs-btn" :disabled="!canNewer" @click="goNewer" title="看下一个版本（更新）">›</button>
  </div>
</template>

<style scoped>
.version-switch {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 1px 4px 1px 2px;
  background: var(--panel);
  vertical-align: middle;
}
.vs-btn {
  border: none;
  background: none;
  color: var(--text-dim);
  font-size: 13px;
  line-height: 1;
  padding: 0 3px;
  cursor: pointer;
}
.vs-btn:hover:not(:disabled) {
  color: var(--accent);
}
.vs-btn:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}
.vs-no {
  font-size: 10px;
  color: var(--text-dim);
  min-width: 26px;
  text-align: center;
  font-variant-numeric: tabular-nums;
}
</style>
