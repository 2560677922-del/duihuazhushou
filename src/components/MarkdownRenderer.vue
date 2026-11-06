<script setup lang="ts">
import { computed } from 'vue';
import MarkdownIt from 'markdown-it';

const md = new MarkdownIt({
  html: true,
  breaks: true,
  linkify: true,
});

const props = defineProps<{
  content: string;
}>();

const html = computed(() => md.render(props.content));
</script>

<template>
  <div class="markdown-body" v-html="html"></div>
</template>

<style scoped>
.markdown-body {
  font-size: 14px;
  line-height: 1.7;
  color: var(--text);
  word-break: break-word;
  overflow-wrap: anywhere;
  /* 美化正则输出的 <pre> 代码块很长，别让它把消息气泡撑出屏幕 */
  min-width: 0;
  max-width: 100%;
  /* 双保险：老 WebView 不认 overflow-x:clip，用 hidden 全平台生效 */
  overflow-x: hidden;
}
.markdown-body :deep(p) {
  margin: 0 0 8px;
}
.markdown-body :deep(p:last-child) {
  margin-bottom: 0;
}
.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin: 12px 0 6px;
  font-weight: 600;
  color: var(--text);
}
.markdown-body :deep(h1) { font-size: 18px; }
.markdown-body :deep(h2) { font-size: 16px; }
.markdown-body :deep(h3) { font-size: 15px; }
.markdown-body :deep(code) {
  background: var(--code-bg);
  padding: 2px 6px;
  border-radius: 4px;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 13px;
  color: var(--accent-2);
}
.markdown-body :deep(pre) {
  background: var(--code-bg);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 12px;
  /* 长行只在代码块内部滚，不把父级 flex 容器撑宽 */
  min-width: 0;
  max-width: 100%;
  overflow-x: auto;
  margin: 8px 0;
}
.markdown-body :deep(pre code) {
  background: none;
  padding: 0;
  color: var(--text);
}
.markdown-body :deep(blockquote) {
  border-left: 3px solid var(--accent);
  padding: 4px 12px;
  margin: 8px 0;
  color: var(--text-dim);
  background: var(--panel-2);
  border-radius: 0 6px 6px 0;
}
.markdown-body :deep(em) {
  color: var(--accent-2);
  font-style: italic;
}
.markdown-body :deep(strong) {
  font-weight: 700;
  color: var(--warn);
}
.markdown-body :deep(a) {
  color: var(--accent);
  text-decoration: none;
  border-bottom: 1px solid var(--accent);
}
.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  padding-left: 20px;
  margin: 8px 0;
}
.markdown-body :deep(li) {
  margin-bottom: 4px;
}
.markdown-body :deep(hr) {
  border: none;
  border-top: 1px solid var(--border);
  margin: 12px 0;
}
</style>
