<script setup lang="ts">
// 冒险 —— 会话列表（一级界面）
//
// 交互模型照微信/QQ 来：
//   一级 = 会话列表（每条显示 标题 / 最后一句预览 / 时间）
//   二级 = 点进去才是真正的对话界面（那里有输入框、消息流）
//
// 这样"进入对话"是一个明确的动作，进去之后界面就干净了 ——
// 不会像以前那样：对话页里还塞着会话列表、侧栏、一堆配置入口。
import { computed, nextTick, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useAdventureStore } from '../stores/adventure';
import { useAssetsStore } from '../stores/assets';
import { useApiConfigStore } from '../stores/apiConfig';
import { useImageStore } from '../stores/image';
import { useStorageSettingsStore } from '../stores/storageSettings';
import AppIcon from '../components/AppIcon.vue';
import { stripImageMarkers } from '../engine/imageMarker';
import {
  sampleCharacterRaw,
  samplePresetRaw,
  sampleWorldbookRaw,
  sampleRegexRaw,
} from '../engine/sampleData';

const router = useRouter();
const adventure = useAdventureStore();
const assets = useAssetsStore();
const api = useApiConfigStore();
const imageStore = useImageStore();
const storageSettings = useStorageSettingsStore();

const loaded = ref(false);
const creating = ref(false);
/** 待删除的会话（两步确认，不用原生 confirm —— 手机上很难看） */
const pendingDelete = ref<string | null>(null);

onMounted(async () => {
  try {
    await Promise.all([
      adventure.load(),
      assets.load(),
      api.load(),
      imageStore.load(),
      storageSettings.load(),
    ]);
  } catch (e) {
    console.error('[冒险列表] 加载失败：', e);
  }
  loaded.value = true;
});

/** 按最近更新排序：刚聊过的排最前，和微信一致 */
const sessions = computed(() =>
  [...adventure.sessions].sort((a, b) => (b.updatedAt ?? 0) - (a.updatedAt ?? 0)),
);

/** 最后一句预览：取这条会话最后一步的 AI 回复（没有就用用户那句） */
function preview(s: { actions?: { user?: string; ai?: string }[] }): string {
  const list = s.actions ?? [];
  for (let i = list.length - 1; i >= 0; i--) {
    const a = list[i];
    /*
     * ★ 只截开头一小段再清理。
     *
     * 预览最终也就显示几十个字，却对整条几万甚至几十万字的回复跑正则 ——
     * 历史数据里若残留内嵌 base64，这里的清理会灾难性回溯，把主线程锁住：
     * 表现正是"一级列表啥都出不来、切别的页面也卡住、隔一会儿才恢复"。
     * 截断到 2000 字后，最坏情况也只是毫秒级。
     */
    const t = stripNoise((a.ai || a.user || '').slice(0, 2000));
    if (t) return t.slice(0, 120);
  }
  return '';
}

/**
 * 去掉思维链/结构标记类的噪声，压成一行。
 *
 * 为什么必须做：推理模型的输出里常常混着 `<Interleaving> <thinking>`
 * 这类标记，直接显示在会话列表上就是一串看不懂的乱码。
 * 这里用轻量的正则清理，不跑完整正则引擎 —— 列表渲染要快。
 */
function stripNoise(raw: string): string {
  let t = raw;
  // 成对的推理/结构标签，连同里面的内容一起去掉
  t = t.replace(
    /<(thinking|think|Interleaving|Interleaved_thinking|plot_guide|sex_style|disclaimer|Reference_Example_format|disclaimer_format)>[\s\S]*?<\/\1>/gi,
    ' ',
  );
  // 落单的尖括号标记（开或闭）也清掉
  t = t.replace(/<\/?[A-Za-z_][\w:-]*>/g, ' ');
  // 注释式指令 <!-- ... -->
  t = t.replace(/<!--[\s\S]*?-->/g, ' ');
  // [[IMG: 描述]] 是给引擎定位配图用的内部标记，绝不能显示给用户
  t = stripImageMarkers(t);
  return t.replace(/\s+/g, ' ').trim();
}

/** 相对时间：今天显示时分，昨天显示"昨天"，更早显示日期 */
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

/** 没有开场场景也没聊过 —— 给个提示，避免用户以为坏了 */
function isFresh(s: { actions?: unknown[]; sceneDescription?: string }): boolean {
  return !(s.actions?.length) && !s.sceneDescription?.trim();
}

