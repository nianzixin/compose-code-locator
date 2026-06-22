package dev.codelocator.gradle

import java.io.File
import java.nio.file.Path

data class ComposeSourceEntry(
    val symbol: String,
    val relativePath: String,
    val line: Int,
    val column: Int,
    val sourceId: Long,
    val packageName: String,
    val ownerClassName: String,
    val tag: String? = null,
    val text: String? = null,
    val role: String? = null,
    val kind: ComposeSourceEntryKind = ComposeSourceEntryKind.Composable,
)

enum class ComposeSourceEntryKind {
    Composable,
    ComposableCallSite,
    ModifierCallSite,
}

object ComposeSourceScanner {
    fun scan(
        projectDir: File,
        sourceDirectories: List<String>,
        pathRootDir: File = projectDir,
    ): List<ComposeSourceEntry> {
        return sourceDirectories
            .map { File(projectDir, it) }
            .filter { it.exists() }
            .flatMap { root ->
                root.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .flatMap { file -> scanFile(pathRootDir.toPath(), file).asSequence() }
                    .toList()
            }
            .distinctBy { "${it.kind}:${it.relativePath}:${it.symbol}:${it.line}:${it.column}:${it.tag.orEmpty()}:${it.text.orEmpty()}:${it.role.orEmpty()}" }
            .sortedWith(compareBy<ComposeSourceEntry> { it.relativePath }.thenBy { it.line }.thenBy { it.symbol })
    }

    private fun scanFile(projectPath: Path, file: File): List<ComposeSourceEntry> {
        val lines = file.readLines()
        val results = mutableListOf<ComposeSourceEntry>()
        val packageName = lines.firstNotNullOfOrNull(::parsePackageName).orEmpty()
        val ownerClassName = buildOwnerClassName(packageName, file.nameWithoutExtension)
        val relativePath = projectPath.relativize(file.toPath()).toString().replace(File.separatorChar, '/')
        val functionRegex = Regex("""^\s*(?:(?:@[A-Za-z0-9_.]+(?:\([^)]*\))?)\s*)*(?:(?:private|internal|public|protected|override|inline|suspend|operator)\s+)*fun\s+([A-Za-z0-9_]+)\s*\(""")
        val composableRanges = findComposableRanges(lines, functionRegex)
        val modifierCallRegex = Regex("""\b([A-Z][A-Za-z0-9_]*)\s*(?:\(|\{)""")

        composableRanges.forEach { range ->
            val line = lines[range.declarationLineIndex]
            val sourceKey = "$relativePath#${range.symbol}#${range.declarationLineIndex + 1}"
            results += ComposeSourceEntry(
                symbol = range.symbol,
                relativePath = relativePath,
                line = range.declarationLineIndex + 1,
                column = range.declarationColumn,
                sourceId = fnv1a64(sourceKey),
                packageName = packageName,
                ownerClassName = ownerClassName,
                kind = ComposeSourceEntryKind.Composable,
            )
        }

        lines.forEachIndexed { index, line ->
            val currentComposable = composableRanges.lastOrNull { it.contains(index) }?.symbol
            if (currentComposable == null) {
                return@forEachIndexed
            }

            modifierCallRegex.findAll(line.withoutLineComment()).forEach { match ->
                if (line.isFunctionDeclarationAt(match.range.first)) return@forEach
                val symbol = match.groupValues[1]
                val sourceId = compilerPluginSourceId(
                    relativePath = relativePath,
                    line = index + 1,
                    column = match.range.first + 1,
                    symbol = symbol,
                )
                results += ComposeSourceEntry(
                    symbol = symbol,
                    relativePath = relativePath,
                    line = index + 1,
                    column = match.range.first + 1,
                    sourceId = sourceId,
                    packageName = packageName,
                    ownerClassName = ownerClassName,
                    kind = ComposeSourceEntryKind.ModifierCallSite,
                )
            }
        }

        results += scanComposableCallSites(
            lines = lines,
            relativePath = relativePath,
            packageName = packageName,
            composableEntries = results.filter { it.kind == ComposeSourceEntryKind.Composable },
            functionRegex = functionRegex,
            composableRanges = composableRanges,
        )

        return results
    }

