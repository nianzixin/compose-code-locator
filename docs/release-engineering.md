# Release Engineering

This project publishes Compose Code Locator as separate team-consumable artifacts:

- Gradle plugins for build-time source identity injection and team convention wiring.
- Kotlin compiler plugin for private Compose source markers.
- Android debug runtime SDK.
- Shared JVM runtime protocol.
- Android Studio plugin ZIP.

Business modules should consume published coordinates. They should not copy debug bridge code, runtime source files, or generated metadata into the app.

## Local Staging

Create the local release package:

```bash
./gradlew stageComposeLocatorRelease
```

Verify the staged package:

```bash
./gradlew verifyComposeLocatorReleasePackage
```

The package is written to:

```text
build/composeLocator/release/
  maven/
  studio-plugin/compose-code-locator-<studio-plugin-version>.zip
  README.md
  README-CN.md
  docs/
  release-manifest.txt
  release-checksums.sha256
```

## Maven Coordinates

Publish or mirror these coordinates into the team's internal Maven repository:

```text
io.github.nianzixin:locator-runtime:0.1.1
io.github.nianzixin:locator-runtime-android:0.1.1
io.github.nianzixin:locator-compiler-plugin:0.1.1
io.github.nianzixin:locator-gradle-plugin:0.1.1
io.github.nianzixin.compose-locator:io.github.nianzixin.compose-locator.gradle.plugin:0.1.1
io.github.nianzixin.team-compose-locator:io.github.nianzixin.team-compose-locator.gradle.plugin:0.1.1
```

The Gradle plugin marker artifacts are required so app builds can use:

```kotlin
plugins {
    id("io.github.nianzixin.team-compose-locator") version "0.1.1"
}
```

`verifyComposeLocatorReleasePackage` checks that both marker POMs point to `io.github.nianzixin:locator-gradle-plugin:<version>`, which is the dependency Gradle resolves when a team applies the plugin by ID.

## Repository Wiring

For local validation against the staged Maven repository:

```kotlin
pluginManagement {
    repositories {
        maven("path/to/build/composeLocator/release/maven")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("path/to/build/composeLocator/release/maven")
        google()
        mavenCentral()
    }
}
```

For team rollout, mirror `build/composeLocator/release/maven` into the internal Maven repository and install the ZIP under `build/composeLocator/release/studio-plugin/` through Android Studio's local plugin installation flow. Publish `release-checksums.sha256` with the mirrored package so teams can verify staged Maven artifacts and the Studio plugin ZIP after transfer.

## Release Gates

Run release gates with JDK 17. This matches AGP 8 requirements and the published Gradle plugin target.

Run these before distributing a version:

```bash
./gradlew verifyCodeLocator
CODELOCATOR_DEVICE_SERIAL=<serial> ./gradlew verifyCodeLocatorDevice
./gradlew verifyComposeLocatorCiTemplate
./gradlew verifyComposeLocatorReleaseArchive
./gradlew verifyComposeLocatorReleaseConsumer
./gradlew verifyComposeLocatorReleasePackage
./gradlew verifyComposeLocatorWindowRootPolicy
./gradlew verifyStudioPluginWithPluginVerifier
```

For a target app, also run:

```bash
./gradlew verifyComposeLocatorRolloutReadiness -Pcodelocator.rollout.modules=:app,:feature
./gradlew :app:assembleDebug :app:verifyComposeCompilerSourceAlignment
./gradlew :app:assembleRelease
```

Release APK checks must prove no locator runtime implementation, debug server permission, generated source catalog, or compose-locator metadata asset leaks into release builds.

The repository includes `.github/workflows/compose-locator-ci.yml` as a baseline CI template. Use hosted macOS runners for non-device gates and a self-hosted runner with adb access for `verifyCodeLocatorDevice`.

Public release readiness is checked with:

```bash
./gradlew verifyComposeLocatorPublicPublishingReadiness
```

Credential-dependent public publishing is driven by `.github/workflows/publish.yml`. Maven Central upload requires GPG signing plus Central Portal credentials. Gradle Plugin Portal publishing requires `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET`. JetBrains Marketplace uploads remain manual in this project; the workflow uploads the Studio plugin ZIP as a release artifact for review, and `verifyStudioPluginWithPluginVerifier` provides the local Plugin Verifier gate before widening Marketplace compatibility. Multi-version verification requires local IDE directories for each target range.

## Versioning Rules

- Patch version: bug fixes in runtime, Studio plugin, Gradle tasks, or documentation that do not change generated metadata format.
- Minor version: metadata format changes, new compiler/ASM injection behavior, or compatibility baseline changes.
- Major version: incompatible Studio index format or required app integration changes.

When changing Kotlin, AGP, Compose compiler, or Compose UI versions, update `docs/compatibility-matrix.json` and rerun the release gates before publishing.

For planned dependency upgrades, also run `.github/workflows/compose-locator-compatibility.yml`. Keep the current published baseline as non-experimental and mark exploratory AGP/Kotlin/Compose lanes as experimental until they pass consistently.
