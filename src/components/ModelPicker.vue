<script setup lang="ts">
// LLM 模型选择器：运营商 → 模型 两级弹层
//
// 为什么做成两级：
//   真实用法是"一家中转/官方下面挂多个模型"。以前的配置是
//   "运营商 + 模型"打包成一条，所以同一家会出现好几条重复记录
//   （同一个 key 存好几遍），选模型时看到的是一串几乎同名的选项。
//
// 现在的交互（用户明确要求的形态）：
//   点标题栏上的当前模型 → 往下弹出**运营商列表**（带备注）
//   再点某个运营商       → 往**侧面**弹出它下面的模型
//   点模型               → 立刻切换
//
// 数据层没动：运营商是从 baseUrl 派生出来的分组（见 apiConfig store
// 的 providers getter），所以老的 35+ 处调用点全不受影响。
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { useApiConfigStore } from '../stores/apiConfig';

/*
 * 两种用法，一个组件：
 *
 *  1) 不传 modelValue  → "切换当前对话模型"（对话界面标题旁用）。
 *     点模型直接 api.setCurrent()，全局生效。
 *
 *  2) 传了 modelValue / allowFollow → "给这一项单独指定模型"
 *     （生图写提示词用的模型、摘要模型…）。这时作为受控组件，
 *     选中后 emit('update:modelValue')，存到哪由调用方决定。
 *     空值（''）表示"跟随当前对话模型"。
 *
 * 为什么合并：同一个"运营商 → 模型"的交互如果在两处各写一套，
 * 早晚会走偏（改了一处忘另一处）。合成一个组件最稳。
 */
const props = defineProps<{
  /** 受控值；不传即"切换全局当前模型"模式 */
  modelValue?: string;
  /** 是否提供"跟随当前对话模型"这个空选项 */
  allowFollow?: boolean;
  /** 按钮前缀说明，如"提示词模型" */
  label?: string;
}>();

const emit = defineEmits<{ 'update:modelValue': [value: string | undefined] }>();

const api = useApiConfigStore();

const open = ref(false);
/** 当前展开的运营商（第二层） */
const openProvider = ref<string | null>(null);
const rootEl = ref<HTMLElement | null>(null);

const providers = computed(() => api.providers);

/** 受控模式下，当前生效的是外面传进来的那条；否则是全局当前配置 */
const current = computed(() => {
  if (props.modelValue !== undefined) {
    return api.configs.find((c) => c._uid === props.modelValue);
  }
  return api.currentConfig;
});
const currentProvider = computed(() => {
  const cur = current.value;
  if (!cur) return undefined;
  const key = (cur.baseUrl || '').trim().replace(/\/+$/, '').toLowerCase();
  return { key, note: api.providerNotes[key] || '', baseUrl: cur.baseUrl || '' };
});

/** 按钮上显示的文字 */
const currentLabel = computed(() => {
  if (!current.value) {
    // 受控模式下没有值 = 跟随当前对话模型
    if (props.modelValue === '') {
      const follow = api.currentConfig?.model;
      return follow ? '跟随对话（' + follow + '）' : '跟随对话模型';
    }
    return props.modelValue === undefined ? '(未配置模型)' : '跟随对话模型';
  }
  return current.value.model || '(未填模型名)';
});

/** 当前该打勾的那个（受控模式看传入值，全局模式看全局当前） */
const activeUid = computed(() =>
  props.modelValue !== undefined ? props.modelValue : api.currentConfigId,
);

function toggle() {
  open.value = !open.value;
  if (open.value) {
    // 打开时默认展开当前模型所在那家，省一次点击
    openProvider.value = currentProvider.value?.key ?? null;
  } else {
    openProvider.value = null;
  }
}

function pickProvider(key: string) {
  openProvider.value = openProvider.value === key ? null : key;
}

/**
 * 选模型。
 *  全局模式 → 改全局当前配置
 *  受控模式 → 只 emit，不碰全局状态（调用方自己存）
 */
async function pickModel(uid: string) {
  if (props.modelValue !== undefined || props.allowFollow) {
    emit('update:modelValue', uid);
  } else {
    await api.setCurrent(uid);
  }
  open.value = false;
  openProvider.value = null;
}

/** 选"跟随当前对话模型" */
function pickFollow() {
  emit('update:modelValue', undefined);
  open.value = false;
  openProvider.value = null;
}

