<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();

/**
 * 切一级页面：**替换**当前历史条目，不新增。
 *
 * 配合安卓返回手势的效果（和原生 App 一致）：
 *   · 在二级页（聊天/小说编辑）→ 返回 = 回它所属的一级页
 *   · 在一级页（列表）        → 返回 = 退出应用
 * 如果一级页面之间用 push 互相堆栈，返回键就永远在几个 tab 之间打转，
 * 用户按到没反应为止 —— 这是"退不出去"的根因。
 */
function goTab(path: string) {
  if (route.path === path) return;
  void router.replace(path);
}
/**
 * 底部标签栏顺序（按用户要求定的）：
 *   冒险 → 小说 → 资产 → 设置 → 生图
 *
 * 说明：
 *  - 「图片库」不单独占一格：它是"看结果"的地方，放进设置里当子页，
 *    标签栏只留每天都要用的入口。手机上 5 格是拇指友好上限
 *    （7 格会挤到 55px 以下、文字显示不全）。
 *  - 设置那一格的命名见下面 navItems 里的注释。
 */
const navItems = [
  { path: '/adventure', name: 'adventure', label: '冒险', icon: '🎭' },
  { path: '/novel', name: 'novel', label: '小说', icon: '📝' },
  { path: '/assets', name: 'assets', label: '资产', icon: '📦' },
  /*
   * 标签叫「设置」而不是「API」。
   *
   * 曾经叫「API」，理由是"用户就是来改接口的"。但那个页面其实有 10 个分区
   * （对话模型接口 / 生图配置 / 图片库 / 资产与世界书 / 生图标签库 /
   *  界面外观 / 图片存放位置 / 数据持久化 / 数据导入导出 / 关于）——
   * 只叫 API 的话，用户想找"换个主题""备份数据"根本不会点它。
   * 标签应该描述**目的地**，而不是描述最常用的那个用途。
   */
  { path: '/settings', name: 'settings', label: '设置', icon: '⚙️' },
  { path: '/tags', name: 'tags', label: '生图', icon: '🏷️' },
];

/**
 * 当前激活哪个 tab。
 *
 * 二级页面（/adventure/xxx、/novel/xxx）也要让对应的一级 tab 保持高亮 ——
 * 用户在对话界面时，底部"冒险"应该是选中态，否则会以为跳出去了。
 */
const activeName = computed(() => {
  const n = String(route.name || '');
  if (n.startsWith('adventure')) return 'adventure';
  if (n.startsWith('novel')) return 'novel';
  if (n === 'gallery') return 'assets';
  return n;
});

/**
 * 是否在"已经进到具体内容里"的界面（冒险对话 / 小说编辑器）。
 *
 * 这些界面要**收起底部标签栏**：
 *  1. 那五个大按钮是"选大类"用的，人已经进到会话里了，不需要它们；
 *  2. 手机上标签栏占 63px，正好是对话窗口最缺的高度。
 * 返回上一级靠页面自己的返回键。
 */
const isChatView = computed(() => {
  const n = String(route.name || '');
  return n === 'adventure-detail' || n === 'novel-detail';
});
</script>

<template>
  <div class="app-layout" :class="{ 'chat-view': isChatView }">
    <aside class="sidebar">
      <div class="sidebar-header">
        <div class="logo">🤖</div>
        <h1>冒险模拟器</h1>
        <p class="subtitle">AI 写作 + 冒险平台</p>
      </div>
      <nav class="sidebar-nav">
        <!--
          切一级页面用 **replace** 而不是 push。
          为什么：RouterLink 默认 push，会在浏览器历史里一层层堆 ——
          "小说 → 冒险"，按返回键又回到「小说」，用户以为退不出去。
          改成 replace 之后，几个一级页面在历史里**互相替换**，
          返回手势/返回键只会有两种结果：回到上一个二级页面（如果在），
          或者直接退出应用（在一级页面时）—— 这才是安卓的预期行为。
        -->
        <a
          v-for="item in navItems"
          :key="item.name"
          href="#"
          class="nav-item"
          :class="{ active: activeName === item.name }"
          @click.prevent="goTab(item.path)"
        >
          <span class="nav-icon">{{ item.icon }}</span>
          <span class="nav-label">{{ item.label }}</span>
        </a>
      </nav>
      <div class="sidebar-footer">
        <span class="hint">SillyTavern 预设兼容</span>
      </div>
    </aside>
    <main class="main-content">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.app-layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
}
.sidebar {
  width: 220px;
  background: var(--panel);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}
