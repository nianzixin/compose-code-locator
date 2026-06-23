# Team Rollout Guide

This guide is for rolling Compose Code Locator into a large Android team. The intended integration model is Android Studio plugin + app debug SDK + Gradle build-time injection. Business UI code should not add locator-specific modifiers, tags, root collectors, or Application/Activity bridges.

## Rollout Principles

- Keep source identity in build outputs and Studio indexes, not in the APK.
- Keep the app runtime query-driven. Runtime collection should happen on `/health`, `/snapshot`, and `/hitTest`, not through continuous polling.
- Keep release builds clean. Release APKs must not package locator runtime, debug server permissions, generated source catalogs, or compose-locator metadata assets.
- Treat text, test tags, and content descriptions as diagnostics only. Source navigation must use compiler/ASM source IDs.
- Validate design-system wrappers before broad rollout. Most real failures come from slot APIs, wrapper composables without `Modifier`, Popup/Dialog windows, and stale Studio indexes.

## Required Integration

Use JDK 17 for Gradle builds. AGP 8 requires it, and the published Compose Locator Gradle plugin is compiled for Java 17.

Prefer the team convention plugin on modules that own Compose UI source:

```kotlin
plugins {
    id("io.github.nianzixin.team-compose-locator")
}
```

The convention plugin applies the core Compose Locator plugin and wires runtime dependencies safely:

- Android application modules get `locator-runtime-android` in `debugImplementation`.
- Android library modules get compile-only runtime symbols for compiler-injected markers.
- Release app runtime classpaths do not receive locator runtime dependencies.

Configure source roots and package filters when the module is large:

```kotlin
composeLocator {
    sourceDirectories = listOf("src/main/kotlin", "src/debug/kotlin")
    includePackages = listOf("com.company.app", "com.company.design")
}
```

The convention plugin handles dependency scope only. Core locator tuning remains in `composeLocator { ... }` so source scanning and AGP/Kotlin wiring happen at the same point as the lower-level plugin.

Use the lower-level `io.github.nianzixin.compose-locator` plugin only when a build needs custom dependency management.

No business UI code should call `.locatorNode(...)`, add artificial `testTag`, install a root collector, or copy a debug bridge into the app.

## Compatibility Matrix

| Area | Status | Notes |
| --- | --- | --- |
| Gradle 8.x | Expected supported | The plugin uses Gradle tasks/configurations and AGP variant APIs. Validate each company wrapper version in CI. |
| Android Gradle Plugin 8.0+ | Expected supported | The implementation relies on AGP ASM instrumentation APIs. This workspace validates AGP 8.6.1. |
| Kotlin 2.0.x | Verified baseline | This workspace validates Kotlin 2.0.21 and Compose compiler plugin wiring. |
| Kotlin 2.1.x+ | Requires validation | Compiler plugin APIs can shift. Add one CI lane before upgrading Kotlin. |
| Compose UI 1.5+ | Expected supported | Runtime collection uses Compose layout/modifier metadata and should be validated against the team's Compose BOM. |
| Compose UI 1.7.0 | Verified baseline | Demo and device regression use Compose UI/Foundation 1.7.0 and Material3 1.3.0. |
| Android Studio 2025.x | Local ZIP supported | Current project builds a local plugin ZIP. Official IntelliJ Platform packaging is still a packaging-track item. |

## CI Gates

Use these gates before enabling the plugin broadly:

```bash
./gradlew verifyCodeLocator
```

This non-device gate verifies source metadata, compiler source alignment, build efficiency, performance baselines, release boundary, team convention plugin wiring, protocol parsing, and plugin ZIP packaging.

The gate also verifies `docs/compatibility-matrix.json`, so the documented Gradle/AGP/Kotlin/Compose baseline cannot drift from the build scripts silently.

The optional `.github/workflows/compose-locator-compatibility.yml` workflow defines the broader Gradle/AGP/Kotlin/Compose compatibility lanes from `docs/compatibility-matrix.json`. The baseline lane is required to pass; exploratory lanes are marked experimental so teams can detect upcoming AGP/Kotlin drift without blocking every PR.

```bash
./gradlew verifyComposeLocatorReleasePackage
```