/**
 * 点外面 / 按 Esc 关掉。
 *
 * 注意两个坑：
 *  1) **不能用捕获阶段**（addEventListener 的第三个参数）。用捕获时，
 *     点击弹层内部的元素也会先被这个监听器拿到 —— 它一看"不在 rootEl 里"
 *     就把弹层关了，于是点运营商根本展不开模型（实测踩到过）。
 *     用冒泡（默认）就不会：内部点击会先冒泡到 .mp-panel，
 *     那个 @click.stop 已经拦住了。
 *  2) 用 `closest` 而不是 `contains` 判断：更直观地表达
 *     "点在弹层这棵子树里"。
 */
function onDocClick(e: MouseEvent) {
  if (!open.value) return;
  const el = rootEl.value;
  const target = e.target as Node | null;
  if (el && target && el.contains(target)) return;
  open.value = false;
  openProvider.value = null;
}
function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape' && open.value) {
    open.value = false;
    openProvider.value = null;
  }
}

onMounted(() => {
  // 冒泡阶段（不加 true），配合面板上的 @click.stop 使用
  document.addEventListener('click', onDocClick);
  document.addEventListener('keydown', onKey);
});
onUnmounted(() => {
  document.removeEventListener('click', onDocClick);
  document.removeEventListener('keydown', onKey);
});

/** 运营商的显示名：有备注用备注，否则用域名 */
function providerLabel(p: { note: string; baseUrl: string }) {
  if (p.note) return p.note;
  try {
    return new URL(p.baseUrl).host;
  } catch {
    return p.baseUrl || '(未填地址)';
  }
}
</script>

<template>
  <div ref="rootEl" class="model-picker">
    <!-- 触发按钮：显示当前模型 -->
    <button class="mp-trigger" :class="{ on: open, block: !!label }" @click="toggle"
      :title="(label ? label + '：' : '当前模型：') + currentLabel + '（点一下切换）'">
      <span class="mp-dot" :class="{ warn: current ? !current.apiKey : false }"></span>
      <span v-if="label" class="mp-prefix">{{ label }}</span>
      <span class="mp-name">{{ currentLabel }}</span>
      <span class="mp-caret">▾</span>
    </button>

    <!-- 第一层：运营商列表 -->
    <div v-if="open" class="mp-panel" @click.stop>
      <div class="mp-head">选择模型</div>

      <!-- 受控模式下的"跟随"选项 -->
      <button v-if="allowFollow" class="mp-follow" :class="{ current: !modelValue }" @click="pickFollow">
        <span class="mp-mname">跟随当前对话模型</span>
        <span v-if="!modelValue" class="mp-check">✓</span>
      </button>

      <div v-if="!providers.length" class="mp-empty">还没有配置，去「设置 → 接口」加一个</div>

      <div v-for="p in providers" :key="p.key" class="mp-provider">
        <button class="mp-provider-row" :class="{ active: openProvider === p.key }" @click="pickProvider(p.key)">
          <span class="mp-plabel">
            <span class="mp-host">{{ providerLabel(p) }}</span>
            <span class="mp-count">{{ p.models.length }} 个模型</span>
          </span>
          <span v-if="!p.hasKey" class="mp-nokey" title="这家还没填密钥">未填密钥</span>
          <span class="mp-arrow">›</span>
        </button>

        <!--
          第二层：这家下面的模型。
          宽屏往侧面弹（用户要的形态）；窄屏没横向空间，
          就改成在这行下面展开 —— 手机上"往侧面弹"会挤成一条缝。
        -->
        <div v-if="openProvider === p.key" class="mp-models">
          <button
            v-for="m in p.models"
            :key="m._uid"
            class="mp-model"
            :class="{ current: m._uid === activeUid }"
            @click="pickModel(m._uid)"
          >
            <span class="mp-mname">{{ m.model || '(未填模型名)' }}</span>
            <span v-if="m._uid === activeUid" class="mp-check">✓</span>
          </button>
        </div>
      </div>

      <div class="mp-foot">
        <a href="#/settings" class="mp-manage" @click="open = false">管理接口与密钥 →</a>
      </div>
    </div>
  </div>
</template>