.sidebar-header {
  padding: 20px 16px 16px;
  border-bottom: 1px solid var(--border);
}
.logo {
  font-size: 28px;
  margin-bottom: 8px;
}
.sidebar-header h1 {
  margin: 0;
  font-size: 16px;
  color: var(--text);
}
.subtitle {
  margin: 4px 0 0;
  font-size: 11px;
  color: var(--text-dim);
}
.sidebar-nav {
  flex: 1;
  padding: 12px 8px;
  overflow-y: auto;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  margin-bottom: 4px;
  border-radius: 8px;
  color: var(--text-dim);
  text-decoration: none;
  font-size: 13px;
  transition: all 0.15s;
}
.nav-item:hover {
  background: var(--panel-2);
  color: var(--text);
}
.nav-item.active {
  background: var(--accent);
  color: #0b1220;
  font-weight: 600;
}
.nav-item.active .nav-icon {
  filter: none;
}
.nav-icon {
  font-size: 16px;
  width: 20px;
  text-align: center;
}
.sidebar-footer {
  padding: 12px 16px;
  border-top: 1px solid var(--border);
  font-size: 11px;
  color: var(--text-dim);
}
.main-content {
  flex: 1;
  overflow-y: auto;
  min-width: 0;
}

/* ---------------------------------------------------------------------------
 * 手机端布局
 *
 * 这些规则必须写在组件自己这里，不能放到全局 main.css。
 * 原因是 <style scoped> 编译后变成 `.sidebar[data-v-xxx]`，特异性比全局的
 * `.sidebar` 高一档 —— 全局媒体查询写得再对也压不过它，会出现
 * "媒体查询明明匹配了、样式却不生效"的现象（症状：手机上侧边栏仍是
 * 一块 220px 的小方块，右边空一大截）。
 * 写在同一个 scoped 块里，特异性天然相同，后写的就会覆盖前面的。
 * --------------------------------------------------------------------------- */
