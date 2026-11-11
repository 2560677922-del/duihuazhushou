# 对话助手 · Duihuazhushou

**安卓原生的 AI 互动叙事客户端。** 导入 SillyTavern 的预设、角色卡、世界书、正则就能直接开聊；
单人冒险、多人群聊、HTML 前端卡、生图都在一个 App 里，数据全部留在本机。

- Android 7.0+（`minSdk 24`）· APK ≈ 5.5 MB · 界面全部原生自绘，不依赖 androidx / material
- 网页端界面已移除，`src/` 只保留生成引擎，跑在 1dp 透明 WebView 里

---

## 做了什么

一句话：**把 SillyTavern 的「组装提示词 + 渲染输出」拆成两层，再配一个原生客户端。**

1. **生成引擎**（`src/`）：读 ST 预设、世界书、正则、变量，按提示词顺序组装请求，流式收发。
   这一层没有界面，兼容性改动都在这里，网页版和 App 共用同一份。
2. **显示引擎**（`dbpg/stcore`）：纯 Kotlin，负责显示正则、宏、变量、思维链剥离、图片标记、
   富文本分块、会话文档与摘要。可以脱离 Android 跑 JVM 单测。
3. **原生界面**（`dbpg/app`）：会话、聊天、群聊、资产、角色卡、生图、图库、设置，全部自绘。

三层之间只传 JSON，谁也不 import 谁。改预设不会动界面，改界面不会影响提示词。

## 功能

| 模块 | 现在能做什么 |
| --- | --- |
| 单人对话 | 流式输出、停止、重新生成（带预设）、重发、编辑、删除；一条消息多版本 `‹ ›` 翻页；点气泡分出新会话；手动 / 自动摘要 |
| **群聊** | 一个群 = 公共区 + 每个角色一个子频道；切频道即切角色卡与世界书，选择自动保存；每个频道独立生成（切走后台继续）；公共区按顺序「续写」或指定谁说；**旁白**单独发言（每群一份设定，可改）；频道内搜索、单独压缩历史 |
| ST 生态 | 预设 `.json`（提示词库 + 排序 + 未激活项）、正则全字段、世界书（含注入位置与选择性触发）、角色卡 PNG 导入与原生新建编辑、`{{char}} {{user}} {{persona}} {{getvar::}}` 等宏与 session / global 变量 |
| 渲染 | Markdown / 裸 HTML / 整页 HTML 卡片自动分流；卡片在 `iframe srcdoc` 沙箱里跑，CSS/JS 原样生效、高度回传；卡片按钮可点（发送 / 填入 / 追加）；`<details>` 折叠 |
| 生图 | 接口与画风分离配置；Danbooru 标签库带中文；对话快捷配图（可指定锚点）、自动配图、图库与查看器 |
| 其它 | 用户角色（persona）、多套主题换肤、LLM / 生图运营商管理、渲染自检与插件位 |

## 架构

```
原生界面 (Kotlin)                        生成引擎 (TypeScript → dist/ → assets/www)
  ├─ 会话 / 聊天 / 群聊 / 资产 / 生图        ├─ 预设排序 + 世界书触发 + 宏 + 正则 + token 裁剪
  ├─ ChatController ── 生成请求 ──────────▶ ├─ streamChatCompletion → API
  │      ▲                                  └─ 回调 onGenDelta / onGenDone
  │      └──────────── 流式文本 ────────────┘
  └─ stcore：显示正则 / 宏 / 变量 / 富文本 / 摘要（纯 Kotlin，可单测）
```

- **数据只有一份**：会话存原生 `SessionDoc`，网页不落任何对话数据，只做「把这份历史组装成请求」。
- **群聊复用单聊**：公共区 1 个会话 + 每个成员 1 个会话，切频道就是换 `sessionId`；
  公共区用 `speakerId` 标发言人，私聊直接复用分支 / 变体 / 摘要，不需要第二套消息模型。
- **配置三层**：群（公共书 / 预设 / 运营商 / 正则）→ 成员（私有书 / 预设 / 运营商）→ 全局，
  `null` 一律表示跟随上层，换群配置不用回写 N 个会话。

## 快速开始

```bash
npm install
npm run build            # 生成 dist/，同步到 dbpg/app/src/main/assets/www

cd dbpg
./gradlew :app:assembleDebug
# 或 build-apk.cmd：先构建网页，再打包

# 产物：dbpg/app/build/outputs/apk/debug/app-debug.apk
```

只改原生代码时可以跳过 `npm run build`，但 `dist/` 必须已经存在。

**版本矩阵**：JDK 25 → Gradle ≥ 9.1；AGP / Gradle / `compileSdk` 三者版本要匹配本机已安装的 SDK。
`local.properties` 里的 `sdk.dir` 用正斜杠（`C:/Users/...`）。

## 目录

```
dbpg/                  Android 工程
├─ app/                原生界面 + assets/www（引擎产物，勿手改）
├─ stcore/             纯 Kotlin 显示引擎（可跑 JVM 单测）
└─ docs/               架构 / 预设兼容性 / 避坑 / 验证 / 改动记录
src/                   生成引擎（无界面）
scripts/               验证与自查脚本
docs/                  设计文档
```

## 测试

```bash
cd dbpg
./gradlew :stcore:test            # 224 个：正则 / 宏 / 变量 / 会话 / 摘要 / 富文本
./gradlew :app:testDebugUnitTest  # 37 个：插件契约 / 渲染判定 / 运营商配置
```

App 内另有两处自查：设置 → 渲染与上下文 → **渲染自检**；设置 → 插件 → **结构探针**。

## 文档

| 文档 | 内容 |
| --- | --- |
| [`dbpg/docs/架构.md`](dbpg/docs/架构.md) | 三层边界、渲染链路、插件契约、存储布局、线程模型 |
| [`dbpg/docs/预设兼容性.md`](dbpg/docs/预设兼容性.md) | ST 字段覆盖表、刻意差异、已知边界 |
| [`dbpg/docs/避坑清单.md`](dbpg/docs/避坑清单.md) | 现象 → 原因 → 解法 |
| [`dbpg/docs/验证与实验台.md`](dbpg/docs/验证与实验台.md) | 单测、自检、真机验证清单 |
| [`dbpg/docs/改动记录.md`](dbpg/docs/改动记录.md) | 逐次改动流水 |
| [`来源与授权.md`](来源与授权.md) | 素材、依赖、兼容标准的来源与授权 |

## 声明

- SillyTavern 生态为**兼容实现**，与本项目无隶属关系；预设、角色卡、世界书的版权归原作者。
- 依赖与素材来源登记在 [`来源与授权.md`](来源与授权.md)。
- 个人自用工具，不对接任何官方服务，API 密钥只存本机。

感谢 SillyTavern、酒馆助手、Tavo 等生态项目公开的标准与踩坑记录，以及所有愿意分享预设、
正则、前端卡的创作者。
