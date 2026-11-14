package dev.codelocator.studio.model

data class HitCandidate(
    val id: Long,
    val sourceId: Long?,
    /**
     * 外层调用点的 sourceId：用于在候选列表中生成"调用点"跳转条目；
     * 置于 sourceId 之后并给默认值，避免影响既有构造点
     */
    val boundarySourceId: Long? = null,
    val location: SourceLocation?,
    val label: String,
    val bounds: Bounds,
    val zIndex: Float,
    val semanticsTag: String?,
    val text: String?,
    val role: String?,
    val composableName: String?,
    val flags: Int,
    val windowId: Int,
    val windowTitle: String?,
    val windowLayer: Int,
)
