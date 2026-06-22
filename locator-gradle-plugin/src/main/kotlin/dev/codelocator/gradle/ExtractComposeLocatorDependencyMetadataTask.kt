package dev.codelocator.gradle

import java.io.File
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class ExtractComposeLocatorDependencyMetadataTask : DefaultTask() {
    @get:Classpath
    abstract val dependencyArtifacts: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun extract() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()

        var extractedCount = 0
        dependencyArtifacts.files
            .sortedBy { it.absolutePath }
            .forEachIndexed { index, artifact ->
                extractedCount += artifact.extractMetadataFiles(output, index)
            }
        logger.lifecycle("Extracted $extractedCount Compose Locator dependency metadata file(s) to ${output.absolutePath}")
    }

    private fun File.extractMetadataFiles(
        output: File,
        artifactIndex: Int,
    ): Int {
        if (isDirectory) {
            var count = 0
            walkTopDown()
                .filter { it.isFile }
                .filter { it.invariantSeparatorsPath.endsWith("META-INF/compose-locator/compose-locator-metadata.json") }
                .forEachIndexed { entryIndex, metadataFile ->
                    val target = output.resolve("${artifactIndex}_${entryIndex}_${name.sanitizeFileName()}.json")
                    metadataFile.copyTo(target, overwrite = true)
                    count += 1
                }
            return count
        }
        if (!isFile || (extension != "aar" && extension != "jar")) return 0

        var count = 0
        ZipFile(this).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory }
                .filter { entry -> entry.name == "META-INF/compose-locator/compose-locator-metadata.json" }
                .forEachIndexed { entryIndex, entry ->
                    val target = output.resolve("${artifactIndex}_${entryIndex}_${nameWithoutExtension.sanitizeFileName()}.json")
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use(input::copyTo)
                    }
                    count += 1
                }
        }
        return count
    }

    private fun String.sanitizeFileName(): String {
        return map { char ->
            if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_'
        }.joinToString("").ifBlank { "metadata" }
    }
}
