<script setup lang="ts">
import { onMounted, ref, computed } from 'vue';
import { useAssetsStore } from '../stores/assets';
import { useApiConfigStore } from '../stores/apiConfig';
import { useImageStore } from '../stores/image';
import ModelPicker from './ModelPicker.vue';
import { useDnaStore } from '../stores/characterDna';

const assets = useAssetsStore();

/**
 * 当前预设是否引用了角色卡。
 *
 * 角色卡只能**通过预设里的宏**进入提示词（{{char}} / {{description}} /
 * {{scenario}} / {{personality}} / {{first_mes}} / {{mes_example}} / {{persona}}）。
 * 预设里一条宏都没有时，选角色卡不会有任何效果 —— 界面上要讲出来，
 * 否则用户只会觉得"这个开关坏了"。
 */
const presetUsesCharacterMacros = computed(() => {
  const preset = assets.getCurrentPreset() as { prompts?: { content?: string }[] } | undefined;
  const prompts = preset?.prompts || [];
  return prompts.some((p) =>
    /\{\{\s*(char|description|personality|scenario|first_mes|mes_example|persona)\s*\}\}/i.test(
      String(p.content || ''),
    ),
  );
});
const api = useApiConfigStore();
const image = useImageStore();
const dna = useDnaStore();

const props = defineProps<{
  compact?: boolean;
}>();

// 这里要能**直接切**大模型、生图配置和角色 DNA。
// 改配置本身仍然在「设置」/「资产」页 —— 这里只做选择，
// 避免同一个东西在两个地方能改、改完还得记得同步。
onMounted(async () => {
  if (!api.configs.length) await api.load();
  if (!image.configs.length) await image.load();
  if (!dna.items.length) await dna.load();
});

function isWorldBookSelected(uid: string) {
  return assets.currentWorldBookIds.includes(uid);
}

/*
 * 世界书的「已启用 / 未启用」两分组 + 折叠状态。
 *
 * 为什么要分组：这东西以后会有几十个。全平铺出来，
 * 面板就变成一串几十行的复选框，又长又难找 ——
 * 而且其中大部分是这次根本不想用的。
 *
 * 现在：已启用的一直显示（那是本次真正生效的），
 * 未启用的超过几个就折起来，需要时再展开。
 * 要批量管理/导入/删除，去「资产」页 —— 那里才是管理界面。
 */
const showAllWorldBooks = ref(false);
const selectedWorldBooks = computed(() =>
  assets.worldBooks.filter((w: any) => isWorldBookSelected(w._uid)),
);
const unselectedWorldBooks = computed(() =>
  assets.worldBooks.filter((w: any) => !isWorldBookSelected(w._uid)),
);
const selectedWorldBookCount = computed(() => selectedWorldBooks.value.length);

/** 正则是按「组」启用的 */
function isRegexGroupSelected(uid: string) {
  return assets.currentRegexGroupIds.includes(uid);
}
/** 组里启用了几条 */
function groupOnCount(g: any): number {
  if (g.enabled === false) return 0;
  return (g.scripts || []).filter((s: any) => !s.disabled).length;
}
</script>

