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

abstract class GenerateComposeLocatorSourceMapTask : DefaultTask() {
    @get:Input
    abstract val sourceDirectories: ListProperty<String>

    // 配置期解析好的扫描根，执行期不再访问 Project
    @get:Input
    abstract val scanRoots: ListProperty<ComposeLocatorScanRoot>

    // 仅取路径字符串，避免把整个工程目录变成任务输入
    @get:Input
    abstract val pathRootDir: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val kotlinSourceFiles: ConfigurableFileCollection = project.objects.fileCollection()

    @get:OutputFile
    abstract val outputPath: RegularFileProperty

    @TaskAction
    fun generate() {
        val entries = scanRoots.get().resolvedScanRoots().flatMap { (projectDir, directories) ->
            ComposeSourceScanner.scan(
                projectDir = projectDir,
                sourceDirectories = directories,
                pathRootDir = java.io.File(pathRootDir.get()),
            )
        }
        val outputFile = outputPath.get().asFile
        outputFile.parentFile.mkdirs()
        val content = ComposeLocatorMetadata.encode(entries.distinctComposeSourceEntries())
        if (!outputFile.isFile || outputFile.readText() != content) {
            outputFile.writeText(content)
            logger.lifecycle("Generated Compose locator source map at ${outputFile.absolutePath}")
        } else {
            logger.lifecycle("Compose locator source map unchanged at ${outputFile.absolutePath}")
        }
    }
}