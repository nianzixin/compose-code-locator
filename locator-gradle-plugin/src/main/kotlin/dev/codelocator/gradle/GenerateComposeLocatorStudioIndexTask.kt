package dev.codelocator.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class GenerateComposeLocatorStudioIndexTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceMapFile: RegularFileProperty

    @get:Classpath
    abstract val dependencyMetadataFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /** 工程根目录：用于剔除指向已删除源文件的陈旧历史条目（不参与 up-to-date 判断） */
    @get:Internal
    abstract val projectRootDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val newEntries = (
            ComposeLocatorMetadata.decode(sourceMapFile.get().asFile) +
                dependencyMetadataFiles.files.flatMap(ComposeLocatorMetadata::decodeAll)
            )
            .distinctComposeSourceEntries()
        val entries = mergeWithPreviousIndex(newEntries)
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

    /**
     * 与上一版索引合并：Kotlin 增量编译只重编改动文件，本次编译的 metadata 不含未改动文件的插桩记录，
     * 索引若每次全量重建，旧文件编译期注入的 sourceId 会从索引中消失——运行时 marker 仍指向该 id，
     * Studio 索引查不到就判为不可导航，点选会跳过它落到背后的元素。因此保留旧条目（同 id 以本次为准），
     * 并剔除源文件已删除的陈旧条目
     */
    private fun mergeWithPreviousIndex(newEntries: Collection<ComposeSourceEntry>): List<ComposeSourceEntry> {
        val shardsDir = outputDirectory.get().asFile.resolve("shards")
        val existingShards = shardsDir.listFiles()?.filter { it.isFile && it.extension == "jsonl" }.orEmpty()
        if (existingShards.isEmpty()) return newEntries.toList()
        val projectRoot = projectRootDirectory.orNull?.asFile ?: return newEntries.toList()
        val newIds = newEntries.mapTo(HashSet()) { it.sourceId }
        val previous = existingShards
            .flatMap { shard -> shard.readLines().mapNotNull(::parseShardEntry) }
            .filter { entry -> entry.sourceId !in newIds && projectRoot.resolve(entry.relativePath).isFile }
        if (previous.isEmpty()) return newEntries.toList()
        logger.lifecycle(
            "Merged ${previous.size} entries from previous Studio index (incremental compile preservation)",
        )
        return newEntries + previous
    }

    /** 解析上一版 shard jsonl 行为索引条目；字段解析失败时返回 null（跳过坏行） */
    private fun parseShardEntry(line: String): ComposeSourceEntry? {
        if (!line.startsWith("{\"sourceId\":")) return null
        val sourceId = line.stringField("sourceId")?.toLongOrNull() ?: return null
        val relativePath = line.stringField("relativePath")?.unescapeJson() ?: return null
        val lineNumber = line.stringField("line")?.toIntOrNull() ?: return null
        val column = line.stringField("column")?.toIntOrNull() ?: 0
        val symbol = line.stringField("symbol")?.unescapeJson().orEmpty()
        val kind = line.stringField("kind")?.let { value ->
            runCatching { ComposeSourceEntryKind.valueOf(value) }.getOrNull()
        } ?: ComposeSourceEntryKind.ModifierCallSite
        return ComposeSourceEntry(
            symbol = symbol,
            relativePath = relativePath,
            line = lineNumber,
            column = column,
            sourceId = sourceId,
            packageName = relativePath.substringBeforeLast('/').replace('/', '.'),
            ownerClassName = "",
            kind = kind,
        )
    }

    /** 提取 "name":"value" 或 "name":123 的原始字段值（shard 行由本任务自己写出，格式可控） */
    private fun String.stringField(name: String): String? {
        val marker = "\"$name\":"
        val start = indexOf(marker).takeIf { it >= 0 }?.let { it + marker.length } ?: return null
        val valueStart = indexOfFirstFrom(start) { !it.isWhitespace() }
        if (valueStart < 0) return null
        if (this[valueStart] == '"') {
            val end = indexOfFirstFrom(valueStart + 1) { it == '"' }
            if (end < 0) return null
            return substring(valueStart + 1, end)
        }
        val end = indexOfFirstFrom(valueStart) { it == ',' || it == '}' }.takeIf { it >= 0 } ?: length
        return substring(valueStart, end).trim()
    }

    /** 从 start 起找第一个满足条件的下标，找不到返回 -1 */
    private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
        for (index in start until length) {
            if (predicate(this[index])) return index
        }
        return -1
    }

    /** 还原 shard 行中转义的反斜杠与引号（仅这两种会被 escape 转义） */
    private fun String.unescapeJson(): String {
        return replace("\\\\", "\u0000").replace("\\\"", "\"").replace("\u0000", "\\")
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
