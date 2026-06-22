package dev.codelocator.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class GenerateComposeLocatorStudioIndexTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceMapFile: RegularFileProperty

    @get:Classpath
    abstract val dependencyMetadataFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val entries = (
            ComposeLocatorMetadata.decode(sourceMapFile.get().asFile) +
                dependencyMetadataFiles.files.flatMap(ComposeLocatorMetadata::decodeAll)
            )
            .distinctComposeSourceEntries()
            .sortedWith(compareBy<ComposeSourceEntry> { shardName(it.sourceId) }.thenBy { it.sourceId })
        val output = outputDirectory.get().asFile
        val shardsDir = output.resolve("shards")
        shardsDir.mkdirs()

        val nextShardNames = entries.mapTo(linkedSetOf()) { shardName(it.sourceId) }
        shardsDir.listFiles()
            ?.filter { it.isFile && it.extension == "jsonl" && it.nameWithoutExtension !in nextShardNames }
            ?.forEach { it.delete() }

        val manifest = buildString {
            appendLine("{")
            appendLine("  \"version\": 1,")
            appendLine("  \"shardCount\": $SHARD_COUNT,")
            appendLine("  \"entryCount\": ${entries.size}")
            appendLine("}")
        }
        output.resolve("manifest.json").writeIfChanged(manifest)

        val sourceIdIndex = buildString {
            entries.forEach { entry ->
                append(entry.sourceId)
                append('\t')
                append(shardName(entry.sourceId))
                append('\n')
            }
        }
        output.resolve("source-id-index.tsv").writeIfChanged(sourceIdIndex)

        entries.groupBy { shardName(it.sourceId) }.forEach { (shard, shardEntries) ->
            val content = buildString {
                shardEntries.forEach { entry ->
                    append("{\"sourceId\":")
                    append(entry.sourceId)
                    append(",\"relativePath\":\"")
                    append(escape(entry.relativePath))
                    append("\",\"line\":")
                    append(entry.line)
                    append(",\"column\":")
                    append(entry.column)
                    append(",\"symbol\":\"")
                    append(escape(entry.symbol))
                    append("\",\"kind\":\"")
                    append(entry.kind.name)
                    append("\"}\n")
                }
            }
            shardsDir.resolve("$shard.jsonl").writeIfChanged(content)
        }

        logger.lifecycle("Generated Compose locator Studio index at ${output.absolutePath} (${entries.size} entries)")
    }

    private fun shardName(sourceId: Long): String {
        val shard = ((sourceId ushr 56) and 0xFF).toInt()
        return shard.toString(16).padStart(2, '0')
    }

    private fun java.io.File.writeIfChanged(content: String) {
        parentFile?.mkdirs()
        if (!isFile || readText() != content) {
            writeText(content)
        }
    }

    private fun escape(value: String): String {
        return buildString {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    else -> append(char)
                }
            }
        }
    }

    private companion object {
        const val SHARD_COUNT = 256
    }
}
