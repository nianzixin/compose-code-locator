import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    base
    kotlin("jvm") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.android.application") version "8.6.1" apply false
    id("com.android.library") version "8.6.1" apply false
    id("org.jetbrains.intellij") version "1.17.4" apply false
}

val publicGroupId = "io.github.nianzixin"
val publicGitHubUrl = "https://github.com/nianzixin/compose-code-locator"

extra["publicGroupId"] = publicGroupId
extra["publicGitHubUrl"] = publicGitHubUrl

allprojects {
    group = publicGroupId
    version = "0.1.0"
}

tasks.register("printModules") {
    doLast {
        println("Modules: locator-runtime, locator-runtime-android, studio-plugin, demo-app")
        println("Included build: locator-gradle-plugin")
    }
}

tasks.register("generateExternalComposeLocatorAarFixture") {
    group = "verification"
    description = "Generates a tiny external AAR that carries Compose Locator metadata under META-INF."

    val fixtureDir = layout.buildDirectory.dir("fixtures/external-compose-locator-aar")
    val manifestText = """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.codelocator.external.fixture" />"""
    val fixtureMetadata = """
        {
          "version": 1,
          "producerPath": "external:compose-locator-fixture",
          "entries": [
            {"sourceId": 910000000000000001, "symbol": "Button", "relativePath": "external-fixture/src/main/kotlin/dev/codelocator/external/ExternalCard.kt", "line": 24, "column": 9, "packageName": "dev.codelocator.external", "ownerClassName": "ExternalCardKt", "kind": "ModifierCallSite", "tag": null, "text": null, "role": null}
          ]
        }
    """.trimIndent()
    inputs.property("fixtureManifest", manifestText)
    inputs.property("fixtureMetadata", fixtureMetadata)
    outputs.file(fixtureDir.map { it.file("external-compose-locator-fixture.aar") })

    doLast {
        val root = fixtureDir.get().asFile
        val exploded = root.resolve("exploded")
        exploded.deleteRecursively()
        exploded.mkdirs()

        exploded.resolve("AndroidManifest.xml").writeText(manifestText)
        val classesJar = exploded.resolve("classes.jar")
        ZipOutputStream(classesJar.outputStream()).use { jar ->
            jar.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            jar.write("Manifest-Version: 1.0\n".toByteArray())
            jar.closeEntry()
        }
        val metadataFile = exploded.resolve("META-INF/compose-locator/compose-locator-metadata.json")
        metadataFile.parentFile.mkdirs()
        metadataFile.writeText(fixtureMetadata)

        val aar = root.resolve("external-compose-locator-fixture.aar")
        aar.parentFile.mkdirs()
        if (aar.exists()) aar.delete()
        ZipOutputStream(aar.outputStream()).use { zip ->
            exploded.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(exploded).invariantSeparatorsPath }
                .forEach { file ->
                    val entryName = file.relativeTo(exploded).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
        }
        println("Generated external Compose Locator AAR fixture at ${aar.absolutePath}")
    }
}

