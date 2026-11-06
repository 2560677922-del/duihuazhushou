plugins {
    id("com.android.library")
}

android {
    namespace = "com.dbpg.stcore"
    // compileSdk 必须与本机已装的 SDK 平台一致（这里是 37）。
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 24
        // consumer 端（:app）的 compileSdk 更高，这里只声明下限。
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 这个模块是纯 Kotlin 的 ST 引擎，没有 Android 专属依赖，
    // 单测跑在 JVM 上（testDebugUnitTest），沙箱里就能验证"与原 web 引擎语义一致"。
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // 单测用 JUnit 4（Android 单元测默认）。
    testImplementation("junit:junit:4.13.2")
}
