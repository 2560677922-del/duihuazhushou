# 对话助手 · Duihuazhushou

**安卓原生的 AI 互动冒险 / 写作客户端。** 导入 SillyTavern 的预设、角色卡、世界书、正则即可直接开跑；
正文以原生渲染为主，遇到 HTML 前端卡片自动切进沙箱 WebView，并可挂载渲染插件接管显示。

- 版本 `1.1.1`（versionCode 8）· `minSdk 24`（Android 7.0+）· `targetSdk 37`
- APK ≈ 5.5 MB，不依赖 androidx / material，界面全部自绘
- 数据全程留在本机：会话、角色卡、图片、世界书、正则都存原生目录，卸载即清

---

## 它现在是什么

浏览器端界面已整体移除（`src/views` 物理删除），现在是三层结构，彼此只通过 JSON 交换数据：

| 层 | 位置 | 负责 | 不负责 |
| --- | --- | --- | --- |
| 原生 UI | `dbpg/app` | 会话、聊天、设置、资产、角色卡、生图、图库 | 不解析预设、不拼提示词 |
| 显示引擎 | `dbpg/stcore`（纯 Kotlin，218 个单测） | 显示正则、宏、变量、思维链剥离、图片标记、富文本分块、会话文档与摘要 | 不碰网络、不碰 Android |
| 生成引擎 | `src/` → `dist/` → `assets/www` | 提示词组装、ST 资产解析、请求构建 | 没有界面，跑在 1dp 透明 WebView 里 |

> 一句话：**生成归 web 引擎，显示归 stcore，界面归原生。**

## 渲染：这条链路是本项目的重点

模型输出的原文落库，显示时逐层重算，不写回数据——改预设、改正则、改外观，旧消息会自动重排。

```
原文 → 剥思维链 → 显示正则（跳过 promptOnly）→ 宏 → 变量 → 图片标记 → 分两路
                                                                    ├─ plain：纯文本气泡（Spannable）
                                                                    └─ rich ：插件仲裁 → 清洗 → WebView / 原生块
```

- **三种形态自动分流**：Markdown、裸 HTML、整页 HTML 卡片，各自走不同渲染路径，互不干扰
- **卡片在 `iframe srcdoc` 沙箱里跑**：作者的 CSS / JS 原样生效，高度自动回传，横向滚动锁死；
  刻意**不给** `allow-same-origin`，配合 `HtmlSanitizer` 做隔离
- **卡片里的选项按钮可以点**：按 ST 契约接上 `window.SillyTavern` 与 `#send_textarea`，
  支持「直接发送 / 填入 / 追加」
- `<details>` 折叠、面板内 `<summary>`、自定义标签（`<xy-card>` 之类）都能正常渲染
- **列表里不塞 WebView**：卡片走离屏 WebP 位图 + LRU，WebView 只给可见的富消息且池上限 2
- 缓存键 = `actionId + 内容哈希 + 外观哈希 + 深度`，少一项就会出现「改了设置旧消息不变」

## 插件位

渲染层是可插拔的：内置插件随 APK 打包，**运行时安装 / 卸载 / 停用**，主渲染管线不用改一行。

- 内置「通用前端卡渲染器」（`panel_data_adapter`）—— 不可删除，它是渲染库的地基
- 认「HTML 卡片」这个形态，不认任何预设的专有名称，所以对每个预设都通用
- 只做补 `head`、包成自包含文档、统一围栏标注；**不解析正文、不生成面板、不猜选项**
- 单 renderer 仲裁，冲突有明确优先级
- 自带诊断：认领判据、探针、产出长度都能在设置里看到（插件 → 结构探针）

## SillyTavern 兼容

| 资产 | 支持情况 |
| --- | --- |
| 预设 | `.json` 导入；`prompts[]` 库 + `prompt_order` 顺序 + 未激活项 |
| 正则 | `regex_scripts` 全字段：placement、trimStrings、markdownOnly / promptOnly、substituteRegex、minDepth / maxDepth、`$0/$n/$<name>/{{match}}` |
| 宏 | `{{char}} {{user}} {{persona}} {{getvar::}} {{setvar::}} {{random::}} {{pick::}} {{roll::}} {{time}} {{date}} {{// 注释}}` 等 |
| 世界书 | 导入、条目、注入位置、SelectiveLogic 触发；会话可绑定 |
| 角色卡 | PNG 卡解析（V1/V2/V3，含内嵌世界书）、列表、详情、导入 |
| 变量 | session / global 两张表，写宏只在「用户发送」「回复落库」两个入口执行 |

有两处与 ST **刻意不同**的正则语义（双标志并非互斥），详见 `dbpg/docs/预设兼容性.md`，不要照 ST 源码改回去。

## 其它能力

- **聊天**：流式输出、随时停止、重新生成（带预设）、重发、编辑、复制、删除；一条消息多版本 `‹ ›` 翻页；
  点 AI 气泡可分出新会话；摘要支持手动 / 自动
