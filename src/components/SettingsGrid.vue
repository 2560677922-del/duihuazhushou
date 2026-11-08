<script setup lang="ts">
// 设置首页：每个子功能一个大方块（卡片网格）
//
// 为什么改成这样：
//   之前设置页是**一列折叠面板**（10 个 section，默认收起，靠点标题展开）。
//   问题不是难看，是**找不到东西**：所有功能挤在一条竖线里，
//   标题一样大、看不出层级，想知道"这里能干什么"得逐个点开。
//
//   改成大方块后：一屏能看到全部功能分区，每块有图标+名称+一句话说明，
//   点进去是那块的**独立页面**（不是折叠展开）。
//   这是主流做法（iOS 设置、Android 设置、Notion 设置都这样）——
//   分区多的时候，网格比折叠列表好找得多。
import { computed } from 'vue';
import AppIcon from './AppIcon.vue';

export interface SettingsCard {
  /** section key。**不是路由参数** —— 设置页内部用 activeSection 切换，
      URL 始终是 /settings，外部没有、也不需要按 key 跳转。 */
  key: string;
  /** 大图标（AppIcon 名称，描边 SVG，随主色走） */
  icon: string;
  title: string;
  desc: string;
  /** 右上角的小状态字，比如"3 个配置" */
  badge?: string;
}

const props = defineProps<{ cards: SettingsCard[] }>();
defineEmits<{ (e: 'open', key: string): void }>();

const list = computed(() => props.cards);
</script>

<template>
  <div class="settings-grid">
    <button
      v-for="c in list"
      :key="c.key"
      class="set-card"
      @click="$emit('open', c.key)"
    >
      <span class="set-ico"><AppIcon :name="c.icon" :size="24" /></span>
      <span class="set-text">
        <span class="set-title">
          {{ c.title }}
          <span v-if="c.badge" class="set-badge">{{ c.badge }}</span>
        </span>
        <span class="set-desc">{{ c.desc }}</span>
      </span>
      <span class="set-chev">›</span>
    </button>
  </div>
</template>

<style scoped>
/*
 * 网格：宽屏多列、手机单列。
 * 手机上单列是因为方块里有两行字，双列会把说明挤没。
 */
.settings-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 12px;
  margin-bottom: 18px;
}

.set-card {
  display: flex;
  align-items: center;
  gap: 14px;
  width: 100%;
  padding: 16px 16px;
  text-align: left;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-1);
  transition: transform 0.12s, box-shadow 0.12s, border-color 0.12s;
}
.set-card:hover {
  border-color: var(--accent);
  box-shadow: var(--shadow-2);
}
.set-card:active {
  transform: translateY(1px) scale(0.995);
}

/* 图标：给个圆底，比光秃秃一个 emoji 更像"入口"。
 * 底色用主色混一点 —— 太淡会显得发灰（试过 13%，偏灰），
 * 22% 刚好有颜色又不抢标题。SVG 用主色描边，主题一换图标就跟着换。 */
.set-ico {
  flex-shrink: 0;
  width: 46px;
  height: 46px;
  display: grid;
  place-items: center;
  border-radius: var(--radius-sm);
  background: color-mix(in srgb, var(--accent) 22%, var(--panel-2));
  border: 1px solid color-mix(in srgb, var(--accent) 16%, transparent);
  color: var(--accent);
}

.set-text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.set-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 700;
  color: var(--text);
}
.set-badge {
  flex-shrink: 0;
  font-size: 10px;
  font-weight: 500;
  padding: 1px 7px;
  border-radius: var(--radius-pill);
  background: var(--panel-2);
  color: var(--text-dim);
}
.set-desc {
  font-size: 11.5px;
  color: var(--text-dim);
  line-height: 1.45;
  /* 说明最多两行，超了省略 —— 方块高度要一致 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.set-chev {
  flex-shrink: 0;
  font-size: 20px;
  color: var(--text-dim);
  opacity: 0.6;
}

@media (max-width: 768px) {
  .settings-grid {
    grid-template-columns: 1fr;
    gap: 10px;
  }
  .set-card {
    padding: 14px;
    gap: 12px;
  }
  .set-ico {
    width: 42px;
    height: 42px;
  }
  .set-title {
    font-size: 13.5px;
  }
  .set-desc {
    font-size: 11px;
  }
}
</style>
