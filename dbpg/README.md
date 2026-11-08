# dbpg · 冒险模拟器（Android 原生聊天）

一个**原生 Android 聊天 App**，兼容 SillyTavern 生态：导入 ST 预设即可用它的提示词顺序、正则、变量和前端卡片，不需要改预设、也不需要为某个预设写适配。

- 包名 `com.dbpg.duihuazhushou` · `versionName 1.1.1` / `versionCode 8`
- `minSdk 24`（Android 7.0+）· `compileSdk 37` · **无第三方依赖**（不引 androidx / material）
- APK ≈ 5.5 MB

---

## 1. 它现在是什么

三层，职责边界很清楚：

| 层 | 位置 | 负责 | 不负责 |
| --- | --- | --- | --- |
| 原生 UI | `app/src/main/java/...` + `res/` | 所有看得见的界面：会话、聊天、设置、资产、角色卡、生图、图库 | 不解析预设、不拼提示词 |
| 显示引擎 | `stcore/`（纯 Kotlin，可跑 JVM 单测） | 显示正则、宏、变量、思维链剥离、图片标记、富文本分块 | 不碰网络、不碰 Android |
| 生成引擎 | `../src`（Vue 时代的 ST 兼容层，产物 `../dist` → `assets/www/`） | 提示词组装、ST 资产解析、请求构建 | **没有界面**，跑在 1dp 透明 WebView 里 |

> **一句话：生成归 web 引擎，显示归 stcore，界面归原生。** 三层之间只通过 JSON 传数据，谁也不 import 谁。

网页界面（Vue / router / views）已经物理删除，`src/` 只剩引擎。想找旧界面：`git log --diff-filter=D -- src/views`，不建议恢复 —— 两套 UI 并存必然漂移，这个坑踩过一次。

---

## 2. 已经做好的功能

**聊天**

- 流式输出、停止、重新生成（**带预设**）、重发、编辑、复制、删除
- 一条消息多个版本：`‹ ›` 左右翻，重新生成不会丢旧回答
- 点 AI 气泡分出新会话（分支），长按出操作面板
- 摘要：手动 / 自动浓缩，被覆盖的轮次用摘要替代原文再进上下文
- 配图：手动生图、自动配图、图库、图片查看器

**渲染（本项目的重点）**

- Markdown / 裸 HTML / **整页 HTML 卡片**三种形态自动分流
- 卡片走 `iframe srcdoc` 沙箱：作者 CSS/JS 原样生效，高度自动回传，横滚锁死
- `<details>` 折叠、面板内 `<summary>`、自定义标签（`<xy-card>` 之类）都能渲染
- **卡片里的选项按钮可以点**：按 ST 契约接上 `window.SillyTavern` 与 `#send_textarea`，支持「直接发送 / 填入 / 追加」

**SillyTavern 生态**

| 资产 | 支持情况 |
| --- | --- |
| 预设 | 导入 `.json`；`prompts[]` 库 + `prompt_order` 顺序 + 未激活项；54 条提示词正常 |
| 正则 | `extensions.regex_scripts` 全字段：placement、trimStrings、markdownOnly / promptOnly、substituteRegex、minDepth / maxDepth、`$0/$n/$<name>/{{match}}` |
| 宏 | `{{char}} {{user}} {{persona}} {{getvar::}} {{setvar::}} {{random::}} {{pick::}} {{roll::}} {{time}} {{date}} {{// 注释}}` 等 |
| 世界书 | 导入、条目、注入位置；会话可绑定 |
| 角色卡 | PNG 卡解析、列表、详情、导入 |
| 变量 | session / global 两张表；写宏只在「用户发送」「回复落库」两个入口执行 |

**插件位（卡片渲染）**

- 内置 1 个插件「通用前端卡渲染器」（id `panel_data_adapter`），**只做渲染、不生成内容**
- 随时可安装 / 卸载 / 停用；内置插件**不可删除**（库必须在）
- 单 renderer 仲裁 + `RenderDiagnostic`（认领判据、探针、产出长度）可自查

**外观**

- 主题 / 明暗 / 底板不透明度 / 强调色 / 字号 / 行距 / 字体型号 / 字体颜色 / 气泡宽度 / 背景图
- 列表里卡片是**离屏位图**（`CardRenderer` + LRU），不是每条一个 WebView

---

## 3. 结构地图

