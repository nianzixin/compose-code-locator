pluginManagement {
    includeBuild("locator-gradle-plugin")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
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
