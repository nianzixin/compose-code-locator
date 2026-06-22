plugins {
    id("com.android.library")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    `maven-publish`
    signing
}

android {
    namespace = "dev.codelocator.runtime.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
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

    publishing {
        singleVariant("debug") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(project(":locator-runtime"))
    compileOnly("androidx.compose.runtime:runtime:1.7.0") {
        isTransitive = false
    }
    compileOnly("androidx.compose.ui:ui:1.7.0") {
        isTransitive = false
    }
    compileOnly("androidx.compose.ui:ui-geometry:1.7.0") {
        isTransitive = false
    }
    compileOnly("androidx.compose.ui:ui-graphics:1.7.0") {
        isTransitive = false
    }
    compileOnly("androidx.compose.ui:ui-text:1.7.0") {
        isTransitive = false
    }
    compileOnly("androidx.compose.ui:ui-unit:1.7.0") {
        isTransitive = false
    }
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0") {
        isTransitive = false
    }
}

val androidJavadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

publishing {
    publications {
        create<MavenPublication>("debug") {
            afterEvaluate {
                from(components["debug"])
            }
            artifact(androidJavadocJar)
            artifactId = "locator-runtime-android"
            pom {
                name.set("Compose Code Locator Android Runtime")
                description.set("Debug Android runtime SDK for Compose Code Locator.")
                url.set(rootProject.extra["publicGitHubUrl"] as String)
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("nianzixin")
                        name.set("nianzixin")
                        url.set("https://github.com/nianzixin")
                    }
                }
                scm {
                    val repositoryUrl = rootProject.extra["publicGitHubUrl"] as String
                    connection.set("scm:git:$repositoryUrl.git")
                    developerConnection.set("scm:git:ssh://git@github.com/nianzixin/compose-code-locator.git")
                    url.set(repositoryUrl)
                }
            }
        }
    }
    repositories {
        maven {
            name = "composeLocatorStaging"
            url = rootProject.layout.buildDirectory.dir("composeLocator/release/maven").get().asFile.toURI()
        }
    }
}

signing {
    val signingKey = providers.environmentVariable("SIGNING_KEY")
        .orElse(providers.gradleProperty("signingInMemoryKey"))
        .orNull
    val signingPassword = providers.environmentVariable("SIGNING_PASSWORD")
        .orElse(providers.gradleProperty("signingInMemoryKeyPassword"))
        .orNull
    if (!signingKey.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
    isRequired = !signingKey.isNullOrBlank()
    sign(publishing.publications)
}