- **生图**：接口与风格分离配置（同一套接口可配多套画风）、Danbooru 标签库 21097 条带中文、
  勾选标签直接出图、自动配图、图片库与查看器
- **外观**：主题、明暗、底板透明度、强调色、字号、行距、字体、气泡宽度、背景图
- **LLM 运营商**：列表 → 详情两级界面（配置 / 模型 / 高级设置 三 Tab）、搜索、密钥显隐、
  按运营商保存与启停、批量删除与全选添加；界面控件统一走主题色板，换肤一处生效

## 路线图

- **跑团 / 多人对话**（本质是群聊）：有计划，尚未实现，没有时间表。
- 其余方向按实际使用中的问题推进，不预设版本号——架构稳定优先于功能数量。

## 快速开始

环境：Node.js + Android Studio（自带 JBR 与 SDK）。

```bash
npm install
npm run build          # 出 dist/，并同步进 assets/www

cd dbpg
./gradlew :app:assembleDebug
# 或直接跑 build-apk.cmd，会先 npm run build 再打包

# 产物
dbpg/app/build/outputs/apk/debug/app-debug.apk
```

只改原生代码、没动 `src/` 时可以跳过 `npm run build`，但 `dist/` 必须已存在。

**版本矩阵要一起动**：JDK 25 → Gradle ≥ 9.1；AGP 9.4.1 / Gradle 9.8.0 / `compileSdk` 必须等于本机已安装的平台版本。
`local.properties` 里的 `sdk.dir` 必须用正斜杠（`C:/Users/...`），反斜杠会被当转义符吃掉。

## 目录

```
dbpg/                     Android 工程
├─ app/                   原生界面（会话 / 聊天 / 设置 / 资产 / 生图 / 图库）
│  └─ src/main/assets/
│     ├─ www/             引擎产物（syncWeb 生成，勿手改）
│     ├─ msg/             消息渲染壳 render.html / render.js / render.css
│     └─ vendor/          capacitor 垫片
├─ stcore/                纯 Kotlin 显示引擎（可跑 JVM 单测）
└─ docs/                  架构 / 预设兼容性 / 避坑 / 验证 / 改动记录
src/                      生成引擎源码（无界面）
scripts/                  验证与自查脚本
docs/                     设计文档与方案评估
```

## 测试

```bash
cd dbpg
./gradlew :stcore:test          # 218 个：正则 / 宏 / 变量 / 思维链 / 会话 / 富文本
./gradlew :app:testDebugUnitTest # 14 个：插件契约 / 渲染判定
```

App 内另有两处自查：设置 → 渲染与上下文 → **渲染自检**（形态、rich 长度、各插件认领、产出）；
设置 → 插件 → **结构探针**（卡片后正文行数、选项行数、分隔线数）。

## 文档

| 文档 | 内容 |
| --- | --- |
| [`dbpg/docs/架构.md`](dbpg/docs/架构.md) | 三层边界、渲染链路、插件契约、存储布局、线程模型 |
| [`dbpg/docs/预设兼容性.md`](dbpg/docs/预设兼容性.md) | ST / Tavo 字段覆盖表、刻意差异、已知边界 |
| [`dbpg/docs/避坑清单.md`](dbpg/docs/避坑清单.md) | 30 条「现象 → 原因 → 解法」 |
| [`dbpg/docs/验证与实验台.md`](dbpg/docs/验证与实验台.md) | 单测、自检、真机验证清单 |
| [`dbpg/docs/改动记录.md`](dbpg/docs/改动记录.md) | 逐次改动流水与实测数据 |
| [`docs/渲染性能与自动摘要方案.md`](docs/渲染性能与自动摘要方案.md) | 渲染性能取舍与摘要策略 |
| [`来源与授权.md`](来源与授权.md) | 素材、依赖、兼容标准的来源与授权 |

## 声明

- SillyTavern 生态为**兼容实现**，与本项目无隶属关系；预设、角色卡、世界书的版权归原作者
- 依赖与素材来源逐一登记在 [`来源与授权.md`](来源与授权.md)，新增外部素材请一并登记
- 本项目为个人自用工具，不对接任何官方服务，API 密钥只存本机

---

## 致谢

这个项目站在很多人的肩膀上。他们在生态标准、实现思路和踩坑记录上的公开分享，让我们少走了大量弯路。

**排名不分先后。**

**生态与数据**

SillyTavern · 酒馆助手 · 小白X · Tavo · RikkaHub · Danbooru · NovelAI · wallhaven · chami_tavern-scene-plugin · booru-tags-gallery · FrenzyExists/wallpapers

**基础设施**

Vue · Vite · Pinia · Capacitor · markdown-it · marked · localforage · parsecard · nekoai-js

以及所有愿意把预设、正则、前端卡和调教经验公开出来的创作者。
