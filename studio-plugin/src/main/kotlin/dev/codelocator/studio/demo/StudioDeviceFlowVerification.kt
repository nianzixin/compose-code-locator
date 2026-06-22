package dev.codelocator.studio.demo

import dev.codelocator.studio.device.DeviceDescriptor
import dev.codelocator.studio.navigation.NavigationPlanner
import dev.codelocator.studio.navigation.NavigationRequest
import dev.codelocator.studio.navigation.Navigator
import dev.codelocator.studio.ui.LocatorToolWindow
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

fun main(args: Array<String>) {
    val projectRoot = args.firstOrNull()?.let(::File)?.absoluteFile ?: File(".").absoluteFile
    val openedRequests = mutableListOf<NavigationRequest>()
    val toolWindow = LocatorToolWindow(
        projectRoot = projectRoot,
        navigator = object : Navigator {
            override fun open(request: NavigationRequest) {
                openedRequests += request
            }
        },
    )

    val adb = locateAdb()
    val serial = selectDeviceSerial(adb)
    toolWindow.selectDevice(DeviceDescriptor(serial = serial))

    toolWindow.connect()
    try {
        toolWindow.captureScreenshot()
        val sourceFile = projectRoot.resolve("demo-app/src/main/kotlin/dev/codelocator/demo/DemoApp.kt")
        val expectedOpenPopupLine = sourceLineBefore(
            file = sourceFile,
            anchor = "onClick = onShowPopup",
            snippet = "Button(",
        )
        val expectedPopupLine = sourceLineAfter(
            file = sourceFile,
            anchor = "private fun FloatingProbePopup",
            snippet = "Button(",
        )
        val index = loadStudioIndex(projectRoot)
        val (openPopupX, openPopupY) = waitForSourceLineCenter(
            index = index,
            relativePath = "demo-app/src/main/kotlin/dev/codelocator/demo/DemoApp.kt",
            line = expectedOpenPopupLine,
            role = "button",
        )
        runAdb(adb, serial, listOf("shell", "input", "tap", openPopupX.toString(), openPopupY.toString()))
        val (clickX, clickY) = waitForSourceLineCenter(
            index = index,
            relativePath = "demo-app/src/main/kotlin/dev/codelocator/demo/DemoApp.kt",
            line = expectedPopupLine,
            role = "button",
            topWindowOnly = true,
        )
        toolWindow.captureScreenshot()
        val result = toolWindow.locateCandidates(clickX, clickY)
        check(result.candidates.isNotEmpty()) { "Expected hit-test candidates at image coordinate ($clickX, $clickY)" }
        val candidate = result.candidates.firstOrNull { it.location != null }
            ?: error("Expected at least one source-backed hit-test candidate: ${result.candidates}")
        toolWindow.openCandidate(candidate)

        val request = openedRequests.singleOrNull()
            ?: error("Expected exactly one navigation request, got ${openedRequests.size}")
        val resolved = NavigationPlanner(projectRoot).plan(request.location)
        check(resolved.file.isFile) { "Resolved source file does not exist: ${resolved.file.absolutePath}" }
        check(resolved.line > 0) { "Resolved line must be positive: ${resolved.line}" }
        check(resolved.column > 0) { "Resolved column must be positive: ${resolved.column}" }
        check(resolved.file.name == "DemoApp.kt" && resolved.line == expectedPopupLine) {
            "Expected Popup CTA to resolve to DemoApp.kt:$expectedPopupLine Button, got ${resolved.file.name}:${resolved.line} ${resolved.symbol}"
        }

        println(
            "Studio device flow verification passed: " +
                "${resolved.file.relativeTo(projectRoot).path}:${resolved.line}:${resolved.column} ${resolved.symbol ?: ""}".trim(),
        )
    } finally {
        toolWindow.disconnect()
    }
}

private data class IndexedSource(
    val relativePath: String,
    val line: Int,
)

private data class SnapshotNode(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val sourceId: Long?,
    val role: String?,
    val windowLayer: Int,
)

private fun SnapshotNode.area(): Int {
    return (right - left).coerceAtLeast(0) * (bottom - top).coerceAtLeast(0)
}

private fun sourceLineAfter(file: File, anchor: String, snippet: String): Int {
    val lines = file.readLines()
    val anchorIndex = lines.indexOfFirst { anchor in it }
    check(anchorIndex >= 0) { "Unable to find source anchor '$anchor' in ${file.absolutePath}" }
    return lines
        .drop(anchorIndex + 1)
        .mapIndexedNotNull { offset, line ->
            if (snippet in line) anchorIndex + offset + 2 else null
        }
        .firstOrNull()
        ?: error("Unable to find source line containing '$snippet' after '$anchor' in ${file.absolutePath}")
}

private fun sourceLineBefore(file: File, anchor: String, snippet: String): Int {
    val lines = file.readLines()
    val anchorIndex = lines.indexOfFirst { anchor in it }
    check(anchorIndex >= 0) { "Unable to find source anchor '$anchor' in ${file.absolutePath}" }
    for (index in anchorIndex downTo 0) {
        if (snippet in lines[index]) {
            return index + 1
        }
    }
    error("Unable to find source line containing '$snippet' before '$anchor' in ${file.absolutePath}")
}

