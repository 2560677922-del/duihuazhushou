pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // 国内加速：上面三个不通时从这里下
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
    }
}

rootProject.name = "dbpg"
include(":app")
// 纯 Kotlin 的 SillyTavern 兼容引擎（正则/变量/宏/思维链/图片标记）。
// 被 :app 的原生聊天界面复用，也自带 JVM 单测验证与原 web 引擎语义一致。
include(":stcore")
