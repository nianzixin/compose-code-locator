plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.nianzixin.team-compose-locator")
}

android {
    namespace = "dev.codelocator.demo"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.codelocator.demo"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

}

composeLocator {
    sourceDirectories = listOf("src/main/kotlin")
}

dependencies {
    val externalComposeLocatorFixture = files(
        rootProject.layout.buildDirectory.file(
            "fixtures/external-compose-locator-aar/external-compose-locator-fixture.aar",
        ),
    ).builtBy(rootProject.tasks.named("generateExternalComposeLocatorAarFixture"))

    implementation(project(":demo-feature"))
    debugImplementation(externalComposeLocatorFixture)
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.compose.ui:ui:1.7.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.0")
    implementation("androidx.compose.foundation:foundation:1.7.0")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.0")
}
