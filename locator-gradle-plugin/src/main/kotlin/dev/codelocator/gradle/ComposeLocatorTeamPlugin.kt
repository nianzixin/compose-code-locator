package dev.codelocator.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
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
        }
        if (alreadyAdded) return

        val dependencyNotation = if (extension.preferIncludedBuildRuntimeProject && rootProject.findProject(":locator-runtime-android") != null) {
            dependencies.project(mapOf("path" to ":locator-runtime-android"))
        } else {
            extension.runtimeArtifact
        }
        dependencies.add(configurationName, dependencyNotation)
        logger.lifecycle("Compose Locator team plugin added $configurationName runtime dependency to $path")
    }
}
