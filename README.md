# Compose Code Locator PoC

This repository is a working PoC for a CodeLocator-style Compose source locator. It is designed around a large-project architecture:

- no business-code markers are required in Compose UI code
- the app debug runtime reports compact `sourceId` values only
- full source locations stay in local Gradle/Studio build outputs, not in the APK
- build metadata stores source identity only, not text/tag lookup tables
- Android Studio resolves `sourceId` through a local sharded index
- release builds stay clean of locator runtime, debug server permissions, and metadata assets

## Modules

- `locator-runtime`: shared runtime models, node registry, hit-test sorting, and JSON protocol
- `locator-runtime-android`: Android debug SDK with provider auto-init, activity/window root tracking, Compose collection, and HTTP server
- `locator-compiler-plugin`: Kotlin IR plugin that injects private Compose source identity into debug builds
- `locator-gradle-plugin`: included Gradle plugins that scan Compose source, wire compiler/ASM instrumentation, publish dependency metadata, generate Studio indexes, and provide a team convention plugin for one-line rollout
- `studio-plugin`: Android Studio / IntelliJ plugin for device capture, hit-testing, overlay, candidate selection, and source navigation
- `demo-app`: Android Compose sample with no locator-specific business UI markers
- `demo-feature`: Android Compose library sample used to verify dependency metadata/index aggregation

## Current Architecture

1. The Gradle plugin scans each module's Compose source and writes module-local build intermediates:
   - `build/intermediates/composeLocator/source-map.json`
   - `build/intermediates/composeLocator/asm-source-map.txt`
   - `build/intermediates/composeLocator/metadata/compose-locator-metadata.json`
   - `build/intermediates/composeLocator/studio-index/v1`
2. The Kotlin IR plugin injects private source modifiers for Compose calls that accept `Modifier`.
3. For composables without a `Modifier` parameter, AGP ASM instrumentation injects debug-only source boundaries around composable invocations.
4. The Android debug runtime auto-starts from `LocatorInitProvider`, tracks active activities and visible window roots, and collects Compose nodes only when Studio queries `/health`, `/snapshot`, or `/hitTest`.
5. Runtime nodes carry bounds, text/tag/role diagnostics when available, window metadata, and optional `sourceId`. They do not carry full file paths or line mappings.
6. The Studio plugin captures a screenshot, sends click coordinates to `/hitTest`, receives candidates with `sourceId`, and resolves those IDs through the local sharded Studio index.

## Large-Project Strategy

The app module's `source-map.json` remains module-local. Dependency metadata from project modules and external AAR/JAR artifacts is merged only into the Studio index, not into a runtime source catalog and not into APK assets. Metadata entries are limited to source identity (`Composable`, `ComposableCallSite`, `ModifierCallSite`) so large projects do not accumulate text/tag lookup tables.

External libraries that want to expose locator metadata should package:

```text
META-INF/compose-locator/compose-locator-metadata.json
```

The old asset-based path is intentionally not part of the final architecture. It would risk APK asset bloat and runtime metadata loading in large apps.

The Studio index is sharded:

```text
build/intermediates/composeLocator/studio-index/v1/
  manifest.json
  source-id-index.tsv
  shards/<hex>.jsonl
```

Studio loads `source-id-index.tsv` once, loads shard files lazily, and keeps an LRU cache of loaded shards. This keeps click-to-source lookup small even when a project has many modules.

## What Is Covered

- automatic Semantics capture for text, content description, existing testTag values, role, clickability, and bounds
- LayoutNode fallback capture via reflective `AndroidComposeView.root` / `LayoutInfo` traversal for bounds-only candidates
- compiler-injected private source markers for dynamic text and UI calls where literal text is not stable
- ASM source-boundary fallback for wrapper composables with no `Modifier` parameter
- multiple activities and multiple Compose roots
- Popup/Dialog/DropdownMenu windows with top-window hit filtering
- nested Dialog + DropdownMenu, ModalBottomSheet, and AndroidView mixed-content boundary fixtures
- LazyVerticalGrid, repeated LazyColumn rows, and NavHost multi-page flows
- duplicate fixed texts such as two `确认` buttons, resolved by source identity rather than text matching
- deterministic source-backed node IDs, with process-local IDs used only as fallback
- local Android Studio plugin ZIP packaging

