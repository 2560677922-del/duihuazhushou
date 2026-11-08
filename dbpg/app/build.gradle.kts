import java.io.File
import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
}

android {
    namespace = "com.dbpg.duihuazhushou"
    // compileSdk / targetSdk 必须等于本机 SDK 里实际装了的平台版本。
    // 这台机器只有 platforms/android-37.0，所以这里是 37；换机器就同步改。
    compileSdk = 37
    // 显式指定用本机装好的 build-tools，免得 AGP 想去下载默认版本。
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.dbpg.duihuazhushou"
        minSdk = 24      // Android 7.0 起；图标用自适应图标，24~25 走 PNG 兜底
        targetSdk = 37
        versionCode = 8
        versionName = "1.1.1"
    }

    // -----------------------------------------------------------------------
    // 统一签名：把本机 debug keystore 收进仓库（dbpg/app/keystore/debug.keystore），
    // 这样任何机器（云电脑/本机/以后换机）clone 后用同一签名打包，
    // 手机上已装的 APK 才能覆盖安装、不会因签名不一致丢失数据。
    //
    // 这是 Android 默认 debug keystore（store/key 密码均为公开默认值 android，
    // alias 为 androiddebugkey），泄露风险低；请勿用此法存放 release 私钥。
    // 仓库保持私有即可。
    // -----------------------------------------------------------------------
    signingConfigs {
        getByName("debug") {
            storeFile = file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            // 先不开混淆：APK 里绝大部分体积是 Web 资源，混淆 Java 层没有收益。
            // release 版要签名才能上架/分发，本项目先只用 debug 包自测。
            isMinifyEnabled = false
            isDebuggable = false
        }
    }

    compileOptions {
        // JDK 17 是 AGP 9 的基线；本机用 Android Studio 自带的 JBR 25 编译。
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // 用 .add() 而不是 += setOf()：excludes 是 SetProperty<String>，
            // Gradle 的 += 操作符只接收单个元素。
            excludes.add("META-INF/*")
        }
    }
}

// 原生聊天界面复用 :stcore（SillyTavern 兼容引擎：正则/变量/宏/思维链/图片标记），
// 两边渲染走同一套转换逻辑，保证原生端看到的与网页一致。
dependencies {
    implementation(project(":stcore"))

    // 原生聊天列表：RecyclerView（大量消息时只复用可见项，滑动顺滑、内存可控）
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // 原生生图网络层：OkHttp（长超时 / HTTP+HTTPS / 局域网 / 自定义中转站，天然无跨域）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
}

// ---------------------------------------------------------------------------
// Web 资源同步
//
// 上一级目录（duihuazhushou/）是 Vue + Vite 工程，`npm run build` 之后产物在
// ../dist。这里在每次构建前把它复制进 assets/www，让 APK 自包含、可离线运行：
//
//   syncWeb      ../dist/**                  -> assets/www/
//   syncWeb      assets/vendor/**            -> assets/www/   （原生桥接垫片）
//   patchWebIndex 往 assets/www/index.html 的 <head> 里注入 import map
//
// 两步 Sync 合并成一个任务，避免"vendor 先同步、Sync 又把 dist 覆盖掉"的顺序坑。
// 全部挂在 preBuild 上，Android Studio 点 Run / Sync 都会自动带上。
//
// 注意：这里只读 ../dist，不改动 Web 工程里任何文件。
// ---------------------------------------------------------------------------

// 只有一套产物：../dist（引擎包，入口 native.html，无界面）。
// 网页界面已移除，`npm run build` 只构建引擎。
val distDir: File = rootProject.file("../dist")
val wwwDir: File = file("src/main/assets/www")
val vendorDir: File = file("src/main/assets/vendor")

val syncWeb = tasks.register("syncWeb", Sync::class) {
    group = "web"
    description = "把 ../dist 和原生桥接垫片同步到 app/src/main/assets/www"
    from(distDir)
    from(vendorDir)
    into(wwwDir)
}

val patchWebIndex = tasks.register("patchWebIndex") {
    group = "web"
    description = "给 assets/www/index.html 注入原生桥接 import map"
    dependsOn(syncWeb)

    doLast {
        // 引擎入口是 native.html（无界面）。index.html 是网页版，不进 APK。
        val index = File(wwwDir, "native.html")
        if (!index.exists()) {
            error("找不到 " + distDir + "/native.html\n" +
                  "        请先在上一级目录执行：npm run build\n" +
                  "        （或者直接双击 dbpg/build-apk.cmd 一键完成）")
        }
        val shim = File(vendorDir, "capacitor-compat.js")
        if (!shim.exists()) {
            error("缺少原生桥接垫片：" + shim.path)
        }
        if (!File(wwwDir, "capacitor-compat.js").exists()) {
            error("同步异常：assets/www 里没有 capacitor-compat.js")
        }

        val html = index.readText()
        if (html.contains("capacitor-compat.js")) {
            logger.lifecycle("[web] native.html 已含原生桥接，跳过注入")
            return@doLast
        }

        // import map 必须出现在任何 module 脚本之前，所以插在 <head> 之后第一行。
        // 1) 把裸导入 "@capacitor/filesystem" 映射到本地垫片
        // 2) 以 module 脚本加载垫片（垫片本身有 export，不能用 classic script 加载），
        //    它排在应用主模块之前，document 顺序执行保证 window.Capacitor 先就位
        val inject = """<script type="importmap">{"imports":{"@capacitor/filesystem":"/capacitor-compat.js"}}</script><script type="module" src="/capacitor-compat.js"></script>"""
        // 不用 Regex.replaceFirst(...)：在 Gradle 9.8 的 Kotlin DSL 环境里那个重载
        // 会被解析成 (CharSequence, String) 导致编译失败，所以直接用 find + 字符串拼接。
        val headOpen = Regex("<head[^>]*>").find(html)
        if (headOpen == null) {
            error("没有在 native.html 里找到 <head>，无法注入 import map")
        }
        val cut = headOpen.range.last + 1
        val patched = html.substring(0, cut) + "\n" + inject + html.substring(cut)
        index.writeText(patched)
        logger.lifecycle("[web] 已注入原生桥接垫片 -> assets/www/native.html")
    }
}

tasks.named("preBuild") {
    dependsOn(patchWebIndex)
}
