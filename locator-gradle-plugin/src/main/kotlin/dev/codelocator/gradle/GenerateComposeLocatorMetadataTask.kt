package dev.codelocator.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class GenerateComposeLocatorMetadataTask : DefaultTask() {
    @get:Input
    abstract val sourceDirectories: ListProperty<String>

    @get:Input
    abstract val producerPath: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val kotlinSourceFiles: FileCollection
        get() = project.files(sourceDirectories.get().map(project::file))
            .asFileTree
            .matching { it.include("**/*.kt") }

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val entries = ComposeSourceScanner.scan(
            projectDir = project.projectDir,
            sourceDirectories = sourceDirectories.get(),
            pathRootDir = project.rootProject.projectDir,
        )
        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        val content = ComposeLocatorMetadata.encode(
            entries = entries,
            producerPath = producerPath.get(),
        )
        if (!file.isFile || file.readText() != content) {
            file.writeText(content)
            logger.lifecycle("Generated Compose locator metadata at ${file.absolutePath}")
        } else {
            logger.lifecycle("Compose locator metadata unchanged at ${file.absolutePath}")
        }
    }
}
