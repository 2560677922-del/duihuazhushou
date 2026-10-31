<script setup lang="ts">
// 图片库（独立页面）
//
// 现在底部标签栏是 4 格（冒险 / 资产 / 设置 / 生图），
// 图片库的入口挪进了「资产 → 图片库」。
// 这个页面保留是为了**老书签和旧链接不 404**，
// 内容直接复用同一个面板组件（只有一份实现）。
import GalleryPanel from '../components/GalleryPanel.vue';
</script>

<template>
  <div class="gallery-page">
    <div class="page-head">
      <h2>🖼️ 图片库</h2>
      <p class="tip">
        这个入口已移到「资产 → 图片库」。本页保留是为了旧链接可用。
      </p>
    </div>
    <GalleryPanel />
  </div>
</template>

<style scoped>
.gallery-page {
  padding: 20px 24px 40px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.page-head h2 {
  margin: 0 0 4px;
  font-size: 20px;
}
.tip {
  margin: 0;
  font-size: 11.5px;
  color: var(--text-dim);
}
</style>
