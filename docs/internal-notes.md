# 内网专属说明（此文件不进入对外 PR）

> 本文件只在内网 GitLab（origin）维护，同步到 upstream-pr 分支时会被脚本剔除。
> 通用接入说明见根目录 README.md / README-CN.md。

## 团队接入

### 1. 安装 Studio 插件（每个 AS 装一次）

用浏览器打开下面的地址下载 zip（项目为 Public，支持匿名下载）：

```
http://192.168.5.254/api/v4/projects/513/packages/generic/compose-code-locator-studio-plugin/0.1.6/compose-code-locator-0.1.6.zip
```

在 Android Studio 里通过 Settings → Plugins → ⚙ → **Install Plugin from Disk** 安装，安装后重启 AS。
也可从内网 Release 页面下载：`http://192.168.5.254/zhangjunjie/gradle-plugin-compose-code-locator/-/releases`。

### 2. 打开工程，零配置生效

**build.gradle、settings.gradle 不需要任何改动。** 用装有插件的 AS 打开 Android 工程后，插件自动完成接入（细节见 README「零配置接入」章节）。

### 从旧版手动配置迁移

删除 `app/build.gradle.kts` 里旧接入的全部内容（见下方附录 A 反向操作）：

- 整段 `buildscript { ... }`（含 locator 的 classpath 与内网 maven 仓库）
- `if (isDebugLocatorBuild) { apply(...) }` 附近的判断与 apply 代码
- `app/src/debug/AndroidManifest.xml` 里的 `overrideLibrary` 行（若该文件只为 locator 而建可整文件删除）

init script 对已手动 apply 的情况幂等跳过，忘删也不会冲突。

### 日常使用

1. 在设备上运行 debug 包（运行时随 debug 包自启，无需额外操作）
2. 在 AS 右侧打开 **Compose Code Locator** 工具窗口
3. 选择设备并点击 **抓取**，然后点击截图中的任意元素
4. 在右侧候选列表中选择目标，双击跳转源码

注意：
- clean 后重新 build，下次点选会自动加载新索引；点"刷新索引"按钮可手动强制刷新，这两种情况都不需要重启 AS
- 使用 jugg 等增量构建工具时无需额外配置；若仍出现 "app APK and IDE project are likely out of sync"，跑一次 `./gradlew :app:installDebug` 即可重新对齐
- 候选列表里的 DecorView、LayoutNode、FillElement 等是框架/库代码节点，本身没有 sourceId，点到这些无法跳转属正常现象，跳转请选带 `文件名.kt:行号` 标签的候选

## 附录 A：旧版手动配置（仅迁移对照与兜底用）

0.1.5 及之前的接入方式如下。依赖内网 GitLab Package Registry（`locator-gradle-plugin:0.1.2` 等 Maven 产物照常发布）。

```kotlin
// Compose Code Locator 按需加载：只有构建 debug 变体（assemble/install/bundle/compile *Debug）时才启用，
// 正式包打包、IDE 同步均不加载插件、不解析产物仓库，项目搬到无外网环境可正常出包
buildscript {
    repositories {
        google()
        mavenCentral()
        // Compose Code Locator 产物：内网 GitLab Package Registry，放最后避免拖慢官方依赖解析
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

首次克隆后需执行一次 `git config core.hooksPath .githooks` 启用提交校验。

```bash
# 0. 版本号 +1：Maven 三件套共用一个版本号，Studio 插件独立版本号

# 1. 发布全部 Maven 产物到本地 staging 目录
./gradlew :locator-runtime:publish :locator-runtime-android:publish \
  :locator-compiler-plugin:publishMavenPublicationToComposeLocatorStagingRepository \
  :locator-gradle-plugin:publish

# 2. 打 Studio 插件 zip（编译对本机真实 AS 平台；路径不在常见位置时用
#    -Pcodelocator.studio.home=<AS 安装目录> 或全局 gradle.properties 指定）
./gradlew :studio-plugin:buildStudioPluginZip

# 3. 上传全部产物到内网 GitLab Package Registry（Maven 产物 + Studio 插件 zip，
#    认证读仓库根目录 .deployToken，已 gitignore，团队成员匿名即可下载）
bash publish-gitlab-registry.sh

# 4. 提交并推送 main，随后推 tag 触发双端 Release（GitHub Actions + 内网 GitLab CI）：
git add -A && git commit -m "chore(release): 发布 x.y.z" && git push origin main
git tag studio-v<studio插件版本> && git push origin studio-v<studio插件版本> fork studio-v<studio插件版本>

# 备选：产物也可推到 maven 分支（git worktree 方式，不影响工作区）
bash publish-maven-branch.sh origin
```

### 分支结构

- `main` — 源码（内网 remote origin：`git@192.168.5.254:zhangjunjie/gradle-plugin-compose-code-locator.git`）
- `maven` — 产物备份分支（根目录即 Maven 仓库），团队分发以内网 Package Registry 为主
- `upstream-pr` — 对外 PR 分支（推 GitHub fork），由 `sync-upstream-pr.sh` 从 main 自动同步并剔除本文件等内网设施

### 对外同步流程

```bash
bash sync-upstream-pr.sh   # merge main → 剔除内网文件 → 推 fork 的 upstream-pr 分支
```

之后在 GitHub 上从 `junjie-z666/compose-code-locator` 的 `upstream-pr` 分支向 `nianzixin/compose-code-locator` 的 main 建 PR（中文描述）。

## 排障

| 现象 | 排查 |
|---|---|
| 状态栏提示 "Studio index not found/empty" | 先 build 一次 debug 包，再点"刷新索引"；仍不行就重启 AS |
| 点选提示 "runtime did not provide sourceId" | 设备上装的不是本插件构建的 debug 包，重装 |
| 打开工程报 `NoSuchMethodError`（如 `executeOnPooledThread`） | Studio 插件用 stubs 编译，平台 API 签名可能随 AS 版本漂移；改用等价的普通 JDK API（如直接起 `Thread`），不要依赖仅从 stubs 推断的平台方法。规则与历史教训见 `docs/release-engineering.md` 的 "Studio Plugin API Compatibility" 一节 |
| 窗口空白/打不开 | 看 `idea.log` grep codelocator |
| 设备连不上 | `adb forward tcp:49391 tcp:49391` 后 `curl http://127.0.0.1:49391/health` 验证运行时存活 |
