package dev.codelocator.runtime.model

data class LocatorNode(
    val id: Long,
    val screenBounds: Rect,
    val zIndex: Float = 0f,
    val sourceId: Long? = null,
    /**
     * 外层调用点的 sourceId：插桩期记录"该 Composable 被外层用户代码调用的位置"，
     * 用于 Studio 侧跳转到实际调用点；与 sourceId 同一命名空间，可为空
     */
    val boundarySourceId: Long? = null,
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
