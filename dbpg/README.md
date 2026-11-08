# dbpg — 冒险模拟器 Android 工程

> ## ⚠ 先读这里：这是「原生优先」App，不是网页套壳（2026-11-08 更新）
>
> 本文下面「把 Vue 打包成 APK」描述的是**最早的套壳形态**，现状已经变了：
>
> - **用户在手机上看到的界面，全部是原生 Kotlin**：首页 / 会话列表 / 聊天 / 设置 / 资产 / 生图 / 图库，都在 `app/src/main/java/com/dbpg/duihuazhushou/`。
> - **网页（上一级 `src/`，构建产物 `dist` → `assets/www/`）只剩对话引擎**：SillyTavern 兼容层（预设 / 正则 / 世界书 / 角色卡 / 变量 / 宏）+ 生成请求组装。它跑在 1dp、`alpha=0` 的隐藏 WebView 里，**用户看不到**，相当于后端。
> - 一句话：**要改用户看得见的界面 → 改 `app/src/main/...`（原生）；要改 ST 兼容 / 生成逻辑 → 改 `src/`（web 引擎）。**
>
> ### 外观系统在哪（最容易改错的地方）
>
> | 想改 | 改这里 | ❌ 不要改 |
> | --- | --- | --- |
> | App 设置页本身 | `appearance/SettingsActivity.kt` + `res/layout/activity_settings.xml` | `src/views/Settings.vue` |
> | 外观数据（字号/行距/字体/颜色/气泡/背景） | `appearance/Appearance.kt`（SharedPreferences） | `src/stores/appearance.ts` |
> | 主题色 / 页面底色 / 装饰 / 全界面字体套用 | `appearance/Theme.kt` | `src/styles/main.css` |
> | 聊天气泡渲染（文字/图片/版本条） | `chat/ChatAdapter.kt` | web 的聊天组件 |
>
> `src/views/Settings.vue`、`src/stores/appearance.ts` 是**引擎内部页**，只有用浏览器跑
> `npm run dev` 才看得到；**改它们不会改变 APK 的设置页**。这个坑已经踩过一次。
>
> ### 底板 / 图标规范（2026-11 统一，改之前先看这段）
>
> **底板（卡片、按钮、顶栏、输入栏、弹窗）只有一套色，来自 `ThemeTokens.panel`。**
>
> - 颜色由 `appearance/Theme.kt` 的 `ThemePalette.tokensOf(cfg)` 算：
>   页面底色 + `panelOpacity`（设置里的「底板不透明度」）+ 明暗推导。
> - 再由 `UiBackdrop.apply()` → `View.applyTokens()` **递归**套到每个 View 的 background 上，
>   判据是「填充色亮度 + 饱和度」，不是逐个登记 drawable 名字。
>   所以新写一个白色卡片，不用改代码也会自动跟随。
> - **不要**在布局里新加 `android:background="#FFFFFF"` 这种实心白去"手动对齐"——
>   它会先按白色渲染一帧，再被递归覆盖，白白多一次闪烁。用 `@drawable/bg_card` 就行。
> - 想加一套新皮肤：往 `ThemePalette.STYLES` 加一条 `(id, 名称, 底色, 主题色)`，
>   设置页会自动多一个选项；深色底的单子会自动翻白字（`tokensOf` 按底色亮度判 dark）。
>
> **图标一律用 VectorDrawable（Android 版的 SVG），不要用 emoji。**
>
> - 现成的在 `res/drawable/ic_*.xml`：24dp viewport、线宽 1.7、`strokeColor="#5A6B85"`。
> - 布局里这样用（**必须带 `android:tint`**，否则 `UiBackdrop` 换主题色时染不到它）：
>   ```xml
>   <ImageView
>       android:layout_width="24dp"
>       android:layout_height="24dp"
>       android:src="@drawable/ic_palette"
>       android:tint="#5A6B85" />
>   ```
> - emoji（🎭🎨📦…）在不同 ROM 上字体不同、大小对不齐，看着廉价，已经全部替换掉了。
>
> **两个已知的 Android 限制（别踩）**
>
> 1. `GradientDrawable` 没有 `getStrokeWidth()`，只有 `setStroke()`。
>    所以描边**不能**只换色——无条件 `setStroke` 会给本来没描边的 shape 硬加一圈框。
>    描边保留 XML 原值（浅灰 `#EBEFF6`）。
> 2. RecyclerView 的 item 是滚动时现 inflate 的，一次性递归覆盖不到。
>    所以 `UiBackdrop.hookRecycler()` 会给每个 RecyclerView 挂
>    `OnChildAttachStateChangeListener`，新项进场时按**当前** token 补色。
>    自己写新的列表页时不用额外做什么，只要根布局里有 RecyclerView 就会自动挂上。

