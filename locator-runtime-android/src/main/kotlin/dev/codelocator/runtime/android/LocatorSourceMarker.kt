package dev.codelocator.runtime.android

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalView
import dev.codelocator.runtime.LocatorRuntime
import dev.codelocator.runtime.model.Rect
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Private debug marker injected by the Compose Code Locator compiler plugin.
 *
 * Business code should not call this directly. It does not add Semantics and is only read by the
 * debug runtime through Compose LayoutNode modifier metadata.
 */
fun Modifier.locatorSource(sourceId: Long): Modifier {
    return this.then(
        LocatorSourceElement(
            sourceId = sourceId,
            boundarySourceId = LocatorRuntime.currentSourceId(),
        ),
    )
}

fun locatorSource(sourceId: Long): Modifier {
    return Modifier.locatorSource(sourceId)
}

class LocatorSourceNode : Modifier.Node(), GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {
    private val registryKey = LocatorSourceBoundsRegistry.nextKey()
    private var lastBounds: LocatorSourceBoundsUpdate? = null
    var sourceId: Long = 0L
        set(value) {
            field = value
            lastBounds?.let { update ->
                LocatorSourceBoundsRegistry.upsert(registryKey, value, boundarySourceId, update)
            }
        }
    var boundarySourceId: Long? = null
        set(value) {
            field = value
            lastBounds?.let { update ->
                LocatorSourceBoundsRegistry.upsert(registryKey, sourceId, value, update)
            }
        }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val bounds = coordinates.boundsInWindow()
        val boundsInWindow = Rect(
            left = bounds.left.roundToInt(),
            top = bounds.top.roundToInt(),
            right = bounds.right.roundToInt(),
            bottom = bounds.bottom.roundToInt(),
        )
        if (boundsInWindow.area() <= 0) {
            lastBounds = null
            LocatorSourceBoundsRegistry.remove(registryKey)
        } else {
            val windowInfo = currentWindowInfo()
            val update = LocatorSourceBoundsUpdate(
                boundsInWindow = boundsInWindow,
                screenBounds = windowInfo.toScreenRect(boundsInWindow),
                windowId = windowInfo.id,
            )
            lastBounds = update
            LocatorSourceBoundsRegistry.upsert(registryKey, sourceId, boundarySourceId, update)
        }
    }

    override fun onDetach() {
        LocatorSourceBoundsRegistry.remove(registryKey)
        lastBounds = null
    }

    private fun currentWindowInfo(): WindowInfo {
        val view = runCatching { currentValueOf(LocalView) }.getOrNull()
        return if (view == null) WindowInfo.UNKNOWN else WindowInfo.forView(view)
    }
}

class LocatorSourceElement(
    val sourceId: Long,
    val boundarySourceId: Long?,
) : ModifierNodeElement<LocatorSourceNode>() {
    override fun create(): LocatorSourceNode {
        return LocatorSourceNode().also {
            it.sourceId = sourceId
            it.boundarySourceId = boundarySourceId
        }
    }

    override fun update(node: LocatorSourceNode) {
        node.sourceId = sourceId
        node.boundarySourceId = boundarySourceId
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "locatorSource"
        properties["sourceId"] = sourceId
        properties["boundarySourceId"] = boundarySourceId
    }

    override fun equals(other: Any?): Boolean {
        return other is LocatorSourceElement &&
            other.sourceId == sourceId &&
            other.boundarySourceId == boundarySourceId
    }

    override fun hashCode(): Int {
        var result = sourceId.hashCode()
        result = 31 * result + boundarySourceId.hashCode()
        return result
    }
}

internal object LocatorSourceBoundsRegistry {
    private val nextKey = AtomicLong(1L)
    private val entries = ConcurrentHashMap<Long, LocatorSourceBounds>()

    fun nextKey(): Long = nextKey.getAndIncrement()