```
dbpg/                           Android 工程（本仓库）
├─ build-apk.cmd                一键：npm run build → syncWeb → assembleDebug
├─ settings.gradle.kts          include(":app", ":stcore")
├─ README.md                    本文件
├─ docs/                        架构 / 避坑 / 兼容性 / 灵感方向 / 验证 / 改动记录
├─ stcore/                      纯 Kotlin 引擎（218 个单测）
│  └─ src/main/java/com/dbpg/stcore/
│     ├─ RegexEngine.kt         正则语义（与 ST 对齐 + 两处刻意不同）
│     ├─ Macro.kt               宏（基础 / 带参 / 注释）
│     ├─ Variables.kt           {{getvar}} {{setvar}} …
│     ├─ ImageMarker.kt         显示链路总入口 transformForDisplayRich
│     ├─ Reasoning.kt           思维链剥离
│     ├─ richtext/              富文本解析（不含 Android 类型）
│     ├─ session/               会话文档 / 编辑 / 导出 / 摘要
│     └─ character/ imagegen/ jsonl/ render/
└─ app/src/main/
   ├─ assets/
   │  ├─ www/                   引擎产物（syncWeb 生成，勿手改）
   │  ├─ msg/                   消息渲染壳 render.html / render.js / render.css / marked
   │  ├─ vendor/                capacitor 垫片
   │  └─ plugins/               （空：插件已代码内置，不再放模板）
   └─ java/com/dbpg/duihuazhushou/
      ├─ NativeChatActivity.kt      聊天页（发送 / 生成 / 菜单 / 输入框）
      ├─ chat/
      │  ├─ ChatController.kt       渲染与生成的中枢（renderCached / decideRender）
      │  ├─ ChatAdapter.kt          列表 + 富文本 ViewHolder + WebView 池
      │  ├─ GenBridge.kt            原生 ↔ web 引擎的生成通道（按 requestId 找回界面）
      │  ├─ DisplayConfig.kt        显示快照（scripts / vars / macroContext）
      │  └─ render/                 CardRenderer（位图）/ HtmlMessageView（WebView）/ plugin/
      ├─ session/                   会话列表 / 仓库 / 绑定 / 摘要入口
      ├─ assets/                    资产页（预设 / 世界书 / 正则组）
      ├─ character/                 角色卡
      ├─ imagegen/ gallery/         生图 / 图库
      ├─ settings/                  连接、模型、生图、插件、引擎桥
      └─ appearance/                外观、主题、渲染设置
```

> 同级的 `src/`（引擎源码）与 `dist/`（引擎产物）属于另一个工程，**不在本仓库**。APK 构建时 `syncWeb` 会把 `../dist` 复制进 `app/src/main/assets/www/`；只改原生代码时不需要碰它们。


**改什么去哪里**

| 想改 | 改这里 | 别改 |
| --- | --- | --- |
| 看得见的界面 | `app/src/main/java/...` + `res/layout` | `src/views`（已删） |
| 消息渲染 / 卡片 | `stcore`（语义）→ `chat/render/`（呈现） | `assets/www`（构建产物） |
| 提示词 / 预设解析 | `../src` | `stcore`（它不拼提示词） |
| 正则行为 | `stcore/RegexEngine.kt` | 别在插件里按预设打补丁 |
| 卡片渲染增强 | `chat/render/plugin/PluginCatalog.kt` | 别改预设模板 |

---

## 4. 消息是怎么显示出来的

```
模型输出（原文，落库）
   │
   ├─ 剥思维链（Reasoning）            hideReasoning 开时
   ├─ 显示正则（RegexEngine）          forDisplay=true：跳过 promptOnly
   │     └─ 替换串里的宏 / 变量展开
   ├─ 宏（expandMacrosForDisplay）     未知宏保留，不清空
   ├─ 变量（readVariables）            写宏抹空，不改表
   ├─ 图片标记 → 文字块 / 图片块
   └─ 两路输出
        ├─ plain  → 纯文本气泡（TextView + Spannable）
        └─ rich   → decideRender
                     ├─ 插件仲裁（PluginRegistry.resolveHtml）
                     ├─ HtmlSanitizer.clean
                     ├─ 有非 <br> 标签？
                     │    ├─ 是 → HtmlMessageView（WebView + iframe srcdoc）
                     │    └─ 否 → 原生文本 / <details> 折叠块
```

要点：

- **落库存原文**。ST 是把正则改写后的文本存进聊天记录，我们每次渲染重算 —— 所以「双 false 的普通正则」在显示和提示词两侧都要跑，这是**必要的差异**，不是 bug。
- **只在渲染时跑正则**，不写回数据。改预设 / 改正则 / 改外观，缓存键变化，旧消息自动重算。
- 缓存键 = `actionId + 内容哈希 + 外观哈希 + 深度`。深度只在存在 `minDepth/maxDepth` 脚本时参与，避免每条消息都失效。

细节见 [`docs/架构.md`](docs/架构.md)。

---

## 5. 十分钟跑起来

**环境**：Node.js + Android Studio（自带 JBR 和 SDK）。首次打开 Android Studio 让它初始化一次 SDK。