tasks.register("verifyDemoDevice") {
    group = "verification"
    description = "Installs the demo app on an adb device, starts it, forwards the locator port, and verifies HTTP locator endpoints."
    dependsOn(":demo-app:assembleDebug")

    doLast {
        fun locateAdb(): String {
            val sdkRoots = listOfNotNull(
                System.getenv("ANDROID_HOME"),
                System.getenv("ANDROID_SDK_ROOT"),
            )
            sdkRoots
                .map { file("$it/platform-tools/adb") }
                .firstOrNull { it.canExecute() }
                ?.let { return it.absolutePath }
            return "adb"
        }

        fun execAndCapture(args: List<String>): String {
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val result = project.exec {
                commandLine(args)
                standardOutput = stdout
                errorOutput = stderr
                isIgnoreExitValue = true
            }
            val out = stdout.toString()
            val err = stderr.toString()
            if (result.exitValue != 0) {
                throw GradleException(
                    "Command failed (${result.exitValue}): ${args.joinToString(" ")}\n" +
                        (err.ifBlank { out }).trim(),
                )
            }
            return out
        }

        fun runAdb(args: List<String>): String {
            val output = execAndCapture(args)
            if (output.isNotBlank()) {
                println(output.trim())
            }
            return output
        }

        fun httpGet(url: String): String {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 1_000
            connection.readTimeout = 5_000
            return connection.inputStream.bufferedReader().use { it.readText() }
        }

        fun retryHttp(url: String): String {
            var lastError: Throwable? = null
            repeat(20) {
                try {
                    return httpGet(url)
                } catch (error: Throwable) {
                    lastError = error
                    Thread.sleep(500)
                }
            }
            throw GradleException("Unable to reach $url after starting demo app: ${lastError?.message}")
        }

        fun extractJsonInt(raw: String, key: String): Int {
            val token = "\"$key\":"
            return raw.substringAfter(token, "")
                .takeWhile { it.isDigit() }
                .toIntOrNull()
                ?: 0
        }

        fun extractLargestWindowBounds(raw: String): LongArray {
            val bounds = Regex("""bounds=\[(-?\d+),(-?\d+),(-?\d+),(-?\d+)\]""")
                .findAll(raw)
                .map { match ->
                    longArrayOf(
                        match.groupValues[1].toLong(),
                        match.groupValues[2].toLong(),
                        match.groupValues[3].toLong(),
                        match.groupValues[4].toLong(),
                    )
                }
                .toList()
            return bounds.maxByOrNull { (it[2] - it[0]).coerceAtLeast(0) * (it[3] - it[1]).coerceAtLeast(0) }
                ?: longArrayOf(0, 0, 1080, 1920)
        }

        fun centerX(node: LongArray): Long = (node[1] + node[3]) / 2L

        fun centerY(node: LongArray): Long = (node[2] + node[4]) / 2L

        val demoSourcePath = "demo-app/src/main/kotlin/dev/codelocator/demo/DemoApp.kt"
        val demoSourceLines = file(demoSourcePath).readLines()
        val featureSourcePath = "demo-feature/src/main/kotlin/dev/codelocator/demo/feature/FeatureCard.kt"
        val featureSourceLines = file(featureSourcePath).readLines()

        fun sourceLinesContaining(snippet: String): Set<Long> {
            return demoSourceLines
                .mapIndexedNotNull { index, line ->
                    if (snippet in line) (index + 1).toLong() else null
                }
                .toSet()
        }

        fun sourceLinesAfter(anchor: String, snippet: String): Set<Long> {
            val anchorIndex = demoSourceLines.indexOfFirst { anchor in it }
            if (anchorIndex < 0) {
                throw GradleException("Unable to find source anchor '$anchor'")
            }
            return demoSourceLines
                .drop(anchorIndex + 1)
                .mapIndexedNotNull { offset, line ->
                    if (snippet in line) (anchorIndex + offset + 2).toLong() else null
                }
                .toSet()
        }

        fun singleSourceLineContaining(snippet: String): Long {
            return sourceLinesContaining(snippet).singleOrNull()
                ?: throw GradleException("Expected exactly one source line containing '$snippet'")
        }

        fun sourceLineAfter(anchor: String, snippet: String): Long {
            val anchorIndex = demoSourceLines.indexOfFirst { anchor in it }
            if (anchorIndex < 0) {
                throw GradleException("Unable to find source anchor '$anchor'")
            }
            return demoSourceLines
                .drop(anchorIndex + 1)
                .mapIndexedNotNull { offset, line ->
                    if (snippet in line) (anchorIndex + offset + 2).toLong() else null
                }
                .firstOrNull()
                ?: throw GradleException("Unable to find source line containing '$snippet' after '$anchor'")
        }

        fun sourceLineBefore(anchor: String, snippet: String): Long {
            val anchorIndex = demoSourceLines.indexOfFirst { anchor in it }
            if (anchorIndex < 0) {
                throw GradleException("Unable to find source anchor '$anchor'")
            }
            for (index in anchorIndex downTo 0) {
                if (snippet in demoSourceLines[index]) {
                    return (index + 1).toLong()
                }
            }
            throw GradleException("Unable to find source line containing '$snippet' before '$anchor'")
        }

        fun sourceLinesBefore(anchor: String, snippet: String): Set<Long> {
            val anchorIndex = demoSourceLines.indexOfFirst { anchor in it }
            if (anchorIndex < 0) {
                throw GradleException("Unable to find source anchor '$anchor'")
            }
            return demoSourceLines
                .take(anchorIndex)
                .mapIndexedNotNull { index, line ->
                    if (snippet in line) (index + 1).toLong() else null
                }
                .toSet()
        }

        fun sourceLinesBetween(startAnchor: String, endAnchor: String, snippet: String): Set<Long> {
            val startIndex = demoSourceLines.indexOfFirst { startAnchor in it }
            if (startIndex < 0) {
                throw GradleException("Unable to find source start anchor '$startAnchor'")
            }
            val endIndex = demoSourceLines
                .drop(startIndex + 1)
                .indexOfFirst { endAnchor in it }
                .takeIf { it >= 0 }
                ?.let { startIndex + 1 + it }
                ?: throw GradleException("Unable to find source end anchor '$endAnchor' after '$startAnchor'")
            return demoSourceLines
                .subList(startIndex + 1, endIndex)
                .mapIndexedNotNull { offset, line ->
                    if (snippet in line) (startIndex + offset + 2).toLong() else null
                }
                .toSet()
        }

        fun sourceLineIn(lines: List<String>, anchor: String, snippet: String): Long {
            val anchorIndex = lines.indexOfFirst { anchor in it }
            if (anchorIndex < 0) {
                throw GradleException("Unable to find source anchor '$anchor'")
            }
            return lines
                .drop(anchorIndex + 1)
                .mapIndexedNotNull { offset, line ->
                    if (snippet in line) (anchorIndex + offset + 2).toLong() else null
                }
                .firstOrNull()
                ?: throw GradleException("Unable to find source line containing '$snippet' after '$anchor'")
        }

        data class SnapshotNode(
            val id: Long,
            val left: Long,
            val top: Long,
            val right: Long,
            val bottom: Long,
            val sourceId: Long?,
            val relativePath: String?,
            val line: Long?,
            val symbol: String?,
            val text: String?,
            val role: String?,
            val windowLayer: Int,
        )

        fun nodeArea(node: SnapshotNode): Long {
            return (node.right - node.left).coerceAtLeast(0) * (node.bottom - node.top).coerceAtLeast(0)
        }

        fun unescapeJson(value: String): String {
            return value
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
        }

        fun loadStudioIndexSources(): Map<Long, Triple<String, Long, String?>> {
            val shards = file("demo-app/build/intermediates/composeLocator/studio-index/v1/shards")
            if (!shards.isDirectory) {
                throw GradleException("Missing Studio source index shards at ${shards.absolutePath}")
            }
            val regex = Regex(
                """\{"sourceId":(\d+),"relativePath":"((?:\\.|[^"\\])*)","line":(\d+),"column":\d+,"symbol":"((?:\\.|[^"\\])*)","kind":"[^"]+"\}""",
            )
            return shards.listFiles()
                .orEmpty()
                .filter { it.isFile && it.extension == "jsonl" }
                .flatMap { shard ->
                    shard.readLines().mapNotNull { line ->
                        val match = regex.matchEntire(line) ?: return@mapNotNull null
                        match.groupValues[1].toLong() to Triple(
                            unescapeJson(match.groupValues[2]),
                            match.groupValues[3].toLong(),
                            unescapeJson(match.groupValues[4]),
                        )
                    }
                }
                .toMap()
        }
        val studioIndexSources = loadStudioIndexSources()

        fun hitTestSourceIds(hitTest: String): Set<Long> {
            return Regex(""""sourceId":(\d+)""")
                .findAll(hitTest)
                .map { it.groupValues[1].toLong() }
                .toSet()
        }

        fun hitTestResolvesToLine(hitTest: String, line: Long): Boolean {
            return hitTestSourceIds(hitTest).any { sourceId -> studioIndexSources[sourceId]?.second == line }
        }

        fun hitTestResolvesToPath(hitTest: String, relativePath: String): Boolean {
            return hitTestSourceIds(hitTest).any { sourceId -> studioIndexSources[sourceId]?.first == relativePath }
        }

        fun parseSnapshotNodes(snapshot: String): List<SnapshotNode> {
            val regex = Regex(
                """\{"id":(\d+),"bounds":\[(-?\d+),(-?\d+),(-?\d+),(-?\d+)\],"zIndex":[^,]+,"sourceId":(\d+|null),"semanticsTag":(?:"[^"]*"|null),"text":(?:"((?:\\.|[^"\\])*)"|null),"role":(?:"((?:\\.|[^"\\])*)"|null),"composableName":(?:"(?:\\.|[^"\\])*"|null),"flags":\d+,"windowId":-?\d+,"windowTitle":(?:"(?:\\.|[^"\\])*"|null),"windowLayer":(-?\d+)\}""",
            )
            return regex.findAll(snapshot).map { match ->
                val sourceId = match.groupValues[6].toLongOrNull()
                val indexed = sourceId?.let(studioIndexSources::get)
                SnapshotNode(
                    id = match.groupValues[1].toLong(),
                    left = match.groupValues[2].toLong(),
                    top = match.groupValues[3].toLong(),
                    right = match.groupValues[4].toLong(),
                    bottom = match.groupValues[5].toLong(),
                    sourceId = sourceId,
                    relativePath = indexed?.first,
                    line = indexed?.second,
                    symbol = indexed?.third,
                    text = match.groupValues[7].ifBlank { null }?.let(::unescapeJson),
                    role = match.groupValues[8].ifBlank { null }?.let(::unescapeJson),
                    windowLayer = match.groupValues[9].toInt(),
                )
            }.toList()
        }

        fun SnapshotNode.toLongArray(): LongArray {
            return longArrayOf(
                id,
                left,
                top,
                right,
                bottom,
                sourceId ?: 0L,
                line ?: 0L,
            )
        }

        fun findSourceBackedExactTextNode(snapshot: String, text: String, role: String? = null): LongArray {
            val node = parseSnapshotNodes(snapshot)
                .filter { it.sourceId != null && it.text == text && (role == null || it.role == role) }
                .sortedWith(compareByDescending<SnapshotNode> { it.windowLayer }.thenBy { (it.right - it.left) * (it.bottom - it.top) })
                .firstOrNull()
                ?: throw GradleException("Unable to find source-backed text node text=$text role=$role in snapshot: $snapshot")
            return node.toLongArray()
        }

        fun findSourceBackedLineNode(
            snapshot: String,
            line: Long,
            relativePath: String = demoSourcePath,
            role: String? = null,
            topWindowOnly: Boolean = false,
        ): LongArray {
            val parsed = parseSnapshotNodes(snapshot)
            val topWindowLayer = parsed.maxOfOrNull { it.windowLayer }
            val candidates = parsed
                .filter {
                    it.sourceId != null &&
                        it.relativePath == relativePath &&
                        it.line == line &&
                        (role == null || it.role == role) &&
                        (!topWindowOnly || topWindowLayer == null || it.windowLayer == topWindowLayer)
                }
            val comparator = if (role != null) {
                compareByDescending<SnapshotNode> { it.windowLayer }
                    .thenByDescending { it.role != null }
                    .thenByDescending { nodeArea(it) }
                    .thenBy { it.id }
            } else {
                // For non-role source markers, smaller modifier nodes usually map closer to the clicked visual.
                compareByDescending<SnapshotNode> { it.windowLayer }
                    .thenByDescending { it.role != null }
                    .thenBy { nodeArea(it) }
                    .thenBy { it.id }
            }
            val node = candidates
                .sortedWith(comparator)
                .firstOrNull()
                ?: throw GradleException("Unable to find source-backed line node $relativePath:$line role=$role topWindowOnly=$topWindowOnly in snapshot: $snapshot")
            return node.toLongArray()
        }

        fun describeSourceLine(snapshot: String, line: Long, relativePath: String = demoSourcePath): String {
            val nodes = parseSnapshotNodes(snapshot)
                .filter { it.sourceId != null && it.relativePath == relativePath && it.line == line }
                .sortedWith(
                    compareByDescending<SnapshotNode> { it.windowLayer }
                        .thenByDescending { it.role != null }
                        .thenByDescending { nodeArea(it) }
                        .thenBy { it.id },
                )
            if (nodes.isEmpty()) return "missing"
            return nodes.joinToString(limit = 4) {
                "id=${it.id},bounds=[${it.left},${it.top},${it.right},${it.bottom}],role=${it.role},layer=${it.windowLayer},symbol=${it.symbol}"
            }
        }

        fun findSourceBackedLineNodes(
            snapshot: String,
            line: Long,
            relativePath: String = demoSourcePath,
        ): List<LongArray> {
            return parseSnapshotNodes(snapshot)
                .filter { it.sourceId != null && it.relativePath == relativePath && it.line == line }
                .map { it.toLongArray() }
        }

        fun hasSourceBackedLineNode(
            snapshot: String,
            line: Long,
            relativePath: String = demoSourcePath,
        ): Boolean {
            return parseSnapshotNodes(snapshot).any { it.sourceId != null && it.relativePath == relativePath && it.line == line }
        }

        fun waitForNode(description: String, timeoutAttempts: Int = 30, finder: (String) -> LongArray): Pair<String, LongArray> {
            var lastSnapshot = ""
            repeat(timeoutAttempts) {
                lastSnapshot = retryHttp("http://127.0.0.1:49391/snapshot")
                runCatching { finder(lastSnapshot) }.getOrNull()?.let { return lastSnapshot to it }
                Thread.sleep(500)
            }
            throw GradleException("Unable to find $description in locator snapshot: $lastSnapshot")
        }

        fun waitForNodeAfterTap(
            description: String,
            adb: String,
            serial: String,
            trigger: () -> LongArray,
            finder: (String) -> LongArray,
        ): Pair<String, LongArray> {
            var lastError: Throwable? = null
            repeat(3) {
                val triggerNode = trigger()
                runAdb(listOf(adb, "-s", serial, "shell", "input", "tap", centerX(triggerNode).toString(), centerY(triggerNode).toString()))
                runCatching {
                    return waitForNode(description, timeoutAttempts = 12, finder = finder)
                }.onFailure { error ->
                    lastError = error
                    runAdb(listOf(adb, "-s", serial, "shell", "input", "keyevent", "BACK"))
                    Thread.sleep(250)
                }
            }
            throw GradleException("Unable to open and find $description after tapping trigger: ${lastError?.message}")
        }

        fun waitForNodeAbsent(description: String, timeoutAttempts: Int = 30, finder: (String) -> LongArray): String {
            var lastSnapshot = ""
            repeat(timeoutAttempts) {
                lastSnapshot = retryHttp("http://127.0.0.1:49391/snapshot")
                if (runCatching { finder(lastSnapshot) }.isFailure) return lastSnapshot
                Thread.sleep(250)
            }
            throw GradleException("Expected $description to disappear from locator snapshot: $lastSnapshot")
        }

        fun findSourceBackedTextNode(snapshot: String, text: String, role: String): LongArray {
            val regex = Regex(
                """\{"id":(\d+),"bounds":\[(-?\d+),(-?\d+),(-?\d+),(-?\d+)\],"zIndex":[^,]+,"sourceId":(\d+),"semanticsTag":[^,]*,"text":"${Regex.escape(text)}","role":"${Regex.escape(role)}"""",
            )
            val match = regex.find(snapshot)
                ?: throw GradleException("Unable to find source-backed semantics node text=$text role=$role in snapshot: $snapshot")
            val sourceId = match.groupValues[6].toLong()
            return longArrayOf(
                match.groupValues[1].toLong(),
                match.groupValues[2].toLong(),
                match.groupValues[3].toLong(),
                match.groupValues[4].toLong(),
                match.groupValues[5].toLong(),
                sourceId,
                studioIndexSources[sourceId]?.second ?: 0L,
            )
        }

        fun findSourceBackedTextNodes(snapshot: String, text: String, role: String): List<LongArray> {
            val regex = Regex(
                """\{"id":(\d+),"bounds":\[(-?\d+),(-?\d+),(-?\d+),(-?\d+)\],"zIndex":[^,]+,"sourceId":(\d+),"semanticsTag":[^,]*,"text":"${Regex.escape(text)}","role":"${Regex.escape(role)}"""",
            )
            return regex.findAll(snapshot).map { match ->
                val sourceId = match.groupValues[6].toLong()
                longArrayOf(
                    match.groupValues[1].toLong(),
                    match.groupValues[2].toLong(),
                    match.groupValues[3].toLong(),
                    match.groupValues[4].toLong(),
                    match.groupValues[5].toLong(),
                    sourceId,
                    studioIndexSources[sourceId]?.second ?: 0L,
                )
            }.toList()
        }

        fun findConfirmSymbols(snapshot: String): List<String> {
            return parseSnapshotNodes(snapshot)
                .filter { it.sourceId != null && it.text == "确认" && it.role == "button" }
                .mapNotNull { it.symbol }
        }

        fun findSourceBackedPrefixTextNode(snapshot: String, textPrefix: String): LongArray {
            val regex = Regex(
                """\{"id":(\d+),"bounds":\[(-?\d+),(-?\d+),(-?\d+),(-?\d+)\],"zIndex":[^,]+,"sourceId":(\d+),"semanticsTag":[^,]*,"text":"${Regex.escape(textPrefix)}[^"]*","role":null""",
            )
            val match = regex.find(snapshot)
                ?: throw GradleException("Unable to find source-backed semantics node textPrefix=$textPrefix in snapshot: $snapshot")
            val sourceId = match.groupValues[6].toLong()
            return longArrayOf(
                match.groupValues[1].toLong(),
                match.groupValues[2].toLong(),
                match.groupValues[3].toLong(),
                match.groupValues[4].toLong(),
                match.groupValues[5].toLong(),
                sourceId,
                studioIndexSources[sourceId]?.second ?: 0L,
            )
        }

        val registeredNodesLine = sourceLineBefore("text = \"Registered nodes:", "Text(")
        val popupCtaLine = sourceLineAfter("private fun FloatingProbePopup", "Button(")
        val followLine = sourceLineAfter("private fun ActionRow", "Button(")
        val messageLine = sourceLinesAfter("private fun ActionRow", "Button(")
            .drop(1)
            .firstOrNull()
            ?: throw GradleException("Unable to find Message Button call site")
        val featureCtaLine = sourceLineIn(featureSourceLines, "fun FeatureCard", "Button(onClick = {})")
        val openMenuLine = sourceLineAfter("private fun OverlayRegressionControls", "Button(")
        val openDialogLine = sourceLineBefore("onClick = onShowDialog", "Button(")
        val openPopupLine = sourceLineBefore("onClick = onShowPopup", "Button(")
        val dropdownAlphaLine = sourceLineAfter("DropdownMenu(", "DropdownMenuItem(")
        val dropdownDynamicLine = sourceLineAfter("val dynamicMenuText", "DropdownMenuItem(")
        val dialogConfirmLine = sourceLineAfter("private fun DialogRegressionProbe", "Button(onClick = onDismiss)")
        val dialogDynamicTextLine = sourceLineAfter("private fun DialogRegressionProbe", "text = { Text(dynamicDialogText) }")
        val lazyGridButtonLine = sourceLineAfter("gridItems(cells)", "Button(onClick = {})")
        val navDetailsTriggerLine = sourceLineAfter("private fun NavHostRegressionProbe", "Button(")
        val navHomeButtonLine = sourceLineAfter("private fun NavHomeProbe", "Button(onClick = {})")
        val navDetailsButtonLine = sourceLineAfter("private fun NavDetailsProbe", "Button(onClick = {})")
        val navDetailsDynamicLine = sourceLineAfter("private fun NavDetailsProbe", "Text(")
        val designPrimaryLine = sourceLineAfter("private fun DesignSystemWrapperRegressionProbe", "DesignSystemPrimaryButton(")
        val designBoundaryLine = sourceLineAfter("private fun DesignSystemWrapperRegressionProbe", "DesignSystemNoModifierButton(")
        val designBoundaryFallbackLine = sourceLineAfter("private fun DesignSystemNoModifierButton", "Button(onClick = {})")
        val designMenuLine = sourceLineAfter("trigger = {", "Button(")
        val designDialogLine = sourceLineAfter("text = { Text(designMenuText) }", "Button(")
        val designMenuItemLines = sourceLinesAfter("DesignSystemDropdownShell(", "DropdownMenuItem(").toList().sorted()
        val designMenuAlphaLine = designMenuItemLines.getOrNull(0)
            ?: throw GradleException("Unable to find design-system fixed DropdownMenuItem call site")
        val designMenuDynamicLine = designMenuItemLines.getOrNull(1)
            ?: throw GradleException("Unable to find design-system dynamic DropdownMenuItem call site")
        val designDialogConfirmLine = sourceLineAfter("private fun DesignSystemDialogProbe", "Button(onClick = onDismiss)")
        val designDialogDynamicTextLine = sourceLineAfter("private fun DesignSystemDialogProbe", "body = { Text(dynamicDialogText) }")
        val nestedDialogTriggerLine = sourceLineAfter("private fun AdvancedWindowRegressionControls", "Button(")
        val bottomSheetTriggerLine = sourceLinesAfter("private fun AdvancedWindowRegressionControls", "Button(")
            .drop(1)
            .firstOrNull()
            ?: throw GradleException("Unable to find Bottom sheet Button call site")
        val androidViewLine = sourceLineAfter("private fun AndroidViewRegressionProbe", "AndroidView(")
        val nestedDialogConfirmLine = sourceLineAfter("private fun NestedOverlayDialogProbe", "Button(onClick = onDismiss)")
        val nestedDialogDynamicTextLine = sourceLineAfter("private fun NestedOverlayDialogProbe", "Text(dynamicNestedText)")
        val nestedMenuTriggerLine = sourceLineAfter("private fun NestedOverlayDialogProbe", "Button(")
        val nestedMenuItemLine = sourceLineAfter("private fun NestedOverlayDialogProbe", "DropdownMenuItem(")
        val bottomSheetConfirmLine = sourceLineAfter("private fun BottomSheetRegressionProbe", "Button(onClick = onDismiss)")
        val bottomSheetDynamicTextLine = sourceLineAfter("private fun BottomSheetRegressionProbe", "Text(dynamicSheetText)")
        val directConfirmButtonLines = sourceLinesBetween("text = \"P0 regression probes\"", "PrimaryActionButton(", "Button(")
        val customConfirmButtonLines = sourceLinesBefore("private fun PrimaryActionButton", "PrimaryActionButton(")
        val noModifierConfirmLines = sourceLinesContaining("NoModifierConfirmAction(text = \"确认\")")
        val noModifierConfirmFallbackLine = sourceLineAfter("private fun NoModifierConfirmAction", "Button(onClick = {})")
        val lazyConfirmButtonLine = sourceLineAfter("private fun ConfirmListRow", "Button(onClick = {})")

        fun waitForSourceBackedNodes(): String {
            var lastDiagnostic = ""
            repeat(30) {
                val snapshot = retryHttp("http://127.0.0.1:49391/snapshot")
                val required = listOf(
                    Triple("Follow button", followLine, demoSourcePath),
                    Triple("Message button", messageLine, demoSourcePath),
                    Triple("Feature CTA", featureCtaLine, featureSourcePath),
                    Triple("Open menu", openMenuLine, demoSourcePath),
                    Triple("Open dialog", openDialogLine, demoSourcePath),
                    Triple("Open popup", openPopupLine, demoSourcePath),
                    Triple("Design primary", designPrimaryLine, demoSourcePath),
                    Triple("Design menu", designMenuLine, demoSourcePath),
                    Triple("Design dialog", designDialogLine, demoSourcePath),
                    Triple("Registered nodes text", registeredNodesLine, demoSourcePath),
                )
                val missingRequired = required.filterNot {
                    hasSourceBackedLineNode(snapshot, line = it.second, relativePath = it.third)
                }
                val confirmLines = (
                    directConfirmButtonLines +
                        customConfirmButtonLines +
                        setOf(lazyConfirmButtonLine)
                    )
                val missingConfirmLines = confirmLines.filterNot { line ->
                    hasSourceBackedLineNode(snapshot, line)
                }
                val noModifierConfirmNodes = noModifierConfirmLines.all { line ->
                    hasSourceBackedLineNode(snapshot, line)
                } || hasSourceBackedLineNode(snapshot, noModifierConfirmFallbackLine)
                val hasDesignBoundary = hasSourceBackedLineNode(snapshot, designBoundaryLine) ||
                    hasSourceBackedLineNode(snapshot, designBoundaryFallbackLine)
                if (missingRequired.isEmpty() && hasDesignBoundary && missingConfirmLines.isEmpty() && noModifierConfirmNodes) {
                    return snapshot
                }
                lastDiagnostic = buildString {
                    if (missingRequired.isNotEmpty()) {
                        appendLine("Missing required initial lines:")
                        missingRequired.forEach {
                            appendLine("- ${it.first}: ${it.third}:${it.second} -> ${describeSourceLine(snapshot, it.second, it.third)}")
                        }
                    }
                    if (missingConfirmLines.isNotEmpty()) {
                        appendLine("Missing duplicate confirm lines: ${missingConfirmLines.joinToString()}")
                    }
                    if (!noModifierConfirmNodes) {
                        appendLine("Missing no-modifier confirm lines: ${noModifierConfirmLines.joinToString()} or fallback $noModifierConfirmFallbackLine")
                    }
                    if (!hasDesignBoundary) {
                        appendLine("Missing design boundary lines: $designBoundaryLine or fallback $designBoundaryFallbackLine")
                    }
                    appendLine("Snapshot node count: ${parseSnapshotNodes(snapshot).size}")
                }.trim()
                Thread.sleep(500)
            }
            throw GradleException("Locator snapshot did not expose required initial source-backed lines.\n$lastDiagnostic")
        }

        fun waitForLocatorReady(): String {
            var lastHealth = ""
            repeat(30) {
                lastHealth = retryHttp("http://127.0.0.1:49391/health")
                val nodeCount = extractJsonInt(lastHealth, "nodeCount")
                if (nodeCount > 0) {
                    return lastHealth
                }
                Thread.sleep(500)
            }
            throw GradleException("Locator server stayed empty after app launch: $lastHealth")
        }

        val adb = locateAdb()
        val devicesOutput = execAndCapture(listOf(adb, "devices", "-l"))
        val devices = devicesOutput
            .lineSequence()
            .drop(1)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.getOrNull(1) == "device") parts.firstOrNull() else null
            }
            .toList()

        val requestedSerial = providers.environmentVariable("CODELOCATOR_DEVICE_SERIAL").orNull
        val serial = when {
            requestedSerial != null && requestedSerial in devices -> requestedSerial
            requestedSerial != null -> throw GradleException(
                "CODELOCATOR_DEVICE_SERIAL=$requestedSerial is not connected. Connected devices: ${devices.ifEmpty { listOf("<none>") }.joinToString()}",
            )
            devices.size == 1 -> devices.single()
            devices.isEmpty() -> throw GradleException("No connected adb device/emulator. Start an emulator or connect a device, then rerun verifyDemoDevice.")
            else -> throw GradleException(
                "Multiple adb devices connected: ${devices.joinToString()}. Set CODELOCATOR_DEVICE_SERIAL to the target serial.",
            )
        }
        val apk = file("demo-app/build/outputs/apk/debug/demo-app-debug.apk")
        check(apk.isFile) { "Missing demo APK at ${apk.absolutePath}" }

        println("Using adb device: $serial")
        runAdb(listOf(adb, "-s", serial, "install", "-r", apk.absolutePath))
        runAdb(listOf(adb, "-s", serial, "shell", "am", "force-stop", "dev.codelocator.demo"))
        runAdb(listOf(adb, "-s", serial, "shell", "am", "start", "-n", "dev.codelocator.demo/.MainActivity"))
        runAdb(listOf(adb, "-s", serial, "forward", "tcp:49391", "tcp:49391"))

        val health = waitForLocatorReady()
        val interactionBounds = extractLargestWindowBounds(health)
        fun swipe(startPercent: Long, endPercent: Long, durationMs: Int = 450) {
            val left = interactionBounds[0]
            val top = interactionBounds[1]
            val right = interactionBounds[2]
            val bottom = interactionBounds[3]
            val x = ((left + right) / 2L).toString()
            val startY = (top + ((bottom - top) * startPercent) / 100L).toString()
            val endY = (top + ((bottom - top) * endPercent) / 100L).toString()
            runAdb(listOf(adb, "-s", serial, "shell", "input", "swipe", x, startY, x, endY, durationMs.toString()))
        }

        fun swipeUp(durationMs: Int = 450) {
            swipe(startPercent = 82L, endPercent = 30L, durationMs = durationMs)
        }

        fun swipeDown(durationMs: Int = 450) {
            swipe(startPercent = 30L, endPercent = 82L, durationMs = durationMs)
        }

        fun waitForLineNodeWithScroll(
            description: String,
            line: Long,
            role: String? = null,
            relativePath: String = demoSourcePath,
            scroll: () -> Unit,
            maxScrolls: Int = 6,
        ): Pair<String, LongArray> {
            var lastSnapshot = ""
            repeat(maxScrolls + 1) { attempt ->
                lastSnapshot = retryHttp("http://127.0.0.1:49391/snapshot")
                runCatching {
                    findSourceBackedLineNode(lastSnapshot, line = line, relativePath = relativePath, role = role)
                }.getOrNull()?.let { return lastSnapshot to it }
                if (attempt < maxScrolls) {
                    scroll()
                    Thread.sleep(350)
                }
            }
            throw GradleException(
                "Unable to find visible $description at $relativePath:$line after scrolling. Last snapshot: $lastSnapshot",
            )
        }
        val snapshot = waitForSourceBackedNodes()
        val followNode = findSourceBackedLineNode(snapshot, followLine, role = "button")
        val messageNode = findSourceBackedLineNode(snapshot, messageLine, role = "button")
        val featureCtaNode = findSourceBackedLineNode(snapshot, featureCtaLine, relativePath = featureSourcePath, role = "button")
        val openMenuNode = findSourceBackedLineNode(snapshot, openMenuLine, role = "button")
        val openDialogNode = findSourceBackedLineNode(snapshot, openDialogLine, role = "button")
        val openPopupNode = findSourceBackedLineNode(snapshot, openPopupLine, role = "button")
        val designPrimaryNode = findSourceBackedLineNode(snapshot, designPrimaryLine, role = "button")
        val designBoundaryNode = runCatching {
            findSourceBackedLineNode(snapshot, designBoundaryLine, role = "button")
        }.getOrElse {
            findSourceBackedLineNode(snapshot, designBoundaryFallbackLine, role = "button")
        }
        val designMenuNode = findSourceBackedLineNode(snapshot, designMenuLine, role = "button")
        val designDialogNode = findSourceBackedLineNode(snapshot, designDialogLine, role = "button")
        val registeredNodesNode = findSourceBackedLineNode(snapshot, registeredNodesLine)
        val confirmNodes = (
            directConfirmButtonLines +
                customConfirmButtonLines +
                setOf(lazyConfirmButtonLine)
            ).flatMap { line -> findSourceBackedLineNodes(snapshot, line) }
            .plus(
                noModifierConfirmLines.flatMap { line -> findSourceBackedLineNodes(snapshot, line) }
                    .ifEmpty { findSourceBackedLineNodes(snapshot, noModifierConfirmFallbackLine) },
            )
        val confirmSymbols = findConfirmSymbols(snapshot)
        val hitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(messageNode)}&y=${centerY(messageNode)}")
        val featureHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(featureCtaNode)}&y=${centerY(featureCtaNode)}")
        val openMenuHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(openMenuNode)}&y=${centerY(openMenuNode)}")
        val openDialogHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(openDialogNode)}&y=${centerY(openDialogNode)}")
        val openPopupHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(openPopupNode)}&y=${centerY(openPopupNode)}")
        val designPrimaryHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(designPrimaryNode)}&y=${centerY(designPrimaryNode)}")
        val designBoundaryHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(designBoundaryNode)}&y=${centerY(designBoundaryNode)}")
        val designMenuHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(designMenuNode)}&y=${centerY(designMenuNode)}")
        val designDialogHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(designDialogNode)}&y=${centerY(designDialogNode)}")
        val dynamicTextHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(registeredNodesNode)}&y=${centerY(registeredNodesNode)}")
        val confirmHitTests = mutableListOf<String>()
        confirmNodes.forEach { node ->
            confirmHitTests += retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(node)}&y=${centerY(node)}")
        }
        val (_, popupCtaNode) = waitForNodeAfterTap(
            description = "Popup CTA button",
            adb = adb,
            serial = serial,
            trigger = {
                runCatching {
                    findSourceBackedLineNode(retryHttp("http://127.0.0.1:49391/snapshot"), openPopupLine, role = "button")
                }.getOrElse { openPopupNode }
            },
        ) {
            findSourceBackedLineNode(it, popupCtaLine, role = "button", topWindowOnly = true)
        }
        val popupHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(popupCtaNode)}&y=${centerY(popupCtaNode)}")
        runAdb(listOf(adb, "-s", serial, "shell", "input", "keyevent", "BACK"))
        waitForNodeAbsent("Popup CTA button") {
            findSourceBackedLineNode(it, popupCtaLine, role = "button", topWindowOnly = true)
        }
        runAdb(listOf(adb, "-s", serial, "shell", "input", "tap", centerX(openMenuNode).toString(), centerY(openMenuNode).toString()))
        val (dropdownSnapshot, dropdownNode) = waitForNode("DropdownMenu item Menu dynamic beta") {
            findSourceBackedLineNode(it, dropdownDynamicLine, topWindowOnly = true)
        }
        val dropdownAlphaNode = findSourceBackedLineNode(dropdownSnapshot, dropdownAlphaLine, topWindowOnly = true)
        val dropdownHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(dropdownNode)}&y=${centerY(dropdownNode)}")
        val dropdownAlphaHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(dropdownAlphaNode)}&y=${centerY(dropdownAlphaNode)}")
        runAdb(listOf(adb, "-s", serial, "shell", "input", "keyevent", "BACK"))
        val afterDropdownSnapshot = waitForNodeAbsent("DropdownMenu item Menu dynamic beta") {
            findSourceBackedLineNode(it, dropdownDynamicLine, topWindowOnly = true)
        }
        val refreshedOpenDialogNode = findSourceBackedLineNode(afterDropdownSnapshot, openDialogLine, role = "button")

        runAdb(listOf(adb, "-s", serial, "shell", "input", "tap", centerX(refreshedOpenDialogNode).toString(), centerY(refreshedOpenDialogNode).toString()))
        val (dialogSnapshot, dialogConfirmNode) = waitForNode("Dialog confirm button") {
            findSourceBackedLineNode(it, dialogConfirmLine, role = "button", topWindowOnly = true)
        }
        val dialogDynamicNode = findSourceBackedLineNode(dialogSnapshot, dialogDynamicTextLine, topWindowOnly = true)
        val dialogHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(dialogConfirmNode)}&y=${centerY(dialogConfirmNode)}")
        val dialogDynamicHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(dialogDynamicNode)}&y=${centerY(dialogDynamicNode)}")
        runAdb(listOf(adb, "-s", serial, "shell", "input", "keyevent", "BACK"))

        runAdb(listOf(adb, "-s", serial, "shell", "input", "tap", centerX(designMenuNode).toString(), centerY(designMenuNode).toString()))
        val (designDropdownSnapshot, designDropdownNode) = waitForNode("design-system DropdownMenu item DS menu dynamic") {
            findSourceBackedLineNode(it, designMenuDynamicLine, topWindowOnly = true)
        }
        val designDropdownAlphaNode = findSourceBackedLineNode(designDropdownSnapshot, designMenuAlphaLine, topWindowOnly = true)
        val designDropdownHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(designDropdownNode)}&y=${centerY(designDropdownNode)}")
        val designDropdownAlphaHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(designDropdownAlphaNode)}&y=${centerY(designDropdownAlphaNode)}")
        runAdb(listOf(adb, "-s", serial, "shell", "input", "keyevent", "BACK"))
        val afterDesignDropdownSnapshot = waitForNodeAbsent("design-system DropdownMenu item DS menu dynamic") {
            findSourceBackedLineNode(it, designMenuDynamicLine, topWindowOnly = true)
        }
        val refreshedDesignDialogNode = findSourceBackedLineNode(afterDesignDropdownSnapshot, designDialogLine, role = "button")

        runAdb(listOf(adb, "-s", serial, "shell", "input", "tap", centerX(refreshedDesignDialogNode).toString(), centerY(refreshedDesignDialogNode).toString()))
        val (designDialogSnapshot, designDialogConfirmNode) = waitForNode("design-system Dialog confirm button") {
            findSourceBackedLineNode(it, designDialogConfirmLine, role = "button", topWindowOnly = true)
        }
        val designDialogDynamicNode = findSourceBackedLineNode(designDialogSnapshot, designDialogDynamicTextLine, topWindowOnly = true)
        val designDialogConfirmHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(designDialogConfirmNode)}&y=${centerY(designDialogConfirmNode)}")
        val designDialogDynamicHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(designDialogDynamicNode)}&y=${centerY(designDialogDynamicNode)}")
        runAdb(listOf(adb, "-s", serial, "shell", "input", "keyevent", "BACK"))

        waitForNodeAbsent("design-system Dialog confirm button") {
            findSourceBackedLineNode(it, designDialogConfirmLine, role = "button", topWindowOnly = true)
        }
        swipeUp()
        val (advancedWindowSnapshot, refreshedNestedDialogTriggerNode) = waitForNode("advanced nested dialog trigger") {
            findSourceBackedLineNode(it, nestedDialogTriggerLine, role = "button")
        }
        val visibleBottomSheetTriggerNode = findSourceBackedLineNode(advancedWindowSnapshot, bottomSheetTriggerLine, role = "button")
        val visibleAndroidViewNode = findSourceBackedLineNode(advancedWindowSnapshot, androidViewLine)
        val lazyGridNode = findSourceBackedLineNode(advancedWindowSnapshot, lazyGridButtonLine, role = "button")
        val nestedDialogTriggerHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(refreshedNestedDialogTriggerNode)}&y=${centerY(refreshedNestedDialogTriggerNode)}")
        val bottomSheetTriggerHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(visibleBottomSheetTriggerNode)}&y=${centerY(visibleBottomSheetTriggerNode)}")
        val androidViewHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(visibleAndroidViewNode)}&y=${centerY(visibleAndroidViewNode)}")
        val lazyGridHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(lazyGridNode)}&y=${centerY(lazyGridNode)}")
        runAdb(listOf(adb, "-s", serial, "shell", "input", "tap", centerX(refreshedNestedDialogTriggerNode).toString(), centerY(refreshedNestedDialogTriggerNode).toString()))
        val (nestedDialogSnapshot, nestedDialogConfirmNode) = waitForNode("nested dialog confirm button") {
            findSourceBackedLineNode(it, nestedDialogConfirmLine, role = "button", topWindowOnly = true)
        }
        val nestedDialogDynamicNode = findSourceBackedLineNode(nestedDialogSnapshot, nestedDialogDynamicTextLine, topWindowOnly = true)
        val nestedMenuTriggerNode = findSourceBackedLineNode(nestedDialogSnapshot, nestedMenuTriggerLine, role = "button", topWindowOnly = true)
        val nestedDialogConfirmHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(nestedDialogConfirmNode)}&y=${centerY(nestedDialogConfirmNode)}")
        val nestedDialogDynamicHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(nestedDialogDynamicNode)}&y=${centerY(nestedDialogDynamicNode)}")
        val nestedMenuTriggerHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(nestedMenuTriggerNode)}&y=${centerY(nestedMenuTriggerNode)}")
        runAdb(listOf(adb, "-s", serial, "shell", "input", "tap", centerX(nestedMenuTriggerNode).toString(), centerY(nestedMenuTriggerNode).toString()))
        val (_, nestedMenuItemNode) = waitForNode("nested dialog DropdownMenu item") {
            findSourceBackedLineNode(it, nestedMenuItemLine, topWindowOnly = true)
        }
        val nestedMenuItemHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(nestedMenuItemNode)}&y=${centerY(nestedMenuItemNode)}")
        runAdb(listOf(adb, "-s", serial, "shell", "input", "keyevent", "BACK"))
        waitForNodeAbsent("nested dialog DropdownMenu item") {
            findSourceBackedLineNode(it, nestedMenuItemLine, topWindowOnly = true)
        }
        runAdb(listOf(adb, "-s", serial, "shell", "input", "keyevent", "BACK"))

        val afterNestedDialogSnapshot = waitForNodeAbsent("nested dialog confirm button") {
            findSourceBackedLineNode(it, nestedDialogConfirmLine, role = "button", topWindowOnly = true)
        }
        val (_, refreshedBottomSheetTriggerNode) = runCatching {
            afterNestedDialogSnapshot to findSourceBackedLineNode(afterNestedDialogSnapshot, bottomSheetTriggerLine, role = "button")
        }.getOrElse {
            waitForLineNodeWithScroll(
                description = "bottom sheet trigger",
                line = bottomSheetTriggerLine,
                role = "button",
                scroll = { swipeDown() },
            )
        }
        val (bottomSheetSnapshot, bottomSheetConfirmNode) = waitForNodeAfterTap(
            description = "bottom sheet confirm button",
            adb = adb,
            serial = serial,
            trigger = {
                runCatching {
                    findSourceBackedLineNode(retryHttp("http://127.0.0.1:49391/snapshot"), bottomSheetTriggerLine, role = "button")
                }.getOrElse {
                    runCatching {
                        waitForLineNodeWithScroll(
                            description = "bottom sheet trigger",
                            line = bottomSheetTriggerLine,
                            role = "button",
                            scroll = { swipeDown() },
                        ).second
                    }.getOrElse { refreshedBottomSheetTriggerNode }
                }
            },
        ) {
            findSourceBackedLineNode(it, bottomSheetConfirmLine, role = "button", topWindowOnly = true)
        }
        val bottomSheetDynamicNode = findSourceBackedLineNode(bottomSheetSnapshot, bottomSheetDynamicTextLine, topWindowOnly = true)
        val bottomSheetConfirmHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(bottomSheetConfirmNode)}&y=${centerY(bottomSheetConfirmNode)}")
        val bottomSheetDynamicHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(bottomSheetDynamicNode)}&y=${centerY(bottomSheetDynamicNode)}")
        runAdb(listOf(adb, "-s", serial, "shell", "input", "keyevent", "BACK"))

        swipeUp()
        val (_, navHomeNode) = waitForNode("NavHost home page CTA") {
            findSourceBackedLineNode(it, navHomeButtonLine, role = "button")
        }
        val navDetailsButtonNode = findSourceBackedLineNode(retryHttp("http://127.0.0.1:49391/snapshot"), navDetailsTriggerLine, role = "button")
        val navHomeHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(navHomeNode)}&y=${centerY(navHomeNode)}")
        runAdb(listOf(adb, "-s", serial, "shell", "input", "tap", centerX(navDetailsButtonNode).toString(), centerY(navDetailsButtonNode).toString()))
        val (detailsSnapshot, navDetailsCtaNode) = waitForNode("NavHost details page CTA") {
            findSourceBackedLineNode(it, navDetailsButtonLine, role = "button")
        }
        val navDetailsDynamicNode = findSourceBackedLineNode(detailsSnapshot, navDetailsDynamicLine)
        val navDetailsHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(navDetailsCtaNode)}&y=${centerY(navDetailsCtaNode)}")
        val navDetailsDynamicHitTest = retryHttp("http://127.0.0.1:49391/hitTest?x=${centerX(navDetailsDynamicNode)}&y=${centerY(navDetailsDynamicNode)}")
        println("Locator /health: $health")
        println("Locator /snapshot contains source-backed auto buttons: Follow#${followNode[0]}, Message#${messageNode[0]}, Feature CTA#${featureCtaNode[0]}")
        println("Locator /snapshot contains high-frequency probes: Open menu#${openMenuNode[0]}, Open dialog#${openDialogNode[0]}, Open popup#${openPopupNode[0]}, Grid beta#${lazyGridNode[0]}, Home CTA#${navHomeNode[0]}")
        println("Locator /snapshot contains source-backed duplicate confirms: ${confirmNodes.map { "#${it[0]}@${it[6]}" }} symbols=$confirmSymbols")
        println("Locator /hitTest page button: $hitTest")
        println("Locator /hitTest feature module: $featureHitTest")
        println("Locator /hitTest popup window: $popupHitTest")
        println("Locator /hitTest overlay controls: menu=$openMenuHitTest dialog=$openDialogHitTest popup=$openPopupHitTest")
        println("Locator /hitTest dropdown window: dynamic=$dropdownHitTest alpha=$dropdownAlphaHitTest")
        println("Locator /hitTest dialog window: button=$dialogHitTest dynamic=$dialogDynamicHitTest")
        println("Locator /hitTest design-system wrappers: primary=$designPrimaryHitTest boundary=$designBoundaryHitTest menu=$designMenuHitTest dialog=$designDialogHitTest")
        println("Locator /hitTest design-system windows: dropdownDynamic=$designDropdownHitTest dropdownAlpha=$designDropdownAlphaHitTest dialogButton=$designDialogConfirmHitTest dialogDynamic=$designDialogDynamicHitTest")
        println("Locator /hitTest advanced windows: nestedTrigger=$nestedDialogTriggerHitTest bottomSheetTrigger=$bottomSheetTriggerHitTest androidView=$androidViewHitTest")
        println("Locator /hitTest nested dialog/dropdown: button=$nestedDialogConfirmHitTest dynamic=$nestedDialogDynamicHitTest trigger=$nestedMenuTriggerHitTest item=$nestedMenuItemHitTest")
        println("Locator /hitTest bottom sheet: button=$bottomSheetConfirmHitTest dynamic=$bottomSheetDynamicHitTest")
        println("Locator /hitTest lazy grid: $lazyGridHitTest")
        println("Locator /hitTest nav host: home=$navHomeHitTest details=$navDetailsHitTest dynamic=$navDetailsDynamicHitTest")
        println("Locator /hitTest dynamic text: $dynamicTextHitTest")
        println("Locator /hitTest duplicate confirms: $confirmHitTests")

        check(health.contains("\"status\":\"ok\"")) { "Unexpected locator health response: $health" }
        check(hitTestSourceIds(hitTest).isNotEmpty()) {
            "Hit-test did not return a sourceId-backed candidate: $hitTest"
        }
        check(hitTestResolvesToLine(hitTest, messageLine) && hitTest.contains("\"role\":\"button\"")) {
            "Message button hit-test did not resolve to ActionRow Button DemoApp.kt:$messageLine: $hitTest"
        }
        check(hitTestResolvesToPath(featureHitTest, featureSourcePath)) {
            "Feature module button hit-test did not resolve through Studio index to the library module source path: $featureHitTest"
        }
        check(featureHitTest.contains("\"role\":\"button\"")) {
            "Feature module hit-test did not return a button candidate: $featureHitTest"
        }
        check(hitTestResolvesToLine(popupHitTest, popupCtaLine)) {
            "Popup window button hit-test did not resolve through Studio index to DemoApp.kt:$popupCtaLine: $popupHitTest"
        }
        check(popupHitTest.contains("\"role\":\"button\"")) {
            "Popup window hit-test did not return a button candidate: $popupHitTest"
        }
        check(popupHitTest.contains("\"windowLayer\":") && Regex(""""windowLayer":([2-9]\d*)""").containsMatchIn(popupHitTest)) {
            "Popup window hit-test must stay in top popup window and not click through: $popupHitTest"
        }
        check(hitTestResolvesToLine(dynamicTextHitTest, registeredNodesLine)) {
            "Dynamic Registered nodes text did not resolve through Studio index to DemoApp.kt:$registeredNodesLine: $dynamicTextHitTest"
        }
        check(hitTestResolvesToLine(openMenuHitTest, openMenuLine)) {
            "Dropdown trigger did not resolve through Studio index to Open menu Button call site DemoApp.kt:$openMenuLine: $openMenuHitTest"
        }
        check(hitTestResolvesToLine(openDialogHitTest, openDialogLine)) {
            "Dialog trigger did not resolve through Studio index to Open dialog Button call site DemoApp.kt:$openDialogLine: $openDialogHitTest"
        }
        check(hitTestResolvesToLine(openPopupHitTest, openPopupLine)) {
            "Popup trigger did not resolve through Studio index to Open popup Button call site DemoApp.kt:$openPopupLine: $openPopupHitTest"
        }
        check(hitTestResolvesToLine(lazyGridHitTest, lazyGridButtonLine)) {
            "LazyGrid button did not resolve through Studio index to grid item Button call site DemoApp.kt:$lazyGridButtonLine: $lazyGridHitTest"
        }
        check(hitTestResolvesToLine(navHomeHitTest, navHomeButtonLine)) {
            "NavHost home page CTA did not resolve through Studio index to NavHomeProbe Button DemoApp.kt:$navHomeButtonLine: $navHomeHitTest"
        }
        check(hitTestResolvesToLine(designPrimaryHitTest, designPrimaryLine)) {
            "Design-system Modifier wrapper did not resolve to the business wrapper call site DemoApp.kt:$designPrimaryLine: $designPrimaryHitTest"
        }
        check(
            hitTestResolvesToLine(designBoundaryHitTest, designBoundaryLine) ||
                hitTestResolvesToLine(designBoundaryHitTest, designBoundaryFallbackLine),
        ) {
            "Design-system no-Modifier wrapper did not resolve through ASM boundary to DemoApp.kt:$designBoundaryLine or wrapper Button DemoApp.kt:$designBoundaryFallbackLine: $designBoundaryHitTest"
        }
        check(hitTestResolvesToLine(designMenuHitTest, designMenuLine)) {
            "Design-system slot trigger did not resolve to inner Button call site DemoApp.kt:$designMenuLine: $designMenuHitTest"
        }
        check(hitTestResolvesToLine(designDialogHitTest, designDialogLine)) {
            "Design-system dialog trigger did not resolve to Button call site DemoApp.kt:$designDialogLine: $designDialogHitTest"
        }
        check(hitTestResolvesToLine(nestedDialogTriggerHitTest, nestedDialogTriggerLine)) {
            "Nested dialog trigger did not resolve to Button call site DemoApp.kt:$nestedDialogTriggerLine: $nestedDialogTriggerHitTest"
        }
        check(hitTestResolvesToLine(bottomSheetTriggerHitTest, bottomSheetTriggerLine)) {
            "Bottom sheet trigger did not resolve to Button call site DemoApp.kt:$bottomSheetTriggerLine: $bottomSheetTriggerHitTest"
        }
        check(hitTestResolvesToLine(androidViewHitTest, androidViewLine)) {
            "AndroidView mixed-content boundary did not resolve to AndroidView call site DemoApp.kt:$androidViewLine: $androidViewHitTest"
        }
        check(hitTestResolvesToLine(dropdownAlphaHitTest, dropdownAlphaLine)) {
            "DropdownMenu fixed item did not resolve through Studio index to DropdownMenuItem DemoApp.kt:$dropdownAlphaLine: $dropdownAlphaHitTest"
        }
        check(hitTestResolvesToLine(dropdownHitTest, dropdownDynamicLine)) {
            "DropdownMenu dynamic item did not resolve through Studio index to DropdownMenuItem DemoApp.kt:$dropdownDynamicLine: $dropdownHitTest"
        }
        check(dropdownHitTest.contains("\"windowLayer\":") && Regex(""""windowLayer":([2-9]\d*)""").containsMatchIn(dropdownHitTest)) {
            "DropdownMenu hit-test must stay in top popup window and not click through: $dropdownHitTest"
        }
        check(hitTestResolvesToLine(dialogHitTest, dialogConfirmLine)) {
            "Dialog confirm did not resolve through Studio index to DialogRegressionProbe Button DemoApp.kt:$dialogConfirmLine: $dialogHitTest"
        }
        check(hitTestResolvesToLine(dialogDynamicHitTest, dialogDynamicTextLine)) {
            "Dialog dynamic body did not resolve through Studio index/compiler marker DemoApp.kt:$dialogDynamicTextLine: $dialogDynamicHitTest"
        }
        check(dialogHitTest.contains("\"windowLayer\":") && Regex(""""windowLayer":([2-9]\d*)""").containsMatchIn(dialogHitTest)) {
            "Dialog hit-test must stay in top dialog window and not click through: $dialogHitTest"
        }
        check(hitTestResolvesToLine(designDropdownAlphaHitTest, designMenuAlphaLine)) {
            "Design-system Dropdown fixed item did not resolve to DropdownMenuItem DemoApp.kt:$designMenuAlphaLine: $designDropdownAlphaHitTest"
        }
        check(hitTestResolvesToLine(designDropdownHitTest, designMenuDynamicLine)) {
            "Design-system Dropdown dynamic item did not resolve to dynamic item DemoApp.kt:$designMenuDynamicLine: $designDropdownHitTest"
        }
        check(designDropdownHitTest.contains("\"windowLayer\":") && Regex(""""windowLayer":([2-9]\d*)""").containsMatchIn(designDropdownHitTest)) {
            "Design-system Dropdown hit-test must stay in top popup window and not click through: $designDropdownHitTest"
        }
        check(hitTestResolvesToLine(designDialogConfirmHitTest, designDialogConfirmLine)) {
            "Design-system Dialog confirm did not resolve to slot Button DemoApp.kt:$designDialogConfirmLine: $designDialogConfirmHitTest"
        }
        check(hitTestResolvesToLine(designDialogDynamicHitTest, designDialogDynamicTextLine)) {
            "Design-system Dialog dynamic body did not resolve to slot text DemoApp.kt:$designDialogDynamicTextLine: $designDialogDynamicHitTest"
        }
        check(designDialogConfirmHitTest.contains("\"windowLayer\":") && Regex(""""windowLayer":([2-9]\d*)""").containsMatchIn(designDialogConfirmHitTest)) {
            "Design-system Dialog hit-test must stay in top dialog window and not click through: $designDialogConfirmHitTest"
        }
        check(hitTestResolvesToLine(nestedDialogConfirmHitTest, nestedDialogConfirmLine)) {
            "Nested Dialog confirm did not resolve to Button DemoApp.kt:$nestedDialogConfirmLine: $nestedDialogConfirmHitTest"
        }
        check(hitTestResolvesToLine(nestedDialogDynamicHitTest, nestedDialogDynamicTextLine)) {
            "Nested Dialog dynamic text did not resolve to Text DemoApp.kt:$nestedDialogDynamicTextLine: $nestedDialogDynamicHitTest"
        }
        check(hitTestResolvesToLine(nestedMenuTriggerHitTest, nestedMenuTriggerLine)) {
            "Nested Dialog menu trigger did not resolve to Button DemoApp.kt:$nestedMenuTriggerLine: $nestedMenuTriggerHitTest"
        }
        check(hitTestResolvesToLine(nestedMenuItemHitTest, nestedMenuItemLine)) {
            "Nested Dialog DropdownMenu item did not resolve to DropdownMenuItem DemoApp.kt:$nestedMenuItemLine: $nestedMenuItemHitTest"
        }
        check(nestedMenuItemHitTest.contains("\"windowLayer\":") && Regex(""""windowLayer":([2-9]\d*)""").containsMatchIn(nestedMenuItemHitTest)) {
            "Nested Dialog DropdownMenu hit-test must stay in top popup window and not click through: $nestedMenuItemHitTest"
        }
        check(hitTestResolvesToLine(bottomSheetConfirmHitTest, bottomSheetConfirmLine)) {
            "ModalBottomSheet confirm did not resolve to Button DemoApp.kt:$bottomSheetConfirmLine: $bottomSheetConfirmHitTest"
        }
        check(hitTestResolvesToLine(bottomSheetDynamicHitTest, bottomSheetDynamicTextLine)) {
            "ModalBottomSheet dynamic text did not resolve to Text DemoApp.kt:$bottomSheetDynamicTextLine: $bottomSheetDynamicHitTest"
        }
        check(bottomSheetConfirmHitTest.contains("\"windowLayer\":") && Regex(""""windowLayer":([2-9]\d*)""").containsMatchIn(bottomSheetConfirmHitTest)) {
            "ModalBottomSheet hit-test must stay in top sheet window and not click through: $bottomSheetConfirmHitTest"
        }
        check(hitTestResolvesToLine(navDetailsHitTest, navDetailsButtonLine)) {
            "NavHost details CTA did not resolve through Studio index to NavDetailsProbe Button DemoApp.kt:$navDetailsButtonLine: $navDetailsHitTest"
        }
        check(hitTestResolvesToLine(navDetailsDynamicHitTest, navDetailsDynamicLine)) {
            "NavHost details dynamic text did not resolve through Studio index/compiler marker DemoApp.kt:$navDetailsDynamicLine: $navDetailsDynamicHitTest"
        }
        check(confirmNodes.size >= 6) {
            "Expected source-backed duplicate confirm call-site nodes, got ${confirmNodes.size}: $snapshot"
        }
        val confirmSourceIds = confirmNodes.map { it[5] }.toSet()
        check(confirmSourceIds.size >= 6) {
            "Duplicate confirm buttons collapsed to too few source IDs: ${confirmNodes.map { "${it[6]}:${it[5]}" }}"
        }
        check(directConfirmButtonLines.size == 2 && directConfirmButtonLines.all { line -> confirmNodes.any { it[6] == line } }) {
            "Direct duplicate confirm buttons did not resolve to distinct Button call sites $directConfirmButtonLines: ${confirmNodes.map { it[6] }}"
        }
        check(customConfirmButtonLines.size == 2 && customConfirmButtonLines.all { line -> confirmNodes.any { it[6] == line } }) {
            "Custom PrimaryActionButton confirms did not resolve to business call sites $customConfirmButtonLines: ${confirmNodes.map { it[6] }}"
        }
        check(
            noModifierConfirmLines.size == 2 &&
                (
                    noModifierConfirmLines.all { line -> confirmNodes.any { it[6] == line } } ||
                        confirmNodes.any { it[6] == noModifierConfirmFallbackLine }
                    ),
        ) {
            "No-modifier duplicate confirms did not resolve to call sites $noModifierConfirmLines or fallback Button line $noModifierConfirmFallbackLine: ${confirmNodes.map { it[6] }}"
        }
        check(confirmNodes.count { it[6] == lazyConfirmButtonLine } >= 2) {
            "LazyColumn duplicate confirm rows did not keep multiple source-backed nodes: ${confirmNodes.map { "${it[0]}:${it[6]}" }}"
        }
    }
}