<template>
  <div class="asset-panel" :class="{ compact: props.compact }">
    <!-- 接口配置：只切换，不改内容 -->
    <div class="asset-section">
      <label class="asset-label">对话模型<span class="hint-sm">（点一下切换）</span></label>
      <!-- 和对话界面标题旁那个是同一个组件（运营商 → 模型） -->
      <ModelPicker />
    </div>

    <div class="asset-section">
      <label class="asset-label">生图配置<span class="hint-sm">（只切换）</span></label>
      <select
        :value="image.currentConfigId"
        @change="(e) => image.setCurrent((e.target as HTMLSelectElement).value)"
        class="asset-select"
      >
        <option v-for="c in image.configs" :key="c._uid" :value="c._uid">
          {{ c.name || '(未命名)' }}
        </option>
      </select>
    </div>

    <div class="asset-section">
      <label class="asset-label">角色卡</label>
      <select
        :value="assets.currentCharacterId"
        @change="(e) => assets.setCurrentCharacter((e.target as HTMLSelectElement).value || undefined)"
        class="asset-select"
      >
        <option value="">（无）</option>
        <option v-for="c in assets.characters" :key="c._uid" :value="c._uid">
          {{ c.name || '(未命名)' }}
        </option>
      </select>
      <!--
        提示：角色卡的进入方式有两种。
        ① 预设里写了 {{char}} / {{description}} / {{scenario}} 这些宏 → 由预设决定放哪；
        ② 预设一条宏都没有 → 应用会**自动补一条 system 消息**把角色卡带上
           （见 engine/assembler.ts 的 injectCharacterCardIfMissing）。

        所以预设不引用时**依然会生效**，只是位置由应用决定。
        这里只需要告诉用户"当前是自动模式"，别让人误以为没生效。
      -->
      <p v-if="assets.currentCharacterId && !presetUsesCharacterMacros" class="tip-sm">
        ℹ 当前预设没有引用角色卡，已按「自动注入」处理 ——
        角色设定会作为一条系统消息附在对话前面，一样会生效。
      </p>
    </div>

    <div class="asset-section">
      <label class="asset-label">预设</label>
      <select
        :value="assets.currentPresetId"
        @change="(e) => assets.setCurrentPreset((e.target as HTMLSelectElement).value || undefined)"
        class="asset-select"
      >
        <option value="">（无）</option>
        <option v-for="p in assets.presets" :key="p._uid" :value="p._uid">
          {{ p.name || '(未命名)' }}
        </option>
      </select>
    </div>

    <!--
      世界书：拆成「已启用」和「全部」两层。
      
      为什么这么改：这东西以后会有几十个。如果全列出来，
      面板就变成一个几十行的复选框长列表，又长又难找 ——
      而且里面大部分是他这次根本不想用的。
      
      现在的做法（微信/Notion 那种）：
        · 默认只显示**已启用的**几条（这些才是本次对话真正生效的）
        · 列表超过 6 条时，其余折叠起来，点「全部」才展开
        · 想批量管理/导入/删除，去「资产」页（那是管理界面）
    -->
    <div class="asset-section" v-if="!props.compact">
      <label class="asset-label">
        世界书
        <span class="hint-sm">已选 {{ selectedWorldBookCount }} / {{ assets.worldBooks.length }}</span>
      </label>

      <div class="asset-checkboxes" v-if="assets.worldBooks.length">
        <!-- 已启用的：永远显示在最上面，一眼看到"这次用了什么" -->
        <label v-for="w in selectedWorldBooks" :key="w._uid" class="checkbox-row">
          <input
            type="checkbox"
            :checked="true"
            @change="(e) => assets.toggleWorldBook(w._uid, (e.target as HTMLInputElement).checked)"
          />
          <span class="truncate">{{ w.name || '(未命名)' }}</span>
        </label>

        <!-- 未启用的：数量多了就折叠，避免长列表刷屏 -->
        <template v-if="showAllWorldBooks || unselectedWorldBooks.length <= 4">
          <label v-for="w in unselectedWorldBooks" :key="w._uid" class="checkbox-row dim">
            <input
              type="checkbox"
              :checked="false"
              @change="(e) => assets.toggleWorldBook(w._uid, (e.target as HTMLInputElement).checked)"
            />
            <span class="truncate">{{ w.name || '(未命名)' }}</span>
          </label>
        </template>
        <button
          v-else
          class="more-btn"
          @click="showAllWorldBooks = true"
        >
          还有 {{ unselectedWorldBooks.length }} 个未启用… 展开
        </button>
        <button
          v-if="showAllWorldBooks && unselectedWorldBooks.length > 4"
          class="more-btn"
          @click="showAllWorldBooks = false"
        >
          收起
        </button>
      </div>
      <div v-else class="hint-sm">无（到「资产」页导入）</div>
    </div>

    <!-- 角色 DNA：只负责"用不用"，编辑在「资产 → 角色 DNA」 -->
    <div class="asset-section" v-if="!props.compact && dna.items.length">
      <label class="asset-label">
        角色 DNA
        <span class="hint-sm">（勾选要锚定的外观）</span>
      </label>
      <div class="asset-checkboxes">
        <!--
          ★ 去掉了原来那个五角星按钮。
          用户的反馈："我都有点看不懂哪个是开启了，已经有勾选了，
          就不要搞那个五角星了。"
          原因：勾选框和星号挨在一起，看起来像"两个开关"，分不清哪个是启用。

          现在：**只有一个勾选框**，它就是这个 DNA 是否参与生图。
          以前的「常驻」是第二套开关（只管自动配图），两个开关互相影响，
          谁也说不清哪条最终生效 —— 已删除，改成勾选即全生效。
        -->
        <label v-for="d in dna.items" :key="d._uid" class="checkbox-row">
          <input
            type="checkbox"
            :checked="d.enabled !== false"
            @change="(e) => dna.setEnabled(d._uid, (e.target as HTMLInputElement).checked)"
          />
          <span class="truncate" :title="d.dna || '(未填写描述)'">{{ d.name }}</span>
        </label>
      </div>
    </div>

    <div class="asset-section" v-if="!props.compact">
      <label class="asset-label">
        正则组
        <span class="hint-sm">（{{ assets.regexGroups.length }} 组 / {{ assets.regexScriptCount }} 条）</span>
      </label>
      <div class="asset-checkboxes" v-if="assets.regexGroups.length">
        <label v-for="g in assets.regexGroups" :key="g._uid" class="checkbox-row">
          <input
            type="checkbox"
            :checked="isRegexGroupSelected(g._uid)"
            @change="(e) => assets.toggleRegexGroup(g._uid, (e.target as HTMLInputElement).checked)"
          />
          <span class="truncate">{{ g.name || '(未命名组)' }}</span>
          <span class="badge-sm">{{ groupOnCount(g) }}/{{ (g.scripts || []).length }}</span>
        </label>
      </div>
      <div v-else class="hint-sm">无（到「资产」页导入）</div>
    </div>

    <div class="asset-stats" v-if="!props.compact">
      <span class="badge">{{ assets.characters.length }} 角色</span>
      <span class="badge">{{ assets.worldBooks.length }} 世界书</span>
      <span class="badge">{{ assets.regexGroups.length }} 正则组</span>
      <span class="badge">{{ assets.presets.length }} 预设</span>
    </div>
  </div>
