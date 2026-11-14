package dev.codelocator.gradle

import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.ProjectDependency

class ComposeLocatorTeamPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "teamComposeLocator",
            ComposeLocatorTeamExtension::class.java,
        )

        project.afterEvaluate {
            if (extension.autoAddDebugRuntime) {
                addRuntimeDependencies(project, extension)
            }
        }

        configureMinSdkOverrideLibrary(project)

        // 按类应用核心插件：按 id 应用依赖插件仓库的 marker 解析，在按需加载（applied script）场景下不可用
        project.pluginManager.apply(ComposeLocatorPlugin::class.java)
    }

    private fun addRuntimeDependencies(
        project: Project,
        extension: ComposeLocatorTeamExtension,
    ) {
        project.plugins.withId("com.android.application") {
            val debugImplementation = project.configurations.findByName("debugImplementation")
                ?: return@withId
            project.addRuntimeDependencyIfMissing(
                configurationName = "debugImplementation",
                configuration = debugImplementation,
                extension = extension,
            )
        }
        project.plugins.withId("com.android.library") {
            val compileOnly = project.configurations.findByName("compileOnly")
                ?: return@withId
            project.addRuntimeDependencyIfMissing(
                configurationName = "compileOnly",
                configuration = compileOnly,
                extension = extension,
            )
        }
    }

    private fun Project.addRuntimeDependencyIfMissing(
        configurationName: String,
        configuration: Configuration,
        extension: ComposeLocatorTeamExtension,
    ) {
        val alreadyAdded = configuration.dependencies.any { dependency ->
            dependency.group == "io.github.nianzixin" && dependency.name == "locator-runtime-android"
        } || configuration.dependencies.any { dependency ->
            dependency is ProjectDependency &&
                dependency.path == ":locator-runtime-android"
        } || configuration.dependencies.any { dependency ->
            // bootstrap（Studio 插件释放）模式注入的是文件依赖，避免重复注入
            dependency is FileCollectionDependency &&
                dependency.files.any { it.name.startsWith(ComposeLocatorBootstrap.RUNTIME_ARTIFACT_FILE_BASE_NAME) }
        }
        if (alreadyAdded) return

        val dependencyNotation = resolveDependencyNotation(extension)
        dependencies.add(configurationName, dependencyNotation)
        logger.lifecycle("Compose Locator team plugin added $configurationName runtime dependency to $path")
    }

    private fun Project.resolveDependencyNotation(
        extension: ComposeLocatorTeamExtension,
    ): Any = when {
        // Studio 插件 bootstrap 模式：优先使用释放到工程内的 runtime AAR 文件依赖，断网可用。
        // AAR 文件依赖不带 pom 传递依赖，core（locator-runtime）必须以 jar 一并注入，否则运行时 NoClassDefFoundError
        composeLocatorBootstrapDir()?.let { File(it, ComposeLocatorBootstrap.RUNTIME_ARTIFACT_FILE_NAME) }?.isFile == true -> {
            val bootstrapDir = composeLocatorBootstrapDir()!!
            val coreJar = File(bootstrapDir, ComposeLocatorBootstrap.RUNTIME_CORE_ARTIFACT_FILE_NAME)
            if (coreJar.isFile) {
                files(bootstrapDir.resolve(ComposeLocatorBootstrap.RUNTIME_ARTIFACT_FILE_NAME), coreJar)
            } else {
                logger.warn(
                    "Compose Locator: bootstrap 目录缺少 ${ComposeLocatorBootstrap.RUNTIME_CORE_ARTIFACT_FILE_NAME}，" +
                        "运行时 core 类不会进入 APK；请重新打开工程让 Studio 插件重新释放",
                )
                files(bootstrapDir.resolve(ComposeLocatorBootstrap.RUNTIME_ARTIFACT_FILE_NAME))
            }
        }

        extension.preferIncludedBuildRuntimeProject && rootProject.findProject(":locator-runtime-android") != null ->
            dependencies.project(mapOf("path" to ":locator-runtime-android"))

        else -> extension.runtimeArtifact
    }

    // minSdk < 26 时 runtime（minSdk 26）会触发 manifest merger 的 overrideLibrary 报错；
    // 用户未提供 src/debug/AndroidManifest.xml 时自动创建带 overrideLibrary 声明的 debug manifest。
    // 放弃走 sourceSet 反射接线（AGP 8 下生成的 build 目录 manifest 不参与 merger），直接落盘最可靠
    private fun configureMinSdkOverrideLibrary(project: Project) {
        project.plugins.withId("com.android.application") {
            val components = project.extensions.findByType(
                com.android.build.api.variant.ApplicationAndroidComponentsExtension::class.java,
            ) ?: return@withId
            components.finalizeDsl { android ->
                val minSdk = android.defaultConfig.minSdk ?: return@finalizeDsl
                if (minSdk >= RUNTIME_MIN_SDK) return@finalizeDsl

                val userDebugManifest = File(
                    project.projectDir,
                    "src/debug/AndroidManifest.xml",
                )
                if (userDebugManifest.isFile) {
                    val text = userDebugManifest.readText()
                    if (!text.contains("overrideLibrary")) {
                        project.logger.warn(
                            "Compose Locator: 项目 minSdk=$minSdk < $RUNTIME_MIN_SDK，且 src/debug/AndroidManifest.xml 未声明 " +
                                "<uses-sdk tools:overrideLibrary=\"dev.codelocator.runtime.android\" />，" +
                                "debug 构建可能因 manifest 合并失败；请按 README 补充该声明",
                        )
                    }
                    return@finalizeDsl
                }

                val debugManifestDir = File(project.projectDir, "src/debug")
                debugManifestDir.mkdirs()
                val generatedManifest = File(debugManifestDir, "AndroidManifest.xml")
                generatedManifest.writeText(
                    """
                    |<!-- 由 Compose Locator 自动生成：minSdk=$minSdk < $RUNTIME_MIN_SDK，放行 minSdk 26 的 debug runtime -->
                    |<manifest xmlns:tools="http://schemas.android.com/tools">
                    |    <uses-sdk tools:overrideLibrary="dev.codelocator.runtime.android" />
                    |</manifest>
                    """.trimMargin(),
                )
                project.logger.lifecycle(
                    "Compose Locator: minSdk < $RUNTIME_MIN_SDK，已自动创建带 overrideLibrary 的 debug manifest（${generatedManifest.path}）",
                )
            }
        }
    }

    private companion object {
        /** locator-runtime-android 声明的 minSdk，低于此值需要 overrideLibrary */
        const val RUNTIME_MIN_SDK = 23
    }
}
