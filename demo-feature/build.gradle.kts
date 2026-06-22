plugins {
    id("com.android.library")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.nianzixin.team-compose-locator")
}

android {
    namespace = "dev.codelocator.demo.feature"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
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
    implementation("androidx.compose.ui:ui:1.7.0")
    implementation("androidx.compose.foundation:foundation:1.7.0")
    implementation("androidx.compose.material3:material3:1.3.0")
}
