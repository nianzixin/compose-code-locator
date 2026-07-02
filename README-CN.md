# Compose Code Locator

[English](README.md)

[掘金技术文章：我做了一个 Compose 版 CodeLocator：点击截图直接跳到源码](https://juejin.cn/spost/7657475917389545498)

Compose Code Locator 是一个面向 Jetpack Compose 的源码定位工具，目标是做到类似 CodeLocator 的使用体验：在 Android Studio 插件里点击设备截图上的 UI 元素，直接跳转到对应 Compose 调用点。

它的核心设计是“不侵入业务 UI 代码”：业务侧不需要手写 `.testTag(...)`、`.locatorNode(...)`、根部 collector、Application bridge 或 Activity bridge。源码身份由 Gradle 构建期、Kotlin IR compiler plugin 和 AGP ASM transform 注入；App debug runtime 只暴露紧凑的 `sourceId` 和节点边界，Android Studio 再通过本地索引把 `sourceId` 解析回文件行号。

> 当前版本是工程化 PoC，已覆盖 demo/device/release/performance/consumer smoke 等门禁。正式接入大业务前，建议先在团队 design-system 和真实业务页面中做试点。

## 能解决什么

- 点击 Compose 页面截图中的元素，跳转到对应 Kotlin 源码位置。
- 不依赖文案匹配，支持动态文案、重复固定文案，例如一个页面里多个“确认”按钮。
- 支持普通 Compose 节点、LayoutNode fallback、Dialog、Popup、DropdownMenu、ModalBottomSheet、LazyGrid、NavHost、多 Activity、多 Compose root。
- 支持无 `Modifier` 参数的 wrapper composable，通过 ASM source boundary 作为 fallback。
- 支持大项目：源码映射只保存在本地 Gradle build 输出和 Studio index 中，不塞进 APK asset，不在运行时加载大 JSON。
- release 包保持干净：release APK 不包含 locator runtime、debug server、source catalog 或 compose-locator metadata asset。

## 架构原理

1. Gradle 插件扫描每个模块的 Compose 源码，生成模块本地构建产物。
2. Kotlin IR compiler plugin 给带 `Modifier` 参数的 Compose 调用点注入私有 source identity。
3. 对没有 `Modifier` 参数的 composable 调用，AGP ASM transform 在 debug 构建中注入 source boundary fallback。
4. Android debug runtime 通过 `LocatorInitProvider` 自动启动，跟踪 Activity、Window root 和 Compose root。
5. Studio 插件抓取设备截图，并把点击坐标发送给 App debug runtime 做 hit-test。
6. runtime 返回候选节点、bounds、诊断信息和 `sourceId`，但不返回完整源码路径。
7. Studio 插件用本地 sharded Studio index 把 `sourceId` 解析成 `file:line`，然后跳转源码。

核心原则：

- 业务代码零埋点。
- APK 里不放大体积源码映射。
- 运行时只做按需采集和 hit-test。
- 大索引留在开发机本地，由 Android Studio 插件按需加载。
- release variant 不引入 debug locator 能力。

## 模块组成

- `locator-gradle-plugin`：Gradle 插件，负责源码扫描、compiler plugin 接入、ASM 注入、Studio index 生成、团队一键接入 convention plugin。
- `locator-compiler-plugin`：Kotlin IR 插件，给 Compose 调用点注入私有 source identity。
- `locator-runtime-android`：App debug runtime，自动初始化、采集 Compose window/root/node、提供本地调试协议。
- `locator-runtime`：通用 runtime model、hit-test、JSON protocol。
- `studio-plugin`：Android Studio 插件，负责设备截图、hit-test、候选节点展示、源码跳转。
- `demo-app`：无业务埋点的 Compose demo app。
- `demo-feature`：Android Compose library fixture，用于验证多模块 metadata/index 聚合。

## 快速接入

`0.1.1` 已发布到 Maven Central。业务工程需要保证 `pluginManagement` 能从 Maven Central 解析插件 marker：

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

接入团队 convention plugin：

```kotlin
plugins {
    id("io.github.nianzixin.team-compose-locator") version "0.1.1"
}
```

`0.1.1` 是 Gradle 插件/App debug SDK 的版本。Android Studio Marketplace 插件是独立版本线，当前 Studio 插件版本是 `0.1.4`。

`team-compose-locator` 会自动处理依赖：

```kotlin
// Android application 模块自动添加：
debugImplementation("io.github.nianzixin:locator-runtime-android:0.1.1")

// Android library 模块自动添加：
compileOnly("io.github.nianzixin:locator-runtime-android:0.1.1")
```

如果需要更细粒度控制，也可以只接入底层插件：

```kotlin
plugins {
    id("io.github.nianzixin.compose-locator") version "0.1.1"
}
```

可选配置：

```kotlin
composeLocator {
    sourceDirectories = listOf(
        "src/main/java",
        "src/main/kotlin",
        "src/debug/java",
        "src/debug/kotlin",
    )
    includePackages = listOf("com.example")
}
```

正常 debug 接入不需要改 `Application` 或 `Activity` 代码，runtime 会通过 provider 自动初始化。

## 使用 GitHub Release 静态 Maven

优先使用 Maven Central。GitHub Release 仍保留静态 Maven 仓库，用于内网镜像、离线试用或 CDN 分发：

[compose-code-locator-0.1.1-release.zip](https://github.com/nianzixin/compose-code-locator/releases/tag/v0.1.1)

然后把压缩包里的 `maven/` 放到内网 Maven、官网 CDN 或本地目录，并在业务工程 `settings.gradle.kts` 中配置：

```kotlin
pluginManagement {
    repositories {
        maven("https://your-domain.example/compose-locator/maven")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://your-domain.example/compose-locator/maven")
        google()
        mavenCentral()
    }
}
```

本机验证也可以直接指向解压后的本地目录：

```kotlin
maven("/absolute/path/to/compose-code-locator-0.1.1/maven")
```

## Android Studio 插件

从 JetBrains Marketplace 安装：

1. Android Studio 打开 `Settings | Plugins | Marketplace`。
2. 搜索 `Compose Code Locator`。
3. 安装插件并重启 Android Studio。
4. 打开接入了 Gradle 插件的 Android 工程，运行 debug app，在工具窗口抓取截图并点击元素定位源码。

当前 Marketplace 插件版本为 `0.1.4`，兼容 IntelliJ Platform build range `241-261.*`。

本地开发或离线安装时，可以构建插件 ZIP：

```bash
./gradlew :studio-plugin:buildStudioPluginZip
```

产物位置：

```text
studio-plugin/build/distributions/compose-code-locator-0.1.4.zip
```

从 Android Studio 安装本地 ZIP：

1. Android Studio 打开 `Settings | Plugins`。
2. 选择 `Install Plugin from Disk...`。
3. 选择上面的 `compose-code-locator-0.1.4.zip`。
4. 重启 Android Studio。
5. 打开接入了 Gradle 插件的 Android 工程，运行 debug app，使用工具窗口抓取截图并点击元素定位源码。

## 构建和验证

完整非设备门禁：

```bash
./gradlew verifyCodeLocator
```

连接设备后的端到端验证：

```bash
CODELOCATOR_DEVICE_SERIAL=<serial> ./gradlew verifyCodeLocatorDevice
```

构建 demo app：

```bash
./gradlew :demo-app:assembleDebug
```

生成可分发 release 包：

```bash
./gradlew verifyComposeLocatorReleaseArchive
```

release zip 产物：

```text
build/composeLocator/compose-code-locator-0.1.1-release.zip
```

验证 compiler source 对齐：

```bash
./gradlew verifyComposeCompilerSourceAlignment
```

验证构建效率和 release 边界：

```bash
./gradlew verifyComposeLocatorBuildEfficiency verifyComposeLocatorReleaseBoundary
```

验证性能基线：

```bash
./gradlew verifyComposeLocatorPerformance
```

生成团队 rollout 报告：

```bash
./gradlew generateComposeLocatorRolloutReport
./gradlew generateComposeLocatorRolloutReport -Pcodelocator.rollout.modules=:app,:feature
./gradlew verifyComposeLocatorRolloutReadiness -Pcodelocator.rollout.modules=:app,:feature
```

报告位置：

```text
build/reports/composeLocator/rollout/rollout-report.md
build/reports/composeLocator/rollout/rollout-report.json
```

## 大项目设计

大项目场景下，不能把所有源码映射都塞进一个 APK asset 或运行时 JSON。当前架构采用 source identity + 本地 sharded Studio index：

```text
build/intermediates/composeLocator/studio-index/v1/
  manifest.json
  source-id-index.tsv
  shards/<hex>.jsonl
```

处理方式：

- app module 的 `source-map.json` 只保留在模块本地 build 目录。
- project module 和外部 AAR/JAR 的 locator metadata 只合并进 Studio index。
- APK 运行时只持有紧凑 `sourceId`，不加载完整 source catalog。
- Studio 插件启动时只加载 `source-id-index.tsv`，具体 shard 点击时懒加载，并使用 LRU 缓存。
- metadata 只记录 source identity，例如 `Composable`、`ComposableCallSite`、`ModifierCallSite`，不记录文本/tag 查找表。

外部库如果希望暴露 locator metadata，可在 AAR/JAR 中携带：

```text
META-INF/compose-locator/compose-locator-metadata.json
```

旧的 asset-based source catalog 路径已经移除，不作为最终架构的一部分。

## 已覆盖场景

- text、content description、已有 testTag、role、clickable、bounds 的 Semantics 采集。
- 通过反射遍历 `AndroidComposeView.root` / `LayoutInfo` 的 LayoutNode fallback。
- 动态文案和重复固定文案下的 compiler-injected source marker。
- 无 `Modifier` 参数 wrapper composable 的 ASM source-boundary fallback。
- 多 Activity、多 Compose root。
- Popup / Dialog / DropdownMenu window root 和 top-window hit filtering。
- 嵌套 Dialog + DropdownMenu、ModalBottomSheet、AndroidView mixed-content boundary。
- LazyVerticalGrid、重复 LazyColumn row、NavHost 多页面。
- 两个相同“确认”按钮等重复文案场景，通过 source identity 而不是文案匹配定位。
- deterministic source-backed node id，process-local id 仅作为 fallback。
- Android Studio 插件本地 ZIP 打包。

demo app 中没有添加 `.locatorNode(...)`、`.testTag(...)`、root collector 或 Application bridge。

## 团队推广

团队推广文档：

- [docs/team-rollout.md](docs/team-rollout.md)：接入边界、兼容性、CI 门禁、性能预算、design-system 回归、推广阶段、问题排查。
- [docs/release-engineering.md](docs/release-engineering.md)：Maven 坐标、Studio ZIP staging、release gate、版本规则。
- [docs/public-publishing.md](docs/public-publishing.md)：官网、Maven Central、Gradle Plugin Portal、JetBrains Marketplace 发布准备。
- [docs/compatibility-matrix.json](docs/compatibility-matrix.json)：机器可读兼容性矩阵。

推荐 CI 门禁：

```bash
./gradlew verifyCodeLocator
CODELOCATOR_DEVICE_SERIAL=<serial> ./gradlew verifyCodeLocatorDevice
./gradlew verifyComposeLocatorCiTemplate
./gradlew verifyComposeLocatorReleaseArchive
./gradlew verifyComposeLocatorReleaseConsumer
./gradlew verifyComposeLocatorReleasePackage
./gradlew verifyComposeLocatorWindowRootPolicy
./gradlew verifyComposeLocatorRolloutReadiness -Pcodelocator.rollout.modules=:app,:feature
./gradlew :app:assembleDebug :app:verifyComposeCompilerSourceAlignment
```

## 当前验证基线

当前 workspace 已通过：

- `./gradlew verifyCodeLocator`
- `./gradlew verifyComposeLocatorCiTemplate`
- `./gradlew verifyComposeLocatorReleaseArchive`
- `./gradlew verifyComposeLocatorReleaseConsumer`
- `./gradlew verifyComposeLocatorWindowRootPolicy`
- `./gradlew verifyDemoDevice`
- `./gradlew verifyCodeLocatorDevice`
- `./gradlew verifyComposeLocatorRolloutReadiness`
- `./gradlew verifyComposeLocatorReleasePackage`
- Studio plugin ZIP 结构验证
- release APK boundary 验证
- source identity 跨 app 重启稳定性验证

已做过的私有工程试点：

- 一个私有试点 app 接入后可成功构建。
- sourceId/index 架构更新后生成 1252 条 source-identity-only Studio index entries。
- forced Kotlin compile 后验证 570 个 compiler-injected source IDs。
- debug APK 未包含 legacy compose-locator metadata/source-catalog entries。

性能基线：

- 250 个文件 / 4000 个 composable 的 synthetic scan 约 269 ms。
- metadata encode/decode 约 100 ms。
- 80 个 AAR metadata extraction 约 1315 ms。
- runtime 20k-node / 2000-query hit-test batch 约 122 ms。

## 发布状态

- GitHub 仓库：[nianzixin/compose-code-locator](https://github.com/nianzixin/compose-code-locator)
- GitHub Release：[v0.1.1](https://github.com/nianzixin/compose-code-locator/releases/tag/v0.1.1)
- Maven Central：已发布 `io.github.nianzixin:*:0.1.1`
- Maven group：`io.github.nianzixin`
- Gradle plugin id：`io.github.nianzixin.compose-locator`
- Team convention plugin id：`io.github.nianzixin.team-compose-locator`

当前 release zip 已包含：

- 静态 Maven 仓库
- Android Studio 插件 ZIP
- release manifest
- SHA-256 checksum
- rollout 文档
- public publishing 文档

发布状态：

- Maven Central：已完成
- Gradle Plugin Portal：已发布
- JetBrains Marketplace：已发布；Studio 插件 0.1.4 已审核通过，目标兼容 IntelliJ Platform build range 241-261.*。

Marketplace 更新和发布产物步骤见 [docs/public-publishing.md](docs/public-publishing.md)。

## 后续工作

- 在更大的生产 app 上运行 `generateComposeLocatorRolloutReport -Pcodelocator.rollout.modules=...`，并把报告纳入 CI 归档。
- 为各团队自研 design-system 组件补充项目级回归 fixture。
- 每次放宽 Android Studio 兼容范围前运行 JetBrains Plugin Verifier。
- 将新版 Android Studio 插件 ZIP 上传到 JetBrains Marketplace，支持 IDE 内安装和更新。
- 在可用的 Android Studio SDK 分发环境下，将本地 ZIP task 替换为官方 IntelliJ Platform Gradle Plugin 打包发布链路。

## License

Apache License 2.0
