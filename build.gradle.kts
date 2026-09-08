// 项目级 build.gradle.kts
// 先试 AGP 8.13.x（8.x 最新，配 Gradle 8.13）；若 compileSdk 37 仍不支持则升级 AGP 9 + Gradle 9。
plugins {
    id("com.android.application") version "8.13.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
