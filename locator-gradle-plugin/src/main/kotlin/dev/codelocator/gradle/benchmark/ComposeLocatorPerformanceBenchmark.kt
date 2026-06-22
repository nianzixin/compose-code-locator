package dev.codelocator.gradle.benchmark

import dev.codelocator.gradle.ComposeLocatorMetadata
import dev.codelocator.gradle.ComposeSourceScanner
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.system.measureNanoTime

private const val SYNTHETIC_FILE_COUNT = 250
private const val COMPOSABLES_PER_FILE = 16
private const val AAR_FIXTURE_COUNT = 80

fun main(args: Array<String>) {
    val outputRoot = args.firstOrNull()?.let(::File)
        ?: File("build/benchmarks/compose-locator-performance")
    outputRoot.deleteRecursively()
    outputRoot.mkdirs()

    val projectDir = outputRoot.resolve("synthetic-project")
    generateSyntheticComposeProject(projectDir)

    val warmupEntries = ComposeSourceScanner.scan(
        projectDir = projectDir,
        sourceDirectories = listOf("src/main/kotlin"),
        pathRootDir = projectDir,
    )
    check(warmupEntries.isNotEmpty()) {
        "Synthetic project scan returned no entries"
    }

    var scannedEntries = warmupEntries
    val scanBestMs = bestMillis(iterations = 5) {
        scannedEntries = ComposeSourceScanner.scan(
            projectDir = projectDir,
            sourceDirectories = listOf("src/main/kotlin"),
            pathRootDir = projectDir,
        )
    }
    check(scannedEntries.size >= SYNTHETIC_FILE_COUNT * COMPOSABLES_PER_FILE) {
        "Synthetic scan produced too few entries: ${scannedEntries.size}"
    }

    val metadataFile = outputRoot.resolve("compose-locator-metadata.json")
    var decodedCount = 0
    val metadataBestMs = bestMillis(iterations = 5) {
        metadataFile.writeText(
            ComposeLocatorMetadata.encode(
                entries = scannedEntries,
                producerPath = "benchmark:synthetic",
            ),
        )
        decodedCount = ComposeLocatorMetadata.decode(metadataFile).size
    }
    check(decodedCount == scannedEntries.size) {
        "Metadata decode count mismatch: expected ${scannedEntries.size}, got $decodedCount"
    }

    val aarDir = outputRoot.resolve("aars")
    generateAarFixtures(aarDir, metadataFile.readText())
    val extractOutput = outputRoot.resolve("extracted")
    var extractedCount = 0
    val extractionBestMs = bestMillis(iterations = 5) {
        extractOutput.deleteRecursively()
        extractOutput.mkdirs()
        extractedCount = extractAarMetadata(aarDir, extractOutput)
    }
    check(extractedCount == AAR_FIXTURE_COUNT) {
        "AAR metadata extraction count mismatch: expected $AAR_FIXTURE_COUNT, got $extractedCount"
    }

    val scanThresholdMs = longProperty("codelocator.performance.maxScanMs", 5_000L)
    val metadataThresholdMs = longProperty("codelocator.performance.maxMetadataMs", 3_000L)
    val extractionThresholdMs = longProperty("codelocator.performance.maxExtractionMs", 5_000L)

    println(
        buildString {
            appendLine("Compose Locator performance benchmark")
            appendLine("syntheticFiles=$SYNTHETIC_FILE_COUNT")
            appendLine("syntheticComposables=${SYNTHETIC_FILE_COUNT * COMPOSABLES_PER_FILE}")
            appendLine("sourceEntries=${scannedEntries.size}")
            appendLine("aarFixtures=$AAR_FIXTURE_COUNT")
            appendLine("scanBestMs=${scanBestMs.formatMs()} thresholdMs=$scanThresholdMs")
            appendLine("metadataEncodeDecodeBestMs=${metadataBestMs.formatMs()} thresholdMs=$metadataThresholdMs")
            appendLine("aarMetadataExtractionBestMs=${extractionBestMs.formatMs()} thresholdMs=$extractionThresholdMs")
        }.trimEnd(),
    )

    check(scanBestMs <= scanThresholdMs) {
        "Compose source scan benchmark exceeded threshold: ${scanBestMs.formatMs()}ms > ${scanThresholdMs}ms"
    }
    check(metadataBestMs <= metadataThresholdMs) {
        "Compose metadata encode/decode benchmark exceeded threshold: ${metadataBestMs.formatMs()}ms > ${metadataThresholdMs}ms"
    }
    check(extractionBestMs <= extractionThresholdMs) {
        "AAR metadata extraction benchmark exceeded threshold: ${extractionBestMs.formatMs()}ms > ${extractionThresholdMs}ms"
    }
}