The demo app intentionally does not add `.locatorNode(...)`, `.testTag(...)`, root collectors, or an Application bridge to business UI.

## Integration Shape

Gradle builds should run with JDK 17. This matches AGP 8 and the published Compose Locator Gradle plugin target.

Preferred team rollout for app and Compose UI library modules:

```kotlin
plugins {
    id("io.github.nianzixin.team-compose-locator")
}
```

The team convention plugin applies the core locator plugin, adds `locator-runtime-android` to app `debugImplementation`, and adds compile-only runtime symbols to Android library modules. Release variants do not receive the runtime dependency.

Optional core locator configuration:

```kotlin
composeLocator {
    sourceDirectories = listOf("src/main/kotlin", "src/debug/kotlin")
    includePackages = listOf("com.example")
}
```

The convention plugin intentionally keeps dependency wiring separate from core locator tuning. Use `composeLocator { ... }` for source roots, package filters, and advanced injection options. The lower-level `io.github.nianzixin.compose-locator` plugin remains available when a build needs fully manual dependency control.

No Activity/Application code is required for normal debug integration. The provider starts the debug server and collector automatically in debug builds.

## Verification Commands

Run the non-device verification suite:

```bash
./gradlew verifyCodeLocator
```

Run the adb-backed end-to-end suite:

```bash
./gradlew verifyCodeLocatorDevice
```

Build the Android demo app:

```bash
./gradlew :demo-app:assembleDebug
```

Build the local Android Studio plugin ZIP:

```bash
./gradlew :studio-plugin:buildStudioPluginZip
```

The ZIP is written to:

```text
studio-plugin/build/distributions/compose-code-locator-0.1.0.zip
```

Stage the team-consumable release package:

```bash
./gradlew stageComposeLocatorRelease
./gradlew verifyComposeLocatorReleasePackage
./gradlew verifyComposeLocatorReleaseArchive
```

The staged package is written to:

```text
build/composeLocator/release/
  maven/
  studio-plugin/compose-code-locator-0.1.0.zip
  README.md
  docs/team-rollout.md
  docs/release-engineering.md
  docs/compatibility-matrix.json
  release-manifest.txt
  release-checksums.sha256
```

The single-file distributable is written to:

```text
build/composeLocator/compose-code-locator-0.1.0-release.zip
```

Verify source alignment:

```bash
./gradlew verifyComposeCompilerSourceAlignment
```

Verify build efficiency and release boundary:

```bash
./gradlew verifyComposeLocatorBuildEfficiency verifyComposeLocatorReleaseBoundary
```

Verify synthetic performance baselines:

```bash
./gradlew verifyComposeLocatorPerformance
```

Generate the team rollout profile report:

```bash
./gradlew generateComposeLocatorRolloutReport
./gradlew generateComposeLocatorRolloutReport -Pcodelocator.rollout.modules=:app,:feature
./gradlew verifyComposeLocatorRolloutReadiness -Pcodelocator.rollout.modules=:app,:feature
```

The report is written to:

```text
build/reports/composeLocator/rollout/rollout-report.md
build/reports/composeLocator/rollout/rollout-report.json
```

Rollout readiness also enforces large-project safety budgets. The defaults are intentionally broad and can be tightened in CI:

```bash
./gradlew verifyComposeLocatorRolloutReadiness \
  -Pcodelocator.rollout.modules=:app,:feature \
  -Pcodelocator.rollout.maxStudioIndexBytesPerModule=26214400 \
  -Pcodelocator.rollout.maxLargestShardEntries=10000 \
  -Pcodelocator.rollout.maxDependencyMetadataBytesPerModule=26214400
```

Run the demo against a connected adb device:

```bash
./gradlew verifyDemoDevice
```

