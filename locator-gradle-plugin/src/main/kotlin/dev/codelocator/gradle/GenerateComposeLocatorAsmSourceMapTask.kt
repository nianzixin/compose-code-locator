package dev.codelocator.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.FileCollection
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

    @get:Input
    abstract val aggregateProjectPaths: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val kotlinSourceFiles: FileCollection
        get() = project.files(scanRoots().flatMap { (scanProject, directories) ->
            directories.map { scanProject.file(it) }
        })
            .asFileTree
            .matching { it.include("**/*.kt") }

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val entries = scanRoots().flatMap { (scanProject, directories) ->
            ComposeSourceScanner.scan(
                projectDir = scanProject.projectDir,
                sourceDirectories = directories,
                pathRootDir = project.rootProject.projectDir,
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

    private fun scanRoots(): List<Pair<org.gradle.api.Project, List<String>>> {
        return aggregateProjectPaths.get().mapNotNull { path ->
            runCatching { project.project(path) }.getOrNull()
        }.map { scanProject ->
            val extension = scanProject.extensions.findByType(ComposeLocatorExtension::class.java)
            val directories = extension?.sourceDirectories ?: sourceDirectories.get()
            scanProject to directories
        }
    }
}
