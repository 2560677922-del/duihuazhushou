<script setup lang="ts">
// 本地落盘状态条。
//
// 之前这里用的是 window.confirm：一旦两个开发服务器（vite 端口被占用会自动换到 5174）
// 共用同一个数据文件，双方会互相把对方的写入当成「外部改动」，
// 于是确认框反复弹出、点确认也没用——页面被模态框卡死。
// 现在改成不阻塞的状态条，由用户决定何时载入。
import { ref, onMounted, onUnmounted } from 'vue';
import { persist } from '../lib/persist';
import type { PersistStatus } from '../lib/persist';

const status = ref<PersistStatus>(persist.getStatus());
const dismissed = ref(false);
let off: (() => void) | undefined;

onMounted(() => {
  off = persist.onChange((s) => {
    status.value = s;
    // 新的外部改动出现时，重新显示提示
    if (s.remoteChanged) dismissed.value = false;
  });
});
onUnmounted(() => off?.());

/** 载入外部改动：把文件内容读回并重载页面，保证所有 store 都拿到新值 */
async function reloadFromFile() {
  await persist.acceptRemote();
  window.location.reload();
}
</script>

<template>
  <div v-if="status.remoteChanged && !dismissed" class="persist-banner">
    <span class="icon">💾</span>
    <span class="text">
      检测到本地配置文件被其他窗口或设备修改（可能是另一个端口上的开发服务器）。
      载入最新配置会刷新页面，未保存的编辑会丢失。
    </span>
    <button class="primary" @click="reloadFromFile">载入最新配置</button>
    <button @click="dismissed = true">稍后</button>
  </div>
</template>

<style scoped>
.persist-banner {
  position: fixed;
  left: 50%;
  transform: translateX(-50%);
  bottom: 16px;
  z-index: 300;
  max-width: min(760px, calc(100vw - 32px));
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-radius: 10px;
  background: var(--panel);
  border: 1px solid var(--accent);
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.35);
  font-size: 12px;
  color: var(--text);
}
.persist-banner .icon {
  font-size: 16px;
  flex: 0 0 auto;
}
.persist-banner .text {
  flex: 1;
  line-height: 1.5;
  color: var(--text-dim);
}
.persist-banner button {
  flex: 0 0 auto;
  white-space: nowrap;
}
@media (max-width: 640px) {
  .persist-banner {
    flex-wrap: wrap;
  }
}
</style>