tasks.register("verifyStableDeviceNodeIds") {
    group = "verification"
    description = "Runs verifyDemoDevice and checks that source-backed locator identities stay stable across app restarts."
    dependsOn("verifyDemoDevice")

    doLast {
        fun locateAdb(): String {
            val sdkRoots = listOfNotNull(
                System.getenv("ANDROID_HOME"),
                System.getenv("ANDROID_SDK_ROOT"),
            )
            sdkRoots
                .map { file("$it/platform-tools/adb") }
                .firstOrNull { it.canExecute() }
                ?.let { return it.absolutePath }
            return "adb"
        }

        fun execAndCapture(args: List<String>): String {
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val result = project.exec {
                commandLine(args)
                standardOutput = stdout
                errorOutput = stderr
                isIgnoreExitValue = true
            }
            if (result.exitValue != 0) {
                throw GradleException("Command failed (${result.exitValue}): ${args.joinToString(" ")}\n${stderr.toString().ifBlank { stdout.toString() }}")
            }
            return stdout.toString()
        }

        fun httpGet(url: String): String {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 1_000
            connection.readTimeout = 5_000
            return connection.inputStream.bufferedReader().use { it.readText() }
        }

        fun unescapeJson(value: String): String {
            return value
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
        }

        fun sourceLineAfter(anchor: String, snippet: String): Long {
            val lines = file("demo-app/src/main/kotlin/dev/codelocator/demo/DemoApp.kt").readLines()
            val anchorIndex = lines.indexOfFirst { anchor in it }
            if (anchorIndex < 0) {
                throw GradleException("Unable to find source anchor '$anchor'")
            }
            return lines
                .drop(anchorIndex + 1)
                .mapIndexedNotNull { offset, line ->
                    if (snippet in line) (anchorIndex + offset + 2).toLong() else null
                }
                .firstOrNull()
                ?: throw GradleException("Unable to find source line containing '$snippet' after '$anchor'")
        }

        fun sourceLinesAfter(anchor: String, snippet: String): List<Long> {
            val lines = file("demo-app/src/main/kotlin/dev/codelocator/demo/DemoApp.kt").readLines()
            val anchorIndex = lines.indexOfFirst { anchor in it }
            if (anchorIndex < 0) {
                throw GradleException("Unable to find source anchor '$anchor'")
            }
            return lines
                .drop(anchorIndex + 1)
                .mapIndexedNotNull { offset, line ->
                    if (snippet in line) (anchorIndex + offset + 2).toLong() else null
                }
        }

        val demoSourcePath = "demo-app/src/main/kotlin/dev/codelocator/demo/DemoApp.kt"
        val followLine = sourceLineAfter("private fun ActionRow", "Button(")
        val messageLine = sourceLinesAfter("private fun ActionRow", "Button(")
            .drop(1)
            .firstOrNull()
            ?: throw GradleException("Unable to find Message Button call site")
        val sourceIdToLine = file("demo-app/build/intermediates/composeLocator/studio-index/v1/shards")
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "jsonl" }
            .flatMap { shard ->
                shard.readLines().mapNotNull { line ->
                    val match = Regex(
                        """\{"sourceId":(\d+),"relativePath":"((?:\\.|[^"\\])*)","line":(\d+),"column":\d+,"symbol":"(?:\\.|[^"\\])*","kind":"[^"]+"\}""",
                    ).matchEntire(line) ?: return@mapNotNull null
                    val relativePath = unescapeJson(match.groupValues[2])
                    val sourceLine = match.groupValues[3].toLong()
                    match.groupValues[1].toLong() to (relativePath to sourceLine)
                }
            }
            .toMap()

        fun extractSourceBackedLineSourceId(snapshot: String, sourceLine: Long): Long {
            val regex = Regex(
                """\{"id":(\d+),"bounds":\[[^]]+],"zIndex":[^,]+,"sourceId":(\d+),"semanticsTag":[^,]*,"text":(?:".*?"|null),"role":"button"""",
            )
            return regex.findAll(snapshot)
                .firstOrNull { match ->
                    sourceIdToLine[match.groupValues[2].toLong()] == (demoSourcePath to sourceLine)
                }
                ?.groupValues
                ?.get(2)
                ?.toLongOrNull()
                ?: throw GradleException("Unable to find source-backed button line $demoSourcePath:$sourceLine in snapshot: $snapshot")
        }

        fun waitForSnapshot(): String {
            var lastSnapshot = ""
            var lastError: Throwable? = null
            repeat(30) {
                try {
                    lastSnapshot = httpGet("http://127.0.0.1:49391/snapshot")
                    val hasFollowButton = runCatching { extractSourceBackedLineSourceId(lastSnapshot, followLine) }.isSuccess
                    val hasMessageButton = runCatching { extractSourceBackedLineSourceId(lastSnapshot, messageLine) }.isSuccess
                    if (hasFollowButton && hasMessageButton) {
                        return lastSnapshot
                    }
                } catch (error: Throwable) {
                    lastError = error
                }
                Thread.sleep(500)
            }
            throw GradleException("Locator snapshot did not become source-backed: $lastSnapshot ${lastError?.message.orEmpty()}")
        }

        val adb = locateAdb()
        val devicesOutput = execAndCapture(listOf(adb, "devices", "-l"))
        val devices = devicesOutput
            .lineSequence()
            .drop(1)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.getOrNull(1) == "device") parts.firstOrNull() else null
            }
            .toList()
        val requestedSerial = providers.environmentVariable("CODELOCATOR_DEVICE_SERIAL").orNull
        val serial = when {
            requestedSerial != null && requestedSerial in devices -> requestedSerial
            requestedSerial != null -> throw GradleException("CODELOCATOR_DEVICE_SERIAL=$requestedSerial is not connected.")
            devices.size == 1 -> devices.single()
            devices.isEmpty() -> throw GradleException("No connected adb device/emulator.")
            else -> throw GradleException("Multiple adb devices connected: ${devices.joinToString()}. Set CODELOCATOR_DEVICE_SERIAL.")
        }

        fun startDemoAndForward() {
            execAndCapture(listOf(adb, "-s", serial, "shell", "am", "force-stop", "dev.codelocator.demo"))
            execAndCapture(listOf(adb, "-s", serial, "shell", "am", "start", "-n", "dev.codelocator.demo/.MainActivity"))
            execAndCapture(listOf(adb, "-s", serial, "forward", "tcp:49391", "tcp:49391"))
            Thread.sleep(500)
        }

        startDemoAndForward()
        val firstSnapshot = waitForSnapshot()
        val firstFollowSourceId = extractSourceBackedLineSourceId(firstSnapshot, followLine)
        val firstMessageSourceId = extractSourceBackedLineSourceId(firstSnapshot, messageLine)
        startDemoAndForward()
        val secondSnapshot = waitForSnapshot()
        val secondFollowSourceId = extractSourceBackedLineSourceId(secondSnapshot, followLine)
        val secondMessageSourceId = extractSourceBackedLineSourceId(secondSnapshot, messageLine)
        check(firstFollowSourceId == secondFollowSourceId) {
            "Expected stable Follow button sourceId, but changed from $firstFollowSourceId to $secondFollowSourceId"
        }
        check(firstMessageSourceId == secondMessageSourceId) {
            "Expected stable Message button sourceId, but changed from $firstMessageSourceId to $secondMessageSourceId"
        }
        check(firstFollowSourceId != firstMessageSourceId) {
            "Expected Follow and Message to keep distinct sourceIds, got $firstFollowSourceId"
        }
        println("Stable source-backed identities verified: Follow#$firstFollowSourceId, Message#$firstMessageSourceId")
    }
}

