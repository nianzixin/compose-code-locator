package dev.codelocator.studio.source

import dev.codelocator.studio.model.SourceLocation
import java.io.File
import java.util.ArrayDeque
import java.util.LinkedHashMap

class StudioSourceIndex(
    private val projectRoot: File,
    private val maxLoadedShards: Int = 16,
) {
    private val indexRoots by lazy { findIndexRoots() }
    private val sourceToShard by lazy { loadSourceIdIndex() }
    private val shardCache = object : LinkedHashMap<String, Map<Long, SourceLocation>>(maxLoadedShards, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<Long, SourceLocation>>?): Boolean {
            return size > maxLoadedShards
        }
    }

    @Synchronized
    fun resolve(sourceId: Long?): SourceLocation? {
        sourceId ?: return null
        val shard = sourceToShard[sourceId] ?: return null
        val shardEntries = shardCache.getOrPut(shard) { loadShard(shard) }
        return shardEntries[sourceId]?.takeIf { it.isNavigable }
    }

    fun resolve(candidate: dev.codelocator.studio.model.HitCandidate): dev.codelocator.studio.model.HitCandidate {
        if (candidate.location?.isNavigable == true) return candidate
        val location = resolve(candidate.sourceId) ?: return candidate
        return candidate.copy(
            location = location,
            label = "${location.relativePath.substringAfterLast('/')}:${location.line} ${location.symbol ?: candidate.composableName ?: candidate.text ?: "source"}",
        )
    }

    @Synchronized
    fun resolutionHint(sourceId: Long?): String {
        sourceId ?: return "runtime did not provide sourceId; rebuild/install a debug app with Compose Locator enabled."
        if (indexRoots.isEmpty()) {
            return "Studio index not found under ${projectRoot.absolutePath}; build/sync the target project first."
        }
        if (sourceToShard.isEmpty()) {
            return "Studio index is empty under ${projectRoot.absolutePath}; run the Compose Locator Gradle tasks again."
        }
        val shard = sourceToShard[sourceId]
            ?: return "sourceId not found in Studio index (${sourceToShard.size} ids, ${indexRoots.size} roots); app APK and IDE project are likely out of sync."
        val location = resolve(sourceId)
        return if (location?.isNavigable == true) {
            "resolved by Studio index"
        } else {
            "sourceId exists in shard $shard but has no valid source location; regenerate the Studio index."
        }
    }

    private fun loadSourceIdIndex(): Map<Long, String> {
        return indexRoots
            .asSequence()
            .map { it.resolve("source-id-index.tsv") }
            .filter(File::isFile)
            .flatMap { file ->
                file.useLines { lines ->
                    lines.mapNotNull { line ->
                        val sourceId = line.substringBefore('\t').toLongOrNull() ?: return@mapNotNull null
                        val shard = line.substringAfter('\t', missingDelimiterValue = "").takeIf(String::isNotBlank)
                            ?: return@mapNotNull null
                        sourceId to shard
                    }.toList().asSequence()
                }
            }
            .toMap()
    }

    private fun loadShard(shard: String): Map<Long, SourceLocation> {
        return indexRoots
            .asSequence()
            .map { it.resolve("shards/$shard.jsonl") }
            .filter(File::isFile)
            .flatMap { file ->
                file.useLines { lines ->
                    lines.mapNotNull(::decodeEntry).toList().asSequence()
                }
            }
            .toMap()
    }

    private fun findIndexRoots(): List<File> {
        if (!projectRoot.isDirectory) return emptyList()
        val roots = mutableListOf<File>()
        val queue = ArrayDeque<File>()
        queue.add(projectRoot)
        while (queue.isNotEmpty()) {
            val directory = queue.removeFirst()
            when (directory.name) {
                in SKIPPED_DIRECTORY_NAMES -> continue
                "build" -> {
                    val indexRoot = directory.resolve("intermediates/composeLocator/studio-index/v1")
                    if (indexRoot.isDirectory) {
                        roots += indexRoot
                    }
                    continue
                }
            }
            directory.listFiles()
                .orEmpty()
                .filter(File::isDirectory)
                .forEach { child -> queue.add(child) }
        }
        return roots
    }

    private fun decodeEntry(line: String): Pair<Long, SourceLocation>? {
        val sourceId = longField(line, "sourceId") ?: return null
        val relativePath = stringField(line, "relativePath") ?: return null
        val lineNumber = intField(line, "line") ?: return null
        val column = intField(line, "column") ?: return null
        val symbol = stringField(line, "symbol")
        val location = SourceLocation(
            relativePath = relativePath,
            line = lineNumber,
            column = column,
            symbol = symbol,
        )
        if (!location.isNavigable) return null
        return sourceId to location
    }

    private fun longField(line: String, name: String): Long? = rawField(line, name)?.toLongOrNull()

    private fun intField(line: String, name: String): Int? = rawField(line, name)?.toIntOrNull()

    private fun stringField(line: String, name: String): String? {
        val marker = "\"$name\":"
        val start = line.indexOf(marker).takeIf { it >= 0 }?.let { it + marker.length } ?: return null
        if (line.getOrNull(start) != '"') return null
        val raw = StringBuilder()
        var index = start + 1
        var escaping = false
        while (index < line.length) {
            val char = line[index]
            when {
                escaping -> {
                    raw.append(
                        when (char) {
                            '"' -> '"'
                            '\\' -> '\\'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'b' -> '\b'
                            'f' -> '\u000C'
                            else -> char
                        },
                    )
                    escaping = false
                }
                char == '\\' -> escaping = true
                char == '"' -> return raw.toString()
                else -> raw.append(char)
            }
            index += 1
        }
        return null
    }

    private fun rawField(line: String, name: String): String? {
        val marker = "\"$name\":"
        val start = line.indexOf(marker).takeIf { it >= 0 }?.let { it + marker.length } ?: return null
        return line.drop(start).takeWhile { it != ',' && it != '}' }.trim().takeIf(String::isNotBlank)
    }

    private companion object {
        val SKIPPED_DIRECTORY_NAMES = setOf(
            ".git",
            ".gradle",
            ".idea",
            ".kotlin",
            "node_modules",
        )
    }
}