    fun upsert(key: Long, sourceId: Long, boundarySourceId: Long?, update: LocatorSourceBoundsUpdate) {
        if (sourceId == 0L || update.screenBounds.area() <= 0 || update.boundsInWindow.area() <= 0) return
        entries[key] = LocatorSourceBounds(
            key = key,
            sourceId = sourceId,
            boundarySourceId = boundarySourceId?.takeIf { it != sourceId },
            boundsInWindow = update.boundsInWindow,
            screenBounds = update.screenBounds,
            windowId = update.windowId,
        )
    }

    fun remove(key: Long) {
        entries.remove(key)
    }

    fun resolve(bounds: Rect): Long? {
        val entry = resolveEntry(bounds) ?: return null
        return entry.effectiveSourceId()
    }

    fun resolve(bounds: Rect, windowId: Int): Long? {
        val entry = resolveEntry(bounds, windowId) ?: return null
        return entry.effectiveSourceId()
    }

    fun resolveEntry(bounds: Rect): LocatorSourceBounds? {
        return resolveEntry(bounds = bounds, windowId = null)
    }

    fun resolveEntry(bounds: Rect, windowId: Int): LocatorSourceBounds? {
        return resolveEntry(bounds = bounds, windowId = windowId)
    }

    private fun resolveEntry(bounds: Rect, windowId: Int?): LocatorSourceBounds? {
        if (bounds.area() <= 0) return null
        return entries.values
            .asSequence()
            .mapNotNull { entry ->
                val rank = entry.matchRank(bounds, windowId) ?: return@mapNotNull null
                SourceBoundsMatch(entry = entry, rank = rank)
            }
            .sortedWith(
                compareBy<SourceBoundsMatch> { it.rank }
                    .thenBy { it.entry.screenBounds.area() }
                    .thenBy { it.entry.key }
                    .thenBy { it.entry.sourceId },
            )
            .firstOrNull()
            ?.entry
    }

    fun snapshot(): List<LocatorSourceBounds> = entries.values.toList()

    private data class SourceBoundsMatch(
        val entry: LocatorSourceBounds,
        val rank: Int,
    )
}

internal data class LocatorSourceBoundsUpdate(
    val boundsInWindow: Rect,
    val screenBounds: Rect,
    val windowId: Int,
)

internal data class LocatorSourceBounds(
    val key: Long,
    val sourceId: Long,
    val boundarySourceId: Long?,
    val boundsInWindow: Rect,
    val screenBounds: Rect,
    val windowId: Int,
) {
    fun effectiveSourceId(): Long {
        val duplicateMarkerCount = LocatorSourceBoundsRegistry.snapshot().count { it.sourceId == sourceId }
        return if (duplicateMarkerCount > 1) {
            boundarySourceId ?: sourceId
        } else {
            sourceId
        }
    }

    fun matchRank(target: Rect, targetWindowId: Int?): Int? {
        val sameWindow = targetWindowId != null && windowId == targetWindowId
        if (targetWindowId != null && !sameWindow && windowId != WindowInfo.UNKNOWN_ID) return null
        screenBounds.matchRank(target)?.let { rank ->
            return if (sameWindow) rank else rank + 20
        }
        if (sameWindow) {
            boundsInWindow.matchRank(target)?.let { return it + 10 }
        }
        return null
    }
}

private fun Rect.matchRank(target: Rect): Int? {
    return when {
        containsWithTolerance(target, tolerance = 2) -> 0
        contains(target.centerX(), target.centerY()) -> 1
        intersects(target) -> 2
        else -> null
    }
}

private fun Rect.containsWithTolerance(target: Rect, tolerance: Int): Boolean {
    return left - tolerance <= target.left &&
        top - tolerance <= target.top &&
        right + tolerance >= target.right &&
        bottom + tolerance >= target.bottom
}

private fun Rect.intersects(other: Rect): Boolean {
    return left < other.right &&
        right > other.left &&
        top < other.bottom &&
        bottom > other.top
}

private fun Rect.centerX(): Int = (left + right) / 2

private fun Rect.centerY(): Int = (top + bottom) / 2
