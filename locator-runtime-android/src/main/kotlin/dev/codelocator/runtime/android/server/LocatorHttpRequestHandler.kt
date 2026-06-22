package dev.codelocator.runtime.android.server

import dev.codelocator.runtime.LocatorRuntime
import dev.codelocator.runtime.android.LocatorAutoInstaller
import dev.codelocator.runtime.android.LocatorDebugFacade
import dev.codelocator.runtime.server.LocatorHttpProtocol
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.URLDecoder

internal object LocatorHttpRequestHandler {
    fun handle(reader: BufferedReader, writer: OutputStreamWriter) {
        val requestLine = reader.readLine().orEmpty()
        while (reader.readLine()?.isNotEmpty() == true) {
            // Skip headers.
        }

        val (statusCode, statusText, body) = when {
            requestLine.startsWith("GET /hitTest") -> {
                LocatorAutoInstaller.collectNow()
                val query = requestLine.queryParameters()
                val x = query["x"]?.toIntOrNull() ?: 0
                val y = query["y"]?.toIntOrNull() ?: 0
                Response.ok(LocatorHttpProtocol.encodeHitTest(LocatorRuntime.registry.hitTest(x, y)))
            }
            requestLine.startsWith("GET /snapshot") -> {
                LocatorAutoInstaller.collectNow()
                Response.ok(LocatorHttpProtocol.encodeSnapshot(LocatorRuntime.registry.snapshot()))
            }
            requestLine.startsWith("GET /windows") -> {
                LocatorAutoInstaller.collectNow()
                Response.ok(
                    LocatorHttpProtocol.encodeWindows(
                        nodes = LocatorRuntime.registry.snapshot(),
                        diagnostics = LocatorDebugFacade.autoCollectStats().windowRootDetails,
                    ),
                )
            }
            requestLine.startsWith("GET /health") -> {
                LocatorAutoInstaller.collectNow()
                Response.ok(
                    LocatorHttpProtocol.encodeHealth(
                        nodeCount = LocatorRuntime.registry.snapshot().size,
                        sourceCount = 0,
                        extraFields = LocatorDebugFacade.autoCollectStats().toHealthFields(),
                    ),
                )
            }
            else -> Response.notFound()
        }

        writer.write(
            "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n" +
                "Connection: close\r\n\r\n" +
                body,
        )
        writer.flush()
    }

    private fun String.queryParameters(): Map<String, String> {
        val path = substringAfter(' ', missingDelimiterValue = "")
            .substringBefore(' ', missingDelimiterValue = "")
        val query = path.substringAfter('?', missingDelimiterValue = "")
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=')
            if (key.isBlank()) return@mapNotNull null
            val value = pair.substringAfter('=', missingDelimiterValue = "")
            decode(key) to decode(value)
        }.toMap()
    }

    private fun decode(value: String): String {
        return URLDecoder.decode(value, Charsets.UTF_8.name())
    }

    private fun dev.codelocator.runtime.android.LocatorAutoCollectStats.Snapshot.toHealthFields(): Map<String, Any?> {
        return mapOf(
            "activeActivities" to activeActivities,
            "windowRoots" to windowRoots,
            "windowRootDetails" to windowRootDetails.take(8),
            "layoutHosts" to layoutHosts,
            "layoutNodes" to layoutNodes,
            "semanticsHosts" to semanticsHosts,
            "semanticsRawNodes" to semanticsRawNodes,
            "semanticsNodes" to semanticsNodes,
            "collectionCount" to collectionCount,
            "collectorFailures" to failures.take(5),
        )
    }

    private data class Response(
        val statusCode: Int,
        val statusText: String,
        val body: String,
    ) {
        companion object {
            fun ok(body: String) = Response(200, "OK", body)

            fun notFound() = Response(404, "Not Found", "{\"error\":\"not_found\"}")
        }
    }
}