tasks.register("verifyComposeCallSiteMetadata") {
    group = "verification"
    description = "Verifies generated Compose call-site source metadata and IR-injected source markers are present."
    dependsOn(":demo-app:assembleDebug")

    doLast {
        val sourceMap = file("demo-app/build/intermediates/composeLocator/source-map.json").readText()
        val studioIndex = file("demo-app/build/intermediates/composeLocator/studio-index/v1")
        val appMetadata = file("demo-app/build/intermediates/composeLocator/metadata/compose-locator-metadata.json").readText()
        val featureMetadata = file("demo-feature/build/intermediates/composeLocator/metadata/compose-locator-metadata.json").readText()
        val extractedExternalAarMetadata = file("demo-app/build/intermediates/composeLocator/dependencyMetadata")
            .walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .joinToString("\n") { it.readText() }
        val featureSourcePath = "demo-feature/src/main/kotlin/dev/codelocator/demo/feature/FeatureCard.kt"
        val externalSourcePath = "external-fixture/src/main/kotlin/dev/codelocator/external/ExternalCard.kt"

        check(sourceMap.contains("\"kind\": \"ComposableCallSite\"")) {
            "Expected ComposableCallSite entries in source-map.json"
        }
        val debugClasses = file("demo-app/build/intermediates/classes/debug/transformDebugClassesWithAsm/dirs/dev/codelocator/demo")
        val locatorSourceMarker = "dev/codelocator/runtime/android/LocatorSourceMarkerKt".toByteArray()
        val locatorSourceMarkerCalls = debugClasses
            .walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .count { classFile -> classFile.readBytes().indexOf(locatorSourceMarker) >= 0 }
        check(locatorSourceMarkerCalls >= 5) {
            "Expected debug classes to contain IR-injected locatorSource marker calls, found $locatorSourceMarkerCalls"
        }
        val featureDebugClasses = file("demo-feature/build/intermediates/classes/debug/transformDebugClassesWithAsm/dirs/dev/codelocator/demo/feature")
        val featureLocatorSourceMarkerCalls = featureDebugClasses
            .walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .count { classFile -> classFile.readBytes().indexOf(locatorSourceMarker) >= 0 }
        check(featureLocatorSourceMarkerCalls >= 1) {
            "Expected feature debug classes to contain IR-injected locatorSource marker calls, found $featureLocatorSourceMarkerCalls"
        }
        check(studioIndex.resolve("source-id-index.tsv").isFile && studioIndex.resolve("shards").isDirectory) {
            "Expected local Studio source index under ${studioIndex.absolutePath}"
        }
        val studioIndexContent = studioIndex.resolve("shards")
            .walkTopDown()
            .filter { it.isFile && it.extension == "jsonl" }
            .joinToString("\n") { it.readText() }
        check(studioIndexContent.contains("\"relativePath\":\"$featureSourcePath\"") && studioIndexContent.contains("\"text\"").not()) {
            "Studio index should include feature paths but not runtime semantics lookup tables"
        }
        check(!sourceMap.contains("\"kind\": \"SemanticsText\"") && !sourceMap.contains("\"kind\": \"SemanticsTag\"") && !sourceMap.contains("\"kind\": \"LocatorNode\"")) {
            "Expected source-map.json to contain source identity entries only, not runtime semantics lookup tables"
        }
        check(!sourceMap.contains("\"relativePath\": \"$featureSourcePath\"") && !sourceMap.contains("\"relativePath\": \"$externalSourcePath\"")) {
            "Expected app source-map.json to remain module-local; dependency metadata should be indexed for Studio only"
        }
        check(featureMetadata.contains("\"producerPath\": \":demo-feature\"") && featureMetadata.contains("\"relativePath\": \"$featureSourcePath\"") && !featureMetadata.contains("\"kind\": \"SemanticsText\"")) {
            "Expected feature module to publish standalone Compose Locator metadata"
        }
        check(studioIndexContent.contains("\"relativePath\":\"$featureSourcePath\"")) {
            "Expected app module to aggregate feature module metadata into the local Studio index"
        }
        check(
            extractedExternalAarMetadata.contains("\"producerPath\": \"external:compose-locator-fixture\"") &&
                extractedExternalAarMetadata.contains("\"relativePath\": \"$externalSourcePath\"") &&
                extractedExternalAarMetadata.contains("\"kind\": \"ModifierCallSite\""),
        ) {
            "Expected app module to extract Compose Locator metadata from an external AAR META-INF entry"
        }
        check(studioIndexContent.contains("\"relativePath\":\"$externalSourcePath\"")) {
            "Expected app Studio index to include external AAR metadata"
        }
        check(appMetadata.contains("\"producerPath\": \":demo-app\"") && !appMetadata.contains(featureSourcePath)) {
            "Expected app module metadata to contain only app-owned sources; dependency metadata should be merged as an input, not by rescanning app metadata"
        }
        println("Compose source metadata and IR marker bytecode verified")
    }
}

