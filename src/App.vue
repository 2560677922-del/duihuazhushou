<script setup lang="ts">
// 应用外壳：单个组件崩溃时给出可读的错误页，而不是整页空白。
//
// 背景：之前出现过"界面空白"——Vue 在渲染期抛异常时会把整棵组件树卸掉，
// 用户只看到白屏，控制台又不会自动打开，于是完全无从下手。
// 这里用 onErrorCaptured 把错误拦下来，显示错误信息 + 重载按钮。
import { ref, onErrorCaptured, onMounted } from 'vue';
import PersistBanner from './components/PersistBanner.vue';
import { store } from './lib/storage';

const crashed = ref<{ message: string; stack?: string } | null>(null);

/**
 * 收起 index.html 里的启动画面。
 *
 * 启动画面是内联在 index.html 的骨架（服务端一解析就能画），
 * 它保证用户不会看到黑屏。这里负责在应用真正就绪后把它淡出。
 *
 * 关键：等 `store.ready()` 之后再收，不是 onMounted 就收。
 *   如果只等组件挂载，数据还没从本地库读出来 —— 界面会先闪一下
 *   空白/默认值再填上内容，比多看一会儿启动画面更糟。
 *
 * 兜底：无论成功失败都要收掉，绝不能让启动画面永久盖住界面。
 */
async function hideBootSplash() {
  const el = document.getElementById('boot-splash');
  if (!el) return;
  const drop = () => {
    el.classList.add('splash-out');
    // 等淡出动画走完再从 DOM 移除，避免它继续占着层级
    setTimeout(() => el.remove(), 500);
  };

  const started = performance.now();

  try {
    await store.ready();
  } catch {
    /* 就绪失败也要收掉启动画面，让用户看到错误提示 */
  }

  /*
   * 最短展示时间 —— 这条很关键。
   *
   * 电脑上应用可能 300ms 就绪，启动画面一闪而过，看着像"抖了一下"，
   * 反而不如不做。
   *
   * 但也不能一律拖长（那是真的变慢）。折中做法：
   *   就绪得**很快**（< 200ms）时补到 420ms 再收，让淡入淡出看得清；
   *   本来就慢的（手机上通常 1s+）立刻收，一秒都不多等。
   */
  const elapsed = performance.now() - started;
  const FAST_READY = 200;
  const MIN_VISIBLE = 420;
  if (elapsed < FAST_READY) {
    await new Promise((r) => setTimeout(r, MIN_VISIBLE - elapsed));
  }

  // 等下一帧再淡出，保证首屏内容已经画出来了
  requestAnimationFrame(() => requestAnimationFrame(drop));
}

onMounted(() => {
  void hideBootSplash();
});

onErrorCaptured((err, _instance, info) => {
  const e = err as Error;
  crashed.value = {
    message: `${e?.name || 'Error'}: ${e?.message || String(err)}\n（发生在：${info}）`,
    stack: e?.stack,
  };
  console.error('[界面错误]', info, err);
  // 出错时更要把启动画面收掉，否则错误页被盖住、用户什么都看不到
  document.getElementById('boot-splash')?.remove();
  // 返回 false 阻止继续向上冒泡（避免整棵树被卸载）
  return false;
});

function reload() {
  crashed.value = null;
  window.location.reload();
}
</script>

<template>
  <div v-if="crashed" class="crash-screen">
    <div class="crash-card">
      <h2>⚠ 界面出错了</h2>
      <p class="crash-tip">
        这是某个组件在渲染时抛出的异常。页面本身还在，重载通常就能恢复；
        如果反复出现，把下面的信息发给我就能定位。
      </p>
      <pre class="crash-msg">{{ crashed.message }}</pre>
      <details v-if="crashed.stack" class="crash-details">
        <summary>查看调用栈</summary>
        <pre class="crash-msg">{{ crashed.stack }}</pre>
      </details>
      <div class="crash-actions">
        <button class="primary" @click="reload">重新加载</button>
        <button @click="crashed = null">忽略错误，继续用</button>
      </div>
    </div>
  </div>

  <template v-else>
    <router-view />
    <!-- 本地落盘状态条：取代之前会反复弹出的 window.confirm -->
    <PersistBanner />
  </template>
</template>

<style scoped>
.crash-screen {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}
.crash-card {
  max-width: 760px;
  width: 100%;
  background: var(--panel);
  border: 1px solid var(--danger);
  border-radius: 12px;
  padding: 20px 22px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.crash-card h2 {
  margin: 0;
  font-size: 17px;
  color: var(--danger);
}
.crash-tip {
  margin: 0;
  font-size: 12px;
  color: var(--text-dim);
  line-height: 1.6;
}
.crash-msg {
  margin: 0;
  padding: 10px 12px;
  background: var(--code-bg);
  border-radius: 8px;
  font-size: 11px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 260px;
  overflow-y: auto;
  color: var(--text);
}
.crash-details summary {
  font-size: 11px;
  color: var(--text-dim);
  cursor: pointer;
  margin-bottom: 6px;
}
.crash-actions {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}
.crash-actions button {
  padding: 8px 16px;
}
</style>