> ### 渲染与上下文（2026-11-08 新增，改之前先看）
>
> 设置首页 → **「渲染与上下文」**（`appearance/RenderSettingsActivity.kt` +
> `res/layout/activity_render_settings.xml`）。它管两件事，都**不在**外观系统里：
>
> | 想改 | 改这里 | 说明 |
> | --- | --- | --- |
> | 美化渲染开关 / 卡片脚本 | `appearance/Appearance.kt`（`htmlRender` / `allowScript`） | 从「UI 风格」页迁到这里，旧页已删掉这两项，别再加回去 |
> | 卡片怎么画 | `chat/render/CardRenderer.kt` + `ChatAdapter.kt` | 列表里是**位图**（ImageView），不是 WebView |
> | 点卡片看全文 | `chat/render/CardViewerActivity.kt` | 全屏单例 WebView，可选中、可跑脚本 |
> | 摘要参数 | `summary/SummaryConfig.kt` | SharedPreferences `dhp_summary` |
> | 摘要请求 | `summary/SummaryService.kt` | 原生 OkHttp 直连，**不走 web 生成通道** |
> | 摘要注入上下文 | `stcore/session/SessionHistory.kt` | 被覆盖的轮次不再发原文 |
> | 「带预设」到底带什么 | `../src/engine/summaryPreset.ts` | 预设全量带；角色卡/世界书宏清空 |
>
> **三条铁律：**
>
> 1. **列表里不要塞 WebView**。以前 `item_chat_message.xml` 每条一个 WebView，
>    滚出屏幕再回来就重载一次 —— 这是"一顿一顿"的直接原因。现在是
>    `CardRenderer` 离屏画一张 WebP，`CardBitmaps` 解码 LRU 复用。
> 2. **摘要 ≠ 扮演**。摘要请求里只带「预设 + 上下文」，
>    角色卡、世界书、正则一律不带。预设要**全量**带（砍一半就失效），
>    只把角色卡类宏（`{{description}}` 等）替换为空。
> 3. **`keepRecent` 不存在**。触发阈值 10、单次取 8，差值天然留下最近 2 条原文。
>    不要再加一个"保留最近 N 条"，会让触发点漂移。
>
> 摘要入口在聊天页右上角（`chat_summary`）：查看 / 编辑 / 立即浓缩 / 清除。
> 摘要有变化时图标变主题蓝，跑任务时会变淡。

把上一级目录（`duihuazhushou/`）的 Vue + Vite 网页应用打包成 Android APK。

**这个目录是完全独立的 Android 工程。** 没有改动 Web 工程里的任何源码文件，
只是构建时去读它的构建产物 `../dist`。

- 包名 `com.dbpg.duihuazhushou`
- 当前版本 `versionName 1.0.4` / `versionCode 6`
- 产物 `app\build\outputs\apk\debug\app-debug.apk`，约 5.4 MiB（5,716,909 字节）

---

## 一、最快的用法：双击 `build-apk.cmd`

在 `dbpg/` 目录里双击 `build-apk.cmd`（或右键 → 运行），它会依次做：

```
1. 在上一级目录跑 npm run build      （生成 dist）
2. 同步 Web 资源进 APK 的 assets
3. 跑 gradlew :app:assembleDebug      （生成 APK）
```

产物在：

```
dbpg\app\build\outputs\apk\debug\app-debug.apk
```

拷到手机上安装即可（第一次需要开启「允许安装未知来源应用」）。
debug 签名的包可以直接装，手机上显示为「Android Debug」证书。

> 第一次跑会下载 Gradle 9.8.0（145 MB）和 AGP 依赖，需要联网，大概几分钟。
> 之后就是本地缓存，十几秒。
>
> **本机已实测跑通**（见文末「十、实测记录」）。

---

## 二、首次需要装什么

| 工具 | 为什么需要 | 下载地址 |
|---|---|---|
| Node.js | 构建 Web 版本 | https://nodejs.org |
| Android Studio | 自带 JBR（JDK 25）和 Android SDK | https://developer.android.com/studio |