</template>

<style scoped>
.asset-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.asset-section {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.asset-label {
  font-size: 11px;
  color: var(--text-dim);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.badge-sm {
  font-size: 9px;
  padding: 0 5px;
  border-radius: 999px;
  background: var(--code-bg);
  color: var(--text-dim);
  border: 1px solid var(--border);
  text-transform: none;
  letter-spacing: 0;
}
.asset-select {
  font-size: 12px;
  padding: 6px 8px;
}
.asset-checkboxes {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
/*
 * 「还有 N 个未启用… 展开」——把长列表收起来，
 * 让面板默认只呈现"本次生效的东西"。
 */
/*
 * 面板里的提示小字（比如"角色卡按自动注入处理"）。
 * 用中性色 —— 这不是警告，只是说明当前行为。
 */
.tip-sm {
  margin: 6px 0 0;
  padding: 6px 9px;
  border-radius: var(--radius-sm);
  font-size: 10.5px;
  line-height: 1.5;
  background: color-mix(in srgb, var(--accent) 9%, transparent);
  color: var(--text-dim);
  border: 1px solid color-mix(in srgb, var(--accent) 18%, transparent);
}
.tip-sm code {
  font-size: 10px;
  padding: 0 3px;
  border-radius: 3px;
  background: color-mix(in srgb, var(--text) 12%, transparent);
}

.more-btn {
  align-self: flex-start;
  margin: 2px 0 0;
  padding: 5px 12px;
  font-size: 11.5px;
  border-radius: var(--radius-pill);
  background: var(--panel-2);
  border: 1px dashed var(--border);
  color: var(--text-dim);
  box-shadow: none;
}
.more-btn:hover {
  color: var(--accent);
  border-color: var(--accent);
}
/* 未启用的项淡一点，和已启用的拉开层次 */
.checkbox-row.dim {
  opacity: 0.62;
}

.checkbox-row {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text);
  cursor: pointer;
  padding: 2px 0;
}
/*
 * 「还有 N 个未启用… 展开」——把长列表收起来，
 * 让面板默认只呈现"本次生效的东西"。
 */

.more-btn {
  align-self: flex-start;
  margin: 2px 0 0;
  padding: 5px 12px;
  font-size: 11.5px;
  border-radius: var(--radius-pill);
  background: var(--panel-2);
  border: 1px dashed var(--border);
  color: var(--text-dim);
  box-shadow: none;
}
.more-btn:hover {
  color: var(--accent);
  border-color: var(--accent);
}
/* 未启用的项淡一点，和已启用的拉开层次 */
.checkbox-row.dim {
  opacity: 0.62;
}

.checkbox-row input {
  width: auto;
  flex-shrink: 0;
}
.truncate {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 180px;
}
.hint-sm {
  font-size: 11px;
  color: var(--text-dim);
  font-style: italic;
}
.asset-stats {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
  padding-top: 6px;
  border-top: 1px solid var(--border);
}
.badge {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--panel-2);
  color: var(--text-dim);
  font-size: 10px;
  border: 1px solid var(--border);
}
</style>
