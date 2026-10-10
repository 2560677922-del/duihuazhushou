import { createRouter, createWebHashHistory } from 'vue-router';
import MainLayout from '../layouts/MainLayout.vue';

/*
 * 路由结构照微信/QQ 那种"两级"来：
 *
 *   一级（底部标签栏直达）    二级（点进去）
 *   ─────────────────────    ──────────────
 *   /adventure  会话列表  →   /adventure/:id  某个冒险的对话界面
 *   /novel      章节列表  →   /novel/:id      某一章的编辑器
 *   /assets     资产          （本身就是一级，内容自己分栏）
 *   /settings   API 设置
 *   /tags       标签模板/生图
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

        // 小说：列表 → 编辑器
        // 注：沉浸式阅读**不是独立路由** —— 它是编辑器里的一个子功能
        // （写完才能读，所以入口在编辑器里，以全屏层形式打开）。
        { path: 'novel', name: 'novel', component: () => import('../views/NovelList.vue') },
        {
          path: 'novel/:id',
          name: 'novel-detail',
          component: () => import('../views/NovelEditor.vue'),
          props: true,
        },

        // 资产是高频反复编辑的东西，给它一个和设置同级的一级入口
        { path: 'assets', name: 'assets', component: () => import('../views/Assets.vue') },
        { path: 'tags', name: 'tags', component: () => import('../views/Tags.vue') },
        // 生成过的图集中管理：用户得知道图到底存哪了
        { path: 'gallery', name: 'gallery', component: () => import('../views/Gallery.vue') },
        { path: 'settings', name: 'settings', component: () => import('../views/Settings.vue') },
      ],
    },
  ],
});

export default router;