```bash
# 1. 构建引擎产物（在 duihuazhushou/ 下）
npm run build

# 2. 打 APK（在 dbpg/ 下）
./gradlew :app:assembleDebug
#    或者直接双击 build-apk.cmd（会先跑 npm run build）

# 3. 产物
app/build/outputs/apk/debug/app-debug.apk
```

只改原生代码、没动 `src/` 时可以跳过第 1 步（`syncWeb` 仍会复制 `../dist`，所以 `dist` 必须存在）。

**版本矩阵（不能只动一个）**：JDK 25 → Gradle ≥ 9.1；AGP 9.x → Gradle 9.x；`compileSdk` 必须等于本机装了的平台版本。当前是 AGP 9.4.1 / Gradle 9.8.0 / compileSdk 37 / buildTools 36.0.0。

`local.properties` 的 `sdk.dir` **必须用正斜杠**（`C:/Users/...`），反斜杠在 `.properties` 里是转义符，会把路径吃掉。

---

## 6. 测试

```bash
./gradlew :stcore:test                  # 218 个：正则 / 宏 / 变量 / 思维链 / 会话 / 富文本
./gradlew :app:testDebugUnitTest        # 14 个：插件契约 / 渲染判定
```

`stcore` 不依赖 Android，能直接跑 JVM 单测 —— 这是它单独拆出来的主要理由：正则语义的坑（`$0`、trimStrings、双 true）只有靠单测才锁得住。

App 内还有两个自查入口：

- 设置 → 渲染与上下文 → **渲染自检**：输入形态 / rich 长度 / 各插件认领 / 生效插件 / 产出长度
- 设置 → 插件 → **结构探针**：卡片后正文 N 行、选项行 M、分隔线 K

---

## 7. 最容易踩的 8 个坑（完整版见 `docs/避坑清单.md`）

1. **列表里不要塞 WebView**。滚出屏幕再回来就重载，必卡。现在是离屏画 WebP + LRU 复用，WebView 只给可见的富消息且池上限 2。
2. **`htmlifyNewlines` 会把 `\n` 变 `<br>`**，于是 `---` 变成 `<hr>`。任何按行/按分隔线定位的逻辑都要先归一化。
3. **整页 HTML 不能过 `htmlifyNewlines`**，否则 `<head>`/`<div>` 之间被插 `<br>`，卡片被撑出一串空行。
4. **正则双标志不是互斥**。显示侧跳过 `promptOnly`，提示词侧只跳过「纯显示」脚本 —— 详见 `docs/预设兼容性.md`，别照 ST 源码改回去。
5. **iframe 不给 `allow-same-origin`，`parent.document` 必抛**。预设选项按钮就死在这一句上。
6. **卡片不是我们造的**。插件只做「补 head / 包文档 / 统一围栏标注」，不解析散文、不填充模板、不猜选项。
7. **`subprocess` 式的"清理文件"别乱删**。`assets/plugins/` 现在空着是故意的，模板已移出 APK。
8. **改渲染后必看缓存键**。内容哈希 / 外观哈希 / 深度少一个，就会出现「改了设置但旧消息不变」。

---

## 8. 文档

| 文档 | 内容 |
| --- | --- |
| [`docs/架构.md`](docs/架构.md) | 三层边界、渲染链路、插件契约、存储布局、线程模型 |
| [`docs/预设兼容性.md`](docs/预设兼容性.md) | ST/Tavo 字段覆盖表、两处刻意差异、已知边界、扩展方法 |
| [`docs/避坑清单.md`](docs/避坑清单.md) | 按主题的 30 条「现象 → 原因 → 解法」 |
| [`docs/灵感方向.md`](docs/灵感方向.md) | 做新功能前先看谁做过：ST 官方 → 插件源码 → 同类客户端 → 通用库 |
| [`docs/验证与实验台.md`](docs/验证与实验台.md) | 单测、自检、`st-lab` 脚本、真机验证清单 |
| [`docs/改动记录.md`](docs/改动记录.md) | 33 节完整流水（含每次实测截图/探针数据） |

---

## 9. 设计原则（改代码前先读）

1. **开放式，不针对预设**。功能是给所有预设用的：插件认「HTML 卡片」这个形态，不认「Meowssiah」这个名字。
2. **预设的事归预设**。卡片长什么样、有没有折叠、几个选项，由预设的正则和模板决定；我们只负责把 HTML 渲染对。
3. **不为单个 bug 加正则补丁**。上一次试图「从散文里猜面板」写了 400 行，最后证明模型只是没遵守预设格式，全删了。
4. **链接必须是「模型输出 → 正则 → 渲染」**。任何绕开正则、直接解析语义的捷径都会在下一个预设上翻车。
5. **可卸载**。插件、正则、预设都能停用；停用后行为回到「原始文本」，不能崩。