装好 Android Studio 后**先打开它、随便新建一个 Empty Activity 项目、等它初始化完再关掉**。
这一步是为了让它把 Android SDK 安装到本地（脚本会自己去这几个位置找 SDK）：

```
%LocalAppData%\Android\Sdk
%LocalAppData%\Programs\Android Studio\sdk
%ProgramFiles%\Android\Android Studio\sdk
```

找不到的话，就在 Android Studio 里 `Settings → Languages & Frameworks → Android SDK`
看一眼 SDK 路径，然后手动在 `dbpg/` 下新建一个 `local.properties`：

```
sdk.dir=C:/Users/你的用户名/AppData/Local/Android/Sdk
```

**路径必须用正斜杠**，不要用反斜杠。`.properties` 文件里反斜杠是转义符，
`C:\Users\...` 会被读成 `C:Users...`（`\U`、`\A` 这些非法转义会直接把反斜杠吃掉），
结果就是 `SDK location not found`。另外保存时别带 UTF-8 BOM。
脚本 `build-apk.cmd` 自动探测时用 Node 写入，已经处理了这两点。

**想直接在 Android Studio 里打的话**：`File → Open` 选 `dbpg/` 目录，等 Gradle 同步完，
菜单 `Build → Build Bundle(s)/APK(s) → Build APK(s)` 即可。

---

## 三、目录结构

```
dbpg/
├─ build-apk.cmd                 一键打包脚本
├─ local.properties              SDK 路径（.gitignore 已排除，脚本会自动生成）
├─ settings.gradle.kts           工程设置（不引任何第三方库）
├─ build.gradle.kts              根脚本，只声明 AGP 9.4.1
├─ gradle.properties             内存/编码参数
├─ gradlew(.bat)                 Gradle 包装脚本（会自动找 Android Studio 自带的 JBR）
├─ gradle/wrapper/               wrapper jar + 指向腾讯镜像的 Gradle 9.8.0
│
├─ app/
│  ├─ build.gradle.kts           ★ Web 资源同步 + index.html 注入
│  └─ src/main/
│     ├─ AndroidManifest.xml     权限（INTERNET）、主题、单 Activity
│     ├─ java/com/dbpg/duihuazhushou/
│     │  ├─ MainActivity.java    ★ WebView 容器 + 静态资源拦截 + 文件选择 + 下载
│     │  └─ FileBridge.java      ★ 暴露给 JS 的文件读写（window.dhp）
│     ├─ assets/vendor/
│     │  └─ capacitor-compat.js  ★ 原生桥接垫片（构建时复制进 www 并注入）
│     └─ res/                    图标（自适应 + PNG 兜底）、主题、颜色
│
└─ tools/
   ├─ make-icons.cjs             生成全部图标（自适应 XML + PNG 兜底 + Web/PWA）
   └─ fetch-wrapper.mjs          备用：wrapper jar 坏了用它重新拉（首选跑 gradle wrapper 任务）
```

带 ★ 的是三个关键文件，下面逐个说。

---

## 四、它是怎么把网页变成 App 的

### 4.1 Web 资源怎么进去的

`app/build.gradle.kts` 里有一个 `syncWeb` 任务挂在 `preBuild` 上，每次构建都会跑：

```
../dist/**                      → app/src/main/assets/www/
app/src/main/assets/vendor/**   → app/src/main/assets/www/   （垫片）
```

然后 `patchWebIndex` 往 `assets/www/index.html` 的 `<head>` 里插两行：

```html
<script type="importmap">{"imports":{"@capacitor/filesystem":"/capacitor-compat.js"}}</script>
<script type="module" src="/capacitor-compat.js"></script>
```

这两行只存在于 APK 里。**Web 工程源码和 `dist` 目录都没被改过** —— 注入是写在
`assets/www/`（这个目录在 `.gitignore` 里）这个副本上做的。

### 4.2 页面从哪个地址加载

`MainActivity` 加载的是：

```
https://dbpg.local/index.html
```

`dbpg.local` 是个**虚构域名**，不会真的联网。所有对它的请求都在
`shouldInterceptRequest` 里被拦下来，直接 `getAssets().open("www/" + path)` 返回内容。

为什么不用 `file:///android_asset/...`，而要用一个假 https 域名：

| 问题 | `file://` | `https://dbpg.local` |
|---|---|---|
| `/assets/xxx.js` 这种绝对路径 | origin 是文件路径，对不上 | ✅ 有正常域名根 |
| `localStorage` / `IndexedDB` | ❌ opaque origin，直接失效 | ✅ 正常 |
| ES module / import map | ❌ 被 CORS 拦 | ✅ 同域 |

