# Compose Code Locator Status

## Implemented

- Android Studio plugin + app debug SDK + Gradle build-time injection architecture.
- App debug runtime now reports lightweight `sourceId` values instead of full source file/line mappings.
- Full source mappings stay in local build intermediates and Studio indexes, not in APK assets or runtime catalogs.
- Gradle plugin emits module-local source maps, dependency metadata, ASM maps, and sharded Studio indexes.
- Kotlin IR compiler plugin injects private source identity into Compose calls with `Modifier` parameters.
- AGP ASM instrumentation injects debug-only source boundaries for composable calls without `Modifier` parameters.
- Android runtime provider auto-starts the debug server and collector; no Application/Activity bridge is required for normal integration.
- Runtime collection is query-driven through `/health`, `/snapshot`, and `/hitTest`, avoiding continuous background polling.
- Window-level Compose root discovery covers Popup/Dialog/DropdownMenu/ModalBottomSheet-style independent roots.
- Window root dedupe keeps class/title/window type/window token/bounds in the equivalence key, so full-screen Dialog/ModalBottomSheet roots are not merged into the underlying Activity root.
- Studio hit-testing filters candidates to the top window layer before source navigation.
- Automatic Semantics capture is derived from LayoutInfo modifier metadata and covers role/clickability plus available text/contentDescription/testTag values without walking Compose's merged SemanticsOwner tree.
- LayoutNode fallback capture provides bounds-only candidates when Semantics data is incomplete.
- Dynamic text and duplicate fixed text cases resolve through compiler/ASM source identity rather than business-code tags.
- Multi-module project dependency metadata is published through `composeLocatorMetadataElements`.
- External AAR/JAR metadata is consumed from `META-INF/compose-locator/compose-locator-metadata.json`.
- App module `source-map.json` remains module-local; dependency metadata is merged into the local Studio index only.
- Build metadata now stores source identity entries only (`Composable`, `ComposableCallSite`, `ModifierCallSite`), not runtime text/tag lookup tables.
- Studio index uses `manifest.json`, `source-id-index.tsv`, and sharded `jsonl` files under `build/intermediates/composeLocator/studio-index/v1`.
- Studio source lookup lazily loads shard files and caches loaded shards.
- Studio index root discovery is cached and prunes large non-target directories such as `.gradle`, `.idea`, `.git`, `node_modules`, and ordinary `build` contents.
- Studio rejects non-navigable source locations (`line <= 0` or `column <= 0`) and shows a `Navigation` diagnostic instead of displaying misleading `0:0` rows.
- Studio candidate ordering only treats source locations as navigable when they have a valid file/line/column.
- Studio now reports whether a candidate failed because runtime omitted `sourceId`, the local Studio index is missing/empty, the APK and IDE index are stale, or a shard has invalid source data.
- The debug runtime also exposes an app-scoped `localabstract:codelocator.<packageName>` socket; Studio prefers this over raw device TCP so multiple debug apps cannot steal the same `49391` endpoint from each other.
- Studio resolves the current foreground package from `dumpsys activity activities`, forwards to the app-scoped local socket, and falls back to legacy TCP `49391` for older app runtimes.
- Studio HTTP client retries short adb-forward connection refusals to handle transient Pad/device forwarding jitter.
- Studio transport can switch from app-scoped `localabstract` forwarding to legacy TCP `49391` after a post-connect request failure, covering devices where localabstract forwarding is visible in adb but intermittently rejects connections.
- P0 regression probes cover Popup, Dialog/AlertDialog, DropdownMenu, LazyVerticalGrid, NavHost pages, duplicate fixed text, no-Modifier wrapper composables, repeated list rows, nested Dialog/DropdownMenu, ModalBottomSheet, and AndroidView mixed-content boundaries.
- Device regression now also covers design-system-style wrappers: wrapper Button with `Modifier`, no-Modifier wrapper fallback, slot-based DropdownMenu content, slot-based AlertDialog content, nested overlay windows, sheet windows, and Compose/native interop boundaries.
- Studio device-flow regression now opens the real Popup trigger first, refreshes the screenshot, then verifies top-window `Popup CTA` click-to-source navigation.
- Team rollout guide is available at `docs/team-rollout.md` with integration boundaries, compatibility matrix, CI gates, performance budgets, rollout phases, and field troubleshooting.
- Release engineering guide is available at `docs/release-engineering.md` with Maven coordinates, Studio ZIP staging, required release gates, and versioning rules.
- Team convention Gradle plugin `io.github.nianzixin.team-compose-locator` is available for one-line rollout. It applies the core locator plugin, adds app debug runtime automatically, and adds library compile-only runtime symbols without touching release runtime classpaths.
- Rollout report tasks are implemented: `generateComposeLocatorRolloutReport` emits Markdown/JSON reports and `verifyComposeLocatorRolloutReadiness` gates source/index health plus configurable large-project budgets for Studio index size, shard size, and dependency metadata size.
- Machine-readable compatibility matrix is available at `docs/compatibility-matrix.json` and is checked by `verifyComposeLocatorCompatibilityMatrix`.
- Public publishing guide is available at `docs/public-publishing.md`; public Maven/Gradle plugin coordinates now use the GitHub-owned namespace `io.github.nianzixin`.
- Scheduled compatibility workflow `.github/workflows/compose-locator-compatibility.yml` is available and is checked against `docs/compatibility-matrix.json`.
- GitHub Actions CI template is available at `.github/workflows/compose-locator-ci.yml` with non-device PR gates and manual self-hosted adb device gates.
- Release and consumer smoke gates require JDK 17, matching AGP 8 and the published Gradle plugin target.
- Local Android Studio plugin ZIP packaging is available through `:studio-plugin:buildStudioPluginZip`.
- Local release staging is available through `stageComposeLocatorRelease`; it produces `build/composeLocator/release/maven`, `build/composeLocator/release/studio-plugin/compose-code-locator-0.1.0.zip`, `README.md`, copied rollout/release docs, `release-manifest.txt`, and `release-checksums.sha256`.
- Release package verification is available through `verifyComposeLocatorReleasePackage`; it checks runtime/compiler/Gradle plugin Maven artifacts, Gradle plugin marker POM coordinates/dependencies, sources artifacts, Studio plugin ZIP contents, release manifest, and SHA-256 checksums.
- Single-file release archive verification is available through `verifyComposeLocatorReleaseArchive`; it produces and verifies `build/composeLocator/compose-code-locator-0.1.0-release.zip`.
- Public coordinate verification is available through `verifyComposeLocatorPublicCoordinates`; it rejects stale `dev.codelocator` Maven/plugin publishing coordinates in release artifacts and verifies staged Maven paths under `io/github/nianzixin`.
- Release-boundary verification rejects locator runtime entries, debug server permissions, generated runtime sources, and compose-locator metadata assets in release APKs.
- Root non-device verification aggregate `verifyCodeLocator` includes rollout readiness, compatibility matrix, CI template, window-root policy, team convention wiring, source alignment, performance, build-efficiency, release package/archive, release-boundary, protocol, and plugin packaging gates.
- Root adb-backed aggregate `verifyCodeLocatorDevice` exists for connected-device validation.

