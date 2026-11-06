# Compose Code Locator（内部适配版）

Compose 布局定位工具：在 Android Studio 插件里截取设备屏幕，点选任意 Compose 节点，直接跳转到对应的 Kotlin 源码行。

基于 [nianzixin/compose-code-locator](https://github.com/nianzixin/compose-code-locator) 适配：Kotlin 2.4、Gradle 9、Compose 1.12、Android Studio 2026.2。

## 组成部分

| 模块 | 作用 | 版本 |
|---|---|---|
| `team-compose-locator`（gradle 插件） | 接入项目构建，自动注入编译器插件 | 0.1.2 |
| `locator-compiler-plugin` | Kotlin 编译器插件，给 Compose 调用注入 sourceId | 0.1.2 |
| `locator-runtime` | 运行时核心 | 0.1.2 |
| `locator-runtime-android` | debug 包内运行时，采集节点并通过 HTTP 暴露 | 0.1.2 |
| `studio-plugin` | Android Studio 插件，截图点选跳转源码 | 0.1.5 |

## 团队成员接入（每个项目一次性配置）

### 1. 安装 Studio 插件（每个 AS 装一次）

不用找发版人要文件，直接浏览器打开下面的地址下载 zip（项目 Public，匿名可下）：

```
http://192.168.5.254/api/v4/projects/513/packages/generic/compose-code-locator-studio-plugin/0.1.5/compose-code-locator-0.1.5.zip
```

各人在 Android Studio 里 Settings → Plugins → ⚙ → **Install Plugin from Disk** 安装，装完重启 AS。
发版脚本会自动把 Studio 插件 zip 传到上面的地址，升级版本后换 URL 里的版本号即可。

### 2. 配置项目

产物通过 GitLab Package Registry 分发。
`app/build.gradle.kts`（整段拷到文件顶部，plugins 块之前）：

```kotlin
// Compose Code Locator 按需加载：只有构建 debug 变体（assemble/install/bundle *Debug）时才启用，
// 正式包打包、IDE 同步均不加载插件、不解析产物仓库，项目搬到无外网环境可正常出包
buildscript {
    repositories {
        google()
        mavenCentral()
        // Compose Code Locator 产物：GitLab Package Registry，放最后避免拖慢官方依赖解析
        maven {
            url = uri("http://192.168.5.254/api/v4/projects/513/packages/maven")
            isAllowInsecureProtocol = true
        }
    }
    val debugBuild = gradle.startParameter.taskNames
        .map { it.substringAfterLast(':') }
        .any { (it.startsWith("assemble") || it.startsWith("install") || it.startsWith("bundle")) && it.endsWith("Debug") }
    if (debugBuild) {
        dependencies {
            classpath("io.github.nianzixin:locator-gradle-plugin:0.1.2")
        }
    }
}

plugins {
    // 原有的插件声明保持不变，不要在这里声明 locator 插件
}

val isDebugLocatorBuild = gradle.startParameter.taskNames
    .map { it.substringAfterLast(':') }
    .any { (it.startsWith("assemble") || it.startsWith("install") || it.startsWith("bundle")) && it.endsWith("Debug") }
if (isDebugLocatorBuild) {
    apply(plugin = "io.github.nianzixin.team-compose-locator")
}
```

应用后插件自动完成三件事，无需手动配置：给 debug 构建注入编译器插件、
自动添加 runtime 依赖（`debugImplementation`）、生成 Studio 索引。
插件 jar 与 AGP 在同一脚本 classpath，无类加载器冲突。

升级版本：发版人发布新版本后，把 classpath 那行的 `0.1.2` 改成新版本号即可。

`app/src/debug/AndroidManifest.xml`（runtime 库 minSdk 26，项目 minSdk 低于 26 时要在 debug manifest 里加这一行）：

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
- clean 后重新 build，下次点选会自动加载新索引；点“刷新索引”按钮可手动强制刷新，这两种情况都不需要重启 AS

## 发版流程（维护者）

前置：JDK 17（系统默认高版本 JDK 会导致 Kotlin 编译失败；路径按本机 JDK 17 实际位置调整）。

```bash
export JAVA_HOME="H:/Cache/android/gradle/jdks/eclipse_adoptium-17-amd64-windows.2"

# 0. 版本号 +1：Maven 三件套共用一个版本号，Studio 插件独立版本号（规则见本节末尾），
#    改动分布在各模块 build.gradle.kts 与 studio-plugin 的 pluginVersion

# 1. 发布全部 Maven 产物到本地 staging 目录
./gradlew :locator-runtime:publish :locator-runtime-android:publish \
  :locator-compiler-plugin:publishMavenPublicationToComposeLocatorStagingRepository \
  :locator-gradle-plugin:publish

# 2. 打 Studio 插件 zip
./gradlew :studio-plugin:buildPlugin

# 3. 上传全部产物到 GitLab Package Registry（Maven 产物 + Studio 插件 zip，
#    认证读仓库根目录 .deployToken，已 gitignore，团队成员匿名即可下载）
bash publish-gitlab-registry.sh

# 4. 提交并推送 main
git add -A && git commit -m "chore(release): 发布 x.y.z" && git push internal main

# 备选：产物也可推到 maven 分支（git worktree 方式，不影响工作区）
bash publish-maven-branch.sh internal
```

分支结构：

- `main` — 源码（`internal` remote：`git@192.168.5.254:zhangjunjie/gradle-plugin-compose-code-locator.git`）
- `maven` — 产物备份分支（根目录即 Maven 仓库），团队分发以 Package Registry 为主

版本号规则：上游版本 +1。Maven 三件套统一一个版本号，Studio 插件独立版本号；改代码发新版时相应 +1。

## 排障

| 现象 | 排查 |
|---|---|
| 状态栏提示 “Studio index not found/empty” | 先 build 一次 debug 包，再点“刷新索引”；仍不行就重启 AS |
| 点选提示 “runtime did not provide sourceId” | 设备上装的不是本插件构建的 debug 包，重装 |
| 窗口空白/打不开 | 看 `idea.log`（`AppData/Local/Google/AndroidStudio2026.2.1/log`）grep codelocator |
| 设备连不上 | `adb forward tcp:49391 tcp:49391` 后 `curl http://127.0.0.1:49391/health` 验证运行时存活 |

## 仓库目录

```
locator-gradle-plugin/      Gradle 插件（构建期注入 compiler 插件、生成 Studio 索引）
locator-compiler-plugin/    Kotlin 编译器插件（IR 注入 sourceId）
locator-runtime/            运行时核心
locator-runtime-android/    Android 运行时（HTTP 服务、节点采集）
studio-plugin/              Android Studio 插件（截图、点选、跳转）
publish-gitlab-registry.sh  发布脚本：上传 Maven 产物 + Studio 插件 zip 到 Package Registry
publish-maven-branch.sh     maven 分支发布脚本
```
