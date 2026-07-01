import java.util.zip.ZipFile

plugins {
    kotlin("jvm")
}

version = "0.1.4"

kotlin {
    jvmToolchain(17)
}

val androidStudioHome = file("/Applications/Android Studio.app/Contents")
val androidStudioLib = fileTree(androidStudioHome.resolve("lib")) {
    include("*.jar")
}

val intellijStubs = sourceSets.create("intellijStubs") {
    java.srcDir("src/intellijStubs/java")
}

val forceIntellijStubs = providers.gradleProperty("codelocator.studio.useStubs")
    .map(String::toBoolean)
    .orElse(false)
val useIntellijStubs = forceIntellijStubs.get() || androidStudioLib.files.isEmpty()

dependencies {
    implementation(kotlin("stdlib"))
    if (useIntellijStubs) {
        compileOnly(intellijStubs.output)
    } else {
        compileOnly(androidStudioLib)
    }
}

if (useIntellijStubs) {
    tasks.named("compileKotlin") {
        dependsOn(tasks.named("compileIntellijStubsJava"))
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

tasks.register<Zip>("buildStudioPluginZip") {
    group = "build"
    description = "Builds a local Android Studio plugin ZIP without invoking the IntelliJ Gradle plugin."
    dependsOn(tasks.jar)

    archiveBaseName.set("compose-code-locator")
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    from(tasks.jar) {
        into("compose-code-locator/lib")
    }

    from(configurations.runtimeClasspath) {
        into("compose-code-locator/lib")
    }
}

tasks.register("verifyStudioPluginPackaging") {
    group = "verification"
    description = "Verifies the locally packaged Android Studio plugin ZIP has installable structure."
    dependsOn(tasks.named("buildStudioPluginZip"))

    doLast {
        val zipFile = layout.buildDirectory
            .file("distributions/compose-code-locator-${project.version}.zip")
            .get()
            .asFile
        check(zipFile.isFile) {
            "Missing Studio plugin ZIP at ${zipFile.absolutePath}"
        }
        val entries = ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().map { it.name }.toSet()
        }
        check("compose-code-locator/lib/studio-plugin-${project.version}.jar" in entries) {
            "Plugin ZIP is missing the studio plugin jar: $zipFile"
        }
        check(entries.any { it.startsWith("compose-code-locator/lib/kotlin-stdlib") && it.endsWith(".jar") }) {
            "Plugin ZIP is missing Kotlin stdlib runtime jars: $zipFile"
        }
        val pluginJar = layout.buildDirectory.file("libs/studio-plugin-${project.version}.jar").get().asFile
        val pluginEntries = ZipFile(pluginJar).use { zip ->
            zip.entries().asSequence().map { it.name }.toSet()
        }
        check("META-INF/plugin.xml" in pluginEntries) {
            "Plugin jar is missing META-INF/plugin.xml: ${pluginJar.absolutePath}"
        }
        val pluginXml = ZipFile(pluginJar).use { zip ->
            zip.getInputStream(zip.getEntry("META-INF/plugin.xml")).bufferedReader().use { it.readText() }
        }
        check("<version>${project.version}</version>" in pluginXml) {
            "Marketplace plugin descriptor is missing <version>${project.version}</version>"
        }
        check("<idea-version " in pluginXml && "since-build=" in pluginXml) {
            "Marketplace plugin descriptor is missing <idea-version since-build=...>"
        }
        println("Studio plugin ZIP packaging verified at ${zipFile.absolutePath}")
    }
}

tasks.register<JavaExec>("runStudioDemo") {
    group = "application"
    description = "Runs the local Studio-side navigation demo without launching Android Studio."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.codelocator.studio.demo.StudioDemoKt")
}

tasks.register<JavaExec>("verifyStudioProtocol") {
    group = "verification"
    description = "Verifies Studio-side locator protocol parsing without external test dependencies."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.codelocator.studio.demo.ProtocolVerificationKt")
}

tasks.register<JavaExec>("verifyStudioDeviceFlow") {
    group = "verification"
    description = "Verifies the Studio-side device screenshot, hit-test, and navigation planning flow against a running demo app."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.codelocator.studio.demo.StudioDeviceFlowVerificationKt")
    args(rootProject.projectDir.absolutePath)
}