This release gate stages and verifies the internal Maven artifacts, Gradle plugin marker artifacts, Android Studio plugin ZIP, release manifest, and SHA-256 checksum manifest. Mirror `build/composeLocator/release/maven`, `build/composeLocator/release/studio-plugin/compose-code-locator-0.1.1.zip`, and `build/composeLocator/release/release-checksums.sha256` to the team's internal distribution channel.

The staged release also includes `README.md`, `README-CN.md`, `docs/team-rollout.md`, `docs/release-engineering.md`, and `docs/compatibility-matrix.json` so the internal package can be distributed as a self-contained onboarding bundle.

Use `./gradlew verifyComposeLocatorReleaseArchive` when the team wants a single uploadable artifact. It verifies `build/composeLocator/compose-code-locator-0.1.1-release.zip` contains the staged Maven repository, Studio plugin ZIP, docs, release manifest, and checksum file.

```bash
CODELOCATOR_DEVICE_SERIAL=<serial> ./gradlew verifyCodeLocatorDevice
```

This adb-backed gate verifies screenshot/click source identity on a real device, including Popup, Dialog, DropdownMenu, LazyGrid, NavHost, duplicate text, and design-system-style wrappers.

For each real app module, add a lightweight app-local gate:

```bash
./gradlew :app:assembleDebug :app:verifyComposeCompilerSourceAlignment
```

Generate and archive a rollout profile for real project modules:

```bash
./gradlew generateComposeLocatorRolloutReport -Pcodelocator.rollout.modules=:app,:feature
./gradlew verifyComposeLocatorRolloutReadiness -Pcodelocator.rollout.modules=:app,:feature
```

The report is emitted under `build/reports/composeLocator/rollout/` as both Markdown and JSON. It records source-map entries, Studio index entries, index size, shard distribution, ASM fallback count, compiler-injected count, dependency metadata count, and invalid source rows.

`verifyComposeLocatorRolloutReadiness` also enforces CI budgets. Defaults are broad; tighten them after collecting the first real-app report:

```bash
./gradlew verifyComposeLocatorRolloutReadiness \
  -Pcodelocator.rollout.modules=:app,:feature \
  -Pcodelocator.rollout.maxStudioIndexBytesPerModule=26214400 \
  -Pcodelocator.rollout.maxLargestShardEntries=10000 \
  -Pcodelocator.rollout.maxDependencyMetadataBytesPerModule=26214400
```

A GitHub Actions template is available at `.github/workflows/compose-locator-ci.yml`. It runs `verifyCodeLocator` on normal PR/push builds, archives the rollout report plus release manifest/checksum files, and leaves `verifyCodeLocatorDevice` as a manual self-hosted job because it requires a connected adb device. The `verifyComposeLocatorCiTemplate` gate verifies that the checked-in template keeps those required pieces.

For scheduled compatibility checks, use `.github/workflows/compose-locator-compatibility.yml`. It patches the wrapper and build-script versions per matrix lane, then runs source alignment, build-efficiency, and release-boundary gates.

For release safety:

```bash
./gradlew :app:assembleRelease
unzip -l app/build/outputs/apk/release/*.apk | grep -E 'locator-runtime|compose-locator|GeneratedLocatorSources' && exit 1 || true
```

## Performance Budget

Large-project targets should be enforced as budgets, not just observations:

| Budget | Target |
| --- | --- |
| Kotlin compiler plugin cost | Keep debug Kotlin compile regression under 5 percent after warm build stabilization. |
| AGP ASM transform cost | Keep transform overhead under 3 percent of debug build time. |
| Studio index size | Keep sharded index in build intermediates only; do not package it into APK assets. |
| APK size | Release APK delta must be zero. Debug APK delta should contain runtime code only, not full source maps. |
| Runtime idle cost | No continuous polling. With Studio disconnected, the runtime should only hold the debug server and lifecycle hooks. |
| First click latency | Keep `/snapshot` + `/hitTest` + Studio index lookup under 300 ms on common team devices after app warm-up. |

Measure a real app before broad rollout:

```bash
./gradlew :app:assembleDebug --profile
./gradlew :app:compileDebugKotlin :app:verifyComposeCompilerSourceAlignment --rerun-tasks
./gradlew verifyComposeLocatorPerformance
./gradlew generateComposeLocatorRolloutReport -Pcodelocator.rollout.modules=:app,:feature
```

Record entry counts:

```bash
wc -l app/build/intermediates/composeLocator/studio-index/v1/source-id-index.tsv
du -sh app/build/intermediates/composeLocator/studio-index/v1
```

