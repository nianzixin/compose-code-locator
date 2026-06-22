package dev.codelocator.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class VerifyComposeCompilerSourceAlignmentTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val compilerReportFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceMapFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val metadataFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val compilerReport = compilerReportFile.get().asFile
        val sourceMap = sourceMapFile.get().asFile
        val metadata = metadataFile.get().asFile

        val injectedRecords = parseCompilerRecords(compilerReport.readText())
            .filter { it.injected && it.kind.startsWith("modifier") }
            .filter { it.line > 0 && it.column > 0 }
            .filterNot { "/build/generated/" in it.relativePath }
            .distinctBy { it.sourceId }
        val sourceEntries = ComposeLocatorMetadata.decode(sourceMap)
            .associateBy { it.sourceId }
        val metadataSourceIds = ComposeLocatorMetadata.decode(metadata)
            .map { it.sourceId }
            .toSet()

        val missingSourceMapEntries = injectedRecords.filter { it.sourceId !in sourceEntries }
        if (missingSourceMapEntries.isNotEmpty()) {
            throw GradleException(
                "Compiler-injected source IDs are missing from ${sourceMap.relativeTo(project.projectDir)}:\n" +
                    missingSourceMapEntries.describeRecords(),
            )
        }

        val unsupportedSourceMapEntries = injectedRecords.mapNotNull { record ->
            val entry = sourceEntries.getValue(record.sourceId)
            val symbolMatches = entry.symbol == record.symbol
            val positionMatches = entry.relativePath == record.relativePath &&
                entry.line == record.line &&
                entry.column == record.column
            val kindMatches = entry.kind == ComposeSourceEntryKind.ModifierCallSite ||
                entry.kind == ComposeSourceEntryKind.ComposableCallSite
            if (symbolMatches && positionMatches && kindMatches) null else record to entry
        }
        if (unsupportedSourceMapEntries.isNotEmpty()) {
            throw GradleException(
                "Compiler-injected source IDs do not align with ${sourceMap.relativeTo(project.projectDir)}:\n" +
                    unsupportedSourceMapEntries.joinToString("\n") { (record, entry) ->
                        "  ${record.describe()} -> ${entry.kind} ${entry.relativePath}:${entry.line}:${entry.column} ${entry.symbol}"
                    },
            )
        }

        val missingMetadataSources = injectedRecords.filter { it.sourceId !in metadataSourceIds }
        if (missingMetadataSources.isNotEmpty()) {
            throw GradleException(
                "Compiler-injected source IDs are missing from ${metadata.relativeTo(project.projectDir)}:\n" +
                    missingMetadataSources.describeRecords(),
            )
        }

        logger.lifecycle(
            "Compose compiler/source-map alignment verified for ${project.path}: " +
                "${injectedRecords.size} compiler-injected source IDs",
        )
    }

    private fun parseCompilerRecords(report: String): List<CompilerRecord> {
        val regex = Regex(
            """^\s*sourceId=(\d+)\s+injected=(true|false)\s+kind=([^\s]+)\s+(.+):(\d+):(\d+)\s+(.+)$""",
        )
        return report.lineSequence().mapNotNull { line ->
            val match = regex.matchEntire(line) ?: return@mapNotNull null
            CompilerRecord(
                sourceId = match.groupValues[1].toLong(),
                injected = match.groupValues[2].toBoolean(),
                kind = match.groupValues[3],
                relativePath = match.groupValues[4],
                line = match.groupValues[5].toInt(),
                column = match.groupValues[6].toInt(),
                symbol = match.groupValues[7].substringAfterLast('.'),
            )
        }.toList()
    }

    private fun List<CompilerRecord>.describeRecords(): String {
        return take(20).joinToString("\n") { "  ${it.describe()}" } +
            if (size > 20) "\n  ... ${size - 20} more" else ""
    }

    private fun CompilerRecord.describe(): String {
        return "sourceId=$sourceId kind=$kind $relativePath:$line:$column $symbol"
    }

    private data class CompilerRecord(
        val sourceId: Long,
        val injected: Boolean,
        val kind: String,
        val relativePath: String,
        val line: Int,
        val column: Int,
        val symbol: String,
    )
}