    private fun scanComposableCallSites(
        lines: List<String>,
        relativePath: String,
        packageName: String,
        composableEntries: List<ComposeSourceEntry>,
        functionRegex: Regex,
        composableRanges: List<ComposableRange>,
    ): List<ComposeSourceEntry> {
        val uniqueComposableBySymbol = composableEntries
            .groupBy { it.symbol }
            .filterValues { it.size == 1 }
            .mapValues { it.value.single() }
        if (uniqueComposableBySymbol.isEmpty()) return emptyList()

        return lines.flatMapIndexed { index, line ->
            if (composableRanges.none { it.contains(index) }) return@flatMapIndexed emptyList()
            val declarationSymbol = functionRegex.find(line)?.groupValues?.getOrNull(1)
            uniqueComposableBySymbol.mapNotNull { (symbol, target) ->
                if (declarationSymbol == symbol) return@mapNotNull null
                val match = Regex("""\b${Regex.escape(symbol)}\s*\(""").find(line)
                    ?: return@mapNotNull null
                ComposeSourceEntry(
                    symbol = symbol,
                    relativePath = relativePath,
                    line = index + 1,
                    column = match.range.first + 1,
                    sourceId = compilerPluginSourceId(
                        relativePath = relativePath,
                        line = index + 1,
                        column = match.range.first + 1,
                        symbol = symbol,
                    ),
                    packageName = packageName,
                    ownerClassName = target.ownerClassName,
                    kind = ComposeSourceEntryKind.ComposableCallSite,
                )
            }
        }
    }

    private fun findComposableRanges(
        lines: List<String>,
        functionRegex: Regex,
    ): List<ComposableRange> {
        val ranges = mutableListOf<ComposableRange>()
        var composablePending = false
        var pendingStartLine = 0
        lines.forEachIndexed { index, line ->
            when {
                "@Composable" in line -> {
                    composablePending = true
                    pendingStartLine = index
                }
                composablePending && line.trimStart().startsWith("@") -> Unit
                composablePending -> {
                    val match = functionRegex.find(line)
                    if (match != null) {
                        val endLine = findFunctionEndLine(lines, index)
                        ranges += ComposableRange(
                            symbol = match.groupValues[1],
                            declarationLineIndex = index,
                            declarationColumn = (line.indexOf("fun ").takeIf { it >= 0 } ?: match.range.first) + 1,
                            startLineIndex = pendingStartLine,
                            endLineIndex = endLine,
                        )
                    }
                    composablePending = false
                }
            }
        }
        return ranges
    }

    private fun findFunctionEndLine(lines: List<String>, declarationLineIndex: Int): Int {
        var depth = 0
        var sawBody = false
        for (index in declarationLineIndex until lines.size) {
            val line = lines[index]
            depth += line.countStructureDelta()
            if ('{' in line.withoutLineComment()) {
                sawBody = true
            }
            if (sawBody && depth <= 0) {
                return index
            }
        }
        return lines.lastIndex
    }

    private fun parsePackageName(line: String): String? {
        val match = Regex("""^\s*package\s+([A-Za-z0-9_.]+)""").find(line) ?: return null
        return match.groupValues[1]
    }

    private fun buildOwnerClassName(
        packageName: String,
        fileNameWithoutExtension: String,
    ): String {
        val simpleName = "${fileNameWithoutExtension}Kt"
        return if (packageName.isBlank()) {
            simpleName
        } else {
            "$packageName.$simpleName"
        }
    }

    private data class ComposableRange(
        val symbol: String,
        val declarationLineIndex: Int,
        val declarationColumn: Int,
        val startLineIndex: Int,
        val endLineIndex: Int,
    ) {
        fun contains(lineIndex: Int): Boolean {
            return lineIndex in startLineIndex..endLineIndex
        }
    }

    private fun String.countStructureDelta(): Int {
        var delta = 0
        var inString = false
        var escaping = false
        forEach { char ->
            when {
                escaping -> escaping = false
                char == '\\' && inString -> escaping = true
                char == '"' -> inString = !inString
                !inString && (char == '(' || char == '{') -> delta += 1
                !inString && (char == ')' || char == '}') -> delta -= 1
            }
        }
        return delta
    }

    private fun String.withoutLineComment(): String {
        var inString = false
        var escaping = false
        for (index in indices) {
            val char = this[index]
            when {
                escaping -> escaping = false
                char == '\\' && inString -> escaping = true
                char == '"' -> inString = !inString
                !inString && char == '/' && getOrNull(index + 1) == '/' -> return take(index)
            }
        }
        return this
    }

    private fun String.isFunctionDeclarationAt(offset: Int): Boolean {
        val prefix = take(offset)
        return Regex("""\bfun\s+$""").containsMatchIn(prefix)
    }

    private fun fnv1a64(value: String): Long {
        var hash = -0x340d631b8c46753bL
        value.forEach { char ->
            hash = hash xor char.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash and Long.MAX_VALUE
    }

    private fun compilerPluginSourceId(
        relativePath: String,
        line: Int,
        column: Int,
        symbol: String,
    ): Long {
        return fnv1a64("$relativePath:$line:$column:$symbol")
    }
}