<style scoped>
.model-picker {
  position: relative;
  /*
   * 宽度上**允许收缩**（原来是 flex-shrink: 0 = 永不缩）。
   *
   * 不缩的话，遇到右边还有一排按钮的场合（聊天顶栏），
   * 它会硬撑住自己那份宽度，把右边的按钮挤出屏幕 → 视觉上叠在一起。
   * 收缩后由父级分配宽度，`.mp-trigger` 内部省略长名字。
   */
  min-width: 0;
  flex: 0 1 auto;
  /*
   * 关键：让自己处在**更高的层叠上下文**里。
   *
   * 弹层虽然写了 z-index: 60，但它被父级（头部里的 .header-left）的
   * 层叠上下文困住了。同一个头部里右侧还有一排按钮（冒险/摘要/显示资产），
   * 它们在后、DOM 顺序更靠后 —— 于是就盖在弹层上面，
   * 看着像"选择模型"那行和导航栏叠在一起（实测出现过）。
   *
   * 给容器 z-index 抬上去，弹层就能正常浮在整行控件之上。
   * 数值取 30：高于头部自身的 25，低于各抽屉的 40（抽屉仍能盖住它）。
   */
  z-index: 30;
}

/* ---- 触发按钮：做成小胶囊，不抢标题的位置 ---- */
.mp-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  /*
   * 宽度改成"**有多少给多少**"，不再写死百分比。
   *
   * 之前是 `max-width: 62vw`（390px 屏约 240px）。但聊天顶栏右边还有
   * 「生图 / 摘要 / ⋯」三个按钮要 200px，62vw + 200px 超过屏宽 ——
   * 结果模型选择器和按钮**叠在一起**（实测重叠 64px）。
   *
   * 现在：让它作为可收缩的 flex 子项（`min-width: 0` + `flex: 0 1 auto`），
   * 空间不够时自己让位给右边的按钮；`.mp-name` 内部省略。
   * `block` 模式（表单里单独一行）不受影响，仍占满整行。
   */
  min-width: 0;
  flex: 0 1 auto;
  max-width: 100%;
  padding: 4px 10px;
  border-radius: var(--radius-pill);
  background: var(--surface-1), var(--panel-2);
  border: 1px solid var(--border);
  border-top-color: var(--hairline-strong);
  box-shadow: var(--shadow-1);
  font-size: 11.5px;
  color: var(--text-dim);
}
.mp-trigger.on {
  border-color: var(--accent);
  color: var(--accent);
}
/*
 * label 模式（表单里用）：占满整行、模型名不截断。
 * 标题栏那种紧凑场景才需要省略号，表单里有的是横向空间。
 */
.mp-trigger.block {
  width: 100%;
  max-width: none;
  justify-content: flex-start;
}
.mp-trigger.block .mp-name {
  flex: 1;
  overflow: visible;
  text-overflow: clip;
}
.mp-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent-2, #86efac);
  flex-shrink: 0;
}
.mp-dot.warn {
  background: var(--warn);
}
.mp-prefix {
  font-size: 10px;
  color: var(--text-dim);
  flex-shrink: 0;
}
.mp-follow {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  padding: 8px 10px;
  margin-bottom: 4px;
  border-radius: var(--radius-sm);
  background: transparent;
  border: none;
  box-shadow: none;
  text-align: left;
  font-size: 12px;
  color: var(--text-dim);
}
.mp-follow:hover {
  background: var(--panel-2);
}
.mp-follow.current {
  color: var(--accent);
  font-weight: 700;
}
.mp-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 600;
  color: var(--text);
}
.mp-caret {
  font-size: 9px;
  opacity: 0.7;
}

/* ---- 弹层 ---- */
.mp-panel {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  z-index: 60;
  /*
   * 关键：弹层宽度**不能跟着触发按钮走**。
   *
   * 之前是 `left: 0; right: 0` —— 意思是"和按钮一样宽"。
   * 而按钮为了不挤掉标题栏，被限制在 max-width: 46vw。
   * 结果弹层也只有那么窄，里面的模型名（gemini-3.1-pro-preview 这种）
   * 全被省略号截掉 —— 用户根本选不准是哪个（这是被反馈的问题）。
   *
   * 现在给弹层自己的最小宽度：足够放下常见的模型全名。
   * 手机上取 88vw 保证不超出屏幕。
   */
  min-width: min(300px, 88vw);
  max-width: min(360px, 92vw);
  /*
   * 用**不透明**底色，不是 var(--panel)。
   *
   * --panel 在浅色主题下是 rgba(255,255,255,0.92)（半透明，为了玻璃质感）。
   * 但下拉面板浮在头部之上，背后正好是"冒险/摘要/显示资产"那一排按钮 ——
   * 半透明会让它们透出来，看着像文字叠在一起（实测就是这样）。
   * 弹出层要挡住底下内容，所以这里用实色。
   */
  background: var(--panel-solid, var(--panel));
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-3);
  padding: 6px;
  /* 运营商多的时候自己滚，不撑破屏幕 */
  max-height: 60vh;
  overflow: auto;
}
.mp-head {
  font-size: 10.5px;
  color: var(--text-dim);
  padding: 4px 8px 6px;
  letter-spacing: 0.4px;
}
.mp-empty {
  padding: 14px 10px;
  font-size: 12px;
  color: var(--text-dim);
}

