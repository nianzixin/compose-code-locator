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
        val sourceRoots = sourceDirectories
            .map { File(projectDir, it) }
            .filter { it.exists() }
        // 两遍扫描：先解析全部文件并收集全局 Composable 声明表，
        // 再匹配调用点；调用点必须支持跨文件（如 login_view 调 waulavis 里的封装组件），
        // 只扫同文件会漏掉绝大多数业务调用点导致 ASM 插桩不生效
        val parsedFiles = sourceRoots
            .flatMap { root ->
                root.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .map { file -> parseFile(pathRootDir.toPath(), file) }
                    .toList()
            }
        val uniqueComposableBySymbol = parsedFiles
            .asSequence()
            .flatMap { parsed -> parsed.composables.asSequence().map { it.symbol to it.ownerClassName } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size == 1 }
            .mapValues { it.value.single() }
        return parsedFiles
            .flatMap { parsed -> parsed.collectEntries(uniqueComposableBySymbol).asSequence() }
            .distinctBy { "${it.kind}:${it.relativePath}:${it.symbol}:${it.line}:${it.column}:${it.tag.orEmpty()}:${it.text.orEmpty()}:${it.role.orEmpty()}" }
            .sortedWith(compareBy<ComposeSourceEntry> { it.relativePath }.thenBy { it.line }.thenBy { it.symbol })
            .toList()
    }

    private class ParsedSourceFile(
        val relativePath: String,
        val packageName: String,
        val ownerClassName: String,
        val lines: List<String>,
        val functionRegex: Regex,
        val composableRanges: List<ComposableRange>,
    ) {
        val composables: List<ComposeSourceEntry>
            get() = composableRanges.map { range ->
                val line = lines[range.declarationLineIndex]
                ComposeSourceEntry(
                    symbol = range.symbol,
                    relativePath = relativePath,
                    line = range.declarationLineIndex + 1,
                    column = range.declarationColumn,
                    sourceId = fnv1a64("$relativePath#${range.symbol}#${range.declarationLineIndex + 1}"),
                    packageName = packageName,
                    ownerClassName = ownerClassName,
                    kind = ComposeSourceEntryKind.Composable,
                )
            }

        /**
         * 收集本文件的 Modifier 调用点 + Composable 调用点；
         * Composable 调用点用全局唯一符号表匹配（支持跨文件调用），
         * map 值是该符号声明文件的 ownerClassName，ASM map key 需要 callee 的 owner
         */
        fun collectEntries(uniqueComposableBySymbol: Map<String, String>): List<ComposeSourceEntry> {
            val results = mutableListOf<ComposeSourceEntry>()
            results += composables
            val modifierCallRegex = Regex("""\b([A-Z][A-Za-z0-9_]*)\s*(?:\(|\{)""")
            val composableCallRegex = if (uniqueComposableBySymbol.isEmpty()) {
                null
            } else {
                // 按 name 长度倒序拼接，避免短符号在长符号里先匹配（如 FooBar 里的 Foo）
                Regex("\\b(" + uniqueComposableBySymbol.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")\\s*\\(")
            }
            lines.forEachIndexed { index, line ->
                if (composableRanges.none { it.contains(index) }) return@forEachIndexed
                val cleanLine = line.withoutLineComment()

                modifierCallRegex.findAll(cleanLine).forEach { match ->
                    if (line.isFunctionDeclarationAt(match.range.first)) return@forEach
                    val symbol = match.groupValues[1]
                    results += ComposeSourceEntry(
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
                        ownerClassName = ownerClassName,
                        kind = ComposeSourceEntryKind.ModifierCallSite,
                    )
                }

                composableCallRegex?.findAll(cleanLine)?.forEach { match ->
                    if (line.isFunctionDeclarationAt(match.range.first)) return@forEach
                    val symbol = match.groupValues[1]
                    results += ComposeSourceEntry(
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
                        ownerClassName = uniqueComposableBySymbol.getValue(symbol),
                        kind = ComposeSourceEntryKind.ComposableCallSite,
                    )
                }
            }
            return results
        }
    }

    private fun parseFile(projectPath: Path, file: File): ParsedSourceFile {
        val lines = file.readLines()
        val packageName = lines.firstNotNullOfOrNull(::parsePackageName).orEmpty()
        val ownerClassName = buildOwnerClassName(packageName, file.nameWithoutExtension)
        val relativePath = projectPath.relativize(file.toPath()).toString().replace(File.separatorChar, '/')
        // 兼容带接收者的 Composable（如 private fun BoxScope.ChapterSideBar(...)）：fun 与函数名之间
        // 可有 "ReceiverType." 前缀；不识别会导致整个函数体落在 composableRanges 之外，
        // 其中的 Modifier 调用点（如侧栏 Text/clickable）全部漏扫，运行时命中却查不到索引
        val functionRegex = Regex("""^\s*(?:(?:@[A-Za-z0-9_.]+(?:\([^)]*\))?)\s*)*(?:(?:private|internal|public|protected|override|inline|suspend|operator)\s+)*fun\s+(?:[A-Za-z0-9_]+\.)?([A-Za-z0-9_]+)\s*\(""")
        val composableRanges = findComposableRanges(lines, functionRegex)
        return ParsedSourceFile(
            relativePath = relativePath,
            packageName = packageName,
            ownerClassName = ownerClassName,
            lines = lines,
            functionRegex = functionRegex,
            composableRanges = composableRanges,
        )
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
