import java.util.zip.ZipFile

plugins {
    kotlin("jvm")
}

version = "0.1.6.3"

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
            file("H:/AndroidStudio"),
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

// stubs 防漂移守门：真实 AS 可用时，用其自带 javap 校验 stub 清单里的公开方法在真实平台上仍然存在。
// 背景：stub 签名只对齐编写时的 AS 版本，平台接口随 AS 升级漂移会导致运行时 NoSuchMethodError /
// IncompatibleClassChangeError（历史事故见 docs/release-engineering.md "Studio Plugin API Compatibility"）
tasks.register("verifyStudioStubsAlignment") {
    group = "verification"
    description = "Verifies stub-declared public APIs still exist in the real Android Studio platform jars."
    // 仅真实 AS 可用时才有对照物；纯 stubs 编译环境（CI）没有 javap 对照物，跳过
    onlyIf { !useIntellijStubs }

    doLast {
        val jbrBin = androidStudioHome.get().resolve("jbr/bin")
        val javap = listOf("javap.exe", "javap").map { jbrBin.resolve(it) }.firstOrNull { it.isFile }
            ?: file(System.getProperty("java.home")).resolve("bin/javap")
        check(javap.isFile) { "javap not found for stubs alignment check (looked in $jbrBin)" }

        // 类 -> stub 中声明、必须在真实平台上存在的公开方法名
        val expectations = mapOf(
            "com.intellij.ui.content.ContentFactory" to listOf("getInstance", "createContent"),
            "com.intellij.ui.content.Content" to listOf<String>(),
            "com.intellij.openapi.fileEditor.OpenFileDescriptor" to listOf("navigate"),
            "com.intellij.openapi.vfs.LocalFileSystem" to listOf("getInstance"),
            "com.intellij.openapi.wm.ToolWindowFactory" to listOf("createToolWindowContent"),
            "com.intellij.openapi.wm.ToolWindow" to listOf("getContentManager"),
            "com.intellij.openapi.wm.ToolWindowManager" to listOf("getInstance"),
            "com.intellij.openapi.actionSystem.AnAction" to listOf("actionPerformed"),
            "com.intellij.openapi.actionSystem.AnActionEvent" to listOf("getProject"),
            "com.intellij.openapi.project.DumbAware" to listOf<String>(),
            "com.intellij.openapi.project.Project" to listOf("getBasePath"),
            "com.intellij.openapi.diagnostic.Logger" to listOf("getInstance", "info", "warn"),
            "com.intellij.openapi.application.Application" to listOf("invokeLater"),
            "com.intellij.openapi.application.ApplicationManager" to listOf("getApplication"),
            "com.intellij.ui.CollectionListModel" to listOf("add", "remove", "replaceAll"),
            "com.intellij.ui.components.JBList" to listOf<String>(),
            "com.intellij.ui.components.JBScrollPane" to listOf<String>(),
            "com.intellij.ui.components.JBLabel" to listOf<String>(),
            "com.intellij.ui.components.JBTextArea" to listOf<String>(),
            "com.intellij.ui.content.ContentManager" to listOf("addContent"),
        )

        val realClasspath = androidStudioHome.get().resolve("lib").absolutePath.replace('\\', '/') + "/*"
        var failed = false
        expectations.forEach { (className, methods) ->
            val output = providers.exec {
                commandLine(javap.absolutePath, "-classpath", realClasspath, className)
            }.standardOutput.asText.get()
            if (output.isBlank()) {
                println("STUBS ALIGNMENT FAIL: $className not found in real AS platform jars")
                failed = true
                return@forEach
            }
            methods.forEach { method ->
                // 方法名出现在真实类签名里即认为对齐（不区分重载，避免 javap 输出解析过脆）
                val exists = Regex("\\b$method\\s*\\(").containsMatchIn(output) ||
                    Regex("\\b$method\\b").containsMatchIn(output)
                if (!exists) {
                    println("STUBS ALIGNMENT FAIL: $className.$method missing in real AS platform")
                    failed = true
                }
            }
        }
        check(!failed) { "Studio stubs drifted from real Android Studio platform APIs; update src/intellijStubs to match (see docs/release-engineering.md)" }
        println("Studio stubs aligned with real AS platform APIs (${expectations.size} classes checked)")
    }
}

