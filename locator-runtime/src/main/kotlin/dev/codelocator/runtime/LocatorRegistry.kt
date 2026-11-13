package dev.codelocator.runtime

import dev.codelocator.runtime.model.HitTestResult
import dev.codelocator.runtime.model.LocatorNode
import java.util.concurrent.ConcurrentHashMap

class LocatorRegistry {
    private val nodes = ConcurrentHashMap<Long, LocatorNode>()
    @Volatile
    private var cachedSnapshot: List<LocatorNode>? = null

    fun upsert(node: LocatorNode) {
        nodes[node.id] = node
        cachedSnapshot = null
    }

    fun remove(nodeId: Long) {
        if (nodes.remove(nodeId) != null) {
            cachedSnapshot = null
        }
    }

    fun clear() {
        if (nodes.isNotEmpty()) {
            nodes.clear()
            cachedSnapshot = null
        }
    }

    fun snapshot(): List<LocatorNode> {
        cachedSnapshot?.let { return it }
        return synchronized(this) {
            cachedSnapshot ?: nodes.values.sortedWith(NODE_ORDER).also {
                cachedSnapshot = it
            }
        }
    }

    fun hitTest(x: Int, y: Int): HitTestResult {
        val candidates = snapshot().filter { it.screenBounds.contains(x, y) }
        return HitTestResult(x = x, y = y, candidates = candidates)
    }

    private companion object {
        private const val FLAG_AUTO_SEMANTICS = 1
        private const val FLAG_AUTO_LAYOUT = 1 shl 2
        private const val FLAG_WINDOW_ROOT = 1 shl 3

        // 排序口径：点谁跳谁。含点的候选里，面积最小的即视觉上最贴近用户点中位置的元素，
        // 必须先于"是否可点击"参与排序——否则非 clickable 的小元素（如自定义导航条标签）
        // 会被它背后可点击的大容器（列表行）遮蔽，点浮窗标签却跳到后面的 item
        val NODE_ORDER = compareByDescending<LocatorNode> { it.windowLayer }
            .thenBy { it.isWindowRoot() }
            .thenByDescending { it.sourceId != null }
            .thenBy { it.screenBounds.area() }
            .thenByDescending { it.isInteractiveSemantics() }
            .thenByDescending { it.isSemantics() }
            .thenBy { it.isLayoutFallback() }
            .thenByDescending { it.zIndex }
            .thenBy { it.id }

        private fun LocatorNode.isSemantics(): Boolean {
            return flags and FLAG_AUTO_SEMANTICS != 0
        }

        private fun LocatorNode.isLayoutFallback(): Boolean {
            return flags and FLAG_AUTO_LAYOUT != 0
        }

        private fun LocatorNode.isWindowRoot(): Boolean {
            return flags and FLAG_WINDOW_ROOT != 0
        }

        private fun LocatorNode.isInteractiveSemantics(): Boolean {
            return isSemantics() &&
                !isLayoutFallback() &&
                !isWindowRoot() &&
                (role != null || text != null || semanticsTag != null)
        }
    }
}
