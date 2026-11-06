import java.util.zip.ZipFile

plugins {
    kotlin("jvm")
}

version = "0.1.5"

kotlin {
    jvmToolchain(17)
}

// 优先使用本机真实 Android Studio 平台编译，避免 stubs 与运行时链接不符。
// 安装路径不在常见位置时，在全局 ~/.gradle/gradle.properties 配 codelocator.studio.home=<AS 安装目录>，
// 或命令行传 -Pcodelocator.studio.home=...；都找不到才退回 stubs 编译（产物链接与运行时可能不符）
val androidStudioHome = providers.gradleProperty("codelocator.studio.home")
    .map(::file)
    .orElse(
        listOf(
            file("/Applications/Android Studio.app/Contents"),
            file("C:/Program Files/Android/Android Studio"),
        ).firstOrNull { it.isDirectory } ?: file("/Applications/Android Studio.app/Contents")
    )
val androidStudioLib = fileTree(androidStudioHome.get().resolve("lib")) {
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
