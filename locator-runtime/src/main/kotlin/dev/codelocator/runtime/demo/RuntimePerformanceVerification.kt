package dev.codelocator.runtime.demo

import dev.codelocator.runtime.LocatorRegistry
import dev.codelocator.runtime.model.LocatorNode
import dev.codelocator.runtime.model.Rect
import dev.codelocator.runtime.server.LocatorHttpProtocol
import java.util.Locale
import kotlin.math.max
import kotlin.system.measureNanoTime

private const val NODE_COUNT = 20_000
private const val QUERY_COUNT = 2_000

fun main() {
    val registry = LocatorRegistry()
    repeat(NODE_COUNT) { index ->
        val left = (index * 17) % 1080
        val top = (index * 31) % 2200
        val width = 24 + (index % 160)
        val height = 18 + (index % 120)
        registry.upsert(
            LocatorNode(
                id = index.toLong() + 1L,
                screenBounds = Rect(
                    left = left,
                    top = top,
                    right = left + width,
                    bottom = top + height,
                ),
                zIndex = (index % 19).toFloat(),
                sourceId = if (index % 3 == 0) index.toLong() + 10_000L else null,
                text = if (index % 5 == 0) "Text $index" else null,
                role = if (index % 7 == 0) "button" else null,
                composableName = "Composable${index % 64}",
                flags = index % 8,
            ),
        )
    }

    val warmupSnapshot = registry.snapshot()
    check(warmupSnapshot.size == NODE_COUNT) {
        "Expected $NODE_COUNT nodes, got ${warmupSnapshot.size}"
    }
    check(warmupSnapshot === registry.snapshot()) {
        "Expected registry snapshot cache to reuse the sorted list between mutations"
    }

    val snapshotBestMs = bestMillis(iterations = 8) {
        registry.snapshot()
    }
    var totalCandidates = 0
    val hitTestBestMs = bestMillis(iterations = 8) {
        repeat(QUERY_COUNT) { query ->
            val x = (query * 37) % 1200
            val y = (query * 53) % 2400
            totalCandidates += registry.hitTest(x, y).candidates.size
        }
    }
    val snapshotJsonBestMs = bestMillis(iterations = 5) {
        LocatorHttpProtocol.encodeSnapshot(registry.snapshot())
    }
    val denseHitTest = registry.hitTest(512, 960)
    val hitTestJsonBestMs = bestMillis(iterations = 8) {
        LocatorHttpProtocol.encodeHitTest(denseHitTest)
    }

    registry.upsert(
        LocatorNode(
            id = NODE_COUNT + 1L,
            screenBounds = Rect(0, 0, 1, 1),
        ),
    )
    check(registry.snapshot().size == NODE_COUNT + 1) {
        "Snapshot cache was not invalidated after upsert"
    }

    val snapshotThresholdMs = longProperty("codelocator.runtime.maxSnapshotMs", 10L)
    val hitTestThresholdMs = longProperty("codelocator.runtime.maxHitTestBatchMs", 1_000L)
    val snapshotJsonThresholdMs = longProperty("codelocator.runtime.maxSnapshotJsonMs", 2_000L)
    val hitTestJsonThresholdMs = longProperty("codelocator.runtime.maxHitTestJsonMs", 50L)

    println(
        buildString {
            appendLine("Compose Locator runtime performance benchmark")
            appendLine("nodes=$NODE_COUNT")
            appendLine("queries=$QUERY_COUNT")
            appendLine("totalCandidates=$totalCandidates")
            appendLine("snapshotCachedBestMs=${snapshotBestMs.formatMs()} thresholdMs=$snapshotThresholdMs")
            appendLine("hitTestBatchBestMs=${hitTestBestMs.formatMs()} thresholdMs=$hitTestThresholdMs")
            appendLine("snapshotJsonBestMs=${snapshotJsonBestMs.formatMs()} thresholdMs=$snapshotJsonThresholdMs")
            appendLine("hitTestJsonBestMs=${hitTestJsonBestMs.formatMs()} thresholdMs=$hitTestJsonThresholdMs")
        }.trimEnd(),
    )

    check(snapshotBestMs <= snapshotThresholdMs) {
        "Cached snapshot benchmark exceeded threshold: ${snapshotBestMs.formatMs()}ms > ${snapshotThresholdMs}ms"
    }
    check(hitTestBestMs <= hitTestThresholdMs) {
        "Hit-test batch benchmark exceeded threshold: ${hitTestBestMs.formatMs()}ms > ${hitTestThresholdMs}ms"
    }
    check(snapshotJsonBestMs <= snapshotJsonThresholdMs) {
        "Snapshot JSON benchmark exceeded threshold: ${snapshotJsonBestMs.formatMs()}ms > ${snapshotJsonThresholdMs}ms"
    }
    check(hitTestJsonBestMs <= hitTestJsonThresholdMs) {
        "Hit-test JSON benchmark exceeded threshold: ${hitTestJsonBestMs.formatMs()}ms > ${hitTestJsonThresholdMs}ms"
    }
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