`localStorage` 和 `IndexedDB` 是关键 —— 项目里的 `localforage` 和 `storage.ts`
全靠它俩，用 `file://` 打开的话配置和素材全部存不下来。

### 4.3 手机端文件落盘是怎么打通的

Web 工程里已经预留好了手机分支：

- `src/lib/persist.ts`：先判断 `window.Capacitor.isNativePlatform()`，
  是原生环境就走文件落盘，否则走 `__persist/` HTTP 接口（浏览器模式）；
- `src/lib/persistFile.ts`：Capacitor 实现，
  `import("@capacitor/filesystem")` 拿 `Filesystem` 插件，
  把配置写成 `files/Data/app-data.json`，并自动备份最近 20 份。

问题是这两个包并没有安装（`package.json` 里确实没有 `@capacitor/*`），
源码里是用 `/* @vite-ignore */` 加动态 import 绕开打包器的，
所以浏览器版本永远加载不到这个分支 —— **电脑端行为完全不受影响**。

APK 这边用一个 7 KB 的垫片 `capacitor-compat.js` 补上最小可用实现：
它只实现 `Filesystem` 插件里用到的那几个方法，背后走 `FileBridge` 的 JSBridge
写到 App 的私有目录。这就是 4.1 里注入 import map 的意义 —— 把
`@capacitor/filesystem` 这个裸导入映射到本地垫片。

---

## 五、技术选型一览

| 项 | 版本 | 说明 |
|---|---|---|
| AGP | 9.4.1 | 当前稳定版 |
| Gradle | 9.8.0 | 默认走腾讯镜像，官方源写在 `gradle-wrapper.properties` 注释里 |
| compileSdk / targetSdk | 37 | 必须等于本机 SDK 里实际装的平台版本 |
| buildToolsVersion | 36.0.0 | 显式指定，免得 AGP 去下载默认版本 |
| minSdk | 24 | Android 7.0+；7.0~7.1 用 PNG 图标，8.0+ 用自适应图标 |
| Java | source/target 17 | 跑 Gradle 用 Android Studio 自带的 JBR 25 |
| 第三方依赖 | **无** | 不引 androidx / material，Gradle 同步只需要下载 AGP，失败面最小 |

> **Gradle 版本不能随便降**：JBR 是 JDK 25，**JDK 25 要求 Gradle ≥ 9.1.0**。
> AGP 9.x 要求 Gradle 9.x。AGP / Gradle / JDK 三者要一起动，别只改一个。

---

## 六、三个关键文件

### `MainActivity.java`

单 Activity WebView 容器。四件事：

1. `shouldInterceptRequest` 拦截所有 `https://dbpg.local/*` 请求，从 assets 读文件返回，
   带上正确的 `Content-Type`（`.js` → `application/javascript`，`.wasm` 等按扩展名推）；
2. `WebChromeClient.onShowFileChooser` 接住网页的文件选择（导入/上传图片用）；
3. `DownloadListener` 把 `<a download>` 的下载落到 App 的 `files/Download/` 目录；
4. 顶部 `setWebContentsDebuggingEnabled(true)` —— 电脑连上手机后可以用
   Chrome 的 `chrome://inspect` 看控制台，排问题必备。

### `FileBridge.java`

`@JavascriptInterface` 暴露给 JS 的桥。JS 侧调用形如 `window.dhp.api()`，
提供读/写/列目录/删除/备份几个方法。所有路径都限制在 App 的私有目录内
（先 resolve 再校验前缀，防止 `../` 逃逸）。

### `assets/vendor/capacitor-compat.js`

原生桥接垫片，详见 4.3。它同时挂两样东西：

- `window.Capacitor`（含 `isNativePlatform()`，让 `persist.ts` 的环境判断成立）
- `@capacitor/filesystem` 模块导出（让动态 import 拿到 `Filesystem`）

---

## 七、常见报错

**`SDK location not found`**
`local.properties` 不存在或路径写坏了。两种情况：
- 没有这个文件 → 先打开一次 Android Studio 让它初始化 SDK，再跑 `build-apk.cmd`（会自动探测写入）。
- 文件里是 `sdk.dir=C:\Users\...` 这种反斜杠 → 改成正斜杠 `C:/Users/...`。
  反斜杠在 `.properties` 里是转义符，路径会被吃掉变成 `C:Users...`。

