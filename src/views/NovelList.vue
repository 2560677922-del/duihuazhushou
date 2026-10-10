<script setup lang="ts">
// 小说编辑器 —— 章节列表（一级界面）
//
// 和冒险页同一套交互模型：一级看列表，点进去才是编辑器。
// 章节在这里显示「标题 / 最后一段正文预览 / 字数 / 时间」。
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useNovelStore } from '../stores/novel';
import { useAssetsStore } from '../stores/assets';
import { useApiConfigStore } from '../stores/apiConfig';
import { useImageStore } from '../stores/image';
import { useStorageSettingsStore } from '../stores/storageSettings';
import { stripImageMarkers } from '../engine/imageMarker';

const router = useRouter();
const novel = useNovelStore();
const assets = useAssetsStore();
const api = useApiConfigStore();
const imageStore = useImageStore();
const storageSettings = useStorageSettingsStore();

const loaded = ref(false);
const creating = ref(false);
const pendingDelete = ref<string | null>(null);

onMounted(async () => {
  try {
    await Promise.all([
      novel.load(),
      assets.load(),
      api.load(),
      imageStore.load(),
      storageSettings.load(),
    ]);
  } catch (e) {
    console.error('[小说列表] 加载失败：', e);
  }
  loaded.value = true;
});

const chapters = computed(() =>
  [...novel.chapters].sort((a, b) => (b.updatedAt ?? 0) - (a.updatedAt ?? 0)),
);

/** 预览：优先取最后一条对话的 AI 回复，其次正文开头 */
function preview(c: { content?: string; messages?: { user?: string; ai?: string }[] }): string {
  const msgs = c.messages ?? [];
  for (let i = msgs.length - 1; i >= 0; i--) {
    const t = stripNoise(msgs[i].ai || msgs[i].user || '');
    if (t) return t;
  }
  return stripNoise(c.content || '');
}

/**
 * 去掉思维链/结构标记噪声（同冒险列表）。
 * 模型输出里常带 <thinking>、<Interleaving> 这类标记，
 * 直接显示在列表上就是一串乱码。
 */
function stripNoise(raw: string): string {
  let t = raw;
  t = t.replace(
    /<(thinking|think|Interleaving|Interleaved_thinking|plot_guide|sex_style|disclaimer|Reference_Example_format|disclaimer_format)>[\s\S]*?<\/\1>/gi,
    ' ',
  );
  t = t.replace(/<\/?[A-Za-z_][\w:-]*>/g, ' ');
  t = t.replace(/<!--[\s\S]*?-->/g, ' ');
  // [[IMG: 描述]] 是给引擎定位配图用的内部标记，绝不能显示给用户
  t = stripImageMarkers(t);
  return t.replace(/\s+/g, ' ').trim();
}

/**
 * 头像上的首字。
 * 抽成函数而不是写在模板里 —— 模板里的空字符串字面量很容易被
 * 各种工具链的引号处理搞坏（这里踩过一次，构建直接报解析错误）。
 */
function initialOf(title?: string): string {
  const t = (title || '').trim();
  return t ? t.charAt(0) : '书';
}

/**
 * 头像底色：按 id 稳定取色（同一个章节永远同色）。
 * 列表里每行有个稳定的视觉锚点，扫起来才快。
 */
const AVATAR_HUES = [210, 280, 340, 20, 45, 160, 190, 320];
function avatarStyle(id: string): Record<string, string> {
  let h = 0;
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) >>> 0;
  const hue = AVATAR_HUES[h % AVATAR_HUES.length];
  return {
    background: `hsl(${hue} 62% 88%)`,
    color: `hsl(${hue} 55% 26%)`,
    borderColor: `hsl(${hue} 40% 78%)`,
  };
}

function wordCount(c: { content?: string; messages?: { ai?: string; user?: string }[] }): number {
  let n = (c.content || '').length;
  for (const m of c.messages ?? []) n += (m.ai || '').length + (m.user || '').length;
  return n;
}

function fmtTime(ts?: number): string {
  if (!ts) return '';
  const d = new Date(ts);
  const now = new Date();
  const sameDay = (a: Date, b: Date) =>
    a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
  if (sameDay(d, now)) {
    return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  }
  const y = new Date(now.getTime() - 86400000);
  if (sameDay(d, y)) return '昨天';
  return `${d.getMonth() + 1}/${d.getDate()}`;
}

function open(id: string) {
  void router.push(`/novel/${id}`);
}

async function createChapter() {
  if (creating.value) return;
  creating.value = true;
  try {
    const c = await novel.addChapter('新章节', '');
    if (c?.id) void router.push(`/novel/${c.id}`);
  } finally {
    creating.value = false;
  }
}

async function askDelete(id: string) {
  if (pendingDelete.value !== id) {
    pendingDelete.value = id;
    setTimeout(() => {
      if (pendingDelete.value === id) pendingDelete.value = null;
    }, 4000);
    return;
  }
  pendingDelete.value = null;
  await novel.removeChapter(id);
}
</script>

