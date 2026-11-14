plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "2.4.21"
    `maven-publish`
    signing
    id("com.gradle.plugin-publish") version "1.3.1"
}

group = "io.github.nianzixin"
version = "0.1.2"

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

// 自包含 bootstrap jar：供 Studio 插件释放到工程 .gradle/compose-locator/ 后由全局 init script 加载，
// 无法走 maven 坐标解析，故把 implementation 依赖（asm、kotlin-stdlib）打进包内；
// 若用户 buildscript classpath 已有同名类，Gradle 类加载先命中用户的，包内副本仅兜底
tasks.register<Jar>("bootstrapJar") {
    group = "build"
    description = "Builds a self-contained bootstrap jar of the locator Gradle plugin for Studio-plugin offline distribution."
    archiveFileName.set("locator-gradle-plugin-bootstrap.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))

    from(sourceSets.named("main").get().output)
    from(
        configurations.named("runtimeClasspath").map { cp ->
            cp.filter { it.name.startsWith("asm") || it.name.contains("kotlin-stdlib") }
                .files.map { zipTree(it) }
        },
    )
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.register<JavaExec>("benchmarkComposeLocatorPerformance") {
    group = "verification"
    description = "Runs a synthetic Compose Locator source scanning and metadata performance benchmark."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("dev.codelocator.gradle.benchmark.ComposeLocatorPerformanceBenchmarkKt")
    args(layout.buildDirectory.dir("benchmarks/compose-locator-performance").get().asFile.absolutePath)
    listOf(
        "codelocator.performance.maxScanMs",
        "codelocator.performance.maxMetadataMs",
        "codelocator.performance.maxExtractionMs",
    ).forEach { propertyName ->
        val value = providers.systemProperty(propertyName)
            .orElse(providers.gradleProperty(propertyName))
        if (value.isPresent) {
            systemProperty(propertyName, value.get())
        }
    }
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
