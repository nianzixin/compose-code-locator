pluginManagement {
    includeBuild("locator-gradle-plugin")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// 允许 Gradle 自动下载缺失版本的 JDK（CI runner 只有任意 JDK，工具链需要 17 时按需拉取）
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "compose-code-locator"

include(":locator-runtime")
include(":locator-runtime-android")
include(":locator-compiler-plugin")
include(":studio-plugin")
include(":demo-app")
include(":demo-feature")