<template>
  <div class="list-page">
    <header class="list-header">
      <div class="lh-left">
        <h1>小说</h1>
        <span class="lh-count">{{ chapters.length }} 个章节</span>
      </div>
      <button class="primary new-btn" @click="createChapter" :disabled="creating">
        {{ creating ? '创建中…' : '＋ 新章节' }}
      </button>
    </header>

    <div v-if="!loaded" class="empty">加载中…</div>

    <div v-else-if="!chapters.length" class="empty">
      <div class="empty-icon">📝</div>
      <h3>还没有章节</h3>
      <p>点右上角「＋ 新章节」开一个，可以直接写，也可以让 AI 接着写下一段。</p>
      <button class="primary" @click="createChapter">开始第一章</button>
    </div>

    <ul v-else class="chat-list">
      <li v-for="c in chapters" :key="c.id" class="chat-row" @click="open(c.id)">
        <div class="avatar" :style="avatarStyle(c.id)">{{ initialOf(c.title) }}</div>
        <div class="body">
          <div class="line1">
            <span class="title">{{ c.title || '未命名章节' }}</span>
            <span class="time">{{ fmtTime(c.updatedAt) }}</span>
          </div>
          <div class="line2">
            <span class="preview">
              {{ preview(c) || '空章节 —— 点进去开始写' }}
            </span>
            <span class="steps">{{ wordCount(c) }} 字</span>
          </div>
        </div>
        <button class="del" :class="{ confirm: pendingDelete === c.id }"
          @click.stop="askDelete(c.id)"
          :title="pendingDelete === c.id ? '再点一次确认删除' : '删除这个章节'">
          {{ pendingDelete === c.id ? '确认' : '✕' }}
        </button>
      </li>
    </ul>

    <div class="tail-space" />
  </div>
</template>

<style scoped>
.list-page {
  min-height: 100%;
  padding-bottom: 8px;
}
.list-header {
  position: sticky;
  top: 0;
  z-index: 25;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px;
  background: var(--glass);
  -webkit-backdrop-filter: blur(14px) saturate(1.25);
  backdrop-filter: blur(14px) saturate(1.25);
  border-bottom: 1px solid var(--border);
  box-shadow: 0 2px 14px rgba(0, 0, 0, 0.06);
}
.lh-left {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}
.list-header h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 0.3px;
}
.lh-count {
  font-size: 11.5px;
  color: var(--text-dim);
}
.new-btn {
  flex-shrink: 0;
  border-radius: var(--radius-pill);
  padding: 8px 16px;
  font-size: 13px;
}
.chat-list {
  list-style: none;
  margin: 8px 0 0;
  padding: 0 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.chat-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border-radius: var(--radius);
  background: var(--surface-1), var(--panel);
  border: 1px solid var(--border);
  border-top-color: var(--hairline);
  box-shadow: var(--shadow-1);
  cursor: pointer;
  transition: transform 0.08s, box-shadow 0.15s;
}
.chat-row:hover {
  box-shadow: var(--shadow-2);
}
.chat-row:active {
  transform: scale(0.988);
  box-shadow: var(--shadow-1);
}
.avatar {
  width: 46px;
  height: 46px;
  flex-shrink: 0;
  border-radius: 14px;
  display: grid;
  place-items: center;
  font-size: 20px;
  font-weight: 700;
  /* 底色由 avatarStyle() 按会话 id 注入，保证每行可区分 */
  border: 1px solid;
  box-shadow: var(--shadow-1);
}
.body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.line1 {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.title {
  flex: 1;
  min-width: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.time {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--text-dim);
}
.line2 {
  display: flex;
  align-items: center;
  gap: 8px;
}
.preview {
  flex: 1;
  min-width: 0;
  font-size: 12.5px;
  color: var(--text-dim);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.steps {
  flex-shrink: 0;
  font-size: 10.5px;
  padding: 1px 7px;
  border-radius: var(--radius-pill);
  background: var(--panel-2);
  border: 1px solid var(--border);
  color: var(--text-dim);
}
.del {
  flex-shrink: 0;
  width: 30px;
  height: 30px;
  padding: 0;
  border-radius: 50%;
  font-size: 12px;
  color: var(--text-dim);
  background: transparent;
  border: 1px solid transparent;
  box-shadow: none;
}
.del:hover {
  color: var(--danger);
  border-color: rgba(248, 113, 113, 0.4);
}
.del.confirm {
  width: auto;
  padding: 0 10px;
  border-radius: var(--radius-pill);
  color: #fff;
  background: var(--danger);
  border-color: var(--danger);
  font-size: 11px;
  font-weight: 600;
}
.empty {
  padding: 64px 32px;
  text-align: center;
  color: var(--text-dim);
}
.empty-icon {
  font-size: 52px;
  margin-bottom: 12px;
  opacity: 0.85;
}
.empty h3 {
  margin: 0 0 8px;
  font-size: 16px;
  color: var(--text);
}
.empty p {
  margin: 0 0 20px;
  font-size: 13px;
  line-height: 1.7;
}
.tail-space {
  height: 12px;
}

/* 手机端：左右各多让出一些、别贴边（理由同 AdventureList） */
@media (max-width: 768px) {
  .list-header {
    padding: 14px 18px;
  }
  .chat-list {
    padding: 0 14px;
  }
}
</style>
