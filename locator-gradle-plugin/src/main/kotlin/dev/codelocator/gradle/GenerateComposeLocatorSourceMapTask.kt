package dev.codelocator.gradle

import java.io.File
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

    // 配置期解析好的扫描根，每项为 "projectDir<分隔符>sourceDir"，执行期不再访问 Project
    @get:Input
    abstract val scanRoots: ListProperty<String>

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
        val entries = resolvedScanRoots().flatMap { (projectDir, directories) ->
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

    private fun resolvedScanRoots(): Map<File, List<String>> {
        val roots = LinkedHashMap<File, MutableList<String>>()
        scanRoots.get().forEach { entry ->
            val separator = entry.indexOf(SCAN_ROOT_SEPARATOR)
            if (separator > 0) {
                roots.getOrPut(File(entry.substring(0, separator))) { mutableListOf() }
                    .add(entry.substring(separator + 1))
            }
        }
        return roots
    }
}

/** scanRoots 条目内 projectDir 与 sourceDir 之间的分隔符，控制字符不会出现在路径里 */
/** scanRoots 条目内 projectDir 与 sourceDir 之间的分隔符；'|' 在 Windows/Unix 路径里均非法 */
private const val SCAN_ROOT_SEPARATOR = '|'