@media (max-width: 768px) {
  /* 整页改成自然滚动 */
  .app-layout {
    flex-direction: column;
    height: auto;
    min-height: 100dvh;
    overflow: visible;
  }

  /*
   * 导航从「顶部横条」改成「底部标签栏」。
   *
   * 这是手机上"像 App"最关键的一步：底部标签栏是原生应用的标准形态，
   * 单手拇指够得着；而且空出来的顶部整条留给内容，
   * 每个页面自己的标题栏可以直接吸顶。
   *
   * DOM 顺序是「导航 → 内容」，用 order 把它翻到最下面，
   * 结构一行不用改。
   */
  .sidebar {
    order: 2;
    position: fixed;
    left: 0;
    right: 0;
    bottom: 0;
    width: 100%;
    z-index: 40;
    border-right: none;
    border-top: 1px solid var(--border);
    border-bottom: none;
    /* 毛玻璃：内容从下面滚过时能透出一点，显得有层次 */
    background: var(--glass);
    -webkit-backdrop-filter: blur(14px) saturate(1.3);
    backdrop-filter: blur(14px) saturate(1.3);
    box-shadow: var(--shadow-up);
    flex-shrink: 0;
  }

  /* 手机上品牌区让位给导航 */
  .sidebar-header {
    display: none;
  }

  .sidebar-nav {
    display: flex;
    overflow-x: auto;
    padding: 6px 4px;
    padding-bottom: calc(6px + env(safe-area-inset-bottom, 0px));
    scrollbar-width: none;
    -webkit-overflow-scrolling: touch;
    gap: 2px;
  }
  .sidebar-nav::-webkit-scrollbar {
    display: none;
  }

  /*
   * 每个标签：图标在上、文字在下。
   *
   * 关键：`flex: 1 1 0` + `min-width: 0` 让 7 个标签**平分**横向空间。
   * 之前写 `min-width: 64px`，7×64=448 > 390，标签栏就会横向溢出 ——
   * 而底部标签栏是**不该横滚**的（原生 App 都是平分，或者干脆只放 5 个）。
   *
   * 高度写死 50px（不用 min-height）：这样标签栏总高 = 1(边框) + 6 + 50 + 6
   * = 63px 是**确定的**，--tabbar-h 才能跟它对上，
   * 否则输入框吸底时会和标签栏重叠或留缝。
   */
  .nav-item {
    flex: 1 1 0;
    min-width: 0;
    height: 50px;
    flex-direction: column;
    gap: 2px;
    padding: 4px 2px;
    margin-bottom: 0;
    border-radius: var(--radius);
    font-size: 10px;
    color: var(--text-dim);
    background: transparent;
    box-shadow: none;
    border: none;
    transition: color 0.15s, background 0.15s;
  }
  /* 标签栏里不要桌面端那种"按钮阴影"，去掉更干净 */
  .nav-item:hover:not(.active) {
    background: rgba(255, 255, 255, 0.05);
    color: var(--text);
  }
  /*
   * 激活态。
   *
   * 细节：文字用 `--accent`（浅色主题下是压暗过的，能读），
   * 但**底色不能再用 accent** —— 药丸底和文字同色系会互相压低对比度
   * （实测 4.15，达不到 4.5）。改成中性灰底，层次也更清楚：
   * 底是"选中块"，文字才是"高亮内容"。
   */
  .nav-item.active {
    background: rgba(120, 120, 130, 0.14);
    /*
     * 文字色要比普通 accent 再深一档。
     *
     * 标签栏字号只有 9.5px（很小），WCAG 对这种小字要求 4.5。
     * 实测：直接用 --accent 是 3.99，差一点。
     * 这里再往黑里掺一点 —— 小字必须看得清，宁可深一些。
     */
    color: color-mix(in srgb, var(--accent) 78%, #000);
    font-weight: 700;
  }
  .nav-label {
    display: block;
    font-size: 9.5px;
    line-height: 1.2;
    white-space: nowrap;
    /* 名字长的（比如"小说编辑器"）宁可省略，也不许把标签栏撑宽 */
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .nav-icon {
    font-size: 20px;
    width: auto;
    line-height: 1.1;
  }

  .sidebar-footer {
    display: none;
  }

  /*
   * 主内容：底部要留出标签栏的高度，否则最后一行会被压住。
   * 用 --tabbar-h 统一控制，页面里的 min-height 也引用它。
   */
  .main-content {
    order: 1;
    flex: 1;
    display: flex;
    flex-direction: column;
    overflow: visible;
    min-height: 0;
    padding-bottom: calc(var(--tabbar-h) + env(safe-area-inset-bottom, 0px));
  }

  /*
   * 已经进到对话/编辑器里 → 收起底部标签栏。
   *
   * 做法不只是 `display:none`，还把这个布局变量改成 0：
   * 页面里所有底部抽屉/吸底输入框都是按 `bottom: var(--tabbar-h)`
   * 定位的，变量归零后它们会**自动**跟着贴到屏幕底部，
   * 不用逐个去改（改一处、全站一致）。
   */
  .app-layout.chat-view {
    --tabbar-h: 0px;
  }
  .app-layout.chat-view .sidebar {
    display: none;
  }
  /*
   * 这里必须**完全去掉**下内边距，不能留一份安全区。
   *
   * 吸底的输入区自己已经有 `padding-bottom: calc(12px + 安全区)`，
   * 用来把手势条那一块让出来。这里再留一份就算了两遍 ——
   * 输入框下面会多出一条空隙，贴不紧屏幕底（用户反馈过这个问题）。
   */
  .app-layout.chat-view .main-content {
    padding-bottom: 0;
  }
}
</style>