.mp-provider {
  position: relative;
}
.mp-provider-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 9px 10px;
  border-radius: var(--radius-sm);
  background: transparent;
  border: none;
  box-shadow: none;
  text-align: left;
  color: var(--text);
  font-size: 12.5px;
}
.mp-provider-row:hover,
.mp-provider-row.active {
  background: var(--panel-2);
}
.mp-plabel {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.mp-host {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 600;
}
.mp-count {
  font-size: 10px;
  color: var(--text-dim);
}
.mp-nokey {
  flex-shrink: 0;
  font-size: 9.5px;
  padding: 1px 6px;
  border-radius: var(--radius-pill);
  background: color-mix(in srgb, var(--warn) 22%, transparent);
  color: var(--warn);
}
.mp-arrow {
  flex-shrink: 0;
  color: var(--text-dim);
  font-size: 14px;
}

/*
 * 模型列表。
 * 窄屏默认在这行下面展开（见媒体查询里的宽屏版本）。
 */
.mp-models {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 2px 0 6px 14px;
  border-left: 2px solid var(--border);
  margin: 0 0 4px 10px;
}
.mp-model {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 7px 10px;
  border-radius: var(--radius-sm);
  background: transparent;
  border: none;
  box-shadow: none;
  text-align: left;
  font-size: 12px;
  color: var(--text);
}
.mp-model:hover {
  background: var(--panel-2);
}
.mp-model.current {
  color: var(--accent);
  font-weight: 700;
  background: color-mix(in srgb, var(--accent) 12%, transparent);
}
/*
 * 模型名**完整显示**，不截断。
 *
 * 这是用户明确要的：模型名（如 gemini-3.1-pro-preview、nai-diffusion-4-5-full）
 * 往往前后缀才是区分点，截成 "gemini-3.1-..." 就分不出是哪个了。
 * 名字过长时换行而不是省略 —— 弹层本身可以滚动，不怕变高。
 */
.mp-mname {
  flex: 1;
  min-width: 0;
  /* 允许在连字符/下划线处断行，长模型名才不会撑破面板 */
  overflow-wrap: anywhere;
  word-break: break-word;
  line-height: 1.35;
}
.mp-check {
  flex-shrink: 0;
}

.mp-foot {
  border-top: 1px solid var(--border);
  margin-top: 4px;
  padding: 7px 8px 3px;
}
.mp-manage {
  font-size: 11px;
  color: var(--accent);
  text-decoration: none;
}
.mp-manage:hover {
  text-decoration: underline;
}

/* ---------------------------------------------------------------------------
 * 宽屏：第二层**往侧面弹**（用户要的形态）
 *
 * 手机上横向不够，弹出去会挤成一条缝，所以用上面的内联展开；
 * 屏幕够宽时才是"点运营商 → 右边滑出一列模型"。
 * --------------------------------------------------------------------------- */
@media (min-width: 769px) {
  .mp-panel {
    min-width: 210px;
    overflow: visible;
  }
  /* 侧面那层脱离文档流，浮在右边 */
  .mp-provider {
    position: static;
  }
  .mp-models {
    position: absolute;
    left: 100%;
    top: 0;
    margin: 0 0 0 4px;
    padding: 5px;
    border-left: none;
    border: 1px solid var(--border);
    border-radius: var(--radius);
    /* 同主弹层：必须不透明，否则身后的内容会透出来 */
    background: var(--panel-solid, var(--panel));
    box-shadow: var(--shadow-3);
    /*
     * 够放下完整的模型名（gemini-3.1-pro-preview 这类约 160–200px）。
     * 宁可宽一点，也不要截断 —— 截断就分不清是哪个模型了。
     */
    min-width: 220px;
    max-width: 320px;
    max-height: 58vh;
    overflow: auto;
  }
  .mp-model {
    padding: 7px 10px;
  }
}
</style>