**`Failed to find Build Tools revision` / `compileSdk 37 requires ...`**
说明 `compileSdk` / `buildToolsVersion` 和本机 SDK 里装的版本对不上。
先看看 `Android SDK\platforms` 和 `build-tools` 下到底有哪些版本，然后把
`app/build.gradle.kts` 里的 `compileSdk` / `targetSdk` / `buildToolsVersion` 改成匹配的值。

**Java 相关报错（`Unsupported class file major version` / `invalid target release`）**
JDK 版本和 Gradle / AGP 不匹配。查一下本机 JBR 版本（`java -version`），
对着「五、技术选型一览」把三个版本号对齐。

**Gradle 下载卡住或 404**
`gradle/wrapper/gradle-wrapper.properties` 里 `distributionUrl` 换成官方源：

```
distributionUrl=https\://services.gradle.org/distributions/gradle-9.8.0-bin.zip
```

**`JAVA_HOME is not set`**
`gradlew.bat` 会自动找 Android Studio 的 JBR，但如果 Android Studio 装在
它不认识的位置，就手动在命令行里先设一下再跑：

```
set JAVA_HOME=C:\路径\到\jbr
gradlew.bat :app:assembleDebug
```

**APK 里是旧版本的页面**
`syncWeb` 读的是 `../dist`。先在上一级目录跑一次 `npm run build` 再打包，
或者直接用 `build-apk.cmd`（它会自动先跑）。

**手机上配置改动不保存**
用 Chrome 的 `chrome://inspect` 连设备看控制台有没有 `[dhp] 原生桥接已启用`。
没有的话说明垫片没注入成功，检查 `assets/www/index.html` 里有没有 `importmap` 那两行。

**手机上点导入没反应**
`onShowFileChooser` 没接上。看 `MainActivity.java` 里 `AppWebChromeClient` 那段。

---

## 八、想改什么

| 改什么 | 改哪里 |
|---|---|
| 包名 | `app/build.gradle.kts` 的 `namespace` 和 `applicationId`，**加上** `app/src/main/java/` 下的包目录（必须一致） |
| 版本号 | `app/build.gradle.kts` 的 `versionCode` / `versionName`。换名重装要 bump `versionCode`，否则覆盖安装会失败 |
| App 名称 | `app/src/main/res/values/strings.xml` 的 `app_name` |
| 图标 | 改 `tools/make-icons.cjs` 里的 `SHAPES` 和 `C`（配色），然后 `node tools\make-icons.cjs` |
| 主题色 | `app/src/main/res/values/colors.xml` 的 `app_bg`（MainActivity 里的 `BG_COLOR` 也要一起改） |
| 最低系统版本 | `app/build.gradle.kts` 的 `minSdk` |
| 应用内显示的名字 | Web 工程：`index.html` 的 `<title>`、`vite.config.ts` 的 PWA manifest、`src/layouts/MainLayout.vue` 的侧栏标题 |

### 图标设计约定

`tools/make-icons.cjs` 不依赖 sharp / canvas —— 自带一个很小的光栅化器
（图标只由圆和圆角矩形组成，逐像素判定就够）和自写 PNG 编码器。

一份设计稿（512 坐标空间）同时产出：

- Android 自适应图标前景 `drawable/ic_launcher_foreground.xml`（矢量，108dp）
- Android 7.0~7.1 的 PNG 兜底（mdpi~xxxhdpi，方形 + 圆形两种）
- Web / PWA 图标（`public/icon.svg`、`pwa-192.png`、`pwa-512.png`、`apple-touch-icon.png`）

所以改一次 `SHAPES`，App 桌面、浏览器标签、PWA 图标全一起变。

**自适应图标有「安全圆」约束**：108dp 的画布里只有中间 66dp 直径保证不被裁。
换算到 512 的坐标空间就是半径 **156.7**。主体要落在这个圆内，
否则系统裁成圆形 / 水滴形时会切到图案。天线球这类装饰可以稍微出头。

---

## 九、怎么验证手机分支真的生效

在 Android Studio 里看 `dbpg/app/src/main/assets/www/index.html`，开头应该是：

```html
<head>
<script type="importmap">{"imports":{"@capacitor/filesystem":"/capacitor-compat.js"}}</script>
<script type="module" src="/capacitor-compat.js"></script>
<meta charset="UTF-8" />
...
```

