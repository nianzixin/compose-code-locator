# Compose Code Locator

[Simplified Chinese](README-CN.md)

Compose Code Locator is a source navigation tool for Jetpack Compose. Its goal is to provide a CodeLocator-like workflow: click a UI element in an Android Studio screenshot panel and jump directly to the Compose call site that produced it.

The core design is non-invasive for business UI code. App code does not need `.testTag(...)`, `.locatorNode(...)`, root collectors, an Application bridge, or Activity bridge. Source identity is injected at build time by the Gradle plugin, Kotlin IR compiler plugin, and AGP ASM transform. The debug runtime only exposes compact `sourceId` values and node bounds. Android Studio resolves those `sourceId` values back to `file:line` through a local source index.

> This is an engineered PoC. It already has demo, device, release, performance, and consumer smoke gates, but production rollout should still start with design-system and real-page trials.

## What It Solves

- Click a Compose UI element in a device screenshot and navigate to the corresponding Kotlin source location.
- Avoid text-based matching, so dynamic text and duplicate fixed text such as multiple `Confirm` buttons can still be resolved.
- Cover regular Compose nodes, LayoutNode fallback, Dialog, Popup, DropdownMenu, ModalBottomSheet, LazyGrid, NavHost, multiple activities, and multiple Compose roots.
- Support wrapper composables without a `Modifier` parameter through ASM source-boundary fallback.
- Scale to large projects by keeping source mappings in local Gradle build outputs and Android Studio indexes, not in APK assets or runtime JSON catalogs.
- Keep release builds clean of locator runtime code, debug server permissions, source catalogs, and compose-locator metadata assets.

## Architecture

1. The Gradle plugin scans each module's Compose source and generates module-local build intermediates.
2. The Kotlin IR compiler plugin injects private source identity for Compose call sites that accept `Modifier`.
3. For composables without a `Modifier` parameter, the AGP ASM transform injects debug-only source boundaries as fallback.
4. The Android debug runtime starts automatically through `LocatorInitProvider` and tracks activities, window roots, and Compose roots.
5. The Android Studio plugin captures a screenshot and sends click coordinates to the app debug runtime for hit-testing.
6. The runtime returns candidate nodes with bounds, diagnostics, and optional `sourceId`, but not full source paths.
7. Android Studio resolves `sourceId` through the local sharded Studio index and opens the matching source file and line.

Core principles:

- No business-code markers.
- No large source map assets in APKs.
- Runtime collection and hit-testing are performed on demand.
- Large indexes stay on the developer machine and are loaded lazily by the Studio plugin.
- Release variants do not include debug locator capability.

## Modules

- `locator-gradle-plugin`: Gradle plugin for source scanning, compiler plugin wiring, ASM injection, Studio index generation, and the team convention plugin.
- `locator-compiler-plugin`: Kotlin IR compiler plugin that injects private source identity into Compose call sites.
- `locator-runtime-android`: Android debug runtime with automatic initialization, Compose window/root/node collection, and local debug protocol.
- `locator-runtime`: Shared runtime models, hit-testing, and JSON protocol.
- `studio-plugin`: Android Studio plugin for device screenshot capture, hit-testing, candidate display, and source navigation.
- `demo-app`: Compose demo app with no locator-specific business UI markers.
- `demo-feature`: Android Compose library fixture for multi-module metadata and index aggregation.

## Quick Start

Version `0.1.1` is available from Maven Central. Ensure `pluginManagement` can resolve plugin markers from Maven Central:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

Apply the team convention plugin:

```kotlin
plugins {
    id("io.github.nianzixin.team-compose-locator") version "0.1.1"
}
```

`team-compose-locator` wires dependencies automatically:

```kotlin
// Added automatically to Android application modules:
debugImplementation("io.github.nianzixin:locator-runtime-android:0.1.1")

// Added automatically to Android library modules:
compileOnly("io.github.nianzixin:locator-runtime-android:0.1.1")
```

For lower-level manual control, apply the core plugin directly:

```kotlin
plugins {
    id("io.github.nianzixin.compose-locator") version "0.1.1"
}
```

Optional configuration:

