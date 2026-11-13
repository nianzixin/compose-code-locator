package dev.codelocator.gradle

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.api.variant.Variant
import org.gradle.api.artifacts.Configuration
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.TaskProvider

class ComposeLocatorPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "composeLocator",
            ComposeLocatorExtension::class.java,
        )
        project.configureAndroidDslBeforeVariants(extension)
        val metadataElementsConfiguration = project.configurations.create(METADATA_ELEMENTS_CONFIGURATION) {
            it.isCanBeConsumed = true
            it.isCanBeResolved = false
            it.isVisible = false
            it.description = "Compose Locator source metadata produced by this project."
        }
        val dependencyMetadataConfiguration = project.configurations.create(DEPENDENCY_METADATA_CONFIGURATION) {
            it.isCanBeConsumed = false
            it.isCanBeResolved = true
            it.isVisible = false
            it.isTransitive = true
            it.description = "Compose Locator source metadata consumed from project dependencies."
        }

        val generateSourceMapTask = project.tasks.register("generateComposeLocatorSourceMap", GenerateComposeLocatorSourceMapTask::class.java) {
            it.group = "compose locator"
            it.description = "Generates a Compose source map for discovered @Composable functions."
            it.sourceDirectories.set(extension.sourceDirectories)
            it.outputPath.set(project.layout.projectDirectory.file(extension.sourceMapOutput))
        }

        val generateAsmSourceMapTask = project.tasks.register("generateComposeLocatorAsmSourceMap", GenerateComposeLocatorAsmSourceMapTask::class.java) {
            it.group = "compose locator"
            it.description = "Generates a compact Compose call-site source map for AGP ASM instrumentation."
            it.sourceDirectories.set(extension.sourceDirectories)
            it.outputFile.set(project.layout.projectDirectory.file(extension.asmSourceMapOutput))
        }

        val generateMetadataTask = project.tasks.register("generateComposeLocatorMetadata", GenerateComposeLocatorMetadataTask::class.java) {
            it.group = "compose locator"
            it.description = "Generates distributable Compose Locator source metadata for dependency aggregation."
            it.sourceDirectories.set(extension.sourceDirectories)
            it.producerPath.set(project.path)
            it.outputFile.set(project.layout.projectDirectory.file(extension.metadataOutput))
        }
        val extractDependencyMetadataTask = project.tasks.register("extractComposeLocatorDependencyMetadata", ExtractComposeLocatorDependencyMetadataTask::class.java) {
            it.group = "compose locator"
            it.description = "Extracts Compose Locator metadata from dependency AAR/JAR artifacts."
            it.outputDirectory.set(project.layout.buildDirectory.dir("intermediates/composeLocator/dependencyMetadata"))
        }
        val generateStudioIndexTask = project.tasks.register("generateComposeLocatorStudioIndex", GenerateComposeLocatorStudioIndexTask::class.java) {
            it.group = "compose locator"
            it.description = "Generates the local Studio source index used to resolve sourceId values."
            it.sourceMapFile.set(generateSourceMapTask.flatMap { task -> task.outputPath })
            it.dependencyMetadataFiles.from(dependencyMetadataConfiguration)
            it.dependencyMetadataFiles.from(extractDependencyMetadataTask.flatMap { task -> task.outputDirectory })
            it.outputDirectory.set(project.layout.projectDirectory.dir(extension.studioIndexOutput))
        }
        metadataElementsConfiguration.outgoing.artifact(generateMetadataTask.flatMap { it.outputFile }) {
            it.builtBy(generateMetadataTask)
            it.type = "json"
        }

        val inspectComposeBytecodeTask = project.tasks.register("inspectComposeBytecode", InspectComposeBytecodeTask::class.java) {
            it.group = "compose locator"
            it.description = "Inspects compiled debug classes to prepare future Compose bytecode instrumentation."
            it.includePackages.set(extension.includePackages)
            it.outputFile.set(project.layout.projectDirectory.file(extension.bytecodeInspectionOutput))
        }

        val instrumentComposeBytecodeTask = project.tasks.register("instrumentComposeBytecode", InstrumentComposeBytecodeTask::class.java) {
            it.group = "compose locator"
            it.description = "Instruments compiled debug Compose methods with LocatorRuntime enter/exit boundaries."
            it.includePackages.set(extension.includePackages)
            it.sourceDirectories.set(extension.sourceDirectories)
            it.outputFile.set(project.layout.projectDirectory.file(extension.bytecodeInstrumentationOutput))
        }
        val verifyCompilerSourceAlignmentTask = project.tasks.register(
            "verifyComposeCompilerSourceAlignment",
            VerifyComposeCompilerSourceAlignmentTask::class.java,
        ) {
            it.group = "verification"
            it.description = "Verifies compiler-injected Compose source IDs align with generated source metadata."
            it.compilerReportFile.set(project.layout.projectDirectory.file(extension.compilerPluginReportOutput))
            it.sourceMapFile.set(generateSourceMapTask.flatMap { task -> task.outputPath })
            it.metadataFile.set(generateMetadataTask.flatMap { task -> task.outputFile })
            it.dependsOn(generateMetadataTask, generateSourceMapTask)
        }

        val injectComposeLocatorMarkersTask = project.tasks.register("injectComposeLocatorMarkers") {
            it.group = "compose locator"
            it.description = "Generates Compose locator metadata and injects debug-only source boundaries."
            it.dependsOn(
                generateSourceMapTask,
                generateAsmSourceMapTask,
                generateMetadataTask,
                generateStudioIndexTask,
            )
            it.doLast { task ->
                task.logger.lifecycle("Compose locator debug source identity is up to date.")
            }
        }

        project.gradle.projectsEvaluated {
            if (extension.enabled) {
                project.configureComposeLocatorDependencyMetadata(dependencyMetadataConfiguration)
            }
        }

        project.afterEvaluate {
            if (!extension.enabled) {
                project.logger.lifecycle("Compose locator disabled for ${project.path}")
                return@afterEvaluate
            }
            wireIntoDebugBuild(
                project,
                extension,
                generateSourceMapTask,
                generateAsmSourceMapTask,
                generateMetadataTask,
                generateStudioIndexTask,
                extractDependencyMetadataTask,
                dependencyMetadataConfiguration,
                inspectComposeBytecodeTask,
                instrumentComposeBytecodeTask,
                verifyCompilerSourceAlignmentTask,
                injectComposeLocatorMarkersTask,
            )
        }
    }

    private fun wireIntoDebugBuild(
        project: Project,
        extension: ComposeLocatorExtension,
        generateSourceMapTask: TaskProvider<GenerateComposeLocatorSourceMapTask>,
        generateAsmSourceMapTask: TaskProvider<GenerateComposeLocatorAsmSourceMapTask>,
        generateMetadataTask: TaskProvider<GenerateComposeLocatorMetadataTask>,
        generateStudioIndexTask: TaskProvider<GenerateComposeLocatorStudioIndexTask>,
        extractDependencyMetadataTask: TaskProvider<ExtractComposeLocatorDependencyMetadataTask>,
        dependencyMetadataConfiguration: Configuration,
        inspectComposeBytecodeTask: TaskProvider<InspectComposeBytecodeTask>,
        instrumentComposeBytecodeTask: TaskProvider<InstrumentComposeBytecodeTask>,
        verifyCompilerSourceAlignmentTask: TaskProvider<VerifyComposeCompilerSourceAlignmentTask>,
        injectComposeLocatorMarkersTask: TaskProvider<Task>,
    ) {
        generateSourceMapTask.configure {
            it.scanRoots.set(project.composeLocatorScanRoots())
            it.pathRootDir.set(project.rootProject.projectDir.absolutePath)
            it.kotlinSourceFiles.from(project.composeLocatorKotlinSourceFiles())
        }
        generateAsmSourceMapTask.configure {
            it.scanRoots.set(project.composeLocatorScanRoots())
            it.pathRootDir.set(project.rootProject.projectDir.absolutePath)
            it.kotlinSourceFiles.from(project.composeLocatorKotlinSourceFiles())
        }
        generateMetadataTask.configure {
            it.producerProjectDir.set(project.projectDir.absolutePath)
            it.pathRootDir.set(project.rootProject.projectDir.absolutePath)
            it.kotlinSourceFiles.from(project.composeLocatorKotlinSourceFiles())
        }

        configureDependencyMetadataExtraction(project, extractDependencyMetadataTask)

        // 兼容 productFlavor：任务名形如 preDevMangoDebugBuild / compileDevMangoDebugKotlin / assembleDevMangoDebug
        project.tasks.matching { it.name.startsWith("pre") && it.name.endsWith("DebugBuild") }
            .all { it.dependsOn(generateSourceMapTask, generateAsmSourceMapTask, generateMetadataTask, generateStudioIndexTask) }
        project.tasks.matching { it.name.startsWith("assemble") && it.name.endsWith("Debug") && !it.name.contains("Test") }
            .all { it.dependsOn(injectComposeLocatorMarkersTask) }

        // 仅主源码集的 Debug Kotlin 编译任务，排除 AndroidTest/UnitTest
        project.tasks.matching { it.name.startsWith("compile") && it.name.endsWith("DebugKotlin") && !it.name.contains("Test") }
            .all { compileDebugKotlin ->
                val variantName = compileDebugKotlin.name
                    .removePrefix("compile")
                    .removeSuffix("Kotlin")
                    .replaceFirstChar { it.lowercase() }
                compileDebugKotlin.dependsOn(generateSourceMapTask, extractDependencyMetadataTask)
                if (extension.generateRuntimeMarkers) {
                    configureCompilerPlugin(project, extension, compileDebugKotlin)
                }
                if (extension.enableBytecodeInspection) {
                    compileDebugKotlin.finalizedBy(inspectComposeBytecodeTask)
                    injectComposeLocatorMarkersTask.configure {
                        it.dependsOn(inspectComposeBytecodeTask)
                    }
                }
                inspectComposeBytecodeTask.configure {
                    it.mustRunAfter(compileDebugKotlin)
                    it.classesDirectory.set(project.layout.buildDirectory.dir("tmp/kotlin-classes/$variantName"))
                }
                instrumentComposeBytecodeTask.configure {
                    it.mustRunAfter(compileDebugKotlin)
                    it.mustRunAfter(inspectComposeBytecodeTask)
                    it.classesDirectory.set(project.layout.buildDirectory.dir("tmp/kotlin-classes/$variantName"))
                }
                verifyCompilerSourceAlignmentTask.configure {
                    it.dependsOn(compileDebugKotlin)
                }
            }
    }

    /** 配置期解析源码扫描根，执行期任务不再访问 Project */
    private fun Project.composeLocatorScanRoots(): List<ComposeLocatorScanRoot> {
        val projectDir = layout.projectDirectory.asFile
        return composeLocatorSourceDirs().map { ComposeLocatorScanRoot(projectDir, it) }
    }

    private fun Project.composeLocatorKotlinSourceFiles(): List<org.gradle.api.file.FileTree> {
        return composeLocatorSourceDirs().map { sourceDir ->
            fileTree(sourceDir) { it.include("**/*.kt") }
        }
    }

    private fun Project.composeLocatorSourceDirs(): List<String> {
        val extension = extensions.findByType(ComposeLocatorExtension::class.java)
        return extension?.sourceDirectories ?: listOf("src/main/kotlin", "src/debug/kotlin")
    }

    private fun configureCompilerPlugin(
        project: Project,
        extension: ComposeLocatorExtension,
        compileDebugKotlin: Task,
    ) {
        val pluginJar = project.resolveCompilerPluginJar(extension, compileDebugKotlin)
        val pluginId = "dev.codelocator.compose-locator.compiler"
        val reportPath = project.layout.projectDirectory.file(extension.compilerPluginReportOutput).asFile.absolutePath
        val args = buildList {
            add("-Xplugin=${pluginJar.absolutePath}")
            add("-P=plugin:$pluginId:enabled=true")
            add("-P=plugin:$pluginId:reportPath=$reportPath")
            add("-P=plugin:$pluginId:projectDir=${project.rootProject.projectDir.absolutePath}")
            extension.includePackages.forEach { includePackage ->
                add("-P=plugin:$pluginId:includePackage=$includePackage")
            }
        }
        compileDebugKotlin.configureFreeCompilerArgs(args)
        project.logger.lifecycle("Compose locator compiler plugin enabled for ${compileDebugKotlin.path}")
    }

    private fun Project.resolveCompilerPluginJar(
        extension: ComposeLocatorExtension,
        compileDebugKotlin: Task,
    ): java.io.File {
        // Studio 插件 bootstrap 模式：优先使用释放到工程 .gradle/compose-locator/ 的 compiler jar，免 maven 解析
        val bootstrapCompilerJar = composeLocatorBootstrapDir()?.let { java.io.File(it, ComposeLocatorBootstrap.COMPILER_ARTIFACT_FILE_NAME) }
        if (bootstrapCompilerJar != null && bootstrapCompilerJar.isFile) {
            return bootstrapCompilerJar
        }

        val localCompilerPluginJarTask = rootProject.tasks.findByPath(":locator-compiler-plugin:jar")
        if (localCompilerPluginJarTask != null) {
            compileDebugKotlin.dependsOn(localCompilerPluginJarTask)
            return rootProject
                .project(":locator-compiler-plugin")
                .layout
                .buildDirectory
                .file("libs/locator-compiler-plugin-${rootProject.project(":locator-compiler-plugin").version}.jar")
                .get()
                .asFile
        }

        val compilerPluginArtifact = extension.compilerPluginArtifact.ifBlank {
            // 自动模式：按宿主 Kotlin 版本映射到对应编译器变体产物
            resolveCompilerPluginArtifactForHost(project)
        }
        val compilerPluginClasspath = configurations.detachedConfiguration(
            dependencies.create(compilerPluginArtifact),
        ).apply {
            isTransitive = false
            isCanBeConsumed = false
            isCanBeResolved = true
        }
        val files = compilerPluginClasspath.resolve()
        // 同一坐标下还有 sources/javadoc jar，带 -sources/-javadoc 后缀的不是主产物
        return files.singleOrNull { it.extension == "jar" && !it.name.contains("-sources") && !it.name.contains("-javadoc") }
            ?: throw org.gradle.api.GradleException(
                "Unable to resolve Compose locator compiler plugin artifact '$compilerPluginArtifact'. " +
                    "Expected exactly one main jar, got: ${files.joinToString { it.absolutePath }}",
            )
    }

    /**
     * 按宿主 Kotlin 版本自动选择编译器插件变体坐标。
     * 从已应用的 Kotlin 插件类的 jar 位置提取版本号（如 kotlin-gradle-plugin-2.2.10.jar → 2.2.10），
     * 映射到大版本行后拼接变体坐标；宿主低于最低支持行时取最低行并告警
     */
    private fun resolveCompilerPluginArtifactForHost(project: Project): String {
        val hostKotlinVersion = detectHostKotlinVersion(project)
        val suffix = pickCompilerApiSuffix(hostKotlinVersion)
        val version = DEFAULT_COMPILER_PLUGIN_VERSION
        project.logger.lifecycle(
            "Compose locator compiler plugin artifact auto-selected: kotlin=$hostKotlinVersion variant=$suffix",
        )
        return "$PLUGIN_GROUP:locator-compiler-plugin-$suffix:$version"
    }

    /**
     * 探测宿主工程的 Kotlin 版本：优先从本工程已应用的 Kotlin 插件类加载来源定位
     * kotlin-gradle-plugin jar，其次回退到 rootProject；两者都探不到返回 null
     */
    private fun detectHostKotlinVersion(project: Project): String? {
        val pluginIds = listOf("org.jetbrains.kotlin.android", "org.jetbrains.kotlin.jvm")
        val scopes = listOf(project, project.rootProject)
        for (scope in scopes) {
            for (pluginId in pluginIds) {
                val plugin = scope.plugins.findPlugin(pluginId) ?: continue
                val location = plugin.javaClass.protectionDomain?.codeSource?.location ?: continue
                // kotlin-gradle-plugin-<version>.jar → 提取版本号
                Regex("kotlin-gradle-plugin-([0-9][^/\\\\]*)\\.jar")
                    .find(location.path)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let { return it }
            }
        }
        return null
    }

    /**
     * 由宿主 Kotlin 版本选择变体后缀：取不高于宿主大版本行的最大支持行，
     * 探测失败时回退最低行（当前基线）并告警
     */
    private fun pickCompilerApiSuffix(hostKotlinVersion: String?): String {
        // 与 locator-compiler-plugin/build.gradle.kts 的 compilerApiVariants 保持一致，升序排列
        val supportedLines = listOf("2.2", "2.4")
        val versionLineRegex = Regex("""(\d+)\.(\d+)""")

        /**
         * 大版本行换算为可比较数值，如 "2.4" → 204；解析失败返回 null
         */
        fun lineCode(line: String): Int? = versionLineRegex.find(line)
            ?.let { it.groupValues[1].toInt() * 100 + it.groupValues[2].toInt() }

        val hostLine = hostKotlinVersion?.let(::lineCode)
        if (hostLine == null) {
            return "kotlin-${supportedLines.first()}".also {
                println("Compose locator: unable to detect host Kotlin version, falling back to compiler variant $it")
            }
        }
        // 取不高于宿主大版本行的最大支持行；宿主低于最低支持行时仍取最低行，尽量保持可用
        val matched = supportedLines.lastOrNull { line -> lineCode(line)?.let { it <= hostLine } == true }
        return "kotlin-${matched ?: supportedLines.first()}"
    }

    @Suppress("UNCHECKED_CAST")
    private fun Task.configureFreeCompilerArgs(args: List<String>) {
        val kotlinOptions = runCatching {
            javaClass.methods.firstOrNull { it.name == "getKotlinOptions" && it.parameterCount == 0 }?.invoke(this)
        }.getOrNull()
        if (kotlinOptions != null) {
            val getArgs = kotlinOptions.javaClass.methods.firstOrNull { it.name == "getFreeCompilerArgs" && it.parameterCount == 0 }
            val setArgs = kotlinOptions.javaClass.methods.firstOrNull { it.name == "setFreeCompilerArgs" && it.parameterCount == 1 }
            if (getArgs != null && setArgs != null) {
                val current = getArgs.invoke(kotlinOptions) as? List<String> ?: emptyList()
                setArgs.invoke(kotlinOptions, (current + args).distinct())
                return
            }
        }

        val compilerOptions = runCatching {
            javaClass.methods.firstOrNull { it.name == "getCompilerOptions" && it.parameterCount == 0 }?.invoke(this)
        }.getOrNull() ?: return
        val freeCompilerArgs = compilerOptions.javaClass.methods
            .firstOrNull { it.name == "getFreeCompilerArgs" && it.parameterCount == 0 }
            ?.invoke(compilerOptions)
            ?: return
        val addAll = freeCompilerArgs.javaClass.methods.firstOrNull { it.name == "addAll" }
        if (addAll != null) {
            addAll.invoke(freeCompilerArgs, args)
        }
    }

    private fun Project.configureAndroidDslBeforeVariants(extension: ComposeLocatorExtension) {
        plugins.withId("com.android.application") {
            val androidComponents = extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
            androidComponents.finalizeDsl { android ->
                if (!extension.enabled) return@finalizeDsl
            }
            androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
                configureAsmTransform(extension, variant)
            }
        }
        plugins.withId("com.android.library") {
            val androidComponents = extensions.getByType(LibraryAndroidComponentsExtension::class.java)
            androidComponents.finalizeDsl { android ->
                if (!extension.enabled) return@finalizeDsl
            }
            androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
                configureAsmTransform(extension, variant)
            }
        }
    }

    private fun Project.configureAsmTransform(
        extension: ComposeLocatorExtension,
        variant: Variant,
    ) {
        if (!extension.enabled || !extension.enableBytecodeInstrumentation) return
        val asmSourceMap = tasks
            .named("generateComposeLocatorAsmSourceMap", GenerateComposeLocatorAsmSourceMapTask::class.java)
            .flatMap { it.outputFile }
        variant.instrumentation.transformClassesWith(
            ComposeLocatorAsmClassVisitorFactory::class.java,
            InstrumentationScope.PROJECT,
        ) { params ->
            params.includePackages.set(extension.includePackages)
            params.sourceMapFile.set(asmSourceMap)
        }
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS,
        )
    }

    private fun configureDependencyMetadataExtraction(
        project: Project,
        extractDependencyMetadataTask: TaskProvider<ExtractComposeLocatorDependencyMetadataTask>,
    ) {
        val runtimeClasspath = project.configurations.findByName("debugRuntimeClasspath")
            ?: project.configurations.findByName("runtimeClasspath")
            ?: return
        val rawArtifacts = runtimeClasspath.incoming.artifactView { view ->
            view.isLenient = true
        }.files
        extractDependencyMetadataTask.configure {
            it.dependencyArtifacts.from(rawArtifacts)
        }
    }

    private fun Project.configureComposeLocatorDependencyMetadata(configuration: Configuration) {
        composeLocatorDependencyProjectPaths().forEach { dependencyPath ->
            configuration.dependencies.add(
                dependencies.project(
                    mapOf(
                        "path" to dependencyPath,
                        "configuration" to METADATA_ELEMENTS_CONFIGURATION,
                    ),
                ),
            )
        }
    }

    private fun Project.composeLocatorDependencyProjectPaths(): List<String> {
        val currentAndDependencies = linkedSetOf<Project>()
        collectComposeLocatorProjectDependencies(this, currentAndDependencies)
        return currentAndDependencies
            .filter { candidate ->
                candidate.path != path &&
                    candidate.plugins.hasPlugin("io.github.nianzixin.compose-locator")
            }
            .map { it.path }
            .distinct()
    }

    private fun collectComposeLocatorProjectDependencies(
        project: Project,
        output: MutableSet<Project>,
    ) {
        if (!output.add(project)) return
        listOf(
            "api",
            "implementation",
            "compileOnly",
            "debugApi",
            "debugImplementation",
            "debugCompileOnly",
        ).mapNotNull(project.configurations::findByName)
            .flatMap { configuration -> configuration.dependencies.withType(ProjectDependency::class.java).toList() }
            .forEach { dependency ->
                // Gradle 9 移除 ProjectDependency.dependencyProject，用 path 反查工程
                collectComposeLocatorProjectDependencies(project.project(dependency.path), output)
            }
    }

    private companion object {
        const val METADATA_ELEMENTS_CONFIGURATION = "composeLocatorMetadataElements"
        const val DEPENDENCY_METADATA_CONFIGURATION = "composeLocatorDependencyMetadata"

        /** 编译器插件产物的 group 与默认版本号，与 locator-compiler-plugin 发布流水线保持一致 */
        const val PLUGIN_GROUP = "io.github.nianzixin"
        const val DEFAULT_COMPILER_PLUGIN_VERSION = "0.1.2"
    }
}