If multiple devices are connected, set `CODELOCATOR_DEVICE_SERIAL=<serial>`.

## Team Rollout

The team rollout guide is in `docs/team-rollout.md`. It covers integration boundaries, compatibility expectations, CI gates, performance budgets, design-system regression coverage, rollout phases, and troubleshooting.

Release engineering details are in `docs/release-engineering.md`. It defines the Maven coordinates, Studio ZIP staging path, required release gates, and versioning rules.

Public website / Maven Central / Gradle Plugin Portal / JetBrains Marketplace publishing notes are in `docs/public-publishing.md`.

Recommended rollout gates:

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

The machine-readable compatibility baseline is `docs/compatibility-matrix.json`; `verifyCodeLocator` checks that it matches the current Gradle/AGP/Kotlin/Compose baseline.

The scheduled compatibility workflow is `.github/workflows/compose-locator-compatibility.yml`. It uses the matrix in `docs/compatibility-matrix.json` to exercise selected Gradle/AGP/Kotlin/Compose lanes, with non-baseline lanes marked experimental by default.

A ready-to-adapt GitHub Actions template lives at `.github/workflows/compose-locator-ci.yml`. It runs non-device verification on PR/push and keeps adb device verification as a manual self-hosted job.

The preferred team integration is also gated by `verifyComposeLocatorTeamConvention`, which verifies that the convention plugin applies the core locator plugin and scopes the runtime dependency to debug app builds / compile-only library symbols.

The device regression now includes design-system-style wrapper coverage for wrapper Buttons, no-Modifier wrappers, slot-based DropdownMenu content, and slot-based AlertDialog content.

## Verified Baseline

Current workspace validation:

- `./gradlew verifyCodeLocator` passes
- `./gradlew verifyComposeLocatorCiTemplate` passes
- `./gradlew verifyComposeLocatorReleaseArchive` passes
- `./gradlew verifyComposeLocatorReleaseConsumer` passes
- `./gradlew verifyComposeLocatorWindowRootPolicy` passes
- `./gradlew verifyDemoDevice` passes on Pad `983d2183`
- `./gradlew verifyCodeLocatorDevice` passes on Pad `983d2183`
- `./gradlew verifyComposeLocatorRolloutReadiness` passes for demo rollout modules
- `./gradlew verifyComposeLocatorReleasePackage` passes and verifies local Maven staging, Studio plugin ZIP, release manifest, and SHA-256 checksums
- `.github/workflows/compose-locator-compatibility.yml` is checked against `docs/compatibility-matrix.json`
- Studio plugin ZIP structure verification passes
- release APK boundary verification passes
- synthetic benchmark with 250 files / 4000 composables scans in about 269 ms
- metadata encode/decode is about 100 ms
- 80 AAR metadata extractions are about 1315 ms
- runtime 20k-node / 2000-query hit-test batch is about 122 ms
- demo device regression covers design-system-style wrapper Button, no-Modifier wrapper, DropdownMenu slot content, AlertDialog slot content, nested Dialog/DropdownMenu, ModalBottomSheet, and AndroidView mixed-content boundaries
- device stability regression verifies source identity stability across app restarts instead of relying on runtime node ids

The real `ClinicTreatmentPad` trial has also built successfully with the plugin after the sourceId/index architecture update. It generated 1252 source-identity-only Studio index entries, verified 570 compiler-injected source IDs after a forced Kotlin compile, and its debug APK did not contain legacy compose-locator metadata/source-catalog entries.

## Remaining Work

- run `generateComposeLocatorRolloutReport -Pcodelocator.rollout.modules=...` on a larger production app and archive the report in CI
- add project-specific regression fixtures for each team's proprietary design-system components before broad rollout
- mirror `build/composeLocator/release/maven` and `build/composeLocator/release/studio-plugin/compose-code-locator-0.1.0.zip` to internal distribution
- replace the local ZIP task with official IntelliJ Platform Gradle Plugin packaging when a compatible Android Studio SDK distribution is available