tasks.register("verifyComposeCompilerSourceAlignment") {
    group = "verification"
    description = "Verifies compiler-injected Compose source IDs align with generated source maps."
    dependsOn(
        ":demo-app:verifyComposeCompilerSourceAlignment",
        ":demo-feature:verifyComposeCompilerSourceAlignment",
    )
}

fun ByteArray.indexOf(pattern: ByteArray): Int {
    if (pattern.isEmpty() || pattern.size > size) return -1
    for (index in 0..(size - pattern.size)) {
        var matched = true
        for (patternIndex in pattern.indices) {
            if (this[index + patternIndex] != pattern[patternIndex]) {
                matched = false
                break
            }
        }
        if (matched) return index
    }
    return -1
}

fun ByteArray.containsLongConstant(value: Long): Boolean {
    val pattern = ByteArray(8) { index ->
        ((value ushr ((7 - index) * 8)) and 0xFF).toByte()
    }
    return indexOf(pattern) >= 0
}

fun File.sizeBytesRecursive(): Long {
    return when {
        !exists() -> 0L
        isFile -> length()
        else -> walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}

fun File.nonBlankLineCount(): Int {
    return if (isFile) readLines().count { it.isNotBlank() } else 0
}

fun File.sha256Hex(): String {
    return digestHex("SHA-256")
}

fun File.sha1Hex(): String {
    return digestHex("SHA-1")
}

fun File.md5Hex(): String {
    return digestHex("MD5")
}

fun File.digestHex(algorithm: String): String {
    val selectedDigest = MessageDigest.getInstance(algorithm)
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            selectedDigest.update(buffer, 0, read)
        }
    }
    return selectedDigest.digest().joinToString(separator = "") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
}

fun escapeJson(value: String): String {
    return buildString {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}

fun humanBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024.0
    return when {
        bytes >= mb -> "%.2f MiB".format(bytes / mb)
        bytes >= kb -> "%.2f KiB".format(bytes / kb)
        else -> "$bytes B"
    }
}

fun extractVersion(script: String, pattern: Regex): String {
    return pattern.find(script)?.groupValues?.getOrNull(1)
        ?: throw GradleException("Unable to extract version with pattern $pattern")
}

data class ComposeLocatorRolloutModuleReport(
    val modulePath: String,
    val sourceMapEntries: Int,
    val metadataEntries: Int,
    val studioIndexEntries: Int,
    val shardFiles: Int,
    val largestShardEntries: Int,
    val studioIndexBytes: Long,
    val dependencyMetadataFiles: Int,
    val dependencyMetadataBytes: Long,
    val asmBoundaryEntries: Int,
    val compilerInjectedEntries: Int,
    val invalidStudioIndexRows: Int,
)

data class ParsedComposeLocatorRolloutModule(
    val modulePath: String,
    val sourceMapEntries: Int,
    val metadataEntries: Int,
    val studioIndexEntries: Int,
    val shardFiles: Int,
    val largestShardEntries: Int,
    val studioIndexBytes: Long,
    val dependencyMetadataBytes: Long,
    val invalidStudioIndexRows: Int,
)

val defaultRolloutModulesProvider = providers.gradleProperty("codelocator.rollout.modules")
    .orElse(":demo-app,:demo-feature")
val defaultRolloutModules = defaultRolloutModulesProvider.get()
    .split(",")
    .map { it.trim() }
    .filter { it.isNotEmpty() }

