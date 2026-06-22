package dev.codelocator.studio.model

data class HitCandidate(
    val id: Long,
    val sourceId: Long?,
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