tasks.register<Zip>("buildStudioPluginZip") {
    group = "build"
    description = "Builds a local Android Studio plugin ZIP without invoking the IntelliJ Gradle plugin."
    dependsOn(tasks.jar)
    dependsOn(copyLocatorBootstrapArtifacts)

    archiveBaseName.set("compose-code-locator")
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    from(tasks.jar) {
        into("compose-code-locator/lib")
    }

    from(configurations.runtimeClasspath) {
        into("compose-code-locator/lib")
    }

    // 构建期三产物（Gradle 插件 bootstrap jar、compiler jar、runtime AAR）随 zip 分发，
    // 由 LocatorProjectOpenListener 释放到工程 .gradle/compose-locator/，实现 build.gradle 零配置
    from(layout.buildDirectory.dir("locator-bootstrap")) {
        into("compose-code-locator/lib")
    }
}

// 编译器插件 Kotlin 2.2/2.4 是同一模块靠 -PkotlinCompilerApi 切换的薄壳变体，
// zip 分发需要两个变体 jar 各一份（installer 按固定名释放、Gradle 插件按宿主版本挑选），
// 因此各起一次独立 Gradle 进程构建并把产物重命名为变体名，两次构建必须串行避免同名产物互踩
val compilerPluginJarName = "locator-compiler-plugin-${rootProject.version}.jar"
val compilerPluginBuildLibs = rootProject.file("locator-compiler-plugin/build/libs")

/**
 * 注册一个 Kotlin 编译器变体的构建+重命名任务对：
 * Exec 起独立 Gradle 进程以 -PkotlinCompilerApi=<apiVersion> 构建薄壳变体，
 * Copy 把同名产物重命名为 locator-compiler-plugin-kotlin-<apiVersion>.jar；
 * 两次构建输出同名 jar 会互踩，调用方必须保证变体之间串行（mustRunAfter + dependsOn）
 *
 * @param apiVersion 编译器插件面向的 Kotlin API 版本（如 "2.2"、"2.4"）
 * @return Exec 构建任务与 Copy 重命名任务的 Provider 对
 */
fun registerCompilerPluginVariant(
    apiVersion: String,
): Pair<TaskProvider<Exec>, TaskProvider<Copy>> {
    val buildTask = tasks.register<Exec>("buildCompilerPluginVariantKotlin${apiVersion.replace(".", "")}") {
        group = "build"
        description = "Builds the Kotlin $apiVersion compiler plugin variant jar."
        workingDir = rootDir
        val wrapperScript = rootProject.file("gradlew.bat").takeIf { it.isFile } ?: rootProject.file("gradlew")
        val wrapperCommand = if (System.getProperty("os.name").contains("Windows")) {
            listOf("cmd", "/c", wrapperScript.absolutePath)
        } else {
            listOf(wrapperScript.absolutePath)
        }
        val javaBin = System.getProperty("java.home") + File.separator + "bin"
        environment("PATH", javaBin + File.pathSeparator + System.getenv("PATH"))
        commandLine(*wrapperCommand.plus(":locator-compiler-plugin:jar").plus("-PkotlinCompilerApi=$apiVersion").toTypedArray())
    }
    val renameTask = tasks.register<Copy>("renameCompilerPluginVariantKotlin${apiVersion.replace(".", "")}") {
        dependsOn(buildTask)
        from(compilerPluginBuildLibs) {
            include(compilerPluginJarName)
            exclude("*-sources.jar", "*-javadoc.jar")
        }
        rename { "locator-compiler-plugin-kotlin-$apiVersion.jar" }
        into(compilerPluginBuildLibs)
    }
    return buildTask to renameTask
}

// 2.4 必须等 2.2 产物重命名完再跑：两次构建输出同名 jar，后一次会覆盖前一次
val (buildCompilerPluginVariantKotlin22, renameCompilerPluginVariantKotlin22) = registerCompilerPluginVariant("2.2")
val (buildCompilerPluginVariantKotlin24, renameCompilerPluginVariantKotlin24) = registerCompilerPluginVariant("2.4")
buildCompilerPluginVariantKotlin24.configure {
    dependsOn(renameCompilerPluginVariantKotlin22)
    mustRunAfter(renameCompilerPluginVariantKotlin22)
}

