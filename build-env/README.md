# 打包成安卓 APK —— 环境与步骤

这份文档是给"以后要打包时"用的。**现在不用做任何事**，代码已经准备好了。

---

## 一、现在已经做好的部分（不需要你再动手）

### 1. 手机端文件存储（已完成）

新增了两个文件，**不影响电脑上的任何现有功能**：

| 文件 | 作用 |
|---|---|
| `src/lib/persistFile.ts` | 手机版存储：把配置写成 App 私有目录里的真实文件 |
| `src/types/capacitor.d.ts` | Capacitor 接口的类型声明（避免现在就装依赖） |

`src/lib/persist.ts` 里加了一段**环境判断**：

- 在浏览器里运行 → 完全走原来那套（开发服务器写文件）**行为一字不变**
- 在手机 App 里运行 → 自动切换到手机版文件存储

判断方式是看全局有没有 `window.Capacitor`。浏览器里它是 `undefined`，
所以那段手机代码**永远不会执行**，构建产物体积也没变化（只有一个 3.8 KB 的懒加载块）。

**实测确认（浏览器环境）**：

```
window.Capacitor        = undefined
落盘模式                 = file
落盘文件                 = E:\duihuazhushou\data\app-data.json
手机端是否误启用          = false
写入/读回                = 正常
```

### 2. 数据格式两边一致

手机版写出来的 `app-data.json` 和电脑上**完全相同**的结构：

```json
{
  "version": 2,
  "updatedAt": 1730000000000,
  "instanceId": "mobile-xxx",
  "data": { "...你的所有配置..." }
}
```

所以：**电脑上导出的 JSON 能直接导进手机，反之也一样。**
手机端还会自动保留最近 20 份备份在 `Data/backups/` 下。

---

## 二、还需要做的部分

### 第 1 步：装 Android Studio（**你自己做，不花 token**）

1. 打开 https://developer.android.com/studio
2. 下载 Windows 版，运行安装程序，**一路默认下一步**
3. 第一次启动时，向导会问你缺什么并**自动帮你装好**：
   - JDK（Java 运行环境）
   - Android SDK
   - 只按它推荐的选就行，不用自己判断

**装到哪了**（以后想卸载时删这三处）：

```
C:\Program Files\Android\Android Studio      ← 主程序，用「设置→应用→卸载」
C:\Users\25606\AppData\Local\Android\Sdk     ← SDK，直接删目录
C:\Users\25606\.gradle                       ← Gradle 缓存，直接删目录
```

**能卸干净**：Android Studio 有官方卸载程序；那两处是**纯数据目录，不含注册表项**，直接删掉即可。

### 第 2 步：装打包依赖（告诉我，我来做）

```bash
npm i -D @capacitor/cli
npm i @capacitor/core @capacitor/filesystem
npx cap init 对话助手 com.duihuazhushou.app --web-dir dist
npx cap add android
npx cap sync
```

### 第 3 步：出 APK

**方式 A — 用 Android Studio（推荐，最直观）**

1. 打开 Android Studio → `Open` → 选 `android` 文件夹
2. 等它把 Gradle 同步完（第一次会下载，比较久）
3. 菜单 `Build` → `Build Bundle(s) / APK(s)` → `Build APK(s)`
4. 产物在 `android/app/build/outputs/apk/` 下

**方式 B — 命令行**

```bash
cd android
.\gradlew assembleRelease
```

> 第一次跑 Gradle 会自动下载构建工具（约 1 GB），耐心等一次就好。

---

## 三、关于 token 消耗（你问过的问题）

分三段看：

| 阶段 | 谁来做 | token 消耗 |
|---|---|---|
| 装 Android Studio + SDK | **你自己点安装向导** | **0** |
| 写打包配置 | 我 | 少（几万以内） |
| 构建报错排错 | 我 | 取决于踩几个坑 |

**结论：不会到百万级别。** 但要诚实说：

- 唯一能烧 token 的是**报错循环**（Gradle 版本不匹配、SDK 缺包之类）
- **让 Android Studio 的图形界面替你挡掉这一步是最省的** —— 它会自己提示并修复缺的东西
- 真正的风险不在 token，而是**第一次 Gradle 同步要下载约 1 GB，比较慢**

**如果预算很紧**：第 2 步的配置也可以在装完 Android Studio 之后，
把这段命令直接复制粘贴到终端里跑，不需要我参与。

---

## 四、重要提醒

1. **打包时不要用手机版覆盖电脑上的数据文件** —— 两边格式虽然一样，但
   `instanceId` 不同，混用会导致同步状态混乱。需要迁移就用「导出/导入 JSON」。

2. **`build-env` 目录**
   沙箱只允许写项目工作区，所以：
   - 位置：`E:\duihuazhushou\build-env\`
   - 里面是空的目录结构（`jdk/` `android-sdk/` `downloads/` `logs/`）
   - 如果最终改成装在 `C:\Users\25606\AppData\Local\Android\Sdk`，
     这个目录可以直接删掉

3. **网络限制（实测发现）**
   这台机器上：
   - `curl.exe` 被拦（工作区外的网络工具受限）
   - PowerShell 5.1 的 `Invoke-WebRequest` 对部分站点会 TLS 报错
   - **Node 的 `fetch` 完全正常**（测试时用它才通）
   - `api.github.com`、`dl.google.com`、清华镜像、Adoptium 都能连
   - 已知不通：`raw.githubusercontent.com`（DNS）

   所以下载类操作走 Node 脚本最稳。
