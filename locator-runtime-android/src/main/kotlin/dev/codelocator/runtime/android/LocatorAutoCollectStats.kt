package dev.codelocator.runtime.android

import java.util.concurrent.atomic.AtomicReference

internal object LocatorAutoCollectStats {
    private val latest = AtomicReference(Snapshot())

    fun record(
        activeActivities: Int? = null,
        windowRoots: Int? = null,
        windowRootDetails: List<String>? = null,
        layoutHosts: Int? = null,
        layoutNodes: Int? = null,
        semanticsHosts: Int? = null,
        semanticsRawNodes: Int? = null,
        semanticsNodes: Int? = null,
        failures: List<String> = emptyList(),
    ) {
        latest.updateAndGet { current ->
            current.copy(
                activeActivities = activeActivities ?: current.activeActivities,
                windowRoots = windowRoots ?: current.windowRoots,
                windowRootDetails = windowRootDetails ?: current.windowRootDetails,
                layoutHosts = layoutHosts ?: current.layoutHosts,
                layoutNodes = layoutNodes ?: current.layoutNodes,
                semanticsHosts = semanticsHosts ?: current.semanticsHosts,
                semanticsRawNodes = semanticsRawNodes ?: current.semanticsRawNodes,
                semanticsNodes = semanticsNodes ?: current.semanticsNodes,
                failures = failures.takeIf { it.isNotEmpty() } ?: current.failures,
                collectionCount = current.collectionCount + 1,
            )
        }
    }

    fun snapshot(): Snapshot = latest.get()

    data class Snapshot(
        val activeActivities: Int = 0,
        val windowRoots: Int = 0,
        val windowRootDetails: List<String> = emptyList(),
        val layoutHosts: Int = 0,
        val layoutNodes: Int = 0,
        val semanticsHosts: Int = 0,
        val semanticsRawNodes: Int = 0,
        val semanticsNodes: Int = 0,
        val failures: List<String> = emptyList(),
        val collectionCount: Long = 0L,
    )
}