// 收集 Gradle 侧构建期三产物到统一目录，供 buildStudioPluginZip 打进 zip 的 lib/
// - locator-gradle-plugin bootstrap jar：来自 included build（自带 asm/stdlib，init script 加载用）
// - locator-compiler-plugin jar：Kotlin 编译器插件，-Xplugin 消费
// - locator-runtime-android debug AAR：以文件依赖注入用户工程 debugImplementation
val copyLocatorBootstrapArtifacts by tasks.registering(Copy::class) {
    group = "build"
    description = "Collects locator Gradle-side artifacts to be bundled inside the Studio plugin ZIP."

    val bootstrapJar = rootProject.file("locator-gradle-plugin/build/libs/locator-gradle-plugin-bootstrap.jar")
    dependsOn(gradle.includedBuild("locator-gradle-plugin").task(":bootstrapJar"))
    // 变体 jar 需要独立进程逐个构建 + 重命名，zip 里必须同时包含 2.2/2.4 两个变体
    dependsOn(renameCompilerPluginVariantKotlin22)
    dependsOn(renameCompilerPluginVariantKotlin24)
    dependsOn(project(":locator-runtime").tasks.named("jar"))
    dependsOn(project(":locator-runtime-android").tasks.named("bundleDebugAar"))

    from(bootstrapJar)
    from(rootProject.file("locator-compiler-plugin/build/libs")) {
        // 只收带 Kotlin 行变体后缀的 jar（kotlin-2.2/kotlin-2.4）；不带后缀的主 jar 任务产物不随 zip 分发
        include("locator-compiler-plugin-kotlin-*.jar")
        exclude("*-sources.jar", "*-javadoc.jar")
    }
    // runtime core jar：AAR 文件依赖不带 pom 传递依赖，必须与 AAR 一起注入，否则 APK 缺 core 类直接崩溃。
    // 只收当前版本的 jar，避免本地残留旧版本产物重命名后同名冲突
    from(rootProject.file("locator-runtime/build/libs")) {
        include("locator-runtime-${rootProject.version}.jar")
    }
    from(rootProject.file("locator-runtime-android/build/outputs/aar")) {
        include("locator-runtime-android-debug.aar")
    }
    into(layout.buildDirectory.dir("locator-bootstrap"))

    // 统一重命名，installer 与 init script 按固定文件名释放/消费。
    // compiler 插件按 Kotlin 行保留变体后缀（kotlin-2.2/kotlin-2.4），宿主工程由 Gradle 插件按版本挑选
    rename { fileName ->
        when {
            fileName == bootstrapJar.name -> "locator-gradle-plugin-bootstrap.jar"
            Regex("locator-compiler-plugin-(kotlin-[0-9]+\\.[0-9]+)").find(fileName) != null ->
                "locator-compiler-plugin-" + Regex("locator-compiler-plugin-(kotlin-[0-9]+\\.[0-9]+)").find(fileName)!!.groupValues[1] + ".jar"
            fileName.startsWith("locator-compiler-plugin") -> "locator-compiler-plugin.jar"
            fileName.startsWith("locator-runtime-android") -> "locator-runtime-android.aar"
            fileName.startsWith("locator-runtime") -> "locator-runtime.jar"
            else -> fileName
        }
    }
}

tasks.register("verifyStudioPluginPackaging") {
    group = "verification"
    description = "Verifies the locally packaged Android Studio plugin ZIP has installable structure."
    dependsOn(tasks.named("buildStudioPluginZip"))
    // 真实 AS 可用时顺带校验 stubs 未漂移；CI（纯 stubs）环境自动跳过
    dependsOn(tasks.named("verifyStudioStubsAlignment"))

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
        listOf(
            "locator-gradle-plugin-bootstrap.jar",
            "locator-compiler-plugin-kotlin-2.2.jar",
            "locator-compiler-plugin-kotlin-2.4.jar",
            "locator-runtime.jar",
            "locator-runtime-android.aar",
        ).forEach { artifact ->
            check("compose-code-locator/lib/$artifact" in entries) {
                "Plugin ZIP is missing the locator bootstrap artifact: $artifact (zip=$zipFile)"
            }
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