注意 `importmap` 必须在任何 `<script type="module">` **之前**，否则映射不生效 ——
`patchWebIndex` 是插在 `<head>` 标签后第一行的，Vite 产物的 module 脚本在
`<title>` 之后，所以顺序是对的。

装到手机上改一条配置，然后：

```
adb shell run-as com.dbpg.duihuazhushou ls files/Data
```

能看到 `app-data.json` 和 `backups/` 目录就说明落盘生效了。

---

## 十、实测记录（本机真的打过一遍）

**环境**：Android Studio 装在 `D:\androidstudio`（自带 JBR 25.0.3），
Android SDK 在 `C:\Users\25606\AppData\Local\Android\Sdk`（platform `android-37.0`，
build-tools `36.0.0`），Node.js v24.9.0。`JAVA_HOME` 没设，`java` 不在 PATH。

**跑法**（`dbpg/` 目录下，故意不设 `JAVA_HOME`，验证自动探测）：

```
gradlew.bat :app:assembleDebug
```

**结果**：`BUILD SUCCESSFUL`，35 个任务全过。删掉整个 `app\build` 从零重打也是
35 个任务全执行、11 秒通过，可复现。

逐项验收（都实际执行过）：

| 检查项 | 方法 | 结果 |
|---|---|---|
| Gradle 用上了 JBR | 不设 `JAVA_HOME` 直接跑 | ✅ 输出「已自动使用: `D:\androidstudio\jbr`」 |
| wrapper 下载源正常 | wrapper 首次运行 | ✅ 从腾讯云镜像下载 gradle-9.8.0-bin.zip（144.6 MB），sha256 校验通过 |
| Web 版本没被弄坏 | 上一级目录 `npm run build` | ✅ `vue-tsc` 类型检查通过 |
| import map 注入正确 | 解包 APK 看 `assets/www/index.html` | ✅ import map 和垫片 script 在 Vite 主 module script 之前 |
| 垫片进了包 | 解包 APK | ✅ `assets/www/capacitor-compat.js`（7260 B），UTF-8 完好 |
| 手机落盘分支进了包 | 解包 APK | ✅ `assets/www/assets/persistFile-*.js` 懒加载块 |
| Java 类进了 dex | `dexdump -f` | ✅ `MainActivity`、`FileBridge`、4 个内部类 + `R` |
| Manifest 正确 | `aapt2 dump badging` | ✅ `minSdk 24` / `targetSdk 37`、标签「冒险模拟器」、versionCode 2 / versionName 1.0.0、`INTERNET`、5 档图标、launcher 是 `MainActivity` |
| 签名可用 | `apksigner verify` | ✅ Android Debug 证书，退出码 0 |
| 新 chunk 进包 / 旧 chunk 清除 | `tar -tf` APK 后逐个比对 | ✅ `syncWeb` 是 Sync 任务，会删掉过时文件 |

**版本矩阵的坑**：Gradle 8.7 + JDK 25 起不来（JDK 25 要 Gradle ≥ 9.1.0，
AGP 9.x 要 Gradle 9.x），所以 AGP 8.5.2 → 9.4.1、Gradle 8.7 → 9.8.0、
compileSdk 34 → 37（本机只有 `android-37.0`）。

**其余踩过的坑**（都已修掉）：

1. Gradle 9.6 废弃了 `tasks.registering(...)`，9.8 里直接编译报错 → 改成
   `tasks.register("name", Type::class) { ... }`。
2. `html.replaceFirst(Regex(...)) { m -> ... }` 在 Gradle 9.8 的 Kotlin DSL 环境里
   重载解析成 `(CharSequence, String)` 直接编译失败 → 改成 `Regex.find()` + 字符串拼接。
3. `excludes += setOf("META-INF/*")` 不行，`excludes` 是 `SetProperty<String>` → 改 `.add()`。
4. Java 里写 `fileChooserParams.acceptTypes` → Java 没有 bean 属性访问，要 `getAcceptTypes()`。
5. `local.properties` 里 `sdk.dir=C:\...` 的反斜杠被 properties 解析吃掉 → 必须写正斜杠。
6. `Set-Content -Encoding UTF8` 会加 BOM，Gradle 读 `.properties` 时 BOM 会让第一行失效 →
   改用无 BOM 写入。
7. `.bat` 文件里写中文必须用 GBK 保存，UTF-8 会让 cmd.exe 解析乱掉。
8. PowerShell 改 LF 换行的 markdown 时别用 `-split "`r`n"` —— 切不出来，
   整份内容会变成一行，再写回就把文件清空了。
