# Public Publishing Guide

This project is prepared for public coordinates owned by the GitHub account `nianzixin`.

## Public Coordinates

```text
io.github.nianzixin:locator-runtime:0.1.0
io.github.nianzixin:locator-runtime-android:0.1.0
io.github.nianzixin:locator-compiler-plugin:0.1.0
io.github.nianzixin:locator-gradle-plugin:0.1.0
io.github.nianzixin.compose-locator:io.github.nianzixin.compose-locator.gradle.plugin:0.1.0
io.github.nianzixin.team-compose-locator:io.github.nianzixin.team-compose-locator.gradle.plugin:0.1.0
```

Gradle plugin IDs:

```kotlin
plugins {
    id("io.github.nianzixin.team-compose-locator") version "0.1.0"
}
```

Use the lower-level plugin only when custom dependency management is needed:

```kotlin
plugins {
    id("io.github.nianzixin.compose-locator") version "0.1.0"
}
```

## Immediate Website Publishing

The current release archive already supports static Maven hosting.

```bash
./gradlew --no-daemon verifyCodeLocator
```

Upload the generated archive:

```text
build/composeLocator/compose-code-locator-0.1.0-release.zip
```

For a website/CDN release, unzip it and host:

```text
maven/                                      -> https://your-domain.example/compose-locator/maven/
studio-plugin/compose-code-locator-0.1.0.zip -> https://your-domain.example/compose-locator/download/
README.md
release-manifest.txt
release-checksums.sha256
```

Consumers add the hosted Maven repository:

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

## Maven Central

Use Central Portal with namespace `io.github.nianzixin`.

Before publishing publicly:

- Create or choose the public GitHub repository. The build currently uses `https://github.com/nianzixin/compose-code-locator` in POM metadata.
- Register and verify the `io.github.nianzixin` namespace in Central Portal.
- Confirm the project license. POM metadata currently declares Apache License 2.0; add a matching `LICENSE` file before public release.
- Configure GPG signing and Central Portal credentials in CI secrets.
- Publish release artifacts with sources JARs and signed Maven publications.

## Gradle Plugin Portal

Publish these plugin IDs after ownership is ready:

```text
io.github.nianzixin.compose-locator
io.github.nianzixin.team-compose-locator
```

The plugin marker artifacts are already generated and verified in the staged Maven repository. For Gradle Plugin Portal release, add `com.gradle.plugin-publish`, configure plugin website/VCS/tags, and publish with Plugin Portal credentials.

## JetBrains Marketplace

The Android Studio plugin ZIP is generated at:

```text
studio-plugin/build/distributions/compose-code-locator-0.1.0.zip
```

For Marketplace release, create a JetBrains Marketplace plugin listing, upload the ZIP for the first release, and configure token-based publishing after approval.
