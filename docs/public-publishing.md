# Public Publishing Guide

This project is prepared for public coordinates owned by the GitHub account `nianzixin`.

## Public Coordinates

```text
io.github.nianzixin:locator-runtime:0.1.1
io.github.nianzixin:locator-runtime-android:0.1.1
io.github.nianzixin:locator-compiler-plugin:0.1.1
io.github.nianzixin:locator-gradle-plugin:0.1.1
io.github.nianzixin.compose-locator:io.github.nianzixin.compose-locator.gradle.plugin:0.1.1
io.github.nianzixin.team-compose-locator:io.github.nianzixin.team-compose-locator.gradle.plugin:0.1.1
```

Gradle plugin IDs:

```kotlin
plugins {
    id("io.github.nianzixin.team-compose-locator") version "0.1.1"
}
```

Use the lower-level plugin only when custom dependency management is needed:

```kotlin
plugins {
    id("io.github.nianzixin.compose-locator") version "0.1.1"
}
```

## Immediate Website Publishing

Maven Central is now the preferred public distribution channel. The release archive still supports static Maven hosting for internal mirrors, offline trials, or CDN-based distribution.

```bash
./gradlew --no-daemon verifyCodeLocator
```

Upload the generated archive:

```text
build/composeLocator/compose-code-locator-0.1.1-release.zip
```

For a website/CDN release, unzip it and host:

```text
maven/                                      -> https://your-domain.example/compose-locator/maven/
studio-plugin/compose-code-locator-0.1.1.zip -> https://your-domain.example/compose-locator/download/
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

Status: published for version `0.1.1`.

Public artifacts are available under:

```text
https://repo.maven.apache.org/maven2/io/github/nianzixin/
```

The Central Portal namespace is `io.github.nianzixin`.

For future releases:

- Create or choose the public GitHub repository. The build currently uses `https://github.com/nianzixin/compose-code-locator` in POM metadata.
- Register and verify the `io.github.nianzixin` namespace in Central Portal.
- Confirm the project license. POM metadata currently declares Apache License 2.0; add a matching `LICENSE` file before public release.
- Configure GPG signing and Central Portal credentials in CI secrets.
- Publish release artifacts with sources JARs, javadoc JARs, signed Maven publications, and Central Portal checksums.

Required secrets:

```text
SIGNING_KEY
SIGNING_PASSWORD
CENTRAL_PORTAL_TOKEN
```

`CENTRAL_PORTAL_TOKEN` may be omitted if both of these are provided instead:

```text
CENTRAL_PORTAL_USERNAME
CENTRAL_PORTAL_PASSWORD
```

Local preflight:

```bash
SIGNING_KEY="$(cat private-key.asc)" SIGNING_PASSWORD=... \
  ./gradlew --no-daemon verifyComposeLocatorCentralBundle
```

Upload to Central Portal:

```bash
SIGNING_KEY="$(cat private-key.asc)" SIGNING_PASSWORD=... CENTRAL_PORTAL_TOKEN=... \
  ./gradlew --no-daemon publishComposeLocatorToMavenCentral
```

By default, uploads use `USER_MANAGED`, so the deployment must be reviewed and published in Central Portal. To request automatic publishing after validation:

```bash
./gradlew --no-daemon publishComposeLocatorToMavenCentral \
  -Pcodelocator.central.publishingType=AUTOMATIC
```

Check deployment status:

```bash
./gradlew --no-daemon checkComposeLocatorMavenCentralDeployment \
  -Pcodelocator.central.deploymentId=<deployment-id>
```

## Gradle Plugin Portal

Status: pending `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET`.

Publish these plugin IDs after ownership is ready:

```text
io.github.nianzixin.compose-locator
io.github.nianzixin.team-compose-locator
```

The included Gradle plugin build applies `com.gradle.plugin-publish` and configures website, VCS URL, display names, descriptions, and tags.

Required secrets:

```text
GRADLE_PUBLISH_KEY
GRADLE_PUBLISH_SECRET
```

Publish:

```bash
GRADLE_PUBLISH_KEY=... GRADLE_PUBLISH_SECRET=... \
  ./gradlew --no-daemon publishComposeLocatorGradlePlugins
```

## JetBrains Marketplace

Status: pending first manual listing/upload.

The Android Studio plugin ZIP is generated at:

```text
studio-plugin/build/distributions/compose-code-locator-0.1.1.zip
```

Current Marketplace plugin id:

```text
io.github.nianzixin.compose-code-locator
```

Official JetBrains Marketplace documentation requires the first plugin publication to be uploaded manually. Create the listing at JetBrains Marketplace, upload the ZIP above, and wait for review.

After the first manual upload exists, token-based publishing can be wired through the official IntelliJ Platform Gradle Plugin. Until this project migrates the custom local ZIP task to that plugin, the CI workflow uploads the Marketplace ZIP as an artifact for manual submission instead of calling an unsupported API.

## GitHub Actions

Manual public publishing workflow:

```text
.github/workflows/publish.yml
```

The workflow always runs:

```bash
./gradlew --no-daemon verifyComposeLocatorPublicPublishingReadiness
```

Then it can optionally:

- upload a signed Central Portal deployment
- publish Gradle plugins to the Gradle Plugin Portal
- upload the Android Studio plugin ZIP as a workflow artifact for JetBrains Marketplace submission