## Validation

- `./gradlew verifyCodeLocator` passes in this workspace.
- `verifyComposeLocatorRolloutReadiness` passes for demo rollout modules and produces `build/reports/composeLocator/rollout/rollout-report.md` / `.json` with 244 source map entries, 253 Studio index entries, 50.01 KiB total index size, and 0 invalid rows.
- `verifyComposeLocatorCompatibilityMatrix` passes for the current Gradle/AGP/Kotlin/Compose baseline.
- `verifyComposeLocatorCompatibilityMatrix` also verifies that the compatibility workflow contains each JSON matrix lane and keeps the current baseline non-experimental.
- `verifyComposeLocatorCiTemplate` passes and guards the checked-in GitHub Actions rollout template.
- `verifyComposeLocatorReleaseConsumer` passes and verifies staged Maven artifacts can be consumed through the published Gradle plugin marker IDs.
- `verifyComposeLocatorWindowRootPolicy` passes and guards against regressing independent window dedupe to bounds-only matching.
- `.github/workflows/compose-locator-ci.yml` captures the current team CI shape: `verifyCodeLocator` on hosted macOS and `verifyCodeLocatorDevice` only on a manually triggered self-hosted adb runner.
- `verifyComposeLocatorTeamConvention` verifies the preferred one-line plugin applies the core locator plugin and scopes runtime dependencies to debug app builds / compile-only library symbols.
- `verifyComposeLocatorReleasePackage` passes and verifies staged Maven artifacts, Studio plugin ZIP, release manifest, and checksum manifest under `build/composeLocator/release`.
- `verifyComposeLocatorReleaseArchive` passes and verifies `build/composeLocator/compose-code-locator-0.1.0-release.zip` contains Maven artifacts, Studio plugin ZIP, docs, release manifest, and checksum manifest.
- `verifyComposeLocatorPublicCoordinates` passes and verifies the staged release package uses `io.github.nianzixin` public coordinates.
- `./gradlew verifyDemoDevice` passes on Pad `983d2183`, including Popup, Dialog/AlertDialog, DropdownMenu, LazyVerticalGrid, NavHost, duplicate fixed text, no-Modifier fallback, design-system-style slot wrappers, nested Dialog/DropdownMenu, ModalBottomSheet, and AndroidView mixed-content boundaries.
- `./gradlew verifyCodeLocatorDevice` passes on Pad `983d2183`, including Studio device-flow click-to-source verification through the real Popup trigger and Popup CTA top-window hit-test.
- `verifyStableDeviceNodeIds` now checks stable source identity across app restarts (`sourceId -> file:line`) rather than volatile runtime node ids.
- `:studio-plugin:verifyStudioPluginPackaging` passes and verifies `studio-plugin/build/distributions/compose-code-locator-0.1.0.zip`.
- `:locator-runtime-android:compileDebugKotlin`, `:studio-plugin:verifyStudioProtocol`, `:studio-plugin:verifyStudioPluginPackaging`, and `:demo-app:assembleDebug` pass after the navigation diagnostics and app-scoped socket changes.
- `verifyComposeCompilerSourceAlignment` passes for demo app and demo feature modules.
- `verifyComposeLocatorBuildEfficiency` passes and confirms default debug builds use the AGP ASM transform without running manual bytecode inspection tasks.
- `verifyComposeLocatorReleaseBoundary` passes and confirms release APK cleanup.
- Synthetic benchmark baseline in this workspace:
  - 250 files / 4000 composables source scan: about 269 ms
  - metadata encode/decode: about 100 ms
  - 80 AAR metadata extractions: about 1315 ms
  - runtime 20k-node / 2000-query hit-test batch: about 122 ms
