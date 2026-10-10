// 根构建脚本：只声明 Android 插件版本，具体配置都在 app/ 里。
// 故意不引入任何第三方库（没有 androidx / material / 网络库），
// 这样 Gradle 同步只需要下载 AGP 本身，失败面最小。
//
// AGP 9.4.1 是当前的稳定版，配 Gradle 9.8.0 使用（见 gradle/wrapper/gradle-wrapper.properties）。
// 这两个版本必须一起动：AGP 9.x 要求 Gradle 9.x，Gradle 9.x 要求 JDK 21+。
plugins {
    id("com.android.application") version "9.4.1" apply false
}