```kotlin
composeLocator {
    sourceDirectories = listOf("src/main/kotlin", "src/debug/kotlin")
    includePackages = listOf("com.example")
}
```

Normal debug integration does not require Application or Activity code changes. The runtime starts automatically through a provider.

## Using The GitHub Release Maven Package

Maven Central is the preferred distribution channel. The GitHub Release still contains a static Maven repository for internal mirrors, offline trials, or CDN-based distribution:

[compose-code-locator-0.1.1-release.zip](https://github.com/nianzixin/compose-code-locator/releases/tag/v0.1.1)

Mirror the `maven/` directory to an internal Maven repository, public CDN, or local directory, then configure `settings.gradle.kts`:

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

For local verification, point Gradle at the unzipped local Maven directory:

```kotlin
maven("/absolute/path/to/compose-code-locator-0.1.1/maven")
```

## Android Studio Plugin

Build the plugin ZIP:

```bash
./gradlew :studio-plugin:buildStudioPluginZip
```

Output:

```text
studio-plugin/build/distributions/compose-code-locator-0.1.4.zip
```

Install it from Android Studio:

1. Open `Settings | Plugins`.
2. Choose `Install Plugin from Disk...`.
3. Select `compose-code-locator-0.1.4.zip`.
4. Restart Android Studio.
5. Open an Android project that has integrated the Gradle plugin, run the debug app, capture a screenshot in the tool window, and click a UI element to navigate to source.

## Build And Verification

Run the full non-device verification suite:

```bash
./gradlew verifyCodeLocator
```

Run the adb-backed end-to-end suite:

```bash
CODELOCATOR_DEVICE_SERIAL=<serial> ./gradlew verifyCodeLocatorDevice
```

Build the demo app:

```bash
./gradlew :demo-app:assembleDebug
```

Build the distributable release package:

```bash
./gradlew verifyComposeLocatorReleaseArchive
```

Release archive:

```text
build/composeLocator/compose-code-locator-0.1.1-release.zip
```

Verify compiler source alignment:

```bash
./gradlew verifyComposeCompilerSourceAlignment
```

Verify build efficiency and release boundaries:

```bash
./gradlew verifyComposeLocatorBuildEfficiency verifyComposeLocatorReleaseBoundary
```

Verify synthetic performance baselines:

```bash
./gradlew verifyComposeLocatorPerformance
```

Generate rollout reports:

```bash
./gradlew generateComposeLocatorRolloutReport
./gradlew generateComposeLocatorRolloutReport -Pcodelocator.rollout.modules=:app,:feature
./gradlew verifyComposeLocatorRolloutReadiness -Pcodelocator.rollout.modules=:app,:feature
```

Report outputs:

```text
build/reports/composeLocator/rollout/rollout-report.md
build/reports/composeLocator/rollout/rollout-report.json
```

## Large-Project Design

Large projects should not put all source mappings into one APK asset or runtime JSON file. This project uses source identity plus a local sharded Studio index:

```text
build/intermediates/composeLocator/studio-index/v1/
  manifest.json
  source-id-index.tsv
  shards/<hex>.jsonl
```

Design details:

- The app module's `source-map.json` remains module-local under `build/`.
- Locator metadata from project modules and external AAR/JAR artifacts is merged only into the Studio index.
- The runtime APK only carries compact `sourceId` values and never loads a full source catalog.
- The Studio plugin loads `source-id-index.tsv` once, then lazily loads shard files with an LRU cache.
- Metadata records source identity only, such as `Composable`, `ComposableCallSite`, and `ModifierCallSite`; it does not build text or tag lookup tables.

External libraries can expose locator metadata by packaging:

```text
META-INF/compose-locator/compose-locator-metadata.json
```

The old asset-based source catalog path has been removed from the final architecture.

## Covered Scenarios

- Semantics capture for text, content description, existing testTag, role, clickability, and bounds.
- LayoutNode fallback through reflective traversal of `AndroidComposeView.root` / `LayoutInfo`.
- Compiler-injected source markers for dynamic text and duplicate fixed text.
- ASM source-boundary fallback for wrapper composables without a `Modifier` parameter.
- Multiple activities and multiple Compose roots.
- Popup, Dialog, and DropdownMenu window roots with top-window hit filtering.
- Nested Dialog plus DropdownMenu, ModalBottomSheet, and AndroidView mixed-content boundaries.
- LazyVerticalGrid, repeated LazyColumn rows, and NavHost multi-page flows.
- Duplicate text such as two `Confirm` buttons, resolved by source identity rather than text matching.
- Deterministic source-backed node ids, with process-local ids used only as fallback.
- Local Android Studio plugin ZIP packaging.

The demo app intentionally does not add `.locatorNode(...)`, `.testTag(...)`, root collectors, or an Application bridge.

## Team Rollout

Team rollout documents:

- [docs/team-rollout.md](docs/team-rollout.md): integration boundaries, compatibility, CI gates, performance budgets, design-system regression, rollout phases, and troubleshooting.
- [docs/release-engineering.md](docs/release-engineering.md): Maven coordinates, Studio ZIP staging, release gates, and versioning rules.
- [docs/public-publishing.md](docs/public-publishing.md): website, Maven Central, Gradle Plugin Portal, and JetBrains Marketplace publishing preparation.
- [docs/compatibility-matrix.json](docs/compatibility-matrix.json): machine-readable compatibility matrix.

Recommended CI gates:

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

## Current Verification Baseline

The current workspace has passed:

- `./gradlew verifyCodeLocator`
- `./gradlew verifyComposeLocatorCiTemplate`
- `./gradlew verifyComposeLocatorReleaseArchive`
- `./gradlew verifyComposeLocatorReleaseConsumer`
- `./gradlew verifyComposeLocatorWindowRootPolicy`
- `./gradlew verifyDemoDevice`
- `./gradlew verifyCodeLocatorDevice`
- `./gradlew verifyComposeLocatorRolloutReadiness`
- `./gradlew verifyComposeLocatorReleasePackage`
- Studio plugin ZIP structure verification
- release APK boundary verification
- source identity stability across app restarts

Private pilot app trial:

- A private pilot app built successfully after integration.
- After the sourceId/index architecture update, it generated 1252 source-identity-only Studio index entries.
- A forced Kotlin compile verified 570 compiler-injected source IDs.
- Its debug APK did not contain legacy compose-locator metadata or source-catalog entries.

Performance baseline:

- Synthetic scan for 250 files / 4000 composables: about 269 ms.
- Metadata encode/decode: about 100 ms.
- 80 AAR metadata extractions: about 1315 ms.
- Runtime 20k-node / 2000-query hit-test batch: about 122 ms.

## Release Status

- GitHub repository: [nianzixin/compose-code-locator](https://github.com/nianzixin/compose-code-locator)
- GitHub Release: [v0.1.1](https://github.com/nianzixin/compose-code-locator/releases/tag/v0.1.1)
- Maven Central: published as `io.github.nianzixin:*:0.1.1`
- Maven group: `io.github.nianzixin`
- Gradle plugin id: `io.github.nianzixin.compose-locator`
- Team convention plugin id: `io.github.nianzixin.team-compose-locator`

The current release ZIP includes:

- static Maven repository
- Android Studio plugin ZIP
- release manifest
- SHA-256 checksums
- rollout documentation
- public publishing documentation
- `README.md`
- `README-CN.md`

Publishing automation status:

- Maven Central: completed
- Gradle Plugin Portal: published
- JetBrains Marketplace: listing published; Studio plugin 0.1.4 targets IntelliJ Platform build range 241-261.* and is ready for Marketplace upload/review.

See [docs/public-publishing.md](docs/public-publishing.md) for Marketplace update and release artifact steps.

## Remaining Work

- Run `generateComposeLocatorRolloutReport -Pcodelocator.rollout.modules=...` on a larger production app and archive the report in CI.
- Add project-specific regression fixtures for each team's proprietary design-system components.
- Run JetBrains Plugin Verifier before each widened Android Studio compatibility update.
- Upload new Android Studio plugin ZIP versions to JetBrains Marketplace for IDE installation and updates.
- Replace the local ZIP task with the official IntelliJ Platform Gradle Plugin packaging flow when a compatible Android Studio SDK distribution is available.

## License

Apache License 2.0