- Real `ClinicTreatmentPad` trial builds successfully after the sourceId/index architecture update.
- `ClinicTreatmentPad` generated 1252 source-identity-only Studio index entries.
- `ClinicTreatmentPad` verified 570 compiler-injected source IDs after a forced Kotlin compile.
- `ClinicTreatmentPad` debug APK does not contain legacy compose-locator metadata/source-catalog entries.
- `ClinicTreatmentPad` was installed and launched on device `L2E0222127015908`; `/health` returned `sourceCount=0`, `nodeCount=20`, and `activeActivities=1`.
- `ClinicTreatmentPad` hit-test returned runtime `sourceId` values that resolved through the local Studio index to `LoginActivity.kt:242`, `LoginActivity.kt:247`, `LoginActivity.kt:249`, and `LoginActivity.kt:256`.
- Manual Android Studio UI verification is complete: installing the regenerated plugin ZIP and clicking real `ClinicTreatmentPad` screenshot elements can jump to source.
- Device check on Pad `983d2183` verified the new runtime still returns `/health` with `sourceCount=0` and active runtime nodes.
- Device check verified app-scoped forwarding to `localabstract:codelocator.dev.codelocator.demo` while `com.soyoung.clinic.pad` was also alive in the background.
- Device check on Pad `983d2183` verified Popup, DropdownMenu, and Dialog/AlertDialog as independent top-window roots.
- DropdownMenu opened as `windowLayer=3` with source-backed nodes resolving to `DemoApp.kt:243` and `DemoApp.kt:248`; hit-test at menu item centers returned top-window candidates before underlay nodes.
- Dialog opened as `windowLayer=3` with source-backed Button semantics; hit-test on dialog buttons returned top-window Button candidates before underlay nodes.
- ModalBottomSheet now resolves to top-window source-backed sheet content instead of the underlying NavHost/Home CTA; this was verified through `verifyCodeLocatorDevice` on Pad `983d2183`.
- Activity tracking hardening has been rerun through `verifyCodeLocatorDevice` after a previous device run exposed `activeActivities=0` while the app Activity was still resumed.
- Runtime no longer walks Compose's merged SemanticsOwner tree during HTTP collection, avoiding the observed `StackOverflowError`/main-thread stalls on Pad `983d2183`.
- Team promotion checklist is documented and requires non-device CI, device CI on phone/tablet class devices, real-app manual Studio jump, design-system wrapper coverage, release-boundary checks, and stale-index recovery docs.

## Not Implemented Yet

- Project-specific regression fixtures for each team's proprietary design-system components.
- Deeper nested popup/dialog/navigation combinations beyond the current P0 and design-system wrapper probes.
- Official IntelliJ Platform Gradle Plugin packaging against a clean compatible Android Studio SDK. The local Android Studio distribution in this workspace has Kotlin plugin descriptor/cache issues, so the project currently uses a local ZIP packaging task.

## Recommended Next Steps

1. Adapt `.github/workflows/compose-locator-ci.yml` to the team's CI system and run `generateComposeLocatorRolloutReport -Pcodelocator.rollout.modules=...` on production app modules as an archived artifact.
2. Add project-specific fixtures for the target company's actual design-system wrappers before broad rollout.
3. Mirror `build/composeLocator/release/maven`, `build/composeLocator/release/studio-plugin/compose-code-locator-0.1.0.zip`, and `build/composeLocator/release/release-checksums.sha256` to internal distribution.
4. Replace the local ZIP task with official IntelliJ Platform Gradle Plugin packaging when a compatible Android Studio SDK distribution is available.

## Field Debug Notes

- New runtime `/health.sourceCount` must be `0`. A nonzero value means Studio is connected to an old app runtime.
- If candidate details show `Navigation: sourceId not found in Studio index`, rebuild/sync the IDE project that produced the installed APK.
- If candidate details show `runtime did not provide sourceId`, reinstall the debug APK built with the Compose Locator Gradle plugin and runtime.
- If multiple locator-enabled apps are installed, use the regenerated Studio plugin so it forwards to `localabstract:codelocator.<foreground-package>` instead of whichever app owns device TCP `49391`.
