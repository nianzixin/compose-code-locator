package dev.codelocator.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
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

    // Producer module dirs and root dir are injected at configuration time; execution no longer touches Project
    @get:Input
    abstract val producerProjectDir: Property<String>

    // Keep only path strings so whole project dirs do not become task inputs
    @get:Input
    abstract val pathRootDir: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val kotlinSourceFiles: ConfigurableFileCollection = project.objects.fileCollection()

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val entries = ComposeSourceScanner.scan(
            projectDir = java.io.File(producerProjectDir.get()),
            sourceDirectories = sourceDirectories.get(),
            pathRootDir = java.io.File(pathRootDir.get()),
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
