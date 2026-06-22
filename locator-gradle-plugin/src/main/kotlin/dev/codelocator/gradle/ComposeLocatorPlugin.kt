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
            it.aggregateProjectPaths.set(listOf(project.path))
            it.outputPath.set(project.layout.projectDirectory.file(extension.sourceMapOutput))
        }

        val generateAsmSourceMapTask = project.tasks.register("generateComposeLocatorAsmSourceMap", GenerateComposeLocatorAsmSourceMapTask::class.java) {
            it.group = "compose locator"
            it.description = "Generates a compact Compose call-site source map for AGP ASM instrumentation."
            it.sourceDirectories.set(extension.sourceDirectories)
            it.aggregateProjectPaths.set(listOf(project.path))
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
            it.doLast {
                project.logger.lifecycle("Compose locator debug source identity is up to date.")
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
            it.aggregateProjectPaths.set(listOf(project.path))
        }
        generateAsmSourceMapTask.configure {
            it.aggregateProjectPaths.set(listOf(project.path))
        }

        configureDependencyMetadataExtraction(project, extractDependencyMetadataTask)

        project.tasks.findByName("preDebugBuild")?.dependsOn(generateSourceMapTask, generateAsmSourceMapTask, generateMetadataTask, generateStudioIndexTask)
        project.tasks.findByName("compileDebugKotlin")?.dependsOn(generateSourceMapTask, extractDependencyMetadataTask)
        project.tasks.findByName("assembleDebug")?.dependsOn(
            injectComposeLocatorMarkersTask,
        )

        val compileDebugKotlin = project.tasks.findByName("compileDebugKotlin")
        if (compileDebugKotlin != null) {
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
            }
            instrumentComposeBytecodeTask.configure {
                it.mustRunAfter(compileDebugKotlin)
                it.mustRunAfter(inspectComposeBytecodeTask)
            }
            inspectComposeBytecodeTask.configure {
                it.classesDirectory.set(project.layout.buildDirectory.dir("tmp/kotlin-classes/debug"))
            }
            instrumentComposeBytecodeTask.configure {
                it.classesDirectory.set(project.layout.buildDirectory.dir("tmp/kotlin-classes/debug"))
            }
            verifyCompilerSourceAlignmentTask.configure {
                it.dependsOn(compileDebugKotlin)
            }
        }
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

        val compilerPluginClasspath = configurations.detachedConfiguration(
            dependencies.create(extension.compilerPluginArtifact),
        ).apply {
            isTransitive = false
            isCanBeConsumed = false
            isCanBeResolved = true
        }
        val files = compilerPluginClasspath.resolve()
        return files.singleOrNull { it.extension == "jar" }
            ?: throw org.gradle.api.GradleException(
                "Unable to resolve Compose locator compiler plugin artifact '${extension.compilerPluginArtifact}'. " +
                    "Expected exactly one jar, got: ${files.joinToString { it.absolutePath }}",
            )
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
                collectComposeLocatorProjectDependencies(dependency.dependencyProject, output)
            }
    }

    private companion object {
        const val METADATA_ELEMENTS_CONFIGURATION = "composeLocatorMetadataElements"
        const val DEPENDENCY_METADATA_CONFIGURATION = "composeLocatorDependencyMetadata"
    }
}