tasks.register("generateComposeLocatorRolloutReport") {
    group = "verification"
    description = "Generates a team-rollout readiness report from Compose Locator build intermediates."
    dependsOn(defaultRolloutModules.map { "$it:assembleDebug" })

    val reportDir = layout.buildDirectory.dir("reports/composeLocator/rollout")
    outputs.dir(reportDir)

    doLast {
        fun countSourceIds(file: File): Int {
            return if (file.isFile) Regex(""""sourceId"\s*:\s*\d+""").findAll(file.readText()).count() else 0
        }

        fun studioEntryCount(manifest: File, indexFile: File): Int {
            if (manifest.isFile) {
                Regex(""""entryCount"\s*:\s*(\d+)""")
                    .find(manifest.readText())
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?.let { return it }
            }
            return indexFile.nonBlankLineCount()
        }

        fun invalidStudioRows(shardsDir: File): Int {
            val regex = Regex(""""line":(\d+),"column":(\d+)""")
            return shardsDir.walkTopDown()
                .filter { it.isFile && it.extension == "jsonl" }
                .sumOf { shard ->
                    shard.readLines().count { line ->
                        val match = regex.find(line)
                        val sourceLine = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                        val column = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
                        sourceLine <= 0 || column <= 0
                    }
                }
        }

        val reports = defaultRolloutModules.map { modulePath ->
            val moduleProject = project(modulePath)
            val composeLocatorDir = moduleProject.layout.buildDirectory.get().asFile.resolve("intermediates/composeLocator")
            val sourceMap = composeLocatorDir.resolve("source-map.json")
            val metadata = composeLocatorDir.resolve("metadata/compose-locator-metadata.json")
            val studioIndex = composeLocatorDir.resolve("studio-index/v1")
            val shardsDir = studioIndex.resolve("shards")
            val dependencyMetadata = composeLocatorDir.resolve("dependencyMetadata")
            val compilerReport = composeLocatorDir.resolve("compiler-plugin-report.txt")
            val asmSourceMap = composeLocatorDir.resolve("asm-source-map.txt")
            val shardLineCounts = shardsDir.walkTopDown()
                .filter { it.isFile && it.extension == "jsonl" }
                .map { it.nonBlankLineCount() }
                .toList()

            ComposeLocatorRolloutModuleReport(
                modulePath = modulePath,
                sourceMapEntries = countSourceIds(sourceMap),
                metadataEntries = countSourceIds(metadata),
                studioIndexEntries = studioEntryCount(
                    manifest = studioIndex.resolve("manifest.json"),
                    indexFile = studioIndex.resolve("source-id-index.tsv"),
                ),
                shardFiles = shardLineCounts.size,
                largestShardEntries = shardLineCounts.maxOrNull() ?: 0,
                studioIndexBytes = studioIndex.sizeBytesRecursive(),
                dependencyMetadataFiles = dependencyMetadata.walkTopDown()
                    .filter { it.isFile && it.extension == "json" }
                    .count(),
                dependencyMetadataBytes = dependencyMetadata.sizeBytesRecursive(),
                asmBoundaryEntries = asmSourceMap.nonBlankLineCount(),
                compilerInjectedEntries = if (compilerReport.isFile) {
                    Regex("""injected=true""").findAll(compilerReport.readText()).count()
                } else {
                    0
                },
                invalidStudioIndexRows = invalidStudioRows(shardsDir),
            )
        }

        val totalSourceMapEntries = reports.map { it.sourceMapEntries }.sum()
        val totalStudioIndexEntries = reports.map { it.studioIndexEntries }.sum()
        val totalStudioIndexBytes = reports.map { it.studioIndexBytes }.sum()
        val totalInvalidStudioIndexRows = reports.map { it.invalidStudioIndexRows }.sum()
        val outputDir = reportDir.get().asFile
        outputDir.mkdirs()
        val json = buildString {
            appendLine("{")
            appendLine("  \"version\": 1,")
            appendLine("  \"modules\": [")
            reports.forEachIndexed { index, report ->
                appendLine("    {")
                appendLine("      \"modulePath\": \"${escapeJson(report.modulePath)}\",")
                appendLine("      \"sourceMapEntries\": ${report.sourceMapEntries},")
                appendLine("      \"metadataEntries\": ${report.metadataEntries},")
                appendLine("      \"studioIndexEntries\": ${report.studioIndexEntries},")
                appendLine("      \"shardFiles\": ${report.shardFiles},")
                appendLine("      \"largestShardEntries\": ${report.largestShardEntries},")
                appendLine("      \"studioIndexBytes\": ${report.studioIndexBytes},")
                appendLine("      \"dependencyMetadataFiles\": ${report.dependencyMetadataFiles},")
                appendLine("      \"dependencyMetadataBytes\": ${report.dependencyMetadataBytes},")
                appendLine("      \"asmBoundaryEntries\": ${report.asmBoundaryEntries},")
                appendLine("      \"compilerInjectedEntries\": ${report.compilerInjectedEntries},")
                appendLine("      \"invalidStudioIndexRows\": ${report.invalidStudioIndexRows}")
                append("    }")
                if (index != reports.lastIndex) append(",")
                appendLine()
            }
            appendLine("  ],")
            appendLine("  \"totals\": {")
            appendLine("    \"sourceMapEntries\": $totalSourceMapEntries,")
            appendLine("    \"studioIndexEntries\": $totalStudioIndexEntries,")
            appendLine("    \"studioIndexBytes\": $totalStudioIndexBytes,")
            appendLine("    \"invalidStudioIndexRows\": $totalInvalidStudioIndexRows")
            appendLine("  }")
            appendLine("}")
        }
        outputDir.resolve("rollout-report.json").writeText(json)

        val markdown = buildString {
            appendLine("# Compose Locator Rollout Report")
            appendLine()
            appendLine("Modules: ${reports.joinToString { it.modulePath }}")
            appendLine()
            appendLine("| Module | Source map | Studio index | Shards | Largest shard | Index size | ASM boundaries | Compiler injected | Invalid rows |")
            appendLine("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
            reports.forEach { report ->
                appendLine(
                    "| ${report.modulePath} | ${report.sourceMapEntries} | ${report.studioIndexEntries} | " +
                        "${report.shardFiles} | ${report.largestShardEntries} | ${humanBytes(report.studioIndexBytes)} | " +
                        "${report.asmBoundaryEntries} | ${report.compilerInjectedEntries} | ${report.invalidStudioIndexRows} |",
                )
            }
            appendLine()
            appendLine("## Totals")
            appendLine()
            appendLine("- Source map entries: $totalSourceMapEntries")
            appendLine("- Studio index entries: $totalStudioIndexEntries")
            appendLine("- Studio index size: ${humanBytes(totalStudioIndexBytes)}")
            appendLine("- Invalid Studio index rows: $totalInvalidStudioIndexRows")
            appendLine()
            appendLine("Run with `-Pcodelocator.rollout.modules=:app,:feature` to profile real project modules.")
        }
        outputDir.resolve("rollout-report.md").writeText(markdown)
        println("Compose Locator rollout report written to ${outputDir.absolutePath}")
    }
}

tasks.register("verifyComposeLocatorRolloutReadiness") {
    group = "verification"
    description = "Verifies rollout report health for source identity coverage and Studio index validity."
    dependsOn("generateComposeLocatorRolloutReport")

    doLast {
        fun budgetLong(propertyName: String, defaultValue: Long): Long {
            return providers.gradleProperty(propertyName)
                .orNull
                ?.toLongOrNull()
                ?: defaultValue
        }

        val report = layout.buildDirectory.file("reports/composeLocator/rollout/rollout-report.json").get().asFile.readText()
        val moduleObjects = Regex("""\{\s*"modulePath":\s*"([^"]+)"(.*?)\n\s*}""", RegexOption.DOT_MATCHES_ALL)
            .findAll(report.substringBefore("  \"totals\""))
            .toList()
        fun intValue(body: String, key: String): Int {
            return Regex(""""${Regex.escape(key)}":\s*(\d+)""")
                .find(body)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: throw GradleException("Rollout report module is missing '$key': $body")
        }
        fun longValue(body: String, key: String): Long {
            return Regex(""""${Regex.escape(key)}":\s*(\d+)""")
                .find(body)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?: throw GradleException("Rollout report module is missing '$key': $body")
        }
        val modules = moduleObjects.map { match ->
            val body = match.groupValues[2]
            ParsedComposeLocatorRolloutModule(
                modulePath = match.groupValues[1],
                sourceMapEntries = intValue(body, "sourceMapEntries"),
                metadataEntries = intValue(body, "metadataEntries"),
                studioIndexEntries = intValue(body, "studioIndexEntries"),
                shardFiles = intValue(body, "shardFiles"),
                largestShardEntries = intValue(body, "largestShardEntries"),
                studioIndexBytes = longValue(body, "studioIndexBytes"),
                dependencyMetadataBytes = longValue(body, "dependencyMetadataBytes"),
                invalidStudioIndexRows = intValue(body, "invalidStudioIndexRows"),
            )
        }
        val maxStudioIndexBytes = budgetLong("codelocator.rollout.maxStudioIndexBytesPerModule", 25L * 1024L * 1024L)
        val maxLargestShardEntries = budgetLong("codelocator.rollout.maxLargestShardEntries", 10_000L)
        val maxDependencyMetadataBytes = budgetLong("codelocator.rollout.maxDependencyMetadataBytesPerModule", 25L * 1024L * 1024L)

        check(modules.isNotEmpty()) {
            "Rollout report did not include any modules"
        }
        check(modules.all { it.invalidStudioIndexRows == 0 }) {
            "Rollout report contains non-navigable Studio index rows: ${modules.map { "${it.modulePath}=${it.invalidStudioIndexRows}" }}"
        }
        check(modules.all { it.studioIndexEntries > 0 }) {
            "Every rollout module must have Studio index entries: ${modules.map { "${it.modulePath}=${it.studioIndexEntries}" }}"
        }
        check(modules.all { it.sourceMapEntries > 0 }) {
            "Every rollout module must have source map entries: ${modules.map { "${it.modulePath}=${it.sourceMapEntries}" }}"
        }
        check(modules.all { it.metadataEntries == it.sourceMapEntries }) {
            "Module-local metadata must match source map entries: ${modules.map { "${it.modulePath}=source:${it.sourceMapEntries},metadata:${it.metadataEntries}" }}"
        }
        check(modules.all { it.studioIndexEntries >= it.sourceMapEntries }) {
            "Studio index must include module source entries: ${modules.map { "${it.modulePath}=source:${it.sourceMapEntries},index:${it.studioIndexEntries}" }}"
        }
        check(modules.all { it.shardFiles > 0 }) {
            "Every rollout module must have Studio index shards: ${modules.map { "${it.modulePath}=${it.shardFiles}" }}"
        }
        check(modules.all { it.studioIndexBytes <= maxStudioIndexBytes }) {
            "Studio index exceeded budget ${humanBytes(maxStudioIndexBytes)} per module: ${modules.map { "${it.modulePath}=${humanBytes(it.studioIndexBytes)}" }}"
        }
        check(modules.all { it.largestShardEntries <= maxLargestShardEntries }) {
            "Largest Studio index shard exceeded budget $maxLargestShardEntries entries: ${modules.map { "${it.modulePath}=${it.largestShardEntries}" }}"
        }
        check(modules.all { it.dependencyMetadataBytes <= maxDependencyMetadataBytes }) {
            "Dependency metadata exceeded budget ${humanBytes(maxDependencyMetadataBytes)} per module: ${modules.map { "${it.modulePath}=${humanBytes(it.dependencyMetadataBytes)}" }}"
        }
        println(
            "Compose Locator rollout readiness verified for modules: ${modules.joinToString { it.modulePath }} " +
                "(index budget ${humanBytes(maxStudioIndexBytes)}, shard budget $maxLargestShardEntries entries)",
        )
    }
}

tasks.register("verifyComposeLocatorCompatibilityMatrix") {
    group = "verification"
    description = "Verifies the team rollout compatibility matrix matches the current build baseline."

    doLast {
        fun jsonValue(json: String, key: String): String {
            return Regex(""""${Regex.escape(key)}"\s*:\s*"([^"]+)"""")
                .find(json)
                ?.groupValues
                ?.getOrNull(1)
                ?: throw GradleException("Missing '$key' in docs/compatibility-matrix.json")
        }

        fun jsonArrayValues(json: String, key: String): List<String> {
            val values = Regex(""""${Regex.escape(key)}"\s*:\s*\[([^]]*)]""")
                .find(json)
                ?.groupValues
                ?.getOrNull(1)
                ?: throw GradleException("Missing array '$key' in docs/compatibility-matrix.json")
            return Regex(""""([^"]+)"""").findAll(values).map { it.groupValues[1] }.toList()
        }

        fun jsonObjectArray(json: String, key: String): List<String> {
            val startToken = """"$key""""
            val keyIndex = json.indexOf(startToken)
            if (keyIndex < 0) throw GradleException("Missing array '$key' in docs/compatibility-matrix.json")
            val arrayStart = json.indexOf('[', keyIndex)
            if (arrayStart < 0) throw GradleException("Missing array start for '$key'")
            var depth = 0
            var arrayEnd = -1
            for (index in arrayStart until json.length) {
                when (json[index]) {
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) {
                            arrayEnd = index
                            break
                        }
                    }
                }
            }
            if (arrayEnd < 0) throw GradleException("Missing array end for '$key'")
            val arrayBody = json.substring(arrayStart + 1, arrayEnd)
            return Regex("""\{[^{}]*}""", RegexOption.DOT_MATCHES_ALL)
                .findAll(arrayBody)
                .map { it.value }
                .toList()
        }

        fun jsonObjectValue(jsonObject: String, key: String): String {
            return Regex(""""${Regex.escape(key)}"\s*:\s*(?:"([^"]+)"|(true|false))""")
                .find(jsonObject)
                ?.let { match -> match.groupValues[1].ifBlank { match.groupValues[2] } }
                ?: throw GradleException("Missing '$key' in compatibility matrix object: $jsonObject")
        }

        val rootBuild = file("build.gradle.kts").readText()
        val demoBuild = file("demo-app/build.gradle.kts").readText()
        val wrapper = file("gradle/wrapper/gradle-wrapper.properties").readText()
        val matrixFile = file("docs/compatibility-matrix.json")
        check(matrixFile.isFile) {
            "Missing compatibility matrix at ${matrixFile.absolutePath}"
        }
        val matrix = matrixFile.readText()

        val gradleVersion = Regex("""gradle-(\d+\.\d+(?:\.\d+)?)-bin\.zip""")
            .find(wrapper)
            ?.groupValues
            ?.getOrNull(1)
            ?: throw GradleException("Unable to extract Gradle wrapper version")
        val kotlinVersion = extractVersion(rootBuild, Regex("""kotlin\("jvm"\) version "([^"]+)""""))
        val composeCompilerPluginVersion = extractVersion(rootBuild, Regex("""id\("org\.jetbrains\.kotlin\.plugin\.compose"\) version "([^"]+)""""))
        val agpVersion = extractVersion(rootBuild, Regex("""id\("com\.android\.application"\) version "([^"]+)""""))
        val composeUiVersion = extractVersion(demoBuild, Regex("""androidx\.compose\.ui:ui:([^"]+)""""))
        val material3Version = extractVersion(demoBuild, Regex("""androidx\.compose\.material3:material3:([^"]+)""""))

        val actual = mapOf(
            "gradle" to gradleVersion,
            "androidGradlePlugin" to agpVersion,
            "kotlin" to kotlinVersion,
            "composeCompilerPlugin" to composeCompilerPluginVersion,
            "composeUi" to composeUiVersion,
            "material3" to material3Version,
        )
        actual.forEach { (key, value) ->
            val expected = jsonValue(matrix, key)
            check(expected == value) {
                "Compatibility matrix baseline '$key'=$expected does not match build value $value"
            }
        }

        val requiredGates = jsonArrayValues(matrix, "requiredGates")
        val missingGates = requiredGates.filterNot { taskName -> tasks.names.contains(taskName) }
        check(missingGates.isEmpty()) {
            "Compatibility matrix references missing gates: $missingGates"
        }
        val ciMatrix = jsonObjectArray(matrix, "ciMatrix").map { entry ->
            mapOf(
                "gradle" to jsonObjectValue(entry, "gradle"),
                "androidGradlePlugin" to jsonObjectValue(entry, "androidGradlePlugin"),
                "kotlin" to jsonObjectValue(entry, "kotlin"),
                "composeCompilerPlugin" to jsonObjectValue(entry, "composeCompilerPlugin"),
                "composeUi" to jsonObjectValue(entry, "composeUi"),
                "material3" to jsonObjectValue(entry, "material3"),
                "experimental" to jsonObjectValue(entry, "experimental"),
            )
        }
        check(ciMatrix.any { it["experimental"] == "false" && it["gradle"] == gradleVersion && it["androidGradlePlugin"] == agpVersion && it["kotlin"] == kotlinVersion }) {
            "Compatibility CI matrix must include the current baseline as a non-experimental lane"
        }
        val compatibilityWorkflow = file(".github/workflows/compose-locator-compatibility.yml")
        check(compatibilityWorkflow.isFile) {
            "Missing compatibility workflow at ${compatibilityWorkflow.absolutePath}"
        }
        val workflowText = compatibilityWorkflow.readText()
        val requiredWorkflowSnippets = listOf(
            "actions/upload-artifact@v4",
            "compose-locator-compatibility-gradle-\${{ matrix.gradle }}-agp-\${{ matrix.agp }}-kotlin-\${{ matrix.kotlin }}",
            "build/reports/composeLocator/**",
            "demo-app/build/intermediates/composeLocator/**",
            "demo-feature/build/intermediates/composeLocator/**",
        )
        val missingWorkflowSnippets = requiredWorkflowSnippets.filterNot(workflowText::contains)
        check(missingWorkflowSnippets.isEmpty()) {
            "Compatibility workflow is missing required diagnostics snippets: $missingWorkflowSnippets"
        }
        ciMatrix.forEach { lane ->
            check(
                """gradle: "${lane.getValue("gradle")}"""" in workflowText &&
                    """agp: "${lane.getValue("androidGradlePlugin")}"""" in workflowText &&
                    """kotlin: "${lane.getValue("kotlin")}"""" in workflowText &&
                    """composeCompiler: "${lane.getValue("composeCompilerPlugin")}"""" in workflowText &&
                    """composeUi: "${lane.getValue("composeUi")}"""" in workflowText &&
                    """material3: "${lane.getValue("material3")}"""" in workflowText &&
                    "experimental: ${lane.getValue("experimental")}" in workflowText,
            ) {
                "Compatibility workflow is missing matrix lane: $lane"
            }
        }
        println("Compose Locator compatibility matrix verified: ${actual.entries.joinToString { "${it.key}=${it.value}" }}")
    }
}

tasks.register("verifyComposeLocatorWindowRootPolicy") {
    group = "verification"
    description = "Verifies independent Compose windows are not deduped by bounds alone."

    doLast {
        val runtimeSource = file("locator-runtime-android/src/main/kotlin/dev/codelocator/runtime/android/LocatorSemanticsAutoCollector.kt")
        check(runtimeSource.isFile) {
            "Missing runtime source at ${runtimeSource.absolutePath}"
        }
        val source = runtimeSource.readText()
        val requiredSnippets = listOf(
            "val type: Int?",
            "val tokenId: Int?",
            "params.readWindowType()",
            "params.readWindowTokenId()",
            "view.javaClass.name",
            "windowInfo.title.orEmpty()",
            "windowInfo.type?.toString().orEmpty()",
            "windowInfo.tokenId?.toString().orEmpty()",
            "groupBy(WindowRoot::equivalentKey)",
        )
        val missingSnippets = requiredSnippets.filterNot(source::contains)
        check(missingSnippets.isEmpty()) {
            "Window root policy must include class/title/type/token/bounds identity. Missing snippets: $missingSnippets"
        }
        check("location[0] + view.width" in source && "location[1] + view.height" in source) {
            "Window root policy must include screen bounds in the equivalence key"
        }
        println("Compose Locator window-root dedupe policy verified")
    }
}

tasks.register("verifyComposeLocatorCiTemplate") {
    group = "verification"
    description = "Verifies the team CI workflow template covers required Compose Locator gates."

    doLast {
        val workflow = file(".github/workflows/compose-locator-ci.yml")
        check(workflow.isFile) {
            "Missing Compose Locator CI workflow template at ${workflow.absolutePath}"
        }
        val text = workflow.readText()
        val requiredSnippets = listOf(
            "pull_request:",
            "workflow_dispatch:",
            "device_serial:",
            "runs-on: macos-14",
            "./gradlew --no-daemon verifyCodeLocator",
            "-Pcodelocator.studio.useStubs=true",
            "runs-on: self-hosted",
            "CODELOCATOR_DEVICE_SERIAL",
            "./gradlew --no-daemon verifyCodeLocatorDevice",
            "build/reports/composeLocator/rollout/rollout-report.md",
            "build/composeLocator/release/release-manifest.txt",
            "compose-locator-release-archive",
            "build/composeLocator/compose-code-locator-*-release.zip",
        )
        val missingSnippets = requiredSnippets.filterNot(text::contains)
        check(missingSnippets.isEmpty()) {
            "Compose Locator CI workflow template is missing required snippets: $missingSnippets"
        }
        println("Compose Locator CI workflow template verified")
    }
}

tasks.register("verifyComposeLocatorPublishWorkflow") {
    group = "verification"
    description = "Verifies the manual public publishing workflow covers Maven Central, Plugin Portal, and Marketplace artifacts."

    doLast {
        val workflow = file(".github/workflows/publish.yml")
        check(workflow.isFile) {
            "Missing Compose Locator publish workflow at ${workflow.absolutePath}"
        }
        val text = workflow.readText()
        val requiredSnippets = listOf(
            "workflow_dispatch:",
            "publish_maven_central:",
            "publish_gradle_plugin_portal:",
            "upload_marketplace_artifact:",
            "./gradlew --no-daemon verifyComposeLocatorPublicPublishingReadiness",
            "-Pcodelocator.studio.useStubs=true",
            "SIGNING_KEY",
            "CENTRAL_PORTAL_TOKEN",
            "./gradlew --no-daemon publishComposeLocatorToMavenCentral",
            "GRADLE_PUBLISH_KEY",
            "GRADLE_PUBLISH_SECRET",
            "./gradlew --no-daemon publishComposeLocatorGradlePlugins",
            "studio-plugin/build/distributions/compose-code-locator-*.zip",
        )
        val missingSnippets = requiredSnippets.filterNot(text::contains)
        check(missingSnippets.isEmpty()) {
            "Compose Locator publish workflow is missing required snippets: $missingSnippets"
        }
        println("Compose Locator publish workflow verified")
    }
}

tasks.register("verifyComposeLocatorTeamConvention") {
    group = "verification"
    description = "Verifies the team convention plugin applies locator instrumentation and scopes runtime dependencies safely."

    doLast {
        val appProject = project(":demo-app")
        val featureProject = project(":demo-feature")

        check(appProject.plugins.hasPlugin("io.github.nianzixin.team-compose-locator")) {
            ":demo-app must use the team convention plugin"
        }
        check(featureProject.plugins.hasPlugin("io.github.nianzixin.team-compose-locator")) {
            ":demo-feature must use the team convention plugin"
        }
        check(appProject.plugins.hasPlugin("io.github.nianzixin.compose-locator")) {
            "Team convention plugin must apply core Compose Locator plugin to :demo-app"
        }
        check(featureProject.plugins.hasPlugin("io.github.nianzixin.compose-locator")) {
            "Team convention plugin must apply core Compose Locator plugin to :demo-feature"
        }

        val appDebugImplementation = appProject.configurations.getByName("debugImplementation")
        val appReleaseImplementation = appProject.configurations.getByName("releaseImplementation")
        val featureCompileOnly = featureProject.configurations.getByName("compileOnly")
        fun org.gradle.api.artifacts.Configuration.hasLocatorRuntimeDependency(): Boolean {
            return allDependencies.any { dependency ->
                dependency.group == "io.github.nianzixin" && dependency.name == "locator-runtime-android"
            } || allDependencies.any { dependency ->
                dependency is org.gradle.api.artifacts.ProjectDependency &&
                    dependency.dependencyProject.path == ":locator-runtime-android"
            }
        }

        check(appDebugImplementation.hasLocatorRuntimeDependency()) {
            "Team convention plugin must add locator runtime to :demo-app debug runtime only"
        }
        check(!appReleaseImplementation.hasLocatorRuntimeDependency()) {
            "Team convention plugin must not add locator runtime to :demo-app release runtime"
        }
        check(featureCompileOnly.hasLocatorRuntimeDependency()) {
            "Team convention plugin must add compile-only locator runtime symbols to :demo-feature"
        }
        println("Compose Locator team convention plugin verified")
    }
}

tasks.register("verifyComposeLocatorBuildEfficiency") {
    group = "verification"
    description = "Verifies default debug builds use local indexes and only the required no-Modifier bytecode fallback."
    dependsOn(":demo-app:assembleDebug")

    doLast {
        val asmSourceMap = file("demo-app/build/intermediates/composeLocator/asm-source-map.txt")
        val studioIndex = file("demo-app/build/intermediates/composeLocator/studio-index/v1")
        check(studioIndex.resolve("source-id-index.tsv").isFile) {
            "Expected local Studio source-id index under ${studioIndex.absolutePath}"
        }
        check(asmSourceMap.isFile) {
            "Expected AGP ASM source map under build/intermediates, missing ${asmSourceMap.absolutePath}"
        }
        val legacyGeneratedSources = file("demo-app/src/debug/kotlin/dev/codelocator/generated/GeneratedLocatorSources.kt")
        check(!legacyGeneratedSources.exists()) {
            "Locator runtime source catalogs must not be written into src/debug: ${legacyGeneratedSources.absolutePath}"
        }
        val inspectTask = project(":demo-app").tasks.named("inspectComposeBytecode").get()
        val instrumentTask = project(":demo-app").tasks.named("instrumentComposeBytecode").get()
        check(inspectTask.state.executed.not()) {
            "inspectComposeBytecode should be opt-in and must not run during default assembleDebug"
        }
        check(instrumentTask.state.executed.not()) {
            "instrumentComposeBytecode is a manual diagnostic task and must not run during default assembleDebug"
        }
        val asmSourceMapText = asmSourceMap.readText()
        val demoSourceLines = file("demo-app/src/main/kotlin/dev/codelocator/demo/DemoApp.kt").readLines()
        val noModifierConfirmLines = demoSourceLines
            .mapIndexedNotNull { index, line ->
                if ("NoModifierConfirmAction(text = \"确认\")" in line) index + 1 else null
            }
        check(noModifierConfirmLines.size == 2) {
            "Expected exactly two no-Modifier confirm call sites, got $noModifierConfirmLines"
        }
        noModifierConfirmLines.forEach { line ->
            check(asmSourceMapText.contains("dev/codelocator/demo/DemoAppKt#NoModifierConfirmAction#$line=")) {
                "Expected AGP ASM source map to include no-Modifier call-site entry at DemoApp.kt:$line"
            }
        }
        val debugClasses = file("demo-app/build/intermediates/classes/debug/transformDebugClassesWithAsm/dirs/dev/codelocator/demo")
        val demoAppClass = debugClasses.resolve("DemoAppKt.class")
        check(demoAppClass.isFile) {
            "Missing AGP ASM transformed DemoAppKt class at ${demoAppClass.absolutePath}"
        }
        val demoAppBytes = demoAppClass.readBytes()
        val noModifierSourceIds = noModifierConfirmLines.map { line ->
            asmSourceMapText
                .lineSequence()
                .first { it.startsWith("dev/codelocator/demo/DemoAppKt#NoModifierConfirmAction#$line=") }
                .substringAfter("=")
                .toLong()
        }
        check(noModifierSourceIds.all { sourceId -> demoAppBytes.containsLongConstant(sourceId) }) {
            "Expected AGP ASM transformed DemoAppKt.class to contain no-Modifier call-site source ids"
        }
        val locatorRuntimeEnter = "dev/codelocator/runtime/LocatorRuntime".toByteArray()
        val runtimeBoundaryClasses = debugClasses
            .walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .count { classFile -> classFile.readBytes().indexOf(locatorRuntimeEnter) >= 0 }
        check(runtimeBoundaryClasses > 0) {
            "Expected debug classes to contain AGP ASM-injected LocatorRuntime boundaries"
        }
        println("Compose locator build efficiency verified with AGP ASM transform")
    }
}

tasks.register("verifyComposeLocatorPerformance") {
    group = "verification"
    description = "Runs synthetic Compose Locator build-time performance benchmarks."
    dependsOn(
        ":locator-runtime:verifyRuntimePerformance",
        gradle.includedBuild("locator-gradle-plugin").task(":benchmarkComposeLocatorPerformance"),
    )
}

tasks.register("verifyComposeLocatorReleaseBoundary") {
    group = "verification"
    description = "Verifies release builds do not package Compose Locator runtime, source metadata, or debug server permissions."
    dependsOn(":demo-app:assembleRelease")

    doLast {
        val releaseManifest = file("demo-app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml")
        check(releaseManifest.isFile) {
            "Missing release merged manifest at ${releaseManifest.absolutePath}"
        }
        val manifestText = releaseManifest.readText()
        check(!manifestText.contains("android.permission.INTERNET")) {
            "Release manifest must not include locator debug server INTERNET permission"
        }

        val releaseApk = file("demo-app/build/outputs/apk/release/demo-app-release-unsigned.apk")
        check(releaseApk.isFile) {
            "Missing release APK at ${releaseApk.absolutePath}"
        }
        val apkEntries = ZipFile(releaseApk).use { zip ->
            zip.entries().asSequence().map { it.name }.toList()
        }
        check(apkEntries.none { it.contains("GeneratedLocatorSources") }) {
            "Release APK must not contain legacy generated locator source catalogs"
        }
        check(apkEntries.none { it == "assets/compose-locator/compose-locator-metadata.json" || it.contains("compose-locator/compose-locator-metadata") }) {
            "Release APK must not contain Compose Locator metadata"
        }
        check(apkEntries.none { it.contains("locator-runtime") || it.contains("runtime/android/Locator") }) {
            "Release APK must not contain locator runtime implementation entries"
        }
        println("Compose locator release boundary verified")
    }
}

tasks.register("stageComposeLocatorRelease") {
    group = "distribution"
    description = "Stages Maven artifacts, the Android Studio plugin ZIP, and a release manifest for team rollout."
    dependsOn(
        ":locator-runtime:publishAllPublicationsToComposeLocatorStagingRepository",
        ":locator-runtime-android:publishAllPublicationsToComposeLocatorStagingRepository",
        ":locator-compiler-plugin:publishAllPublicationsToComposeLocatorStagingRepository",
        gradle.includedBuild("locator-gradle-plugin").task(":publishAllPublicationsToComposeLocatorStagingRepository"),
        ":studio-plugin:buildStudioPluginZip",
    )

    val releaseDir = layout.buildDirectory.dir("composeLocator/release")
    val pluginZip = project(":studio-plugin").layout.buildDirectory.file("distributions/compose-code-locator-${project.version}.zip")
    val manifestFile = releaseDir.map { it.file("release-manifest.txt") }
    val checksumsFile = releaseDir.map { it.file("release-checksums.sha256") }
    val quickStartFile = releaseDir.map { it.file("README.md") }
    val chineseReadmeFile = releaseDir.map { it.file("README-CN.md") }

    outputs.file(releaseDir.map { it.file("studio-plugin/compose-code-locator-${project.version}.zip") })
    outputs.file(quickStartFile)
    outputs.file(chineseReadmeFile)
    outputs.file(releaseDir.map { it.file("docs/team-rollout.md") })
    outputs.file(releaseDir.map { it.file("docs/release-engineering.md") })
    outputs.file(releaseDir.map { it.file("docs/public-publishing.md") })
    outputs.file(releaseDir.map { it.file("docs/compatibility-matrix.json") })
    outputs.file(manifestFile)
    outputs.file(checksumsFile)
    outputs.upToDateWhen { false }

    doLast {
        val releaseRoot = releaseDir.get().asFile
        val mavenRoot = releaseRoot.resolve("maven")
        mavenRoot.resolve("dev").deleteRecursively()
        val manifest = manifestFile.get().asFile
        manifest.parentFile.mkdirs()
        manifest.writeText(
            buildString {
                appendLine("Compose Code Locator ${project.version}")
                appendLine()
                appendLine("Maven repository:")
                appendLine("  ${mavenRoot.relativeTo(projectDir).invariantSeparatorsPath}")
                appendLine()
                appendLine("Artifacts:")
                appendLine("  io.github.nianzixin:locator-runtime:${project.version}")
                appendLine("  io.github.nianzixin:locator-runtime-android:${project.version}")
                appendLine("  io.github.nianzixin:locator-compiler-plugin:${project.version}")
                appendLine("  io.github.nianzixin:locator-gradle-plugin:${project.version}")
                appendLine("  io.github.nianzixin.compose-locator:io.github.nianzixin.compose-locator.gradle.plugin:${project.version}")
                appendLine("  io.github.nianzixin.team-compose-locator:io.github.nianzixin.team-compose-locator.gradle.plugin:${project.version}")
                appendLine()
                appendLine("Android Studio plugin ZIP:")
                appendLine("  studio-plugin/compose-code-locator-${project.version}.zip")
                appendLine()
                appendLine("Documentation:")
                appendLine("  README.md")
                appendLine("  README-CN.md")
                appendLine("  docs/team-rollout.md")
                appendLine("  docs/release-engineering.md")
                appendLine("  docs/public-publishing.md")
                appendLine("  docs/compatibility-matrix.json")
                appendLine()
                appendLine("Checksums:")
                appendLine("  release-checksums.sha256")
                appendLine()
                appendLine("Required verification gates:")
                appendLine("  ./gradlew verifyCodeLocator")
                appendLine("  CODELOCATOR_DEVICE_SERIAL=<serial> ./gradlew verifyCodeLocatorDevice")
                appendLine("  ./gradlew verifyComposeLocatorRolloutReadiness -Pcodelocator.rollout.modules=:app,:feature")
                appendLine("  ./gradlew verifyComposeLocatorCiTemplate")
                appendLine("  ./gradlew verifyComposeLocatorWindowRootPolicy")
            },
        )

        val stagedPluginZip = releaseRoot.resolve("studio-plugin/compose-code-locator-${project.version}.zip")
        stagedPluginZip.parentFile.mkdirs()
        pluginZip.get().asFile.copyTo(stagedPluginZip, overwrite = true)

        quickStartFile.get().asFile.writeText(
            buildString {
                appendLine("# Compose Code Locator ${project.version} Release")
                appendLine()
                appendLine("This package contains the Maven repository, Android Studio plugin ZIP, public publishing notes, rollout documentation, compatibility matrix, and SHA-256 checksum manifest.")
                appendLine()
                appendLine("## Package Contents")
                appendLine()
                appendLine("- `maven/`: Maven artifacts and Gradle plugin marker artifacts for static Maven hosting.")
                appendLine("- `studio-plugin/compose-code-locator-${project.version}.zip`: Android Studio plugin ZIP.")
                appendLine("- `README.md`: release quick start.")
                appendLine("- `README-CN.md`: Chinese project README and architecture overview.")
                appendLine("- `docs/team-rollout.md`: team rollout guide and CI gates.")
                appendLine("- `docs/release-engineering.md`: release gates, coordinates, and versioning rules.")
                appendLine("- `docs/public-publishing.md`: website, Maven Central, Gradle Plugin Portal, and JetBrains Marketplace publishing notes.")
                appendLine("- `docs/compatibility-matrix.json`: machine-readable baseline and CI matrix.")
                appendLine("- `release-manifest.txt`: human-readable artifact manifest.")
                appendLine("- `release-checksums.sha256`: SHA-256 checksums for every staged file except itself.")
                appendLine()
                appendLine("## Gradle Integration")
                appendLine()
                appendLine("Mirror `maven/` into an internal Maven repository, then add that repository to plugin management and dependency resolution:")
                appendLine()
                appendLine("```kotlin")
                appendLine("pluginManagement {")
                appendLine("    repositories {")
                appendLine("        maven(\"https://your-internal-maven.example/compose-locator\")")
                appendLine("        google()")
                appendLine("        mavenCentral()")
                appendLine("        gradlePluginPortal()")
                appendLine("    }")
                appendLine("}")
                appendLine()
                appendLine("dependencyResolutionManagement {")
                appendLine("    repositories {")
                appendLine("        maven(\"https://your-internal-maven.example/compose-locator\")")
                appendLine("        google()")
                appendLine("        mavenCentral()")
                appendLine("    }")
                appendLine("}")
                appendLine("```")
                appendLine()
                appendLine("Apply the team plugin in app and Compose UI library modules:")
                appendLine()
                appendLine("```kotlin")
                appendLine("plugins {")
                appendLine("    id(\"io.github.nianzixin.team-compose-locator\") version \"${project.version}\"")
                appendLine("}")
                appendLine("```")
                appendLine()
                appendLine("No business UI code should add locator-specific modifiers, tags, root collectors, or Application/Activity bridges.")
                appendLine()
                appendLine("## Studio Integration")
                appendLine()
                appendLine("Install `studio-plugin/compose-code-locator-${project.version}.zip` from Android Studio's local plugin installation flow, then restart Android Studio.")
                appendLine()
                appendLine("## Required Gates")
                appendLine()
                appendLine("```bash")
                appendLine("./gradlew verifyCodeLocator")
                appendLine("CODELOCATOR_DEVICE_SERIAL=<serial> ./gradlew verifyCodeLocatorDevice")
                appendLine("./gradlew verifyComposeLocatorRolloutReadiness -Pcodelocator.rollout.modules=:app,:feature")
                appendLine("./gradlew :app:assembleDebug :app:verifyComposeCompilerSourceAlignment")
                appendLine("./gradlew :app:assembleRelease")
                appendLine("```")
                appendLine()
                appendLine("Verify package transfer integrity with `release-checksums.sha256` before publishing to teams.")
                appendLine()
                appendLine("For Maven Central, Gradle Plugin Portal, and JetBrains Marketplace publishing, see `docs/public-publishing.md`.")
            },
        )
        file("README-CN.md").copyTo(chineseReadmeFile.get().asFile, overwrite = true)

        val stagedDocs = releaseRoot.resolve("docs")
        stagedDocs.deleteRecursively()
        stagedDocs.mkdirs()
        listOf(
            file("docs/team-rollout.md") to stagedDocs.resolve("team-rollout.md"),
            file("docs/release-engineering.md") to stagedDocs.resolve("release-engineering.md"),
            file("docs/public-publishing.md") to stagedDocs.resolve("public-publishing.md"),
            file("docs/compatibility-matrix.json") to stagedDocs.resolve("compatibility-matrix.json"),
        ).forEach { (source, target) ->
            check(source.isFile) {
                "Missing release documentation source: ${source.absolutePath}"
            }
            source.copyTo(target, overwrite = true)
        }

        val checksums = checksumsFile.get().asFile
        val checksumLines = releaseRoot
            .walkTopDown()
            .filter { it.isFile && it != checksums }
            .map { file ->
                val relativePath = file.relativeTo(releaseRoot).invariantSeparatorsPath
                "${file.sha256Hex()}  $relativePath"
            }
            .toList()
            .sortedBy { it.substringAfter("  ") }
        checksums.writeText(checksumLines.joinToString(separator = "\n", postfix = "\n"))

        println("Compose Locator release package staged at ${releaseRoot.absolutePath}")
    }
}

tasks.register("verifyComposeLocatorReleasePackage") {
    group = "verification"
    description = "Verifies local Maven staging artifacts and Android Studio plugin ZIP are ready for team distribution."
    dependsOn("stageComposeLocatorRelease")

    doLast {
        val releaseDir = layout.buildDirectory.dir("composeLocator/release").get().asFile
        val mavenRoot = releaseDir.resolve("maven")
        val version = project.version.toString()
        val required = listOf(
            "io/github/nianzixin/locator-runtime/$version/locator-runtime-$version.pom",
            "io/github/nianzixin/locator-runtime/$version/locator-runtime-$version.jar",
            "io/github/nianzixin/locator-runtime/$version/locator-runtime-$version-sources.jar",
            "io/github/nianzixin/locator-runtime-android/$version/locator-runtime-android-$version.pom",
            "io/github/nianzixin/locator-runtime-android/$version/locator-runtime-android-$version.aar",
            "io/github/nianzixin/locator-runtime-android/$version/locator-runtime-android-$version-sources.jar",
            "io/github/nianzixin/locator-compiler-plugin/$version/locator-compiler-plugin-$version.pom",
            "io/github/nianzixin/locator-compiler-plugin/$version/locator-compiler-plugin-$version.jar",
            "io/github/nianzixin/locator-compiler-plugin/$version/locator-compiler-plugin-$version-sources.jar",
            "io/github/nianzixin/locator-gradle-plugin/$version/locator-gradle-plugin-$version.pom",
            "io/github/nianzixin/locator-gradle-plugin/$version/locator-gradle-plugin-$version.jar",
            "io/github/nianzixin/locator-gradle-plugin/$version/locator-gradle-plugin-$version-sources.jar",
            "io/github/nianzixin/compose-locator/io.github.nianzixin.compose-locator.gradle.plugin/$version/io.github.nianzixin.compose-locator.gradle.plugin-$version.pom",
            "io/github/nianzixin/team-compose-locator/io.github.nianzixin.team-compose-locator.gradle.plugin/$version/io.github.nianzixin.team-compose-locator.gradle.plugin-$version.pom",
        )
        val missing = required.filterNot { mavenRoot.resolve(it).isFile }
        check(missing.isEmpty()) {
            "Compose Locator release package is missing Maven artifacts:\n${missing.joinToString("\n")}"
        }
        fun verifyPluginMarkerPom(markerPath: String, markerGroup: String, markerArtifact: String) {
            val markerPom = mavenRoot.resolve(markerPath)
            val pomText = markerPom.readText()
            check("<groupId>$markerGroup</groupId>" in pomText && "<artifactId>$markerArtifact</artifactId>" in pomText) {
                "Gradle plugin marker POM has unexpected coordinates: ${markerPom.absolutePath}"
            }
            val locatorPluginDependency = Regex(
                """<dependency>\s*<groupId>io\.github\.nianzixin</groupId>\s*<artifactId>locator-gradle-plugin</artifactId>\s*<version>${Regex.escape(version)}</version>\s*</dependency>""",
                RegexOption.DOT_MATCHES_ALL,
            )
            check(locatorPluginDependency.containsMatchIn(pomText)) {
                "Gradle plugin marker POM must depend on io.github.nianzixin:locator-gradle-plugin:$version: ${markerPom.absolutePath}"
            }
        }
        verifyPluginMarkerPom(
            markerPath = "io/github/nianzixin/compose-locator/io.github.nianzixin.compose-locator.gradle.plugin/$version/io.github.nianzixin.compose-locator.gradle.plugin-$version.pom",
            markerGroup = "io.github.nianzixin.compose-locator",
            markerArtifact = "io.github.nianzixin.compose-locator.gradle.plugin",
        )
        verifyPluginMarkerPom(
            markerPath = "io/github/nianzixin/team-compose-locator/io.github.nianzixin.team-compose-locator.gradle.plugin/$version/io.github.nianzixin.team-compose-locator.gradle.plugin-$version.pom",
            markerGroup = "io.github.nianzixin.team-compose-locator",
            markerArtifact = "io.github.nianzixin.team-compose-locator.gradle.plugin",
        )

        val pluginZip = releaseDir.resolve("studio-plugin/compose-code-locator-$version.zip")
        check(pluginZip.isFile) {
            "Missing staged Android Studio plugin ZIP: ${pluginZip.absolutePath}"
        }
        val pluginEntries = ZipFile(pluginZip).use { zip ->
            zip.entries().asSequence().map { it.name }.toSet()
        }
        check("compose-code-locator/lib/studio-plugin-$version.jar" in pluginEntries) {
            "Staged Studio plugin ZIP is missing studio-plugin-$version.jar"
        }
        val manifest = releaseDir.resolve("release-manifest.txt")
        val manifestText = manifest.takeIf(File::isFile)?.readText().orEmpty()
        check(
            "io.github.nianzixin.team-compose-locator" in manifestText &&
                "release-checksums.sha256" in manifestText &&
            "README.md" in manifestText &&
            "README-CN.md" in manifestText &&
            "docs/compatibility-matrix.json" in manifestText &&
            "docs/public-publishing.md" in manifestText &&
                "verifyComposeLocatorCiTemplate" in manifestText &&
                "verifyComposeLocatorWindowRootPolicy" in manifestText,
        ) {
            "Missing or incomplete release manifest: ${manifest.absolutePath}"
        }
        val quickStart = releaseDir.resolve("README.md")
        val quickStartText = quickStart.takeIf(File::isFile)?.readText().orEmpty()
        check(
            "io.github.nianzixin.team-compose-locator" in quickStartText &&
                "studio-plugin/compose-code-locator-$version.zip" in quickStartText &&
                "README-CN.md" in quickStartText &&
                "docs/public-publishing.md" in quickStartText &&
                "verifyCodeLocator" in quickStartText &&
                "release-checksums.sha256" in quickStartText,
        ) {
            "Missing or incomplete release quick start: ${quickStart.absolutePath}"
        }
        val chineseReadme = releaseDir.resolve("README-CN.md")
        val chineseReadmeText = chineseReadme.takeIf(File::isFile)?.readText().orEmpty()
        check(
            "Compose Code Locator 是一个面向 Jetpack Compose 的源码定位工具" in chineseReadmeText &&
                "io.github.nianzixin.team-compose-locator" in chineseReadmeText &&
                "大项目设计" in chineseReadmeText,
        ) {
            "Missing or incomplete release Chinese README: ${chineseReadme.absolutePath}"
        }
        val requiredDocs = listOf(
            "docs/team-rollout.md",
            "docs/release-engineering.md",
            "docs/public-publishing.md",
            "docs/compatibility-matrix.json",
        )
        val missingDocs = requiredDocs.filterNot { releaseDir.resolve(it).isFile }
        check(missingDocs.isEmpty()) {
            "Release package is missing documentation files: $missingDocs"
        }
        val compatibilityMatrixText = releaseDir.resolve("docs/compatibility-matrix.json").readText()
        check(
            """"gradle": "8.7"""" in compatibilityMatrixText &&
                """"androidGradlePlugin": "8.6.1"""" in compatibilityMatrixText &&
                """"requiredGates"""" in compatibilityMatrixText &&
                """"ciMatrix"""" in compatibilityMatrixText,
        ) {
            "Release compatibility matrix is incomplete: ${releaseDir.resolve("docs/compatibility-matrix.json").absolutePath}"
        }
        val checksums = releaseDir.resolve("release-checksums.sha256")
        check(checksums.isFile) {
            "Missing release checksums file: ${checksums.absolutePath}"
        }
        val checksumEntries = checksums.readLines()
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split("  ", limit = 2)
                check(parts.size == 2 && parts[0].matches(Regex("[0-9a-f]{64}"))) {
                    "Invalid checksum line in ${checksums.absolutePath}: $line"
                }
                parts[1] to parts[0]
            }
            .toMap()
        val stagedFiles = releaseDir
            .walkTopDown()
            .filter { it.isFile && it != checksums }
            .map { it.relativeTo(releaseDir).invariantSeparatorsPath }
            .toSet()
        check(checksumEntries.keys == stagedFiles) {
            "Release checksum manifest does not match staged files. Missing=${stagedFiles - checksumEntries.keys}, extra=${checksumEntries.keys - stagedFiles}"
        }
        checksumEntries.forEach { (relativePath, expectedHash) ->
            val actualHash = releaseDir.resolve(relativePath).sha256Hex()
            check(actualHash == expectedHash) {
                "SHA-256 mismatch for $relativePath: expected $expectedHash, got $actualHash"
            }
        }
        println("Compose Locator release package verified at ${releaseDir.absolutePath}")
    }
}

tasks.register<Zip>("packageComposeLocatorRelease") {
    group = "distribution"
    description = "Packages the staged Compose Locator release directory into a single distributable ZIP."
    dependsOn("verifyComposeLocatorReleasePackage")
    archiveFileName.set("compose-code-locator-${project.version}-release.zip")
    destinationDirectory.set(layout.buildDirectory.dir("composeLocator"))
    from(layout.buildDirectory.dir("composeLocator/release")) {
        into("compose-code-locator-${project.version}")
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.register("verifyComposeLocatorReleaseArchive") {
    group = "verification"
    description = "Verifies the distributable Compose Locator release ZIP contains the staged package and integrity files."
    dependsOn("packageComposeLocatorRelease")

    doLast {
        val version = project.version.toString()
        val archive = layout.buildDirectory.file("composeLocator/compose-code-locator-$version-release.zip").get().asFile
        check(archive.isFile) {
            "Missing Compose Locator release archive: ${archive.absolutePath}"
        }
        val prefix = "compose-code-locator-$version/"
        val entries = ZipFile(archive).use { zip ->
            zip.entries().asSequence().map { it.name }.toSet()
        }
        val requiredEntries = listOf(
            "${prefix}README.md",
            "${prefix}README-CN.md",
            "${prefix}release-manifest.txt",
            "${prefix}release-checksums.sha256",
            "${prefix}studio-plugin/compose-code-locator-$version.zip",
            "${prefix}docs/team-rollout.md",
            "${prefix}docs/release-engineering.md",
            "${prefix}docs/public-publishing.md",
            "${prefix}docs/compatibility-matrix.json",
            "${prefix}maven/io/github/nianzixin/locator-gradle-plugin/$version/locator-gradle-plugin-$version.jar",
            "${prefix}maven/io/github/nianzixin/team-compose-locator/io.github.nianzixin.team-compose-locator.gradle.plugin/$version/io.github.nianzixin.team-compose-locator.gradle.plugin-$version.pom",
        )
        val missing = requiredEntries.filterNot(entries::contains)
        check(missing.isEmpty()) {
            "Compose Locator release archive is missing entries: $missing"
        }
        check(entries.none { it.contains("consumer-smoke") || it.contains("intermediates/composeLocator") }) {
            "Compose Locator release archive must not contain local build smoke/intermediate files"
        }
        println("Compose Locator release archive verified at ${archive.absolutePath}")
    }
}

val centralBundleFile = layout.buildDirectory.file("composeLocator/central/compose-code-locator-${project.version}-central-bundle.zip")

tasks.register("packageComposeLocatorCentralBundle") {
    group = "publishing"
    description = "Packages signed Maven artifacts into a Central Portal deployment bundle."
    dependsOn("stageComposeLocatorRelease")

    outputs.file(centralBundleFile)

    doLast {
        val releaseMavenRoot = layout.buildDirectory.dir("composeLocator/release/maven").get().asFile
        check(releaseMavenRoot.isDirectory) {
            "Missing staged Maven repository: ${releaseMavenRoot.absolutePath}"
        }

        val centralRoot = layout.buildDirectory.dir("composeLocator/central/staging").get().asFile
        centralRoot.deleteRecursively()
        centralRoot.mkdirs()
        releaseMavenRoot.copyRecursively(centralRoot, overwrite = true)

        val publishableExtensions = setOf("jar", "aar", "pom", "module")
        val publishableFiles = centralRoot
            .walkTopDown()
            .filter { it.isFile && it.extension in publishableExtensions }
            .toList()
        check(publishableFiles.isNotEmpty()) {
            "No Maven artifacts found for Central Portal bundle in ${centralRoot.absolutePath}"
        }
        val missingSignatures = publishableFiles
            .filterNot { file -> file.resolveSibling("${file.name}.asc").isFile }
            .map { it.relativeTo(centralRoot).invariantSeparatorsPath }
        check(missingSignatures.isEmpty()) {
            "Central Portal release requires GPG signatures. Missing .asc files:\n${missingSignatures.joinToString("\n")}\n" +
                "Provide SIGNING_KEY and SIGNING_PASSWORD, then rerun the release task."
        }

        publishableFiles.forEach { file ->
            file.resolveSibling("${file.name}.md5").writeText("${file.md5Hex()}\n")
            file.resolveSibling("${file.name}.sha1").writeText("${file.sha1Hex()}\n")
        }

        val bundle = centralBundleFile.get().asFile
        bundle.parentFile.mkdirs()
        if (bundle.exists()) bundle.delete()
        ZipOutputStream(bundle.outputStream()).use { zip ->
            centralRoot
                .walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(centralRoot).invariantSeparatorsPath }
                .forEach { file ->
                    val entryName = file.relativeTo(centralRoot).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
        }
        println("Compose Locator Central Portal bundle packaged at ${bundle.absolutePath}")
    }
}

tasks.register("verifyComposeLocatorCentralBundle") {
    group = "verification"
    description = "Verifies the Central Portal bundle contains signed artifacts and legacy checksums."
    dependsOn("packageComposeLocatorCentralBundle")

    doLast {
        val bundle = centralBundleFile.get().asFile
        check(bundle.isFile) {
            "Missing Central Portal bundle: ${bundle.absolutePath}"
        }
        val entries = ZipFile(bundle).use { zip ->
            zip.entries().asSequence().map { it.name }.toSet()
        }
        val version = project.version.toString()
        val requiredEntries = listOf(
            "io/github/nianzixin/locator-runtime/$version/locator-runtime-$version.jar",
            "io/github/nianzixin/locator-runtime/$version/locator-runtime-$version.jar.asc",
            "io/github/nianzixin/locator-runtime/$version/locator-runtime-$version.jar.md5",
            "io/github/nianzixin/locator-runtime/$version/locator-runtime-$version.jar.sha1",
            "io/github/nianzixin/locator-runtime/$version/locator-runtime-$version-javadoc.jar",
            "io/github/nianzixin/locator-runtime-android/$version/locator-runtime-android-$version.aar",
            "io/github/nianzixin/locator-runtime-android/$version/locator-runtime-android-$version.aar.asc",
            "io/github/nianzixin/locator-compiler-plugin/$version/locator-compiler-plugin-$version.jar",
            "io/github/nianzixin/locator-gradle-plugin/$version/locator-gradle-plugin-$version.jar",
            "io/github/nianzixin/team-compose-locator/io.github.nianzixin.team-compose-locator.gradle.plugin/$version/io.github.nianzixin.team-compose-locator.gradle.plugin-$version.pom",
        )
        val missing = requiredEntries.filterNot(entries::contains)
        check(missing.isEmpty()) {
            "Central Portal bundle is missing entries: $missing"
        }
        println("Compose Locator Central Portal bundle verified at ${bundle.absolutePath}")
    }
}

fun Project.centralPortalAuthorizationHeader(): String {
    val explicitToken = providers.environmentVariable("CENTRAL_PORTAL_TOKEN")
        .orElse(providers.gradleProperty("centralPortalToken"))
        .orNull
    if (!explicitToken.isNullOrBlank()) {
        return if (explicitToken.startsWith("Bearer ")) explicitToken else "Bearer $explicitToken"
    }

    val username = providers.environmentVariable("CENTRAL_PORTAL_USERNAME")
        .orElse(providers.gradleProperty("centralPortalUsername"))
        .orNull
    val password = providers.environmentVariable("CENTRAL_PORTAL_PASSWORD")
        .orElse(providers.gradleProperty("centralPortalPassword"))
        .orNull
    check(!username.isNullOrBlank() && !password.isNullOrBlank()) {
        "Missing Central Portal credentials. Set CENTRAL_PORTAL_TOKEN or CENTRAL_PORTAL_USERNAME/CENTRAL_PORTAL_PASSWORD."
    }
    val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
    return "Bearer $token"
}

tasks.register("publishComposeLocatorToMavenCentral") {
    group = "publishing"
    description = "Uploads the signed Central Portal bundle. Set codelocator.central.publishingType=AUTOMATIC to auto-publish."
    dependsOn("verifyComposeLocatorCentralBundle")

    doLast {
        val bundle = centralBundleFile.get().asFile
        val publishingType = providers.gradleProperty("codelocator.central.publishingType")
            .orElse(providers.environmentVariable("CENTRAL_PORTAL_PUBLISHING_TYPE"))
            .orElse("USER_MANAGED")
            .get()
        val deploymentName = providers.gradleProperty("codelocator.central.deploymentName")
            .orElse("compose-code-locator-${project.version}")
            .get()
        val query = "name=${URLEncoder.encode(deploymentName, "UTF-8")}&publishingType=${URLEncoder.encode(publishingType, "UTF-8")}"
        val connection = URL("https://central.sonatype.com/api/v1/publisher/upload?$query").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.doOutput = true
        connection.setRequestProperty("Authorization", project.centralPortalAuthorizationHeader())
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        bundle.inputStream().use { input ->
            connection.outputStream.use { output -> input.copyTo(output) }
        }
        val responseCode = connection.responseCode
        val body = if (responseCode in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }
        check(responseCode in 200..299) {
            "Central Portal upload failed with HTTP $responseCode:\n$body"
        }
        val deploymentId = body.trim()
        val deploymentIdFile = layout.buildDirectory.file("composeLocator/central/deployment-id.txt").get().asFile
        deploymentIdFile.parentFile.mkdirs()
        deploymentIdFile.writeText("$deploymentId\n")
        println("Compose Locator Central Portal deployment uploaded: $deploymentId")
        println("Deployment id written to ${deploymentIdFile.absolutePath}")
    }
}

tasks.register("checkComposeLocatorMavenCentralDeployment") {
    group = "publishing"
    description = "Checks a Central Portal deployment status. Pass -Pcodelocator.central.deploymentId=<id> or run upload first."

    doLast {
        val deploymentId = providers.gradleProperty("codelocator.central.deploymentId").orNull
            ?: layout.buildDirectory.file("composeLocator/central/deployment-id.txt").get().asFile
                .takeIf(File::isFile)
                ?.readText()
                ?.trim()
        check(!deploymentId.isNullOrBlank()) {
            "Missing Central Portal deployment id. Set -Pcodelocator.central.deploymentId=<id> or run publishComposeLocatorToMavenCentral first."
        }
        val query = URLEncoder.encode(deploymentId, "UTF-8")
        val connection = URL("https://central.sonatype.com/api/v1/publisher/status?id=$query").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("Authorization", project.centralPortalAuthorizationHeader())
        val responseCode = connection.responseCode
        val body = if (responseCode in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }
        check(responseCode in 200..299) {
            "Central Portal status request failed with HTTP $responseCode:\n$body"
        }
        println(body)
    }
}

tasks.register("publishComposeLocatorGradlePlugins") {
    group = "publishing"
    description = "Publishes Compose Locator Gradle plugins to the Gradle Plugin Portal from the included build."
    dependsOn(gradle.includedBuild("locator-gradle-plugin").task(":publishPlugins"))
}

tasks.register("verifyComposeLocatorMarketplacePackage") {
    group = "verification"
    description = "Verifies the Android Studio plugin ZIP and Marketplace-facing plugin metadata."
    dependsOn(":studio-plugin:verifyStudioPluginPackaging")

    doLast {
        val version = project.version.toString()
        val pluginXml = project(":studio-plugin").file("src/main/resources/META-INF/plugin.xml")
        val pluginXmlText = pluginXml.readText()
        check("<id>io.github.nianzixin.compose-code-locator</id>" in pluginXmlText) {
            "Marketplace plugin id must use the public namespace in ${pluginXml.absolutePath}"
        }
        check("nianzixin" in pluginXmlText && "https://github.com/nianzixin/compose-code-locator" in pluginXmlText) {
            "Marketplace plugin vendor/description must reference the public project in ${pluginXml.absolutePath}"
        }
        val pluginZip = project(":studio-plugin").layout.buildDirectory
            .file("distributions/compose-code-locator-$version.zip")
            .get()
            .asFile
        check(pluginZip.isFile) {
            "Missing Marketplace plugin ZIP: ${pluginZip.absolutePath}"
        }
        println("Compose Locator Marketplace package verified at ${pluginZip.absolutePath}")
    }
}

tasks.register("verifyComposeLocatorPublicPublishingReadiness") {
    group = "verification"
    description = "Verifies public publishing configuration without requiring external credentials."
    dependsOn(
        "verifyComposeLocatorReleaseArchive",
        "verifyComposeLocatorPublicCoordinates",
        "verifyComposeLocatorMarketplacePackage",
    )

    doLast {
        val signingConfigured = !providers.environmentVariable("SIGNING_KEY").orNull.isNullOrBlank() ||
            !providers.gradleProperty("signingInMemoryKey").orNull.isNullOrBlank()
        val centralConfigured = !providers.environmentVariable("CENTRAL_PORTAL_TOKEN").orNull.isNullOrBlank() ||
            (!providers.environmentVariable("CENTRAL_PORTAL_USERNAME").orNull.isNullOrBlank() &&
                !providers.environmentVariable("CENTRAL_PORTAL_PASSWORD").orNull.isNullOrBlank())
        val gradlePluginPortalConfigured = !providers.environmentVariable("GRADLE_PUBLISH_KEY").orNull.isNullOrBlank() &&
            !providers.environmentVariable("GRADLE_PUBLISH_SECRET").orNull.isNullOrBlank()
        val marketplaceConfigured = !providers.environmentVariable("INTELLIJ_PLATFORM_PUBLISHING_TOKEN").orNull.isNullOrBlank() ||
            !providers.environmentVariable("ORG_GRADLE_PROJECT_intellijPlatformPublishingToken").orNull.isNullOrBlank()

        val report = layout.buildDirectory.file("reports/composeLocator/public-publishing-readiness.md").get().asFile
        report.parentFile.mkdirs()
        report.writeText(
            buildString {
                appendLine("# Compose Locator Public Publishing Readiness")
                appendLine()
                appendLine("- Version: `${project.version}`")
                appendLine("- Release archive: `build/composeLocator/compose-code-locator-${project.version}-release.zip`")
                appendLine("- Maven Central signing configured: `$signingConfigured`")
                appendLine("- Maven Central credentials configured: `$centralConfigured`")
                appendLine("- Gradle Plugin Portal credentials configured: `$gradlePluginPortalConfigured`")
                appendLine("- JetBrains Marketplace token configured: `$marketplaceConfigured`")
                appendLine()
                appendLine("Credential-dependent publish tasks are intentionally not run by this readiness gate.")
            },
        )
        println("Compose Locator public publishing readiness report written to ${report.absolutePath}")
    }
}

tasks.register("verifyComposeLocatorPublicCoordinates") {
    group = "verification"
    description = "Verifies public publishing coordinates use the io.github.nianzixin namespace."
    dependsOn("stageComposeLocatorRelease")

    doLast {
        val releaseDir = layout.buildDirectory.dir("composeLocator/release").get().asFile
        val version = project.version.toString()
        val requiredSnippets = listOf(
            "io.github.nianzixin:locator-runtime:$version",
            "io.github.nianzixin:locator-runtime-android:$version",
            "io.github.nianzixin:locator-compiler-plugin:$version",
            "io.github.nianzixin:locator-gradle-plugin:$version",
            "io.github.nianzixin.compose-locator:io.github.nianzixin.compose-locator.gradle.plugin:$version",
            "io.github.nianzixin.team-compose-locator:io.github.nianzixin.team-compose-locator.gradle.plugin:$version",
            "io.github.nianzixin.team-compose-locator",
        )
        val filesToCheck = listOf(
            releaseDir.resolve("release-manifest.txt"),
            releaseDir.resolve("README.md"),
            releaseDir.resolve("README-CN.md"),
            releaseDir.resolve("docs/compatibility-matrix.json"),
            releaseDir.resolve("docs/release-engineering.md"),
            releaseDir.resolve("docs/public-publishing.md"),
            file("locator-gradle-plugin/build.gradle.kts"),
            file("locator-gradle-plugin/src/main/kotlin/dev/codelocator/gradle/ComposeLocatorExtension.kt"),
            file("locator-gradle-plugin/src/main/kotlin/dev/codelocator/gradle/ComposeLocatorTeamExtension.kt"),
            file("demo-app/build.gradle.kts"),
            file("demo-feature/build.gradle.kts"),
        )
        filesToCheck.forEach { checkedFile ->
            check(checkedFile.isFile) {
                "Missing file for public coordinate verification: ${checkedFile.absolutePath}"
            }
        }
        val combinedText = filesToCheck.joinToString("\n") { it.readText() }
        val missingSnippets = requiredSnippets.filterNot(combinedText::contains)
        check(missingSnippets.isEmpty()) {
            "Public publishing coordinate snippets are missing: $missingSnippets"
        }
        val staleCoordinatePatterns = listOf(
            "dev.codelocator:locator-runtime",
            "dev.codelocator:locator-runtime-android",
            "dev.codelocator:locator-compiler-plugin",
            "dev.codelocator:locator-gradle-plugin",
            "dev.codelocator.compose-locator.gradle.plugin",
            "dev.codelocator.team-compose-locator.gradle.plugin",
            "id(\"dev.codelocator.compose-locator\")",
            "id(\"dev.codelocator.team-compose-locator\")",
        )
        val staleCoordinates = staleCoordinatePatterns.filter(combinedText::contains)
        check(staleCoordinates.isEmpty()) {
            "Found stale public coordinates after io.github.nianzixin migration: $staleCoordinates"
        }
        check(!releaseDir.resolve("maven/dev").exists()) {
            "Staged Maven repository must not contain stale dev.codelocator coordinates: ${releaseDir.resolve("maven/dev").absolutePath}"
        }
        println("Compose Locator public publishing coordinates verified for io.github.nianzixin")
    }
}

tasks.register("verifyComposeLocatorReleaseConsumer") {
    group = "verification"
    description = "Verifies staged Maven artifacts can be consumed by a fresh Gradle build through plugin marker IDs."
    dependsOn("stageComposeLocatorRelease")

    doLast {
        val releaseDir = layout.buildDirectory.dir("composeLocator/release").get().asFile
        val consumerDir = layout.buildDirectory.dir("composeLocator/consumer-smoke").get().asFile
        val javaHome = providers.gradleProperty("org.gradle.java.home").orNull
            ?: System.getenv("JAVA_HOME")
            ?: System.getProperty("java.home")
        check(file(javaHome).isDirectory) {
            "Unable to locate Java home for consumer smoke test: $javaHome"
        }
        consumerDir.deleteRecursively()
        consumerDir.mkdirs()
        val sdkDir = file("local.properties")
            .takeIf(File::isFile)
            ?.readLines()
            ?.firstOrNull { it.startsWith("sdk.dir=") }
            ?.substringAfter("sdk.dir=")
        if (sdkDir != null) {
            consumerDir.resolve("local.properties").writeText("sdk.dir=$sdkDir\n")
        }
        consumerDir.resolve("gradle.properties").writeText(
            """
            org.gradle.java.home=${file(javaHome).invariantSeparatorsPath}
            org.gradle.user.home=${project.rootDir.resolve(".gradle-user-home").invariantSeparatorsPath}
            org.gradle.jvmargs=-Xmx1g -Dfile.encoding=UTF-8
            android.useAndroidX=true
            android.suppressUnsupportedCompileSdk=35
            """.trimIndent(),
        )
        consumerDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    maven("${releaseDir.resolve("maven").invariantSeparatorsPath}") {
                        content {
                            includeGroup("io.github.nianzixin")
                            includeGroup("io.github.nianzixin.compose-locator")
                            includeGroup("io.github.nianzixin.team-compose-locator")
                        }
                    }
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }

            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    maven("${releaseDir.resolve("maven").invariantSeparatorsPath}") {
                        content {
                            includeGroup("io.github.nianzixin")
                        }
                    }
                    google()
                    mavenCentral()
                }
            }

            rootProject.name = "compose-locator-consumer-smoke"
            """.trimIndent(),
        )
        consumerDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application") version "8.6.1"
                id("io.github.nianzixin.team-compose-locator") version "${project.version}"
                id("io.github.nianzixin.compose-locator") version "${project.version}" apply false
            }

            android {
                namespace = "dev.codelocator.consumer.smoke"
                compileSdk = 35

                defaultConfig {
                    applicationId = "dev.codelocator.consumer.smoke"
                    minSdk = 26
                    targetSdk = 35
                    versionCode = 1
                    versionName = "0.1.0"
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }

            teamComposeLocator {
                autoAddDebugRuntime = false
            }

            tasks.register("verifyConsumerPlugins") {
                doLast {
                    check(pluginManager.hasPlugin("io.github.nianzixin.team-compose-locator")) {
                        "team compose locator plugin was not applied"
                    }
                    check(pluginManager.hasPlugin("io.github.nianzixin.compose-locator")) {
                        "core compose locator plugin was not applied by the team convention plugin"
                    }
                    check(project.extensions.findByName("composeLocator") != null) {
                        "composeLocator extension was not created"
                    }
                    check(project.extensions.findByName("teamComposeLocator") != null) {
                        "teamComposeLocator extension was not created"
                    }
                    check(tasks.names.contains("generateComposeLocatorSourceMap")) {
                        "core compose locator tasks were not registered"
                    }
                }
            }
            """.trimIndent(),
        )

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val result = exec {
            workingDir = consumerDir
            commandLine = listOf(
                project.rootDir.resolve("gradlew").absolutePath,
                "--no-daemon",
                "verifyConsumerPlugins",
            )
            environment("JAVA_HOME", javaHome)
            standardOutput = stdout
            errorOutput = stderr
            isIgnoreExitValue = true
        }
        check(result.exitValue == 0) {
            "Staged Compose Locator plugin consumer smoke test failed.\nSTDOUT:\n$stdout\nSTDERR:\n$stderr"
        }
        println("Compose Locator staged plugin consumer smoke test verified")
    }
}

tasks.register("verifyStudioDeviceFlow") {
    group = "verification"
    description = "Verifies Studio-side screenshot click-to-source logic against the adb demo app without launching Android Studio UI."
    dependsOn("verifyDemoDevice", ":studio-plugin:verifyStudioDeviceFlow")
    project(":studio-plugin").tasks.named("verifyStudioDeviceFlow").configure {
        mustRunAfter(tasks.named("verifyDemoDevice"))
    }
}

tasks.register("verifyCodeLocator") {
    group = "verification"
    description = "Runs the non-device Compose Code Locator verification suite."
    dependsOn(
        ":locator-runtime:verifyRuntimeProtocol",
        ":studio-plugin:verifyStudioProtocol",
        "verifyComposeCallSiteMetadata",
        "verifyComposeCompilerSourceAlignment",
        "verifyComposeLocatorBuildEfficiency",
        "verifyComposeLocatorCiTemplate",
        "verifyComposeLocatorCompatibilityMatrix",
        "verifyComposeLocatorWindowRootPolicy",
        "verifyComposeLocatorPerformance",
        "verifyComposeLocatorReleaseArchive",
        "verifyComposeLocatorReleaseConsumer",
        "verifyComposeLocatorReleasePackage",
        "verifyComposeLocatorPublicCoordinates",
        "verifyComposeLocatorPublicPublishingReadiness",
        "verifyComposeLocatorPublishWorkflow",
        "verifyComposeLocatorRolloutReadiness",
        "verifyComposeLocatorReleaseBoundary",
        "verifyComposeLocatorTeamConvention",
        ":studio-plugin:verifyStudioPluginPackaging",
    )
}

tasks.register("verifyCodeLocatorDevice") {
    group = "verification"
    description = "Runs adb-backed end-to-end Compose Code Locator verification."
    dependsOn(
        "verifyDemoDevice",
        "verifyStableDeviceNodeIds",
        "verifyStudioDeviceFlow",
    )
}
