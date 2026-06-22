package dev.codelocator.runtime.android

data class LocatorNodeState(
    val id: Long,
    val sourceId: Long? = null,
    val semanticsTag: String? = null,
    val text: String? = null,
    val role: String? = null,
    val composableName: String? = null,
)
