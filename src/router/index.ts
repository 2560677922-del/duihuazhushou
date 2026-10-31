import { createRouter, createWebHashHistory } from 'vue-router';
import MainLayout from '../layouts/MainLayout.vue';

/*
 * 路由结构照微信/QQ 那种"两级"来：
 *
 *   一级（底部标签栏直达）    二级（点进去）
 *   ─────────────────────    ──────────────
 *   /adventure  会话列表  →   /adventure/:id  某个冒险的对话界面
 *   /assets     资产          （本身就是一级，内容自己分栏）
 *   /settings   API 设置
 *   /tags       标签模板/生图
 *
 * 注：小说（章节列表 / 编辑器）已按需求**整体移除**，只保留冒险一条主线。
 *     数据层没动 —— 老的 novelChapters / novelProjects 仍然留在 storage 的
 *     合并键表里，用户的旧稿件还安全躺在 app-data.json 里，不会被清掉。
 *
 * 为什么要拆：以前"进冒险"直接就是对话界面，里面还塞着会话列表、
 * 资产侧栏、一堆按钮 —— 一屏上什么都有，手机上又挤又乱。
 * 拆开之后，一级只负责"选哪个"，二级只负责"聊/写"，各自都干净。
 *
 * 用 :id 作为可选参数（`:id?`）：不带 id 时进入二级界面会自动选最近那个，
 * 这样旧书签/旧链接（/adventure）不会 404。
 */
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: '/',
      component: MainLayout,
      children: [
        /*
         * 没有"首页"。
         *
         * 这是个 App，不是网站 —— 打开就该直接进入工作界面：
         * 用户第一次点开看到的是他的会话列表（或空状态引导），
         * 而不是一页介绍"本应用能做什么"的门户。
         * 旧首页的"载入示例资产"入口挪进了冒险列表的空状态。
         */
        { path: '', redirect: '/adventure' },

        // 冒险：列表 → 对话
        { path: 'adventure', name: 'adventure', component: () => import('../views/AdventureList.vue') },
        {
          path: 'adventure/:id',
          name: 'adventure-detail',
          component: () => import('../views/Adventure.vue'),
          props: true,
        },

        // 资产是高频反复编辑的东西，给它一个和设置同级的一级入口
        { path: 'assets', name: 'assets', component: () => import('../views/Assets.vue') },
        { path: 'tags', name: 'tags', component: () => import('../views/Tags.vue') },
        // 生成过的图集中管理：用户得知道图到底存哪了
        { path: 'gallery', name: 'gallery', component: () => import('../views/Gallery.vue') },
        { path: 'settings', name: 'settings', component: () => import('../views/Settings.vue') },

        /*
         * 兜底：一律回冒险页，不给空白页。
         *
         * 最现实的来源是**旧书签**：#/novel、#/novel/<章节id> 是小说功能留下的地址，
         * 功能删掉之后这些路由就不存在了。没有这条兜底的话，
         * vue-router 匹配不到就直接渲染空白 —— 用户会以为应用坏了。
         */
        { path: ':pathMatch(.*)*', redirect: '/adventure' },
      ],
    },
  ],
});

export default router;

