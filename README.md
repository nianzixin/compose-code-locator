# Compose Code Locator（内部适配版）

Compose 布局定位工具：在 Android Studio 插件里截取设备屏幕，点选任意 Compose 节点，直接跳转到对应的 Kotlin 源码行。

基于 [nianzixin/compose-code-locator](https://github.com/nianzixin/compose-code-locator) 适配：Kotlin 2.4、Gradle 9、Compose 1.12、Android Studio 2026.2。

## 组成部分

| 模块 | 作用 | 版本 |
|---|---|---|
| `team-compose-locator`（gradle 插件） | 接入项目构建，自动注入编译器插件 | 0.1.2 |
| `locator-compiler-plugin` | Kotlin 编译器插件，给 Compose 调用注入 sourceId | 0.1.2 |
| `locator-runtime-android` | debug 包内运行时，采集节点并通过 HTTP 暴露 | 0.1.2 |
| `studio-plugin` | Android Studio 插件，截图点选跳转源码 | 0.1.5 |

## 团队成员接入（每个项目一次性配置）

### 1. 安装 Studio 插件（每个 AS 装一次）

拿 `studio-plugin/build/distributions/compose-code-locator-0.1.5.zip`（发版人从 GitLab Releases 或构建产物获取），
Android Studio → Settings → Plugins → ⚙ → **Install Plugin from Disk**，装完重启 AS。

### 2. 配置项目

`settings.gradle.kts`：

```kotlin
pluginManagement {
    repositories {
        maven("http://192.168.5.254/zhangjunjie/gradle-plugin-compose-code-locator.git#maven")
    }
}
dependencyResolutionManagement {
    repositories {
        maven("http://192.168.5.254/zhangjunjie/gradle-plugin-compose-code-locator.git#maven")
    }
}
```

`app/build.gradle.kts`：

```kotlin
plugins {
    id("io.github.nianzixin.team-compose-locator") version "0.1.2"
}

composeLocator {
    compilerPluginArtifact = "io.github.nianzixin:locator-compiler-plugin:0.1.2"
}

dependencies {
    debugImplementation("io.github.nianzixin:locator-runtime-android:0.1.2")
}
```

`app/src/debug/AndroidManifest.xml`（runtime 库 minSdk 26，项目 minSdk 低于 26 时需要）：

```xml
<manifest xmlns:tools="http://schemas.android.com/tools">
    <uses-sdk tools:overrideLibrary="dev.codelocator.runtime.android" />
</manifest>
```

### 3. 日常使用

1. 跑 debug 包到设备（运行时随 debug 包自启，无需额外操作）
2. AS 右侧打开 **Compose Code Locator** 工具窗口
3. 选中设备，点 **抓取**，点击截图上的任意元素
4. 右侧候选列表选目标，双击跳转源码

注意：
- clean 后重新 build，下次点选自动加载新索引（有"刷新索引"按钮可手动强制刷新），**无需重启 AS**
- 只有 debug 构建带定位能力，release 不受影响

## 发版流程（维护者）

前置：JDK 17（系统默认高版本 JDK 会导致 Kotlin 编译失败）。

```bash
export JAVA_HOME="H:/Cache/android/gradle/jdks/eclipse_adoptium-17-amd64-windows.2"

# 1. 发布全部 Maven 产物到本地 staging 目录
./gradlew :locator-runtime:publish :locator-runtime-android:publish \
  :locator-compiler-plugin:publishMavenPublicationToComposeLocatorStagingRepository \
  :locator-gradle-plugin:publish

# 2. 打 Studio 插件 zip
./gradlew :studio-plugin:buildStudioPluginZip

# 3. 把 Maven 产物推到 maven 分支（脚本会先清空工作区，本脚本有未提交改动时勿运行）
bash publish-maven-branch.sh internal
```

分支结构：

- `main` — 源码（`internal` remote：`git@192.168.5.254:zhangjunjie/gradle-plugin-compose-code-locator.git`）
- `maven` — Maven 产物，根目录即 Maven 仓库，团队通过 git URL 直接引用

版本号规则：上游版本 +1。Maven 三件套统一一个版本号，Studio 插件独立版本号；改代码发新版时相应 +1。

## 排障

| 现象 | 排查 |
|---|---|
| 状态栏 "Studio index not found/empty" | 先 build 一次 debug 包；再点"刷新索引"；仍不行重启 AS |
| 点选提示 "runtime did not provide sourceId" | 装的不是本插件构建的 debug 包，重装 |
| 窗口空白/打不开 | 看 `idea.log`（`AppData/Local/Google/AndroidStudio2026.2.1/log`）grep codelocator |
| 设备连不上 | `adb forward tcp:49391 tcp:49391` 后 `curl http://127.0.0.1:49391/health` 验证运行时存活 |

## 仓库目录

```
locator-gradle-plugin/      Gradle 插件（构建期注入 compiler 插件、生成 Studio 索引）
locator-compiler-plugin/    Kotlin 编译器插件（IR 注入 sourceId）
locator-runtime/            运行时核心
locator-runtime-android/    Android 运行时（HTTP 服务、节点采集）
studio-plugin/              Android Studio 插件（截图、点选、跳转）
publish-maven-branch.sh     maven 分支发布脚本
```