## Design-System Regression Checklist

Add or keep fixtures that cover these patterns:

- Wrapper composable with a `Modifier` parameter around Material `Button`, `Text`, `Card`, and list rows.
- Wrapper composable without a `Modifier` parameter, relying on ASM source-boundary fallback.
- Slot APIs where the business call site passes `title`, `body`, `confirmButton`, `trigger`, or `content` lambdas.
- Popup/Dialog/Dropdown/ModalBottomSheet windows, including dynamic text inside the top window.
- Nested overlays such as Dialog containing DropdownMenu, plus ModalBottomSheet.
- AndroidView/WebView mixed content, where the expected locator boundary is the Compose interop call site unless native View instrumentation is added separately.
- LazyColumn/LazyGrid repeated items with duplicate fixed text.
- NavHost destination changes where the same text appears on multiple pages.
- Third-party design-system modules published as project dependencies and external AAR/JAR artifacts.

The demo's `verifyDemoDevice` task now includes design-system-style wrapper coverage for `Button`, no-Modifier wrapper, DropdownMenu slot content, AlertDialog slot content, nested overlays, and ModalBottomSheet.

## Rollout Phases

1. Enable the plugin in one demo app and one design-system sample module. Run `verifyCodeLocator` and `verifyCodeLocatorDevice`.
2. Enable the plugin in one real feature module and the app module. Validate source index generation and manual Studio jump.
3. Add the app-local CI gate for `assembleDebug`, `verifyComposeCompilerSourceAlignment`, and `verifyComposeLocatorRolloutReadiness`.
4. Add release-boundary checks to release CI.
5. Enable more feature modules by package allowlist. Track compile time, index size, and first click latency.
6. Mirror the staged Maven repository and Studio plugin ZIP into internal distribution.
7. Make Android Studio plugin installation part of the team's debug tooling docs.

## Troubleshooting

| Symptom | Likely Cause | Action |
| --- | --- | --- |
| Candidate shows `line=0` or cannot navigate | Runtime and Studio index are stale or source ID is missing from the index | Rebuild/sync the IDE project and reinstall the debug APK built from the same source. |
| `/health.sourceCount` is nonzero | Studio connected to an old runtime that still exposes full source catalogs | Reinstall the app with the latest runtime; verify app-scoped local socket forwarding. |
| Clicking a Dialog/Dropdown/ModalBottomSheet locates the underlay page | Top-window root was not collected, same-bounds independent windows were deduped incorrectly, or ordering regressed | Run `verifyCodeLocatorDevice` and `verifyComposeLocatorWindowRootPolicy`; inspect `windowLayer` in `/hitTest`. |
| Dynamic variable text cannot locate | Compiler source marker did not inject or source index is missing | Run `:app:compileDebugKotlin :app:verifyComposeCompilerSourceAlignment --rerun-tasks`. |
| Duplicate fixed text jumps to the wrong place | Navigation is using text instead of source ID | Verify Studio candidate uses `sourceId` and the local index shard resolves to the expected path/line. |
| Multiple devices/apps connect incorrectly | adb forward points to the wrong app | Use the current Studio plugin; it prefers `localabstract:codelocator.<foreground-package>`. |
| Release APK contains locator entries | Runtime dependency or metadata asset leaked into release | Keep runtime in `debugImplementation` only and run release-boundary checks. |

## Promotion Criteria

Do not roll out to the whole team until these are true:

- `verifyCodeLocator` passes on CI.
- `verifyCodeLocatorDevice` passes on at least one phone and one Pad/tablet class device.
- `verifyComposeLocatorReleasePackage` passes and the staged Maven repository plus Studio plugin ZIP are archived or mirrored internally.
- `verifyComposeLocatorRolloutReadiness -Pcodelocator.rollout.modules=...` passes and its report is archived for the target app.
- `verifyComposeLocatorTeamConvention` passes, or the team has an equivalent convention-plugin gate in its build logic.
- A real app module manually jumps from screenshot click to source in Android Studio.
- A real design-system wrapper sample covers wrapper, no-Modifier, slot, Dialog, Dropdown, ModalBottomSheet, lazy list/grid, and navigation cases.
- Release-boundary check proves no locator runtime or metadata is packaged in release APKs.
- The team has a documented recovery path for stale index, old runtime, multiple devices, and Android Studio plugin reinstall.
