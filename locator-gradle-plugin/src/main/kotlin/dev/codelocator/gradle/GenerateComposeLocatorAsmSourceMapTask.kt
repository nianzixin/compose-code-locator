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

abstract class GenerateComposeLocatorAsmSourceMapTask : DefaultTask() {
    @get:Input
    abstract val sourceDirectories: ListProperty<String>

    // Scan root resolved at configuration time; execution no longer touches Project
    @get:Input
    abstract val scanRoots: ListProperty<ComposeLocatorScanRoot>

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
        val entries = scanRoots.get().resolvedScanRoots().flatMap { (projectDir, directories) ->
            ComposeSourceScanner.scan(
                projectDir = projectDir,
                sourceDirectories = directories,
                pathRootDir = java.io.File(pathRootDir.get()),
            )
        }
            .asSequence()
            .filter { it.kind == ComposeSourceEntryKind.ComposableCallSite }
            .map { entry ->
                "${entry.ownerClassName.replace('.', '/')}#${entry.symbol}#${entry.line}=${entry.sourceId}"
            }
            .distinct()
            .sorted()
            .toList()
        val content = buildString {
            appendLine("# owner#symbol#line=sourceId")
            entries.forEach(::appendLine)
        }
        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        if (!file.isFile || file.readText() != content) {
            file.writeText(content)
            logger.lifecycle("Generated Compose locator ASM source map at ${file.absolutePath}")
        } else {
            logger.lifecycle("Compose locator ASM source map unchanged at ${file.absolutePath}")
        }
    }
}
