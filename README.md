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
| `studio-plugin` | Android Studio 插件，截图点选跳转源码（内嵌 Gradle 侧构建产物） | 0.1.6 |

## 团队成员接入（每个项目一次性配置）

### 1. 安装 Studio 插件（每个 AS 装一次）

用浏览器打开下面的地址下载 zip（项目为 Public，支持匿名下载）：

```
http://192.168.5.254/api/v4/projects/513/packages/generic/compose-code-locator-studio-plugin/0.1.6/compose-code-locator-0.1.6.zip
```

在 Android Studio 里通过 Settings → Plugins → ⚙ → **Install Plugin from Disk** 安装，安装后重启 AS。
也可从 Release 页面下载（GitHub/GitLab 推送 `studio-v*` tag 自动发布双端 Release）：
`http://192.168.5.254/zhangjunjie/gradle-plugin-compose-code-locator/-/releases` 或
`https://github.com/junjie-z666/compose-code-locator/releases`。

### 2. 打开工程，零配置生效

**build.gradle、settings.gradle 不需要任何改动。** 用装有插件的 AS 打开 Android 工程后，插件自动完成：

1. 把构建期产物（Gradle 插件 bootstrap jar、Kotlin 编译器插件 jar、runtime AAR）释放到工程的
   `.gradle/compose-locator/`（内网、离线均可用，不依赖任何远程仓库）
2. 写全局 init script 到 `$GRADLE_USER_HOME/init.d/compose-code-locator.init.gradle.kts`
   （GRADLE_USER_HOME 环境变量优先，未设置时为 `~/.gradle`；Gradle 只从该目录加载 init script）
3. debug 构建（assemble/install/bundle/compile *Debug，判断口径与旧手动配置一致，jugg 增量构建兼容）
   自动注入 Gradle 插件：注入编译器插件、添加 runtime 文件依赖（`debugImplementation`）、生成 Studio 索引
4. 项目 minSdk < 26 时自动在 `src/debug/AndroidManifest.xml`（不存在才创建）写入
   `<uses-sdk tools:overrideLibrary="dev.codelocator.runtime.android" />`

关于全局 init script 的生效范围：`~/.gradle/init.d/` 下的 init script 虽然对所有 Gradle 构建可见，
但**只对"被装了插件的 AS 打开过的工程"生效**——它每次构建先检查当前工程根目录是否有
`.gradle/compose-locator/enable` 标记（AS 插件打开工程时写入），无标记的工程（没装插件的同事、
CI、无关项目）构建路径与以前完全一致，不会有任何额外行为。

无标记文件的工程（CI、其他项目）不受 init script 影响；Studio 插件升级后重新打开工程即自动更新
释放产物与 init script。删除工程的 `.gradle/compose-locator/` 目录即可停用单项目注入；
卸载 Studio 插件后残留的 `~/.gradle/init.d/compose-code-locator.init.gradle.kts` 因无 marker 与产物文件而自动失效，
介意可直接删除该文件。

### 从旧版手动配置迁移

升级到 0.1.6+ 后，删除 `app/build.gradle.kts` 里旧接入的全部内容（见附录 A 反向操作）：

- 整段 `buildscript { ... }`（含 locator 的 classpath 与 maven 仓库）
- `if (isDebugLocatorBuild) { apply(...) }` 附近的判断与 apply 代码
- `app/src/debug/AndroidManifest.xml` 里的 `overrideLibrary` 行（若该文件只为 locator 而建可整文件删除）

init script 对已手动 apply 的情况幂等跳过，忘删也不会冲突。

### 3. 日常使用

1. 在设备上运行 debug 包（运行时随 debug 包自启，无需额外操作）
2. 在 AS 右侧打开 **Compose Code Locator** 工具窗口
3. 选择设备并点击 **抓取**，然后点击截图中的任意元素
4. 在右侧候选列表中选择目标，双击跳转源码

注意：
- clean 后重新 build，下次点选会自动加载新索引；点“刷新索引”按钮可手动强制刷新，这两种情况都不需要重启 AS
- 使用 jugg 等增量构建工具时无需额外配置：init script 内置的任务判断已包含 compile 任务（判断口径见第 2 节）；若仍出现 "app APK and IDE project are likely out of sync"，跑一次 `./gradlew :app:installDebug` 即可重新对齐
- 候选列表里的 DecorView、LayoutNode、FillElement 等是框架/库代码节点，本身没有 sourceId，点到这些无法跳转属正常现象，跳转请选带 `文件名.kt:行号` 标签的候选