private fun generateSyntheticComposeProject(projectDir: File) {
    val sourceRoot = projectDir.resolve("src/main/kotlin/dev/codelocator/benchmark")
    sourceRoot.mkdirs()
    repeat(SYNTHETIC_FILE_COUNT) { fileIndex ->
        val packageName = "dev.codelocator.benchmark.file$fileIndex"
        val content = buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("import androidx.compose.runtime.Composable")
            appendLine("import androidx.compose.foundation.layout.Column")
            appendLine("import androidx.compose.foundation.layout.Row")
            appendLine("import androidx.compose.foundation.layout.Box")
            appendLine("import androidx.compose.material3.Button")
            appendLine("import androidx.compose.material3.Text")
            appendLine()
            repeat(COMPOSABLES_PER_FILE) { composableIndex ->
                val name = "SyntheticCard${fileIndex}_$composableIndex"
                appendLine("@Composable")
                appendLine("fun $name() {")
                appendLine("    Column {")
                appendLine("        Row {")
                appendLine("            Box {")
                appendLine("                Text(\"Title $fileIndex-$composableIndex\")")
                appendLine("            }")
                appendLine("            Button(onClick = {}) {")
                appendLine("                Text(\"Confirm $fileIndex-$composableIndex\")")
                appendLine("            }")
                if (composableIndex > 0) {
                    appendLine("            SyntheticCard${fileIndex}_${composableIndex - 1}()")
                }
                appendLine("        }")
                appendLine("    }")
                appendLine("}")
                appendLine()
            }
        }
        sourceRoot.resolve("SyntheticFile$fileIndex.kt").writeText(content)
    }
}

private fun generateAarFixtures(
    aarDir: File,
    metadata: String,
) {
    aarDir.deleteRecursively()
    aarDir.mkdirs()
    repeat(AAR_FIXTURE_COUNT) { index ->
        val aarFile = aarDir.resolve("external-fixture-$index.aar")
        ZipOutputStream(aarFile.outputStream()).use { zip ->
            zip.putTextEntry(
                name = "AndroidManifest.xml",
                text = """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.codelocator.fixture$index" />""",
            )
            zip.putTextEntry(
                name = "META-INF/compose-locator/compose-locator-metadata.json",
                text = metadata,
            )
            zip.putTextEntry(
                name = "classes.jar",
                text = "",
            )
        }
    }
}

private fun extractAarMetadata(
    aarDir: File,
    outputDir: File,
): Int {
    var extractedCount = 0
    aarDir.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension == "aar" }
        .sortedBy { it.name }
        .forEachIndexed { artifactIndex, aar ->
            ZipFile(aar).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .filter { entry -> entry.name == "META-INF/compose-locator/compose-locator-metadata.json" }
                    .forEachIndexed { entryIndex, entry ->
                        val target = outputDir.resolve("${artifactIndex}_${entryIndex}_${aar.nameWithoutExtension}.json")
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().use(input::copyTo)
                        }
                        extractedCount += 1
                    }
            }
        }
    return extractedCount
}

private fun ZipOutputStream.putTextEntry(
    name: String,
    text: String,
) {
    putNextEntry(ZipEntry(name))
    write(text.toByteArray())
    closeEntry()
}

private fun bestMillis(
    iterations: Int,
    block: () -> Unit,
): Double {
    var bestNanos = Long.MAX_VALUE
    repeat(max(iterations, 1)) {
        val nanos = measureNanoTime(block)
        if (nanos < bestNanos) bestNanos = nanos
    }
    return bestNanos / 1_000_000.0
}

private fun longProperty(
    name: String,
    fallback: Long,
): Long {
    return System.getProperty(name)?.toLongOrNull() ?: fallback
}

private fun Double.formatMs(): String {
    return String.format(Locale.US, "%.2f", this)
}
