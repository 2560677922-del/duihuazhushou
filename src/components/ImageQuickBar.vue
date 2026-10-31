<script setup lang="ts">
// 生图快捷条：常驻在冒险对话界面上。
//
// 现在只做**一件事**：图生图的参考图。
//
// 为什么把配置下拉和 DNA 拿掉了：
//   这两样已经在右侧「资产」面板里能切（对话模型 / 生图配置 / 角色 DNA），
//   放在输入框上方既占地方又是重复入口 —— 用户会不知道该用哪个。
//   这里只留"这一段要配哪张参考图"这种**每次都可能变**的东西。
//
// 行为：放一张参考图就生效，不放就是纯文生图（没有开关）。
import { ref, computed } from 'vue';
import { useImageStore } from '../stores/image';

const store = useImageStore();

const fileInput = ref<HTMLInputElement | null>(null);
const error = ref('');

/** 参考图（存在 image store 里，会随配置一起落盘） */
const referenceImage = computed({
  get: () => store.quickReferenceImage,
  set: (v: string) => {
    store.quickReferenceImage = v;
    void store.save();
  },
});

const strength = computed({
  get: () => store.quickReferenceStrength,
  set: (v: number) => {
    store.quickReferenceStrength = v;
    void store.save();
  },
});

const hasRef = computed(() => !!referenceImage.value);

async function onPick(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0];
  if (!file) return;
  error.value = '';
  try {
    /*
     * 原图直传，一个字节都不动。
     *
     * 以前会缩到长边 1024 再转 JPEG，两个问题：
     *   1. 画质掉一档，做图生图时参考细节会丢
     *   2. 重编码会把 元数据（生成参数、seed）一起抹掉 ——
     *      而这些正是"从图里反推提示词"的依据
     */
    referenceImage.value = await readAsDataUrl(file);
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err);
  } finally {
    // 允许重复选同一个文件
    if (fileInput.value) fileInput.value.value = '';
  }
}

function readAsDataUrl(file: File): Promise<string> {
  return new Promise((res, rej) => {
    const r = new FileReader();
    r.onload = () => res(r.result as string);
    r.onerror = () => rej(new Error('读取图片失败'));
    r.readAsDataURL(file);
  });
}

function clearRef() {
  referenceImage.value = '';
}
</script>

<template>
  <div class="quick-bar">
    <input ref="fileInput" type="file" accept="image/*" class="hidden-file" @change="onPick" />

    <template v-if="hasRef">
      <img :src="referenceImage" class="qb-thumb" alt="参考图" @click="clearRef" title="点一下清除参考图" />
      <label class="qb-slider" title="重绘强度：越高越偏离原图">
        <input type="range" min="0.1" max="0.95" step="0.05" v-model.number="strength" />
        <span>{{ Math.round(strength * 100) }}%</span>
      </label>
      <button class="qb-mini" @click="clearRef">清除</button>
    </template>
    <template v-else>
      <button class="qb-mini" @click="fileInput?.click()" title="放一张参考图做图生图；不放就是纯文生图">
        🖼️ 参考图
      </button>
    </template>

    <span v-if="error" class="qb-error">{{ error }}</span>
  </div>
</template>

<style scoped>
.quick-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 11.5px;
}
.qb-thumb {
  width: 32px;
  height: 32px;
  object-fit: cover;
  border-radius: 5px;
  border: 1px solid var(--accent);
  cursor: pointer;
}
.qb-slider {
  display: flex;
  align-items: center;
  gap: 5px;
}
.qb-slider input[type='range'] {
  width: 76px;
}
.qb-slider span {
  font-size: 10px;
  color: var(--text-dim);
  min-width: 28px;
}
.hidden-file {
  display: none;
}
.qb-mini {
  font-size: 10.5px;
  padding: 3px 10px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--panel);
  color: var(--text-dim);
  cursor: pointer;
  white-space: nowrap;
}
.qb-mini:hover {
  border-color: var(--accent);
  color: var(--text);
}
.qb-error {
  color: var(--danger);
  font-size: 10.5px;
}
</style>