## 附录 A：旧版手动配置（仅迁移对照与兜底用）

0.1.5 及之前的接入方式如下。**新装插件不需要配置这些**；留作：(a) 迁移时对照该删哪些代码；
(b) 无法装 Studio 插件的环境（如纯 CI）兜底——此路径依赖 GitLab Package Registry（`locator-gradle-plugin:0.1.2` 等 Maven 产物仍照常发布）。

```kotlin
// Compose Code Locator 按需加载：只有构建 debug 变体（assemble/install/bundle/compile *Debug）时才启用，
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
    // 判断条件必须包含 compile 任务：jugg 等增量构建工具内部直接调 compileDebugKotlin，
    // 只匹配 assemble/install/bundle 会导致增量编译出的 APK 与 Studio 索引不同步，点击元素无法跳转
    val debugBuild = gradle.startParameter.taskNames
        .map { it.substringAfterLast(':') }
        .any {
            (it.startsWith("assemble") || it.startsWith("install") || it.startsWith("bundle") || it.startsWith("compile")) &&
                (it.endsWith("Debug") || it.startsWith("compileDebug"))
        }
    if (debugBuild) {
        dependencies {
            classpath("io.github.nianzixin:locator-gradle-plugin:0.1.2")
        }
    }
}

plugins {
    // 原有的插件声明保持不变，不要在这里声明 locator 插件
}

// 与上方 buildscript 的 debugBuild 判断保持同一口径，避免插件加载与 classpath 解析不一致
val isDebugLocatorBuild = gradle.startParameter.taskNames
    .map { it.substringAfterLast(':') }
    .any {
        (it.startsWith("assemble") || it.startsWith("install") || it.startsWith("bundle") || it.startsWith("compile")) &&
            (it.endsWith("Debug") || it.startsWith("compileDebug"))
    }
if (isDebugLocatorBuild) {
    apply(plugin = "io.github.nianzixin.team-compose-locator")
}
```

runtime 库 minSdk 26，项目 minSdk 低于 26 时需在 `app/src/debug/AndroidManifest.xml` 加：

```xml
<manifest xmlns:tools="http://schemas.android.com/tools">
    <uses-sdk tools:overrideLibrary="dev.codelocator.runtime.android" />
</manifest>
```

## 发版流程（维护者）

首次克隆后需执行一次 `git config core.hooksPath .githooks` 启用提交校验：
若 README 里的版本号（接入 classpath、表格、Studio 插件下载 URL）与代码不一致，提交会被拒绝。

```bash
# 0. 版本号 +1：Maven 三件套共用一个版本号，Studio 插件独立版本号（规则见本节末尾），
#    改动分布在各模块 build.gradle.kts 与 studio-plugin 的 pluginVersion

# 1. 发布全部 Maven 产物到本地 staging 目录
./gradlew :locator-runtime:publish :locator-runtime-android:publish \
  :locator-compiler-plugin:publishMavenPublicationToComposeLocatorStagingRepository \
  :locator-gradle-plugin:publish

# 2. 打 Studio 插件 zip（编译对本机真实 AS 平台；路径不在常见位置时用
#    -Pcodelocator.studio.home=<AS 安装目录> 或全局 gradle.properties 指定）。
#    该任务同时把 Gradle 侧三产物（bootstrap jar / compiler jar / runtime AAR）打进 zip，
#    产物版本变化时记得重新构建再打包
./gradlew :studio-plugin:buildStudioPluginZip

# 3. 上传全部产物到 GitLab Package Registry（Maven 产物 + Studio 插件 zip，
#    认证读仓库根目录 .deployToken，已 gitignore，团队成员匿名即可下载）
bash publish-gitlab-registry.sh

# 4. 提交并推送 main，随后推 tag 触发双端 Release（GitHub Actions + GitLab CI）：
git add -A && git commit -m "chore(release): 发布 x.y.z" && git push internal main
git tag studio-v<studio插件版本> && git push internal studio-v<studio插件版本> fork studio-v<studio插件版本>
#    双端各自构建 zip 并发布 Release 页面（内网 GitLab + GitHub），zip 也会随步骤 3 进 Package Registry

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
| 打开工程报 `NoSuchMethodError`（如 `executeOnPooledThread`） | Studio 插件用 stubs 编译，平台 API 签名可能随 AS 版本漂移；改用等价的普通 JDK API（如直接起 `Thread`），不要依赖仅从 stubs 推断的平台方法。规则与历史教训见 `docs/release-engineering.md` 的 "Studio Plugin API Compatibility" 一节 |
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
