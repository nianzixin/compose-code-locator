plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "2.0.21"
    `maven-publish`
    signing
    id("com.gradle.plugin-publish") version "1.3.1"
}

group = "io.github.nianzixin"
version = "0.1.1"

val publicGitHubUrl = "https://github.com/nianzixin/compose-code-locator"

kotlin {
    jvmToolchain(17)
}

java {
    withSourcesJar()
    withJavadocJar()
}

gradlePlugin {
    website.set(publicGitHubUrl)
    vcsUrl.set("$publicGitHubUrl.git")
    plugins {
        create("composeCodeLocator") {
            id = "io.github.nianzixin.compose-locator"
            implementationClass = "dev.codelocator.gradle.ComposeLocatorPlugin"
            displayName = "Compose Code Locator"
            description = "Debug-only source metadata injection scaffold for Compose code location."
            tags.set(listOf("android", "compose", "debugging", "source-navigation"))
        }
        create("teamComposeCodeLocator") {
            id = "io.github.nianzixin.team-compose-locator"
            implementationClass = "dev.codelocator.gradle.ComposeLocatorTeamPlugin"
            displayName = "Team Compose Code Locator"
            description = "Team convention plugin that applies Compose Code Locator and wires debug-only runtime dependencies."
            tags.set(listOf("android", "compose", "debugging", "convention-plugin"))
        }
    }
}

dependencies {
    implementation(gradleApi())
    implementation(kotlin("stdlib"))
    compileOnly("com.android.tools.build:gradle-api:8.5.2")
    implementation("org.ow2.asm:asm:9.7")
    implementation("org.ow2.asm:asm-tree:9.7")
}

tasks.register<JavaExec>("benchmarkComposeLocatorPerformance") {
    group = "verification"
    description = "Runs a synthetic Compose Locator source scanning and metadata performance benchmark."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("dev.codelocator.gradle.benchmark.ComposeLocatorPerformanceBenchmarkKt")
    args(layout.buildDirectory.dir("benchmarks/compose-locator-performance").get().asFile.absolutePath)
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Compose Code Locator Gradle Plugin")
            description.set("Gradle plugin for Compose source identity metadata, compiler wiring, ASM instrumentation, and Studio indexes.")
            url.set(publicGitHubUrl)
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
                connection.set("scm:git:$publicGitHubUrl.git")
                developerConnection.set("scm:git:ssh://git@github.com/nianzixin/compose-code-locator.git")
                url.set(publicGitHubUrl)
            }
        }
    }
    repositories {
        maven {
            name = "composeLocatorStaging"
            url = layout.projectDirectory.dir("../build/composeLocator/release/maven").asFile.toURI()
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
