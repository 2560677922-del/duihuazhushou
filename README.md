# 对话助手 · Duihuazhushou

> 移动端的傻酒馆 —— 一个轻量、本地的 Android AI 互动叙事 / 写作客户端，兼容 SillyTavern 的一部分生态。

<a href="https://github.com/2560677922-del/duihuazhushou/releases/download/apk-v1.1.1/app-debug.apk"><img alt="下载 APK" src="https://img.shields.io/badge/下载-APK%20v1.1.1-blue?logo=android"></a>
&nbsp; **[⬇️ 下载 APK（v1.1.1，debug 签名）](https://github.com/2560677922-del/duihuazhushou/releases/download/apk-v1.1.1/app-debug.apk)**

---

# 一、它能做什么（给「用」的人看）

**一句话定位：把 SillyTavern 的「组装提示词 + 渲染输出」搬到了手机上，并且只留你觉得需要的那部分。**

- **轻**：Android 7.0+ 即可运行；APK 约 5.5 MB；界面全部原生自绘，不依赖 androidx / material
- **本地优先**：会话、角色卡、图片、世界书、正则都存在你本机，卸载即清；API 密钥不外传
- **兼容傻酒馆部分生态**：导入 SillyTavern 的预设、角色卡、世界书、正则就能直接用，不用为某个预设写适配

**单人冒险**

流式输出、随时停止、重新生成（带预设）、一条消息多版本 `‹ ›` 翻页、点气泡分出新会话、手动 / 自动摘要。

**多人群聊（本质就是跑团 / 群聊）**

一个群 = 公共区 + 每个角色一个子频道；切频道即切角色卡与世界书，选择自动保存；每个频道独立生成（切走后台继续）；
公共区可按顺序「续写」或指定谁说；**旁白**单独发言（每群一份设定）；频道内可搜索、可单独压缩历史。底层复用同一套单聊逻辑。

**看得见的卡片**

Markdown / 裸 HTML / 整页 HTML 前端卡自动分流；卡片在沙箱 `iframe` 里跑，作者 CSS / JS 原样生效，高度自动回传；
卡片里的选项按钮可以直接「发送 / 填入 / 追加」。

**生图**

接口与画风分离配置；Danbooru 标签库带中文；对话里快捷配图、自动配图、图库与查看器一应俱全。

---

# 二、它怎么搭的（给「看代码 / 想改」的人看）

三层结构，彼此只通过 JSON 交换数据，谁也不 import 谁——**改预设不会动界面，改界面不会影响提示词。**

| 层 | 位置 | 负责 | 不负责 |
| --- | --- | --- | --- |
| 原生界面 | `dbpg/app` | 会话、聊天、群聊、资产、角色卡、生图、图库、设置 | 不解析预设、不拼提示词 |
| 显示引擎 | `dbpg/stcore`（纯 Kotlin，可 JVM 单测） | 显示正则、宏、变量、思维链剥离、图片标记、富文本分块、会话文档与摘要 | 不碰网络、不碰 Android |
| 生成引擎 | `src/` → `dist/` → `assets/www` | 提示词组装、ST 资产解析、请求构建 | 没有界面，跑在 1dp 透明 WebView 里 |

数据流要点：

- **数据只有一份**：会话存原生 `SessionDoc`，网页端只负责「把这份历史组装成请求」，不落任何对话数据。
- **群聊复用单聊**：公共区 1 个会话 + 每个成员 1 个会话，切频道就是换 `sessionId`；公共区用 `speakerId` 标发言人。
- **配置三层继承**：群 → 成员 → 全局，`null` 一律表示跟随上层，换群配置不用回写 N 个会话。

## 快速开始

```bash
npm install
npm run build            # 生成 dist/，同步到 dbpg/app/src/main/assets/www

cd dbpg
./gradlew :app:assembleDebug
# 产物：dbpg/app/build/outputs/apk/debug/app-debug.apk
```

只改原生代码时可跳过 `npm run build`，但 `dist/` 必须已经存在。
`local.properties` 里的 `sdk.dir` 用正斜杠（`C:/Users/...`）；`compileSdk 37`，需 JDK 17+ 与匹配的 Gradle / AGP。

## 目录

```
dbpg/
├─ app/       原生界面 + assets/www（引擎产物，勿手改）
├─ stcore/    纯 Kotlin 显示引擎（可跑 JVM 单测）
└─ docs/      架构 / 预设兼容性 / 避坑 / 验证 / 改动记录
src/          生成引擎（无界面）
docs/         设计文档
```

## 测试

```bash
cd dbpg
./gradlew :stcore:test            # 224 个：正则 / 宏 / 变量 / 会话 / 摘要 / 富文本
./gradlew :app:testDebugUnitTest  # 37 个：插件契约 / 渲染判定 / 运营商配置
```

App 内另有两处自查：设置 → 渲染与上下文 → **渲染自检**；设置 → 插件 → **结构探针**。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [`dbpg/docs/架构.md`](dbpg/docs/架构.md) | 三层边界、渲染链路、插件契约、存储布局、线程模型 |
| [`dbpg/docs/预设兼容性.md`](dbpg/docs/预设兼容性.md) | ST 字段覆盖表、刻意差异、已知边界 |
| [`dbpg/docs/避坑清单.md`](dbpg/docs/避坑清单.md) | 现象 → 原因 → 解法 |
| [`dbpg/docs/验证与实验台.md`](dbpg/docs/验证与实验台.md) | 单测、自检、真机验证清单 |
| [`dbpg/docs/改动记录.md`](dbpg/docs/改动记录.md) | 逐次改动流水与实测数据 |
| [`来源与授权.md`](来源与授权.md) | 素材、依赖、兼容标准的来源与授权 |

## 声明

- SillyTavern 生态为**兼容实现**，与本项目无隶属关系；预设、角色卡、世界书的版权归原作者。
- 本项目为个人自用工具，不对接任何官方服务，API 密钥只存本机。

---

## 致谢

这个项目站在很多人的肩膀上。他们在生态标准、实现思路和踩坑记录上的公开分享，让我们少走了大量弯路。

**排名不分先后。**

**生态与数据**

SillyTavern · 酒馆助手 · 小白X · Tavo · RikkaHub · Danbooru · NovelAI · wallhaven · chami_tavern-scene-plugin · booru-tags-gallery · FrenzyExists/wallpapers

**基础设施**

Vue · Vite · Pinia · Capacitor · markdown-it · marked · localforage · parsecard · nekoai-js

以及所有愿意把预设、正则、前端卡和调教经验公开出来的创作者。