/*
 * 「载入示例资产」—— 从旧首页搬过来的。
 *
 * 那是新用户唯一的"一键跑起来"入口，删首页时不能把它一起删掉，
 * 所以挪到列表的空状态：没数据的时候正好需要它。
 */
const sampleLoading = ref(false);
const sampleLoaded = computed(
  () => assets.characters.length > 0 || assets.presets.length > 0,
);
async function loadSamples() {
  if (sampleLoading.value || sampleLoaded.value) return;
  sampleLoading.value = true;
  try {
    await assets.importCharacter(sampleCharacterRaw);
    await assets.importPreset(samplePresetRaw);
    await assets.importWorldBook(sampleWorldbookRaw);
    await assets.importRegexScript(sampleRegexRaw);
    // 顺手把它们设为当前启用，这样立刻就能发消息
    const lastOf = <T,>(arr: T[]) => arr[arr.length - 1];
    if (assets.characters.length) assets.currentCharacterId = (lastOf(assets.characters) as any)?._uid;
    if (assets.presets.length) assets.currentPresetId = (lastOf(assets.presets) as any)?._uid;
    assets.currentWorldBookIds = assets.worldBooks.map((w: any) => w._uid);
    assets.currentRegexGroupIds = assets.regexGroups.map((g: any) => g._uid);
    await assets.save();
  } catch (e) {
    console.error('[示例资产] 载入失败：', e);
  } finally {
    sampleLoading.value = false;
  }
}

/**
 * 头像底色：按 id 稳定取色（同一个会话永远同色）。
 *
 * 用色相环上均匀分布的几个色，配深色文字保证可读。
 * 目的只是"可区分"，不是好看 —— 列表里有个稳定的视觉锚点就够了。
 */
/** 头像上的首字（抽成函数，避免模板里写空字符串被工具链搞坏） */
function initialOf(title?: string): string {
  const t = (title || '').trim();
  return t ? t.charAt(0) : '冒';
}

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

function open(id: string) {
  void router.push(`/adventure/${id}`);
}