private fun waitForSourceLineCenter(
    index: Map<Long, IndexedSource>,
    relativePath: String,
    line: Int,
    role: String?,
    topWindowOnly: Boolean = false,
): Pair<Int, Int> {
    var lastSnapshot = ""
    repeat(30) {
        lastSnapshot = httpGet("http://127.0.0.1:49391/snapshot")
        val nodes = parseSnapshotNodes(lastSnapshot)
        val topWindowLayer = nodes.maxOfOrNull { it.windowLayer }
        val node = nodes
            .filter { node ->
                val source = node.sourceId?.let(index::get)
                source?.relativePath == relativePath &&
                    source.line == line &&
                    (role == null || node.role == role) &&
                    (!topWindowOnly || topWindowLayer == null || node.windowLayer == topWindowLayer)
            }
            .maxWithOrNull(compareBy<SnapshotNode> { it.windowLayer }.thenBy { it.area() })
        if (node != null) {
            return ((node.left + node.right) / 2) to ((node.top + node.bottom) / 2)
        }
        Thread.sleep(250)
    }
    error("Unable to find source-backed node $relativePath:$line role=$role topWindowOnly=$topWindowOnly in locator snapshot: $lastSnapshot")
}

private fun parseSnapshotNodes(snapshot: String): List<SnapshotNode> {
    val regex = Regex(
        """\{"id":\d+,"bounds":\[(-?\d+),(-?\d+),(-?\d+),(-?\d+)\],"zIndex":[^,]+,"sourceId":(\d+|null),"semanticsTag":(?:"(?:\\.|[^"\\])*"|null),"text":(?:"(?:\\.|[^"\\])*"|null),"role":(?:"((?:\\.|[^"\\])*)"|null),"composableName":(?:"(?:\\.|[^"\\])*"|null),"flags":\d+,"windowId":-?\d+,"windowTitle":(?:"(?:\\.|[^"\\])*"|null),"windowLayer":(-?\d+)\}""",
    )
    return regex.findAll(snapshot).map { match ->
        SnapshotNode(
            left = match.groupValues[1].toInt(),
            top = match.groupValues[2].toInt(),
            right = match.groupValues[3].toInt(),
            bottom = match.groupValues[4].toInt(),
            sourceId = match.groupValues[5].toLongOrNull(),
            role = match.groupValues[6].ifBlank { null }?.let(::unescapeJson),
            windowLayer = match.groupValues[7].toInt(),
        )
    }.toList()
}

private fun loadStudioIndex(projectRoot: File): Map<Long, IndexedSource> {
    val shards = projectRoot.resolve("demo-app/build/intermediates/composeLocator/studio-index/v1/shards")
    check(shards.isDirectory) { "Missing Studio source index shards at ${shards.absolutePath}" }
    val regex = Regex(
        """\{"sourceId":(\d+),"relativePath":"((?:\\.|[^"\\])*)","line":(\d+),"column":\d+,"symbol":"(?:\\.|[^"\\])*","kind":"[^"]+"\}""",
    )
    return shards.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension == "jsonl" }
        .flatMap { shard ->
            shard.readLines().mapNotNull { line ->
                val match = regex.matchEntire(line) ?: return@mapNotNull null
                match.groupValues[1].toLong() to IndexedSource(
                    relativePath = unescapeJson(match.groupValues[2]),
                    line = match.groupValues[3].toInt(),
                )
            }
        }
        .toMap()
}

private fun unescapeJson(value: String): String {
    return value
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
}

private fun locateAdb(): String {
    val home = System.getProperty("user.home")
    return listOfNotNull(
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
        home?.let { "$it/Library/Android/sdk" },
        home?.let { "$it/Android/Sdk" },
    ).asSequence()
        .map { File(it, "platform-tools/adb") }
        .firstOrNull { it.canExecute() }
        ?.absolutePath
        ?: "adb"
}

private fun selectDeviceSerial(adb: String): String {
    val devicesOutput = runAdb(adb, serial = null, args = listOf("devices", "-l"))
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
    val requestedSerial = System.getenv("CODELOCATOR_DEVICE_SERIAL")?.takeIf { it.isNotBlank() }
    return when {
        requestedSerial != null && requestedSerial in devices -> requestedSerial
        requestedSerial != null -> error(
            "CODELOCATOR_DEVICE_SERIAL=$requestedSerial is not connected. Connected devices: ${devices.ifEmpty { listOf("<none>") }.joinToString()}",
        )
        devices.size == 1 -> devices.single()
        devices.isEmpty() -> error("No connected adb device/emulator. Connect a device or start an emulator.")
        else -> error("Multiple adb devices connected: ${devices.joinToString()}. Set CODELOCATOR_DEVICE_SERIAL to the target serial.")
    }
}

private fun runAdb(adb: String, serial: String?, args: List<String>): String {
    val command = buildList {
        add(adb)
        if (serial != null) {
            add("-s")
            add(serial)
        }
        addAll(args)
    }
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val process = ProcessBuilder(command)
        .redirectErrorStream(false)
        .start()
    process.inputStream.use { it.copyTo(stdout) }
    process.errorStream.use { it.copyTo(stderr) }
    val exitCode = process.waitFor()
    val out = stdout.toString()
    val err = stderr.toString()
    check(exitCode == 0) {
        "Command failed ($exitCode): ${command.joinToString(" ")}\n${err.ifBlank { out }.trim()}"
    }
    return out
}

private fun httpGet(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 500
    connection.readTimeout = 1_000
    val status = connection.responseCode
    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
    val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    if (status !in 200..299) {
        error("HTTP $status from $url: $body")
    }
    return body
}
