package dev.codelocator.runtime.model

data class LocatorNode(
    val id: Long,
    val screenBounds: Rect,
    val zIndex: Float = 0f,
    val sourceId: Long? = null,
    val semanticsTag: String? = null,
    val text: String? = null,
    val role: String? = null,
    val composableName: String? = null,
    val parentId: Long? = null,
    val childIds: List<Long> = emptyList(),
    val flags: Int = 0,
    val windowId: Int = 0,
    val windowTitle: String? = null,
    val windowLayer: Int = 0,
)