async function createSession() {
  if (creating.value) return;
  creating.value = true;
  try {
    const s = await adventure.addSession('新冒险', '');
    if (s?.id) void router.push(`/adventure/${s.id}`);
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
  await adventure.removeSession(id);
}

/* ---------------- 改名 ---------------- */

/** 正在改名的会话 id（null = 没有在改） */
const renamingId = ref<string | null>(null);
const renameText = ref('');
/**
 * 改名输入框的元素。
 *
 * 注意：这里在 v-for 里，用 `ref="name"` 会拿到**数组**（每个会话一个），
 * 直接 .focus() 会报 "not a function"（实测）。所以用函数 ref 手动接住元素。
 */
const renameInput = ref<HTMLInputElement | null>(null);
function bindRenameInput(el: any) {
  renameInput.value = (el as HTMLInputElement) ?? null;
}

/** 点标题进入编辑；@click.stop 保证不会顺带把会话打开 */
function startRename(s: any) {
  renamingId.value = s.id;
  renameText.value = s.title || '';
  // 等输入框渲染出来再聚焦并全选，省得用户还得自己点一下
  void nextTick(() => {
    renameInput.value?.focus();
    renameInput.value?.select();
  });
}

async function commitRename() {
  const id = renamingId.value;
  if (!id) return;
  renamingId.value = null;
  await adventure.updateSessionTitle(renameText.value, id);
}

function cancelRename() {
  renamingId.value = null;
}
</script>

<template>
  <div class="list-page">
    <!-- 顶部：标题 + 新建 -->
    <header class="list-header">
      <div class="lh-left">
        <span class="lh-ico"><AppIcon name="adventure" :size="22" /></span>
        <div class="lh-text">
          <h1>冒险</h1>
          <span class="lh-count">{{ sessions.length }} 个会话</span>
        </div>
      </div>
      <button class="primary new-btn" @click="createSession" :disabled="creating">
        {{ creating ? '创建中…' : '＋ 新冒险' }}
      </button>
    </header>

    <div v-if="!loaded" class="empty">加载中…</div>

    <!-- 空状态：第一次打开的人在这里拿到明确的第一步，而不是一片空白 -->
    <div v-else-if="!sessions.length" class="empty">
      <div class="empty-icon"><AppIcon name="adventure" :size="40" /></div>
      <h3>开始你的第一个冒险</h3>
      <p>写下你的第一步行动，AI 会接着写下去。<br />还没配好？可以先载入一套示例资产试玩。</p>
      <div class="empty-actions">
        <button class="primary" @click="createSession">＋ 新冒险</button>
        <button @click="loadSamples" :disabled="sampleLoading || sampleLoaded">
          {{ sampleLoaded ? '示例已载入' : sampleLoading ? '载入中…' : '载入示例资产' }}
        </button>
      </div>
    </div>

    <!-- 会话列表 -->
    <ul v-else class="chat-list">
      <li v-for="s in sessions" :key="s.id" class="chat-row" @click="open(s.id)">
        <!--
          头像用标题首字 + 按 id 稳定取色。
          之前所有会话都是同一个 🎭 图标，两行看起来一模一样、扫不清 ——
          微信之所以好用，很大程度是每行有可区分的视觉锚点。
        -->
        <div class="avatar" :style="avatarStyle(s.id)">{{ initialOf(s.title) }}</div>
        <div class="body">
          <div class="line1">
            <!--
              点标题＝改名（@click.stop 防止顺带打开会话）。
              以前标题是死的，所有会话永远叫「新冒险」，完全没法区分。
            -->
            <input v-if="renamingId === s.id" :ref="bindRenameInput" class="rename-input"
              v-model="renameText" maxlength="40"
              @click.stop
              @keyup.enter="commitRename"
              @keyup.esc="cancelRename"
              @blur="commitRename" />
            <span v-else class="title" @click.stop="startRename(s)"
              :title="'点一下改名：' + (s.title || '未命名会话')">
              {{ s.title || '未命名会话' }}
            </span>
            <span class="time">{{ fmtTime(s.updatedAt) }}</span>
          </div>
          <div class="line2">
            <span class="preview">
              {{ isFresh(s) ? '空会话 —— 点进去写下第一步' : (preview(s) || '（还没有内容）') }}
            </span>
            <span v-if="s.actions?.length" class="steps">{{ s.actions.length }} 步</span>
          </div>
        </div>
        <button class="del" :class="{ confirm: pendingDelete === s.id }"
          @click.stop="askDelete(s.id)"
          :title="pendingDelete === s.id ? '再点一次确认删除' : '删除这个会话'">
          {{ pendingDelete === s.id ? '确认' : '✕' }}
        </button>
      </li>
    </ul>

    <!-- 底部留白：别被标签栏压住 -->
    <div class="tail-space" />
  </div>
</template>

<style scoped>
.list-page {
  min-height: 100%;
  padding-bottom: 8px;
}

/* 顶部标题栏：吸顶 + 毛玻璃，跟整体风格一致 */
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
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.lh-ico {
  flex-shrink: 0;
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  border-radius: var(--radius-sm);
  background: color-mix(in srgb, var(--accent) 22%, var(--panel-2));
  border: 1px solid color-mix(in srgb, var(--accent) 16%, transparent);
  color: var(--accent);
}
.lh-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.list-header h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 0.3px;
  line-height: 1.2;
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

/* ---- 列表 ---- */
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
  padding: 12px 12px;
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
/* 按下去轻微下沉，有"点到了"的反馈 */
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
  /* 可点：点一下改名 */
  cursor: text;
  border-radius: 4px;
  padding: 0 2px;
  margin: 0 -2px;
}
.title:hover {
  background: var(--panel-2);
}
.rename-input {
  flex: 1;
  min-width: 0;
  font-size: 15px;
  font-weight: 600;
  padding: 2px 6px;
  border-radius: 5px;
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
/* 预览只给一行，多了就省略 —— 列表里整齐比信息量重要 */
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
/* 二次确认态：变红加宽，明确"再点一次就删" */
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

/* ---- 空/加载 ---- */
.empty {
  padding: 64px 32px;
  text-align: center;
  color: var(--text-dim);
}
.empty-icon {
  margin-bottom: 12px;
  color: var(--accent);
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

/* 空状态的两个按钮：并排，主操作在前 */
.empty-actions {
  display: flex;
  gap: 10px;
  justify-content: center;
  flex-wrap: wrap;
}

.tail-space {
  height: 12px;
}

/*
 * 手机端：左右各多让出一些、别贴边。
 *
 * 原来 .chat-list 只有 8px 内边距，卡片（自己还有 12px 内边距）
 * 的**外沿**离屏幕边只有 8px —— 全面屏的圆角和手势区就压在那儿，
 * 点边缘很不舒服，看着也局促。
 */
@media (max-width: 768px) {
  .list-header {
    padding: 14px 18px;
  }
  .chat-list {
    padding: 0 14px;
  }
}
</style>
