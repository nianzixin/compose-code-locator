package dev.codelocator.gradle

import java.io.File

internal object ComposeLocatorMetadata {
    fun encode(
        entries: List<ComposeSourceEntry>,
        producerPath: String? = null,
    ): String {
        val distinctEntries = entries.distinctComposeSourceEntries()
        return buildString {
            appendLine("{")
            appendLine("  \"version\": 1,")
            if (producerPath != null) {
                appendLine("  \"producerPath\": \"${escape(producerPath)}\",")
            }
            appendLine("  \"entries\": [")
            distinctEntries.forEachIndexed { index, entry ->
                val suffix = if (index == distinctEntries.lastIndex) "" else ","
                appendLine(
                    "    {\"sourceId\": ${entry.sourceId}, \"symbol\": \"${escape(entry.symbol)}\", " +
                        "\"relativePath\": \"${escape(entry.relativePath)}\", \"line\": ${entry.line}, " +
                        "\"column\": ${entry.column}, \"packageName\": \"${escape(entry.packageName)}\", " +
                        "\"ownerClassName\": \"${escape(entry.ownerClassName)}\", " +
                        "\"kind\": \"${entry.kind.name}\", \"tag\": ${stringOrNull(entry.tag)}, " +
                        "\"text\": ${stringOrNull(entry.text)}, \"role\": ${stringOrNull(entry.role)}}$suffix",
                )
            }
            appendLine("  ]")
            append('}')
        }
    }

    fun decode(file: File): List<ComposeSourceEntry> {
        if (!file.isFile) return emptyList()
        return file.useLines { lines ->
            lines
                .filter { "\"sourceId\"" in it }
                .mapNotNull(::decodeEntryLine)
                .toList()
        }
    }

    fun decodeAll(file: File): List<ComposeSourceEntry> {
        return when {
            file.isFile -> decode(file)
            file.isDirectory -> file.walkTopDown()
                .filter { it.isFile && it.extension == "json" }
                .flatMap { decode(it).asSequence() }
                .toList()
            else -> emptyList()
        }
    }

    private fun decodeEntryLine(line: String): ComposeSourceEntry? {
        val kind = stringField(line, "kind")
            ?.let { runCatching { ComposeSourceEntryKind.valueOf(it) }.getOrNull() }
            ?: return null
        return ComposeSourceEntry(
            sourceId = longField(line, "sourceId") ?: return null,
            symbol = stringField(line, "symbol") ?: return null,
            relativePath = stringField(line, "relativePath") ?: return null,
            line = intField(line, "line") ?: return null,
            column = intField(line, "column") ?: return null,
            packageName = stringField(line, "packageName").orEmpty(),
            ownerClassName = stringField(line, "ownerClassName").orEmpty(),
            kind = kind,
            tag = nullableStringField(line, "tag"),
            text = nullableStringField(line, "text"),
            role = nullableStringField(line, "role"),
        )
    }

    private fun stringOrNull(value: String?): String {
        return value?.let { "\"${escape(it)}\"" } ?: "null"
    }

    private fun longField(line: String, name: String): Long? {
        return rawField(line, name)?.toLongOrNull()
    }

    private fun intField(line: String, name: String): Int? {
        return rawField(line, name)?.toIntOrNull()
    }

    private fun stringField(line: String, name: String): String? {
        return nullableStringField(line, name) ?: return null
    }

    private fun nullableStringField(line: String, name: String): String? {
        val marker = "\"$name\": "
        val start = line.indexOf(marker).takeIf { it >= 0 }?.let { it + marker.length } ?: return null
        if (line.startsWith("null", start)) return null
        if (line.getOrNull(start) != '"') return null
        val raw = StringBuilder()
        var index = start + 1
        var escaping = false
        while (index < line.length) {
            val char = line[index]
            when {
                escaping -> {
                    raw.append('\\')
                    raw.append(char)
                    escaping = false
                }
                char == '\\' -> escaping = true
                char == '"' -> return unescape(raw.toString())
                else -> raw.append(char)
            }
            index += 1
        }
        return null
    }

    private fun rawField(line: String, name: String): String? {
        val marker = "\"$name\": "
        val start = line.indexOf(marker).takeIf { it >= 0 }?.let { it + marker.length } ?: return null
        return line
            .drop(start)
            .takeWhile { it != ',' && it != '}' }
            .trim()
            .takeIf { it.isNotBlank() }
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

    private fun unescape(value: String): String {
        return buildString {
            var index = 0
            while (index < value.length) {
                val char = value[index]
                if (char != '\\' || index == value.lastIndex) {
                    append(char)
                    index += 1
                    continue
                }
                when (val escaped = value[index + 1]) {
                    '\\' -> append('\\')
                    '"' -> append('"')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    'b' -> append('\b')
                    'f' -> append('\u000C')
                    'u' -> {
                        val hex = value.drop(index + 2).take(4)
                        val code = hex.toIntOrNull(16)
                        if (hex.length == 4 && code != null) {
                            append(code.toChar())
                            index += 4
                        } else {
                            append(escaped)
                        }
                    }
                    else -> append(escaped)
                }
                index += 2
            }
        }
    }
}

internal fun List<ComposeSourceEntry>.distinctComposeSourceEntries(): List<ComposeSourceEntry> {
    return distinctBy {
        "${it.kind}:${it.relativePath}:${it.symbol}:${it.line}:${it.column}:${it.tag.orEmpty()}:${it.text.orEmpty()}:${it.role.orEmpty()}"
    }.sortedWith(compareBy<ComposeSourceEntry> { it.relativePath }.thenBy { it.line }.thenBy { it.symbol })
}
